package cn.swapy;

import cn.swapy.documents.ArchiveScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;

class ArchiveTest {
    @TempDir Path temp;
    byte[] zip(String name,byte[] data) throws Exception {var buffer=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(buffer)){zip.putNextEntry(new ZipEntry(name));zip.write(data);zip.closeEntry();}return buffer.toByteArray();}
    @Test void nestedArchiveExtractsContentWithFullMemberChain() throws Exception {
        Path input=temp.resolve("outer.zip");Files.write(input,zip("inner.zip",zip("说明.md","北京".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        var result=new ArchiveScanner().extract(input,temp.resolve("work"));
        assertThat(result).hasSize(1);assertThat(result.get(0).member()).isEqualTo("inner.zip!/说明.md");
        assertThat(Files.readString(result.get(0).physical())).isEqualTo("北京");assertThat(result.get(0).error()).isEmpty();
    }
    @Test void unsafeMemberPathIsReportedAndNeverEscapesStaging() throws Exception {
        Path input=temp.resolve("outer.zip");Files.write(input,zip("../escape.txt","北京".getBytes()));
        var result=new ArchiveScanner().extract(input,temp.resolve("work"));
        assertThat(result).hasSize(1);assertThat(result.get(0).error()).contains("ARCHIVE_PATH_REJECTED");
        assertThat(temp.resolve("escape.txt")).doesNotExist();
    }
    @Test void remainingTaskBudgetAppliesBeforeExtractingAnotherArchive() throws Exception {
        Path first=temp.resolve("first.zip"),second=temp.resolve("second.zip");Files.write(first,zip("a.txt",new byte[]{1,2,3}));Files.write(second,zip("b.txt",new byte[]{4,5,6}));
        var scanner=new ArchiveScanner();var one=scanner.extract(first,temp.resolve("one"),4);var two=scanner.extract(second,temp.resolve("two"),4-one.expandedBytes());
        assertThat(one.entries().get(0).error()).isEmpty();assertThat(two.entries()).anyMatch(e->e.error().contains("ARCHIVE_LIMIT"));
    }
}
