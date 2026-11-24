package cn.swapy.tasks;

import cn.swapy.*;
import cn.swapy.documents.Documents;
import cn.swapy.documents.ArchiveScanner;
import cn.swapy.matching.Matcher;
import cn.swapy.matching.MappingEngine;
import cn.swapy.packages.PackageService;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

public final class TaskService implements AutoCloseable {
    public record Source(String rootId,String path) {}
    public record Request(String kind,Source source,String mode,boolean filenamePrefix,Map<String,Object> credential,boolean incremental,boolean forceRescan,Source output) {
        public Request(String kind,Source source,String mode,boolean filenamePrefix,Map<String,Object> credential,boolean incremental,boolean forceRescan){this(kind,source,mode,filenamePrefix,credential,incremental,forceRescan,null);}
    }
    private static final Set<String> TERMINAL=Set.of("SUCCEEDED","PARTIAL","FAILED","INTERRUPTED");
    public final TaskRepository repo;
    private final Catalog catalog;
    public final Roots roots;
    private final Documents documents;
    private final MappingEngine engine=new MappingEngine();
    private final ScheduledExecutorService coordinator=Executors.newSingleThreadScheduledExecutor(daemon("swapy-coordinator"));
    private final ExecutorService workers=Executors.newFixedThreadPool(4,daemon("swapy-file"));
    private final Semaphore slots=new Semaphore(4);
    private final ConcurrentMap<String,AtomicInteger> active=new ConcurrentHashMap<>();
    private final AtomicInteger activeTotal=new AtomicInteger(),maxObserved=new AtomicInteger();
    private final ConcurrentMap<String,Path> extracted=new ConcurrentHashMap<>();
    private final ConcurrentMap<String,MappingEngine.Plan> plans=new ConcurrentHashMap<>();
    private volatile boolean closing;
    private int roundRobin;

