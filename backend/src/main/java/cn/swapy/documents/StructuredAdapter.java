package cn.swapy.documents;

import cn.swapy.*;
import java.io.StringReader;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.tomlj.Toml;

public final class StructuredAdapter {
    private record Span(int start,int end,String value,String quote) {}
    public Documents.Content read(String raw,String ext) {
        if(ext.equals("html")||ext.equals("htm"))return htmlRead(raw);
        var protectedTexts=new ArrayList<String>();var spans=spans(raw,ext,protectedTexts);
        return new Documents.Content(spans.stream().map(Span::value).toList(),protectedTexts);
    }
    public String rewrite(String raw,String ext,UnaryOperator<String> transform) {
        if(ext.equals("html")||ext.equals("htm"))return htmlRewrite(raw,transform);
        var spans=spans(raw,ext,new ArrayList<>());var output=new StringBuilder(raw);
        for(int i=spans.size()-1;i>=0;i--) {
            var span=spans.get(i);String next=transform.apply(span.value());if(next.equals(span.value()))continue;
            String encoded=switch(span.quote()) {case "comment","plain"->next;case "single"->"'"+next.replace("'","''")+"'";default->Json.write(next);};
            output.replace(span.start(),span.end(),encoded);
        }
        String result=output.toString();spans(result,ext,new ArrayList<>());return result;
    }
    private List<Span> spans(String raw,String ext,List<String> protectedTexts) {
        List<Span> values=switch(ext){case "yaml","yml"->yaml(raw,protectedTexts);case "toml"->toml(raw,protectedTexts);case "ini"->ini(raw,protectedTexts);default->throw new Problem("UNSUPPORTED_FORMAT",ext);};
        var result=new ArrayList<>(values);var comments=Pattern.compile("(?m)(?:^|[ \\t])([#;][^\\r\\n]*)").matcher(raw);
        while(comments.find()) {
            int start=comments.start(1),end=comments.end(1);
            if(values.stream().noneMatch(s->start<s.end()&&s.start()<end))result.add(new Span(start,end,comments.group(1),"comment"));
        }
        result.sort(Comparator.comparingInt(Span::start));return result;
    }
    private List<Span> yaml(String raw,List<String> protectedTexts) {
        try {
            var options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(50);options.setCodePointLimit(100*1024*1024);
            var yaml=new Yaml(new SafeConstructor(options));yaml.loadAll(raw).forEach(v->{});
            var result=new ArrayList<Span>();var visited=Collections.newSetFromMap(new IdentityHashMap<org.yaml.snakeyaml.nodes.Node,Boolean>());
            for(var root:yaml.composeAll(new StringReader(raw)))walkYaml(root,false,result,protectedTexts,visited);
            return result;
        }catch(Problem e){throw e;}catch(Exception e){throw new Problem("INVALID_YAML",e.getMessage());}
    }
    private void walkYaml(org.yaml.snakeyaml.nodes.Node node,boolean key,List<Span> result,List<String> protectedTexts,Set<org.yaml.snakeyaml.nodes.Node> visited) {
        if(!visited.add(node))return;
        if(node instanceof ScalarNode scalar) {
            if(key||!Tag.STR.equals(scalar.getTag())){protectedTexts.add(scalar.getValue());return;}
            String quote=switch(scalar.getScalarStyle()){case SINGLE_QUOTED->"single";case PLAIN->"double";default->"double";};
            result.add(new Span(node.getStartMark().getIndex(),node.getEndMark().getIndex(),scalar.getValue(),quote));
        }else if(node instanceof MappingNode mapping)for(var tuple:mapping.getValue()){walkYaml(tuple.getKeyNode(),true,result,protectedTexts,visited);walkYaml(tuple.getValueNode(),false,result,protectedTexts,visited);}
        else if(node instanceof SequenceNode sequence)for(var value:sequence.getValue())walkYaml(value,false,result,protectedTexts,visited);
    }
    private List<Span> toml(String raw,List<String> protectedTexts) {
        var parsed=Toml.parse(raw);if(parsed.hasErrors())throw new Problem("INVALID_TOML",parsed.errors().toString());
        var result=new ArrayList<Span>();boolean value=false;int depth=0;
        for(int i=0;i<raw.length();) {
            char c=raw.charAt(i);
            if(c=='#'){int end=raw.indexOf('\n',i);i=end<0?raw.length():end;continue;}
            if(c=='='&&depth==0){value=true;i++;continue;}
            if((c=='['||c=='{')&&value)depth++;if((c==']'||c=='}')&&value)depth--;
            if(c=='\n'&&depth==0)value=false;
            if(c!='\''&&c!='"'){i++;continue;}
            int start=i;char quote=c;boolean triple=raw.startsWith(String.valueOf(c).repeat(3),i);i+=triple?3:1;
            while(i<raw.length()) {
                if(quote=='"'&&raw.charAt(i)=='\\'){i+=2;continue;}
                if(triple?raw.startsWith(String.valueOf(quote).repeat(3),i):raw.charAt(i)==quote){i+=triple?3:1;break;}i++;
            }
            String token=raw.substring(start,i);var scalar=Toml.parse("value="+token);String decoded=scalar.getString("value");
            int after=i;while(after<raw.length()&&Character.isWhitespace(raw.charAt(after))&&raw.charAt(after)!='\n')after++;
            boolean inlineKey=after<raw.length()&&raw.charAt(after)=='=';
            if(value&&!inlineKey)result.add(new Span(start,i,decoded,"double"));else protectedTexts.add(decoded);
        }
        return result;
    }
    private List<Span> ini(String raw,List<String> protectedTexts) {
        var result=new ArrayList<Span>();var matcher=Pattern.compile("(?m)^([^\\r\\n]*)").matcher(raw);
        while(matcher.find()) {
            String line=matcher.group(1),trim=line.trim();if(trim.isEmpty()||trim.startsWith("#")||trim.startsWith(";")||trim.startsWith("[")){protectedTexts.add(line);continue;}
            int equals=line.indexOf('=');if(equals<0)equals=line.indexOf(':');if(equals<0)throw new Problem("INVALID_INI");
            protectedTexts.add(line.substring(0,equals));int begin=equals+1;while(begin<line.length()&&Character.isWhitespace(line.charAt(begin)))begin++;
            int end=line.length();char quote=0;
            for(int i=begin;i<line.length();i++){char c=line.charAt(i);if(quote!=0){if(c==quote)quote=0;}else if(c=='"'||c=='\'')quote=c;else if((c=='#'||c==';')&&(i==begin||Character.isWhitespace(line.charAt(i-1)))){end=i;break;}}
            while(end>begin&&Character.isWhitespace(line.charAt(end-1)))end--;String token=line.substring(begin,end);if(token.matches("(?i)(true|false|null|[+-]?[0-9]+(?:\\.[0-9]+)?)")){protectedTexts.add(token);continue;}
            boolean quoted=token.length()>1&&(token.charAt(0)=='"'||token.charAt(0)=='\'')&&token.charAt(token.length()-1)==token.charAt(0);
            result.add(new Span(matcher.start(1)+begin,matcher.start(1)+end,quoted?token.substring(1,token.length()-1):token,quoted?"double":"plain"));
        }
        return result;
    }
    private boolean htmlEditable(org.jsoup.nodes.Node node){for(var parent=node.parent();parent!=null;parent=parent.parent())if(parent.nodeName().equals("script")||parent.nodeName().equals("style"))return false;return true;}
    private static final Set<String> ATTRIBUTES=Set.of("title","alt","placeholder","href","src","value","aria-label");
    private Documents.Content htmlRead(String raw) {
        var doc=Jsoup.parse(raw);var texts=new ArrayList<String>();var protectedTexts=new ArrayList<String>();
        for(var node:doc.nodeStream().toList()) {
            if(node instanceof TextNode text){(htmlEditable(node)?texts:protectedTexts).add(text.getWholeText());}
            else if(node instanceof DataNode data)protectedTexts.add(data.getWholeData());
            else if(node instanceof Comment comment)texts.add(comment.getData());
            else if(node instanceof Element element) {protectedTexts.add(element.tagName());for(var a:element.attributes()){protectedTexts.add(a.getKey());(ATTRIBUTES.contains(a.getKey())?texts:protectedTexts).add(a.getValue());}}
        }
        return new Documents.Content(texts,protectedTexts);
    }
    private String htmlRewrite(String raw,UnaryOperator<String> transform) {
        var doc=Jsoup.parse(raw);doc.outputSettings().prettyPrint(false);
        for(var node:doc.nodeStream().toList()) {
            if(node instanceof TextNode text&&htmlEditable(node))text.text(transform.apply(text.getWholeText()));
            else if(node instanceof Comment comment)comment.setData(transform.apply(comment.getData()));
            else if(node instanceof Element element)for(var a:element.attributes())if(ATTRIBUTES.contains(a.getKey()))a.setValue(transform.apply(a.getValue()));
        }
        return doc.outerHtml();
    }
}
