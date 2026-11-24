package cn.swapy;

import java.nio.file.*;

final class TestLinks {
    private TestLinks() {}
    static void directory(Path link,Path target)throws Exception {
        try{Files.createSymbolicLink(link,target);return;}catch(FileSystemException e){if(!System.getProperty("os.name").toLowerCase().contains("windows"))org.junit.jupiter.api.Assumptions.abort("当前文件系统不允许创建测试链接");}
        var process=new ProcessBuilder("cmd.exe","/c","mklink","/J",link.toString(),target.toString()).redirectErrorStream(true).start();
        if(!process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();org.junit.jupiter.api.Assumptions.abort("创建测试联接超时");}
        if(process.exitValue()!=0)org.junit.jupiter.api.Assumptions.abort("当前系统不允许创建测试联接");
    }
}
