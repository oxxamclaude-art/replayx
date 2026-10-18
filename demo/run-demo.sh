#!/usr/bin/env bash
# End-to-end ReplayX demo (host mode, no Docker required):
#   1. start payment stub + backend v1
#   2. capture traffic (incl. downstream dependency calls + secret redaction)
#   3. start backend v2 with an intentional regression (paymentStatus SUCCESS -> PENDING)
#   4. replay + diff -> expect exit code 2 and the regression in the report
set -euo pipefail
cd "$(dirname "$0")/.."

JAR="target/replayx-0.1.0.jar"
CASSETTE="demo/cassettes/checkout"

# Prefer IPv4 for the JVM: some sandboxed environments block Java IPv6 TCP.
export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true ${JAVA_TOOL_OPTIONS:-}"

if [ ! -f "$JAR" ]; then
  echo "==> building ReplayX (this downloads Maven dependencies on first run)"
  mvn -q package -DskipTests
fi
command -v python3 >/dev/null || { echo "python3 is required for the demo backends"; exit 1; }
command -v curl >/dev/null || { echo "curl is required for the demo"; exit 1; }

rm -rf "$CASSETTE"

PIDS=()
cleanup() { kill "${PIDS[@]}" 2>/dev/null || true; }
trap cleanup EXIT

wait_for() {
  for _ in $(seq 1 60); do
    (echo > /dev/tcp/127.0.0.1/$1) 2>/dev/null && return 0
    sleep 0.2
  done
  echo "timeout waiting for 127.0.0.1:$1" >&2
  return 1
}

echo "==> starting payment stub (:8082) and backend v1 (:8081)"
python3 demo/payment-stub.py --port 8082 & PIDS+=($!)
# v1 calls the downstream payment service through the capture proxy (:8091)
python3 demo/backend.py --mode v1 --port 8081 --payment-url http://127.0.0.1:8091 & PIDS+=($!)
wait_for 8082 && wait_for 8081

echo "==> capturing traffic (Ctrl-C equivalent sent automatically below)"
java -jar "$JAR" capture \
  --target http://127.0.0.1:8081 \
  --listen 127.0.0.1:8090 \
  --dep-listen 127.0.0.1:8091 \
  --dep-target http://127.0.0.1:8082 \
  --output "$CASSETTE" & PIDS+=($!)
CAPTURE_PID=$!
wait_for 8090

echo "==> sending sample checkout traffic (with secrets, to prove redaction)"
curl -s -X POST http://127.0.0.1:8090/api/checkout \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer demo-secret-token' \
  -d '{"item":"book","amount":100,"cardToken":"tok_demo_123"}'
echo

echo "==> stopping capture"
# SIGINT first (like Ctrl-C); escalate to SIGTERM if the process lingers.
kill -INT "$CAPTURE_PID" 2>/dev/null || true
for _ in $(seq 1 25); do
  kill -0 "$CAPTURE_PID" 2>/dev/null || break
  sleep 0.2
done
if kill -0 "$CAPTURE_PID" 2>/dev/null; then
  kill -TERM "$CAPTURE_PID" 2>/dev/null || true
fi
wait "$CAPTURE_PID" || true

echo "==> validating cassette"
java -jar "$JAR" validate "$CASSETTE"

if grep -q "demo-secret-token\|tok_demo_123" "$CASSETTE/exchanges.jsonl"; then
  echo "DEMO FAILED: secrets were persisted in the cassette" >&2
  exit 1
fi
echo "==> redaction OK: no secrets in the cassette"

echo "==> starting backend v2 (regressed) on :8083"
# v2 calls downstream through the replay virtualization proxy (:8092)
python3 demo/backend.py --mode v2 --port 8083 --payment-url http://127.0.0.1:8092 & PIDS+=($!)
wait_for 8083

echo "==> diff: replay v1 cassette against v2"
set +e
java -jar "$JAR" diff "$CASSETTE" \
  --target http://127.0.0.1:8083 \
  --dep-listen 127.0.0.1:8092 \
  --fail-on-regression \
  --report demo/replayx-report.json
CODE=$?
set -e

echo "==> diff exit code: $CODE (2 = regression detected)"
if [ "$CODE" -ne 2 ]; then
  echo "DEMO FAILED: expected exit code 2" >&2
  exit 1
fi
echo "==> DEMO OK: intentional regression detected (paymentStatus SUCCESS -> PENDING)"
echo "    JSON report: demo/replayx-report.json"
