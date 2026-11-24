package cn.swapy;

import cn.swapy.tasks.TaskService;
import cn.swapy.packages.PackageService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class Api {
    private final TaskService tasks;private final Catalog catalog;private final Roots roots;private final PackageService packages;private final SecurityFilter security;
    private final GitCatalog git;
    public Api(TaskService tasks,Catalog catalog,Roots roots,PackageService packages,SecurityFilter security,GitCatalog git){this.tasks=tasks;this.catalog=catalog;this.roots=roots;this.packages=packages;this.security=security;this.git=git;}
    @GetMapping("/session") public Object session(){return Map.of("token",security.token,"backend","java-sqlite","parallelism",4,"nativeSelection",false,"pid",ProcessHandle.current().pid(),"dataDirectory",tasks.repo.db.data.toString());}
    @GetMapping("/roots") public Object roots(){return roots.configured().entrySet().stream().map(e->Map.of("id",e.getKey(),"name",e.getValue().toString())).toList();}
    @GetMapping("/roots/{id}/entries") public Object entries(@PathVariable String id,@RequestParam(defaultValue="")String path){return roots.entries(id,path);}
    @PostMapping("/tasks") public ResponseEntity<?> create(@RequestBody Map<String,Object> body,@RequestHeader(value="Idempotency-Key",required=false)String idempotencyKey){if(body.containsKey("parallelism"))throw new Problem("FIXED_PARALLELISM");body.putIfAbsent("mode","A");body.putIfAbsent("filenamePrefix",true);return ResponseEntity.accepted().body(tasks.create(Json.MAPPER.convertValue(body,TaskService.Request.class),idempotencyKey));}
    @GetMapping("/tasks") public Object list(){return tasks.list();}
    @GetMapping("/tasks/{id}") public Object task(@PathVariable String id){return tasks.status(id);}
    @GetMapping("/tasks/{id}/issues") public Object issues(@PathVariable String id){return tasks.issues(id);}
    @PostMapping("/tasks/{id}/pause-requests") public Object pause(@PathVariable String id){tasks.pause(id);return tasks.status(id);}
    @PostMapping("/tasks/{id}/resume-requests") public Object resume(@PathVariable String id){tasks.resume(id);return tasks.status(id);}
    @PostMapping("/tasks/{id}/interrupt-requests") public Object interrupt(@PathVariable String id){tasks.interrupt(id);return tasks.status(id);}
    @GetMapping("/tasks/{id}/report") public ResponseEntity<Resource> report(@PathVariable String id){if(Boolean.TRUE.equals(tasks.status(id).get("imported")))throw new Problem("FILE_NOT_FOUND");Path report=tasks.outputDirectory(id).resolve("report.html");Roots.guard(tasks.outputDirectory(id),report);return file(report,"text/html;charset=UTF-8");}
    @GetMapping("/tasks/{id}/files/{*path}") public ResponseEntity<Resource> output(@PathVariable String id,@PathVariable String path) {
        tasks.status(id);Path base=tasks.outputDirectory(id).resolve("files").toAbsolutePath().normalize();Path selected=base.resolve(path.replaceFirst("^/","")).normalize();
        try {Roots.guard(tasks.outputDirectory(id),selected);if(!selected.toRealPath().startsWith(base.toRealPath()))throw new Problem("PATH_OUTSIDE_ROOT");}catch(Problem e){throw e;}catch(Exception e){throw new Problem("FILE_NOT_FOUND");}
        String registered="files/"+base.relativize(selected).toString().replace('\\','/');
        if(tasks.repo.db.read(c->Store.rows(c,"SELECT id FROM artifacts WHERE task_id=? AND path=? AND state='PUBLISHED'",id,registered)).isEmpty())throw new Problem("FILE_NOT_FOUND");
        return file(selected,"application/octet-stream");
    }
    @GetMapping("/catalog") public Object library(){var value=Json.object(Json.write(catalog.snapshot()));value.put("readOnly",!git.publisher);return value;}
    @PostMapping("/catalog/entries") public Object add(@RequestBody Map<String,String> entry){return git.add(entry.get("kind"),entry.get("word"),entry.getOrDefault("pos","专有名词"),entry.getOrDefault("form","中文词"),entry.get("role"),entry.getOrDefault("family",""));}
    @PostMapping("/catalog/entries/{id}/deprecations") public Object deprecate(@PathVariable String id){return git.deprecate(id);}
    @PostMapping("/catalog/sync-jobs") public Object syncCatalog(){git.retryPending();return git.sync();}
    @PostMapping("/catalog/imports") public Object importLibrary(@RequestParam MultipartFile file) throws Exception {return git.importLibrary(Json.read(new String(file.getBytes(),StandardCharsets.UTF_8),Catalog.Library.class));}
    @PostMapping("/catalog/exports") public ResponseEntity<byte[]> exportLibrary(){return download("swapy-library-v"+catalog.snapshot().version()+".json",Json.write(catalog.snapshot()).getBytes(StandardCharsets.UTF_8),"application/json");}
    @PostMapping("/task-package-imports") public Object importPackages(@RequestParam MultipartFile file) throws Exception {return Map.of("taskIds",packages.importPackages(file.getBytes()));}
    @PostMapping("/task-package-exports") public ResponseEntity<byte[]> exportPackages(@RequestBody Map<String,Object> request) {
        boolean all="all_ready".equals(request.get("scope"));List<String> ids=all?List.of():((List<?>)request.getOrDefault("task_ids",List.of())).stream().map(String::valueOf).toList();
        byte[] data=all?packages.exportAll():packages.exportSelected(ids);return download(!all&&ids.size()==1?ids.get(0)+".swapy":"swapy-tasks.zip",data,"application/zip");
    }
    @PostMapping(value="/restore-resolutions",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public Object restoreFile(@RequestParam MultipartFile file) throws Exception {return packages.loadCredential(file.getBytes());}
    @PostMapping(value="/restore-resolutions",consumes=MediaType.APPLICATION_JSON_VALUE) public Object restoreTask(@RequestBody Map<String,String> body) {String id=body.get("taskId");var maps=packages.resolve(id);var task=tasks.repo.task(id);return Map.of("type","resolved","taskId",id,"registerId",task.get("registerId"),"mappings",maps);}
    @PostMapping("/tasks/{id}/passwordbook-exports") public ResponseEntity<byte[]> book(@PathVariable String id,@RequestBody Map<String,String> request){boolean html="html".equals(request.get("format"));return download(id+(html?"-passwordbook.html":"-passwordbook.json"),html?packages.passwordbookHtml(id):Json.write(packages.passwordbook(id)).getBytes(StandardCharsets.UTF_8),html?"text/html;charset=UTF-8":"application/json");}
    private ResponseEntity<Resource> file(Path path,String type) {if(!Files.isRegularFile(path))throw new Problem("FILE_NOT_FOUND");return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).body(new FileSystemResource(path));}
    private ResponseEntity<byte[]> download(String name,byte[] data,String type){return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+java.net.URLEncoder.encode(name,StandardCharsets.UTF_8)).contentType(MediaType.parseMediaType(type)).body(data);}
    @ExceptionHandler(Problem.class) public ResponseEntity<?> problem(Problem p) {
        int status=switch(p.code){case "TASK_NOT_LOCAL","WORD_NOT_FOUND","FILE_NOT_FOUND","ROOT_NOT_FOUND"->404;case "PATH_OUTSIDE_ROOT"->403;case "ID_CONFLICT","LIBRARY_DOWNGRADE","VERSION_CONFLICT","IDEMPOTENCY_CONFLICT"->409;default->422;};
        return ResponseEntity.status(status).body(Map.of("code",p.code,"message",p.getMessage(),"requestId",UUID.randomUUID().toString()));
    }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","message",Objects.toString(e.getMessage(),"参数不正确")));}
}
