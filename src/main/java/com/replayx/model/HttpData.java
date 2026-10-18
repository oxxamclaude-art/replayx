package com.replayx.model;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One side (request or response) of a recorded HTTP exchange.
 * Header names are stored lower-cased; bodies are base64-encoded so the
 * cassette stays binary-safe.
 */
public class HttpData {
    /** Request only; null for responses. */
    public String method;
    /** Response only; null for requests. */
    public Integer status;
    /** Raw path, e.g. /api/checkout */
    public String path;
    public Map<String, List<String>> query = new LinkedHashMap<>();
    public Map<String, List<String>> headers = new LinkedHashMap<>();
    /** Base64-encoded body; null when there is no body. */
    public String bodyBase64;
    public String bodyContentType;
    public boolean bodyTruncated;
    public long bodyBytes;

    public void setBody(byte[] body, String contentType, boolean truncated) {
        if (body == null || body.length == 0) {
            this.bodyBase64 = null;
            this.bodyBytes = 0;
        } else {
            this.bodyBase64 = Base64.getEncoder().encodeToString(body);
            this.bodyBytes = body.length;
        }
        this.bodyContentType = contentType;
        this.bodyTruncated = truncated;
    }

    public byte[] bodyBytes() {
        if (bodyBase64 == null) {
            return new byte[0];
        }
        return Base64.getDecoder().decode(bodyBase64);
    }
}
