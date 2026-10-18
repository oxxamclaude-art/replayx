package com.replayx.diff;

import com.replayx.model.HttpData;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DiffEngineTest {

    private static HttpData res(int status, String jsonBody) {
        HttpData d = new HttpData();
        d.status = status;
        d.headers = Map.of("content-type", List.of("application/json"));
        if (jsonBody != null) {
            d.setBody(jsonBody.getBytes(StandardCharsets.UTF_8), "application/json", false);
        }
        return d;
    }

    @Test
    void identicalResponsesHaveNoChanges() {
        DiffEngine.DiffResult r = DiffEngine.diff(
                res(200, "{\"a\":1}"), res(200, "{\"a\":1}"), new DiffEngine.DiffConfig());
        assertTrue(r.changes().isEmpty());
        assertFalse(r.isRegression());
    }

    @Test
    void statusChangeIsBreaking() {
        DiffEngine.DiffResult r = DiffEngine.diff(
                res(200, "{\"a\":1}"), res(500, "{\"a\":1}"), new DiffEngine.DiffConfig());
        assertTrue(r.hasBreaking());
        assertTrue(r.isRegression());
        assertTrue(r.changes().stream().anyMatch(c -> c.path().equals("status")));
    }

    @Test
    void valueChangeIsBehavioral() {
        DiffEngine.DiffResult r = DiffEngine.diff(
                res(200, "{\"paymentStatus\":\"SUCCESS\"}"),
                res(200, "{\"paymentStatus\":\"PENDING\"}"),
                new DiffEngine.DiffConfig());
        assertFalse(r.hasBreaking());
        assertTrue(r.hasBehavioral());
        assertTrue(r.isRegression());
        assertTrue(r.changes().stream().anyMatch(c ->
                c.path().equals("$.paymentStatus") && c.category() == DiffEngine.Category.BEHAVIORAL));
    }

    @Test
    void missingFieldIsBreaking() {
        DiffEngine.DiffResult r = DiffEngine.diff(
                res(200, "{\"a\":1,\"b\":2}"), res(200, "{\"a\":1}"), new DiffEngine.DiffConfig());
        assertTrue(r.hasBreaking());
        assertTrue(r.changes().stream().anyMatch(c ->
                c.path().equals("$.b") && c.category() == DiffEngine.Category.BREAKING));
    }

    @Test
    void typeChangeIsBreaking() {
        DiffEngine.DiffResult r = DiffEngine.diff(
                res(200, "{\"a\":1}"), res(200, "{\"a\":\"1\"}"), new DiffEngine.DiffConfig());
        assertTrue(r.hasBreaking());
    }

    @Test
    void ignoredFieldIsNotARegression() {
        DiffEngine.DiffConfig cfg = new DiffEngine.DiffConfig();
        cfg.ignoreJsonFields.add("timestamp");
        DiffEngine.DiffResult r = DiffEngine.diff(
                res(200, "{\"timestamp\":\"t1\",\"a\":1}"),
                res(200, "{\"timestamp\":\"t2\",\"a\":1}"),
                cfg);
        assertFalse(r.isRegression());
    }

    @Test
    void addedFieldIsInformational() {
        DiffEngine.DiffResult r = DiffEngine.diff(
                res(200, "{\"a\":1}"), res(200, "{\"a\":1,\"b\":2}"), new DiffEngine.DiffConfig());
        assertFalse(r.isRegression());
        assertTrue(r.changes().stream().anyMatch(c ->
                c.category() == DiffEngine.Category.INFORMATIONAL));
    }

    @Test
    void nestedArrayChangeDetected() {
        DiffEngine.DiffResult r = DiffEngine.diff(
                res(200, "{\"items\":[{\"id\":1},{\"id\":2}]}"),
                res(200, "{\"items\":[{\"id\":1},{\"id\":3}]}"),
                new DiffEngine.DiffConfig());
        assertTrue(r.isRegression());
        assertTrue(r.changes().stream().anyMatch(c -> c.path().equals("$.items[1].id")));
    }
}
