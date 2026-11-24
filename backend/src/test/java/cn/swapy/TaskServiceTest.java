package cn.swapy;

import cn.swapy.tasks.TaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class TaskServiceTest {
    @TempDir Path temp;
    void seed(Catalog c) {
        c.importLibrary(new Catalog.Library("swapy-library",1,"test",1,List.of(
                new Catalog.Word("S_1","北京","专有名词","中文词","地名","用户手填","启用",1,"")),List.of(
                new Catalog.Word("R_1","林舟","专有名词","中文词","地名","用户手填","启用",1,""))));
    }
    TaskService.Request scan(String path) {return new TaskService.Request("scan",new TaskService.Source("root0",path),"A",true,Map.of(),false,false);}
    Map<String,Object> terminal(TaskService svc,String id) throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(90).toNanos();
        while(System.nanoTime()<until) {
            var status=svc.status(id);
            if(List.of("SUCCEEDED","PARTIAL","FAILED","INTERRUPTED").contains(status.get("status"))) return status;
            Thread.sleep(20);
        }
        throw new AssertionError("Task did not finish: "+svc.status(id));
    }
    @Test void realScanOnlyReportsHitOrFailedFilesAndCreatesOneReport() throws Exception {
        Path source=Files.createDirectory(temp.resolve("source"));
        Files.writeString(source.resolve("命中.md"),"北京北京");Files.writeString(source.resolve("正常.txt"),"无敏感内容");
        var roots=new Roots(List.of(source),temp.resolve("out"));
        try(Store db=new Store(temp.resolve("data"))) {
            var catalog=new Catalog(db);seed(catalog);
            try(var service=new TaskService(db,catalog,roots)) {
                String id=(String)service.create(scan("")).get("id");
                var result=terminal(service,id);
                assertThat(result.get("processed")).isEqualTo(2);
                assertThat(service.issues(id)).hasSize(1);
                assertThat(service.issues(id).get(0)).containsEntry("word","北京").containsEntry("count",2);
                assertThat(Files.readString(roots.output.resolve(id).resolve("report.html"))).contains("命中.md").doesNotContain("正常.txt");
            }
        }
    }
    @Test void restartPreservesPausedScanAndResumeUsesSameIdWithoutDuplicateRows() throws Exception {
        Path source=Files.createDirectory(temp.resolve("source"));
        for(int i=0;i<160;i++) Files.writeString(source.resolve(i+".txt"),"北京".repeat(200));
        var roots=new Roots(List.of(source),temp.resolve("out"));String id;
        try(Store db=new Store(temp.resolve("data"))) {
            var catalog=new Catalog(db);seed(catalog);
            try(var service=new TaskService(db,catalog,roots)) {
                id=(String)service.create(scan("")).get("id");service.pause(id);
                long until=System.currentTimeMillis()+5000;
                while(!service.status(id).get("status").equals("PAUSED") && System.currentTimeMillis()<until) Thread.sleep(10);
                assertThat(service.status(id).get("status")).isEqualTo("PAUSED");
            }
        }
        try(Store db=new Store(temp.resolve("data"));var service=new TaskService(db,new Catalog(db),roots)) {
            assertThat(service.status(id).get("status")).isEqualTo("PAUSED");
            service.resume(id);assertThat(terminal(service,id).get("processed")).isEqualTo(160);
            int count=db.read(c->Store.rows(c,"SELECT count(*) n FROM work_items WHERE task_id=?",id).get(0).get("n") instanceof Number n?n.intValue():-1);
            assertThat(count).isEqualTo(160);assertThat(service.issues(id)).hasSize(160);
        }
    }
    @Test void threeScanTasksShareAtMostFourFileSlots() throws Exception {
        Path source=Files.createDirectory(temp.resolve("source"));
        for(int i=0;i<16;i++) Files.writeString(source.resolve(i+".txt"),"无命中".repeat(2000));
        var roots=new Roots(List.of(source),temp.resolve("out"));
        try(Store db=new Store(temp.resolve("data"));var service=new TaskService(db,new Catalog(db),roots)) {
            var ids=new ArrayList<String>();for(int i=0;i<3;i++) ids.add((String)service.create(scan("")).get("id"));
            for(String id:ids) assertThat(terminal(service,id).get("processed")).isEqualTo(16);
            assertThat(service.maxObservedWorkers()).isBetween(1,4);
        }
    }
    @Test void realRedactionRestoresTextAndOriginalNamesWithoutOverwritingInput() throws Exception {
        Path source=Files.createDirectory(temp.resolve("source"));Files.writeString(source.resolve("北京.txt"),"北京 北京");
        var roots=new Roots(List.of(source,temp.resolve("source")),temp.resolve("out"));
        try(Store db=new Store(temp.resolve("data"))) {
            var catalog=new Catalog(db);seed(catalog);
            String id;Map<String,Object> resolved;
            try(var service=new TaskService(db,catalog,roots)) {
                var request=new TaskService.Request("redact",new TaskService.Source("root0",""),"A",true,Map.of(),false,false);
                id=(String)service.create(request).get("id");assertThat(terminal(service,id).get("status")).isEqualTo("SUCCEEDED");
                Path outputFile;try(var stream=Files.walk(roots.output.resolve(id).resolve("files"))){outputFile=stream.filter(Files::isRegularFile).findFirst().orElseThrow();}
                assertThat(Files.readString(outputFile)).contains("林舟〔sw:");assertThat(Files.readString(source.resolve("北京.txt"))).isEqualTo("北京 北京");
                var credential=new cn.swapy.packages.PackageService(db,catalog).passwordbook(id);
                resolved=new cn.swapy.packages.PackageService(db,catalog).loadCredential(Json.write(credential).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            var restoreRoots=new Roots(List.of(roots.output.resolve(id).resolve("files")),temp.resolve("restored"));
            try(var restoreService=new TaskService(db,catalog,restoreRoots)) {
                    String restoredId=(String)restoreService.create(new TaskService.Request("restore",new TaskService.Source("root0",""),"A",false,resolved,false,false)).get("id");
                    var restored=terminal(restoreService,restoredId);
                    assertThat(restored.get("status")).as("restore issues: %s",restoreService.issues(restoredId)).isEqualTo("SUCCEEDED");
                    assertThat(Files.readString(restoreRoots.output.resolve(restoredId).resolve("files/北京.txt"))).isEqualTo("北京 北京");
            }
        }
    }
    @Test void failedSqlMemberPreventsPublishingHalfAGroup() throws Exception {
        Path source=Files.createDirectory(temp.resolve("sql-source"));Files.writeString(source.resolve("schema.sql"),"CREATE TABLE a (北京 TEXT);");Files.writeString(source.resolve("broken.sql"),"SELECT 北京 FROM (");
        var roots=new Roots(List.of(source),temp.resolve("out"));
        try(Store db=new Store(temp.resolve("data"))) {
            var catalog=new Catalog(db);seed(catalog);
            try(var service=new TaskService(db,catalog,roots)) {
                String id=(String)service.create(new TaskService.Request("redact",new TaskService.Source("root0",""),"B",false,Map.of(),false,false)).get("id");
                assertThat(terminal(service,id).get("status")).isEqualTo("PARTIAL");
                Path files=roots.output.resolve(id).resolve("files");
                if(Files.exists(files))try(var entries=Files.walk(files)){assertThat(entries.filter(Files::isRegularFile).count()).isZero();}
                assertThat(service.issues(id)).anyMatch(i->"SQL_GROUP_FAILED".equals(i.get("code")));
            }
        }
    }
    @Test void passwordbookOnlyContainsMappingsActuallyWrittenToOutputs() throws Exception {
        Path source=Files.createDirectory(temp.resolve("protected-source"));Files.writeString(source.resolve("config.json"),"{\"北京\":12}");
        var roots=new Roots(List.of(source),temp.resolve("out"));
        try(Store db=new Store(temp.resolve("data"))) {
            var catalog=new Catalog(db);seed(catalog);
            try(var service=new TaskService(db,catalog,roots)) {
                String id=(String)service.create(new TaskService.Request("redact",new TaskService.Source("root0",""),"B",false,Map.of(),false,false)).get("id");
                assertThat(terminal(service,id).get("status")).isEqualTo("PARTIAL");
                assertThat((java.util.List<?>)new cn.swapy.packages.PackageService(db,catalog).passwordbook(id).get("mappings")).isEmpty();
            }
        }
    }
    @Test void incrementalScanReusesOnlyMatchingContentAndCatalogVersion() throws Exception {
        Path source=Files.createDirectory(temp.resolve("incremental"));Files.writeString(source.resolve("one.txt"),"北京");
        var roots=new Roots(List.of(source),temp.resolve("out"));
        try(Store db=new Store(temp.resolve("data"))) {
            var catalog=new Catalog(db);seed(catalog);
            try(var service=new TaskService(db,catalog,roots)) {
                String first=(String)service.create(scan("")).get("id");terminal(service,first);
                var request=new TaskService.Request("scan",new TaskService.Source("root0",""),"A",false,Map.of(),true,false);
                String second=(String)service.create(request).get("id");assertThat(terminal(service,second).get("reused")).isEqualTo(1);assertThat(service.issues(second)).hasSize(1);
                Files.writeString(source.resolve("one.txt"),"北京北京");
                String third=(String)service.create(request).get("id");assertThat(terminal(service,third).get("reused")).isEqualTo(0);assertThat(service.issues(third).get(0)).containsEntry("count",2);
            }
        }
    }
    @Test void libraryUpdateAddsNewMappingsWithoutChangingExistingOnes() throws Exception {
        Path source=Files.createDirectory(temp.resolve("updating"));for(int i=0;i<8;i++)Files.writeString(source.resolve(i+".txt"),"北京 杭州");
        var roots=new Roots(List.of(source),temp.resolve("out"));var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var docs=new cn.swapy.documents.Documents(){@Override public void rewrite(Path input,Path output,java.util.function.UnaryOperator<String> change){entered.countDown();try{if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("release timed out");}catch(InterruptedException e){throw new RuntimeException(e);}super.rewrite(input,output,change);}};
        try(Store db=new Store(temp.resolve("data"))) {
            var catalog=new Catalog(db);seed(catalog);
            try(var service=new TaskService(db,catalog,roots,docs)) {
                String id=(String)service.create(new TaskService.Request("redact",new TaskService.Source("root0",""),"B",false,Map.of(),false,false)).get("id");
                assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                catalog.add("sensitive","杭州","专有名词","中文词","地名","");catalog.add("pool","海津","专有名词","中文词","地名","");release.countDown();
                assertThat(terminal(service,id).get("requiredVersion")).isEqualTo(3);
                var mappings=service.repo.successfulMappings(id);assertThat(mappings).extracting(cn.swapy.matching.MappingEngine.Mapping::replacement).containsExactlyInAnyOrder("林舟","海津");
            }finally{release.countDown();}
        }
    }
    @Test void archiveLinkOutsideTheRootIsNeverScanned() throws Exception {
        Path source=Files.createDirectory(temp.resolve("linked-source")),outside=Files.createDirectory(temp.resolve("outside")),archive=outside.resolve("outside.zip"),link=source.resolve("linked");
        try(var out=new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))){out.putNextEntry(new java.util.zip.ZipEntry("secret.md"));out.write("北京".getBytes(java.nio.charset.StandardCharsets.UTF_8));out.closeEntry();}
        TestLinks.directory(link,outside);
        try{var roots=new Roots(List.of(source),temp.resolve("out"));try(Store db=new Store(temp.resolve("data"))){var catalog=new Catalog(db);seed(catalog);try(var service=new TaskService(db,catalog,roots)){String id=(String)service.create(scan("")).get("id");assertThat(terminal(service,id).get("status")).isEqualTo("PARTIAL");assertThat(service.issues(id)).anyMatch(i->"PATH_OUTSIDE_ROOT".equals(i.get("code"))).noneMatch(i->"SENSITIVE_HIT".equals(i.get("code")));}}}finally{Files.deleteIfExists(link);}
    }
}
