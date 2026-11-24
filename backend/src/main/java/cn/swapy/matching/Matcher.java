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
        return english(word) ? Pattern.compile("(?<![A-Za-z0-9_])"+expression+"(?![A-Za-z0-9_])",Pattern.CASE_INSENSITIVE) : Pattern.compile(expression);
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
