package cn.swapy.documents;

import cn.swapy.Problem;
import com.github.difflib.DiffUtils;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.zip.*;

public final class OoxmlAdapter {
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    public Documents.Content read(Path input) {
        var texts=new ArrayList<String>();
        for(var part:parts(input).entrySet())if(textPart(part.getKey())) {
            var doc=parse(part.getValue());for(var group:groups(doc))texts.add(group.stream().map(Node::getTextContent).reduce("",String::concat));
        }
        return new Documents.Content(texts,List.of());
    }
    public void rewrite(Path input,Path output,UnaryOperator<String> transform) {
        var parts=parts(input);
        for(var part:parts.entrySet())if(textPart(part.getKey())) {
            var doc=parse(part.getValue());
            for(var group:groups(doc))patch(group,transform);
            part.setValue(serialize(doc));
        }
        try(var out=new ZipOutputStream(Files.newOutputStream(output,StandardOpenOption.CREATE_NEW))) {
            for(var part:parts.entrySet()){var entry=new ZipEntry(part.getKey());out.putNextEntry(entry);out.write(part.getValue());out.closeEntry();}
        }catch(Exception e){throw new Problem("DOCX_WRITE_FAILED",e.getMessage());}
        read(output);
    }
    private boolean textPart(String name) {return name.startsWith("word/")&&name.endsWith(".xml")&&!name.contains("_rels/");}
    private List<List<Node>> groups(Document doc) {
        var result=new ArrayList<List<Node>>();var paragraphs=doc.getElementsByTagNameNS(W,"p");
        for(int i=0;i<paragraphs.getLength();i++) {
            Node p=paragraphs.item(i);var nodes=new ArrayList<Node>();collect(p,p,nodes,result);if(!nodes.isEmpty())result.add(new ArrayList<>(nodes));
        }
        return result;
    }
    private void collect(Node node,Node paragraph,List<Node> current,List<List<Node>> result) {
        if(node!=paragraph&&W.equals(node.getNamespaceURI())&&"p".equals(node.getLocalName()))return;
        if(W.equals(node.getNamespaceURI())&&Set.of("tab","br","cr","fldChar").contains(node.getLocalName())){if(!current.isEmpty()){result.add(new ArrayList<>(current));current.clear();}return;}
        if(W.equals(node.getNamespaceURI())&&"t".equals(node.getLocalName())){current.add(node);return;}
        for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())collect(child,paragraph,current,result);
    }
    private void patch(List<Node> nodes,UnaryOperator<String> transform) {
        String original=nodes.stream().map(Node::getTextContent).reduce("",String::concat),updated=transform.apply(original);if(original.equals(updated))return;
        var before=original.chars().mapToObj(c->(char)c).toList();var after=updated.chars().mapToObj(c->(char)c).toList();
        var deltas=new ArrayList<>(DiffUtils.diff(before,after).getDeltas());deltas.sort(Comparator.comparingInt((com.github.difflib.patch.AbstractDelta<Character> d)->d.getSource().getPosition()).reversed());
        int[] starts=new int[nodes.size()],ends=new int[nodes.size()];int offset=0;
        for(int i=0;i<nodes.size();i++){starts[i]=offset;offset+=nodes.get(i).getTextContent().length();ends[i]=offset;}
        for(var delta:deltas) {
            int start=delta.getSource().getPosition(),end=start+delta.getSource().size();int first=nodes.size()-1,last=nodes.size()-1;
            for(int i=0;i<nodes.size();i++)if(start<ends[i]){first=i;break;}
            for(int i=first;i<nodes.size();i++)if(end<=ends[i]){last=i;break;}
            String replacement=delta.getTarget().getLines().stream().map(String::valueOf).reduce("",String::concat);
            String prefix=nodes.get(first).getTextContent().substring(0,start-starts[first]);String suffix=nodes.get(last).getTextContent().substring(end-starts[last]);
            nodes.get(first).setTextContent(prefix+replacement+(first==last?suffix:""));
            for(int i=first+1;i<=last;i++)nodes.get(i).setTextContent(i==last?suffix:"");
            ((Element)nodes.get(first)).setAttributeNS("http://www.w3.org/XML/1998/namespace","xml:space","preserve");
        }
    }
    private Map<String,byte[]> parts(Path input) {
        var parts=new LinkedHashMap<String,byte[]>();long total=0;
        try(var zip=new ZipInputStream(Files.newInputStream(input))) {
            ZipEntry entry;while((entry=zip.getNextEntry())!=null) {
                if(entry.isDirectory())continue;String name=entry.getName();
                if(name.startsWith("/")||name.contains("\\")||Arrays.asList(name.split("/")).contains("..")||parts.containsKey(name)||parts.size()>10000)throw new Problem("INVALID_DOCX_ARCHIVE");
                var out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
                while((n=zip.read(buffer))!=-1){total+=n;if(total>256L*1024*1024)throw new Problem("DOCX_SIZE_LIMIT");out.write(buffer,0,n);}
                parts.put(name,out.toByteArray());
            }
        }catch(Problem e){throw e;}catch(Exception e){throw new Problem("DOCX_READ_FAILED",e.getMessage());}
        if(!parts.containsKey("word/document.xml"))throw new Problem("INVALID_DOCX");return parts;
    }
    private Document parse(byte[] bytes) {
        try {
            var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setXIncludeAware(false);f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        }catch(Exception e){throw new Problem("INVALID_DOCX_XML",e.getMessage());}
    }
    private byte[] serialize(Document doc) {
        try {var f=TransformerFactory.newInstance();f.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD","");f.setAttribute("http://javax.xml.XMLConstants/property/accessExternalStylesheet","");var out=new ByteArrayOutputStream();f.newTransformer().transform(new DOMSource(doc),new StreamResult(out));return out.toByteArray();}
        catch(Exception e){throw new Problem("DOCX_WRITE_FAILED",e.getMessage());}
    }
}
