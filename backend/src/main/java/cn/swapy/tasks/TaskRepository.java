package cn.swapy.tasks;

import cn.swapy.*;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;

public final class TaskRepository {
    public final Store db;
    public TaskRepository(Store db) {this.db=db;}
    public Map<String,Object> task(String id) {
        return db.read(c->{var rows=Store.rows(c,"SELECT * FROM tasks WHERE id=?",id);if(rows.isEmpty())throw new Problem("TASK_NOT_LOCAL",id);return expand(rows.get(0));});
    }
    public List<Map<String,Object>> tasks() {return db.read(c->Store.rows(c,"SELECT * FROM tasks ORDER BY created_at DESC").stream().map(this::expand).toList());}
    private Map<String,Object> expand(Map<String,Object> row) {
        var result=new LinkedHashMap<>(Json.object((String)row.get("payload")));result.putAll(row);result.remove("payload");return result;
    }
    public void create(String id,String kind,Map<String,Object> payload) {
        db.write(c->{String now=Instant.now().toString();Store.execute(c,"INSERT INTO tasks(id,kind,status,phase,payload,created_at,updated_at) VALUES(?,?,'QUEUED','CREATED',?,?,?)",id,kind,Json.write(payload),now,now);return null;});
    }
    public void state(String id,String status,String phase) {
        db.write(c->{Store.execute(c,"UPDATE tasks SET status=?,phase=?,updated_at=? WHERE id=?",status,phase,Instant.now().toString(),id);return null;});
    }
    public void payload(String id,java.util.function.Consumer<Map<String,Object>> modify) {
        db.write(c->{var row=Store.rows(c,"SELECT payload FROM tasks WHERE id=?",id).get(0);var value=Json.object((String)row.get("payload"));modify.accept(value);Store.execute(c,"UPDATE tasks SET payload=?,updated_at=? WHERE id=?",Json.write(value),Instant.now().toString(),id);return null;});
    }
    public List<Map<String,Object>> work(String id) {return db.read(c->Store.rows(c,"SELECT * FROM work_items WHERE task_id=? ORDER BY path",id));}
    public void addWork(String task,String path) {
        db.write(c->{Store.execute(c,"INSERT OR IGNORE INTO work_items(id,task_id,path,state) VALUES(?,?,?,'PENDING')","F_"+UUID.randomUUID(),task,path);return null;});
    }
    public String reservePath(String task,String source,String candidate,String fileId) {
        return db.write(c->{
            var existing=Store.rows(c,"SELECT target_path FROM path_mappings WHERE task_id=? AND source_path=?",task,source);if(!existing.isEmpty())return (String)existing.get(0).get("target_path");
            String base=safePath(candidate,fileId),target=base;int attempt=0;
            var occupied=Store.rows(c,"SELECT target_path FROM path_mappings WHERE task_id=?",task).stream().map(row->canonical((String)row.get("target_path"))).collect(java.util.stream.Collectors.toSet());
            while(occupied.contains(canonical(target))){int dot=base.lastIndexOf('.'),slash=base.lastIndexOf('/');if(dot<slash)dot=-1;String suffix="~"+fileId.replace("F_","").substring(0,Math.min(12,fileId.replace("F_","").length()))+(attempt++==0?"":"-"+attempt);target=dot<0?base+suffix:base.substring(0,dot)+suffix+base.substring(dot);}
            Store.execute(c,"INSERT INTO path_mappings(task_id,source_path,target_path,payload) VALUES(?,?,?,?)",task,source,target,Json.write(Map.of("fileId",fileId)));return target;
        });
    }
    private static String canonical(String path){return java.text.Normalizer.normalize(path,java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT);}
    private String safePath(String input,String fileId) {
        var parts=new ArrayList<String>();
        for(String part:input.replace('\\','/').split("/")) {
            String cleaned=part.replaceAll("[. ]+$","");if(cleaned.isBlank()||cleaned.equals(".."))cleaned="_";
            String stem=cleaned.contains(".")?cleaned.substring(0,cleaned.indexOf('.')):cleaned;
            if(stem.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])"))cleaned="_"+cleaned;
            if(cleaned.codePointCount(0,cleaned.length())>160){int dot=cleaned.lastIndexOf('.');String extension=dot>0&&cleaned.length()-dot<20?cleaned.substring(dot):"";cleaned=cleaned.substring(0,cleaned.offsetByCodePoints(0,100))+"~"+fileId.substring(0,Math.min(fileId.length(),12))+extension;}
            parts.add(cleaned);
        }
        return String.join("/",parts);
    }
    public void addArchiveWork(String task,String path,Map<String,Object> payload) {
        db.write(c->{
            var old=Store.rows(c,"SELECT id,payload FROM work_items WHERE task_id=? AND path=?",task,path);
            if(old.isEmpty())Store.execute(c,"INSERT INTO work_items(id,task_id,path,state,payload) VALUES(?,?,?,'PENDING',?)","F_"+UUID.randomUUID(),task,path,Json.write(payload));
            else {var previous=Json.object((String)old.get(0).get("payload"));boolean changed=!Objects.equals(previous.get("archiveHash"),payload.get("archiveHash"));Store.execute(c,"UPDATE work_items SET payload=?,state=CASE WHEN ? THEN 'PENDING' ELSE state END WHERE id=?",Json.write(payload),changed,old.get(0).get("id"));}
            return null;
        });
    }
    public Map<String,Object> claim(String task,String expectedState) {
        return db.write(c->{var found=Store.rows(c,"SELECT * FROM work_items WHERE task_id=? AND state=? ORDER BY path LIMIT 1",task,expectedState);if(found.isEmpty())return null;
            var item=found.get(0);Store.execute(c,"UPDATE work_items SET state='RUNNING' WHERE id=?",item.get("id"));return item;});
    }
    public Map<String,Object> claimNonSql(String task) {
        return db.write(c->{var found=Store.rows(c,"SELECT * FROM work_items WHERE task_id=? AND state='PENDING' AND lower(path) NOT LIKE '%.sql' ORDER BY path LIMIT 1",task);if(found.isEmpty())return null;var row=found.get(0);Store.execute(c,"UPDATE work_items SET state='RUNNING' WHERE id=?",row.get("id"));return row;});
    }
    public void finish(String task,Map<String,Object> work,String state,String hash,long size,long mtime,Map<String,Object> payload,List<Map<String,Object>> issues) {
        db.write(c->{
            Store.execute(c,"DELETE FROM issues WHERE task_id=? AND file_id=?",task,work.get("id"));
            for(var issue:issues) addIssue(c,task,(String)work.get("id"),issue);
            Store.execute(c,"UPDATE work_items SET state=?,source_hash=?,size=?,mtime=?,payload=? WHERE id=?",state,hash,size,mtime,Json.write(payload),work.get("id"));return null;
        });
    }
    public void stageArtifact(String task,Map<String,Object> work,String output,java.nio.file.Path staged,String digest,String sourceHash,long size,long mtime,Map<String,Object> payload,List<Map<String,Object>> issues,String state) {
        db.write(c->{
            Store.execute(c,"INSERT INTO artifacts(id,task_id,file_id,path,staging,hash,state) VALUES(?,?,?,?,?,?,?)","A_"+UUID.randomUUID(),task,work.get("id"),output,staged.toString(),digest,state);
            Store.execute(c,"DELETE FROM issues WHERE task_id=? AND file_id=?",task,work.get("id"));
            for(var issue:issues)addIssue(c,task,(String)work.get("id"),issue);
            Store.execute(c,"UPDATE work_items SET state='DONE',source_hash=?,size=?,mtime=?,payload=? WHERE id=?",sourceHash,size,mtime,Json.write(payload),work.get("id"));return null;
        });
    }
    public void issue(String task,String file,Map<String,Object> issue) {db.write(c->{addIssue(c,task,file,issue);return null;});}
    private void addIssue(Connection c,String task,String file,Map<String,Object> issue) throws Exception {
        Store.execute(c,"INSERT INTO issues(id,task_id,file_id,code,payload) VALUES(?,?,?,?,?)","I_"+UUID.randomUUID(),task,file,issue.get("code"),Json.write(issue));
    }
    public List<Map<String,Object>> issues(String id) {
        return db.read(c->Store.rows(c,"SELECT file_id,payload FROM issues WHERE task_id=? ORDER BY file_id,id",id).stream().map(row->{var issue=Json.object((String)row.get("payload"));issue.put("fileId",row.get("file_id"));return issue;}).toList());
    }
    public void mappings(String task,List<cn.swapy.matching.MappingEngine.Mapping> mappings) {
        db.write(c->{for(var m:mappings) Store.execute(c,"INSERT OR IGNORE INTO task_mappings(task_id,source_id,target_id,payload) VALUES(?,?,?,?)",task,m.sourceId(),m.targetId(),Json.write(m));return null;});
    }
    public List<cn.swapy.matching.MappingEngine.Mapping> mappings(String task) {
        return db.read(c->Store.rows(c,"SELECT payload FROM task_mappings WHERE task_id=? ORDER BY rowid",task).stream().map(r->Json.read((String)r.get("payload"),cn.swapy.matching.MappingEngine.Mapping.class)).toList());
    }
    public List<cn.swapy.matching.MappingEngine.Mapping> successfulMappings(String task) {
        var ids=new HashSet<String>();
        var committed=db.read(c->Store.rows(c,"SELECT w.payload FROM work_items w WHERE w.task_id=? AND w.state='DONE' AND EXISTS(SELECT 1 FROM artifacts a WHERE a.file_id=w.id AND a.state='PUBLISHED')",task));
        for(var row:committed){Object value=Json.object((String)row.get("payload")).get("usedMappings");if(value instanceof List<?> list)list.forEach(id->ids.add(String.valueOf(id)));}
        return mappings(task).stream().filter(m->ids.contains(m.sourceId())).toList();
    }
}
