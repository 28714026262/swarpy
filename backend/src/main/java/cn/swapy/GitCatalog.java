package cn.swapy;

import cn.swapy.packages.PackageService;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class GitCatalog {
    private final Store db;private final Catalog catalog;private final Path repository;public final boolean publisher;
    public GitCatalog(Store db,Catalog catalog,Path repository){this(db,catalog,repository,true);}
    public GitCatalog(Store db,Catalog catalog,Path repository,boolean publisher){this.db=db;this.catalog=catalog;this.repository=repository;this.publisher=publisher;}
    private void configured(){if(repository==null||!Files.isDirectory(repository.resolve(".git")))throw new Problem("CATALOG_REPO_NOT_CONFIGURED","请在启动配置中指定词库Git仓库");}
    public synchronized Catalog.Library publish(Catalog.Library library) {
        return publish(library,false);
    }
    public synchronized Catalog.Library importLibrary(Catalog.Library library) {
        if(repository==null||!publisher||library.version()==0)return catalog.importLibrary(library);
        return publish(library,true);
    }
    private Catalog.Library publish(Catalog.Library library,boolean checkpoint) {
        if(!publisher)throw new Problem("CATALOG_READ_ONLY");
        configured();var current=catalog.snapshot();String tag="swapy-v"+library.version();
        if(current.version()==library.version()&&!Json.write(current).equals(Json.write(library)))throw new Problem("VERSION_CONFLICT");
        catalog.validate(library);
        if(!git("tag","--list",tag).isBlank()) {
            var existing=Json.read(git("show",tag+":catalog.json"),Catalog.Library.class);
            if(!Json.write(existing).equals(Json.write(library)))throw new Problem("VERSION_CONFLICT");
            catalog.importLibrary(library);clearPending();return library;
        }
        var priorTags=versions();int parentVersion=priorTags.stream().mapToInt(Integer::intValue).max().orElse(0);
        if(parentVersion>=library.version()||!checkpoint&&library.version()!=current.version()+1)throw new Problem("INVALID_RELEASE_SEQUENCE");
        if(parentVersion>0)catalog.validateHistory(Json.read(git("show","swapy-v"+parentVersion+":catalog.json"),Catalog.Library.class),library);
        String pending=db.read(c->{var rows=Store.rows(c,"SELECT value FROM metadata WHERE key='catalog_pending'");return rows.isEmpty()?"":(String)rows.get(0).get("value");});
        String dirty=git("status","--porcelain");
        if(!dirty.isBlank()&&(pending.isBlank()||!pending.equals(Json.write(library))))throw new Problem("CATALOG_REPO_DIRTY");
        for(String line:dirty.split("\\R"))if(!line.isBlank()&&!line.endsWith("catalog.json")&&!line.endsWith("release.json"))throw new Problem("CATALOG_REPO_DIRTY");
        db.write(c->{Store.execute(c,"INSERT INTO metadata(key,value) VALUES('catalog_pending',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",Json.write(library));return null;});
        db.write(c->{Store.execute(c,"INSERT INTO metadata(key,value) VALUES('catalog_pending_checkpoint',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",String.valueOf(checkpoint));return null;});
        if(!checkpoint&&parentVersion!=current.version())throw new Problem("CATALOG_GIT_HISTORY_MISSING","请先导入或同步完整词库快照以补齐Git发布锚点");
        String parent=parentVersion==0?"":PackageService.sha(git("show","swapy-v"+parentVersion+":release.json").getBytes(StandardCharsets.UTF_8));
        String content=Json.write(library);var manifest=Map.of("format","swapy-catalog-release","version",library.version(),"lineage",library.lineage(),"parentHash",parent,"parentVersion",parentVersion,"checkpoint",checkpoint,"contentHash",PackageService.sha(content.getBytes(StandardCharsets.UTF_8)));
        write("catalog.json",content);write("release.json",Json.write(manifest));git("add","--","catalog.json","release.json");
        String head=git("status","--porcelain");if(!head.isBlank())git("-c","user.name=Swapy","-c","user.email=swapy@localhost","commit","-m","Publish Swapy catalog v"+library.version());
        git("-c","user.name=Swapy","-c","user.email=swapy@localhost","tag","-a",tag,"-m","Swapy catalog v"+library.version());
        catalog.importLibrary(library);clearPending();return library;
    }
    public synchronized Catalog.Library sync() {
        configured();if(!git("remote").isBlank())git("fetch","--tags","--no-recurse-submodules");
        var versions=versions();int latest=versions.stream().mapToInt(Integer::intValue).max().orElse(0);
        var current=catalog.snapshot();if(latest<current.version())throw new Problem("LIBRARY_DOWNGRADE");
        var releases=new ArrayList<Catalog.Library>();String parent="";int previousVersion=0;
        for(int version:versions) {
            String tag="swapy-v"+version,content=git("show",tag+":catalog.json"),manifestText=git("show",tag+":release.json");
            var manifest=Json.object(manifestText);var library=Json.read(content,Catalog.Library.class);
            int declaredParent=((Number)manifest.getOrDefault("parentVersion",version-1)).intValue();boolean checkpoint=Boolean.TRUE.equals(manifest.get("checkpoint"));
            if(!Objects.equals(manifest.get("format"),"swapy-catalog-release")||((Number)manifest.get("version")).intValue()!=version||library.version()!=version||declaredParent!=previousVersion||!checkpoint&&version!=previousVersion+1
                    ||!library.lineage().equals(manifest.get("lineage"))||!parent.equals(manifest.get("parentHash"))||!PackageService.sha(content.getBytes(StandardCharsets.UTF_8)).equals(manifest.get("contentHash")))throw new Problem("INVALID_RELEASE_CHAIN");
            parent=PackageService.sha(manifestText.getBytes(StandardCharsets.UTF_8));
            previousVersion=version;
            if(version==current.version()&&!Json.write(library).equals(Json.write(current)))throw new Problem("VERSION_CONFLICT");
            if(version>current.version())releases.add(library);
        }
        return releases.isEmpty()?current:catalog.importReleases(releases);
    }
    public synchronized Catalog.Library add(String kind,String text,String pos,String form,String role,String family) {
        if(!publisher)throw new Problem("CATALOG_READ_ONLY","当前实例只同步已发布词库");
        if(repository==null)return catalog.add(kind,text,pos,form,role,family);
        var old=catalog.snapshot();int version=old.version()+1;if(!Set.of("sensitive","pool").contains(kind))throw new Problem("INVALID_LIBRARY_KIND");
        var sensitive=new ArrayList<>(old.sensitive());var pool=new ArrayList<>(old.pool());
        (kind.equals("sensitive")?sensitive:pool).add(new Catalog.Word((kind.equals("sensitive")?"S_":"R_")+UUID.randomUUID(),text,pos,form,role,"用户手填","启用",version,Objects.toString(family,"")));
        return publish(new Catalog.Library("swapy-library",1,old.lineage().isBlank()?UUID.randomUUID().toString():old.lineage(),version,sensitive,pool));
    }
    public synchronized Catalog.Library deprecate(String id) {
        if(!publisher)throw new Problem("CATALOG_READ_ONLY");
        if(repository==null)return catalog.deprecate(id);var old=catalog.snapshot();
        if(java.util.stream.Stream.concat(old.sensitive().stream(),old.pool().stream()).noneMatch(w->w.id().equals(id)))throw new Problem("WORD_NOT_FOUND");
        return publish(new Catalog.Library("swapy-library",1,old.lineage(),old.version()+1,old.sensitive().stream().map(w->w.id().equals(id)?w.deprecated():w).toList(),old.pool().stream().map(w->w.id().equals(id)?w.deprecated():w).toList()));
    }
    public void retryPending(){if(!publisher)return;String pending=db.read(c->{var rows=Store.rows(c,"SELECT value FROM metadata WHERE key='catalog_pending'");return rows.isEmpty()?"":(String)rows.get(0).get("value");});boolean checkpoint=db.read(c->{var rows=Store.rows(c,"SELECT value FROM metadata WHERE key='catalog_pending_checkpoint'");return !rows.isEmpty()&&Boolean.parseBoolean((String)rows.get(0).get("value"));});if(!pending.isBlank())publish(Json.read(pending,Catalog.Library.class),checkpoint);}
    private void clearPending(){db.write(c->{Store.execute(c,"DELETE FROM metadata WHERE key IN ('catalog_pending','catalog_pending_checkpoint')");return null;});}
    private List<Integer> versions(){return Arrays.stream(git("tag","--list","swapy-v*").split("\\R")).filter(s->s.matches("swapy-v[1-9][0-9]{0,8}")).map(s->Integer.parseInt(s.substring(7))).sorted().toList();}
    private void write(String name,String value) {
        try {Path staged=Files.createTempFile(repository,".swapy-",".json");Files.writeString(staged,value);Files.move(staged,repository.resolve(name),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(Exception e){throw new Problem("CATALOG_PUBLISH_FAILED",e.getMessage());}
    }
    private String git(String... arguments) {
        var command=new ArrayList<String>(List.of("git","-C",repository.toString()));command.addAll(Arrays.asList(arguments));
        Process process=null;
        try {
            var builder=new ProcessBuilder(command).redirectErrorStream(true);builder.environment().put("GIT_TERMINAL_PROMPT","0");process=builder.start();var running=process;
            var output=CompletableFuture.supplyAsync(()->{try{return running.getInputStream().readNBytes(100*1024*1024+1);}catch(Exception e){throw new CompletionException(e);}});
            if(!process.waitFor(120,TimeUnit.SECONDS)){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();throw new Problem("GIT_TIMEOUT");}
            byte[] bytes=output.get(5,TimeUnit.SECONDS);if(bytes.length>100*1024*1024)throw new Problem("CATALOG_SIZE_LIMIT");
            if(process.exitValue()!=0)throw new Problem("GIT_FAILED",new String(bytes,StandardCharsets.UTF_8).replaceAll("(https?://)[^\\s/@]+@","$1***@"));
            return new String(bytes,StandardCharsets.UTF_8);
        }catch(Problem e){throw e;}catch(Exception e){if(process!=null)process.destroyForcibly();throw new Problem("GIT_FAILED",e.getMessage());}
    }
}
