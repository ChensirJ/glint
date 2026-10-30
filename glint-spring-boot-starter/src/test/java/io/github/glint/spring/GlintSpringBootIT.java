package io.github.glint.spring;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boots Spring Boot on Glint (no Tomcat) and exercises session cookies + multipart.
 * Uses {@link java.net.http.HttpClient} so multiple {@code Set-Cookie} headers are visible.
 */
@SpringBootTest(
        classes = GlintSpringBootIT.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.banner-mode=off",
                "spring.servlet.multipart.enabled=true",
                "spring.servlet.multipart.max-file-size=2MB",
                "spring.servlet.multipart.max-request-size=2MB"
        }
)
class GlintSpringBootIT {

    @LocalServerPort
    int port;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void sessionAndMultiSetCookie() throws Exception {
        HttpResponse<String> first = client.send(
                HttpRequest.newBuilder(URI.create(url("/it/session")))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, first.statusCode(), first.body());
        assertEquals("count=1", first.body());

        List<String> setCookies = first.headers().allValues("set-cookie");
        assertFalse(setCookies.isEmpty(), "expected Set-Cookie headers");
        assertTrue(setCookies.stream().anyMatch(c -> c.startsWith("JSESSIONID=")), setCookies.toString());
        assertTrue(setCookies.stream().anyMatch(c -> c.startsWith("demo=")), setCookies.toString());
        assertTrue(setCookies.size() >= 2, "expected at least 2 Set-Cookie lines, got " + setCookies);

        String jsession = setCookies.stream()
                .filter(c -> c.startsWith("JSESSIONID="))
                .map(c -> c.split(";", 2)[0])
                .findFirst()
                .orElseThrow();

        HttpResponse<String> second = client.send(
                HttpRequest.newBuilder(URI.create(url("/it/session")))
                        .timeout(Duration.ofSeconds(5))
                        .header("Cookie", jsession)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, second.statusCode());
        assertEquals("count=2", second.body());
    }

    @Test
    void multipartUploadViaSpring() throws Exception {
        String boundary = "----GlintItBoundary";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
                + "hello-glint\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"note.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "abc\r\n"
                + "--" + boundary + "--\r\n";

        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(url("/it/upload")))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("hello-glint"), resp.body());
        assertTrue(resp.body().contains("note.txt"), resp.body());
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    @SpringBootApplication
    static class App {
        @RestController
        static class ItController {
            @GetMapping("/it/session")
            String session(HttpSession session, HttpServletResponse response) {
                Integer n = (Integer) session.getAttribute("n");
                n = n == null ? 1 : n + 1;
                session.setAttribute("n", n);
                Cookie demo = new Cookie("demo", "1");
                demo.setPath("/");
                response.addCookie(demo);
                return "count=" + n;
            }

            @PostMapping(value = "/it/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
            String upload(@RequestParam("title") String title,
                          @RequestParam(value = "file", required = false) MultipartFile file) {
                String name = file == null ? "" : file.getOriginalFilename();
                long size = file == null ? 0 : file.getSize();
                return "{\"title\":\"" + title + "\",\"file\":\"" + name + "\",\"size\":" + size + "}";
            }

            @GetMapping("/it/hello")
            String hello() {
                return "ok";
            }
        }
    }
}
