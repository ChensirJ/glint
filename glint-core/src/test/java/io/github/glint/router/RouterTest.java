package io.github.glint.router;

import io.github.glint.http.HttpMethod;
import io.github.glint.http.HttpRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RouterTest {

    @Test
    void matchesStaticPath() {
        Router router = new Router();
        router.add(HttpMethod.GET, "/hello", (req, resp) -> {});
        HttpRequest req = new HttpRequest(HttpMethod.GET, "/hello", Map.of(), new byte[0]);
        assertNotNull(router.match(req));
    }

    @Test
    void extractsPathParams() {
        Router router = new Router();
        router.add(HttpMethod.GET, "/users/{id}/posts/{postId}", (req, resp) -> {});
        HttpRequest req = new HttpRequest(HttpMethod.GET, "/users/42/posts/7", Map.of(), new byte[0]);
        RouteHandler h = router.match(req);
        assertNotNull(h);
        assertEquals("42", req.pathParam("id"));
        assertEquals("7", req.pathParam("postId"));
    }

    @Test
    void methodMustMatch() {
        Router router = new Router();
        router.add(HttpMethod.GET, "/hello", (req, resp) -> {});
        HttpRequest req = new HttpRequest(HttpMethod.POST, "/hello", Map.of(), new byte[0]);
        assertNull(router.match(req));
    }

    @Test
    void returnsNullWhenNoMatch() {
        Router router = new Router();
        router.add(HttpMethod.GET, "/a", (req, resp) -> {});
        HttpRequest req = new HttpRequest(HttpMethod.GET, "/b", Map.of(), new byte[0]);
        assertNull(router.match(req));
    }
}
