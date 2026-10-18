package com.replayx.util;

import com.sun.net.httpserver.Headers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small HTTP helpers shared by the capture and replay proxies. */
public final class HttpProxyUtil {
    private HttpProxyUtil() {}

    /** Bounded body read. */
    public record BodyRead(byte[] bytes, boolean truncated) {}

    public static BodyRead readBody(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        boolean truncated = false;
        int n;
        while ((n = in.read(buf)) != -1) {
            if (total + n > maxBytes) {
                int allowed = (int) (maxBytes - total);
                if (allowed > 0) {
                    bos.write(buf, 0, allowed);
                }
                truncated = true;
                break;
            }
            bos.write(buf, 0, n);
            total += n;
        }
        return new BodyRead(bos.toByteArray(), truncated);
    }

    /** Copy request/response headers into a lowercase-keyed multimap. */
    public static Map<String, List<String>> readHeaders(Headers headers) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            out.put(e.getKey().toLowerCase(), new ArrayList<>(e.getValue()));
        }
        return out;
    }

    /** Copy java.net.http headers into a lowercase-keyed multimap. */
    public static Map<String, List<String>> readHeaders(java.net.http.HttpHeaders headers) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : headers.map().entrySet()) {
            out.put(e.getKey().toLowerCase(), new ArrayList<>(e.getValue()));
        }
        return out;
    }

    /** Parse a raw query string into an ordered multimap (decoded). */
    public static Map<String, List<String>> parseQuery(String rawQuery) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            out.computeIfAbsent(decode(k), x -> new ArrayList<>()).add(decode(v));
        }
        return out;
    }

    /** Encode a query multimap back to a raw query string. */
    public static String encodeQuery(Map<String, List<String>> query) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : query.entrySet()) {
            for (String v : e.getValue()) {
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(encode(e.getKey())).append('=').append(encode(v));
            }
        }
        return sb.toString();
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Parse "host:port" into parts. */
    public static String[] parseHostPort(String s) {
        int i = s.lastIndexOf(':');
        if (i < 0) {
            throw new IllegalArgumentException("expected host:port, got: " + s);
        }
        return new String[]{s.substring(0, i), s.substring(i + 1)};
    }

    /** Join a target base URL and a raw path without doubling slashes. */
    public static URI joinTarget(String targetBase, String rawPath, String rawQuery) {
        String base = targetBase.endsWith("/") ? targetBase.substring(0, targetBase.length() - 1) : targetBase;
        String q = (rawQuery == null || rawQuery.isEmpty()) ? "" : "?" + rawQuery;
        return URI.create(base + rawPath + q);
    }

    /** Remove userinfo (credentials) from a URL string. */
    public static String stripUserinfo(String url) {
        try {
            URI u = URI.create(url);
            if (u.getUserInfo() == null) {
                return url;
            }
            return new URI(u.getScheme(), null, u.getHost(), u.getPort(), u.getPath(), u.getQuery(), u.getFragment()).toString();
        } catch (Exception e) {
            return url;
        }
    }

    public static String firstHeader(Map<String, List<String>> headers, String name) {
        List<String> v = headers.get(name.toLowerCase());
        return (v == null || v.isEmpty()) ? null : v.get(0);
    }
}
