package io.github.glint.http.multipart;

import io.github.glint.http.HttpException;
import io.github.glint.http.HttpMethod;
import io.github.glint.http.HttpRequest;
import io.github.glint.http.ServerConfig;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MultipartParserTest {

    @Test
    void parsesFieldsAndFile() {
        String boundary = "----GlintBoundary7MA4YWxkTrZu0gW";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
                + "hello\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n"
                + "file-bytes\r\n"
                + "--" + boundary + "--\r\n";

        MultipartForm form = MultipartParser.parse(
                body.getBytes(StandardCharsets.UTF_8), boundary, ServerConfig.defaults());

        assertEquals(2, form.size());
        assertEquals("hello", form.field("title").orElseThrow());
        Part file = form.first("file").orElseThrow();
        assertTrue(file.isFile());
        assertEquals("a.txt", file.filename().orElseThrow());
        assertEquals("text/plain", file.contentType());
        assertEquals("file-bytes", file.asText());
    }

    @Test
    void extractsBoundaryFromContentType() {
        assertEquals("abc", MultipartParser.extractBoundary(
                "multipart/form-data; boundary=abc"));
        assertEquals("xyz", MultipartParser.extractBoundary(
                "multipart/form-data; charset=utf-8; boundary=\"xyz\""));
        assertNull(MultipartParser.extractBoundary("application/json"));
    }

    @Test
    void parseFromHttpRequest() {
        String boundary = "B";
        String rawBody = "--B\r\nContent-Disposition: form-data; name=\"k\"\r\n\r\nv\r\n--B--\r\n";
        HttpRequest req = new HttpRequest(
                HttpMethod.POST,
                "/upload",
                Map.of("content-type", "multipart/form-data; boundary=B"),
                rawBody.getBytes(StandardCharsets.UTF_8));
        MultipartForm form = MultipartParser.parse(req);
        assertEquals("v", form.field("k").orElseThrow());
    }

    @Test
    void enforcesMaxParts() {
        String boundary = "X";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            sb.append("--X\r\nContent-Disposition: form-data; name=\"f").append(i)
                    .append("\"\r\n\r\n").append(i).append("\r\n");
        }
        sb.append("--X--\r\n");
        ServerConfig cfg = ServerConfig.builder().maxMultipartParts(2).build();
        HttpException ex = assertThrows(HttpException.class, () ->
                MultipartParser.parse(sb.toString().getBytes(StandardCharsets.UTF_8), boundary, cfg));
        assertEquals(413, ex.status());
    }

    @Test
    void enforcesMaxPartBytes() {
        String boundary = "Y";
        String body = "--Y\r\nContent-Disposition: form-data; name=\"big\"\r\n\r\n"
                + "0123456789\r\n--Y--\r\n";
        ServerConfig cfg = ServerConfig.builder().maxMultipartPartBytes(5).build();
        HttpException ex = assertThrows(HttpException.class, () ->
                MultipartParser.parse(body.getBytes(StandardCharsets.UTF_8), boundary, cfg));
        assertEquals(413, ex.status());
    }

    @Test
    void emptyWhenNotMultipart() {
        HttpRequest req = new HttpRequest(
                HttpMethod.POST, "/", Map.of("content-type", "text/plain"), "x".getBytes());
        assertTrue(MultipartParser.parse(req).isEmpty());
    }
}
