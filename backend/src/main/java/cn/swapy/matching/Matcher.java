package cn.swapy.matching;

import cn.swapy.Catalog.Word;
import java.util.*;
import java.util.regex.Pattern;

public final class Matcher {
    public record Hit(int start,int end,String surface,Word word) {}
    private Matcher() {}
    public static boolean english(Word word) {return word.form().contains("英文") || word.form().contains("数字");}
    public static Pattern pattern(Word word) {
        String expression=Pattern.quote(word.word());
        boolean bounded=english(word)||Set.of("日期","时刻","数量","金额","比例","电话","邮箱","IPv4","网址").contains(word.form());
        String before="(?<![A-Za-z0-9_])",after="(?![A-Za-z0-9_])";
        switch(word.form()) {
            case "数量","金额","比例" -> {before+="(?<![0-9][.,])";after+="(?![.,][0-9])";}
            case "时刻" -> {before+="(?<![0-9]:)";after+="(?!:[0-9])";}
            case "IPv4" -> {before+="(?<![A-Za-z0-9_]\\.)";after+="(?!\\.[A-Za-z0-9_])";}
            case "邮箱" -> {before="(?<![A-Za-z0-9_@%+\\-])(?<![A-Za-z0-9_.%+\\-]\\.)";after="(?![A-Za-z0-9_@%+\\-])(?!\\.[A-Za-z0-9_\\-])";}
            case "网址" -> {before="(?<![A-Za-z0-9_@:/?=&%+#~\\-])(?<![A-Za-z0-9_]\\.)";after="(?![A-Za-z0-9_@:/?=&%+#~\\-])(?!\\.[A-Za-z0-9_/])";}
        }
        return bounded?Pattern.compile(before+expression+after,english(word)?Pattern.CASE_INSENSITIVE:0):Pattern.compile(expression);
    }
    public static List<Hit> all(String text,List<Word> words) {
        var hits=new ArrayList<Hit>();
        for(Word word:words) {
            var matcher=pattern(word).matcher(text);int offset=0;
            while(offset<=text.length() && matcher.find(offset)) {
                hits.add(new Hit(matcher.start(),matcher.end(),matcher.group(),word));
                offset=matcher.start()+1;
            }
        }
        return hits;
    }
    public static List<Hit> find(String text,List<Word> words) {
        var candidates=all(text,words);
        candidates.sort(Comparator.<Hit>comparingInt(h->h.end()-h.start()).reversed().thenComparingInt(Hit::start).thenComparing(h->h.word().id()));
        var selected=new ArrayList<Hit>();
        for(Hit h:candidates) if(selected.stream().noneMatch(s->h.start()<s.end() && s.start()<h.end())) selected.add(h);
        selected.sort(Comparator.comparingInt(Hit::start));return selected;
    }
}
