package com.replayx.model;

import java.util.ArrayList;
import java.util.List;

/** A single recorded request/response pair. */
public class Exchange {
    /** Stable id, e.g. ex-000001 */
    public String id;
    /** "inbound" (client -> service) or "downstream" (service -> dependency). */
    public String kind;
    public int sequence;
    /** ISO-8601 capture timestamp. */
    public String timestamp;
    public HttpData request = new HttpData();
    public HttpData response = new HttpData();
    /**
     * For replay sessions: the original captured response, so a replay
     * cassette stays self-describing for later diffs.
     */
    public HttpData expectedResponse;
    public long durationMs;
    public boolean redacted;
    public List<String> redactionWarnings = new ArrayList<>();
}
