#!/usr/bin/env python3
"""Sample checkout backend for the ReplayX demo (stdlib only).

v1 returns the downstream paymentStatus untouched.
v2 intentionally regresses it to PENDING.

Usage:
    python3 backend.py --mode v1 --port 8081 --payment-url http://127.0.0.1:8082
"""
import argparse
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.request import urlopen


class Handler(BaseHTTPRequestHandler):
    mode = "v1"
    payment_url = "http://127.0.0.1:8082"

    def log_message(self, *args):
        pass

    def _send(self, status, obj):
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"status": "ok", "mode": self.mode})
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        if self.path != "/api/checkout":
            self.send_response(404)
            self.end_headers()
            return
        length = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(length) if length else b"{}"
        try:
            data = json.loads(raw.decode())
        except Exception:
            data = {}
        amount = data.get("amount", 100)

        # Call the downstream payment service.
        with urlopen(f"{self.payment_url}/charge?amount={amount}", timeout=10) as r:
            pay = json.loads(r.read().decode())

        status = pay.get("paymentStatus", "SUCCESS")
        if self.mode == "v2":
            status = "PENDING"  # <-- intentional regression for the demo

        self._send(200, {"orderId": "ord-1001", "paymentStatus": status, "amount": amount})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["v1", "v2"], default="v1")
    ap.add_argument("--port", type=int, default=8081)
    ap.add_argument("--payment-url", default="http://127.0.0.1:8082")
    args = ap.parse_args()
    Handler.mode = args.mode
    Handler.payment_url = args.payment_url.rstrip("/")
    server = HTTPServer(("0.0.0.0", args.port), Handler)
    print(f"backend {args.mode} on :{args.port}, payment-url={Handler.payment_url}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
