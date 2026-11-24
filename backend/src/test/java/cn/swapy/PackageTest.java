package cn.swapy;

import cn.swapy.packages.PackageService;
import cn.swapy.tasks.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PackageTest {
    @TempDir Path temp;
    void task(Store db,String id,String kind) {
        var repo=new TaskRepository(db);
        repo.create(id,kind,new LinkedHashMap<>(Map.of("mode","B","registerId","K_test","key","key-test","requiredVersion",0,"lineage","test")));
        repo.state(id,"SUCCEEDED","FINISHED");
    }
    @Test void singlePackageRoundTripIsIdempotentAndOnlyRedactionCanExport() {
        byte[] pack;
        try(Store db=new Store(temp.resolve("a"))) {
            task(db,"T_test","redact");task(db,"T_scan","scan");var service=new PackageService(db,new Catalog(db));
            pack=service.exportSelected(List.of("T_test"));
            assertThatThrownBy(()->service.exportSelected(List.of("T_scan"))).hasMessageContaining("NOT_REDACTION_TASK");
        }
        try(Store db=new Store(temp.resolve("b"))) {
            var service=new PackageService(db,new Catalog(db));
            assertThat(service.importPackages(pack)).containsExactly("T_test");
            assertThat(service.importPackages(pack)).containsExactly("T_test");
            assertThat(new TaskRepository(db).tasks()).hasSize(1);
        }
    }
    @Test void lastItemConflictRejectsEntireBatchIncludingCrossKindIds() {
        byte[] pack;
        try(Store db=new Store(temp.resolve("a"))) {
            task(db,"T_first","redact");task(db,"T_conflict","redact");
            pack=new PackageService(db,new Catalog(db)).exportSelected(List.of("T_first","T_conflict"));
        }
        try(Store db=new Store(temp.resolve("b"))) {
            task(db,"T_conflict","scan");
            assertThatThrownBy(()->new PackageService(db,new Catalog(db)).importPackages(pack)).hasMessageContaining("ID_CONFLICT");
            assertThat(new TaskRepository(db).tasks()).extracting(t->t.get("id")).containsExactly("T_conflict");
        }
    }
    @Test void checksummedPackageCannotSwapSensitiveAndReplacementLibraryIds() throws Exception {
        var library=new Catalog.Library("swapy-library",1,"test",1,List.of(new Catalog.Word("S_1","原医院","名词","中文词","医院名","用户手填","启用",1,"")),List.of(new Catalog.Word("R_1","青溪馆","专有名词","中文词","设施名","用户手填","启用",1,"")));
        byte[] pack;
        try(Store db=new Store(temp.resolve("source"))) {
            var catalog=new Catalog(db);catalog.importLibrary(library);task(db,"T_test","redact");var repo=new TaskRepository(db);repo.payload("T_test",p->{p.put("requiredVersion",1);p.put("lineage","test");});
            repo.mappings("T_test",List.of(new cn.swapy.matching.MappingEngine.Mapping("S_1","R_1","原医院","青溪馆","中文词","医院名",1,1)));repo.addWork("T_test","one.txt");var work=repo.claim("T_test","PENDING");
            repo.finish("T_test",work,"DONE","hash",1,1,Map.of("usedMappings",List.of("S_1")),List.of());
            db.write(c->{Store.execute(c,"INSERT INTO artifacts(id,task_id,file_id,path,staging,hash,state) VALUES('A_test','T_test',?,'files/one.txt','unused','hash','PUBLISHED')",work.get("id"));return null;});
            pack=new PackageService(db,catalog).exportSelected(List.of("T_test"));
        }
        try(Store db=new Store(temp.resolve("coarse-receiver"))){var catalog=new Catalog(db);catalog.importLibrary(library);var service=new PackageService(db,catalog);assertThat(service.importPackages(pack)).containsExactly("T_test");assertThat(service.resolve("T_test")).extracting(cn.swapy.matching.MappingEngine.Mapping::replacement).containsExactly("青溪馆");}
        var members=new LinkedHashMap<String,byte[]>();try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(pack))){java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null)members.put(entry.getName(),zip.readAllBytes());}
        var maps=Json.read(new String(members.get("mappings.json"),java.nio.charset.StandardCharsets.UTF_8),List.class);var mapping=(Map<String,Object>)maps.get(0);mapping.put("source_id","R_1");mapping.put("replacement_id","S_1");members.put("mappings.json",Json.write(maps).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var manifest=Json.object(new String(members.get("manifest.json"),java.nio.charset.StandardCharsets.UTF_8));((Map<String,Object>)manifest.get("members")).put("mappings.json",PackageService.sha(members.get("mappings.json")));members.put("manifest.json",Json.write(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var output=new java.io.ByteArrayOutputStream();try(var zip=new java.util.zip.ZipOutputStream(output)){for(var entry:members.entrySet()){zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}
        try(Store db=new Store(temp.resolve("receiver"))){var catalog=new Catalog(db);catalog.importLibrary(library);assertThatThrownBy(()->new PackageService(db,catalog).importPackages(output.toByteArray())).hasMessageContaining("INVALID_MAPPING_REFERENCE");assertThat(new TaskRepository(db).tasks()).isEmpty();}
    }
}
