#!/usr/bin/env python3
"""A stand-in OCR engine for tests/ocr/OcrApiTest: POST /v1/ocr with a fixed answer, no models.

usage: fake_ocr_engine.py --port N --mode fast|slow|lazy|huge|bad --log FILE

  fast  answers at once with a fixed answer (fake_answer.json beside this file)
  slow  takes 8 s, watching its client: if the client goes, writes "cancelled" to the log
  lazy  is not healthy until 3 s after it starts
  huge  answers 413, as the engine does for a picture of too many pixels
  bad   answers 400, as the engine does for a request it cannot read

Each POST appends to the log "type <Content-Type>", "body-sha256 <hex>" (never the body), then
"finished" or "cancelled".
"""
import argparse
import hashlib
import os
import select
import socket
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STARTED = time.time()
ANSWER = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "fake_answer.json"), "rb").read()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def send(self, status, body):
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def note(self, text):
        with open(ARGS.log, "a", encoding="utf-8") as f:
            f.write(text + "\n")

    def do_GET(self):
        if self.path == "/health":
            ready = ARGS.mode != "lazy" or time.time() - STARTED > 3
            self.send(200 if ready else 503, b'{"status": "ok"}' if ready else b'{"status": "loading"}')
        else:
            self.send(404, b'{"error": "not found"}')

    def do_POST(self):
        if self.path != "/v1/ocr":
            self.send(404, b'{"error": "not found"}')
            return
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        self.note("type " + self.headers.get("Content-Type", ""))
        self.note("body-sha256 " + hashlib.sha256(body).hexdigest())
        if ARGS.mode == "huge":
            self.note("finished")
            self.send(413, b'{"error": "the picture has 60000000 pixels; at most 50000000 are read"}')
            return
        if ARGS.mode == "bad":
            self.note("finished")
            self.send(400, b'{"error": "granularity must be \\"lines\\", \\"words\\" or \\"chars\\""}')
            return
        if ARGS.mode == "slow":
            until = time.time() + 8
            while time.time() < until:
                r, _, _ = select.select([self.connection], [], [], 0.05)
                if r and self.connection.recv(1, socket.MSG_PEEK) == b"":
                    self.note("cancelled")
                    return
        self.note("finished")
        self.send(200, ANSWER)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--mode", default="fast")
    ap.add_argument("--log", required=True)
    ARGS = ap.parse_args()
    ThreadingHTTPServer(("127.0.0.1", ARGS.port), Handler).serve_forever()
