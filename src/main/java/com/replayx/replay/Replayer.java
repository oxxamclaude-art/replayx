package com.replayx.replay;

import com.replayx.model.Exchange;
import com.replayx.model.HttpData;
import com.replayx.proxy.CaptureProxy;
import com.replayx.redact.RedactionEngine;
import com.replayx.util.HttpProxyUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sends recorded inbound requests to a new target and captures the new
 * responses. Replayed responses are redacted before they are returned,
 * so replay sessions are safe to persist.
 */
public final class Replayer {
    private Replayer() {}

    public record ReplayOutcome(Exchange original, HttpData actual, String error) {
        public boolean failed() {
            return error != null;
        }
    }

    public static List<ReplayOutcome> replay(List<Exchange> inbound, URI target, RedactionEngine redactor) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        String base = target.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        List<ReplayOutcome> out = new ArrayList<>();
        for (Exchange orig : inbound) {
            HttpData req = orig.request;
            try {
                String qs = HttpProxyUtil.encodeQuery(req.query);
                URI uri = URI.create(base + req.path + (qs.isEmpty() ? "" : "?" + qs));
                HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));
                for (Map.Entry<String, List<String>> e : req.headers.entrySet()) {
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
                byte[] body = req.bodyBytes();
                rb.method(req.method.toUpperCase(), body.length > 0
                        ? HttpRequest.BodyPublishers.ofByteArray(body)
                        : HttpRequest.BodyPublishers.noBody());
                HttpResponse<byte[]> resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());

                HttpData actual = new HttpData();
                actual.status = resp.statusCode();
                actual.headers = HttpProxyUtil.readHeaders(resp.headers());
                actual.setBody(resp.body(), HttpProxyUtil.firstHeader(actual.headers, "content-type"), false);

                // Redact before the replayed data goes anywhere persistent.
                Exchange tmp = new Exchange();
                tmp.request = req;
                tmp.response = actual;
                redactor.redact(tmp);

                out.add(new ReplayOutcome(orig, tmp.response, null));
            } catch (Exception e) {
                out.add(new ReplayOutcome(orig, null, "replay failed: " + e.getMessage()));
            }
        }
        return out;
    }
}
