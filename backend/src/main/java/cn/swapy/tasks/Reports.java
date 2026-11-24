package cn.swapy.tasks;

import java.nio.file.*;
import java.util.*;

public final class Reports {
    private Reports() {}
    public static String escape(Object v) {return String.valueOf(v==null?"":v).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    public static void write(Path taskDirectory,Map<String,Object> task,List<Map<String,Object>> issues) throws Exception {
        cn.swapy.Roots.guard(taskDirectory,taskDirectory.resolve("report.html"));cn.swapy.Roots.guard(taskDirectory,taskDirectory.resolve(".staging"));
        var html=new StringBuilder("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>Swapy 结果报告</title><style>body{font:15px/1.7 sans-serif;margin:32px;color:#193a37}table{width:100%;border-collapse:collapse}td,th{padding:10px;text-align:left;border-bottom:1px solid #ddd;overflow-wrap:anywhere}</style><h1>任务结果报告</h1><p>");
        html.append(escape(task.get("id"))).append(" · ").append(escape(task.get("status"))).append("</p>");
        if(issues.isEmpty())html.append("<p>无异常问题。</p>");
        else {
            html.append("<table><thead><tr><th>文件索引</th><th>问题</th><th>词／频次</th><th>输出</th></tr></thead><tbody>");
            for(var issue:issues) {
                html.append("<tr><td>").append(escape(issue.get("path"))).append("<br><small>").append(escape(issue.get("fileId"))).append("</small></td><td>")
                    .append(escape(issue.get("code"))).append("：").append(escape(issue.get("message"))).append("</td><td>").append(escape(issue.get("word"))).append(" ").append(escape(issue.get("count"))).append("</td><td>");
                if(issue.get("output") instanceof String output && output.startsWith("files/")) {
                    String href=java.util.Arrays.stream(output.split("/")).map(s->java.net.URLEncoder.encode(s,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20")).collect(java.util.stream.Collectors.joining("/"));
                    html.append("<a href=\"").append(escape(href)).append("\">查看输出</a>");
                }
                html.append("</td></tr>");
            }
            html.append("</tbody></table>");
        }
        html.append("</html>");Path staging=taskDirectory.resolve(".staging");Files.createDirectories(staging);
        Path tmp=Files.createTempFile(staging,"report-",".html");Files.writeString(tmp,html);
        Files.move(tmp,taskDirectory.resolve("report.html"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
}
