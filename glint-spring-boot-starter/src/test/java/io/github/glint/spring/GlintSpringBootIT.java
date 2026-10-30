package io.github.glint.spring;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boots Spring Boot on Glint (no Tomcat) and exercises session cookies, multipart,
 * the servlet filter chain, and the {@code glint.*} middleware properties.
 * Uses {@link java.net.http.HttpClient} so multiple {@code Set-Cookie} headers are visible.
 */
@SpringBootTest(
        classes = GlintSpringBootIT.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.banner-mode=off",
                "spring.servlet.multipart.enabled=true",
                "spring.servlet.multipart.max-file-size=2MB",
                "spring.servlet.multipart.max-request-size=2MB",
                "glint.static.enabled=true",
                "glint.static.prefix=/assets",
                "glint.static.location=classpath:it-static",
                "glint.access-log.enabled=true",
                "glint.access-log.format=json",
                "glint.cors.enabled=true",
                "glint.cors.allowed-origin=https://example.test",
                "glint.cors.exposed-headers=X-Glint-Filter",
                "glint.rate-limit.enabled=true",
                "glint.rate-limit.permits-per-second=1000",
                "glint.rate-limit.burst=1000"
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

    @Test
    void registeredFiltersRunInOrder() throws Exception {
        HttpResponse<String> resp = get("/it/hello", null);
        assertEquals(200, resp.statusCode(), resp.body());
        assertEquals("ok", resp.body());
        assertEquals("first,second", resp.headers().firstValue("X-Glint-Filter").orElse(null));
    }

    @Test
    void springCharacterEncodingFilterRuns() throws Exception {
        HttpResponse<String> resp = get("/it/encoding", null);
        assertEquals(200, resp.statusCode(), resp.body());
        assertEquals("UTF-8", resp.body());
    }

    @Test
    void filterCanShortCircuit() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(url("/it/hello")))
                        .timeout(Duration.ofSeconds(5))
                        .header("X-Block", "1")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, resp.statusCode());
        assertEquals("blocked by filter", resp.body());
        assertEquals("first", resp.headers().firstValue("X-Glint-Filter").orElse(null));
    }

    @Test
    void staticMountServesClasspathFiles() throws Exception {
        HttpResponse<String> resp = get("/assets/hello.txt", null);
        assertEquals(200, resp.statusCode(), resp.body());
        assertEquals("hello from glint static", resp.body().trim());
        assertEquals(404, get("/assets/missing.txt", null).statusCode());
    }

    @Test
    void corsHeadersFromProperties() throws Exception {
        HttpResponse<String> resp = get("/it/hello", "https://example.test");
        assertEquals(200, resp.statusCode());
        assertEquals("https://example.test",
                resp.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertTrue(resp.headers().firstValue("Access-Control-Expose-Headers").orElse("")
                .contains("X-Glint-Filter"));

        HttpResponse<String> preflight = client.send(
                HttpRequest.newBuilder(URI.create(url("/it/hello")))
                        .timeout(Duration.ofSeconds(5))
                        .header("Origin", "https://example.test")
                        .header("Access-Control-Request-Method", "POST")
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, preflight.statusCode());
        assertTrue(preflight.headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("POST"));
    }

    private HttpResponse<String> get(String path, String origin) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path)))
                .timeout(Duration.ofSeconds(5))
                .GET();
        if (origin != null) {
            b.header("Origin", origin);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    @SpringBootApplication
    static class App {

        /** Runs first on /it/*: tags the response, then lets the chain continue. */
        @Bean
        FilterRegistrationBean<HttpFilter> firstFilter() {
            FilterRegistrationBean<HttpFilter> reg = new FilterRegistrationBean<>(new HttpFilter() {
                @Override
                protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                        throws IOException, ServletException {
                    res.setHeader("X-Glint-Filter", "first");
                    chain.doFilter(req, res);
                }
            });
            reg.addUrlPatterns("/it/*");
            reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
            return reg;
        }

        /** Runs second: short-circuits with 403 when X-Block is present. */
        @Bean
        FilterRegistrationBean<HttpFilter> secondFilter() {
            FilterRegistrationBean<HttpFilter> reg = new FilterRegistrationBean<>(new HttpFilter() {
                @Override
                protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                        throws IOException, ServletException {
                    if (req.getHeader("X-Block") != null) {
                        res.setStatus(403);
                        res.setContentType("text/plain");
                        res.getWriter().write("blocked by filter");
                        return;
                    }
                    res.setHeader("X-Glint-Filter", res.getHeader("X-Glint-Filter") + ",second");
                    chain.doFilter(req, res);
                }
            });
            reg.addUrlPatterns("/it/*");
            reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 200);
            return reg;
        }

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

            @GetMapping("/it/encoding")
            String encoding(HttpServletRequest request) {
                return String.valueOf(request.getCharacterEncoding());
            }

            @GetMapping("/it/hello")
            String hello() {
                return "ok";
            }
        }
    }
}
