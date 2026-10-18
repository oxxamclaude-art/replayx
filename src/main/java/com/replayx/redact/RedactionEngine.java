package com.replayx.redact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.replayx.model.Exchange;
import com.replayx.model.HttpData;
import com.replayx.util.Json;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes or masks secrets BEFORE anything is written to disk.
 * <ul>
 *   <li>Sensitive headers (Authorization, Cookie, ...) are dropped entirely.</li>
 *   <li>Sensitive JSON fields (password, token, apiKey, ...) are masked recursively.</li>
 *   <li>Bodies that cannot be inspected (binary or unparseable) are kept as-is
 *       but flagged with a warning.</li>
 * </ul>
 */
public class RedactionEngine {
    public static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "api-key", "x-auth-token", "x-access-token");

    public static final Set<String> SENSITIVE_FIELDS = Set.of(
            "password", "passwd", "secret", "client_secret", "clientsecret",
            "token", "access_token", "accesstoken", "refresh_token", "refreshtoken",
            "api_key", "apikey", "private_key", "privatekey", "ssn",
            "cardnumber", "creditcard", "cvv", "card_token", "cardtoken");

    private static final List<String> BINARY_PREFIXES = List.of(
            "image/", "video/", "audio/", "application/octet-stream",
            "application/pdf", "application/zip", "application/gzip", "font/");

    private final Set<String> sensitiveHeaders;
    private final Set<String> sensitiveFields;

    public RedactionEngine() {
        this(SENSITIVE_HEADERS, SENSITIVE_FIELDS);
    }

    public RedactionEngine(Set<String> sensitiveHeaders, Set<String> sensitiveFields) {
        this.sensitiveHeaders = lower(sensitiveHeaders);
        this.sensitiveFields = lower(sensitiveFields);
    }

    private static Set<String> lower(Set<String> in) {
        Set<String> out = new HashSet<>();
        for (String s : in) {
            out.add(s.toLowerCase());
        }
        return out;
    }

    /** Redacts request and response in place. Returns the warnings recorded. */
    public List<String> redact(Exchange ex) {
        List<String> warnings = new ArrayList<>();
        redactHeaders(ex.request.headers, warnings, "request");
        redactHeaders(ex.response.headers, warnings, "response");
        redactQuery(ex.request.query, warnings);
        redactBody(ex.request, warnings, "request");
        redactBody(ex.response, warnings, "response");
        ex.redacted = true;
        ex.redactionWarnings.addAll(warnings);
        return warnings;
    }

    private void redactHeaders(Map<String, List<String>> headers, List<String> warnings, String side) {
        for (String name : new ArrayList<>(headers.keySet())) {
            if (sensitiveHeaders.contains(name.toLowerCase())) {
                headers.remove(name);
                warnings.add("removed sensitive header '" + name + "' from " + side);
            }
        }
    }

    private void redactQuery(Map<String, List<String>> query, List<String> warnings) {
        for (Map.Entry<String, List<String>> e : query.entrySet()) {
            if (sensitiveFields.contains(e.getKey().toLowerCase())) {
                e.setValue(List.of(Json.REDACTED));
                warnings.add("masked sensitive query parameter '" + e.getKey() + "'");
            }
        }
    }

    private void redactBody(HttpData data, List<String> warnings, String side) {
        byte[] body = data.bodyBytes();
        if (body.length == 0) {
            return;
        }
        String ct = data.bodyContentType == null ? "" : data.bodyContentType.toLowerCase();
        if (isBinary(ct) || !isValidUtf8(body)) {
            warnings.add(side + " body not inspected for secrets (binary or non-UTF8); stored as-is");
            return;
        }
        boolean likelyJson = ct.contains("json") || looksLikeJson(body);
        if (!likelyJson) {
            return; // plain text: nothing structured to redact
        }
        try {
            JsonNode root = Json.MAPPER.readTree(body);
            if (redactJsonNode(root)) {
                byte[] redacted = Json.MAPPER.writeValueAsBytes(root);
                data.setBody(redacted, data.bodyContentType, data.bodyTruncated);
                warnings.add("masked sensitive JSON field(s) in " + side + " body");
            }
        } catch (Exception e) {
            warnings.add(side + " body looks like JSON but could not be parsed; stored uninspected");
        }
    }

    /** Returns true if anything was masked. */
    private boolean redactJsonNode(JsonNode node) {
        boolean changed = false;
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<String> names = new ArrayList<>();
            obj.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (sensitiveFields.contains(name.toLowerCase())) {
                    obj.put(name, Json.REDACTED);
                    changed = true;
                } else {
                    changed |= redactJsonNode(obj.get(name));
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                changed |= redactJsonNode(child);
            }
        }
        return changed;
    }

    private static boolean isBinary(String contentType) {
        for (String prefix : BINARY_PREFIXES) {
            if (contentType.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static boolean looksLikeJson(byte[] body) {
        int i = 0;
        while (i < body.length && Character.isWhitespace(body[i])) {
            i++;
        }
        return i < body.length && (body[i] == '{' || body[i] == '[');
    }
}
