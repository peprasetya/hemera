#!/usr/bin/env python3
"""A stand-in for hemera's OCR engine (engines/ocr/ocr_server.py), for tests/ocr/OcrApiTest.

usage: fake_ocr_engine.py --port N --mode fast|slow|lazy|huge --log FILE

  fast  answers POST /ocr at once with a fixed reading
  slow  takes 8 s, watching its client: if the client goes, writes "cancelled" to the log
  lazy  is not healthy until 3 s after it starts
  huge  answers 413, as the engine does for a picture of too many pixels

Each POST /ocr appends one line to the log: "options <the X-Options header>", then
"finished" or "cancelled".
"""
import argparse
import json
import select
import socket
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STARTED = time.time()


def spans(n, step=10.0):
    return [[i * step, (i + 1) * step] for i in range(n)]


def answer():
    latin = "Café \U0001F44D\U0001F3FD ok"  # 11 code points, 9 grapheme clusters
    nik = "NIK 3171"
    arabic = "مرحبا 15"
    def line(text, polygon, direction, script, language, conf):
        n = len(text)
        return {"text": text, "polygon": polygon, "angle": 0.0, "direction": direction, "script": script,
                "language": language, "confidence": 0.9, "cps": spans(n), "cp_conf": conf(n), "chars_estimated": False}
    return {"image": {"width": 400, "height": 300}, "orientation": 0,
            "timing_ms": {"orientation": 1, "detect": 2, "recognize": 3, "total": 6},
            "blocks": [
                {"polygon": [[0, 0], [110, 0], [110, 40], [0, 40]], "lines": [
                    line(latin, [[0, 0], [110, 0], [110, 10], [0, 10]], "ltr", "Latn", "en", lambda n: [0.5] * 4 + [1.0] * (n - 4)),
                    line(nik, [[0, 20], [80, 20], [80, 30], [0, 30]], "ltr", "Latn", "id", lambda n: [0.8] * n)]},
                {"polygon": [[200, 100], [280, 100], [280, 110], [200, 110]], "lines": [
                    # right to left: the polygon starts top-right; spans run from the right end
                    line(arabic, [[280, 100], [280, 110], [200, 110], [200, 100]], "rtl", "Arab", "ar", lambda n: [0.7] * n)]},
            ]}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def send(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def note(self, text):
        with open(ARGS.log, "a", encoding="utf-8") as f:
            f.write(text + "\n")

    def do_GET(self):
        if self.path == "/health":
            ready = ARGS.mode != "lazy" or time.time() - STARTED > 3
            self.send(200 if ready else 503, {"status": "ok" if ready else "loading"})
        else:
            self.send(404, {"error": "not found"})

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        self.note("options " + self.headers.get("X-Options", ""))
        if ARGS.mode == "huge":
            self.note("finished")
            self.send(413, {"error": "the picture has 60000000 pixels; at most 50000000 are read"})
            return
        if ARGS.mode == "slow":
            until = time.time() + 8
            while time.time() < until:
                r, _, _ = select.select([self.connection], [], [], 0.05)
                if r and self.connection.recv(1, socket.MSG_PEEK) == b"":
                    self.note("cancelled")
                    return
        self.note("finished")
        self.send(200, answer())


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--mode", default="fast")
    ap.add_argument("--log", required=True)
    ARGS = ap.parse_args()
    ThreadingHTTPServer(("127.0.0.1", ARGS.port), Handler).serve_forever()
