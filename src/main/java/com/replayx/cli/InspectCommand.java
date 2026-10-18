package com.replayx.cli;

import com.replayx.model.CassetteManifest;
import com.replayx.model.Exchange;
import com.replayx.store.CassetteStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "inspect", description = "Print a human-readable summary of a cassette.")
public class InspectCommand implements Callable<Integer> {
    @Parameters(index = "0", paramLabel = "<cassette>", description = "Cassette directory")
    Path cassette;

    @Override
    public Integer call() throws Exception {
        CassetteStore store = new CassetteStore();
        List<String> problems = store.validate(cassette);
        CassetteManifest m = store.readManifest(cassette);
        if (m == null) {
            System.err.println("error: no manifest found in " + cassette);
            return 1;
        }
        System.out.println("Session:    " + m.sessionId + (m.complete ? " (complete)" : " (INCOMPLETE)"));
        System.out.println("Captured:   " + m.capturedAt);
        System.out.println("Target:     " + m.target.getOrDefault("url", "?"));
        if (m.downstreamTarget != null) {
            System.out.println("Downstream: " + m.downstreamTarget);
        }
        System.out.println("Redaction:  " + m.redactionPolicy);
        if (!problems.isEmpty()) {
            System.out.println("Problems:");
            problems.forEach(p -> System.out.println("  ! " + p));
        }
        List<Exchange> exchanges = store.readExchanges(cassette);
        long inbound = exchanges.stream().filter(e -> "inbound".equals(e.kind)).count();
        long downstream = exchanges.stream().filter(e -> "downstream".equals(e.kind)).count();
        System.out.println("Exchanges:  " + exchanges.size() + " (" + inbound + " inbound, " + downstream + " downstream)");
        System.out.println();
        for (Exchange e : exchanges) {
            String status = e.response != null && e.response.status != null ? String.valueOf(e.response.status) : "?";
            System.out.printf("  %-10s %-10s %-6s %-40s -> %-4s (%d ms)%n",
                    e.id, e.kind, e.request.method, e.request.path, status, e.durationMs);
            if (!e.redactionWarnings.isEmpty()) {
                e.redactionWarnings.forEach(w -> System.out.println("               [redaction] " + w));
            }
        }
        return 0;
    }
}
