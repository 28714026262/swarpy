package cn.swapy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class GitCatalogTest {
    @TempDir Path temp;
    @Test void publishedTagsSynchronizeFullHistoryWithoutReusingIds() throws Exception {
        Path repository=Files.createDirectory(temp.resolve("releases"));
        var process=new ProcessBuilder("git","init",repository.toString()).redirectErrorStream(true).start();process.getInputStream().readAllBytes();assertThat(process.waitFor()).isZero();
        try(Store publisher=new Store(temp.resolve("publisher"));Store client=new Store(temp.resolve("client"))) {
            var p=new Catalog(publisher);var c=new Catalog(client);var release=new GitCatalog(publisher,p,repository);
            var word=new Catalog.Word("S_1","北京","专有名词","中文词","地名","用户手填","启用",1,"");
            release.publish(new Catalog.Library("swapy-library",1,"authority",1,List.of(word),List.of()));
            release.publish(new Catalog.Library("swapy-library",1,"authority",2,List.of(word.deprecated()),List.of()));
            new GitCatalog(client,c,repository).sync();
            assertThat(c.snapshot().version()).isEqualTo(2);assertThat(c.snapshot().sensitive()).extracting(Catalog.Word::status).containsExactly("弃用");
            assertThatThrownBy(()->release.publish(new Catalog.Library("swapy-library",1,"authority",2,List.of(word),List.of()))).hasMessageContaining("VERSION_CONFLICT");
        }
    }
    @Test void manualSnapshotImportDoesNotLeaveConfiguredGitPublisherWithoutAParent() throws Exception {
        Path repository=Files.createDirectory(temp.resolve("snapshot-releases"));var process=new ProcessBuilder("git","init",repository.toString()).redirectErrorStream(true).start();process.getInputStream().readAllBytes();assertThat(process.waitFor()).isZero();
        try(Store db=new Store(temp.resolve("db"));Store client=new Store(temp.resolve("client"))) {
            var catalog=new Catalog(db);var git=new GitCatalog(db,catalog,repository);
            git.importLibrary(new Catalog.Library("swapy-library",1,"authority",5,List.of(new Catalog.Word("S_1","北京","名词","中文词","地名","用户手填","启用",1,"")),List.of()));
            git.add("pool","林舟","名词","中文词","地名","");
            assertThat(catalog.snapshot().version()).isEqualTo(6);
            var consumer=new Catalog(client);new GitCatalog(client,consumer,repository).sync();assertThat(consumer.snapshot().version()).isEqualTo(6);
        }
    }
}
