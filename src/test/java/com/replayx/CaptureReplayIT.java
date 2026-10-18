package com.replayx;

import com.fasterxml.jackson.databind.JsonNode;
import com.replayx.cli.DiffCommand;
import com.replayx.diff.DiffEngine;
import com.replayx.match.MatchConfig;
import com.replayx.model.Exchange;
import com.replayx.proxy.CaptureProxy;
import com.replayx.proxy.DepProxy;
import com.replayx.redact.RedactionEngine;
import com.replayx.replay.Replayer;
import com.replayx.store.CassetteStore;
import com.replayx.util.Json;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end: capture through the proxy (with downstream recording and
 * redaction), replay against a regressed service while virtualizing the
 * downstream dependency, diff, fail-closed unmatched calls, and CLI exit codes.
 */
class CaptureReplayIT {
    private static final java.nio.charset.Charset UTF_8 = StandardCharsets.UTF_8;

    // ------------------------------------------------------------------ helpers

    private static HttpServer startServer(HttpHandler handler) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", handler);
        s.setExecutor(Executors.newCachedThreadPool());
        s.start();
        return s;
    }

    private static int portOf(HttpServer s) {
        return s.getAddress().getPort();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Downstream stub: always reports payment SUCCESS. */
    private static HttpHandler chargeStub() {
        return exchange -> {
            byte[] rb = "{\"paymentStatus\":\"SUCCESS\",\"txnId\":\"txn-1\"}".getBytes(UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, rb.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(rb);
            }
        };
    }

    /**
     * Sample checkout service. Calls {downstreamBase}/charge, then answers.
     * Mode "v2" intentionally regresses paymentStatus to PENDING.
     */
    private static HttpHandler serviceHandler(String mode, String downstreamBase) {
        return exchange -> {
            try {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())
                        || !"/api/checkout".equals(exchange.getRequestURI().getRawPath())) {
                    byte[] nf = "not found".getBytes(UTF_8);
                    exchange.sendResponseHeaders(404, nf.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(nf);
                    }
                    return;
                }
                JsonNode reqJson = Json.MAPPER.readTree(exchange.getRequestBody().readAllBytes());
                int amount = reqJson.path("amount").asInt(100);
                HttpRequest dr = HttpRequest.newBuilder(
                                URI.create(downstreamBase + "/charge?amount=" + amount))
                        .timeout(Duration.ofSeconds(10)).GET().build();
                JsonNode pay = Json.MAPPER.readTree(CLIENT.send(dr, HttpResponse.BodyHandlers.ofString()).body());
                String status = "v2".equals(mode) ? "PENDING" : pay.path("paymentStatus").asText("SUCCESS");
                byte[] rb = Json.MAPPER.writeValueAsBytes(
                        Map.of("orderId", "ord-1", "paymentStatus", status, "amount", amount));
                exchange.getResponseHeaders().add("content-type", "application/json");
                exchange.sendResponseHeaders(200, rb.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(rb);
                }
            } catch (Exception e) {
                byte[] err = ("error: " + e.getMessage()).getBytes(UTF_8);
                try {
                    exchange.sendResponseHeaders(500, err.length);
                    exchange.getResponseBody().write(err);
                } catch (IOException ignored) {
                }
                exchange.close();
            }
        };
    }

    /**
     * Captures one checkout call (with an Authorization header and a secret
     * JSON field) through the capture proxies. Returns the cassette dir.
     */
    private static Path captureSampleCassette(Path dir) throws Exception {
        HttpServer stub = startServer(chargeStub());
        CassetteStore store = new CassetteStore();
        RedactionEngine redactor = new RedactionEngine();

        CaptureProxy depCap = new CaptureProxy("127.0.0.1", 0,
                URI.create("http://127.0.0.1:" + portOf(stub)), store, dir, redactor, "downstream");
        depCap.start();
        String depUrl = "http://127.0.0.1:" + depCap.port();

        HttpServer v1 = startServer(serviceHandler("v1", depUrl));
        String v1Url = "http://127.0.0.1:" + portOf(v1);

        store.createSession(dir, dir.getFileName().toString(), v1Url,
                "http://127.0.0.1:" + portOf(stub));

        CaptureProxy inCap = new CaptureProxy("127.0.0.1", 0, URI.create(v1Url), store, dir, redactor, "inbound");
        inCap.start();
        try {
            String body = "{\"item\":\"book\",\"cardToken\":\"tok_abc\",\"amount\":100}";
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + inCap.port() + "/api/checkout"))
                    .header("content-type", "application/json")
                    .header("authorization", "Bearer s3cret")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            assertTrue(resp.body().contains("SUCCESS"));
        } finally {
            inCap.close();
            depCap.close();
            v1.stop(0);
            stub.stop(0);
        }
        store.finalizeSession(dir);
        return dir;
    }

    private static List<Exchange> ofKind(CassetteStore store, Path dir, String kind) throws IOException {
        return store.readExchanges(dir).stream()
                .filter(e -> kind.equals(e.kind))
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ tests

    @Test
    void captureRecordsRedactsAndValidates(@TempDir Path tmp) throws Exception {
        Path dir = captureSampleCassette(tmp.resolve("checkout"));
        CassetteStore store = new CassetteStore();

        assertTrue(store.validate(dir).isEmpty(), "fresh capture should validate");

        List<Exchange> inbound = ofKind(store, dir, "inbound");
        List<Exchange> downstream = ofKind(store, dir, "downstream");
        assertEquals(1, inbound.size());
        assertEquals(1, downstream.size());
        assertEquals("POST", inbound.get(0).request.method);
        assertEquals("/api/checkout", inbound.get(0).request.path);
        assertEquals(Integer.valueOf(200), inbound.get(0).response.status);

        // Secrets must not be on disk.
        String raw = Files.readString(dir.resolve("exchanges.jsonl"), UTF_8);
        assertFalse(raw.contains("s3cret"), "Authorization secret persisted!");
        assertFalse(inbound.get(0).request.headers.containsKey("authorization"),
                "Authorization header should be dropped, not stored");
        String reqBody = new String(inbound.get(0).request.bodyBytes(), UTF_8);
        assertFalse(reqBody.contains("tok_abc"), "cardToken persisted!");
        assertTrue(reqBody.contains(Json.REDACTED), "expected redaction markers");

        // The business payload survives redaction.
        String respBody = new String(inbound.get(0).response.bodyBytes(), UTF_8);
        assertTrue(respBody.contains("\"paymentStatus\":\"SUCCESS\""));
    }

    @Test
    void replayVirtualizesDownstreamAndDetectsRegression(@TempDir Path tmp) throws Exception {
        Path dir = captureSampleCassette(tmp.resolve("checkout"));
        CassetteStore store = new CassetteStore();
        List<Exchange> inbound = ofKind(store, dir, "inbound");
        List<Exchange> downstream = ofKind(store, dir, "downstream");

        DepProxy depReplay = new DepProxy("127.0.0.1", 0, downstream,
                MatchConfig.defaults(), false, null);
        depReplay.start();
        HttpServer v2 = startServer(serviceHandler("v2", "http://127.0.0.1:" + depReplay.port()));
        try {
            List<Replayer.ReplayOutcome> outcomes = Replayer.replay(inbound,
                    URI.create("http://127.0.0.1:" + portOf(v2)), new RedactionEngine());
            assertEquals(1, outcomes.size());
            assertFalse(outcomes.get(0).failed(), outcomes.get(0).error());

            DiffEngine.DiffResult r = DiffEngine.diff(inbound.get(0).response,
                    outcomes.get(0).actual(), new DiffEngine.DiffConfig());
            assertFalse(r.hasBreaking());
            assertTrue(r.hasBehavioral(), "expected behavioral regression, got: " + r.changes());
            assertTrue(r.isRegression());
            assertTrue(r.changes().stream().anyMatch(c ->
                    c.path().equals("$.paymentStatus")
                            && "\"SUCCESS\"".equals(c.expected())
                            && "\"PENDING\"".equals(c.actual())));
        } finally {
            v2.stop(0);
            depReplay.close();
        }
    }

    @Test
    void unmatchedDependencyFailsClosed(@TempDir Path tmp) throws Exception {
        Path dir = captureSampleCassette(tmp.resolve("checkout"));
        CassetteStore store = new CassetteStore();
        List<Exchange> downstream = ofKind(store, dir, "downstream");

        DepProxy dep = new DepProxy("127.0.0.1", 0, downstream, MatchConfig.defaults(), false, null);
        dep.start();
        try {
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + dep.port() + "/no/such/endpoint"))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(502, resp.statusCode());
            assertTrue(resp.body().contains("unmatched-dependency"));
        } finally {
            dep.close();
        }
    }

    @Test
    void diffCommandExitCodes(@TempDir Path tmp) throws Exception {
        Path dir = captureSampleCassette(tmp.resolve("checkout"));

        int depPort = freePort();
        HttpServer v2 = startServer(serviceHandler("v2", "http://127.0.0.1:" + depPort));
        String target = "http://127.0.0.1:" + portOf(v2);
        try {
            int withFlag = new CommandLine(new DiffCommand()).execute(
                    dir.toString(), "--target", target,
                    "--dep-listen", "127.0.0.1:" + depPort,
                    "--fail-on-regression");
            assertEquals(2, withFlag, "expected exit 2 on regression with --fail-on-regression");

            int withoutFlag = new CommandLine(new DiffCommand()).execute(
                    dir.toString(), "--target", target,
                    "--dep-listen", "127.0.0.1:" + depPort);
            assertEquals(0, withoutFlag, "expected exit 0 without --fail-on-regression");
        } finally {
            v2.stop(0);
        }
    }
}
