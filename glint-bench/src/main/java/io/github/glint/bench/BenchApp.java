package io.github.glint.bench;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Application logic shared verbatim by the Glint and Tomcat sides of {@link RealisticBench}.
 * Only the HTTP adapter differs between the two servers.
 */
public final class BenchApp {

    /** Simulated slow downstream call (blocking scenario). */
    public static final int SLOW_MS = 50;
    /** Simulated database wait inside the mixed JSON handler. */
    public static final int ORDER_DB_MS = 15;
    /** Simulated database wait for the API share of the mixed-traffic scenario. */
    public static final int ITEM_DB_MS = 10;

    private static final String DESCRIPTION = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(14);

    private BenchApp() {
    }

    public static String hello() {
        return "{\"message\":\"hello\"}";
    }

    public static String slow() throws InterruptedException {
        Thread.sleep(SLOW_MS);
        return "{\"status\":\"ok\",\"downstreamMs\":" + SLOW_MS + "}";
    }

    /** About 1 KiB of JSON after a short DB-like wait. */
    public static String item(String id) throws InterruptedException {
        Thread.sleep(ITEM_DB_MS);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", "Item " + id);
        m.put("price", 19.99);
        m.put("stock", 42);
        m.put("tags", List.of("bench", "realistic", "json"));
        m.put("description", DESCRIPTION);
        return Json.write(m);
    }

    /**
     * Parse an order (~7 KiB JSON), price every line, hash the payload (small CPU work),
     * wait like a DB insert, then serialize a ~5 KiB response.
     */
    @SuppressWarnings("unchecked")
    public static String order(byte[] body) throws Exception {
        Map<String, Object> order = (Map<String, Object>) Json.parse(new String(body, StandardCharsets.UTF_8));
        List<Object> items = (List<Object>) order.get("items");
        double total = 0;
        long qty = 0;
        List<Object> lines = new ArrayList<>(items.size());
        for (Object o : items) {
            Map<String, Object> it = (Map<String, Object>) o;
            double price = ((Number) it.get("price")).doubleValue();
            long q = ((Number) it.get("qty")).longValue();
            double line = price * q;
            total += line;
            qty += q;
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("sku", it.get("sku"));
            l.put("qty", q);
            l.put("lineTotal", Math.round(line * 100) / 100.0);
            l.put("tags", it.get("tags"));
            lines.add(l);
        }
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (int k = 0; k < 5; k++) {
            md.update(body);
        }
        String digest = HexFormat.of().formatHex(md.digest());
        Thread.sleep(ORDER_DB_MS);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("orderId", order.get("orderId"));
        resp.put("customer", order.get("customer"));
        resp.put("itemCount", items.size());
        resp.put("totalQty", qty);
        resp.put("total", Math.round(total * 100) / 100.0);
        resp.put("digest", digest);
        resp.put("lines", lines);
        return Json.write(resp);
    }

    /** Consume an upload body and return its size and CRC32. */
    public static String upload(InputStream in) throws IOException {
        CRC32 crc = new CRC32();
        byte[] buf = new byte[64 * 1024];
        long n = 0;
        int r;
        while ((r = in.read(buf)) != -1) {
            crc.update(buf, 0, r);
            n += r;
        }
        return "{\"bytes\":" + n + ",\"crc32\":" + crc.getValue() + "}";
    }

    /** Deterministic order payload with {@code items} lines (60 lines is about 7 KiB). */
    public static byte[] samplePayload(int items) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderId", "ord-000123");
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("id", 987);
        customer.put("name", "Jane Doe");
        customer.put("email", "jane.doe@example.com");
        customer.put("vip", true);
        order.put("customer", customer);
        List<Object> list = new ArrayList<>();
        for (int k = 0; k < items; k++) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("sku", "SKU-" + (10000 + k));
            it.put("name", "Product number " + k + " with a longer display name");
            it.put("qty", 1 + (k % 5));
            it.put("price", 3.5 + k * 1.25);
            it.put("tags", List.of("tag" + (k % 7), "cat" + (k % 3)));
            list.add(it);
        }
        order.put("items", list);
        order.put("note", "Leave at the door, ring twice.");
        return Json.write(order).getBytes(StandardCharsets.UTF_8);
    }
}
