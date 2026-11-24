package cn.swapy.packages;

import cn.swapy.*;
import cn.swapy.matching.MappingEngine;
import cn.swapy.tasks.Reports;
import cn.swapy.tasks.TaskRepository;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

public final class PackageService {
    private static final int MAX_PACKAGE=64*1024*1024,MAX_EXPANDED=256*1024*1024,MAX_ENTRIES=10000;
    private static final Set<String> READY=Set.of("SUCCEEDED","PARTIAL","INTERRUPTED");
    private final Store db;private final Catalog catalog;private final TaskRepository repo;
    public PackageService(Store db,Catalog catalog){this.db=db;this.catalog=catalog;repo=new TaskRepository(db);}
    public byte[] exportSelected(List<String> ids) {
        if(ids==null || ids.isEmpty() || ids.size()>MAX_ENTRIES || new HashSet<>(ids).size()!=ids.size())throw new Problem("INVALID_SELECTION");
        var packages=new LinkedHashMap<String,byte[]>();
        for(String id:ids)packages.put(id+".swapy",single(snapshot(id)));
        return ids.size()==1?packages.values().iterator().next():zip(packages,true);
    }
    public byte[] exportAll() {return exportSelected(repo.tasks().stream().filter(t->"redact".equals(t.get("kind")) && READY.contains(t.get("status"))).map(t->(String)t.get("id")).toList());}
    @SuppressWarnings("unchecked") private Map<String,Object> snapshot(String id) {
        var task=repo.task(id);if(!"redact".equals(task.get("kind")))throw new Problem("NOT_REDACTION_TASK");
        if(!READY.contains(task.get("status")))throw new Problem("TASK_NOT_READY");
        if(task.get("packageSnapshot") instanceof Map<?,?> saved)return (Map<String,Object>)saved;
        var record=new LinkedHashMap<String,Object>();record.put("format","swapy-task");record.put("core_version",1);record.put("kind","redaction-task");record.put("task_id",id);
        record.put("catalog_lineage",task.getOrDefault("lineage",""));record.put("required_library_version",task.getOrDefault("requiredVersion",0));
        record.put("mode",task.getOrDefault("mode","A"));record.put("case_transform","ascii-case-bijection-v1");record.put("register_id",task.get("registerId"));record.put("key",task.get("key"));
        record.put("status",task.get("status"));record.put("created_at",task.get("created_at"));record.put("extensions",Map.of());
        var mappings=repo.successfulMappings(id).stream().map(m->Map.<String,Object>of("source_id",m.sourceId(),"replacement_id",m.targetId(),"ordinal",m.ordinal(),"form",m.form(),"role",m.role(),"introduced_version",m.introducedVersion())).toList();
        var paths=db.read(c->Store.rows(c,"SELECT source_path,target_path,payload FROM path_mappings p WHERE task_id=? AND EXISTS(SELECT 1 FROM artifacts a WHERE a.task_id=p.task_id AND a.path='files/'||p.target_path AND a.state='PUBLISHED') ORDER BY source_path",id));
        return new LinkedHashMap<>(Map.of("record",record,"mappings",mappings,"paths",paths));
    }
    @SuppressWarnings("unchecked") private byte[] single(Map<String,Object> snapshot) {
        var members=new LinkedHashMap<String,byte[]>();members.put("record.json",Json.write(snapshot.get("record")).getBytes(StandardCharsets.UTF_8));
        members.put("mappings.json",Json.write(snapshot.get("mappings")).getBytes(StandardCharsets.UTF_8));members.put("paths.json",Json.write(snapshot.get("paths")).getBytes(StandardCharsets.UTF_8));
        var hashes=new LinkedHashMap<String,String>();members.forEach((name,data)->hashes.put(name,sha(data)));
        members.put("manifest.json",Json.write(Map.of("format","swapy-manifest","core_version",1,"members",hashes)).getBytes(StandardCharsets.UTF_8));return zip(members,false);
    }
    public List<String> importPackages(byte[] bytes) {
        var top=unzip(bytes);var snapshots=new ArrayList<Map<String,Object>>();
        if(top.containsKey("manifest.json"))snapshots.add(parse(top));
        else {
            if(top.isEmpty())throw new Problem("INVALID_PACKAGE","空合集");
            long expanded=0;
            for(var entry:top.entrySet()) {
                if(!entry.getKey().endsWith(".swapy"))throw new Problem("INVALID_PACKAGE","合集仅允许.swapy文件");
                var inside=unzip(entry.getValue());for(byte[] data:inside.values()){expanded+=data.length;if(expanded>MAX_EXPANDED)throw new Problem("PACKAGE_LIMIT");}
                snapshots.add(parse(inside));
            }
        }
        var seen=new HashSet<String>();
        var library=catalog.snapshot();
        for(var snapshot:snapshots){var record=object(snapshot.get("record"));String id=(String)record.get("task_id");if(!seen.add(id))throw new Problem("ID_CONFLICT",id);validateReferences(record,list(snapshot.get("mappings")),library,false);}
        return db.write(c->{
            var result=new ArrayList<String>();
            for(var snapshot:snapshots) {
                var record=object(snapshot.get("record"));String id=(String)record.get("task_id");result.add(id);
                var existing=Store.rows(c,"SELECT kind,payload FROM tasks WHERE id=?",id);
                if(!existing.isEmpty()) {
                    if(!existing.get(0).get("kind").equals("redact"))throw new Problem("ID_CONFLICT",id);
                    var current=Json.object((String)existing.get(0).get("payload"));Object prior=current.get("packageSnapshot");
                    if(prior==null)prior=snapshot(id);
                    if(!Json.write(prior).equals(Json.write(snapshot)))throw new Problem("ID_CONFLICT",id);
                    continue;
                }
                var payload=new LinkedHashMap<String,Object>();payload.put("packageSnapshot",snapshot);payload.put("imported",true);
                payload.put("mode",record.get("mode"));payload.put("registerId",record.get("register_id"));payload.put("key",record.get("key"));
                payload.put("requiredVersion",record.get("required_library_version"));payload.put("lineage",record.get("catalog_lineage"));payload.put("totalKnown",true);
                String created=(String)record.get("created_at");
                Store.execute(c,"INSERT INTO tasks(id,kind,status,phase,payload,created_at,updated_at) VALUES(?,'redact',?,'FINISHED',?,?,?)",id,record.get("status"),Json.write(payload),created,created);
                for(Object value:list(snapshot.get("paths"))) {
                    var path=object(value);Store.execute(c,"INSERT INTO path_mappings(task_id,source_path,target_path,payload) VALUES(?,?,?,?)",id,path.get("source_path"),path.get("target_path"),Objects.toString(path.get("payload"),"{}"));
                }
            }
            return result;
        });
    }
    private Map<String,Object> parse(Map<String,byte[]> members) {
        if(!members.keySet().equals(Set.of("manifest.json","record.json","mappings.json","paths.json")))throw new Problem("INVALID_PACKAGE","成员不完整");
        var manifest=Json.object(text(members.get("manifest.json")));if(!"swapy-manifest".equals(manifest.get("format")) || integer(manifest.get("core_version"))!=1)throw new Problem("INVALID_PACKAGE");
        var hashes=object(manifest.get("members"));
        for(String name:List.of("record.json","mappings.json","paths.json"))if(!sha(members.get(name)).equals(hashes.get(name)))throw new Problem("CHECKSUM_MISMATCH",name);
        var record=Json.object(text(members.get("record.json")));var mappings=Json.read(text(members.get("mappings.json")),List.class);var paths=Json.read(text(members.get("paths.json")),List.class);
        if(!"swapy-task".equals(record.get("format")) || integer(record.get("core_version"))!=1 || !"redaction-task".equals(record.get("kind")))throw new Problem("NOT_REDACTION_TASK");
        validId(record.get("task_id"));if(integer(record.get("required_library_version"))<0)throw new Problem("INVALID_PACKAGE");
        if(!Set.of("A","B").contains(record.get("mode")) || !READY.contains(record.get("status")) || !"ascii-case-bijection-v1".equals(record.get("case_transform")))throw new Problem("UNSUPPORTED_CORE_CAPABILITY");
        for(String field:List.of("catalog_lineage","register_id","key","created_at"))if(!(record.get(field) instanceof String s)||s.length()>1024)throw new Problem("INVALID_PACKAGE",field);
        try{java.time.Instant.parse((String)record.get("created_at"));}catch(Exception e){throw new Problem("INVALID_PACKAGE","created_at");}
        var sources=new HashSet<String>();var targets=new HashSet<String>();var ordinals=new HashSet<Integer>();
        for(Object value:mappings) {
            var m=object(value);String source=validId(m.get("source_id")),target=validId(m.get("replacement_id"));
            if(!sources.add(source)||!targets.add(target)||!ordinals.add(integer(m.get("ordinal")))||integer(m.get("ordinal"))<=0)throw new Problem("INVALID_MAPPING");
            if(!(m.get("form") instanceof String)||!(m.get("role") instanceof String))throw new Problem("INVALID_MAPPING");
            int introduced=integer(m.get("introduced_version"));if(introduced<1||introduced>integer(record.get("required_library_version")))throw new Problem("INVALID_MAPPING_VERSION");
        }
        var sourcePaths=new HashSet<String>();var targetPaths=new HashSet<String>();
        for(Object value:paths) {
            var path=object(value);String a=relative(path.get("source_path")),b=relative(path.get("target_path"));
            if(!sourcePaths.add(a)||!targetPaths.add(b))throw new Problem("INVALID_PATH_MAPPING");
        }
        return new LinkedHashMap<>(Map.of("record",record,"mappings",mappings,"paths",paths));
    }
    public List<MappingEngine.Mapping> resolve(String id) {
        var task=repo.task(id);if(!"redact".equals(task.get("kind")))throw new Problem("NOT_REDACTION_TASK");
        if(!READY.contains(task.get("status")))throw new Problem("TASK_NOT_READY");
        var library=catalog.snapshot();int required=integer(task.getOrDefault("requiredVersion",0));
        if(library.version()<required)throw new Problem("LIBRARY_TOO_OLD","当前 "+library.version()+"，需要 "+required);
        if(required>0 && !library.lineage().equals(task.get("lineage")))throw new Problem("CATALOG_LINEAGE_CONFLICT");
        if(!Boolean.TRUE.equals(task.get("imported")))return repo.successfulMappings(id);
        var snapshot=object(task.get("packageSnapshot"));var result=validateReferences(object(snapshot.get("record")),list(snapshot.get("mappings")),library,true);
        repo.mappings(id,result);return result;
    }
    private List<MappingEngine.Mapping> validateReferences(Map<String,Object> record,List<?> mappings,Catalog.Library library,boolean required) {
        int minimum=integer(record.get("required_library_version"));
        if(library.version()<minimum||minimum>0&&!library.lineage().equals(record.get("catalog_lineage"))){if(required)throw new Problem(library.version()<minimum?"LIBRARY_TOO_OLD":"CATALOG_LINEAGE_CONFLICT");return List.of();}
        var sources=new HashMap<String,Catalog.Word>();var targets=new HashMap<String,Catalog.Word>();library.sensitive().forEach(w->sources.put(w.id(),w));library.pool().forEach(w->targets.put(w.id(),w));
        var result=new ArrayList<MappingEngine.Mapping>();var originals=new HashSet<String>();var replacements=new HashSet<String>();
        for(Object value:mappings) {
            var m=object(value);var a=sources.get(m.get("source_id"));var b=targets.get(m.get("replacement_id"));if(a==null||b==null)throw new Problem("INVALID_MAPPING_REFERENCE");
            int actual=Math.max(a.introducedVersion(),b.introducedVersion());
            if(actual>minimum||actual!=integer(m.get("introduced_version")))throw new Problem("INVALID_MAPPING_VERSION");
            if(!cn.swapy.WordTypes.compatible(a,b)||!a.form().equals(m.get("form"))||!a.role().equals(m.get("role"))
                    ||!originals.add(a.word().toLowerCase(Locale.ROOT))||!replacements.add(b.word().toLowerCase(Locale.ROOT)))throw new Problem("INVALID_MAPPING_REFERENCE");
            result.add(new MappingEngine.Mapping(a.id(),b.id(),a.word(),b.word(),a.form(),a.role(),integer(m.get("ordinal")),actual));
        }
        validateVisibleMappings(result);return result;
    }
    private void validateVisibleMappings(List<MappingEngine.Mapping> mappings) {
        var ordinals=new HashSet<Integer>();var originals=new HashSet<String>();var targets=new HashSet<String>();
        for(var m:mappings){
            if(m.form()==null||m.role()==null||m.form().isBlank()||m.role().isBlank()||m.original()==null||m.original().isBlank()||m.replacement()==null||m.replacement().isBlank()||m.original().length()>512||m.replacement().length()>512||m.ordinal()<1||!ordinals.add(m.ordinal())||!originals.add(m.original().toLowerCase(Locale.ROOT))||!targets.add(m.replacement().toLowerCase(Locale.ROOT)))throw new Problem("INVALID_MAPPING");
            if(m.form().contains("英文")&&m.original().chars().filter(c->c>='A'&&c<='Z'||c>='a'&&c<='z').count()!=m.replacement().chars().filter(c->c>='A'&&c<='Z'||c>='a'&&c<='z').count())throw new Problem("INVALID_MAPPING_CASE");
        }
        for(var a:mappings)for(var b:mappings)if(a!=b&&a.replacement().toLowerCase(Locale.ROOT).contains(b.replacement().toLowerCase(Locale.ROOT)))throw new Problem("AMBIGUOUS_MAPPING");
    }
    public Map<String,Object> passwordbook(String id) {
        var task=repo.task(id);var maps=resolve(id);
        var paths=db.read(c->Store.rows(c,"SELECT source_path,target_path FROM path_mappings p WHERE task_id=? AND (? OR EXISTS(SELECT 1 FROM artifacts a WHERE a.task_id=p.task_id AND a.path='files/'||p.target_path AND a.state='PUBLISHED')) ORDER BY source_path",id,Boolean.TRUE.equals(task.get("imported"))));
        return Map.of("format","swapy-passwordbook","core_version",1,"taskId",id,"registerId",task.get("registerId"),"mappings",maps,"paths",paths,"caseTransform","ascii-case-bijection-v1");
    }
    public byte[] passwordbookHtml(String id) {
        var book=passwordbook(id);StringBuilder html=new StringBuilder("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>Swapy 密码本</title><style>body{font:16px/1.8 sans-serif;margin:32px}td,th{padding:8px 24px;border-bottom:1px solid #ddd}</style><h1>可视化密码本</h1><p>");
        html.append(Reports.escape(id)).append("</p><table><tr><th>原词</th><th>平替词</th><th>类型</th></tr>");
        for(Object value:list(book.get("mappings"))){var m=Json.MAPPER.convertValue(value,MappingEngine.Mapping.class);html.append("<tr><td>").append(Reports.escape(m.original())).append("</td><td>").append(Reports.escape(m.replacement())).append("</td><td>").append(Reports.escape(m.role())).append("</td></tr>");}
        return html.append("</table></html>").toString().getBytes(StandardCharsets.UTF_8);
    }
    public Map<String,Object> loadCredential(byte[] bytes) {
        if(bytes.length>MAX_PACKAGE)throw new Problem("PACKAGE_LIMIT");
        if(bytes.length>1 && bytes[0]=='P' && bytes[1]=='K') {
            var ids=importPackages(bytes);if(ids.size()!=1)throw new Problem("SINGLE_CREDENTIAL_REQUIRED");
            var task=repo.task(ids.get(0));return Map.of("type","resolved","mappings",resolve(ids.get(0)),"registerId",task.get("registerId"),"taskId",ids.get(0));
        }
        var book=Json.object(text(bytes));
        if(!"swapy-passwordbook".equals(book.get("format")) || integer(book.get("core_version"))!=1 || !(book.get("registerId") instanceof String))throw new Problem("INVALID_PASSWORDBOOK");
        var mappings=new ArrayList<MappingEngine.Mapping>();var sources=new HashSet<String>();var targets=new HashSet<String>();
        for(Object raw:list(book.get("mappings"))) {
            var m=Json.MAPPER.convertValue(raw,MappingEngine.Mapping.class);validId(m.sourceId());validId(m.targetId());
            if(m.original()==null||m.original().isBlank()||m.replacement()==null||m.replacement().isBlank()||!sources.add(m.sourceId())||!targets.add(m.replacement().toLowerCase(Locale.ROOT)))throw new Problem("INVALID_MAPPING");
            mappings.add(m);
        }
        validId(book.get("registerId"));validateVisibleMappings(mappings);
        return Map.of("type","resolved","mappings",mappings,"registerId",book.get("registerId"),"paths",book.getOrDefault("paths",List.of()));
    }
    private static byte[] zip(Map<String,byte[]> members,boolean store) {
        try {var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out,StandardCharsets.UTF_8)) {
            for(var member:members.entrySet()) {var entry=new ZipEntry(member.getKey());entry.setTime(0);
                if(store){CRC32 crc=new CRC32();crc.update(member.getValue());entry.setMethod(ZipEntry.STORED);entry.setSize(member.getValue().length);entry.setCrc(crc.getValue());}
                zip.putNextEntry(entry);zip.write(member.getValue());zip.closeEntry();
            }
        }return out.toByteArray();}catch(Exception e){throw new Problem("PACKAGE_WRITE_FAILED",e.getMessage());}
    }
    private static Map<String,byte[]> unzip(byte[] data) {
        if(data.length>MAX_PACKAGE)throw new Problem("PACKAGE_LIMIT");
        try(var zip=new ZipInputStream(new ByteArrayInputStream(data),StandardCharsets.UTF_8)) {
            var result=new LinkedHashMap<String,byte[]>();long total=0;ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                if(entry.isDirectory()||entry.getName().contains("/")||entry.getName().contains("\\")||entry.getName().contains("..")||entry.getName().contains(":"))throw new Problem("INVALID_PACKAGE_PATH");
                if(result.size()>=MAX_ENTRIES || result.containsKey(entry.getName()))throw new Problem("PACKAGE_LIMIT");
                var bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int read;
                while((read=zip.read(buffer))!=-1){total+=read;if(total>MAX_EXPANDED)throw new Problem("PACKAGE_LIMIT");bytes.write(buffer,0,read);}
                result.put(entry.getName(),bytes.toByteArray());
            }return result;
        }catch(Problem e){throw e;}catch(Exception e){throw new Problem("INVALID_PACKAGE",e.getMessage());}
    }
    public static String sha(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String text(byte[] bytes){return new String(bytes,StandardCharsets.UTF_8);}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){if(!(value instanceof Map<?,?>))throw new Problem("INVALID_PACKAGE");return (Map<String,Object>)value;}
    private static List<?> list(Object value){if(!(value instanceof List<?> list)||list.size()>100000)throw new Problem("INVALID_PACKAGE");return list;}
    private static int integer(Object value){if(!(value instanceof Number n)||n.doubleValue()!=n.intValue())throw new Problem("INVALID_PACKAGE_NUMBER");return n.intValue();}
    private static String validId(Object value){if(!(value instanceof String s)||!s.matches("[A-Za-z][A-Za-z0-9_-]{1,127}"))throw new Problem("INVALID_ID");return s;}
    private static String relative(Object value){if(!(value instanceof String s)||s.isEmpty()||s.startsWith("/")||s.startsWith("\\")||s.contains(":")||Arrays.asList(s.replace('\\','/').split("/")).contains(".."))throw new Problem("INVALID_PATH_MAPPING");return s;}
}
