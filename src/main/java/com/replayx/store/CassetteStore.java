package com.replayx.store;

import com.replayx.model.CassetteManifest;
import com.replayx.model.Exchange;
import com.replayx.util.HttpProxyUtil;
import com.replayx.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Reads and writes versioned cassette directories:
 *
 * <pre>
 *   &lt;session&gt;/
 *     manifest.json     (written atomically; complete=false until clean shutdown)
 *     exchanges.jsonl   (one JSON exchange per line, appended during capture)
 * </pre>
 */
public class CassetteStore {
    public static final int SUPPORTED_FORMAT_VERSION = CassetteManifest.FORMAT_VERSION;

    private static final String MANIFEST = "manifest.json";
    private static final String EXCHANGES = "exchanges.jsonl";

    /** Creates the session dir and an initial (incomplete) manifest, atomically. */
    public synchronized Path createSession(Path dir, String sessionId, String targetUrl, String downstreamTargetUrl)
            throws IOException {
        Files.createDirectories(dir);
        CassetteManifest m = new CassetteManifest();
        m.sessionId = sessionId;
        m.capturedAt = Instant.now().toString();
        m.complete = false;
        m.target.put("url", HttpProxyUtil.stripUserinfo(targetUrl));
        if (downstreamTargetUrl != null) {
            m.downstreamTarget = HttpProxyUtil.stripUserinfo(downstreamTargetUrl);
        }
        writeManifestAtomic(dir, m);
        // Truncate any stale exchanges file so a resumed dir starts clean.
        Files.write(dir.resolve(EXCHANGES), new byte[0],
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return dir;
    }

    /** Appends one exchange record (already redacted by the caller). */
    public synchronized void appendExchange(Path dir, Exchange ex) throws IOException {
        String line = Json.MAPPER.writeValueAsString(ex) + "\n";
        Files.write(dir.resolve(EXCHANGES), line.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
    }

    /** Marks the session complete, atomically. */
    public synchronized void finalizeSession(Path dir) throws IOException {
        CassetteManifest m = readManifest(dir);
        if (m == null) {
            throw new IOException("no manifest found in " + dir);
        }
        List<Exchange> exchanges = readExchanges(dir);
        m.inboundCount = (int) exchanges.stream().filter(e -> "inbound".equals(e.kind)).count();
        m.downstreamCount = (int) exchanges.stream().filter(e -> "downstream".equals(e.kind)).count();
        m.complete = true;
        m.completedAt = Instant.now().toString();
        writeManifestAtomic(dir, m);
    }

    public CassetteManifest readManifest(Path dir) throws IOException {
        Path p = dir.resolve(MANIFEST);
        if (!Files.isRegularFile(p)) {
            return null;
        }
        return Json.MAPPER.readValue(Files.readString(p, StandardCharsets.UTF_8), CassetteManifest.class);
    }

    public List<Exchange> readExchanges(Path dir) throws IOException {
        Path p = dir.resolve(EXCHANGES);
        List<Exchange> out = new ArrayList<>();
        if (!Files.isRegularFile(p)) {
            return out;
        }
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            out.add(Json.MAPPER.readValue(line, Exchange.class));
        }
        return out;
    }

    /**
     * Validates a cassette directory. Returns a list of human-readable
     * problems; empty means valid.
     */
    public List<String> validate(Path dir) {
        List<String> problems = new ArrayList<>();
        CassetteManifest m;
        try {
            m = readManifest(dir);
        } catch (Exception e) {
            return List.of("manifest.json is corrupt: " + e.getMessage());
        }
        if (m == null) {
            return List.of("manifest.json is missing in " + dir);
        }
        if (m.formatVersion > SUPPORTED_FORMAT_VERSION) {
            problems.add("unsupported cassette format version " + m.formatVersion
                    + " (this build supports up to " + SUPPORTED_FORMAT_VERSION + ")");
        }
        if (m.formatVersion < 1) {
            problems.add("invalid cassette format version " + m.formatVersion);
        }
        if (!m.complete) {
            problems.add("incomplete session: manifest is not marked complete "
                    + "(capture was interrupted or never finalized)");
        }
        Path exchanges = dir.resolve(EXCHANGES);
        if (!Files.isRegularFile(exchanges)) {
            problems.add("exchanges.jsonl is missing");
            return problems;
        }
        try {
            List<String> lines = Files.readAllLines(exchanges, StandardCharsets.UTF_8);
            int n = 0;
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                n++;
                Exchange ex;
                try {
                    ex = Json.MAPPER.readValue(line, Exchange.class);
                } catch (Exception e) {
                    problems.add("exchanges.jsonl line " + n + " is corrupt: " + e.getMessage());
                    continue;
                }
                if (ex.id == null || ex.id.isBlank()) {
                    problems.add("exchanges.jsonl line " + n + ": missing exchange id");
                }
                if (!"inbound".equals(ex.kind) && !"downstream".equals(ex.kind)) {
                    problems.add("exchanges.jsonl line " + n + ": unknown kind '" + ex.kind + "'");
                }
                if (ex.request == null || ex.request.method == null) {
                    problems.add("exchanges.jsonl line " + n + ": missing request method");
                }
                for (HttpBodyRef ref : List.of(
                        new HttpBodyRef("request", ex.request == null ? null : ex.request.bodyBase64),
                        new HttpBodyRef("response", ex.response == null ? null : ex.response.bodyBase64))) {
                    if (ref.base64() != null) {
                        try {
                            Base64.getDecoder().decode(ref.base64());
                        } catch (IllegalArgumentException e) {
                            problems.add("exchanges.jsonl line " + n + ": " + ref.side()
                                    + " body is not valid base64");
                        }
                    }
                }
            }
            if (n == 0) {
                problems.add("exchanges.jsonl contains no exchanges");
            }
        } catch (IOException e) {
            problems.add("could not read exchanges.jsonl: " + e.getMessage());
        }
        return problems;
    }

    private record HttpBodyRef(String side, String base64) {}

    /** Atomically applies a mutation to the session manifest. */
    public synchronized void updateManifest(Path dir, java.util.function.Consumer<CassetteManifest> mutator)
            throws IOException {
        CassetteManifest m = readManifest(dir);
        if (m == null) {
            throw new IOException("no manifest found in " + dir);
        }
        mutator.accept(m);
        writeManifestAtomic(dir, m);
    }

    private static void writeManifestAtomic(Path dir, CassetteManifest m) throws IOException {
        Path tmp = dir.resolve(MANIFEST + ".tmp");
        String json = Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(m);
        Files.writeString(tmp, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
        Files.move(tmp, dir.resolve(MANIFEST),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
