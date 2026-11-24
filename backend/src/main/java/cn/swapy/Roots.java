package cn.swapy;

import java.nio.file.*;
import java.util.*;

public final class Roots {
    private final Map<String,Path> inputs=new LinkedHashMap<>();
    public final Path output;
    public Roots(List<Path> paths,Path output) {
        try {
            for(int i=0;i<paths.size();i++) inputs.put("root"+i,paths.get(i).toRealPath());
            Files.createDirectories(output);this.output=output.toRealPath();inputs.put("output",this.output);
        } catch(Exception e) {throw new Problem("SOURCE_UNAVAILABLE",e.getMessage());}
    }
    public Map<String,Path> configured() {return Collections.unmodifiableMap(inputs);}
    public Path resolve(String rootId,String relative) {
        Path root=inputs.get(rootId);
        if(root==null) throw new Problem("ROOT_NOT_FOUND");
        try {
            guard(root,root);
            Path part=Path.of(relative==null?"":relative);
            for(Path component:part)if(Set.of(".swapy-stage",".staging").contains(component.toString()))throw new Problem("SOURCE_EXCLUDED");
            if(part.isAbsolute() || part.toString().contains(":")) throw new Problem("PATH_OUTSIDE_ROOT");
            for(Path component:part) if(component.toString().equals("..")) throw new Problem("PATH_OUTSIDE_ROOT");
            Path resolved=root.resolve(part).normalize();
            if(!resolved.startsWith(root)) throw new Problem("PATH_OUTSIDE_ROOT");
            Path cursor=root;
            for(Path component:root.relativize(resolved)) {
                cursor=cursor.resolve(component);
                if(Files.isSymbolicLink(cursor) || !cursor.toRealPath().startsWith(root)) throw new Problem("PATH_OUTSIDE_ROOT");
            }
            return resolved.toRealPath();
        } catch(Problem e) {throw e;}
        catch(Exception e) {throw new Problem("SOURCE_UNAVAILABLE",e.getMessage());}
    }
    public static void guard(Path trustedRoot,Path candidate) {
        try {
            Path root=trustedRoot.toAbsolutePath().normalize(),target=candidate.toAbsolutePath().normalize();
            if(!target.startsWith(root)||!root.toRealPath().equals(root)||Files.isSymbolicLink(root))throw new Problem("PATH_OUTSIDE_ROOT");
            Path cursor=root;
            for(Path part:root.relativize(target)) {
                cursor=cursor.resolve(part);
                if(!Files.exists(cursor,LinkOption.NOFOLLOW_LINKS))continue;
                if(Files.isSymbolicLink(cursor)||!cursor.toRealPath().startsWith(root))throw new Problem("PATH_OUTSIDE_ROOT");
            }
        }catch(Problem e){throw e;}catch(Exception e){throw new Problem("PATH_OUTSIDE_ROOT",e.getMessage());}
    }
    public List<Map<String,Object>> entries(String rootId,String relative) {
        Path directory=resolve(rootId,relative);
        if(!Files.isDirectory(directory)) throw new Problem("NOT_A_DIRECTORY");
        try(var files=Files.list(directory)) {
            return files.filter(p->!Files.isSymbolicLink(p)).filter(p->!Set.of(".swapy-stage",".staging").contains(p.getFileName().toString())).filter(p->rootId.equals("output")||!p.startsWith(output)).sorted(Comparator.comparing(p->p.getFileName().toString()))
                .limit(1000).map(p->{Map<String,Object> item=new LinkedHashMap<>();item.put("name",p.getFileName().toString());item.put("path",inputs.get(rootId).relativize(p).toString().replace('\\','/'));item.put("directory",Files.isDirectory(p));return item;}).toList();
        } catch(Exception e) {throw new Problem("SOURCE_UNAVAILABLE",e.getMessage());}
    }
}
