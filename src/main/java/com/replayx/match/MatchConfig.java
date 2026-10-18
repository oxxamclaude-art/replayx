package com.replayx.match;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Configurable request-matching rules. Normalization is explicit: only what is listed here is relaxed. */
public class MatchConfig {
    /** Compare query parameters without regard to order. */
    public boolean ignoreQueryOrder = true;
    /** Request headers compared by value (lowercase names). */
    public Set<String> matchHeaders = new LinkedHashSet<>(List.of("content-type"));
    /** Dot-paths (e.g. "meta.requestId") ignored in JSON body comparison. */
    public Set<String> ignoreJsonFields = new LinkedHashSet<>();
    /** When true, JSON bodies are compared structurally; otherwise byte-exact. */
    public boolean jsonStructuralBody = true;

    public static MatchConfig defaults() {
        return new MatchConfig();
    }
}
