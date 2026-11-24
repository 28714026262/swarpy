package cn.swapy.documents;

import cn.swapy.Problem;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.UnaryOperator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.text.PDFTextStripper;

public final class PdfAdapter {
    public Documents.Content read(Path path) {
        try(var doc=Loader.loadPDF(path.toFile())) {
            if(doc.isEncrypted())throw new Problem("ENCRYPTED_DOCUMENT");
            var texts=new ArrayList<String>();var stripper=new PDFTextStripper();
            for(int i=1;i<=doc.getNumberOfPages();i++){stripper.setStartPage(i);stripper.setEndPage(i);texts.add(stripper.getText(doc));}
            if(texts.stream().allMatch(String::isBlank))throw new Problem("PDF_NO_TEXT_LAYER");
            return new Documents.Content(texts,List.of());
        }catch(Problem e){throw e;}catch(Exception e){throw new Problem("PDF_READ_FAILED",e.getMessage());}
    }
    public void rewrite(Path source,Path output,UnaryOperator<String> transform) {
        var original=read(source);
        try(var doc=Loader.loadPDF(source.toFile())) {
            for(var page:doc.getPages()) {
                var tokens=new PDFStreamParser(page).parse();var rewritten=new ArrayList<Object>();var args=new ArrayList<Object>();
                PDFont font=null;var fonts=new ArrayList<PDFont>();
                for(Object token:tokens) {
                    if(!(token instanceof Operator operator)){args.add(token);continue;}
                    switch(operator.getName()) {
                        case "q" -> fonts.add(font);
                        case "Q" -> {if(!fonts.isEmpty())font=fonts.remove(fonts.size()-1);}
                        case "Tf" -> {if(args.size()!=2 || !(args.get(0) instanceof COSName name))throw new Problem("UNSUPPORTED_PDF_TEXT_LAYOUT");font=page.getResources().getFont(name);}
                        case "Tj" -> {
                            if(args.size()!=1 || !(args.get(0) instanceof COSString value))throw new Problem("UNSUPPORTED_PDF_TEXT_LAYOUT");
                            COSArray adjusted=rewriteString(value,font,transform,0);args.clear();args.add(adjusted);operator=Operator.getOperator("TJ");
                        }
                        case "TJ" -> {
                            if(args.size()!=1 || !(args.get(0) instanceof COSArray array))throw new Problem("UNSUPPORTED_PDF_TEXT_LAYOUT");
                            var adjusted=new COSArray();for(int i=0;i<array.size();i++){var value=array.getObject(i);if(value instanceof COSString string){float reserved=i+1<array.size()&&array.getObject(i+1) instanceof COSNumber n?Math.max(0,-n.floatValue()):0;adjusted.addAll(rewriteString(string,font,transform,reserved));}else adjusted.add(value);}
                            args.clear();args.add(adjusted);
                        }
                        case "'","\"" -> {if(!args.isEmpty()&&args.get(args.size()-1) instanceof COSString value&&!decode(value,font).equals(transform.apply(decode(value,font))))throw new Problem("UNSUPPORTED_PDF_TEXT_OPERATOR");}
                        default -> {}
                    }
                    rewritten.addAll(args);rewritten.add(operator);args.clear();
                }
                if(!args.isEmpty())throw new Problem("UNSUPPORTED_PDF_TEXT_LAYOUT");
                var stream=new PDStream(doc);try(var bytes=stream.createOutputStream(COSName.FLATE_DECODE)){new ContentStreamWriter(bytes).writeTokens(rewritten);}page.setContents(stream);
            }
            if(Files.exists(output))throw new Problem("OUTPUT_PATH_CONFLICT");doc.save(output.toFile());
        }catch(Problem e){throw e;}catch(Exception e){throw new Problem("PDF_WRITE_FAILED",e.getMessage());}
        if(!read(output).texts().equals(original.texts().stream().map(transform).toList()))throw new Problem("UNSUPPORTED_PDF_TEXT_LAYOUT","文本跨操作符或字体／布局无法安全写回");
    }
    private COSArray rewriteString(COSString value,PDFont font,UnaryOperator<String> transform,float reserved) throws IOException {
        String original=decode(value,font),updated=transform.apply(original);var array=new COSArray();
        if(original.equals(updated)){array.add(value);return array;}
        if(font==null||font.isVertical())throw new Problem("UNSUPPORTED_PDF_FONT");
        try {
            float before=font.getStringWidth(original),after=font.getStringWidth(updated);
            if(after>before+reserved+0.01f)throw new Problem("PDF_TEXT_DOES_NOT_FIT");
            array.add(new COSString(font.encode(updated)));if(Math.abs(after-before)>0.01f)array.add(new COSFloat(after-before));return array;
        }catch(IllegalArgumentException e){throw new Problem("UNSUPPORTED_PDF_FONT",e.getMessage());}
    }
    private String decode(COSString value,PDFont font) throws IOException {
        if(font==null)throw new Problem("UNSUPPORTED_PDF_FONT");
        var result=new StringBuilder();try(var in=new ByteArrayInputStream(value.getBytes())){while(in.available()>0){String text=font.toUnicode(font.readCode(in));if(text==null)throw new Problem("PDF_UNMAPPED_GLYPH");result.append(text);}}return result.toString();
    }
}
