package com.replayx.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Session metadata for a cassette directory. Written atomically at session
 * start with {@code complete=false}; rewritten with {@code complete=true}
 * on clean shutdown. A manifest that is missing or not marked complete must
 * never be mistaken for a valid finished capture.
 */
public class CassetteManifest {
    public static final int FORMAT_VERSION = 1;

    public int formatVersion = FORMAT_VERSION;
    public String replayxVersion = "0.1.0";
    public String sessionId;
    /** ISO-8601 */
    public String capturedAt;
    /** ISO-8601, set on clean shutdown */
    public String completedAt;
    public boolean complete = false;
    /** Target metadata with sensitive values (userinfo) excluded. */
    public Map<String, String> target = new LinkedHashMap<>();
    /** Original downstream target URL (userinfo excluded), if dependency capture was used. */
    public String downstreamTarget;
    public String redactionPolicy = "default-v1";
    public int inboundCount;
    public int downstreamCount;
    public List<String> warnings = new ArrayList<>();
    /** For replay sessions: the source cassette session id. */
    public String replayOf;
}
