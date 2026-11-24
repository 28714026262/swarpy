package cn.swapy;

import cn.swapy.documents.Documents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class DocumentTest {
    @TempDir Path temp;
    @Test void markdownRewritesCodeAndUrlsPreservingDelimiters() throws Exception {
        var docs=new Documents();Path input=temp.resolve("input.md"),out=temp.resolve("out.md");
        Files.writeString(input,"# 北京\r\n`北京` [北京](https://北京.test)\r\n");
        docs.rewrite(input,out,t->t.replace("北京","林舟"));
        assertThat(Files.readString(out)).isEqualTo("# 林舟\r\n`林舟` [林舟](https://林舟.test)\r\n");
    }
    @Test void jsonProtectsKeysAndNonStringValues() throws Exception {
        var docs=new Documents();Path input=temp.resolve("input.json"),out=temp.resolve("out.json");
        Files.writeString(input,"{\"北京\":\"北京\",\"n\":12,\"flag\":true,\"nil\":null}");
        docs.rewrite(input,out,t->t.replace("北京","林舟"));
        assertThat(Files.readString(out)).isEqualTo("{\"北京\":\"林舟\",\"n\":12,\"flag\":true,\"nil\":null}");
        assertThat(docs.read(input).allText()).contains("北京");
    }
    @Test void xmlProtectsNamesAndRejectsExternalEntities() throws Exception {
        var docs=new Documents();Path input=temp.resolve("input.xml"),out=temp.resolve("out.xml");
        Files.writeString(input,"<北京 北京=\"北京\">北京</北京>");
        docs.rewrite(input,out,t->t.replace("北京","林舟"));
        assertThat(Files.readString(out)).contains("<北京 北京=\"林舟\">林舟</北京>");
        Files.writeString(input,"<!DOCTYPE x [<!ENTITY xxe SYSTEM \"file:///secret\">]><x>&xxe;</x>");
        assertThatThrownBy(()->docs.read(input)).hasMessageContaining("XML");
    }
    @Test void docxMatchesAcrossRunsAndPreservesOtherRunStyles() throws Exception {
        Path input=temp.resolve("input.docx"),output=temp.resolve("output.docx");
        try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            var p=doc.createParagraph();p.createRun().setText("北");p.createRun().setText("京");var untouched=p.createRun();untouched.setBold(true);untouched.setText("保留");
            try(var out=Files.newOutputStream(input)){doc.write(out);}
        }
        var docs=new Documents();docs.rewrite(input,output,s->s.replace("北京","林舟"));
        try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument(Files.newInputStream(output))) {
            assertThat(doc.getParagraphs().get(0).getText()).isEqualTo("林舟保留");
            assertThat(doc.getParagraphs().get(0).getRuns()).anySatisfy(r->{assertThat(r.text()).isEqualTo("保留");assertThat(r.isBold()).isTrue();});
        }
    }
    @Test void sqlChangesColumnsButKeepsTableNamesAndKeywords() throws Exception {
        Path input=temp.resolve("input.sql"),output=temp.resolve("output.sql");
        Files.writeString(input,"CREATE TABLE 北京 (北京 VARCHAR(32));\nSELECT 北京 FROM 北京 WHERE 北京 = '北京'; -- 北京\n");
        var docs=new Documents();docs.rewrite(input,output,s->s.replace("北京","林舟"));
        String actual=Files.readString(output);
        assertThat(actual).contains("TABLE 北京", "FROM 北京", "SELECT 林舟", "WHERE 林舟", "'林舟'", "-- 林舟");
        assertThat(actual).doesNotContain("SELECT 北京");
    }
    @Test void structuredConfigurationOnlyRewritesStringValuesAndComments() throws Exception {
        var docs=new Documents();
        for(String ext:java.util.List.of("yaml","toml","ini")) {
            Path input=temp.resolve("input."+ext),output=temp.resolve("output."+ext);
            String separator=ext.equals("yaml")?":":"=";
            Files.writeString(input,"\"北京\""+separator+" \"北京\"\nnumber"+separator+" 12\n# 北京\n");
            docs.rewrite(input,output,s->s.replace("北京","林舟"));
            assertThat(Files.readString(output)).contains("\"北京\""+separator+" \"林舟\"","number"+separator+" 12","# 林舟");
        }
    }
    @Test void htmlRewritesVisibleTextAndAttributesButProtectsScriptAndNames() throws Exception {
        Path input=temp.resolve("input.html"),output=temp.resolve("output.html");
        Files.writeString(input,"<div title=\"北京\">北京</div><script>北京</script>");
        new Documents().rewrite(input,output,s->s.replace("北京","林舟"));
        assertThat(Files.readString(output)).contains("title=\"林舟\"",">林舟</div>","<script>北京</script>");
    }
    @Test void pdfActuallyRemovesOriginalTextInsteadOfCoveringIt() throws Exception {
        Path input=temp.resolve("input.pdf"),output=temp.resolve("output.pdf");
        try(var doc=new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page=new org.apache.pdfbox.pdmodel.PDPage();doc.addPage(page);
            try(var stream=new org.apache.pdfbox.pdmodel.PDPageContentStream(doc,page)) {
                stream.beginText();stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.COURIER),12);stream.newLineAtOffset(60,700);stream.showText("Acme");stream.endText();
            }
            doc.save(input.toFile());
        }
        var docs=new Documents();docs.rewrite(input,output,s->s.replace("Acme","Nora"));
        try(var doc=org.apache.pdfbox.Loader.loadPDF(output.toFile())) {
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(doc)).contains("Nora").doesNotContain("Acme");
        }
    }
    @Test void allCreateTableDefinitionsInOneScriptUseTheSameFieldMapping() throws Exception {
        Path input=temp.resolve("multi.sql"),output=temp.resolve("multi-out.sql");
        Files.writeString(input,"CREATE TABLE a (北京 TEXT); CREATE TABLE b (北京 TEXT); SELECT a.北京,b.北京 FROM a JOIN b ON a.北京=b.北京;");
        new Documents().rewrite(input,output,s->s.replace("北京","林舟"));
        assertThat(Files.readString(output)).doesNotContain("北京").contains("a (林舟", "b (林舟", "a.林舟");
    }
    @Test void oldDocIsAlwaysRejectedWithoutWritingAnyOutput() throws Exception {
        Path input=temp.resolve("old.DOC"),output=temp.resolve("out.doc");
        Files.writeString(input,"北京");
        var docs=new Documents();
        assertThatThrownBy(()->docs.read(input)).hasMessageContaining("LEGACY_DOC_UNSUPPORTED");
        assertThatThrownBy(()->docs.rewrite(input,output,s->s.replace("北京","林舟"))).hasMessageContaining("LEGACY_DOC_UNSUPPORTED");
        assertThat(output).doesNotExist();
    }
    @Test void legacyFormatsReportMissingConversionEngineInsteadOfCopyingAsSuccess() throws Exception {
        Path input=temp.resolve("input.rtf"),output=temp.resolve("output.rtf");Files.writeString(input,"{\\rtf1 example}");
        var adapter=new cn.swapy.documents.LegacyOfficeAdapter(temp.resolve("missing-soffice"));
        assertThatThrownBy(()->adapter.rewrite(input,output,s->s.replace("example","changed"))).hasMessageContaining("OFFICE_ENGINE_UNAVAILABLE");
        assertThat(output).doesNotExist();
    }
    @Test void pdfShorterReplacementCanRestoreIntoItsReservedSpace() throws Exception {
        Path input=temp.resolve("wide.pdf"),out=temp.resolve("narrow.pdf"),restored=temp.resolve("restored.pdf");
        try(var doc=new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page=new org.apache.pdfbox.pdmodel.PDPage();doc.addPage(page);
            try(var stream=new org.apache.pdfbox.pdmodel.PDPageContentStream(doc,page)){stream.beginText();stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);stream.newLineAtOffset(60,700);stream.showText("WWWW");stream.endText();}doc.save(input.toFile());
        }
        var docs=new Documents();docs.rewrite(input,out,s->s.replace("WWWW","iiii"));docs.rewrite(out,restored,s->s.replace("iiii","WWWW"));
        assertThat(docs.read(restored).allText()).contains("WWWW");
    }
    @Test void csvReplacementContainingDelimiterRemainsOneField() throws Exception {
        Path input=temp.resolve("input.csv"),output=temp.resolve("output.csv");Files.writeString(input,"北京,normal\r\n");
        new Documents().rewrite(input,output,s->s.replace("北京","林,舟"));
        assertThat(Files.readString(output)).isEqualTo("\"林,舟\",normal\r\n");
    }
    @Test void docxTabSeparatesWordsInsteadOfCreatingAFalseHit() throws Exception {
        Path input=temp.resolve("tab.docx"),output=temp.resolve("tab-out.docx");
        try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument()){var p=doc.createParagraph();p.createRun().setText("北");p.createRun().addTab();p.createRun().setText("京");try(var out=Files.newOutputStream(input)){doc.write(out);}}
        new Documents().rewrite(input,output,s->s.replace("北京","林舟"));
        try(var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument(Files.newInputStream(output))){assertThat(doc.getParagraphs().get(0).getText()).isEqualTo("北\t京");}
    }
    @Test void sqlUniqueConstraintReferencesAreUpdatedWithDefinitions() throws Exception {
        Path input=temp.resolve("constraint.sql"),output=temp.resolve("constraint-out.sql");Files.writeString(input,"CREATE TABLE t (secret int, CONSTRAINT u UNIQUE(secret));");
        new Documents().rewrite(input,output,s->s.replace("secret","other"));assertThat(Files.readString(output)).isEqualTo("CREATE TABLE t (other int, CONSTRAINT u UNIQUE(other));");
    }
    @Test void unhitHtmlIsCopiedByteForByte() throws Exception {
        Path input=temp.resolve("unhit.html"),output=temp.resolve("unhit-out.html");Files.writeString(input,"<P title='unchanged'>plain</P>\r\n");
        new Documents().rewrite(input,output,s->s.replace("北京","林舟"));assertThat(Files.readAllBytes(output)).isEqualTo(Files.readAllBytes(input));
    }
}
