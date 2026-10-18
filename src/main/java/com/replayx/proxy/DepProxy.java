package com.replayx.proxy;

import com.replayx.match.MatchConfig;
import com.replayx.match.RequestMatcher;
import com.replayx.model.Exchange;
import com.replayx.model.HttpData;
import com.replayx.util.HttpProxyUtil;
import com.replayx.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Serves virtualized downstream dependencies from recorded fixtures.
 *
 * <p>Matching is sequential per (method, path): repeated identical calls
 * consume fixtures in recorded order. Unmatched requests fail closed with
 * a 502 diagnostic — they are never sent to a live service unless
 * explicit passthrough was enabled.
 */
public class DepProxy implements AutoCloseable {
    private final HttpServer server;
    private final List<Exchange> fixtures;
    private final Set<String> consumed = new HashSet<>();
    private final MatchConfig matchConfig;
    private final boolean passthrough;
    private final String passthroughTarget;
    private final HttpClient client;

    public DepProxy(String host, int port, List<Exchange> downstreamFixtures,
                    MatchConfig matchConfig, boolean passthrough, String passthroughTarget) throws IOException {
        this.fixtures = new ArrayList<>(downstreamFixtures);
        this.fixtures.sort(Comparator.comparingInt(e -> e.sequence));
        this.matchConfig = matchConfig == null ? MatchConfig.defaults() : matchConfig;
        this.passthrough = passthrough;
        this.passthroughTarget = passthroughTarget;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 128);
        this.server.setExecutor(CaptureProxy.daemonPool("dep"));
        this.server.createContext("/", new Handler());
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private class Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            HttpData incoming = CaptureProxy.readIncomingRequest(exchange);
            List<Exchange> candidates = new ArrayList<>();
            for (Exchange f : fixtures) {
                if ("downstream".equals(f.kind)
                        && f.request.method != null
                        && f.request.method.equalsIgnoreCase(incoming.method)
                        && java.util.Objects.equals(f.request.path, incoming.path)) {
                    candidates.add(f);
                }
            }

            List<Exchange> fullMatches = new ArrayList<>();
            Map<String, List<String>> mismatchReasons = new LinkedHashMap<>();
            for (Exchange c : candidates) {
                RequestMatcher.MatchVerdict v = RequestMatcher.match(incoming, c.request, matchConfig);
                if (v.matched()) {
                    fullMatches.add(c);
                } else if (mismatchReasons.size() < 3) {
                    mismatchReasons.put(c.id, v.reasons());
                }
            }

            Exchange chosen = null;
            for (Exchange m : fullMatches) {
                if (!consumed.contains(m.id)) {
                    chosen = m;
                    break;
                }
            }
            if (chosen != null) {
                long ambiguous = fullMatches.stream().filter(f -> !consumed.contains(f.id)).count();
                if (ambiguous > 1) {
                    System.err.println("replayx: warning: " + ambiguous
                            + " fixtures match " + incoming.method + " " + incoming.path
                            + "; serving earliest unconsumed (" + chosen.id + ") in recorded order");
                }
                consumed.add(chosen.id);
                CaptureProxy.writeResponse(exchange, chosen.response);
                return;
            }

            if (!fullMatches.isEmpty()) {
                // Every matching fixture was already consumed: a repeated call beyond the recorded count.
                failClosed(exchange, incoming, "all " + fullMatches.size()
                        + " matching fixture(s) already consumed; refusing to invent a new response");
                return;
            }

            if (passthrough && passthroughTarget != null) {
                forwardLive(exchange, incoming);
                return;
            }

            StringBuilder detail = new StringBuilder();
            detail.append(candidates.size()).append(" candidate(s) with same method+path");
            for (Map.Entry<String, List<String>> e : mismatchReasons.entrySet()) {
                detail.append("; ").append(e.getKey()).append(": ").append(String.join(", ", e.getValue()));
            }
            failClosed(exchange, incoming, detail.toString());
        }

        private void failClosed(HttpExchange exchange, HttpData incoming, String detail) throws IOException {
            Map<String, Object> diag = new LinkedHashMap<>();
            diag.put("error", "unmatched-dependency-request");
            diag.put("method", incoming.method);
            diag.put("path", incoming.path);
            diag.put("detail", detail);
            diag.put("hint", "fail closed: no recorded fixture matched; not forwarded to any live service");
            byte[] body;
            try {
                body = Json.MAPPER.writeValueAsBytes(diag);
            } catch (Exception e) {
                body = ("{\"error\":\"unmatched-dependency-request\"}").getBytes(StandardCharsets.UTF_8);
            }
            HttpData res = new HttpData();
            res.status = 502;
            res.setBody(body, "application/json", false);
            CaptureProxy.writeResponse(exchange, res);
        }

        private void forwardLive(HttpExchange exchange, HttpData incoming) throws IOException {
            try {
                URI uri = HttpProxyUtil.joinTarget(passthroughTarget, incoming.path,
                        HttpProxyUtil.encodeQuery(incoming.query));
                HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));
                for (Map.Entry<String, List<String>> e : incoming.headers.entrySet()) {
                    if (CaptureProxy.HOP_BY_HOP.contains(e.getKey())) {
                        continue;
                    }
                    for (String v : e.getValue()) {
                        try {
                            rb.header(e.getKey(), v);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
                byte[] body = incoming.bodyBytes();
                rb.method(incoming.method.toUpperCase(), body.length > 0
                        ? HttpRequest.BodyPublishers.ofByteArray(body)
                        : HttpRequest.BodyPublishers.noBody());
                HttpResponse<byte[]> resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
                HttpData res = new HttpData();
                res.status = resp.statusCode();
                res.headers = HttpProxyUtil.readHeaders(resp.headers());
                res.setBody(resp.body(), HttpProxyUtil.firstHeader(res.headers, "content-type"), false);
                CaptureProxy.writeResponse(exchange, res);
            } catch (Exception e) {
                HttpData res = new HttpData();
                res.status = 502;
                res.setBody(("replayx: passthrough failed: " + e.getMessage()).getBytes(StandardCharsets.UTF_8),
                        "text/plain", false);
                CaptureProxy.writeResponse(exchange, res);
            }
        }
    }
}
