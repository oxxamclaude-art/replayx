# ReplayX — Deterministic HTTP Regression Replay Engine

ReplayX is a developer CLI for recording HTTP interactions, replaying captured
traffic against a modified backend, virtualizing downstream dependencies, and
detecting API behavior regressions. Java 21, Maven, zero required runtime
services.

```
Client -> [replayx capture] -> service v1        (record)
replayx -> service v2 -> [replayx dep proxy]     (replay, virtualized deps)
diff: captured response vs replayed response     (report regressions)
```

## Quickstart

Prerequisites: JDK 21+, Maven 3.9+.

```bash
mvn -q package -DskipTests
java -jar target/replayx-0.1.0.jar --help
```

### 5-minute demo (no Docker needed)

```bash
bash demo/run-demo.sh
```

This starts a sample checkout backend (v1) plus a payment stub, captures a
checkout call through the proxy (with downstream recording and secret
redaction), then replays the cassette against backend v2 — which intentionally
regresses `paymentStatus` from `SUCCESS` to `PENDING` — and exits `2` with a
diff report at `demo/replayx-report.json`.

## CLI

```bash
# Capture (foreground; Ctrl-C finalizes the session)
replayx capture --target http://localhost:8080 --listen 127.0.0.1:8090 \
  --output ./cassettes/checkout

# Also record downstream dependency traffic
replayx capture --target http://localhost:8080 --listen 127.0.0.1:8090 \
  --dep-listen 127.0.0.1:8091 --dep-target http://payments.internal \
  --output ./cassettes/checkout
# -> point the service's dependency base URL at 127.0.0.1:8091

# Replay inbound requests against a new version, virtualizing downstream deps
replayx replay ./cassettes/checkout --target http://localhost:8081 \
  --dep-listen 127.0.0.1:8092

# Diff and fail on regressions (exit 2)
replayx diff ./cassettes/checkout --target http://localhost:8081 \
  --dep-listen 127.0.0.1:8092 --fail-on-regression

# Machine-readable report
replayx diff ./cassettes/checkout --target http://localhost:8081 \
  --format json --report ./build/replayx-report.json

# Inspect / validate
replayx inspect ./cassettes/checkout
replayx validate ./cassettes/checkout
```

Exit codes: `0` ok · `1` operational error · `2` regressions with
`--fail-on-regression`.

## How it works

**Capture.** A JDK `HttpServer`-based reverse proxy forwards traffic to the
target and records request/response pairs. An optional second proxy records
downstream dependency calls (point the service's dependency base URL at it).
The session manifest is written atomically with `complete: false` and flipped
to `true` on clean shutdown — an interrupted capture can never look valid.

**Cassette format** (`<session>/`):

```
manifest.json     # format version, session id, timestamps, target metadata, counts
exchanges.jsonl   # one JSON exchange per line; bodies base64-encoded (binary-safe)
```

**Request matching.** Method, normalized path, query params (order-insensitive),
selected headers, and body (byte-exact or JSON-structural with explicit
ignore rules). Dependency fixtures are consumed in recorded order per
method+path; unmatched dependency calls fail closed with a 502 diagnostic —
never silently forwarded to a live service (opt-in `--dep-passthrough`
exists for safe environments only).

**Diff.** Status changes, missing fields, and type changes are *breaking*;
JSON value/structure changes are *behavioral*; ignored fields and compared
headers are *informational*. `--fail-on-regression` fails on breaking and
behavioral differences.

**Redaction (before persistence).** `Authorization`/`Cookie`/`Set-Cookie` and
similar headers are dropped; secret JSON fields (`password`, `token`,
`apiKey`, `secret`, ...) are masked recursively, including inside arrays and
nested objects; sensitive query params are masked. Binary or unparseable
bodies are kept but flagged with a warning. Cassettes should still be treated
as potentially sensitive artifacts.

## Docker demo

```bash
cd demo && docker compose up -d
# capture v1 traffic (host):
java -jar ../target/replayx-0.1.0.jar capture \
  --target http://127.0.0.1:8081 --listen 127.0.0.1:8090 \
  --dep-listen 127.0.0.1:8091 --dep-target http://127.0.0.1:8082 \
  --output ./cassettes/checkout
curl -s -X POST http://127.0.0.1:8090/api/checkout \
  -H 'Content-Type: application/json' -d '{"item":"book","amount":100}'
# Ctrl-C, then diff against v2:
java -jar ../target/replayx-0.1.0.jar diff ./cassettes/checkout \
  --target http://127.0.0.1:8083 --dep-listen 127.0.0.1:8092 --fail-on-regression
```

## Development

```bash
mvn package          # build + run all tests
mvn -q -DskipTests package   # build only
```

Tests: JUnit 5 unit tests for matcher/diff/redaction/cassette, plus an
end-to-end integration test (capture → replay → regression detection →
fail-closed unmatched dependency → CLI exit codes).

CI: `.github/workflows/ci.yml` builds and tests on every push/PR with JDK 21.

## Layout

```
src/main/java/com/replayx/
  ReplayX.java            CLI entry point
  cli/                    capture|replay|diff|inspect|validate commands
  proxy/                  CaptureProxy, DepProxy (dependency virtualization)
  replay/                 Replayer
  store/                  CassetteStore (versioned format, atomic writes)
  match/                  RequestMatcher + MatchConfig
  diff/                   DiffEngine + JsonCompare
  redact/                 RedactionEngine
  model/                  CassetteManifest, Exchange, HttpData
  util/                   Json, HttpProxyUtil
demo/                     sample backends, Dockerfile, compose, run-demo.sh
```

## Security notes

- Proxies bind loopback by default; bind `0.0.0.0` only deliberately.
- Replay fails closed on unmatched virtualized dependencies.
- Cassette files may contain personal/confidential data: redact, limit
  retention, and don't commit them to shared repos.

## License

MIT — see `LICENSE` (to be added).
