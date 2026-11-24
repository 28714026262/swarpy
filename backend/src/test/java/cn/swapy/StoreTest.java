package cn.swapy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class StoreTest {
    @TempDir Path temp;
    Catalog.Word word(String id, String value, String status) {
        return new Catalog.Word(id, value, "专有名词", "中文词", "人名", "用户手填", status, 1, "");
    }
    @Test void committedCatalogSurvivesReopeningAndRetainsDeprecatedWords() {
        try (Store db = new Store(temp)) {
            var catalog = new Catalog(db);
            catalog.importLibrary(new Catalog.Library("swapy-library", 1, "test", 1,
                    List.of(word("S_1", "张明", "启用")), List.of(word("R_1", "林舟", "启用"))));
            catalog.importLibrary(new Catalog.Library("swapy-library", 1, "test", 2,
                    List.of(word("S_1", "张明", "弃用")), List.of(word("R_1", "林舟", "启用"))));
        }
        try (Store db = new Store(temp)) {
            var snapshot = new Catalog(db).snapshot();
            assertThat(snapshot.version()).isEqualTo(2);
            assertThat(snapshot.sensitive()).extracting(Catalog.Word::status).containsExactly("弃用");
            assertThat(snapshot.pool()).extracting(Catalog.Word::word).containsExactly("林舟");
        }
    }
    @Test void importConflictRollsBackWholeLibraryAndCannotDowngradeOrResurrect() {
        try (Store db = new Store(temp)) {
            var catalog = new Catalog(db);
            var first = new Catalog.Library("swapy-library",1,"test",1,List.of(word("S_1","张明","弃用")),List.of());
            catalog.importLibrary(first);
            assertThatThrownBy(() -> catalog.importLibrary(new Catalog.Library("swapy-library",1,"test",2,
                    List.of(new Catalog.Word("S_2","新词","专有名词","中文词","人名","用户手填","启用",2,""), word("S_1","不同内容","弃用")),List.of())))
                    .hasMessageContaining("ID_CONFLICT");
            assertThat(catalog.snapshot().sensitive()).hasSize(1);
            assertThat(catalog.snapshot().version()).isEqualTo(1);
            assertThatThrownBy(() -> catalog.importLibrary(new Catalog.Library("swapy-library",1,"test",2,
                    List.of(word("S_1","张明","启用")),List.of()))).hasMessageContaining("DEPRECATED");
            assertThatThrownBy(() -> catalog.importLibrary(new Catalog.Library("swapy-library",1,"test",0,
                    List.of(),List.of()))).hasMessageContaining("DOWNGRADE");
        }
    }
    @Test void pathsRejectTraversalAndResolveUnicodeFiles() throws Exception {
        Path root = Files.createDirectory(temp.resolve("资料"));
        Files.writeString(root.resolve("说明.md"),"文字");
        var paths = new Roots(List.of(root), temp.resolve("outputs"));
        assertThat(paths.resolve("root0","说明.md")).isEqualTo(root.resolve("说明.md").toRealPath());
        assertThatThrownBy(() -> paths.resolve("root0","../secret")).hasMessageContaining("PATH_OUTSIDE_ROOT");
        assertThatThrownBy(() -> paths.resolve("root0",root.resolve("说明.md").toString())).hasMessageContaining("PATH_OUTSIDE_ROOT");
    }
    @Test void writeFailureDoesNotCommitPartialRows() {
        try (Store db = new Store(temp)) {
            assertThatThrownBy(() -> db.write(c -> {
                c.createStatement().executeUpdate("INSERT INTO metadata(key,value) VALUES('test','value')");
                throw new IllegalArgumentException("reject");
            })).hasMessageContaining("reject");
            int count = db.read(c -> {var r=c.createStatement().executeQuery("SELECT count(*) FROM metadata WHERE key='test'");return r.getInt(1);});
            assertThat(count).isZero();
        }
    }
    @Test void outputNamesAreReservedBeforeWritingAndCaseCollisionsAreDisambiguated() {
        try(Store db=new Store(temp)) {
            var repo=new cn.swapy.tasks.TaskRepository(db);repo.create("T_test","redact",java.util.Map.of());
            String first=repo.reservePath("T_test","a.txt","Result.txt","F_first");
            String second=repo.reservePath("T_test","b.txt","result.txt","F_second");
            assertThat(first).isEqualTo("Result.txt");assertThat(second).isNotEqualToIgnoringCase(first).endsWith(".txt");
            assertThat(repo.reservePath("T_test","b.txt","other.txt","F_second")).isEqualTo(second);
        }
    }
    @Test void outputGuardRejectsAPathThroughAnOutsideLink() throws Exception {
        Path root=Files.createDirectory(temp.resolve("root")),outside=Files.createDirectory(temp.resolve("outside")),link=root.resolve("escape");
        TestLinks.directory(link,outside);
        try{assertThatThrownBy(()->Roots.guard(root,link.resolve("restored.txt"))).hasMessageContaining("PATH_OUTSIDE_ROOT");}finally{Files.deleteIfExists(link);}
    }
}