    public TaskService(Store db,Catalog catalog,Roots roots) {
        this(db,catalog,roots,new Documents());
    }
    public TaskService(Store db,Catalog catalog,Roots roots,Documents documents) {
        this.repo=new TaskRepository(db);this.catalog=catalog;this.roots=roots;this.documents=documents;recover();
        coordinator.scheduleWithFixedDelay(this::tickSafely,0,35,TimeUnit.MILLISECONDS);
    }
    private static ThreadFactory daemon(String name) {return action->{Thread t=new Thread(action,name);t.setDaemon(true);return t;};}
    public int maxObservedWorkers() {return maxObserved.get();}
    public Map<String,Object> create(Request request) {return create(request,null);}
    public synchronized Map<String,Object> create(Request request,String idempotencyKey) {
        String requestHash=PackageService.sha(Json.write(request).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(idempotencyKey!=null) {
            if(!idempotencyKey.matches("[A-Za-z0-9_-]{8,128}"))throw new Problem("INVALID_IDEMPOTENCY_KEY");
            var existing=repo.db.read(c->Store.rows(c,"SELECT id,payload FROM tasks WHERE json_extract(payload,'$.idempotencyKey')=?",idempotencyKey));
            if(!existing.isEmpty()) {
                if(!requestHash.equals(Json.object((String)existing.get(0).get("payload")).get("requestHash")))throw new Problem("IDEMPOTENCY_CONFLICT");
                return status((String)existing.get(0).get("id"));
            }
        }
        if(request==null || !Set.of("scan","redact","restore").contains(request.kind()) || request.source()==null) throw new Problem("INVALID_TASK");
        String mode=request.mode()==null?"A":request.mode();if(!Set.of("A","B").contains(mode))throw new Problem("INVALID_MODE");
        Path input=roots.resolve(request.source().rootId(),request.source().path());
        String id="T_"+UUID.randomUUID();String register="K_"+UUID.randomUUID();
        var payload=new LinkedHashMap<String,Object>();payload.put("source",request.source());payload.put("input",input.toString());payload.put("mode",mode);payload.put("filenamePrefix",request.filenamePrefix());
        if(idempotencyKey!=null){payload.put("idempotencyKey",idempotencyKey);payload.put("requestHash",requestHash);}
        payload.put("registerId",register);payload.put("key",UUID.randomUUID().toString());payload.put("totalKnown",false);payload.put("libraryVersion",catalog.snapshot().version());
        payload.put("requiredVersion",0);payload.put("lineage",catalog.snapshot().lineage());payload.put("incremental",request.incremental());payload.put("forceRescan",request.forceRescan());
        Path outputBase=request.output()==null?roots.output:roots.resolve(request.output().rootId(),request.output().path());
        if(!Files.isDirectory(outputBase)||outputBase.equals(input))throw new Problem("INVALID_OUTPUT_DIRECTORY");payload.put("outputBase",outputBase.toString());
        if("restore".equals(request.kind())) {
            var credential=request.credential()==null?Map.<String,Object>of():request.credential();String originalId=Objects.toString(credential.get("taskId"),"");
            if("resolved".equals(credential.get("type"))) {
                payload.put("restoreMappings",credential.get("mappings"));payload.put("restoreRegisterId",credential.get("registerId"));
                payload.put("restorePaths",credential.getOrDefault("paths",List.of()));payload.put("restoreTaskId",originalId);
            } else {
            var original=repo.task(originalId);if(!"redact".equals(original.get("kind")))throw new Problem("NOT_REDACTION_TASK");
            if(!TERMINAL.contains(original.get("status")))throw new Problem("TASK_NOT_READY");
            var library=catalog.snapshot();int minimum=((Number)original.getOrDefault("requiredVersion",0)).intValue();
            if(library.version()<minimum)throw new Problem("LIBRARY_TOO_OLD","当前 "+library.version()+"，需要 "+minimum);
            if(minimum>0 && !Objects.equals(original.get("lineage"),library.lineage()))throw new Problem("CATALOG_LINEAGE_CONFLICT");
            payload.put("restoreTaskId",originalId);payload.put("restoreRegisterId",original.get("registerId"));
            payload.put("restoreMappings",new PackageService(repo.db,catalog).resolve(originalId));
            }
        }
        try {Roots.guard(outputBase,outputBase.resolve(id));Files.createDirectory(outputBase.resolve(id));Path privateStage=outputBase.resolve(".swapy-stage").resolve(id);Roots.guard(outputBase,privateStage);Files.createDirectories(privateStage.getParent());Files.createDirectory(privateStage);}
        catch(Exception e){throw new Problem("OUTPUT_UNAVAILABLE",e.getMessage());}
        repo.create(id,request.kind(),payload);return status(id);
    }
    private void recover() {
        var interrupted=repo.tasks().stream().filter(t->!"scan".equals(t.get("kind"))&&!TERMINAL.contains(t.get("status"))).map(t->(String)t.get("id")).toList();
        repo.db.write(c->{
            Store.execute(c,"UPDATE work_items SET state='PENDING' WHERE state='RUNNING' AND task_id IN (SELECT id FROM tasks WHERE kind='scan')");
            Store.execute(c,"UPDATE tasks SET status='INTERRUPTED',phase='INTERRUPTED' WHERE kind<>'scan' AND status NOT IN ('SUCCEEDED','PARTIAL','FAILED','INTERRUPTED')");
            Store.execute(c,"UPDATE tasks SET status='PAUSED' WHERE kind='scan' AND status='PAUSING'");
            Store.execute(c,"UPDATE tasks SET status='QUEUED',phase=CASE WHEN phase IN ('CREATED','DISCOVERING') THEN 'CREATED' ELSE 'RECOVERING' END WHERE kind='scan' AND status NOT IN ('PAUSED','SUCCEEDED','PARTIAL','FAILED','INTERRUPTED')");
            return null;
        });
        for(String id:interrupted){discardUnreadySql(id);finishTask(id,"INTERRUPTED");}
    }
    private void discardUnreadySql(String id) {
        var waiting=repo.db.read(c->Store.rows(c,"SELECT file_id,path FROM artifacts WHERE task_id=? AND state='WAITING_GROUP'",id));
        if(waiting.isEmpty())return;
        repo.db.write(c->{Store.execute(c,"UPDATE artifacts SET state='DISCARDED' WHERE task_id=? AND state='WAITING_GROUP'",id);Store.execute(c,"UPDATE work_items SET state='FAILED' WHERE id IN (SELECT file_id FROM artifacts WHERE task_id=? AND state='DISCARDED')",id);Store.execute(c,"UPDATE work_groups SET state='FAILED' WHERE task_id=? AND state<>'PUBLISHED'",id);return null;});
        for(var item:waiting)repo.issue(id,(String)item.get("file_id"),issue((String)item.get("path"),"SQL_GROUP_INTERRUPTED","SQL组未全部验证，整组未输出",null,0,null));
    }
    private void publishTree(String id) throws Exception {
        Path directory=outputDirectory(id),tree=stagingDirectory(id).resolve("files"),published=directory.resolve("files");
        guardStaging(id,tree);Roots.guard(directory,published);
        var artifacts=repo.db.read(c->Store.rows(c,"SELECT * FROM artifacts WHERE task_id=? AND state='READY'",id));
        if(artifacts.isEmpty())return;
        if(Files.exists(published)) {
            for(var artifact:artifacts){Path target=directory.resolve((String)artifact.get("path")).normalize();Roots.guard(directory,target);if(!target.startsWith(published)||!Files.isRegularFile(target)||!Objects.equals(hash(target),artifact.get("hash")))throw new Problem("OUTPUT_TREE_CONFLICT");}
        }else {
            var expected=new HashSet<Path>();
            for(var artifact:artifacts) {
                Path relative=Path.of((String)artifact.get("path"));if(relative.isAbsolute()||relative.getNameCount()<2||!relative.getName(0).toString().equals("files"))throw new Problem("PATH_OUTSIDE_ROOT");
                Path target=tree.resolve(relative.subpath(1,relative.getNameCount())).normalize();guardStaging(id,target);expected.add(target);
                Path staged=Path.of((String)artifact.get("staging")).toAbsolutePath().normalize();guardStaging(id,staged);
                if(!Files.exists(target)){if(!Objects.equals(hash(staged),artifact.get("hash")))throw new Problem("ARTIFACT_HASH_MISMATCH");Files.createDirectories(target.getParent());Files.move(staged,target,StandardCopyOption.ATOMIC_MOVE);}
                if(!Objects.equals(hash(target),artifact.get("hash")))throw new Problem("ARTIFACT_HASH_MISMATCH");
                repo.db.write(c->{Store.execute(c,"UPDATE artifacts SET staging=? WHERE id=?",target.toString(),artifact.get("id"));return null;});
            }
            try(var files=Files.walk(tree)){for(Path file:files.filter(Files::isRegularFile).toList())if(!expected.contains(file))throw new Problem("UNEXPECTED_OUTPUT_CONTENT");}
            guardStaging(id,tree);Roots.guard(directory,published);Files.move(tree,published,StandardCopyOption.ATOMIC_MOVE);
        }
        repo.db.write(c->{Store.execute(c,"UPDATE artifacts SET state='PUBLISHED' WHERE task_id=? AND state='READY'",id);Store.execute(c,"UPDATE work_groups SET state='PUBLISHED' WHERE task_id=? AND state='READY'",id);return null;});
    }
    private Path committedFile(String id,String output) {
        Path published=outputDirectory(id).resolve(output);
        if(Files.isRegularFile(published)){Roots.guard(outputDirectory(id),published);return published;}
        var rows=repo.db.read(c->Store.rows(c,"SELECT staging FROM artifacts WHERE task_id=? AND path=? AND state='READY'",id,output));
        if(rows.isEmpty())throw new Problem("ARTIFACT_NOT_READY");Path staged=Path.of((String)rows.get(0).get("staging"));guardStaging(id,staged);return staged;
    }
    public Map<String,Object> status(String id) {
        var task=repo.task(id);var work=repo.work(id);int processed=0,failed=0,skipped=0,succeeded=0;
        for(var item:work) switch((String)item.get("state")) {case "DONE"->{processed++;succeeded++;}case "FAILED"->{processed++;failed++;}case "SKIPPED"->{processed++;skipped++;}default->{}}
        task.put("total",work.size());task.put("discovered",work.size());task.put("processed",processed);task.put("succeeded",succeeded);task.put("failed",failed);task.put("skipped",skipped);
        int reused=(int)work.stream().filter(w->Json.object((String)w.get("payload")).containsKey("reusedFrom")).count();task.put("reused",reused);
        task.put("activeWorkers",active.getOrDefault(id,new AtomicInteger()).get());task.put("output",directory(task).toString());return task;
    }
    private Path directory(Map<String,Object> task){return Path.of(Objects.toString(task.get("outputBase"),roots.output.toString())).resolve((String)task.get("id"));}
    public Path outputDirectory(String id){return directory(repo.task(id));}
    private Path stagingDirectory(String id){return outputDirectory(id).getParent().resolve(".swapy-stage").resolve(id);}
    private void guardStaging(String id,Path path){Path stage=stagingDirectory(id),legacy=outputDirectory(id).resolve(".staging");if(path.toAbsolutePath().normalize().startsWith(stage)){Roots.guard(outputDirectory(id).getParent(),path);}else if(path.toAbsolutePath().normalize().startsWith(legacy)){Roots.guard(outputDirectory(id),path);}else throw new Problem("PATH_OUTSIDE_ROOT");}
    public List<Map<String,Object>> list() {return repo.tasks().stream().map(t->status((String)t.get("id"))).toList();}
    public List<Map<String,Object>> issues(String id) {repo.task(id);return repo.issues(id);}
    public synchronized void pause(String id) {
        var task=repo.task(id);if(!"scan".equals(task.get("kind")))throw new Problem("SCAN_ONLY_ACTION");
        if(TERMINAL.contains(task.get("status")))throw new Problem("TASK_ALREADY_FINISHED");
        repo.state(id,"PAUSING",(String)task.get("phase"));
    }
    public void resume(String id) {
        var task=repo.task(id);if(!"scan".equals(task.get("kind")))throw new Problem("SCAN_ONLY_ACTION");
        if(!Set.of("PAUSED","PAUSING").contains(task.get("status")))throw new Problem("TASK_NOT_PAUSED");
        repo.state(id,"QUEUED","CREATED");
    }
    public void interrupt(String id) {
        var task=repo.task(id);if(TERMINAL.contains(task.get("status")))return;
        repo.state(id,"INTERRUPTING",(String)task.get("phase"));
    }
    private void tickSafely() {
        if(closing)return;
        try {tick();} catch(Exception e) {System.getLogger(TaskService.class.getName()).log(System.Logger.Level.ERROR,"Task coordinator: "+e.getMessage());}
    }
    private synchronized void tick() {
        List<Map<String,Object>> all=repo.tasks();if(all.isEmpty())return;
        for(int pass=0;pass<all.size()*4;pass++) {
            var task=all.get(Math.floorMod(roundRobin++,all.size()));String id=(String)task.get("id");task=repo.task(id);
            String state=(String)task.get("status"),phase=(String)task.get("phase");int running=active.getOrDefault(id,new AtomicInteger()).get();
            if(TERMINAL.contains(state)||state.equals("PAUSED"))continue;
            if(state.equals("PAUSING")) {if(running==0)repo.state(id,"PAUSED",phase);continue;}
            if(state.equals("INTERRUPTING")) {if(running==0) finishTask(id,"INTERRUPTED");continue;}
            if(slots.availablePermits()==0)break;
            final Map<String,Object> current=task;
            if(phase.equals("CREATED") && running==0) {repo.state(id,"RUNNING","DISCOVERING");launch(id,()->discover(current));continue;}
            if(phase.equals("RECOVERING") && running==0) {repo.state(id,"RUNNING","CHECKING");launch(id,()->checkCompleted(id));continue;}
            if(Set.of("DISCOVERING","CHECKING","PLANNING","UPDATING","FINALIZING").contains(phase))continue;
            if(phase.equals("PROCESSING")&&"redact".equals(task.get("kind"))&&((Number)task.getOrDefault("plannedVersion",0)).intValue()<catalog.snapshot().version()) {
                if(running==0){repo.state(id,"RUNNING","UPDATING");launch(id,()->allocate(current));}continue;
            }
            if(Set.of("PROCESSING","PREFLIGHT").contains(phase)) {
                if(phase.equals("PROCESSING")&&!task.get("kind").equals("scan")&&claimSqlGroup(id)) {launch(id,()->processSqlGroup(current));continue;}
                var work=phase.equals("PROCESSING")&&!task.get("kind").equals("scan")?repo.claimNonSql(id):repo.claim(id,"PENDING");
                if(work!=null) {launch(id,()->process(current,work,phase.equals("PREFLIGHT")));continue;}
                if(running==0) {
                    if(phase.equals("PREFLIGHT")) {repo.state(id,"RUNNING","PLANNING");launch(id,()->allocate(current));}
                    else {repo.state(id,"RUNNING","FINALIZING");launch(id,()->finishTask(id,null));}
                }
            }
        }
    }
    private boolean claimSqlGroup(String id) {
        return repo.db.write(c->{
            var members=Store.rows(c,"SELECT id FROM work_items WHERE task_id=? AND lower(path) LIKE '%.sql'",id);if(members.isEmpty())return false;
            var group=Store.rows(c,"SELECT state FROM work_groups WHERE id=?",id+"_sql");if(!group.isEmpty())return false;
            Store.execute(c,"INSERT INTO work_groups(id,task_id,state) VALUES(?,?,'RUNNING')",id+"_sql",id);
            for(var m:members){Store.execute(c,"INSERT INTO group_members(group_id,work_id) VALUES(?,?)",id+"_sql",m.get("id"));Store.execute(c,"UPDATE work_items SET state=CASE WHEN state='PENDING' THEN 'RUNNING' ELSE state END WHERE id=?",m.get("id"));}
            return true;
        });
    }
    private void processSqlGroup(Map<String,Object> task) {
        String id=(String)task.get("id");var members=repo.work(id).stream().filter(i->((String)i.get("path")).toLowerCase(Locale.ROOT).endsWith(".sql")).toList();
        if(members.stream().noneMatch(i->i.get("state").equals("FAILED")))for(var member:members)process(task,member,false);
        boolean failed=repo.work(id).stream().filter(i->((String)i.get("path")).toLowerCase(Locale.ROOT).endsWith(".sql")).anyMatch(i->!i.get("state").equals("DONE"));
        if(failed) {
            repo.db.write(c->{Store.execute(c,"UPDATE work_groups SET state='FAILED' WHERE id=?",id+"_sql");Store.execute(c,"UPDATE artifacts SET state='DISCARDED' WHERE task_id=? AND state='WAITING_GROUP'",id);Store.execute(c,"UPDATE work_items SET state='FAILED' WHERE task_id=? AND lower(path) LIKE '%.sql'",id);return null;});
            for(var member:members)repo.issue(id,(String)member.get("id"),issue((String)member.get("path"),"SQL_GROUP_FAILED","SQL一致性组未全部完成，整组未输出",null,0,null));
            return;
        }
        repo.db.write(c->{Store.execute(c,"UPDATE work_groups SET state='READY' WHERE id=?",id+"_sql");Store.execute(c,"UPDATE artifacts SET state='READY' WHERE task_id=? AND state='WAITING_GROUP'",id);return null;});
    }
    private void launch(String id,Runnable job) {
        if(!slots.tryAcquire())throw new IllegalStateException("No worker slot");
        active.computeIfAbsent(id,k->new AtomicInteger()).incrementAndGet();maxObserved.accumulateAndGet(activeTotal.incrementAndGet(),Math::max);
        workers.submit(()->{
            try {if(!closing)job.run();}
            catch(Exception e) {if(!closing){repo.issue(id,"task",issue("",e instanceof Problem p?p.code:"TASK_FAILED",e.getMessage(),null,0,null));finishTask(id,"FAILED");}}
            finally {active.get(id).decrementAndGet();activeTotal.decrementAndGet();slots.release();}
        });
    }
    private void discover(Map<String,Object> task) {
        String id=(String)task.get("id");Path input=input(task);boolean single=Files.isRegularFile(input);Path base=single?input.getParent():input;
        var outputs=repo.tasks().stream().filter(t->!Boolean.TRUE.equals(t.get("imported"))).map(this::directory).toList();
        try {
            Files.walkFileTree(input,new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes a) {
                    if(closing || stopping(id))return FileVisitResult.TERMINATE;
                    if(Set.of(".swapy-stage",".staging").contains(dir.getFileName()==null?"":dir.getFileName().toString()))return FileVisitResult.SKIP_SUBTREE;
                    try{Roots.guard(roots.configured().get(source(task).rootId()),dir);}catch(Problem e){repo.issue(id,"discovery",issue(base.relativize(dir).toString(),"PATH_OUTSIDE_ROOT",e.getMessage(),null,0,null));return FileVisitResult.SKIP_SUBTREE;}
                    if(!dir.equals(input) && (dir.startsWith(roots.output) || dir.startsWith(repo.db.data) || outputs.stream().anyMatch(dir::startsWith) || Files.isSymbolicLink(dir)))return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file,BasicFileAttributes a) {
                    if(closing || stopping(id))return FileVisitResult.TERMINATE;
                    String relative=base.relativize(file).toString().replace('\\','/');
                    try{Roots.guard(roots.configured().get(source(task).rootId()),file);}catch(Problem e){repo.issue(id,"discovery",issue(relative,"PATH_OUTSIDE_ROOT",e.getMessage(),null,0,null));return FileVisitResult.CONTINUE;}
                    if("scan".equals(task.get("kind"))&&ArchiveScanner.archive(file)) {
                        try {
                            String fingerprint=hash(file);Path work=repo.db.data.resolve("work").resolve(id).resolve(fingerprint);Roots.guard(repo.db.data,work);
                            var oldMembers=repo.work(id).stream().filter(item->{var meta=Json.object((String)item.get("payload"));return relative.equals(meta.get("archiveSource"))&&fingerprint.equals(meta.get("archiveHash"));}).toList();
                            Object indexed=repo.task(id).get("archiveIndexes");boolean completeIndex=indexed instanceof Map<?,?> indexes&&fingerprint.equals(indexes.get(relative));
                            if(completeIndex&&!oldMembers.isEmpty()&&oldMembers.stream().allMatch(item->{var meta=Json.object((String)item.get("payload"));return !Objects.toString(meta.get("archiveError"),"").isEmpty()||Files.isRegularFile(Path.of(Objects.toString(meta.get("physical"),"")));}))return FileVisitResult.CONTINUE;
                            long consumed=((Number)repo.task(id).getOrDefault("archiveBytes",0)).longValue(),reserved=Math.max(0,Math.min(1024L*1024*1024,4L*1024*1024*1024-consumed));
                            repo.payload(id,p->p.put("archiveBytes",consumed+reserved));
                            var expansion=new ArchiveScanner().extract(file,work,reserved);repo.payload(id,p->p.put("archiveBytes",consumed+expansion.expandedBytes()));
                            for(var entry:expansion.entries()) {
                                var metadata=new LinkedHashMap<String,Object>();metadata.put("archiveSource",relative);metadata.put("archiveHash",fingerprint);
                                metadata.put("physical",entry.physical()==null?"":entry.physical().toString());metadata.put("archiveError",entry.error());
                                repo.addArchiveWork(id,relative+(entry.member().isEmpty()?"":"!/"+entry.member()),metadata);
                            }
                            repo.payload(id,p->{var indexes=new LinkedHashMap<String,Object>();if(p.get("archiveIndexes") instanceof Map<?,?> old)old.forEach((key,value)->indexes.put(String.valueOf(key),value));indexes.put(relative,fingerprint);p.put("archiveIndexes",indexes);});
                        }catch(Exception e){repo.issue(id,"archive",issue(relative,"ARCHIVE_READ_FAILED",e.getMessage(),null,0,null));}
                    }else repo.addWork(id,relative);return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file,IOException e) {repo.issue(id,"discovery",issue(base.relativize(file).toString(),"READ_FAILED",e.getMessage(),null,0,null));return FileVisitResult.CONTINUE;}
            });
            if(stopping(id)||closing) {
                String state=Objects.toString(repo.task(id).get("status"));
                if(!closing&&Set.of("PAUSING","PAUSED").contains(state))repo.state(id,"PAUSING","CREATED");
                else if(!closing&&state.equals("INTERRUPTING"))repo.state(id,"INTERRUPTING","CREATED");
                else repo.state(id,"QUEUED","CREATED");return;
            }
            repo.payload(id,p->p.put("totalKnown",true));
            if("scan".equals(task.get("kind")))checkCompleted(id);else repo.state(id,"RUNNING","redact".equals(task.get("kind"))?"PREFLIGHT":"PROCESSING");
        } catch(Exception e){throw new Problem("DISCOVERY_FAILED",e.getMessage());}
    }
    private boolean stopping(String id) {return Set.of("PAUSING","PAUSED","INTERRUPTING","INTERRUPTED").contains(repo.task(id).get("status"));}
    private Source source(Map<String,Object> task) {return Json.MAPPER.convertValue(task.get("source"),Source.class);}
    private Path input(Map<String,Object> task) {Source s=source(task);return roots.resolve(s.rootId(),s.path());}
    private Path file(Map<String,Object> task,Map<String,Object> item) {
        Source s=source(task);Path input=input(task);Path base=Files.isRegularFile(input)?input.getParent():input;
        var metadata=Json.object((String)item.get("payload"));
        if(metadata.get("archiveSource") instanceof String outer) {
            try {Path archivePath=base.resolve(outer);Roots.guard(roots.configured().get(s.rootId()),archivePath);if(!Objects.equals(hash(archivePath),metadata.get("archiveHash")))throw new Problem("SOURCE_CHANGED");}
            catch(Problem e){throw e;}catch(Exception e){throw new Problem("SOURCE_UNAVAILABLE");}
            Path physical=Path.of((String)metadata.get("physical")).toAbsolutePath().normalize();
            Roots.guard(repo.db.data,physical);if(!physical.startsWith(repo.db.data.resolve("work").resolve((String)task.get("id"))))throw new Problem("PATH_OUTSIDE_ROOT");return physical;
        }
        Path candidate=base.resolve((String)item.get("path")).normalize();Path root=roots.configured().get(s.rootId());
        return roots.resolve(s.rootId(),root.relativize(candidate).toString());
    }
    private void process(Map<String,Object> task,Map<String,Object> item,boolean preflight) {
        String id=(String)task.get("id"),path=(String)item.get("path");var problems=new ArrayList<Map<String,Object>>();String hash=null;long size=0,mtime=0;
        try {
            var metadata=Json.object((String)item.get("payload"));if(metadata.get("archiveError") instanceof String error&&!error.isEmpty())throw new Problem(error);
            Path input=file(task,item);hash=hash(input);size=Files.size(input);mtime=Files.getLastModifiedTime(input).toMillis();
            if(!preflight && "redact".equals(task.get("kind")) && !Objects.equals(hash,item.get("source_hash"))) {repo.state(id,"INTERRUPTING","PROCESSING");throw new Problem("SOURCE_CHANGED");}
            if(path.contains("[swapy-") && !task.get("kind").equals("restore"))throw new Problem("ALREADY_REDACTED");
            var scanLibrary=catalog.snapshot();
            if("scan".equals(task.get("kind")) && Boolean.TRUE.equals(task.get("incremental"))&&!Boolean.TRUE.equals(task.get("forceRescan"))) {
                final String digest=hash;
                var cached=repo.db.read(c->Store.rows(c,"SELECT w.id,w.task_id,w.payload FROM work_items w JOIN tasks t ON t.id=w.task_id WHERE t.kind='scan' AND t.id<>? AND json_extract(t.payload,'$.input')=? AND w.path=? AND w.source_hash=? AND w.state='DONE' AND json_extract(w.payload,'$.catalogVersion')=? AND json_extract(w.payload,'$.rulesVersion')='matcher-v1' ORDER BY t.created_at DESC LIMIT 1",id,task.get("input"),path,digest,scanLibrary.version()));
                if(!cached.isEmpty()) {
                    var previous=cached.get(0);var saved=repo.issues((String)previous.get("task_id")).stream().filter(i->Objects.equals(i.get("fileId"),previous.get("id"))).map(i->{var copy=new LinkedHashMap<>(i);copy.remove("fileId");return (Map<String,Object>)copy;}).toList();
                    if(!hash.equals(hash(input)))throw new Problem("SOURCE_CHANGED");metadata.put("reusedFrom",previous.get("task_id"));metadata.put("catalogVersion",scanLibrary.version());metadata.put("rulesVersion","matcher-v1");metadata.put("completedAt",Instant.now().toString());
                    repo.finish(id,item,"DONE",hash,size,mtime,metadata,saved);return;
                }
            }
            var content=documents.read(input);
            if(preflight) {
                Path cache=repo.db.data.resolve("work").resolve(id).resolve("extracts").resolve(item.get("id")+".json");Roots.guard(repo.db.data,cache);Files.createDirectories(cache.getParent());Files.writeString(cache,Json.write(content));extracted.put((String)item.get("id"),cache);
                if(!hash.equals(hash(input)))throw new Problem("SOURCE_CHANGED");
                repo.finish(id,item,"EXTRACTED",hash,size,mtime,Map.of(),List.of());return;
            }
            if(task.get("kind").equals("scan")) {
                var counts=new LinkedHashMap<String,Integer>();
                for(String text:content.texts())for(var hit:Matcher.find(text,scanLibrary.sensitive().stream().filter(Catalog.Word::active).toList())) counts.merge(hit.word().word(),1,Integer::sum);
                counts.forEach((word,count)->problems.add(issue(path,"SENSITIVE_HIT","命中敏感词",word,count,null)));
                if(!hash.equals(hash(input)))throw new Problem("SOURCE_CHANGED");
                metadata.put("completedAt",Instant.now().toString());metadata.put("catalogVersion",scanLibrary.version());metadata.put("rulesVersion","matcher-v1");repo.finish(id,item,"DONE",hash,size,mtime,metadata,problems);return;
            }
            writeOutput(task,item,input,content,hash,size,mtime,problems);
        } catch(Exception e) {
            String code=e instanceof Problem p?p.code:"FILE_FAILED";problems.clear();problems.add(issue(path,code,e.getMessage(),null,0,null));
            repo.finish(id,item,"FAILED",hash,size,mtime,Json.object((String)item.get("payload")),problems);
        }
    }
    private void allocate(Map<String,Object> task) {
        String id=(String)task.get("id");
        Iterable<String> allText=()->repo.work(id).stream().filter(item->extracted.containsKey(item.get("id"))).flatMap(item->{
            var content=cached((String)item.get("id"));return java.util.stream.Stream.concat(java.util.stream.Stream.concat(content.texts().stream(),content.protectedTexts().stream()),java.util.stream.Stream.of((String)item.get("path")));
        }).iterator();
        var library=catalog.snapshot();var existing=repo.mappings(id);
        if(!existing.isEmpty()) {
            var outputs=repo.work(id).stream().filter(w->w.get("state").equals("DONE")).map(w->Json.object((String)w.get("payload")).get("output")).filter(String.class::isInstance).map(String.class::cast).toList();
            var pool=library.pool().stream().filter(candidate->outputs.stream().noneMatch(output->{
                var content=documents.read(committedFile(id,output));return java.util.stream.Stream.concat(java.util.stream.Stream.concat(content.texts().stream(),content.protectedTexts().stream()),java.util.stream.Stream.of(output)).anyMatch(text->Matcher.pattern(candidate).matcher(text).find());
            })).toList();
            library=new Catalog.Library(library.format(),library.formatVersion(),library.lineage(),library.version(),library.sensitive(),pool);
        }
        var plan=engine.plan(allText,library,existing);plans.put(id,plan);repo.mappings(id,plan.mappings());
        int floor=plan.mappings().stream().mapToInt(MappingEngine.Mapping::introducedVersion).max().orElse(0);
        var appliedLibrary=library;
        repo.payload(id,p->{p.put("requiredVersion",floor);p.put("lineage",appliedLibrary.lineage());p.put("plannedVersion",appliedLibrary.version());p.put("sources",plan.sources());p.put("skippedMappings",plan.skipped());});
        repo.db.write(c->{Store.execute(c,"UPDATE work_items SET state='PENDING' WHERE task_id=? AND state='EXTRACTED'",id);return null;});repo.state(id,"RUNNING","PROCESSING");
    }
    private Documents.Content cached(String fileId) {try{return Json.read(Files.readString(extracted.get(fileId)),Documents.Content.class);}catch(Exception e){throw new Problem("EXTRACTION_CACHE_UNAVAILABLE",e.getMessage());}}
    private String writeOutput(Map<String,Object> task,Map<String,Object> item,Path input,Documents.Content content,String sourceHash,long size,long mtime,List<Map<String,Object>> issues) throws Exception {
        String id=(String)task.get("id"),path=(String)item.get("path"),register=(String)task.get("registerId");boolean restore=task.get("kind").equals("restore");
        MappingEngine.Plan plan=plans.get(id);List<MappingEngine.Mapping> mappings=restore?((List<?>)task.getOrDefault("restoreMappings",List.of())).stream().map(m->Json.MAPPER.convertValue(m,MappingEngine.Mapping.class)).toList():plan.mappings();
        String activeRegister=restore?(String)task.get("restoreRegisterId"):register;
        UnaryOperator<String> transform=restore?t->engine.restore(t,mappings,activeRegister):t->engine.redact(t,plan,(String)task.get("mode"),register);
        String mappedPath=mapPath(path,transform,restore,activeRegister,Boolean.TRUE.equals(task.get("filenamePrefix")));
        if(restore) {
            String originalId=Objects.toString(task.get("restoreTaskId"),"");
            var exact=repo.db.read(c->Store.rows(c,"SELECT source_path FROM path_mappings WHERE task_id=? AND target_path=?",originalId,path));
            if(!exact.isEmpty())mappedPath=(String)exact.get(0).get("source_path");
            else for(Object value:(List<?>)task.getOrDefault("restorePaths",List.of())) {
                var entry=Json.MAPPER.convertValue(value,Map.class);if(path.equals(entry.get("target_path")))mappedPath=Objects.toString(entry.get("source_path"));
            }
        }
        mappedPath=repo.reservePath(id,path,mappedPath,(String)item.get("id"));
        Path directory=directory(task),destination=directory.resolve("files").resolve(mappedPath).normalize();
        if(!destination.startsWith(directory.resolve("files")))throw new Problem("PATH_OUTSIDE_ROOT");Roots.guard(directory,destination);
        Path stageRoot=stagingDirectory(id);guardStaging(id,stageRoot);Files.createDirectories(stageRoot);Path staging=stageRoot.resolve(UUID.randomUUID()+"."+Documents.extension(input));guardStaging(id,staging);documents.rewrite(input,staging,transform);
        if(!sourceHash.equals(hash(input))) {Files.deleteIfExists(staging);throw new Problem("SOURCE_CHANGED");}
        if(!restore) {
            var rewritten=documents.read(staging);
            if(content.texts().size()!=rewritten.texts().size())throw new Problem("FORMAT_VALIDATION_FAILED");
            for(int i=0;i<content.texts().size();i++) {
                String expected=content.texts().get(i),actual=engine.restore(rewritten.texts().get(i),mappings,register);
                if(!rewritten.texts().get(i).equals(transform.apply(expected)))throw new Problem("FORMAT_VALIDATION_FAILED","适配器未按计划写回");
                if(!expected.equals(actual))throw new Problem("REVERSIBILITY_FAILED");
            }
            for(var entry:plan.skipped().entrySet())for(var word:plan.sources())if(word.id().equals(entry.getKey())) {
                int count=content.texts().stream().mapToInt(t->Matcher.find(t,List.of(word)).size()).sum();
                if(count>0)issues.add(issue(path,entry.getValue(),"该词未替换",word.word(),count,"files/"+mappedPath));
            }
            for(String protectedText:content.protectedTexts())for(var hit:Matcher.find(protectedText,plan.sources()))issues.add(issue(path,"PROTECTED_STRUCTURE","结构位置保留",hit.word().word(),1,"files/"+mappedPath));
        }
        if(Files.exists(destination))throw new Problem("OUTPUT_PATH_CONFLICT",mappedPath);
        try(var channel=java.nio.channels.FileChannel.open(staging,StandardOpenOption.WRITE)) {channel.force(true);}
        String output="files/"+mappedPath;String digest=hash(staging);
        boolean sqlGroup=Documents.extension(input).equals("sql");
        var used=new HashSet<String>();
        if(!restore) {var ids=plan.mappings().stream().map(MappingEngine.Mapping::sourceId).collect(java.util.stream.Collectors.toSet());var texts=new ArrayList<>(content.texts());texts.add(path);for(String text:texts)for(var hit:Matcher.find(text,plan.sources()))if(ids.contains(hit.word().id()))used.add(hit.word().id());}
        repo.stageArtifact(id,item,output,staging,digest,sourceHash,size,mtime,Map.of("output",output,"completedAt",Instant.now().toString(),"usedMappings",used,"catalogVersion",task.getOrDefault("plannedVersion",catalog.snapshot().version())),issues,sqlGroup?"WAITING_GROUP":"READY");
        return output;
    }
    private String mapPath(String path,UnaryOperator<String> transform,boolean restore,String register,boolean prefix) {
        var result=new ArrayList<String>();String[] components=path.replace('\\','/').split("/");
        for(int i=0;i<components.length;i++) {
            String name=components[i];if(restore)name=name.replace("[swapy-"+register+"]","");
            int dot=i==components.length-1?name.lastIndexOf('.'):-1;String ext=dot>0?name.substring(dot):"",base=dot>0?name.substring(0,dot):name;
            name=transform.apply(base).replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]","_")+ext;
            if(!restore && prefix && i==components.length-1)name="[swapy-"+register+"]"+name;
            result.add(name);
        }
        return String.join("/",result);
    }
    private void checkCompleted(String id) {
        var task=repo.task(id);
        for(var item:repo.work(id))if(Set.of("DONE","FAILED").contains(item.get("state"))) {
            boolean changed;
            try {changed=!Objects.equals(item.get("source_hash"),hash(file(task,item)));}catch(Exception e){changed=true;}
            if(changed)repo.db.write(c->{Store.execute(c,"DELETE FROM issues WHERE task_id=? AND file_id=?",id,item.get("id"));Store.execute(c,"UPDATE work_items SET state='PENDING' WHERE id=?",item.get("id"));return null;});
        }
        repo.state(id,"RUNNING","PROCESSING");
    }
    private void finishTask(String id,String forcedStatus) {
        try {discardUnreadySql(id);publishTree(id);}catch(Exception e){repo.issue(id,"publication",issue("","OUTPUT_PUBLICATION_FAILED",e.getMessage(),null,0,null));repo.state(id,"FAILED","PUBLICATION_FAILED");try{Reports.write(outputDirectory(id),repo.task(id),repo.issues(id));}catch(Exception ignored){}cleanupWork(id);return;}
        if("INTERRUPTED".equals(forcedStatus))for(var work:repo.work(id))if(!Set.of("DONE","FAILED","SKIPPED").contains(work.get("state")))repo.issue(id,(String)work.get("id"),issue((String)work.get("path"),"TASK_INTERRUPTED","任务中断，文件未完成",null,0,null));
        String status=forcedStatus;
        if(status==null)status=repo.issues(id).stream().anyMatch(i->!"SENSITIVE_HIT".equals(i.get("code")))?"PARTIAL":"SUCCEEDED";
        if("redact".equals(repo.task(id).get("kind"))) {
            int floor=repo.successfulMappings(id).stream().mapToInt(MappingEngine.Mapping::introducedVersion).max().orElse(0);repo.payload(id,p->p.put("requiredVersion",floor));
        }
        try {var reportTask=repo.task(id);reportTask.put("status",status);Reports.write(directory(reportTask),reportTask,repo.issues(id));repo.state(id,status,"FINISHED");}
        catch(Exception e){repo.state(id,"FAILED","REPORT_FAILED");}
        plans.remove(id);
        for(var work:repo.work(id))extracted.remove(work.get("id"));cleanupWork(id);
        cleanupStaging(id);
    }
    private void cleanupStaging(String id) {
        Path stage=stagingDirectory(id);if(!Files.exists(stage,LinkOption.NOFOLLOW_LINKS))return;
        if(!repo.db.read(c->Store.rows(c,"SELECT id FROM artifacts WHERE task_id=? AND state IN ('READY','WAITING_GROUP')",id)).isEmpty())return;
        try{guardStaging(id,stage);Files.walkFileTree(stage,new SimpleFileVisitor<>(){
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException{guardStaging(id,file);Files.delete(file);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException error)throws IOException{if(error!=null)throw error;guardStaging(id,dir);Files.delete(dir);return FileVisitResult.CONTINUE;}
        });}catch(Exception e){System.getLogger(TaskService.class.getName()).log(System.Logger.Level.WARNING,"结果暂存清理失败："+id);}
    }
    private void cleanupWork(String id) {
        Path work=repo.db.data.resolve("work").resolve(id);
        if(!Files.exists(work,LinkOption.NOFOLLOW_LINKS))return;
        try{Roots.guard(repo.db.data,work);Files.walkFileTree(work,new SimpleFileVisitor<>(){
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException{Roots.guard(repo.db.data,file);Files.delete(file);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException error)throws IOException{if(error!=null)throw error;Roots.guard(repo.db.data,dir);Files.delete(dir);return FileVisitResult.CONTINUE;}
        });}catch(Exception e){System.getLogger(TaskService.class.getName()).log(System.Logger.Level.WARNING,"工作缓存清理失败："+id);}
    }
    public static Map<String,Object> issue(String path,String code,String message,String word,int count,String output) {
        var issue=new LinkedHashMap<String,Object>();issue.put("path",path);issue.put("code",code);issue.put("message",message);
        if(word!=null){issue.put("word",word);issue.put("count",count);}if(output!=null)issue.put("output",output);return issue;
    }
    public static String hash(Path path) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(path)) {byte[] buffer=new byte[65536];int read;while((read=in.read(buffer))!=-1)digest.update(buffer,0,read);}
        return HexFormat.of().formatHex(digest.digest());
    }
    @Override public void close() {
        closing=true;coordinator.shutdown();
        try {coordinator.awaitTermination(15,TimeUnit.SECONDS);workers.shutdown();if(!workers.awaitTermination(30,TimeUnit.SECONDS)){workers.shutdownNow();workers.awaitTermination(10,TimeUnit.SECONDS);}}
        catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
}
