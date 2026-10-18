package com.replayx.match;

import com.replayx.model.HttpData;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RequestMatcherTest {

    private static HttpData req(String method, String path, String query, String jsonBody) {
        HttpData d = new HttpData();
        d.method = method;
        d.path = path;
        d.query = com.replayx.util.HttpProxyUtil.parseQuery(query);
        d.headers = Map.of("content-type", List.of("application/json"));
        if (jsonBody != null) {
            d.setBody(jsonBody.getBytes(StandardCharsets.UTF_8), "application/json", false);
        }
        return d;
    }

    @Test
    void identicalRequestsMatch() {
        HttpData a = req("POST", "/api/checkout", "a=1&b=2", "{\"item\":\"book\"}");
        HttpData b = req("POST", "/api/checkout", "b=2&a=1", "{\"item\":\"book\"}");
        // query order ignored by default
        assertTrue(RequestMatcher.match(a, b, MatchConfig.defaults()).matched());
    }

    @Test
    void methodMismatchIsReported() {
        HttpData a = req("POST", "/api/checkout", "", null);
        HttpData b = req("GET", "/api/checkout", "", null);
        RequestMatcher.MatchVerdict v = RequestMatcher.match(a, b, MatchConfig.defaults());
        assertFalse(v.matched());
        assertTrue(v.reasons().stream().anyMatch(r -> r.contains("method")));
    }

    @Test
    void pathMismatchIsReported() {
        HttpData a = req("GET", "/api/a", "", null);
        HttpData b = req("GET", "/api/b", "", null);
        assertFalse(RequestMatcher.match(a, b, MatchConfig.defaults()).matched());
    }

    @Test
    void queryValueMismatchIsReported() {
        HttpData a = req("GET", "/api/a", "a=1", null);
        HttpData b = req("GET", "/api/a", "a=2", null);
        RequestMatcher.MatchVerdict v = RequestMatcher.match(a, b, MatchConfig.defaults());
        assertFalse(v.matched());
        assertTrue(v.reasons().stream().anyMatch(r -> r.contains("query")));
    }

    @Test
    void jsonBodyComparedStructurallyWithIgnoredFields() {
        MatchConfig cfg = MatchConfig.defaults();
        cfg.ignoreJsonFields.add("requestId");
        HttpData a = req("POST", "/x", "", "{\"requestId\":\"1\",\"item\":\"book\"}");
        HttpData b = req("POST", "/x", "", "{\"requestId\":\"2\",\"item\":\"book\"}");
        assertTrue(RequestMatcher.match(a, b, cfg).matched());

        HttpData c = req("POST", "/x", "", "{\"requestId\":\"2\",\"item\":\"pen\"}");
        assertFalse(RequestMatcher.match(a, c, cfg).matched());
    }

    @Test
    void nonJsonBodyComparedByBytes() {
        HttpData a = req("POST", "/x", "", null);
        a.setBody("hello".getBytes(StandardCharsets.UTF_8), "text/plain", false);
        HttpData b = req("POST", "/x", "", null);
        b.setBody("hello".getBytes(StandardCharsets.UTF_8), "text/plain", false);
        assertTrue(RequestMatcher.match(a, b, MatchConfig.defaults()).matched());

        b.setBody("world".getBytes(StandardCharsets.UTF_8), "text/plain", false);
        assertFalse(RequestMatcher.match(a, b, MatchConfig.defaults()).matched());
    }

    @Test
    void contentTypeHeaderIsMatched() {
        HttpData a = req("POST", "/x", "", null);
        HttpData b = req("POST", "/x", "", null);
        b.headers = Map.of("content-type", List.of("text/plain"));
        RequestMatcher.MatchVerdict v = RequestMatcher.match(a, b, MatchConfig.defaults());
        assertFalse(v.matched());
        assertTrue(v.reasons().stream().anyMatch(r -> r.contains("content-type")));
    }
}
