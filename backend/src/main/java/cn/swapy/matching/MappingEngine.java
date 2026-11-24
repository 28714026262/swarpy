package cn.swapy.matching;

import cn.swapy.Catalog;
import cn.swapy.Catalog.Word;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Pattern;

public final class MappingEngine {
    public record Mapping(String sourceId,String targetId,String original,String replacement,String form,String role,int ordinal,int introducedVersion) {
        Word source() {return new Word(sourceId,original,"",form,role,"","启用",introducedVersion,"");}
        Word target() {return new Word(sourceId,replacement,"",form,role,"","启用",introducedVersion,"");}
    }
    public record Plan(List<Mapping> mappings,Map<String,String> skipped,List<Word> sources) {}
    private record Span(int start,int end,String sourceId,String original) {}
    private record Render(String text,List<Span> spans) {}
    private final SecureRandom random=new SecureRandom();

    public Plan plan(Iterable<String> originals,Catalog.Library library) {return plan(originals,library,List.of());}
    public Plan plan(Iterable<String> originals,Catalog.Library library,List<Mapping> existing) {
        var existingWords=existing.stream().map(m->m.original().toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        List<Word> sources=new ArrayList<>(library.sensitive().stream().filter(Word::active).filter(w->!existingWords.contains(w.word().toLowerCase(Locale.ROOT))).toList());
        for(Mapping m:existing) if(sources.stream().noneMatch(w->w.id().equals(m.sourceId()))) sources.add(m.source());
        var needed=new LinkedHashSet<String>();
        for(String text:originals) Matcher.find(text,sources).forEach(h->needed.add(h.word().id()));
        var mappings=new ArrayList<>(existing);var skipped=new LinkedHashMap<String,String>();
        for(Word source:sources.stream().sorted(Comparator.comparingInt((Word w)->w.word().length()).reversed().thenComparing(Word::id)).toList()) {
            if(!needed.contains(source.id()) || mappings.stream().anyMatch(m->m.sourceId().equals(source.id()))) continue;
            if(source.pos().isBlank() || source.role().isBlank() || source.form().isBlank()) {skipped.put(source.id(),"ATTRIBUTE_MISSING");continue;}
            var candidates=new ArrayList<>(library.pool().stream().filter(Word::active).filter(w->compatible(source,w)).toList());
            Collections.shuffle(candidates,random);boolean accepted=false;
            for(Word target:candidates) {
                if(stream(originals).anyMatch(t->Matcher.pattern(target).matcher(t).find())) continue;
                String normalized=target.word().toLowerCase(Locale.ROOT);
                if(mappings.stream().anyMatch(m->normalized.contains(m.replacement().toLowerCase(Locale.ROOT)) || m.replacement().toLowerCase(Locale.ROOT).contains(normalized))) continue;
                Mapping mapping=new Mapping(source.id(),target.id(),source.word(),target.word(),source.form(),source.role(),mappings.size()+1,Math.max(source.introducedVersion(),target.introducedVersion()));
                var tentative=new ArrayList<>(mappings);tentative.add(mapping);
                if(!stream(originals).allMatch(t->reversible(t,tentative,sources))) continue;
                mappings.add(mapping);accepted=true;break;
            }
            if(!accepted) skipped.put(source.id(),"CANDIDATE_EXHAUSTED");
        }
        return new Plan(List.copyOf(mappings),Map.copyOf(skipped),List.copyOf(sources));
    }
    private static java.util.stream.Stream<String> stream(Iterable<String> texts){return java.util.stream.StreamSupport.stream(texts.spliterator(),false);}
    private boolean compatible(Word a,Word b) {
        return a.pos().equals(b.pos()) && a.role().equals(b.role()) && a.form().equals(b.form())
                && (a.family()==null || a.family().isBlank() || !a.family().equals(b.family()))
                && (!Matcher.english(a) || asciiLetterCount(a.word())==asciiLetterCount(b.word()));
    }
    private static long asciiLetterCount(String text) {return text.chars().filter(c->c>='A'&&c<='Z'||c>='a'&&c<='z').count();}
    private static boolean letter(char c) {return c>='A'&&c<='Z'||c>='a'&&c<='z';}
    private String transferCase(String fromSurface,String toBase) {
        var caseBits=new ArrayList<Boolean>();
        for(char c:fromSurface.toCharArray()) if(letter(c)) caseBits.add(c>='A'&&c<='Z');
        StringBuilder out=new StringBuilder();int index=0;
        for(char c:toBase.toCharArray()) {
            if(letter(c)) {
                if(index>=caseBits.size()) throw new IllegalArgumentException("Non-bijective case transform");
                out.append(caseBits.get(index++)?Character.toUpperCase(c):Character.toLowerCase(c));
            } else out.append(c);
        }
        if(index!=caseBits.size()) throw new IllegalArgumentException("Non-bijective case transform");
        return out.toString();
    }
    private String forward(Mapping m,String surface) {return Matcher.english(m.source())?transferCase(surface,m.replacement()):m.replacement();}
    private String inverse(Mapping m,String surface) {return Matcher.english(m.target())?transferCase(surface,m.original()):m.original();}
    private Render render(String text,List<Mapping> mappings,List<Word> sources,String mode,String register) {
        var byId=new HashMap<String,Mapping>();mappings.forEach(m->byId.put(m.sourceId(),m));
        StringBuilder out=new StringBuilder();var spans=new ArrayList<Span>();int cursor=0;
        for(var hit:Matcher.find(text,sources)) {
            Mapping m=byId.get(hit.word().id());if(m==null) continue;
            out.append(text,cursor,hit.start());int start=out.length();
            out.append(forward(m,hit.surface()));
            if("A".equals(mode)) out.append(marker(register,m.ordinal()));
            spans.add(new Span(start,out.length(),m.sourceId(),hit.surface()));cursor=hit.end();
        }
        out.append(text,cursor,text.length());return new Render(out.toString(),spans);
    }
    private boolean reversible(String text,List<Mapping> mappings,List<Word> sources) {
        Render rendered=render(text,mappings,sources,"B","");
        List<Matcher.Hit> all=Matcher.all(rendered.text(),mappings.stream().map(Mapping::target).toList());
        if(all.size()!=rendered.spans().size()) return false;
        var byId=new HashMap<String,Mapping>();mappings.forEach(m->byId.put(m.sourceId(),m));
        for(var hit:all) if(rendered.spans().stream().noneMatch(s->s.start()==hit.start() && s.end()==hit.end()
                && s.sourceId().equals(hit.word().id()) && s.original().equals(inverse(byId.get(s.sourceId()),hit.surface())))) return false;
        return true;
    }
    public String redact(String text,Plan plan,String mode,String register) {return render(text,plan.mappings(),plan.sources(),mode,register).text();}
    public String redact(String text,List<Mapping> mappings,String mode,String register) {return render(text,mappings,mappings.stream().map(Mapping::source).toList(),mode,register).text();}
    private static String marker(String register,int ordinal) {return "〔sw:"+register+":"+ordinal+"〕";}
    public String restore(String text,List<Mapping> mappings,String register) {
        var candidates=new ArrayList<ReverseHit>();
        for(Mapping m:mappings) {
            var matcher=Matcher.pattern(m.target()).matcher(text);int offset=0;
            while(offset<=text.length() && matcher.find(offset)) {
                int end=matcher.end();String marker=marker(register,m.ordinal());
                if(text.startsWith(marker,end)) end+=marker.length();
                candidates.add(new ReverseHit(matcher.start(),end,inverse(m,matcher.group()),end>matcher.end()));
                offset=matcher.start()+1;
            }
        }
        candidates.sort(Comparator.comparing(ReverseHit::marked).reversed().thenComparing(Comparator.comparingInt((ReverseHit h)->h.end()-h.start()).reversed()).thenComparingInt(ReverseHit::start));
        var selected=new ArrayList<ReverseHit>();
        for(var h:candidates) if(selected.stream().noneMatch(s->h.start()<s.end() && s.start()<h.end())) selected.add(h);
        selected.sort(Comparator.comparingInt(ReverseHit::start));
        StringBuilder out=new StringBuilder();int cursor=0;
        for(var h:selected) {out.append(text,cursor,h.start()).append(h.value());cursor=h.end();}
        return out.append(text,cursor,text.length()).toString();
    }
    private record ReverseHit(int start,int end,String value,boolean marked) {}
}
