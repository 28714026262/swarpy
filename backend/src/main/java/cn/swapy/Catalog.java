package cn.swapy;

import java.sql.Connection;
import java.util.*;

public final class Catalog {
    public record Word(String id,String word,String pos,String form,String role,String source,String status,int introducedVersion,String family) {
        public boolean active() { return "启用".equals(status); }
        public Word deprecated() { return new Word(id,word,pos,form,role,source,"弃用",introducedVersion,family); }
    }
    public record Library(String format,int formatVersion,String lineage,int version,List<Word> sensitive,List<Word> pool) {}
    private final Store db;
    public Catalog(Store db) { this.db=db; }
    public Library snapshot() { return db.read(this::snapshot); }
    private Library snapshot(Connection c) throws Exception {
        var versions=Store.rows(c,"SELECT payload FROM catalog_releases ORDER BY version DESC LIMIT 1");
        return versions.isEmpty() ? new Library("swapy-library",1,"",0,List.of(),List.of())
                : Json.read((String)versions.get(0).get("payload"),Library.class);
    }
    public Library importLibrary(Library incoming) {
        return db.write(c -> {validateAndImport(c,incoming);return incoming;});
    }
    public void validate(Library incoming) {db.write(c->{var savepoint=c.setSavepoint();validateAndImport(c,incoming);c.rollback(savepoint);return null;});}
    public void validateHistory(Library previous,Library incoming) {
        if(!previous.lineage().equals(incoming.lineage()))throw new Problem("CATALOG_LINEAGE_CONFLICT");
        for(String kind:List.of("sensitive","pool")) {
            var next=new HashMap<String,Word>();(kind.equals("sensitive")?incoming.sensitive():incoming.pool()).forEach(w->next.put(w.id(),w));
            for(Word old:kind.equals("sensitive")?previous.sensitive():previous.pool()) {
                Word value=next.get(old.id());if(value==null)throw new Problem("HISTORY_MISSING");if(!sameIdentity(old,value))throw new Problem("ID_CONFLICT",old.id());if(!old.active()&&value.active())throw new Problem("DEPRECATED_WORD_REACTIVATION");
            }
        }
    }
    public Library importReleases(List<Library> releases) {return db.write(c->{for(var release:releases)validateAndImport(c,release);return snapshot(c);});}
    private void validateAndImport(Connection c,Library incoming) throws Exception {
        if(incoming==null || !"swapy-library".equals(incoming.format()) || incoming.formatVersion()!=1 || incoming.version()<0
                || incoming.lineage()==null || incoming.sensitive()==null || incoming.pool()==null || incoming.lineage().isBlank()&&!(incoming.version()==0&&incoming.sensitive().isEmpty()&&incoming.pool().isEmpty()))
            throw new Problem("INVALID_LIBRARY");
        Library old=snapshot(c);
        if(incoming.version()<old.version()) throw new Problem("LIBRARY_DOWNGRADE");
        if(old.version()>0 && !old.lineage().equals(incoming.lineage())) throw new Problem("CATALOG_LINEAGE_CONFLICT");
        var existing=new HashMap<String,Word>();
        old.sensitive().forEach(w->existing.put(w.id(),w)); old.pool().forEach(w->existing.put(w.id(),w));
        var seen=new HashSet<String>(); var enabled=new HashSet<String>();
        for(String kind:List.of("sensitive","pool")) {
            List<Word> list=kind.equals("sensitive")?incoming.sensitive():incoming.pool();
            for(Word w:list) {
                if(w.id()==null || !w.id().matches("[A-Za-z][A-Za-z0-9_-]{1,127}") || w.word()==null || w.word().isBlank()
                        || w.word().length()>512 || w.pos()==null || w.form()==null || w.role()==null || w.source()==null
                        || !List.of("启用","弃用").contains(w.status()) || w.introducedVersion()<1 || w.introducedVersion()>incoming.version())
                    throw new Problem("INVALID_WORD");
                if(!seen.add(w.id())) throw new Problem("ID_CONFLICT",w.id());
                if(w.active() && !enabled.add(kind+":"+w.word().toLowerCase(Locale.ROOT))) throw new Problem("AMBIGUOUS_WORD",w.word());
                Word previous=existing.get(w.id());
                if(previous!=null) {
                    if(!sameIdentity(previous,w) || old.sensitive().contains(previous)!=kind.equals("sensitive")) throw new Problem("ID_CONFLICT",w.id());
                    if(!previous.active() && w.active()) throw new Problem("DEPRECATED_WORD_REACTIVATION",w.id());
                } else if(old.version()>0 && w.introducedVersion()<=old.version()) throw new Problem("INVALID_HISTORY",w.id());
            }
        }
        if(!seen.containsAll(existing.keySet())) throw new Problem("HISTORY_MISSING");
        if(incoming.version()==old.version()) {
            if(!canonical(old).equals(canonical(incoming))) throw new Problem("VERSION_CONFLICT");
            return;
        }
        for(String kind:List.of("sensitive","pool")) for(Word w:kind.equals("sensitive")?incoming.sensitive():incoming.pool()) {
            Word previous=existing.get(w.id());
            Store.execute(c,"INSERT INTO word_entries(id,kind,payload) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload",w.id(),kind,Json.write(w));
            if(previous!=null && previous.active() && !w.active()) Store.execute(c,"INSERT INTO word_events(entry_id,version,event) VALUES(?,?,'deprecated')",w.id(),incoming.version());
        }
        Store.execute(c,"INSERT INTO catalog_releases(version,lineage,payload) VALUES(?,?,?)",incoming.version(),incoming.lineage(),Json.write(incoming));
    }
    private String canonical(Library v) {
        return Json.write(new Library(v.format(),v.formatVersion(),v.lineage(),v.version(),
                v.sensitive().stream().sorted(Comparator.comparing(Word::id)).toList(),v.pool().stream().sorted(Comparator.comparing(Word::id)).toList()));
    }
    private boolean sameIdentity(Word a,Word b) {
        return Objects.equals(a.word(),b.word()) && Objects.equals(a.pos(),b.pos()) && Objects.equals(a.form(),b.form())
                && Objects.equals(a.role(),b.role()) && Objects.equals(a.source(),b.source())
                && a.introducedVersion()==b.introducedVersion() && Objects.equals(a.family(),b.family());
    }
    public Library add(String kind,String text,String pos,String form,String role,String family) {
        return db.write(c -> {
            if(!List.of("sensitive","pool").contains(kind)) throw new Problem("INVALID_LIBRARY_KIND");
            Library old=snapshot(c); int version=old.version()+1;
            var sensitive=new ArrayList<>(old.sensitive());var pool=new ArrayList<>(old.pool());
            Word w=new Word((kind.equals("sensitive")?"S_":"R_")+UUID.randomUUID(),text,pos,form,role,"用户手填","启用",version,family==null?"":family);
            (kind.equals("sensitive")?sensitive:pool).add(w);
            Library next=new Library("swapy-library",1,old.lineage().isBlank()?UUID.randomUUID().toString():old.lineage(),version,sensitive,pool);
            validateAndImport(c,next);return next;
        });
    }
    public Library deprecate(String id) {
        return db.write(c->{
            Library old=snapshot(c);
            if(java.util.stream.Stream.concat(old.sensitive().stream(),old.pool().stream()).noneMatch(w->w.id().equals(id))) throw new Problem("WORD_NOT_FOUND");
            Library next=new Library("swapy-library",1,old.lineage(),old.version()+1,
                    old.sensitive().stream().map(w->w.id().equals(id)?w.deprecated():w).toList(),
                    old.pool().stream().map(w->w.id().equals(id)?w.deprecated():w).toList());
            validateAndImport(c,next);return next;
        });
    }
}
