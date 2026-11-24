package cn.swapy.documents;

import cn.swapy.Json;
import cn.swapy.Problem;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;

public class Documents {
    public record Content(List<String> texts,List<String> protectedTexts) {
        public String allText() {return String.join("\n",java.util.stream.Stream.concat(texts.stream(),protectedTexts.stream()).toList());}
    }
    private record Codec(String text,Charset charset,byte[] bom) {}
    private record Span(int start,int end,String text,String encoding) {}
    private static final Set<String> PLAIN=Set.of("txt","log","md");
    private final CsvAdapter csv=new CsvAdapter();
    private final OoxmlAdapter ooxml=new OoxmlAdapter();
    private final SqlAdapter sql=new SqlAdapter();
    private final StructuredAdapter structured=new StructuredAdapter();
    private final PdfAdapter pdf=new PdfAdapter();
    private final LegacyOfficeAdapter legacy=new LegacyOfficeAdapter();
    private static final Set<String> LEGACY=Set.of("doc","rtf","odt");
    private static final Set<String> STRUCTURED=Set.of("yaml","yml","toml","ini","html","htm");
    public static String extension(Path p) {String n=p.getFileName().toString();int dot=n.lastIndexOf('.');return dot<0?"":n.substring(dot+1).toLowerCase(Locale.ROOT);}
    public boolean supported(Path p) {return PLAIN.contains(extension(p)) || STRUCTURED.contains(extension(p)) || LEGACY.contains(extension(p)) || Set.of("csv","json","xml","docx","sql","pdf").contains(extension(p));}
    public Content read(Path path) {
        rejectOldDoc(path);
        String ext=extension(path);if(!supported(path)) throw new Problem("UNSUPPORTED_FORMAT",ext);
        if(ext.equals("docx"))return ooxml.read(path);
        if(ext.equals("pdf"))return pdf.read(path);
        if(LEGACY.contains(ext))return legacy.read(path);
        Codec codec=decode(path);String raw=codec.text();
        if(ext.equals("csv"))return csv.read(raw);
        if(STRUCTURED.contains(ext))return structured.read(raw,ext);
        if(ext.equals("sql"))return sql.read(raw);
        if(PLAIN.contains(ext)) return new Content(List.of(raw),List.of());
        var protectedTexts=new ArrayList<String>();var spans=spans(raw,ext,protectedTexts);
        return new Content(spans.stream().map(Span::text).toList(),List.copyOf(protectedTexts));
    }
    public void rewrite(Path input,Path output,UnaryOperator<String> transform) {
        rejectOldDoc(input);
        String ext=extension(input);if(!supported(input)) throw new Problem("UNSUPPORTED_FORMAT",ext);
        var original=read(input);
        if(original.texts().stream().allMatch(text->text.equals(transform.apply(text)))){try{Files.copy(input,output);return;}catch(Exception e){throw new Problem("WRITE_FAILED",e.getMessage());}}
        if(ext.equals("docx")){ooxml.rewrite(input,output,transform);return;}
        if(ext.equals("pdf")){pdf.rewrite(input,output,transform);return;}
        if(LEGACY.contains(ext)){legacy.rewrite(input,output,transform);return;}
        Codec codec=decode(input);String raw=codec.text();String result;
        if(ext.equals("csv"))result=csv.rewrite(raw,transform);
        else if(STRUCTURED.contains(ext))result=structured.rewrite(raw,ext,transform);
        else if(ext.equals("sql"))result=sql.rewrite(raw,transform);
        else if(PLAIN.contains(ext)) result=transform.apply(raw);
        else {
            var spans=spans(raw,ext,new ArrayList<>());var out=new StringBuilder(raw);
            for(int i=spans.size()-1;i>=0;i--) {
                Span s=spans.get(i);String updated=transform.apply(s.text());
                if(updated.equals(s.text())) continue;
                String encoded=s.encoding().equals("json")?Json.write(updated):escapeXml(updated);
                out.replace(s.start(),s.end(),encoded);
            }
            result=out.toString();spans(result,ext,new ArrayList<>());
        }
        try {
            CharsetEncoder encoder=codec.charset().newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer buffer=encoder.encode(java.nio.CharBuffer.wrap(result));byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);
            try(OutputStream out=Files.newOutputStream(output,StandardOpenOption.CREATE_NEW)) {out.write(codec.bom());out.write(bytes);}
        } catch(Exception e) {throw new Problem("WRITE_FAILED",e.getMessage());}
    }
    private static void rejectOldDoc(Path path) {
        if(extension(path).equals("doc"))throw new Problem("LEGACY_DOC_UNSUPPORTED","不支持旧版 DOC，请先另存为 DOCX 后重试");
    }
    private List<Span> spans(String text,String ext,List<String> protectedTexts) {
        return ext.equals("json")?jsonSpans(text,protectedTexts):xmlSpans(text,protectedTexts);
    }
    private List<Span> jsonSpans(String raw,List<String> protectedTexts) {
        try {Json.MAPPER.readTree(raw);} catch(Exception e) {throw new Problem("INVALID_JSON",e.getMessage());}
        var result=new ArrayList<Span>();
        for(int i=0;i<raw.length();i++) if(raw.charAt(i)=='"') {
            int start=i;i++;
            while(i<raw.length()) {if(raw.charAt(i)=='\\') i+=2;else if(raw.charAt(i)=='"') break;else i++;}
            int end=i+1;String value=Json.read(raw.substring(start,end),String.class);int after=end;
            while(after<raw.length() && Character.isWhitespace(raw.charAt(after))) after++;
            if(after<raw.length() && raw.charAt(after)==':') protectedTexts.add(value);
            else result.add(new Span(start,end,value,"json"));
        }
        return result;
    }
    private List<Span> xmlSpans(String raw,List<String> protectedTexts) {
        validateXml(raw);var result=new ArrayList<Span>();int cursor=0;
        while(cursor<raw.length()) {
            if(raw.startsWith("<!--",cursor)) {int end=raw.indexOf("-->",cursor)+3;protectedTexts.add(raw.substring(cursor,end));cursor=end;continue;}
            if(raw.startsWith("<![CDATA[",cursor)) {
                int end=raw.indexOf("]]>",cursor+9);String value=raw.substring(cursor+9,end);
                result.add(new Span(cursor,end+3,value,"xml"));cursor=end+3;continue;
            }
            if(raw.charAt(cursor)=='<') {
                int end=cursor+1;char quote=0;
                while(end<raw.length()) {char c=raw.charAt(end);if(quote!=0) {if(c==quote) quote=0;}else if(c=='\''||c=='"') quote=c;else if(c=='>') break;end++;}
                String tag=raw.substring(cursor,end+1);protectedTexts.add(tag.replaceAll("(['\"])[\\s\\S]*?\\1",""));
                var matcher=Pattern.compile("(['\"])([\\s\\S]*?)\\1").matcher(tag);
                while(matcher.find()) result.add(new Span(cursor+matcher.start(2),cursor+matcher.end(2),unescapeXml(matcher.group(2)),"xml"));
                cursor=end+1;
            } else {
                int end=raw.indexOf('<',cursor);if(end<0)end=raw.length();
                result.add(new Span(cursor,end,unescapeXml(raw.substring(cursor,end)),"xml"));cursor=end;
            }
        }
        return result;
    }
    private void validateXml(String raw) {
        try {
            var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
            var builder=factory.newDocumentBuilder();builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){
                @Override public void error(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}
                @Override public void fatalError(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}
            });
            builder.parse(new org.xml.sax.InputSource(new StringReader(raw)));
        } catch(Exception e) {throw new Problem("INVALID_XML",e.getMessage());}
    }
    private static String escapeXml(String v) {return v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    private static String unescapeXml(String value) {
        var matcher=Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);").matcher(value);var result=new StringBuilder();
        while(matcher.find()) {
            String t=matcher.group(1);String replacement=switch(t) {case "amp"->"&";case "lt"->"<";case "gt"->">";case "quot"->"\"";case "apos"->"'";default->new String(Character.toChars(t.startsWith("#x")?Integer.parseInt(t.substring(2),16):Integer.parseInt(t.substring(1))));};
            matcher.appendReplacement(result,java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);return result.toString();
    }
    private Codec decode(Path path) {
        try {
            if(Files.size(path)>100L*1024*1024) throw new Problem("FILE_SIZE_LIMIT");
            byte[] bytes=Files.readAllBytes(path);Charset charset=StandardCharsets.UTF_8;int skip=0;
            if(bytes.length>=3 && bytes[0]==(byte)0xef && bytes[1]==(byte)0xbb && bytes[2]==(byte)0xbf) skip=3;
            else if(bytes.length>=2 && bytes[0]==(byte)0xff && bytes[1]==(byte)0xfe) {charset=StandardCharsets.UTF_16LE;skip=2;}
            else if(bytes.length>=2 && bytes[0]==(byte)0xfe && bytes[1]==(byte)0xff) {charset=StandardCharsets.UTF_16BE;skip=2;}
            String text;
            try {text=charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,skip,bytes.length-skip)).toString();}
            catch(CharacterCodingException e) {
                if(skip!=0) throw e;
                charset=Charset.forName("GB18030");text=charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            }
            if(text.indexOf('\0')>=0) throw new Problem("INVALID_ENCODING");
            return new Codec(text,charset,Arrays.copyOf(bytes,skip));
        } catch(Problem e){throw e;}catch(Exception e){throw new Problem("READ_FAILED",e.getMessage());}
    }
}
