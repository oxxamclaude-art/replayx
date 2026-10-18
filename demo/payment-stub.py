#!/usr/bin/env python3
"""Tiny downstream payment stub for the ReplayX demo (stdlib only).

Always reports paymentStatus SUCCESS.

Usage:
    python3 payment-stub.py --port 8082
"""
import argparse
import json
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path.startswith("/charge"):
            body = json.dumps({"paymentStatus": "SUCCESS", "txnId": "txn-4242"}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8082)
    args = ap.parse_args()
    server = HTTPServer(("0.0.0.0", args.port), Handler)
    print(f"payment-stub on :{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
