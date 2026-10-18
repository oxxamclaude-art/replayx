package com.replayx.cli;

import com.replayx.model.CassetteManifest;
import com.replayx.proxy.CaptureProxy;
import com.replayx.redact.RedactionEngine;
import com.replayx.store.CassetteStore;
import com.replayx.util.HttpProxyUtil;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

@Command(name = "capture",
        description = "Capture HTTP traffic through a local reverse proxy. Runs in the foreground until Ctrl-C.")
public class CaptureCommand implements Callable<Integer> {
    @Option(names = "--target", required = true,
            description = "Service to forward inbound traffic to, e.g. http://localhost:8080")
    String target;

    @Option(names = "--listen", defaultValue = "127.0.0.1:8090",
            description = "host:port the capture proxy listens on (default: ${DEFAULT-VALUE})")
    String listen;

    @Option(names = "--output", required = true,
            description = "Cassette directory to write (created if missing)")
    Path output;

    @Option(names = "--dep-listen",
            description = "host:port for an extra proxy that records downstream dependency traffic")
    String depListen;

    @Option(names = "--dep-target",
            description = "Real downstream URL that --dep-listen forwards to (required with --dep-listen)")
    String depTarget;

    @Override
    public Integer call() throws Exception {
        if (depListen != null && depTarget == null) {
            System.err.println("error: --dep-target is required when --dep-listen is set");
            return 1;
        }
        URI targetUri = URI.create(target);
        Path dir = output.toAbsolutePath().normalize();
        CassetteStore store = new CassetteStore();
        RedactionEngine redactor = new RedactionEngine();

        store.createSession(dir, dir.getFileName().toString(), target, depTarget);

        String[] listenParts = HttpProxyUtil.parseHostPort(listen);
        CaptureProxy inbound = new CaptureProxy(listenParts[0], Integer.parseInt(listenParts[1]),
                targetUri, store, dir, redactor, "inbound");
        CaptureProxy downstream = null;
        if (depListen != null) {
            String[] dp = HttpProxyUtil.parseHostPort(depListen);
            downstream = new CaptureProxy(dp[0], Integer.parseInt(dp[1]),
                    URI.create(depTarget), store, dir, redactor, "downstream");
        }
        final CaptureProxy depFinal = downstream;

        inbound.start();
        System.out.println("replayx: capturing inbound " + listenParts[0] + ":" + inbound.port()
                + " -> " + HttpProxyUtil.stripUserinfo(target));
        if (depFinal != null) {
            depFinal.start();
            String[] dp = HttpProxyUtil.parseHostPort(depListen);
            System.out.println("replayx: capturing downstream " + dp[0] + ":" + depFinal.port()
                    + " -> " + HttpProxyUtil.stripUserinfo(depTarget));
            System.out.println("replayx: point the service's dependency base URL at the downstream proxy");
        }
        System.out.println("replayx: writing to " + dir + " -- press Ctrl-C to stop and finalize");

        // Finalization must happen inside the shutdown hook: the JVM halts as
        // soon as hooks complete, so code after latch.await() would never run.
        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.out.println("\nreplayx: stopping capture...");
                if (depFinal != null) {
                    depFinal.close();
                }
                inbound.close();
                store.finalizeSession(dir);
                CassetteManifest m = store.readManifest(dir);
                System.out.println("replayx: session finalized: " + m.inboundCount + " inbound, "
                        + m.downstreamCount + " downstream exchanges -> " + dir);
            } catch (Exception e) {
                System.err.println("replayx: error during shutdown: " + e.getMessage());
            } finally {
                done.countDown();
            }
        }, "replayx-shutdown"));
        done.await();
        return 0;
    }
}
