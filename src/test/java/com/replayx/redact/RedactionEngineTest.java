package com.replayx.redact;

import com.replayx.model.Exchange;
import com.replayx.model.HttpData;
import com.replayx.util.Json;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RedactionEngineTest {

    private static Exchange exchange(String jsonBody) {
        Exchange ex = new Exchange();
        ex.request.headers.put("authorization", List.of("Bearer s3cret"));
        ex.request.headers.put("cookie", List.of("session=abc"));
        ex.request.headers.put("content-type", List.of("application/json"));
        ex.request.setBody(jsonBody.getBytes(StandardCharsets.UTF_8), "application/json", false);
        ex.response.setBody("{\"ok\":true}".getBytes(StandardCharsets.UTF_8), "application/json", false);
        return ex;
    }

    @Test
    void sensitiveHeadersAreRemoved() {
        Exchange ex = exchange("{\"a\":1}");
        new RedactionEngine().redact(ex);
        assertFalse(ex.request.headers.containsKey("authorization"));
        assertFalse(ex.request.headers.containsKey("cookie"));
        assertTrue(ex.request.headers.containsKey("content-type"));
        assertTrue(ex.redactionWarnings.stream().anyMatch(w -> w.contains("authorization")));
    }

    @Test
    void nestedSecretFieldsAreMasked() {
        Exchange ex = exchange("{\"user\":{\"name\":\"amy\",\"password\":\"hunter2\"},\"apiKey\":\"k\"}");
        new RedactionEngine().redact(ex);
        String body = new String(ex.request.bodyBytes(), StandardCharsets.UTF_8);
        assertFalse(body.contains("hunter2"));
        assertTrue(body.contains(Json.REDACTED));
        // non-secret data survives
        assertTrue(body.contains("amy"));
    }

    @Test
    void secretsInNestedObjectsInsideArraysAreMasked() {
        Exchange ex = exchange("{\"users\":[{\"name\":\"amy\",\"password\":\"hunter2\"}],\"keep\":\"yes\"}");
        new RedactionEngine().redact(ex);
        String body = new String(ex.request.bodyBytes(), StandardCharsets.UTF_8);
        assertFalse(body.contains("hunter2"));
        assertTrue(body.contains("amy"));
        assertTrue(body.contains("yes"));
    }

    @Test
    void unparseableJsonBodyWarns() {
        Exchange ex = exchange("{not json");
        List<String> warnings = new RedactionEngine().redact(ex);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("could not be parsed")));
    }

    @Test
    void binaryBodyWarnsAndIsKept() {
        Exchange ex = exchange("{\"a\":1}");
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
        ex.response.setBody(png, "image/png", false);
        List<String> warnings = new RedactionEngine().redact(ex);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("not inspected")));
        assertArrayEquals(png, ex.response.bodyBytes());
    }

    @Test
    void sensitiveQueryParamsAreMasked() {
        Exchange ex = exchange("{\"a\":1}");
        ex.request.query.put("api_key", new java.util.ArrayList<>(List.of("k123")));
        new RedactionEngine().redact(ex);
        assertEquals(List.of(Json.REDACTED), ex.request.query.get("api_key"));
    }
}
