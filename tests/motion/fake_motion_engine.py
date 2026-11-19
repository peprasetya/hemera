#!/usr/bin/env python3
"""A stand-in motion engine for MotionApiTest: the motion API with made-up clips, no model.

    fake_motion_engine.py --port N [--job-seconds S] [--start-seconds S] [--log FILE]

It answers like the real motion server (status codes, error shape, content types), records what it
was sent in --log (the body's sha256, never its text), and makes a clip in --job-seconds.
"""
import argparse
import hashlib
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

BASE = "/v1/motion/generations"
ap = argparse.ArgumentParser()
ap.add_argument("--port", type=int, required=True)
ap.add_argument("--job-seconds", type=float, default=0.5)
ap.add_argument("--start-seconds", type=float, default=0.0)
ap.add_argument("--log")
args = ap.parse_args()
started = time.time()
jobs = {}
lock = threading.Lock()
count = [0]


def note(line):
    if args.log:
        with open(args.log, "a") as f:
            f.write(line + "\n")


def describe(j):
    done = time.time() - j["at"] >= args.job_seconds
    d = {"id": j["id"], "object": "motion.generation", "model": "fake", "status": "completed" if done else "in_progress",
         "progress": 100 if done else 50, "created_at": int(j["at"]), "duration": j["duration"], "fps": 30,
         "frames": int(j["duration"] * 30), "loop": False, "seed": 1}
    if done:
        d["output"] = {"formats": ["json", "glb", "bvh"], "url": BASE + "/" + j["id"] + "/content"}
    return d


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def send(self, status, body, ctype="application/json", extra=None):
        if not isinstance(body, bytes):
            body = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def handle_any(self):
        url = urlparse(self.path)
        path = url.path
        note("%s %s" % (self.command, self.path))
        if path == "/health":
            ok = time.time() - started >= args.start_seconds
            return self.send(200 if ok else 503, {"status": "ok" if ok else "loading"})
        rest = path[len(BASE):].strip("/") if path.startswith(BASE) else None
        if rest is None:
            return self.send(404, {"error": "not found"})
        if self.command == "POST" and not rest:
            body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
            note("body-sha256 " + hashlib.sha256(body).hexdigest())
            req = json.loads(body or b"{}")
            if not req.get("prompt"):
                return self.send(400, {"error": "'prompt' is missing: say in words how the person moves"})
            with lock:
                count[0] += 1
                j = {"id": "motion_%04d" % count[0], "at": time.time(), "duration": float(req.get("duration", 4))}
                jobs[j["id"]] = j
            return self.send(200, describe(j))
        if self.command == "GET" and not rest:
            with lock:
                data = [describe(j) for j in jobs.values()]
            return self.send(200, {"object": "list", "data": data})
        parts = rest.split("/")
        with lock:
            j = jobs.get(parts[0])
        if j is None:
            return self.send(404, {"error": "no motion generation '%s'" % parts[0]})
        if self.command == "GET" and len(parts) == 1:
            return self.send(200, describe(j))
        if self.command == "GET" and parts[1:] == ["content"]:
            fmt = (parse_qs(url.query).get("format") or ["json"])[0]
            if fmt not in ("json", "glb", "bvh"):
                return self.send(400, {"error": "format '%s' is not offered: json, glb or bvh" % fmt})
            if describe(j)["status"] != "completed":
                return self.send(409, {"error": "motion generation '%s' is in_progress" % j["id"]})
            body = {"json": json.dumps({"object": "motion", "frames": []}).encode(), "glb": b"glTF\x02\x00\x00\x00fake",
                    "bvh": b"HIERARCHY\n"}[fmt]
            ctype = {"json": "application/json", "glb": "model/gltf-binary", "bvh": "text/plain; charset=utf-8"}[fmt]
            return self.send(200, body, ctype, {"Content-Disposition": 'attachment; filename="%s.%s"' % (j["id"], fmt)})
        if self.command == "DELETE" and len(parts) == 1:
            with lock:
                jobs.pop(j["id"], None)
            return self.send(200, {"id": j["id"], "object": "motion.generation.deleted", "deleted": True,
                                   "cancelled": describe(j)["status"] != "completed"})
        return self.send(405, {"error": "not offered"})

    do_GET = do_POST = do_DELETE = handle_any


ThreadingHTTPServer(("127.0.0.1", args.port), H).serve_forever()
