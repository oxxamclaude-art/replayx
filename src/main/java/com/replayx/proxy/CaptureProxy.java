package com.replayx.proxy;

import com.replayx.model.Exchange;
import com.replayx.model.HttpData;
import com.replayx.redact.RedactionEngine;
import com.replayx.store.CassetteStore;
import com.replayx.util.HttpProxyUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny reverse proxy built on the JDK HttpServer. Forwards traffic to the
 * configured target and records redacted request/response exchanges.
 * Used for inbound capture (kind="inbound") and for recording downstream
 * dependency traffic (kind="downstream").
 */
public class CaptureProxy implements AutoCloseable {
    public static final long MAX_BODY_BYTES = 10L * 1024 * 1024;

    /** Headers never forwarded or stored; also skipped when writing responses back. */
    public static final List<String> HOP_BY_HOP = List.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length", "expect");

    private final HttpServer server;
    private final HttpClient client;
    private final String targetBase;
    private final CassetteStore store;
    private final Path cassetteDir;
    private final RedactionEngine redactor;
    private final String kind;
    private final AtomicInteger seq = new AtomicInteger();

    public CaptureProxy(String host, int port, URI target, CassetteStore store, Path cassetteDir,
                        RedactionEngine redactor, String kind) throws IOException {
        this.targetBase = target.toString();
        this.store = store;
        this.cassetteDir = cassetteDir;
        this.redactor = redactor;
        this.kind = kind;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 128);
        this.server.setExecutor(daemonPool(kind));
        this.server.createContext("/", new Handler());
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public void start() {
        server.start();
    }

    /** Daemon worker pool so proxy threads never keep the JVM alive on their own. */
    static ExecutorService daemonPool(String name) {
        AtomicInteger n = new AtomicInteger();
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "replayx-" + name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Actual bound port (useful when port 0 was requested). */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** Read the inbound side of an exchange from an HttpExchange. */
    public static HttpData readIncomingRequest(HttpExchange exchange) throws IOException {
        HttpData req = new HttpData();
        req.method = exchange.getRequestMethod();
        URI uri = exchange.getRequestURI();
        String rawPath = uri.getRawPath();
        req.path = (rawPath == null || rawPath.isEmpty()) ? "/" : rawPath;
        req.query = HttpProxyUtil.parseQuery(uri.getRawQuery());
        req.headers = HttpProxyUtil.readHeaders(exchange.getRequestHeaders());
        HttpProxyUtil.BodyRead body = HttpProxyUtil.readBody(exchange.getRequestBody(), MAX_BODY_BYTES);
        req.setBody(body.bytes(), HttpProxyUtil.firstHeader(req.headers, "content-type"), body.truncated());
        return req;
    }

    /** Write a recorded (or live) response back to the client. */
    public static void writeResponse(HttpExchange exchange, HttpData response) throws IOException {
        var outHeaders = exchange.getResponseHeaders();
        for (Map.Entry<String, List<String>> e : response.headers.entrySet()) {
            if (HOP_BY_HOP.contains(e.getKey())) {
                continue;
            }
            for (String v : e.getValue()) {
                outHeaders.add(e.getKey(), v);
            }
        }
        int status = response.status == null ? 200 : response.status;
        byte[] body = response.bodyBytes();
        if (status == 204 || status == 304) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private class Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            long start = System.currentTimeMillis();
            Exchange rec = new Exchange();
            rec.id = String.format("ex-%06d", seq.incrementAndGet());
            rec.kind = kind;
            rec.sequence = seq.get();
            rec.timestamp = Instant.now().toString();
            try {
                HttpData req = readIncomingRequest(exchange);
                rec.request = req;

                URI forwardUri = HttpProxyUtil.joinTarget(targetBase, req.path,
                        exchange.getRequestURI().getRawQuery());
                HttpRequest.Builder rb = HttpRequest.newBuilder(forwardUri)
                        .timeout(Duration.ofSeconds(30));
                for (Map.Entry<String, List<String>> e : req.headers.entrySet()) {
                    if (HOP_BY_HOP.contains(e.getKey())) {
                        continue;
                    }
                    for (String v : e.getValue()) {
                        try {
                            rb.header(e.getKey(), v);
                        } catch (IllegalArgumentException ignored) {
                            // restricted header (e.g. set by HttpClient itself)
                        }
                    }
                }
                byte[] body = req.bodyBytes();
                HttpRequest.BodyPublisher pub = body.length > 0
                        ? HttpRequest.BodyPublishers.ofByteArray(body)
                        : HttpRequest.BodyPublishers.noBody();
                rb.method(req.method.toUpperCase(), pub);

                HttpResponse<byte[]> resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());

                HttpData res = new HttpData();
                res.status = resp.statusCode();
                res.headers = HttpProxyUtil.readHeaders(resp.headers());
                res.setBody(resp.body(), HttpProxyUtil.firstHeader(res.headers, "content-type"), false);
                rec.response = res;
                rec.durationMs = System.currentTimeMillis() - start;

                redactor.redact(rec);
                store.appendExchange(cassetteDir, rec);
                writeResponse(exchange, res);
            } catch (Exception e) {
                rec.durationMs = System.currentTimeMillis() - start;
                HttpData res = new HttpData();
                res.status = 502;
                String msg = "replayx: failed to reach target: " + e.getMessage();
                res.setBody(msg.getBytes(StandardCharsets.UTF_8), "text/plain", false);
                rec.response = res;
                try {
                    redactor.redact(rec);
                    store.appendExchange(cassetteDir, rec);
                } catch (Exception storeErr) {
                    System.err.println("replayx: failed to record exchange: " + storeErr.getMessage());
                }
                try {
                    writeResponse(exchange, res);
                } catch (Exception writeErr) {
                    exchange.close();
                }
            }
        }
    }

    /** Expose recorded sequence count (handy for tests/logs). */
    public int recordedCount() {
        return seq.get();
    }

    /** Package-visible for tests: current cassette dir. */
    Path cassetteDir() {
        return cassetteDir;
    }

    /** Package-visible for tests: exchange kind recorded by this proxy. */
    String kind() {
        return kind;
    }

    /** Package-visible for tests: redactor in use. */
    RedactionEngine redactor() {
        return redactor;
    }
}
