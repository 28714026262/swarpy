package cn.swapy.documents;

import cn.swapy.Problem;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

public final class LegacyOfficeAdapter {
    private final Path executable;
    private final OoxmlAdapter ooxml=new OoxmlAdapter();
    public LegacyOfficeAdapter(){this(find());}
    public LegacyOfficeAdapter(Path executable){this.executable=executable;}
    private static Path find() {
        String configured=System.getenv("SWAPY_SOFFICE");if(configured!=null&&!configured.isBlank())return Path.of(configured);
        for(String location:List.of("C:/Program Files/LibreOffice/program/soffice.com","C:/Program Files/LibreOffice/program/soffice.exe","/usr/bin/soffice","/usr/local/bin/soffice"))if(Files.isRegularFile(Path.of(location)))return Path.of(location);
        return Path.of("/missing-soffice");
    }
    public Documents.Content read(Path input) {
        check();Path work=null;
        try {work=Files.createTempDirectory("swapy-office-");Path docx=prepare(input,work);return ooxml.read(docx);}
        catch(Problem e){throw e;}catch(Exception e){throw new Problem("OFFICE_READ_FAILED",e.getMessage());}finally{cleanup(work);}
    }
    public void rewrite(Path input,Path output,UnaryOperator<String> transform) {
        check();Path work=null;
        try {
            work=Files.createTempDirectory(output.getParent(),".office-");Path docx=prepare(input,work);Path edited=work.resolve("edited.docx");ooxml.rewrite(docx,edited,transform);
            String ext=Documents.extension(input),filter=switch(ext){case "doc"->"doc:MS Word 97";case "rtf"->"rtf:Rich Text Format";case "odt"->"odt:writer8";default->throw new Problem("UNSUPPORTED_FORMAT");};
            Path converted=convert(edited,work.resolve("final"),work,filter,ext);Files.move(converted,output,StandardCopyOption.ATOMIC_MOVE);
            var expected=ooxml.read(edited);var actual=read(output);if(!expected.texts().equals(actual.texts()))throw new Problem("OFFICE_VALIDATION_FAILED");
        }catch(Problem e){throw e;}catch(Exception e){throw new Problem("OFFICE_WRITE_FAILED",e.getMessage());}finally{cleanup(work);}
    }
    private void check(){if(!Files.isRegularFile(executable))throw new Problem("OFFICE_ENGINE_UNAVAILABLE","DOC/RTF/ODT需要配置LibreOffice无界面转换引擎");}
    private Path prepare(Path input,Path work)throws Exception {
        Path copy=work.resolve("source."+Documents.extension(input));Files.copy(input,copy);return convert(copy,work.resolve("docx"),work,"docx","docx");
    }
    private Path convert(Path input,Path out,Path work,String filter,String extension)throws Exception {
        Files.createDirectories(out);Path profile=work.resolve("profile");Files.createDirectories(profile.resolve("user"));
        Files.writeString(profile.resolve("user/registrymodifications.xcu"),"<?xml version=\"1.0\"?><oor:items xmlns:oor=\"http://openoffice.org/2001/registry\"><item oor:path=\"/org.openoffice.Office.Common/Security/Scripting\"><prop oor:name=\"MacroSecurityLevel\" oor:op=\"fuse\"><value>3</value></prop><prop oor:name=\"SecureURL\" oor:op=\"fuse\"><value/></prop></item><item oor:path=\"/org.openoffice.Office.Writer/Content/Update\"><prop oor:name=\"Link\" oor:op=\"fuse\"><value>0</value></prop></item></oor:items>");
        var process=new ProcessBuilder(executable.toString(),"-env:UserInstallation="+profile.toUri(),"--headless","--nologo","--nodefault","--norestore","--convert-to",filter,"--outdir",out.toString(),input.toString()).redirectErrorStream(true).redirectOutput(work.resolve("office.log").toFile()).start();
        try{if(!process.waitFor(120,TimeUnit.SECONDS)){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();throw new Problem("OFFICE_TIMEOUT");}}
        catch(InterruptedException e){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();Thread.currentThread().interrupt();throw new Problem("OFFICE_INTERRUPTED");}
        String name=input.getFileName().toString();Path result=out.resolve(name.substring(0,name.lastIndexOf('.'))+"."+extension);
        if(process.exitValue()!=0||!Files.isRegularFile(result))throw new Problem("OFFICE_CONVERSION_FAILED");return result;
    }
    private void cleanup(Path work) {
        if(work==null)return;
        try {
            Path absolute=work.toAbsolutePath().normalize();if(!absolute.getFileName().toString().startsWith("swapy-office-")&&!absolute.getFileName().toString().startsWith(".office-"))return;
            Files.walkFileTree(absolute,new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs)throws IOException{Files.delete(file);return FileVisitResult.CONTINUE;}
                @Override public FileVisitResult postVisitDirectory(Path dir,IOException error)throws IOException{if(error!=null)throw error;Files.delete(dir);return FileVisitResult.CONTINUE;}
            });
        }catch(Exception e){System.getLogger(LegacyOfficeAdapter.class.getName()).log(System.Logger.Level.WARNING,"Office暂存清理失败");}
    }
}
