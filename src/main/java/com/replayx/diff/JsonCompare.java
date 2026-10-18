package com.replayx.diff;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structural JSON comparison producing path-addressed differences.
 * Shared by the request matcher (equality check) and the diff engine
 * (categorized reporting).
 */
public final class JsonCompare {
    private JsonCompare() {}

    public enum Kind {
        /** Present in expected, missing in actual. */
        MISSING,
        /** Present in actual, missing in expected. */
        ADDED,
        /** Same path, different JSON type. */
        TYPE_CHANGED,
        /** Same path and type, different value. */
        VALUE_CHANGED,
        /** Path is on the ignore list. */
        IGNORED
    }

    public record JsonDiff(String path, Kind kind, String expected, String actual, String detail) {}

    public static List<JsonDiff> compare(JsonNode expected, JsonNode actual, Set<String> ignorePaths) {
        List<JsonDiff> out = new ArrayList<>();
        compareInto("", expected, actual, ignorePaths, out);
        return out;
    }

    /** True when the two trees are equal after applying the ignore list. */
    public static boolean equal(JsonNode expected, JsonNode actual, Set<String> ignorePaths) {
        for (JsonDiff d : compare(expected, actual, ignorePaths)) {
            if (d.kind() != Kind.IGNORED) {
                return false;
            }
        }
        return true;
    }

    private static void compareInto(String path, JsonNode expected, JsonNode actual,
                                    Set<String> ignorePaths, List<JsonDiff> out) {
        if (expected == null && actual == null) {
            return;
        }
        if (expected == null) {
            out.add(new JsonDiff(path, Kind.ADDED, null, text(actual), "field added"));
            return;
        }
        if (actual == null || actual.isMissingNode()) {
            out.add(new JsonDiff(path, Kind.MISSING, text(expected), null, "required field missing"));
            return;
        }
        if (ignorePaths.contains(path)) {
            out.add(new JsonDiff(path, Kind.IGNORED, text(expected), text(actual), "explicitly ignored"));
            return;
        }
        if (expected.isObject() && actual.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = expected.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                String child = path.isEmpty() ? f.getKey() : path + "." + f.getKey();
                compareInto(child, f.getValue(), actual.get(f.getKey()), ignorePaths, out);
            }
            Iterator<String> actualFields = actual.fieldNames();
            while (actualFields.hasNext()) {
                String name = actualFields.next();
                if (!expected.has(name)) {
                    String child = path.isEmpty() ? name : path + "." + name;
                    if (!ignorePaths.contains(child)) {
                        out.add(new JsonDiff(child, Kind.ADDED, null, text(actual.get(name)), "field added"));
                    }
                }
            }
            return;
        }
        if (expected.isArray() && actual.isArray()) {
            if (expected.size() != actual.size()) {
                out.add(new JsonDiff(path, Kind.VALUE_CHANGED, "array[" + expected.size() + "]",
                        "array[" + actual.size() + "]", "array length changed"));
            }
            int n = Math.min(expected.size(), actual.size());
            for (int i = 0; i < n; i++) {
                compareInto(path + "[" + i + "]", expected.get(i), actual.get(i), ignorePaths, out);
            }
            return;
        }
        if (expected.getNodeType() != actual.getNodeType()) {
            out.add(new JsonDiff(path, Kind.TYPE_CHANGED, text(expected), text(actual),
                    "type changed from " + expected.getNodeType() + " to " + actual.getNodeType()));
            return;
        }
        if (!expected.equals(actual)) {
            out.add(new JsonDiff(path, Kind.VALUE_CHANGED, text(expected), text(actual), "value changed"));
        }
    }

    private static String text(JsonNode n) {
        if (n == null || n.isNull()) {
            return "null";
        }
        if (n.isTextual()) {
            return "\"" + n.asText() + "\"";
        }
        return n.toString();
    }
}
