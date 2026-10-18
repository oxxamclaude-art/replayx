package com.replayx.cli;

import com.replayx.ReplayX;
import com.replayx.diff.DiffEngine;
import com.replayx.match.MatchConfig;
import com.replayx.model.CassetteManifest;
import com.replayx.model.Exchange;
import com.replayx.model.HttpData;
import com.replayx.proxy.DepProxy;
import com.replayx.redact.RedactionEngine;
import com.replayx.replay.Replayer;
import com.replayx.store.CassetteStore;
import com.replayx.util.HttpProxyUtil;
import com.replayx.util.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(name = "diff",
        description = "Replay a cassette against a target and report behavioral differences.")
public class DiffCommand implements Callable<Integer> {
    @Parameters(index = "0", paramLabel = "<cassette>", description = "Cassette directory")
    Path cassette;

    @Option(names = "--target", required = true,
            description = "New service version to compare against, e.g. http://localhost:8081")
    String target;

    @Option(names = "--dep-listen",
            description = "host:port for the dependency virtualization proxy")
    String depListen;

    @Option(names = "--dep-passthrough",
            description = "Allow unmatched dependency calls to hit the live downstream target (opt-in, unsafe)")
    boolean depPassthrough;

    @Option(names = "--fail-on-regression",
            description = "Exit non-zero when breaking or behavioral differences are found")
    boolean failOnRegression;

    @Option(names = "--format", defaultValue = "text",
            description = "Output format: text or json (default: ${DEFAULT-VALUE})")
    String format;

    @Option(names = "--report",
            description = "Write the machine-readable JSON report to this file")
    Path report;

    @Option(names = "--ignore-json-fields", split = ",",
            description = "Comma-separated JSON dot-paths excluded from regression verdicts")
    List<String> ignoreJsonFields = new ArrayList<>();

    @Override
    public Integer call() throws Exception {
        CassetteStore store = new CassetteStore();
        List<String> problems = store.validate(cassette);
        if (!problems.isEmpty()) {
            problems.forEach(p -> System.err.println("error: " + p));
            return ReplayX.EXIT_ERROR;
        }
        CassetteManifest manifest = store.readManifest(cassette);
        List<Exchange> exchanges = store.readExchanges(cassette);
        List<Exchange> inbound = exchanges.stream().filter(e -> "inbound".equals(e.kind)).collect(Collectors.toList());
        List<Exchange> downstream = exchanges.stream().filter(e -> "downstream".equals(e.kind)).collect(Collectors.toList());

        DiffEngine.DiffConfig diffConfig = new DiffEngine.DiffConfig();
        diffConfig.ignoreJsonFields.addAll(ignoreJsonFields);

        DepProxy dep = null;
        if (depListen != null) {
            String[] parts = HttpProxyUtil.parseHostPort(depListen);
            dep = new DepProxy(parts[0], Integer.parseInt(parts[1]), downstream,
                    MatchConfig.defaults(), depPassthrough, manifest.downstreamTarget);
            dep.start();
        }

        List<Replayer.ReplayOutcome> outcomes;
        try {
            outcomes = Replayer.replay(inbound, URI.create(target), new RedactionEngine());
        } finally {
            if (dep != null) {
                dep.close();
            }
        }

        List<Map<String, Object>> diffEntries = new ArrayList<>();
        int regressions = 0;
        int errors = 0;
        for (Replayer.ReplayOutcome o : outcomes) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("exchangeId", o.original().id);
            entry.put("method", o.original().request.method);
            entry.put("path", o.original().request.path);
            if (o.failed()) {
                errors++;
                entry.put("error", o.error());
                entry.put("regression", false);
                diffEntries.add(entry);
                continue;
            }
            HttpData expected = o.original().response;
            DiffEngine.DiffResult r = DiffEngine.diff(expected, o.actual(), diffConfig);
            boolean isRegression = r.isRegression();
            if (isRegression) {
                regressions++;
            }
            entry.put("statusExpected", expected.status);
            entry.put("statusActual", o.actual().status);
            List<Map<String, Object>> changes = new ArrayList<>();
            for (DiffEngine.Change c : r.changes()) {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("path", c.path());
                cm.put("category", c.category().name());
                cm.put("expected", c.expected());
                cm.put("actual", c.actual());
                cm.put("detail", c.detail());
                changes.add(cm);
            }
            entry.put("changes", changes);
            entry.put("regression", isRegression);
            diffEntries.add(entry);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("replayed", outcomes.size());
        summary.put("withDiffs", diffEntries.stream().filter(e -> !((List<?>) e.getOrDefault("changes", List.of())).isEmpty() || e.containsKey("error")).count());
        summary.put("regressions", regressions);
        summary.put("errors", errors);

        Map<String, Object> reportModel = new LinkedHashMap<>();
        reportModel.put("session", manifest.sessionId);
        reportModel.put("target", HttpProxyUtil.stripUserinfo(target));
        reportModel.put("generatedAt", Instant.now().toString());
        reportModel.put("summary", summary);
        reportModel.put("diffs", diffEntries);

        String json = Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(reportModel);
        if (report != null) {
            Files.writeString(report.toAbsolutePath().normalize(), json, StandardCharsets.UTF_8);
            System.out.println("replayx: JSON report written to " + report.toAbsolutePath().normalize());
        }
        if ("json".equalsIgnoreCase(format) && report == null) {
            System.out.println(json);
        } else {
            printText(manifest.sessionId, outcomes.size(), regressions, diffEntries);
        }

        if (failOnRegression && regressions > 0) {
            return ReplayX.EXIT_REGRESSION;
        }
        return errors > 0 ? ReplayX.EXIT_ERROR : ReplayX.EXIT_OK;
    }

    private void printText(String session, int replayed, int regressions, List<Map<String, Object>> diffEntries) {
        System.out.println("Session: " + session);
        System.out.println("Requests replayed: " + replayed);
        System.out.println("Regressions: " + regressions);
        System.out.println();
        for (Map<String, Object> e : diffEntries) {
            if (e.containsKey("error")) {
                System.out.println(e.get("method") + " " + e.get("path"));
                System.out.println("  error: " + e.get("error"));
                System.out.println();
                continue;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> changes = (List<Map<String, Object>>) e.get("changes");
            if (changes.isEmpty()) {
                continue;
            }
            System.out.println(e.get("method") + " " + e.get("path"));
            for (Map<String, Object> c : changes) {
                System.out.println("  " + c.get("path") + ": " + c.get("expected") + " -> " + c.get("actual")
                        + " [" + c.get("category") + "]");
            }
            System.out.println();
        }
        System.out.println("Result: " + (regressions > 0 ? "FAILED" : "OK"));
    }
}
