package cn.swapy;

import cn.swapy.tasks.*;
import cn.swapy.matching.MappingEngine;
import cn.swapy.packages.PackageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RecoveryTest {
    @TempDir Path temp;
    void create(TaskRepository repo,Roots roots) throws Exception {
        var payload=new LinkedHashMap<String,Object>(Map.of("source",new TaskService.Source("root0",""),"input",roots.configured().get("root0").toString(),"outputBase",roots.output.toString(),"mode","B","registerId","K_test","key","key","lineage","test","requiredVersion",1,"totalKnown",true));
        repo.create("T_test","redact",payload);repo.state("T_test","RUNNING","PROCESSING");Files.createDirectories(roots.output.resolve("T_test/.staging/files"));
    }
    @Test void artifactIntentAndMappingUsageCommitTogetherAndRecoverAfterReopening() throws Exception {
        Path source=Files.createDirectory(temp.resolve("source"));var roots=new Roots(List.of(source),temp.resolve("out"));
        try(Store db=new Store(temp.resolve("data"))) {
            var catalog=new Catalog(db);catalog.importLibrary(new Catalog.Library("swapy-library",1,"test",1,List.of(new Catalog.Word("S_1","北京","名词","中文词","地名","用户手填","启用",1,"")),List.of(new Catalog.Word("R_1","林舟","名词","中文词","地名","用户手填","启用",1,""))));
            var repo=new TaskRepository(db);create(repo,roots);repo.addWork("T_test","one.txt");var work=repo.claim("T_test","PENDING");
            repo.mappings("T_test",List.of(new MappingEngine.Mapping("S_1","R_1","北京","林舟","中文词","地名",1,1)));
            Path staging=roots.output.resolve("T_test/.staging/files/one.txt");Files.writeString(staging,"林舟");
            db.write(c->{Store.execute(c,"CREATE TRIGGER fail_result BEFORE UPDATE ON work_items WHEN NEW.state='DONE' BEGIN SELECT RAISE(ABORT,'injected result failure'); END");return null;});
            assertThatThrownBy(()->repo.stageArtifact("T_test",work,"files/one.txt",staging,TaskService.hash(staging),"source-hash",1,1,Map.of("output","files/one.txt","usedMappings",List.of("S_1")),List.of(),"READY")).hasMessageContaining("injected");
            List<Map<String,Object>> artifacts=db.read(c->Store.rows(c,"SELECT id FROM artifacts"));assertThat(artifacts).isEmpty();assertThat(repo.work("T_test").get(0).get("state")).isEqualTo("RUNNING");
            db.write(c->{Store.execute(c,"DROP TRIGGER fail_result");return null;});
            repo.stageArtifact("T_test",work,"files/one.txt",staging,TaskService.hash(staging),"source-hash",1,1,Map.of("output","files/one.txt","usedMappings",List.of("S_1")),List.of(),"READY");
            Path cache=db.data.resolve("work/T_test/extracts");Files.createDirectories(cache);Files.writeString(cache.resolve("source.json"),"北京");
        }
        try(Store db=new Store(temp.resolve("data"));var service=new TaskService(db,new Catalog(db),roots)) {
            assertThat(Files.readString(roots.output.resolve("T_test/files/one.txt"))).isEqualTo("林舟");
            assertThat(service.status("T_test").get("status")).isEqualTo("INTERRUPTED");
            assertThat((List<?>)new PackageService(db,new Catalog(db)).passwordbook("T_test").get("mappings")).hasSize(1);
            assertThat(db.data.resolve("work/T_test/extracts/source.json")).doesNotExist();
        }
    }
    @Test void blockedWholeTreePublicationDoesNotExposeHalfASqlGroup() throws Exception {
        Path source=Files.createDirectory(temp.resolve("source"));var roots=new Roots(List.of(source),temp.resolve("out"));
        try(Store db=new Store(temp.resolve("data"))) {
            var repo=new TaskRepository(db);create(repo,roots);
            for(String name:List.of("a.sql","b.sql")) {
                repo.addWork("T_test",name);var work=repo.claim("T_test","PENDING");Path staged=roots.output.resolve("T_test/.staging/files/"+name);Files.writeString(staged,"SELECT 1;");
                repo.stageArtifact("T_test",work,"files/"+name,staged,TaskService.hash(staged),"source",1,1,Map.of("output","files/"+name,"usedMappings",List.of()),List.of(),"READY");
            }
            Files.createDirectories(roots.output.resolve("T_test/files/b.sql"));
        }
        try(Store db=new Store(temp.resolve("data"));var service=new TaskService(db,new Catalog(db),roots)) {
            assertThat(roots.output.resolve("T_test/files/a.sql")).doesNotExist();
            assertThat(service.status("T_test").get("status")).isEqualTo("FAILED");
        }
    }
    @Test void nonUserShutdownDuringDiscoveryResumesInsteadOfBecomingPaused() throws Exception {
        Path source=Files.createDirectory(temp.resolve("scan-source"));for(int i=0;i<40;i++)Files.writeString(source.resolve(i+".txt"),"normal");var roots=new Roots(List.of(source),temp.resolve("out"));String id;
        try(Store db=new Store(temp.resolve("data"))) {
            var service=new TaskService(db,new Catalog(db),roots);
            try{id=(String)service.create(new TaskService.Request("scan",new TaskService.Source("root0",""),"A",false,Map.of(),false,false)).get("id");long deadline=System.currentTimeMillis()+5000;while(!service.status(id).get("phase").equals("DISCOVERING")&&System.currentTimeMillis()<deadline)Thread.sleep(5);assertThat(service.status(id).get("phase")).isEqualTo("DISCOVERING");}finally{service.close();}
        }
        try(Store db=new Store(temp.resolve("data"));var service=new TaskService(db,new Catalog(db),roots)) {
            assertThat(service.status(id).get("status")).isNotEqualTo("PAUSED");long deadline=System.currentTimeMillis()+20000;while(!List.of("SUCCEEDED","PARTIAL","FAILED").contains(service.status(id).get("status"))&&System.currentTimeMillis()<deadline)Thread.sleep(10);assertThat(service.status(id).get("status")).isEqualTo("SUCCEEDED");
        }
    }
}
