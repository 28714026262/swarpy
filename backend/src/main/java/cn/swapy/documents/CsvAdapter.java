package cn.swapy.documents;

import cn.swapy.Problem;
import java.util.*;
import java.util.function.UnaryOperator;

public final class CsvAdapter {
    private record Field(int start,int end,String value,boolean quoted) {}
    public Documents.Content read(String raw){return new Documents.Content(fields(raw,separator(raw)).stream().map(Field::value).toList(),List.of());}
    public String rewrite(String raw,UnaryOperator<String> transform) {
        char delimiter=separator(raw);var fields=fields(raw,delimiter);var out=new StringBuilder(raw);
        for(int i=fields.size()-1;i>=0;i--){var field=fields.get(i);String updated=transform.apply(field.value());if(updated.equals(field.value()))continue;
            boolean quote=field.quoted()||updated.indexOf(delimiter)>=0||updated.indexOf('"')>=0||updated.indexOf('\r')>=0||updated.indexOf('\n')>=0;
            out.replace(field.start(),field.end(),quote?"\""+updated.replace("\"","\"\"")+"\"":updated);
        }
        fields(out.toString(),delimiter);return out.toString();
    }
    private char separator(String raw){int[] counts=new int[3];char[] choices={',',';','\t'};boolean quoted=false;for(int i=0;i<raw.length();i++){char c=raw.charAt(i);if(c=='"'){if(quoted&&i+1<raw.length()&&raw.charAt(i+1)=='"'){i++;continue;}quoted=!quoted;}if(!quoted&&(c=='\r'||c=='\n'))break;if(!quoted)for(int j=0;j<choices.length;j++)if(c==choices[j])counts[j]++;}int best=0;for(int j=1;j<3;j++)if(counts[j]>counts[best])best=j;return choices[best];}
    private List<Field> fields(String raw,char delimiter) {
        var result=new ArrayList<Field>();int i=0;
        while(i<raw.length()) {
            int start=i;boolean quoted=raw.charAt(i)=='"';StringBuilder value=new StringBuilder();
            if(quoted){i++;boolean closed=false;while(i<raw.length()){char c=raw.charAt(i++);if(c=='"'){if(i<raw.length()&&raw.charAt(i)=='"'){value.append('"');i++;}else{closed=true;break;}}else value.append(c);}if(!closed)throw new Problem("INVALID_CSV");if(i<raw.length()&&raw.charAt(i)!=delimiter&&raw.charAt(i)!='\r'&&raw.charAt(i)!='\n')throw new Problem("INVALID_CSV");}
            else while(i<raw.length()&&raw.charAt(i)!=delimiter&&raw.charAt(i)!='\r'&&raw.charAt(i)!='\n'){if(raw.charAt(i)=='"')throw new Problem("INVALID_CSV");value.append(raw.charAt(i++));}
            result.add(new Field(start,i,value.toString(),quoted));
            if(i==raw.length())break;char c=raw.charAt(i++);if(c=='\r'&&i<raw.length()&&raw.charAt(i)=='\n')i++;
            if(c==delimiter&&(i==raw.length()||raw.charAt(i)=='\r'||raw.charAt(i)=='\n')){result.add(new Field(i,i,"",false));if(i<raw.length()){if(raw.charAt(i++)=='\r'&&i<raw.length()&&raw.charAt(i)=='\n')i++;}}
        }
        return result;
    }
}
