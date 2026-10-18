package com.replayx.diff;

import com.fasterxml.jackson.databind.JsonNode;
import com.replayx.model.HttpData;
import com.replayx.util.HttpProxyUtil;
import com.replayx.util.Json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Compares an original captured response with a replayed one.
 *
 * <p>Categories:
 * <ul>
 *   <li>BREAKING — status changed, required field missing, type changed.</li>
 *   <li>BEHAVIORAL — JSON values or structure changed.</li>
 *   <li>INFORMATIONAL — ignored fields, added fields, compared headers.</li>
 * </ul>
 */
public final class DiffEngine {
    private DiffEngine() {}

    public enum Category {
        BREAKING, BEHAVIORAL, INFORMATIONAL
    }

    public record Change(String path, Category category, String expected, String actual, String detail) {}

    public record DiffResult(List<Change> changes) {
        public boolean hasBreaking() {
            return changes.stream().anyMatch(c -> c.category() == Category.BREAKING);
        }

        public boolean hasBehavioral() {
            return changes.stream().anyMatch(c -> c.category() == Category.BEHAVIORAL);
        }

        /** True when --fail-on-regression should fail the run. */
        public boolean isRegression() {
            return hasBreaking() || hasBehavioral();
        }
    }

    public static class DiffConfig {
        /** Dot-paths (e.g. "meta.requestId") excluded from regression verdicts. */
        public Set<String> ignoreJsonFields = new LinkedHashSet<>();
        /** Response headers compared (differences are informational). */
        public Set<String> compareHeaders = new LinkedHashSet<>(Set.of("content-type"));
    }

    public static DiffResult diff(HttpData expected, HttpData actual, DiffConfig cfg) {
        List<Change> changes = new ArrayList<>();

        if (!Objects.equals(expected.status, actual.status)) {
            changes.add(new Change("status", Category.BREAKING,
                    str(expected.status), str(actual.status), "HTTP status changed"));
        }

        for (String header : cfg.compareHeaders) {
            String e = HttpProxyUtil.firstHeader(expected.headers, header);
            String a = HttpProxyUtil.firstHeader(actual.headers, header);
            if (!Objects.equals(e, a)) {
                changes.add(new Change("header:" + header.toLowerCase(), Category.INFORMATIONAL,
                        e, a, "response header changed"));
            }
        }

        byte[] eb = expected.bodyBytes();
        byte[] ab = actual.bodyBytes();
        if (eb.length == 0 && ab.length == 0) {
            // no body on either side
        } else if (looksJson(expected) && looksJson(actual)) {
            try {
                JsonNode en = Json.MAPPER.readTree(eb.length == 0 ? new byte[]{'{', '}'} : eb);
                JsonNode an = Json.MAPPER.readTree(ab.length == 0 ? new byte[]{'{', '}'} : ab);
                for (JsonCompare.JsonDiff d : JsonCompare.compare(en, an, cfg.ignoreJsonFields)) {
                    Category cat = switch (d.kind()) {
                        case MISSING, TYPE_CHANGED -> Category.BREAKING;
                        case VALUE_CHANGED -> Category.BEHAVIORAL;
                        case ADDED, IGNORED -> Category.INFORMATIONAL;
                    };
                    changes.add(new Change("$." + d.path(), cat, d.expected(), d.actual(), d.detail()));
                }
            } catch (Exception e) {
                if (!Arrays.equals(eb, ab)) {
                    changes.add(new Change("body", Category.BEHAVIORAL,
                            eb.length + " bytes", ab.length + " bytes", "body bytes differ (unparseable JSON)"));
                }
            }
        } else if (!Arrays.equals(eb, ab)) {
            changes.add(new Change("body", Category.BEHAVIORAL,
                    eb.length + " bytes", ab.length + " bytes", "body bytes differ"));
        }

        return new DiffResult(List.copyOf(changes));
    }

    private static boolean looksJson(HttpData d) {
        String ct = d.bodyContentType == null ? "" : d.bodyContentType.toLowerCase();
        if (ct.contains("json")) {
            return true;
        }
        byte[] b = d.bodyBytes();
        int i = 0;
        while (i < b.length && Character.isWhitespace(b[i])) {
            i++;
        }
        return i < b.length && (b[i] == '{' || b[i] == '[');
    }

    private static String str(Object o) {
        return o == null ? "null" : o.toString();
    }
}
