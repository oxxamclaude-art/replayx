package com.replayx.store;

import com.replayx.model.CassetteManifest;
import com.replayx.model.Exchange;
import com.replayx.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CassetteStoreTest {

    private static Exchange sample(String id, String kind) {
        Exchange ex = new Exchange();
        ex.id = id;
        ex.kind = kind;
        ex.sequence = 1;
        ex.timestamp = "2026-10-18T00:00:00Z";
        ex.request.method = "GET";
        ex.request.path = "/api/items";
        ex.response.status = 200;
        ex.response.setBody("{\"ok\":true}".getBytes(StandardCharsets.UTF_8), "application/json", false);
        return ex;
    }

    @Test
    void roundTrip(@TempDir Path tmp) throws Exception {
        CassetteStore store = new CassetteStore();
        Path dir = tmp.resolve("s1");
        store.createSession(dir, "s1", "http://localhost:8080", null);
        store.appendExchange(dir, sample("ex-000001", "inbound"));
        store.appendExchange(dir, sample("ex-000002", "downstream"));
        store.finalizeSession(dir);

        assertTrue(store.validate(dir).isEmpty());

        CassetteManifest m = store.readManifest(dir);
        assertTrue(m.complete);
        assertEquals(1, m.inboundCount);
        assertEquals(1, m.downstreamCount);
        assertEquals("http://localhost:8080", m.target.get("url"));

        List<Exchange> read = store.readExchanges(dir);
        assertEquals(2, read.size());
        assertEquals("ex-000001", read.get(0).id);
    }

    @Test
    void interruptedSessionFailsValidation(@TempDir Path tmp) throws Exception {
        CassetteStore store = new CassetteStore();
        Path dir = tmp.resolve("s2");
        store.createSession(dir, "s2", "http://localhost:8080", null);
        store.appendExchange(dir, sample("ex-000001", "inbound"));
        // no finalizeSession: simulates Ctrl-C / kill

        List<String> problems = store.validate(dir);
        assertTrue(problems.stream().anyMatch(p -> p.contains("incomplete")),
                "expected incomplete-session error, got: " + problems);
    }

    @Test
    void unsupportedVersionIsRejected(@TempDir Path tmp) throws Exception {
        CassetteStore store = new CassetteStore();
        Path dir = tmp.resolve("s3");
        store.createSession(dir, "s3", "http://localhost:8080", null);
        store.appendExchange(dir, sample("ex-000001", "inbound"));
        store.finalizeSession(dir);

        // Corrupt the version by hand.
        CassetteManifest m = store.readManifest(dir);
        m.formatVersion = 99;
        Path manifest = dir.resolve("manifest.json");
        Files.writeString(manifest, Json.MAPPER.writeValueAsString(m), StandardCharsets.UTF_8);

        List<String> problems = store.validate(dir);
        assertTrue(problems.stream().anyMatch(p -> p.contains("unsupported cassette format version 99")),
                "expected version error, got: " + problems);
    }

    @Test
    void corruptExchangeLineIsReported(@TempDir Path tmp) throws Exception {
        CassetteStore store = new CassetteStore();
        Path dir = tmp.resolve("s4");
        store.createSession(dir, "s4", "http://localhost:8080", null);
        store.appendExchange(dir, sample("ex-000001", "inbound"));
        store.finalizeSession(dir);
        // Corrupt the file after finalization; validate() must report it leniently.
        Files.writeString(dir.resolve("exchanges.jsonl"), "{not valid json}\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        List<String> problems = store.validate(dir);
        assertTrue(problems.stream().anyMatch(p -> p.contains("corrupt")),
                "expected corrupt-line error, got: " + problems);
    }

    @Test
    void missingManifestIsReported(@TempDir Path tmp) {
        CassetteStore store = new CassetteStore();
        List<String> problems = store.validate(tmp.resolve("does-not-exist"));
        assertFalse(problems.isEmpty());
    }

    @Test
    void targetUserinfoIsStripped(@TempDir Path tmp) throws Exception {
        CassetteStore store = new CassetteStore();
        Path dir = tmp.resolve("s5");
        store.createSession(dir, "s5", "http://user:pass@localhost:8080", null);
        CassetteManifest m = store.readManifest(dir);
        assertEquals("http://localhost:8080", m.target.get("url"));
    }
}
