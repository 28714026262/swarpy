package cn.swapy;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

public final class LibraryCsv {
    private static final List<String> HEADER=List.of("kind","id","word","pos","form","role","source","status","introducedVersion","family");
    private LibraryCsv() {}
    public static byte[] encode(Catalog.Library library) {
        StringBuilder out=new StringBuilder();row(out,List.of("swapy-library-csv","1",library.lineage(),String.valueOf(library.version())));row(out,HEADER);
        for(String kind:List.of("sensitive","pool"))for(var w:kind.equals("pool")?library.pool():library.sensitive())
            row(out,List.of(kind,w.id(),w.word(),w.pos(),w.form(),w.role(),w.source(),w.status(),String.valueOf(w.introducedVersion()),w.family()==null?"\\N":w.family().startsWith("\\")?"\\"+w.family():w.family()));
        if(java.util.stream.Stream.concat(library.sensitive().stream(),library.pool().stream()).anyMatch(w->w.source().startsWith("jieba:")))notice(out,"jieba-MIT","/jieba-LICENSE.txt");
        if(java.util.stream.Stream.concat(library.sensitive().stream(),library.pool().stream()).anyMatch(w->w.source().equals("WordNet:3.0")))notice(out,"WordNet-3.0","/wordnet-LICENSE.txt");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
    public static Catalog.Library decode(byte[] data) {
        try {
            if(data.length>64*1024*1024)throw new IllegalArgumentException();
            String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
            if(raw.startsWith("\uFEFF"))raw=raw.substring(1);
            var rows=parse(raw);if(rows.size()<2||rows.get(0).size()!=4||!rows.get(0).get(0).equals("swapy-library-csv")||!rows.get(0).get(1).equals("1")||!rows.get(1).equals(HEADER))throw new IllegalArgumentException();
            var sensitive=new ArrayList<Catalog.Word>();var pool=new ArrayList<Catalog.Word>();
            for(int i=2;i<rows.size();i++) {
                var r=rows.get(i);if(r.size()==1&&r.get(0).isEmpty())continue;
                if(r.size()==3&&r.get(0).equals("license")&&Set.of("jieba-MIT","WordNet-3.0").contains(r.get(1))&&!r.get(2).isBlank())continue;
                if(r.size()!=10||!Set.of("pool","sensitive").contains(r.get(0)))throw new IllegalArgumentException();
                String family=r.get(9).equals("\\N")?null:r.get(9).startsWith("\\\\")?r.get(9).substring(1):r.get(9);
                var word=new Catalog.Word(r.get(1),r.get(2),r.get(3),r.get(4),r.get(5),r.get(6),r.get(7),Integer.parseInt(r.get(8)),family);
                (r.get(0).equals("pool")?pool:sensitive).add(word);
            }
            return new Catalog.Library("swapy-library",1,rows.get(0).get(2),Integer.parseInt(rows.get(0).get(3)),sensitive,pool);
        }catch(Exception e){throw new Problem("INVALID_LIBRARY_CSV","词库 CSV 内容或编码不正确");}
    }
    private static void row(StringBuilder out,List<String> values) {
        for(int i=0;i<values.size();i++){if(i>0)out.append(',');String s=values.get(i);boolean quote=s.indexOf(',')>=0||s.indexOf('"')>=0||s.indexOf('\r')>=0||s.indexOf('\n')>=0;
            if(quote)out.append('"').append(s.replace("\"","\"\"")).append('"');else out.append(s);}
        out.append('\n');
    }
    private static void notice(StringBuilder out,String id,String resource) {
        try(var input=LibraryCsv.class.getResourceAsStream(resource)){
            if(input==null)throw new Problem("LICENSE_RESOURCE_MISSING");row(out,List.of("license",id,new String(input.readAllBytes(),StandardCharsets.UTF_8)));
        }catch(java.io.IOException e){throw new Problem("LICENSE_RESOURCE_MISSING");}
    }
    private static List<List<String>> parse(String raw) {
        var rows=new ArrayList<List<String>>();int i=0;
        while(i<raw.length()) {
            var row=new ArrayList<String>();
            while(true) {
                StringBuilder field=new StringBuilder();
                if(i<raw.length()&&raw.charAt(i)=='"') {
                    i++;boolean closed=false;
                    while(i<raw.length()){char c=raw.charAt(i++);if(c=='"'){if(i<raw.length()&&raw.charAt(i)=='"'){field.append('"');i++;}else{closed=true;break;}}else field.append(c);}
                    if(!closed||i<raw.length()&&raw.charAt(i)!=','&&raw.charAt(i)!='\r'&&raw.charAt(i)!='\n')throw new IllegalArgumentException();
                }else while(i<raw.length()&&raw.charAt(i)!=','&&raw.charAt(i)!='\r'&&raw.charAt(i)!='\n'){char c=raw.charAt(i++);if(c=='"')throw new IllegalArgumentException();field.append(c);}
                if(row.size()>10)throw new IllegalArgumentException();row.add(field.toString());
                if(i==raw.length())break;
                char end=raw.charAt(i++);if(end==',')continue;
                if(end=='\r'&&i<raw.length()&&raw.charAt(i)=='\n')i++;break;
            }
            rows.add(row);if(rows.size()>500002)throw new IllegalArgumentException();
        }
        return rows;
    }
}
