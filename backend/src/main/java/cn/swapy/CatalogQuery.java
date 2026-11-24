package cn.swapy;

import java.util.*;

public final class CatalogQuery {
    public record Options(String kind,String q,String role,String pos,String language,String status,int page,int pageSize) {}
    private final Store db;
    public CatalogQuery(Store db){this.db=db;db.write(c->{Store.execute(c,"CREATE INDEX IF NOT EXISTS word_attributes ON word_entries(kind,json_extract(payload,'$.status'),json_extract(payload,'$.role'),json_extract(payload,'$.pos'),json_extract(payload,'$.form'))");return null;});}
    public Map<String,Object> summary() {
        return db.read(c->{var row=Store.rows(c,"SELECT COALESCE((SELECT version FROM catalog_releases ORDER BY version DESC LIMIT 1),0) AS version, COALESCE((SELECT lineage FROM catalog_releases ORDER BY version DESC LIMIT 1),'') AS lineage,(SELECT count(*) FROM word_entries WHERE kind='pool' AND json_extract(payload,'$.status')='启用') AS poolCount,(SELECT count(*) FROM word_entries WHERE kind='sensitive' AND json_extract(payload,'$.status')='启用') AS sensitiveCount").get(0);
            var result=new LinkedHashMap<String,Object>(row);result.put("poolCount",((Number)row.get("poolCount")).longValue());result.put("sensitiveCount",((Number)row.get("sensitiveCount")).longValue());result.put("roles",WordTypes.ROLES);return result;});
    }
    public Map<String,Object> search(Options o) {
        if(o==null||!Set.of("pool","sensitive").contains(o.kind())||!Set.of("all","zh","en").contains(o.language())||!Set.of("all","启用","弃用").contains(o.status())||o.page()<1||o.pageSize()<1||o.pageSize()>100||o.q()==null||o.q().length()>512||o.role()==null||o.pos()==null||o.role().length()>128||o.pos().length()>128)throw new Problem("INVALID_CATALOG_QUERY");
        var sql=new StringBuilder(" WHERE kind=?");var params=new ArrayList<Object>();params.add(o.kind());
        if(!o.status().equals("all")){sql.append(" AND json_extract(payload,'$.status')=?");params.add(o.status());}
        if(!o.role().isBlank()){sql.append(" AND json_extract(payload,'$.role')=?");params.add(o.role());}
        if(!o.pos().isBlank()){sql.append(" AND json_extract(payload,'$.pos')=?");params.add(o.pos());}
        if(o.language().equals("en"))sql.append(" AND json_extract(payload,'$.form') LIKE '%英文%'");
        if(o.language().equals("zh"))sql.append(" AND json_extract(payload,'$.form') IN ('中文词','中文代号')");
        if(!o.q().isBlank()){String pattern="%"+o.q().strip().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";sql.append(" AND (lower(json_extract(payload,'$.word')) LIKE ? ESCAPE '\\' OR lower(id) LIKE ? ESCAPE '\\')");params.add(pattern);params.add(pattern);}
        return db.read(c->{c.setAutoCommit(false);long total=((Number)Store.rows(c,"SELECT count(*) AS n FROM word_entries"+sql,params.toArray()).get(0).get("n")).longValue();
            var paged=new ArrayList<>(params);paged.add(o.pageSize());paged.add(((long)o.page()-1)*o.pageSize());
            var items=Store.rows(c,"SELECT payload FROM word_entries"+sql+" ORDER BY rowid LIMIT ? OFFSET ?",paged.toArray()).stream().map(r->Json.read((String)r.get("payload"),Catalog.Word.class)).toList();
            return Map.of("items",items,"total",total,"page",o.page(),"pageSize",o.pageSize());});
    }
}
