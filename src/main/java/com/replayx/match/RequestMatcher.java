package com.replayx.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.replayx.diff.JsonCompare;
import com.replayx.model.HttpData;
import com.replayx.util.HttpProxyUtil;
import com.replayx.util.Json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Matches an incoming request against a recorded one: method, normalized
 * path, query parameters, selected headers, and body (exact or
 * JSON-structural). Produces human-readable mismatch reasons for
 * diagnostics instead of a bare boolean.
 */
public final class RequestMatcher {
    private RequestMatcher() {}

    public record MatchVerdict(boolean matched, List<String> reasons) {
        public static MatchVerdict ok() {
            return new MatchVerdict(true, List.of());
        }

        public static MatchVerdict no(List<String> reasons) {
            return new MatchVerdict(false, List.copyOf(reasons));
        }
    }

    public static MatchVerdict match(HttpData candidate, HttpData recorded, MatchConfig cfg) {
        List<String> reasons = new ArrayList<>();

        if (!Objects.equals(normMethod(candidate.method), normMethod(recorded.method))) {
            reasons.add("method differs: " + candidate.method + " vs " + recorded.method);
        }
        if (!Objects.equals(candidate.path, recorded.path)) {
            reasons.add("path differs: " + candidate.path + " vs " + recorded.path);
        }

        String qDiff = diffQuery(candidate.query, recorded.query, cfg.ignoreQueryOrder);
        if (qDiff != null) {
            reasons.add(qDiff);
        }

        for (String header : cfg.matchHeaders) {
            String c = HttpProxyUtil.firstHeader(candidate.headers, header);
            String r = HttpProxyUtil.firstHeader(recorded.headers, header);
            if (!Objects.equals(normHeader(c), normHeader(r))) {
                reasons.add("header '" + header + "' differs: " + c + " vs " + r);
            }
        }

        String bDiff = diffBody(candidate, recorded, cfg);
        if (bDiff != null) {
            reasons.add(bDiff);
        }

        return reasons.isEmpty() ? MatchVerdict.ok() : MatchVerdict.no(reasons);
    }

    private static String normMethod(String m) {
        return m == null ? null : m.toUpperCase();
    }

    private static String normHeader(String v) {
        if (v == null) {
            return null;
        }
        int i = v.indexOf(';');
        return (i < 0 ? v : v.substring(0, i)).trim().toLowerCase();
    }

    static String diffQuery(Map<String, List<String>> a, Map<String, List<String>> b, boolean ignoreOrder) {
        Map<String, List<String>> na = normQuery(a, ignoreOrder);
        Map<String, List<String>> nb = normQuery(b, ignoreOrder);
        if (na.equals(nb)) {
            return null;
        }
        return "query differs: " + na + " vs " + nb;
    }

    private static Map<String, List<String>> normQuery(Map<String, List<String>> q, boolean ignoreOrder) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (q == null) {
            return out;
        }
        for (Map.Entry<String, List<String>> e : q.entrySet()) {
            List<String> vals = new ArrayList<>(e.getValue());
            if (ignoreOrder) {
                Collections.sort(vals);
            }
            out.put(e.getKey(), vals);
        }
        return out;
    }

    static String diffBody(HttpData candidate, HttpData recorded, MatchConfig cfg) {
        byte[] cb = candidate.bodyBytes();
        byte[] rb = recorded.bodyBytes();
        if (cb.length == 0 && rb.length == 0) {
            return null;
        }
        if (cfg.jsonStructuralBody && looksJson(candct(candidate), cb) && looksJson(candct(recorded), rb)) {
            try {
                JsonNode cn = Json.MAPPER.readTree(cb);
                JsonNode rn = Json.MAPPER.readTree(rb);
                if (JsonCompare.equal(cn, rn, cfg.ignoreJsonFields)) {
                    return null;
                }
                return "JSON body differs structurally";
            } catch (Exception e) {
                // fall through to byte comparison
            }
        }
        if (Arrays.equals(cb, rb)) {
            return null;
        }
        return "body differs (" + cb.length + " vs " + rb.length + " bytes)";
    }

    private static String candct(HttpData d) {
        return d.bodyContentType == null ? "" : d.bodyContentType.toLowerCase();
    }

    private static boolean looksJson(String contentType, byte[] body) {
        if (contentType.contains("json")) {
            return true;
        }
        int i = 0;
        while (i < body.length && Character.isWhitespace(body[i])) {
            i++;
        }
        return i < body.length && (body[i] == '{' || body[i] == '[');
    }
}
