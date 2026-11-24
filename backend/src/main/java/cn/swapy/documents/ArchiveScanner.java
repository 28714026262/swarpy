package cn.swapy.documents;

import cn.swapy.Problem;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import com.github.junrar.Archive;

public final class ArchiveScanner {
    public record Entry(String member,Path physical,String error) {}
    public record Expansion(List<Entry> entries,long expandedBytes) {}
    private static final long MEMBER=100L*1024*1024,ARCHIVE=1024L*1024*1024;
    private static final Set<String> SUPPORTED=Set.of("txt","md","csv","log","ini","yaml","yml","toml","json","xml","html","htm","sql","docx","doc","rtf","odt","pdf");
    private static final class Budget {long bytes;int entries;final long limit;Budget(long limit){this.limit=limit;}}
    private interface StreamSupplier {InputStream open() throws Exception;}
    public static boolean archive(Path path){return Set.of("zip","7z","rar").contains(Documents.extension(path));}
    public List<Entry> extract(Path source,Path work) {
        return extract(source,work,ARCHIVE).entries();
    }
    public Expansion extract(Path source,Path work,long remaining) {
        var result=new ArrayList<Entry>();var budget=new Budget(Math.min(ARCHIVE,Math.max(0,remaining)));
        if(remaining<=0)return new Expansion(List.of(new Entry("",null,"ARCHIVE_LIMIT")),0);
        try{Files.createDirectories(work);expand(source,work,"",1,budget,result);}catch(Exception e){result.add(new Entry("",null,code(e)));}
        return new Expansion(result,budget.bytes);
    }
    private void expand(Path source,Path work,String prefix,int depth,Budget budget,List<Entry> result) throws Exception {
        if(depth>3){result.add(new Entry(prefix,null,"ARCHIVE_DEPTH_LIMIT"));return;}
        var seen=new HashSet<String>();
        switch(Documents.extension(source)) {
            case "zip" -> {
                try(var zip=ZipFile.builder().setPath(source).get()) {
                    var entries=zip.getEntries();while(entries.hasMoreElements()) {
                        if(exhausted(prefix,budget,result))break;
                        var e=entries.nextElement();if(e.isDirectory())continue;
                        if(e.getGeneralPurposeBit().usesEncryption()){result.add(new Entry(prefix+e.getName(),null,"ENCRYPTED_ARCHIVE"));continue;}
                        if(!zip.canReadEntryData(e)){result.add(new Entry(prefix+e.getName(),null,"UNSUPPORTED_ARCHIVE_ENTRY"));continue;}
                        handle(e.getName(),e.getSize(),e.getCompressedSize(),()->zip.getInputStream(e),work,prefix,depth,budget,result,seen);
                    }
                }
            }
            case "7z" -> {
                try(var seven=SevenZFile.builder().setPath(source).get()) {
                    org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry e;
                    while((e=seven.getNextEntry())!=null) {
                        if(exhausted(prefix,budget,result))break;
                        if(e.isDirectory())continue;var entry=e;
                        handle(e.getName(),e.getSize(),-1,()->new InputStream(){public int read(byte[] b,int off,int len)throws IOException{return seven.read(b,off,len);}public int read()throws IOException{return seven.read();}},work,prefix,depth,budget,result,seen);
                    }
                }
            }
            case "rar" -> {
                try(var rar=new Archive(source.toFile())) {
                    if(rar.isEncrypted())throw new Problem("ENCRYPTED_ARCHIVE");
                    for(var e:rar.getFileHeaders()) {
                        if(exhausted(prefix,budget,result))break;
                        if(e.isDirectory())continue;
                        if(e.isEncrypted()){result.add(new Entry(prefix+e.getFileName(),null,"ENCRYPTED_ARCHIVE"));continue;}
                        handle(e.getFileName(),e.getFullUnpackSize(),e.getFullPackSize(),()->rar.getInputStream(e),work,prefix,depth,budget,result,seen);
                    }
                }
            }
            default -> throw new Problem("UNSUPPORTED_ARCHIVE");
        }
    }
    private void handle(String raw,long expected,long compressed,StreamSupplier supplier,Path work,String prefix,int depth,Budget budget,List<Entry> result,Set<String> seen) {
        String name=raw.replace('\\','/'),label=prefix+name;
        try {
            if(name.startsWith("/")||name.contains(":")||Arrays.asList(name.split("/")).contains("..")||!seen.add(name))throw new Problem("ARCHIVE_PATH_REJECTED");
            if(++budget.entries>50000 || budget.bytes>=budget.limit || expected>MEMBER)throw new Problem("ARCHIVE_LIMIT");
            String ext=Documents.extension(Path.of(name));if(!SUPPORTED.contains(ext)&&!Set.of("zip","7z","rar").contains(ext)){result.add(new Entry(label,null,"UNSUPPORTED_FORMAT"));return;}
            Path out=work.resolve(UUID.randomUUID()+"."+ext);long bytes=0,deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
            try(var in=supplier.open();var stream=Files.newOutputStream(out,StandardOpenOption.CREATE_NEW)) {
                byte[] buffer=new byte[32768];int n;
                while((n=in.read(buffer,0,(int)Math.min(buffer.length,Math.max(1,budget.limit-budget.bytes+1))))!=-1) {
                    bytes+=n;budget.bytes+=n;
                    if(bytes>MEMBER||budget.bytes>budget.limit||compressed>0&&(double)bytes/compressed>200||System.nanoTime()>deadline||Thread.currentThread().isInterrupted())throw new Problem("ARCHIVE_LIMIT");
                    stream.write(buffer,0,n);
                }
            }catch(Exception e){Files.deleteIfExists(out);throw e;}
            if(archive(out))expand(out,work,label+"!/",depth+1,budget,result);else result.add(new Entry(label,out,""));
        }catch(Exception e){result.add(new Entry(label,null,code(e)));}
    }
    private boolean exhausted(String prefix,Budget budget,List<Entry> result){if(budget.entries<50000&&budget.bytes<budget.limit)return false;result.add(new Entry(prefix.isEmpty()?"未展开部分":prefix+"未展开部分",null,"ARCHIVE_LIMIT"));return true;}
    private String code(Exception e){if(e instanceof Problem p)return p.code;String message=Objects.toString(e.getMessage(),"").toLowerCase(Locale.ROOT);return message.contains("password")||message.contains("encrypt")?"ENCRYPTED_ARCHIVE":"ARCHIVE_READ_FAILED";}
}
