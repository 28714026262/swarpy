package cn.swapy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ApiTest {
    @TempDir Path temp;
    @Test void unchangedCatalogHasNoBodyAndChangesInvalidateItsTag() throws Exception {
        Path source=Files.createDirectory(temp.resolve("catalog-source"));
        try(var context=new SpringApplicationBuilder(Application.class).run("--server.port=0","--server.address=127.0.0.1",
                "--swapy.data="+temp.resolve("catalog-data"),"--swapy.output="+temp.resolve("catalog-out"),"--swapy.roots="+source,"--spring.main.banner-mode=off")) {
            String base="http://127.0.0.1:"+((WebServerApplicationContext)context).getWebServer().getPort()+"/api/v1";
            var http=HttpClient.newHttpClient();var uri=URI.create(base+"/catalog");
            var first=http.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(first.statusCode()).isEqualTo(200);String tag=first.headers().firstValue("ETag").orElse("");assertThat(tag).isNotBlank();
            var unchanged=http.send(HttpRequest.newBuilder(uri).header("If-None-Match",tag).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(unchanged.statusCode()).isEqualTo(304);assertThat(unchanged.body()).isEmpty();
            var session=http.send(HttpRequest.newBuilder(URI.create(base+"/session")).GET().build(),HttpResponse.BodyHandlers.ofString());
            String token=(String)Json.object(session.body()).get("token");
            var added=http.send(HttpRequest.newBuilder(URI.create(base+"/catalog/entries")).header("X-Swapy-Token",token).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"kind\":\"pool\",\"word\":\"林舟\",\"role\":\"人名\"}")).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(added.statusCode()).isEqualTo(200);
            var changed=http.send(HttpRequest.newBuilder(uri).header("If-None-Match",tag).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(changed.statusCode()).isEqualTo(200);assertThat(Json.object(changed.body()).get("version")).isEqualTo(1);
            String next=changed.headers().firstValue("ETag").orElse("");assertThat(next).isNotEqualTo(tag);
            var cached=http.send(HttpRequest.newBuilder(uri).header("If-None-Match","W/"+next).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(cached.statusCode()).isEqualTo(304);assertThat(cached.body()).isEmpty();
        }
    }
    @Test void realHttpRequiresSameOriginTokenAndScansSelectedDirectory() throws Exception {
        Path source=Files.createDirectory(temp.resolve("source"));Files.writeString(source.resolve("hello.md"),"正常内容");
        try(var context=new SpringApplicationBuilder(Application.class).run("--server.port=0","--server.address=127.0.0.1",
                "--swapy.data="+temp.resolve("data"),"--swapy.output="+temp.resolve("out"),"--swapy.roots="+source,"--spring.main.banner-mode=off")) {
            int port=((WebServerApplicationContext)context).getWebServer().getPort();String base="http://127.0.0.1:"+port;
            var http=HttpClient.newHttpClient();var session=http.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/session")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(session.statusCode()).isEqualTo(200);String token=(String)Json.object(session.body()).get("token");
            String body="{\"kind\":\"scan\",\"source\":{\"rootId\":\"root0\",\"path\":\"\"},\"mode\":\"A\"}";
            var denied=http.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/tasks")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(denied.statusCode()).isEqualTo(403);
            var cross=http.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/tasks")).header("Content-Type","application/json").header("X-Swapy-Token",token).header("Origin","https://example.invalid").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(cross.statusCode()).isEqualTo(403);
            var created=http.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/tasks")).header("Content-Type","application/json").header("X-Swapy-Token",token).header("Idempotency-Key","api-test-1234").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(created.statusCode()).isEqualTo(202);String id=(String)Json.object(created.body()).get("id");
            var repeated=http.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/tasks")).header("Content-Type","application/json").header("X-Swapy-Token",token).header("Idempotency-Key","api-test-1234").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(Json.object(repeated.body()).get("id")).isEqualTo(id);
            long deadline=System.currentTimeMillis()+15000;String status="";
            while(System.currentTimeMillis()<deadline) {
                var response=http.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/tasks/"+id)).GET().build(),HttpResponse.BodyHandlers.ofString());
                status=(String)Json.object(response.body()).get("status");if(status.equals("SUCCEEDED"))break;Thread.sleep(25);
            }
            assertThat(status).isEqualTo("SUCCEEDED");assertThat(Files.exists(temp.resolve("out").resolve(id).resolve("report.html"))).isTrue();
        }
    }
}
