package cn.swapy;

import cn.swapy.tasks.TaskService;
import cn.swapy.packages.PackageService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import java.nio.file.Path;
import java.util.*;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {SpringApplication.run(Application.class,args);}
    @Bean(destroyMethod="close") Store store(Environment env){return new Store(Path.of(env.getProperty("swapy.data","./data")));}
    @Bean Catalog catalog(Store db){return new Catalog(db);}
    @Bean Roots roots(Environment env,Store db) {
        String configured=env.getProperty("swapy.roots","");var paths=new LinkedHashSet<Path>();
        if(configured.isBlank()){paths.add(Path.of("").toAbsolutePath());paths.add(Path.of(System.getProperty("user.home")));}
        else for(String path:configured.split(";"))if(!path.isBlank())paths.add(Path.of(path));
        return new Roots(new ArrayList<>(paths),Path.of(env.getProperty("swapy.output",db.data.resolve("tasks").toString())));
    }
    @Bean(destroyMethod="close") TaskService taskService(Store db,Catalog catalog,Roots roots){return new TaskService(db,catalog,roots);}
    @Bean PackageService packages(Store db,Catalog catalog){return new PackageService(db,catalog);}
    @Bean GitCatalog gitCatalog(Store db,Catalog catalog,Environment env){String path=env.getProperty("swapy.catalog-repo",System.getenv("SWAPY_CATALOG_REPO"));boolean configured=path!=null&&!path.isBlank();boolean publisher=Boolean.parseBoolean(env.getProperty("swapy.publisher",Objects.toString(System.getenv("SWAPY_PUBLISHER"),String.valueOf(!configured))));return new GitCatalog(db,catalog,configured?Path.of(path):null,publisher);}
}
