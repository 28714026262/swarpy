package cn.swapy.documents;

import cn.swapy.Problem;
import net.sf.jsqlparser.parser.*;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import java.util.*;
import java.util.function.UnaryOperator;

public final class SqlAdapter {
    public record Part(int start,int end,String value,String kind) {}
    public List<Part> parts(String sql) {
        var lexical=lex(sql);var columns=new HashSet<Integer>();var tableTokens=new HashSet<Integer>();var fieldNames=new HashSet<String>();
        try {
            var parser=CCJSqlParserUtil.newParser(sql);var statements=parser.Statements();walk(parser.getASTRoot(),sql,columns);
            collectTables(parser.getASTRoot(),sql,lexical,tableTokens);
            for(var statement:statements.getStatements())if(!(statement instanceof CreateTable||statement instanceof Select||statement instanceof Insert||statement instanceof Update||statement instanceof Delete))throw new Problem("UNSUPPORTED_SQL_BINDING","当前语句的全部字段引用无法安全绑定，整组跳过");
            for(var statement:statements.getStatements())if(statement instanceof CreateTable create && create.getColumnDefinitions()!=null) {
                var names=create.getColumnDefinitions().stream().map(c->unquote(c.getColumnName())).toList();
                names.forEach(n->fieldNames.add(n.toLowerCase(Locale.ROOT)));
                int index=0;int tableStart=offset(sql,create.getTable().getASTNode().jjtGetFirstToken());boolean inCreate=false;int depth=0;boolean expectColumn=false;
                for(Part t:lexical) {
                    if(t.start()<tableStart)continue;
                    String token=sql.substring(t.start(),t.end());
                    if(!inCreate){inCreate=true;continue;}
                    if(token.equals("(")){depth++;if(depth==1)expectColumn=true;continue;}
                    if(token.equals(")")){depth--;if(depth==0)break;continue;}
                    if(depth==1 && token.equals(",")){expectColumn=true;continue;}
                    if(depth==1 && expectColumn && !Set.of("comment","space").contains(t.kind())) {
                        if(index<names.size()&&unquote(token).equals(names.get(index))){columns.add(t.start());index++;}expectColumn=false;
                    }
                }
            }
            for(int i=0;i<lexical.size();i++) {
                Part part=lexical.get(i);if(!Set.of("UNIQUE","PRIMARY").contains(part.value().toUpperCase(Locale.ROOT)))continue;
                int j=i+1;while(j<lexical.size()&&(lexical.get(j).kind().equals("space")||lexical.get(j).value().equalsIgnoreCase("KEY")))j++;
                if(j>=lexical.size()||!lexical.get(j).value().equals("("))continue;
                for(j++;j<lexical.size()&&!lexical.get(j).value().equals(")");j++)if(lexical.get(j).kind().equals("identifier"))columns.add(lexical.get(j).start());
            }
            for(var token:lexical)if(token.kind().equals("identifier")&&!columns.contains(token.start())&&!tableTokens.contains(token.start())&&fieldNames.contains(unquote(token.value()).toLowerCase(Locale.ROOT)))throw new Problem("UNSUPPORTED_SQL_BINDING","存在未绑定的字段引用："+token.value());
        }catch(Problem e){throw e;}catch(Exception e){throw new Problem("UNSUPPORTED_SQL_DIALECT",e.getMessage());}
        return lexical.stream().map(t->columns.contains(t.start())?new Part(t.start(),t.end(),unquote(sql.substring(t.start(),t.end())),"column"):t).toList();
    }
    private void collectTables(Node node,String raw,List<Part> lexical,Set<Integer> tables) {
        if(node.jjtGetValue() instanceof Table && node.toString().equals("TableName")) {
            int start=offset(raw,node.jjtGetFirstToken()),end=offset(raw,node.jjtGetLastToken())+node.jjtGetLastToken().image.length();
            for(var token:lexical)if(token.start()>=start&&token.end()<=end)tables.add(token.start());
        }
        for(int i=0;i<node.jjtGetNumChildren();i++)collectTables(node.jjtGetChild(i),raw,lexical,tables);
    }
    private void walk(Node node,String sql,Set<Integer> columns) {
        if(node.jjtGetValue() instanceof Column) {Token token=node.jjtGetLastToken();int position=offset(sql,token);columns.add(position);}
        for(int i=0;i<node.jjtGetNumChildren();i++)walk(node.jjtGetChild(i),sql,columns);
    }
    private int offset(String text,Token token) {
        int start=0;for(int line=1;line<token.beginLine;line++){start=text.indexOf('\n',start)+1;if(start==0)throw new Problem("UNSAFE_SQL_POSITION");}
        int offset=start+token.beginColumn-1;
        if(!text.startsWith(token.image,offset))throw new Problem("UNSAFE_SQL_POSITION");return offset;
    }
    public Documents.Content read(String sql) {
        var editable=new ArrayList<String>();var protectedTexts=new ArrayList<String>();
        for(var p:parts(sql))if(Set.of("column","string","comment").contains(p.kind()))editable.add(p.value());else if(!p.kind().equals("space"))protectedTexts.add(p.value());
        return new Documents.Content(editable,protectedTexts);
    }
    public String rewrite(String sql,UnaryOperator<String> transform) {
        var parts=parts(sql);var out=new StringBuilder(sql);
        for(int i=parts.size()-1;i>=0;i--) {
            Part p=parts.get(i);if(!Set.of("column","string","comment").contains(p.kind()))continue;
            String updated=transform.apply(p.value());if(updated.equals(p.value()))continue;
            String raw=sql.substring(p.start(),p.end());String replacement=updated;
            if(p.kind().equals("string"))replacement="'"+updated.replace("'","''")+"'";
            else if(p.kind().equals("column")) {
                if(raw.startsWith("`"))replacement="`"+updated.replace("`","``")+"`";
                else if(raw.startsWith("["))replacement="["+updated.replace("]","]]")+"]";
                else if(raw.startsWith("\"") || !updated.matches("[\\p{L}_][\\p{L}\\p{N}_]*"))replacement="\""+updated.replace("\"","\"\"")+"\"";
            }
            out.replace(p.start(),p.end(),replacement);
        }
        String result=out.toString();parts(result);return result;
    }
    private List<Part> lex(String text) {
        var result=new ArrayList<Part>();
        for(int i=0;i<text.length();) {
            int start=i;char c=text.charAt(i);String kind="symbol",value;
            if(Character.isWhitespace(c)){while(i<text.length()&&Character.isWhitespace(text.charAt(i)))i++;kind="space";}
            else if(text.startsWith("--",i)){i=text.indexOf('\n',i);if(i<0)i=text.length();kind="comment";}
            else if(text.startsWith("/*",i)){int end=text.indexOf("*/",i+2);if(end<0)throw new Problem("INVALID_SQL");i=end+2;kind="comment";}
            else if(c=='\''||c=='"'||c=='`'||c=='['){char close=c=='['?']':c;i++;boolean ended=false;while(i<text.length()){if(text.charAt(i)==close){if(i+1<text.length()&&text.charAt(i+1)==close){i+=2;continue;}i++;ended=true;break;}i++;}if(!ended)throw new Problem("INVALID_SQL");kind=c=='\''?"string":"identifier";}
            else if(Character.isLetterOrDigit(c)||c=='_'){i++;while(i<text.length()&&(Character.isLetterOrDigit(text.charAt(i))||text.charAt(i)=='_'||text.charAt(i)=='$'))i++;kind="identifier";}
            else i++;
            value=text.substring(start,i);if(kind.equals("string"))value=value.substring(1,value.length()-1).replace("''","'");
            result.add(new Part(start,i,value,kind));
        }
        return result;
    }
    private String unquote(String value){if(value.length()>1 && (value.startsWith("\"")||value.startsWith("`")||value.startsWith("[")))return value.substring(1,value.length()-1).replace("\"\"","\"").replace("``","`").replace("]]","]");return value;}
}
