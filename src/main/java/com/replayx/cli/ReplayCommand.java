package com.replayx.cli;

import com.replayx.match.MatchConfig;
import com.replayx.model.CassetteManifest;
import com.replayx.model.Exchange;
import com.replayx.proxy.DepProxy;
import com.replayx.redact.RedactionEngine;
import com.replayx.replay.Replayer;
import com.replayx.store.CassetteStore;
import com.replayx.util.HttpProxyUtil;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(name = "replay",
        description = "Replay a cassette's inbound requests against a new target, "
                + "virtualizing recorded downstream dependencies.")
public class ReplayCommand implements Callable<Integer> {
    @Parameters(index = "0", paramLabel = "<cassette>", description = "Cassette directory")
    Path cassette;

    @Option(names = "--target", required = true,
            description = "New service version to replay against, e.g. http://localhost:8081")
    String target;

    @Option(names = "--dep-listen",
            description = "host:port for the dependency virtualization proxy")
    String depListen;

    @Option(names = "--dep-passthrough",
            description = "Allow unmatched dependency calls to hit the live downstream target (opt-in, unsafe)")
    boolean depPassthrough;

    @Option(names = "--output",
            description = "Write the replay session (with original responses) to this directory")
    Path output;

    @Override
    public Integer call() throws Exception {
        CassetteStore store = new CassetteStore();
        List<String> problems = store.validate(cassette);
        if (!problems.isEmpty()) {
            problems.forEach(p -> System.err.println("error: " + p));
            return 1;
        }
        CassetteManifest manifest = store.readManifest(cassette);
        List<Exchange> exchanges = store.readExchanges(cassette);
        List<Exchange> inbound = exchanges.stream().filter(e -> "inbound".equals(e.kind)).collect(Collectors.toList());
        List<Exchange> downstream = exchanges.stream().filter(e -> "downstream".equals(e.kind)).collect(Collectors.toList());

        DepProxy dep = null;
        if (depListen != null) {
            String[] parts = HttpProxyUtil.parseHostPort(depListen);
            dep = new DepProxy(parts[0], Integer.parseInt(parts[1]), downstream,
                    MatchConfig.defaults(), depPassthrough, manifest.downstreamTarget);
            dep.start();
            System.out.println("replayx: virtualizing " + downstream.size() + " downstream fixture(s) on "
                    + parts[0] + ":" + dep.port() + (depPassthrough ? " (passthrough ENABLED)" : " (fail-closed)"));
        } else if (!downstream.isEmpty()) {
            System.out.println("replayx: note: cassette has " + downstream.size()
                    + " downstream fixture(s) but no --dep-listen was given; they will not be virtualized");
        }

        List<Replayer.ReplayOutcome> outcomes;
        try {
            outcomes = Replayer.replay(inbound, URI.create(target), new RedactionEngine());
        } finally {
            if (dep != null) {
                dep.close();
            }
        }

        long failed = outcomes.stream().filter(Replayer.ReplayOutcome::failed).count();
        System.out.println("replayx: replayed " + outcomes.size() + " request(s) against "
                + HttpProxyUtil.stripUserinfo(target) + ", " + failed + " failed");

        if (output != null) {
            Path outDir = output.toAbsolutePath().normalize();
            store.createSession(outDir, outDir.getFileName().toString(), target, null);
            for (Replayer.ReplayOutcome o : outcomes) {
                Exchange ex = new Exchange();
                ex.id = o.original().id;
                ex.kind = "inbound";
                ex.sequence = o.original().sequence;
                ex.timestamp = o.original().timestamp;
                ex.request = o.original().request;
                ex.response = o.actual();
                ex.expectedResponse = o.original().response;
                ex.durationMs = o.original().durationMs;
                ex.redacted = true;
                store.appendExchange(outDir, ex);
            }
            store.finalizeSession(outDir);
            final String replayOf = manifest.sessionId;
            store.updateManifest(outDir, m -> m.replayOf = replayOf);
            System.out.println("replayx: replay session written to " + outDir);
        }
        return failed > 0 ? 1 : 0;
    }
}
