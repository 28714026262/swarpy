package cn.swapy;

import java.time.*;
import java.net.URI;
import java.util.*;

public final class WordTypes {
    public static final List<String> ROLES=List.of("人名","地点","组织名","设施名","事物名","标识","通用词");
    private static final Map<String,String> ALIASES=new HashMap<>();
    static {
        aliases("地点","地名 地址 地域 区域名称");aliases("组织名","机构名 企业名 部门名 团队名 协会名");
        aliases("设施名","学校名 医院名 园区名 建筑名 道路名 线路名 站点名");
        aliases("事物名","产品名 品牌名 项目代号 系统名 平台名 应用名 服务名 网站名 活动名 会议名 基金名 设备名 文档名");
        aliases("标识","编号 字段名 数据库名 数据表名 文件名 目录名 接口名 配置项名 指标名 角色名 岗位名 流程名 业务名 人员编号 订单编号 设备编号 批次编号 账户编号 工单编号");
        aliases("通用词","通用名词 通用动作 通用性状 通用方式");aliases("联系与网络地址","电话 邮箱 域名 IP 网址");
    }
    private WordTypes() {}
    private static void aliases(String target,String words){for(String value:words.split(" "))ALIASES.put(value,target);}
    public static String role(String value,String form) {
        if(value==null||form==null)return value;
        if(ROLES.contains(value))return value;
        if(Set.of("中文代号","数字代号","英文缩写").contains(form))return "标识";
        return ALIASES.getOrDefault(value,value);
    }
    public static boolean compatible(Catalog.Word a,Catalog.Word b) {
        String role=role(a.role(),a.form());boolean nominal=Set.of("名词","专有名词").contains(a.pos())&&Set.of("名词","专有名词").contains(b.pos())&&!role.equals("通用词");
        return a.form().equals(b.form())&&role.equals(role(b.role(),b.form()))&&(a.pos().equals(b.pos())||nominal)
                &&(a.family()==null||a.family().isBlank()||!a.family().equals(b.family()))&&shape(a.form(),a.word()).equals(shape(b.form(),b.word()))
                &&!shape(a.form(),a.word()).equals("invalid")
                &&(!cn.swapy.matching.Matcher.english(a)||letters(a.word())==letters(b.word()));
    }
    private static long letters(String s){return s.chars().filter(c->c>='A'&&c<='Z'||c>='a'&&c<='z').count();}
    private static String shape(String form,String text) {
        try {
            return switch(form) {
                case "日期" -> {if(!text.matches("\\d{4}[-/]\\d{2}[-/]\\d{2}"))yield "invalid";var p=text.split("[-/]");LocalDate.of(Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2]));yield text.replaceAll("\\d","#");}
                case "时刻" -> {if(!text.matches("\\d{2}:\\d{2}(:\\d{2})?"))yield "invalid";LocalTime.parse(text);yield text.replaceAll("\\d","#");}
                case "数量","金额","比例" -> {if(!text.matches("[$￥¥]?[+-]?\\d+(\\.\\d+)?[\\p{IsHan}A-Za-z%]*"))yield "invalid";String result=text.replaceAll("\\d+","#");int dot=text.indexOf('.');yield result+(dot<0?"":":"+text.substring(dot+1).replaceAll("[^0-9].*","").length());}
                case "电话" -> {if(!text.matches("\\+?\\d[0-9 -]{5,20}"))yield "invalid";yield text.replaceAll("\\d","#");}
                case "邮箱" -> text.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")?"email":"invalid";
                case "IPv4" -> {String[] p=text.split("\\.",-1);boolean valid=p.length==4;for(String x:p)valid&=x.matches("\\d{1,3}")&&Integer.parseInt(x)<=255;yield valid?"ip":"invalid";}
                case "网址" -> {URI uri=URI.create(text);yield Set.of("http","https").contains(uri.getScheme())&&uri.getHost()!=null?uri.getScheme():"invalid";}
                default -> "text";
            };
        }catch(Exception e){return "invalid";}
    }
}
