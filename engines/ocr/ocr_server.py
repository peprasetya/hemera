#!/usr/bin/env python3
"""hemera's OCR engine: text in a picture, with where each line and each character is.

usage: ocr_server.py --port N --models DIR [--det NAME] [--rec NAME] [--scripts NAME,...] [--threads N] [--workers N]

POST /ocr       body: the picture (PNG, JPEG, WebP, TIFF); header X-Options: JSON (see read())
                answer: JSON, lines grouped in blocks in reading order, positions in the picture's pixels
GET  /health    200 once the models are loaded
GET  /info      the models, the scripts they read, the limits

It reads with PaddleOCR's models in ONNX form on the CPU: a text detector, a recogniser per group
of scripts, and two orientation classifiers, one for the whole picture and one per line. The
recognisers are CTC models, so each character's place along its line comes from the recogniser
itself. hemera starts it and speaks to it on localhost; hemera turns code points into grapheme
clusters and words.

How a picture is read:
- the page's quarter turn is found (or given) and undone; lines are found at full resolution, in
  stitched tiles for a large picture;
- each line is read by the main recogniser; a tall one also as vertical CJK (turned, and stacked
  cell by cell), one the classifier takes for upside down also turned round;
- a doubtful reading (stretches left unread, unsure characters) is read again by every script's
  recogniser; the clearest reading that has its own script's letters wins, and is completed from
  the others (splice): text it left unread, words of another script, numbers most of them agree on;
- characters are tidied one for one (look-alike letters within a word, one digit system within a
  number, full-width forms outside CJK); unsure lines and texture read as strokes are left out;
- lines become blocks, and blocks are put in reading order (columns, then lines; forms and
  receipts row by row), with the page turned straight.

Nothing is kept: pictures and text live in memory for the length of a request. The log says how
large a picture was, how many lines it had and how long it took, never what it said.
"""
import argparse
import ctypes
import io
import json
import math
import re
import select
import socket
import sys
import threading
import time
import unicodedata
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort
import yaml
from PIL import Image, ImageOps

Image.MAX_IMAGE_PIXELS = None  # the limit is ours (MAX_PIXELS), checked before decoding

MAX_PIXELS = 50_000_000

# Recognisers for the scripts the main one lacks, by PaddleOCR's language names; and the
# languages a client may hint with, to the recogniser for them.
SCRIPT_RECS = {
    "korean": ("Hang", ["ko"]),
    "arabic": ("Arab", ["ar", "fa", "ur", "ug", "ps", "ku", "sd"]),
    "th": ("Thai", ["th"]),
    "devanagari": ("Deva", ["hi", "mr", "ne", "sa", "bh", "mai", "new", "gom"]),
    "cyrillic": ("Cyrl", ["ru", "uk", "be", "bg", "sr", "mk", "kk", "ky", "mn", "tg", "tt", "ba", "cv"]),
    "eslav": ("Cyrl", []),
    "el": ("Grek", ["el"]),
    "ta": ("Taml", ["ta"]),
    "te": ("Telu", ["te"]),
}
RTL_SCRIPTS = {"Arab", "Hebr"}
SMALL_MARKS = str.maketrans("", "", "、。，．・っゃゅょぁぃぅぇぉゎッャュョァィゥェォヮ")
STROKES = re.compile(r"[Il1|!iIH.:\- ]+")
UNSPACED_SCRIPTS = {"Hani", "Jpan", "Thai"}


class Cancelled(Exception):
    pass


class BadRequest(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


# ---- models -----------------------------------------------------------------------------------


def session(path, threads):
    o = ort.SessionOptions()
    o.intra_op_num_threads = threads
    o.inter_op_num_threads = 1
    o.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    o.log_severity_level = 3  # the converted models carry unused initializers; ORT warns of each one
    o.enable_mem_pattern = False  # shapes change with every picture
    return ort.InferenceSession(str(path), o, providers=["CPUExecutionProvider"])


# Shapes change with every picture, and ONNX Runtime's arena would keep the largest picture's
# tensors (GBs, for a 4000 px photo) for as long as the engine lives, in memory the GPU shares:
# after each run it gives back what it no longer uses.
RUN = ort.RunOptions()
RUN.add_run_config_entry("memory.enable_memory_arena_shrinkage", "cpu:0")


def config(model_dir):
    return yaml.safe_load((model_dir / "inference.yml").read_text(encoding="utf-8"))


def find_op(cfg, name):
    for op in cfg.get("PreProcess", {}).get("transform_ops", []):
        if isinstance(op, dict) and name in op:
            return op[name] or {}
    return {}


class Detector:
    def __init__(self, model_dir, threads):
        cfg = config(model_dir)
        self.name = cfg.get("Global", {}).get("model_name", model_dir.name)
        self.sess = session(model_dir / "inference.onnx", threads)
        norm = find_op(cfg, "NormalizeImage")
        self.mean = np.array(norm.get("mean", [0.485, 0.456, 0.406]), np.float32)
        self.std = np.array(norm.get("std", [0.229, 0.224, 0.225]), np.float32)
        post = cfg.get("PostProcess", {})
        self.thresh = float(post.get("thresh", 0.3))
        self.box_thresh = float(post.get("box_thresh", 0.6))
        self.unclip = float(post.get("unclip_ratio", 1.5))

    def probability(self, bgr):
        """DB's text probability map, one value per pixel of the picture. The picture is padded,
        never scaled: small print stays as it was printed."""
        h, w = bgr.shape[:2]
        ph, pw = -h % 32, -w % 32
        img = cv2.copyMakeBorder(bgr, 0, ph, 0, pw, cv2.BORDER_REPLICATE) if ph or pw else bgr
        x = (img.astype(np.float32) / 255.0 - self.mean) / self.std
        x = x.transpose(2, 0, 1)[None]
        out = self.sess.run(None, {self.sess.get_inputs()[0].name: x}, RUN)[0]
        return out[0, 0, :h, :w]

    def boxes(self, prob, sx=1.0, sy=1.0, min_side=3):
        """Quadrilaterals (four points, in the picture's pixels) from a probability map."""
        bitmap = (prob > self.thresh).astype(np.uint8) * 255
        contours, _ = cv2.findContours(bitmap, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        out = []
        for c in contours[:3000]:
            if len(c) < 4:
                continue
            rect = cv2.minAreaRect(c)
            (cx, cy), (rw, rh), ang = rect
            if min(rw, rh) < min_side:
                continue
            score = box_score(prob, cv2.boxPoints(rect))
            if score < self.box_thresh:
                continue
            # DB's unclip: grow by area * ratio / perimeter on every side
            d = rw * rh * self.unclip / (2 * (rw + rh))
            grown = ((cx, cy), (rw + 2 * d, rh + 2 * d), ang)
            if min(grown[1]) < min_side + 2:
                continue
            pts = cv2.boxPoints(grown)
            pts[:, 0] /= sx
            pts[:, 1] /= sy
            out.append((pts.astype(np.float64), float(score)))
        return out


def box_score(prob, pts):
    h, w = prob.shape
    xs, ys = pts[:, 0], pts[:, 1]
    x0, x1 = int(np.clip(np.floor(xs.min()), 0, w - 1)), int(np.clip(np.ceil(xs.max()), 0, w - 1))
    y0, y1 = int(np.clip(np.floor(ys.min()), 0, h - 1)), int(np.clip(np.ceil(ys.max()), 0, h - 1))
    mask = np.zeros((y1 - y0 + 1, x1 - x0 + 1), np.uint8)
    shifted = (pts - [x0, y0]).astype(np.int32)
    cv2.fillPoly(mask, [shifted], 1)
    return float(cv2.mean(prob[y0:y1 + 1, x0:x1 + 1], mask)[0])


class Recognizer:
    def __init__(self, model_dir, threads, script=None):
        cfg = config(model_dir)
        self.name = cfg.get("Global", {}).get("model_name", model_dir.name)
        self.sess = session(model_dir / "inference.onnx", threads)
        chars = list(cfg["PostProcess"]["character_dict"])
        # index 0 is CTC's blank; a space is the last class
        self.chars = ["\u0000"] + [str(c) for c in chars] + [" "]
        shape = find_op(cfg, "RecResizeImg").get("image_shape", [3, 48, 320])
        self.height, self.min_width = int(shape[1]), int(shape[2])
        self.script = script
        self.rtl = script in RTL_SCRIPTS

    def read(self, crops, cancel):
        """A Reading of each crop (one line each, upright, left to right)."""
        order = sorted(range(len(crops)), key=lambda i: crops[i].shape[1] / max(1, crops[i].shape[0]))
        out = [None] * len(crops)
        batch = 8
        for b in range(0, len(order), batch):
            cancel()
            idx = order[b:b + batch]
            widths = [max(1, int(math.ceil(self.height * crops[i].shape[1] / max(1, crops[i].shape[0])))) for i in idx]
            width = max(self.min_width, min(max(widths), 4800))
            width = int(math.ceil(width / 8) * 8)
            x = np.zeros((len(idx), 3, self.height, width), np.float32)
            used = []
            for k, i in enumerate(idx):
                rw = min(width, widths[k])
                img = cv2.resize(crops[i], (rw, self.height), interpolation=cv2.INTER_LINEAR)
                x[k, :, :, :rw] = ((img.astype(np.float32) / 255.0 - 0.5) / 0.5).transpose(2, 0, 1)
                used.append(rw)
            probs = self.sess.run(None, {self.sess.get_inputs()[0].name: x}, RUN)[0]
            steps = probs.shape[1]
            for k, i in enumerate(idx):
                text, probs_k, spans, centres = ctc_decode(probs[k], self.chars)
                # timestep -> pixels of the resized crop -> pixels of the original crop
                f = (width / steps) * (crops[i].shape[1] / used[k])
                out[i] = Reading(self, text, probs_k, [(a * f, b * f) for a, b in spans], [c * f for c in centres],
                                 crops[i].shape[1], crops[i].shape[0])
        return out


class Reading:
    """One recogniser's reading of one line: its characters (code points), each one's
    probability, span and centre along the crop, and the crop's size."""

    def __init__(self, rec, text, probs, spans, centres, width, height):
        self.rec, self.text, self.probs, self.spans, self.centres = rec, text, probs, spans, centres
        self.width, self.height = width, height
        self.conf = float(np.mean(probs)) if probs else 0.0

    def weakest(self):
        """The mean probability of the least sure three characters in a row."""
        p = self.probs
        if not p:
            return 0.0
        n = min(3, len(p))
        return min(sum(p[k:k + n]) / n for k in range(len(p) - n + 1))

    def coverage(self):
        """How much of the line the characters account for. A recogniser that meets a script it
        does not know mostly leaves it out: long stretches between its characters (or before the
        first, after the last) where a line of print has none."""
        if not self.text.strip():
            return 0.0
        h = self.height
        edge = [0.0] + [c for ch, c in zip(self.text, self.centres) if ch != " "] + [float(self.width)]
        missing = 0.0
        for k in range(1, len(edge)):
            allow = (1.0 if k == 1 or k == len(edge) - 1 else 1.6) * h
            missing += max(0.0, edge[k] - edge[k - 1] - allow)
        return max(0.0, 1.0 - missing / max(1.0, self.width))

    def quality(self):
        return self.coverage() * (0.5 * self.conf + 0.5 * self.weakest())

    def chars(self):
        """(character, probability, span, centre) of each code point."""
        return list(zip(self.text, self.probs, self.spans, self.centres))

    def doubtful(self):
        return self.quality() < 0.9 or self.weakest() < 0.8


def ctc_decode(probs, chars):
    """Greedy CTC: the characters, each one's peak probability, span and centre in timesteps.
    A character's span runs from halfway to the one before to halfway to the next."""
    best = probs.argmax(axis=1)
    peak = probs.max(axis=1)
    runs = []  # (char index, first step, last step, peak prob)
    t = 0
    n = len(best)
    while t < n:
        c = int(best[t])
        u = t
        while u + 1 < n and int(best[u + 1]) == c:
            u += 1
        if c != 0 and c < len(chars):
            runs.append((c, t, u, float(peak[t:u + 1].max())))
        t = u + 1
    if not runs:
        return "", [], [], []
    centres = [(a + b + 1) / 2 for _, a, b, _ in runs]
    spans = []
    for k, (c, a, b, p) in enumerate(runs):
        start = (centres[k - 1] + centres[k]) / 2 if k > 0 else max(0.0, a - (b + 1 - a) * 0.5 - 0.5)
        end = (centres[k] + centres[k + 1]) / 2 if k + 1 < len(runs) else min(float(n), b + 1 + (b + 1 - a) * 0.5 + 0.5)
        spans.append((start, end))
    return "".join(chars[c] for c, *_ in runs), [p for *_, p in runs], spans, centres


class Classifier:
    def __init__(self, model_dir, threads):
        cfg = config(model_dir)
        self.sess = session(model_dir / "inference.onnx", threads)
        post = cfg.get("PostProcess", {})
        topk = post.get("Topk", post)
        self.labels = [str(l) for l in (topk.get("label_list") or [])]
        resize = find_op(cfg, "ResizeImage")
        size = resize.get("size")
        self.size = (int(size[0]), int(size[1])) if size else None
        self.resize_short = int(resize.get("resize_short", 256))
        crop = find_op(cfg, "CropImage").get("size", 224)
        self.crop = int(crop[0] if isinstance(crop, list) else crop)
        norm = find_op(cfg, "NormalizeImage")
        self.mean = np.array(norm.get("mean", [0.485, 0.456, 0.406]), np.float32)
        self.std = np.array(norm.get("std", [0.229, 0.224, 0.225]), np.float32)

    def classify(self, bgrs):
        xs = []
        for bgr in bgrs:
            if self.size:
                img = cv2.resize(bgr, self.size, interpolation=cv2.INTER_LINEAR)
            else:
                h, w = bgr.shape[:2]
                s = self.resize_short / min(h, w)
                img = cv2.resize(bgr, (max(1, round(w * s)), max(1, round(h * s))), interpolation=cv2.INTER_AREA)
                h, w = img.shape[:2]
                y, x = (h - self.crop) // 2, (w - self.crop) // 2
                img = img[max(0, y):max(0, y) + self.crop, max(0, x):max(0, x) + self.crop]
                img = cv2.resize(img, (self.crop, self.crop))
            # PaddleClas feeds RGB to these
            img = img[:, :, ::-1]
            xs.append(((img.astype(np.float32) / 255.0 - self.mean) / self.std).transpose(2, 0, 1))
        out = self.sess.run(None, {self.sess.get_inputs()[0].name: np.stack(xs)}, RUN)[0]
        return [(self.labels[int(o.argmax())], float(o.max())) for o in out]


class Models:
    def __init__(self, root, det, rec, scripts, threads):
        root = Path(root)
        self.det = Detector(root / (det + "_onnx"), threads)
        self.rec = Recognizer(root / (rec + "_onnx"), threads)
        self.scripts = {}
        for name in scripts:
            d = root / f"{name}_PP-OCRv5_mobile_rec_onnx"
            if d.is_dir():
                self.scripts[name] = Recognizer(d, threads, SCRIPT_RECS[name][0])
        d = root / "PP-LCNet_x1_0_doc_ori_onnx"
        self.doc_ori = Classifier(d, threads) if d.is_dir() else None
        d = root / "PP-LCNet_x1_0_textline_ori_onnx"
        self.line_ori = Classifier(d, threads) if d.is_dir() else None

    def info(self):
        scripts = ["Latn", "Hani", "Jpan"] + sorted({r.script for r in self.scripts.values()})
        return {"detector": self.det.name, "recognizers": [self.rec.name] + [r.name for r in self.scripts.values()],
                "scripts": scripts, "char_boxes": "exact", "licence": "Apache-2.0 (PaddleOCR models)", "max_pixels": MAX_PIXELS}


# ---- reading ----------------------------------------------------------------------------------


def crop_line(bgr, quad, height=None):
    """The line upright and left to right: quad[0] lands top-left, quad[1] top-right."""
    p0, p1, p2, p3 = [np.array(p, np.float32) for p in quad]
    w = max(1.0, float(max(np.linalg.norm(p1 - p0), np.linalg.norm(p2 - p3))))
    h = max(1.0, float(max(np.linalg.norm(p3 - p0), np.linalg.norm(p2 - p1))))
    if height:
        w, h = w * height / h, height
    dst = np.array([[0, 0], [w, 0], [w, h], [0, h]], np.float32)
    m = cv2.getPerspectiveTransform(np.array([p0, p1, p2, p3]), dst)
    return cv2.warpPerspective(bgr, m, (int(round(w)), int(round(h))), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)


def em_cells(col):
    """A vertical CJK line, upright (text top to bottom), cut into its characters' cells:
    (top, bottom) rows. The em is how far the ink repeats down the column; each cut goes where
    the least ink is, every cell between 0.8 and 1.2 em."""
    h, w = col.shape[:2]
    g = cv2.cvtColor(col, cv2.COLOR_BGR2GRAY)
    _, bw = cv2.threshold(g, 0, 1, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    if bw.mean() > 0.5:  # light text on a dark ground
        bw = 1 - bw
    prof = bw.sum(axis=1).astype(np.float32) / max(1, w)
    rows = np.nonzero(prof > 0)[0]
    if len(rows) == 0:
        return []
    first, last = int(rows[0]), int(rows[-1]) + 1
    x = prof - prof.mean()
    em, best = w, -1e18
    for p in range(max(4, int(0.7 * w)), int(1.3 * w) + 1):
        if p >= len(x) // 2:
            break
        c = float((x[:-p] * x[p:]).mean())
        if c > best:
            best, em = c, p
    lo, hi = max(2, int(0.8 * em)), int(1.2 * em) + 1
    ext = np.concatenate([prof, np.zeros(hi + 1, np.float32)])
    cost, back = {first: 0.0}, {}
    for r in range(first + lo, last + hi + 1):
        bc, bq = None, None
        for d in range(lo, hi + 1):
            q = r - d
            if q in cost:
                c = cost[q] + float(ext[r]) + 0.3 * abs(d - em) / em
                if bc is None or c < bc:
                    bc, bq = c, q
        if bq is not None:
            cost[r], back[r] = bc, bq
    ends = [r for r in cost if last <= r <= last + hi]
    if not ends:
        return [(first, min(h, last))]
    r = min(ends, key=lambda e: cost[e])
    cuts = [r]
    while r in back:
        r = back[r]
        cuts.append(r)
    cuts = cuts[::-1]
    return [(a, min(h, b)) for a, b in zip(cuts, cuts[1:]) if min(h, b) > a]


def stack(col, cells):
    """The cells side by side, upright: a vertical line as a horizontal one."""
    h, w = col.shape[:2]
    border = np.concatenate([col[0], col[-1], col[:, 0], col[:, -1]])
    bg = np.median(border, axis=0).astype(np.uint8)
    tall = max(b - a for a, b in cells)
    gap = max(2, w // 8)
    out = np.empty((tall, len(cells) * (w + gap), 3), np.uint8)
    out[:] = bg
    for k, (a, b) in enumerate(cells):
        out[:b - a, k * (w + gap):k * (w + gap) + w] = col[a:b]
    return out, w + gap


def unstack(r, cells, pitch, length, em):
    """A reading of a stacked line, its characters placed back in their cells down the column."""
    groups = {}
    for k, ch in enumerate(r.text):
        groups.setdefault(min(len(cells) - 1, max(0, int(r.centres[k] // pitch))), []).append(k)
    spans = [None] * len(r.text)
    for cell, ks in groups.items():
        a, b = cells[cell]
        step = (b - a) / len(ks)
        for n, k in enumerate(ks):
            spans[k] = (a + n * step, a + (n + 1) * step)
    return Reading(r.rec, r.text, r.probs, spans, [(x + y) / 2 for x, y in spans], length, em)


def clockwise_from(pts, start):
    pts = [list(map(float, p)) for p in pts]
    return pts[start:] + pts[:start]


def corners(pts):
    """The four corners clockwise on screen, starting at the top-left-most."""
    pts = np.array(pts, np.float64)
    c = pts.mean(axis=0)
    pts = pts[np.argsort(np.arctan2(pts[:, 1] - c[1], pts[:, 0] - c[0]))]
    return np.roll(pts, -int(np.argmin(pts[:, 0] + pts[:, 1])), axis=0).tolist()


def line_angle(quad, direction):
    """How far the text is turned clockwise from upright, in degrees."""
    p0, p1, _, p3 = quad
    if direction == "rtl":
        dx, dy = p3[0] - p0[0], p3[1] - p0[1]
        base = 180.0
    elif direction == "ttb":
        dx, dy = p1[0] - p0[0], p1[1] - p0[1]
        base = 90.0
    else:
        dx, dy = p1[0] - p0[0], p1[1] - p0[1]
        base = 0.0
    a = math.degrees(math.atan2(dy, dx)) - base
    a = (a + 180) % 360 - 180
    return round(a, 1)


def script_of(text):
    counts = {}
    for ch in text:
        if not ch.isalpha():
            continue
        name = unicodedata.name(ch, "")
        if "HIRAGANA" in name or "KATAKANA" in name:
            s = "Kana"
        elif "CJK" in name or "IDEOGRAPH" in name:
            s = "Hani"
        elif "HANGUL" in name:
            s = "Hang"
        elif "ARABIC" in name:
            s = "Arab"
        elif "HEBREW" in name:
            s = "Hebr"
        elif "THAI" in name:
            s = "Thai"
        elif "DEVANAGARI" in name:
            s = "Deva"
        elif "CYRILLIC" in name:
            s = "Cyrl"
        elif "GREEK" in name:
            s = "Grek"
        elif "TAMIL" in name:
            s = "Taml"
        elif "TELUGU" in name:
            s = "Telu"
        else:
            s = "Latn"
        counts[s] = counts.get(s, 0) + 1
    if not counts:
        return "Zyyy"
    if "Kana" in counts:
        return "Jpan"
    # a line of mixed scripts is labelled by its most frequent, but any non-Latin script wins
    # over Latin, which is everywhere
    others = {k: v for k, v in counts.items() if k != "Latn"}
    pool = others if others else counts
    return max(pool, key=pool.get)


LANGUAGE_OF_SCRIPT = {"Jpan": "ja", "Hang": "ko", "Thai": "th", "Grek": "el", "Taml": "ta", "Telu": "te"}


LANGUAGES_OF_SCRIPT = {
    "Latn": {"id", "en", "ms", "fr", "de", "es", "it", "pt", "nl", "vi", "tl", "jv", "su", "tr", "pl", "sv", "da", "no",
             "fi", "cs", "ro", "hu", "sw", "la"},
    "Cyrl": set(SCRIPT_RECS["cyrillic"][1]), "Arab": set(SCRIPT_RECS["arabic"][1]),
    "Deva": set(SCRIPT_RECS["devanagari"][1]), "Hani": {"zh", "ja"}, "Jpan": {"ja"}, "Hang": {"ko"},
    "Thai": {"th"}, "Grek": {"el"}, "Taml": {"ta"}, "Telu": {"te"},
}


def has_script(text, script):
    """Whether any of the text's letters belong to the script."""
    return any(script_of(ch) == script or (script == "Jpan" and script_of(ch) in ("Jpan", "Hani")) for ch in text if ch.isalpha())


def language_of(script, hints):
    """The language of a line: the client's hint that fits its script, else the script's own
    language where it has only one, else unknown."""
    for h in hints:
        if h.split("-")[0].lower() in LANGUAGES_OF_SCRIPT.get(script, ()):
            return h
    return LANGUAGE_OF_SCRIPT.get(script)

def unread(chars, width, h):
    """Stretches of a line long enough to hold text that no character of the reading accounts for."""
    pts = [0.0] + [c[3] for c in chars if c[0] != " "] + [float(width)]
    out = []
    for k in range(1, len(pts)):
        first, last = k == 1, k == len(pts) - 1
        if pts[k] - pts[k - 1] > (1.0 if first or last else 1.6) * h:
            out.append((pts[k - 1] + (0.0 if first else 0.5 * h), pts[k] - (0.0 if last else 0.5 * h)))
    return out


def words(chars):
    """(first, last) index of each run of characters between spaces."""
    out, start = [], None
    for k, c in enumerate(chars + [(" ", 0, (0, 0), 0)]):
        if c[0] != " " and start is None:
            start = k
        elif c[0] == " " and start is not None:
            out.append((start, k - 1))
            start = None
    return out


def digit_runs(chars):
    """(first, last) index of each number: digits, with . , : / - between digits."""
    out, k, n = [], 0, len(chars)
    while k < n:
        if chars[k][0].isdigit():
            a = k
            while k + 1 < n and (chars[k + 1][0].isdigit() or
                                 (chars[k + 1][0] in ".,:/-" and k + 2 < n and chars[k + 2][0].isdigit())):
                k += 1
            out.append((a, k))
        k += 1
    return out


def digits_of(run):
    return "".join(str(unicodedata.digit(c[0])) for c in run if c[0].isdigit())


def splice(best, others):
    """The chosen reading of a line, completed from the line's other readings: text it left
    unread that another one read surely (the digits in an Arabic line, a Korean name in a Latin
    one), and words of another script that script's recogniser read surely, in place of the
    look-alike Latin the main recogniser made of them."""
    h, width = best.height, best.width
    chars = best.chars()
    changed = False
    for r in others:
        gaps = unread(chars, width, h)
        if not gaps:
            break
        add = [c for c in r.chars() if c[0] != " " and c[1] >= 0.8 and any(a <= c[3] <= b for a, b in gaps)]
        if add:
            chars = sorted(chars + add, key=lambda c: c[3])
            changed = True
    for r in others:
        script = r.rec.script
        if script is None:
            continue
        rc = r.chars()
        for a, b in words(rc):
            word = rc[a:b + 1]
            if not has_script("".join(c[0] for c in word), script):
                continue
            lo, hi = word[0][2][0], word[-1][2][1]
            inside = [c for c in chars if lo <= c[3] <= hi and c[0] != " "]
            pw = sum(c[1] for c in word) / len(word)
            pc = sum(c[1] for c in inside) / len(inside) if inside else 0.0
            # letters the main recogniser cannot make (no Latin look-alike) mean it misread the word
            sure = any(script_of(c[0]) == script and c[0] not in LATIN_LOOKALIKES for c in word)
            ok = (pw >= 0.7 and pw >= pc - 0.2) if sure else (pw >= 0.8 and pw >= pc - 0.1)
            if ok and not has_script("".join(c[0] for c in inside), script):
                chars = sorted([c for c in chars if not lo <= c[3] <= hi] + word, key=lambda c: c[3])
                changed = True
    # Numbers: every recogniser knows the same digits, so where they read the same place the
    # number most of them agree on wins (an Arabic recogniser may drop the 1 of "15" that four
    # others read). Compared by digits alone; the digits' own script stays the chosen reading's.
    if len(others) >= 2:
        for a, b in reversed(digit_runs(chars)):
            run = chars[a:b + 1]
            lo, hi = run[0][2][0] - 0.3 * h, run[-1][2][1] + 0.3 * h
            votes = {digits_of(run): [run]}
            for r in others:
                rc = r.chars()
                near, most = None, 0.0
                for ra, rb in digit_runs(rc):
                    ov = min(hi, rc[rb][2][1]) - max(lo, rc[ra][2][0])
                    if ov > most:
                        near, most = rc[ra:rb + 1], ov
                if near:
                    votes.setdefault(digits_of(near), []).append(near)
            mine = digits_of(run)
            win = max(votes, key=lambda key: len(votes[key]))
            if win != mine and len(votes[win]) >= 3 and len(votes[win]) > len(votes[mine]):
                pick = max(votes[win], key=lambda rr: sum(c[1] for c in rr) / len(rr))
                chars = sorted(chars[:a] + list(pick) + chars[b + 1:], key=lambda c: c[3])
                changed = True
    if not changed:
        return best
    out = []
    for c in chars:
        if c[0] == " " and (not out or out[-1][0] == " "):
            continue
        if out and c[0] != " " and out[-1][0] != " " and c[3] - out[-1][3] > 1.3 * h:
            out.append((" ", min(out[-1][1], c[1]), (out[-1][2][1], c[2][0]), (out[-1][3] + c[3]) / 2))
        out.append(c)
    while out and out[-1][0] == " ":
        out.pop()
    return Reading(best.rec, "".join(c[0] for c in out), [c[1] for c in out], [c[2] for c in out], [c[3] for c in out],
                   width, h)


# Letters that look alike across Latin, Cyrillic and Greek. A word printed in one script has none
# of another's, so a word mixing them takes the script of its unmistakable letters.
LOOKALIKES = {"Cyrl": "аaеeоoрpсcуyхxіiјjѕsһhАAВBЕEКKМMНHОOРPСCТTХXУYІIЈJЅS",
              "Grek": "οoνvΑAΒBΕEΖZΗHΙIΚKΜMΝNΟOΡPΤTΥYΧX"}

LATIN_LOOKALIKES = set("".join(pairs[k] for pairs in LOOKALIKES.values() for k in range(0, len(pairs), 2)))


def homoglyphs(word):
    for script, pairs in LOOKALIKES.items():
        to_latin = {pairs[k]: pairs[k + 1] for k in range(0, len(pairs), 2)}
        from_latin = {v: k for k, v in to_latin.items()}
        letters = [ch for ch in word if ch.isalpha()]
        theirs = [ch for ch in letters if script_of(ch) == script]
        latin = [ch for ch in letters if script_of(ch) == "Latn"]
        if not theirs or not latin:
            continue
        sure_theirs = any(ch not in to_latin for ch in theirs)
        sure_latin = any(ch not in from_latin for ch in latin)
        if sure_theirs and not sure_latin:
            return "".join(from_latin.get(ch, ch) for ch in word)
        if sure_latin and not sure_theirs:
            return "".join(to_latin.get(ch, ch) for ch in word)
    return word


DIGIT_RUN = re.compile(r"[0-9\u0660-\u0669\u06f0-\u06f9](?:[.,:/\-]?[0-9\u0660-\u0669\u06f0-\u06f9])*")


def one_digit_system(m):
    """A number is written in one set of digits: the Arabic-Indic one (١) and the Latin 1 look alike."""
    run = m.group(0)
    latin = sum(ch.isascii() and ch.isdigit() for ch in run)
    eastern = sum(not ch.isascii() and ch.isdigit() for ch in run)
    if not latin or not eastern:
        return run
    if latin >= eastern:
        return "".join(str(unicodedata.digit(ch)) if ch.isdigit() else ch for ch in run)
    base = 0x06F0 if any("\u06f0" <= ch <= "\u06f9" for ch in run) else 0x0660
    return "".join(chr(base + int(ch)) if ch.isascii() and ch.isdigit() else ch for ch in run)


def tidy(text):
    """Character fixes that keep one character for one: full-width forms outside CJK text (where a
    colon followed by a gap reads as "："), look-alike letters within a word, digits within a number."""
    if not any(script_of(ch) in ("Hani", "Jpan", "Hang") or "\u3000" <= ch <= "\u30ff" for ch in text if not ch.isspace()):
        text = "".join(chr(ord(ch) - 0xFEE0) if "\uff01" <= ch <= "\uff5e" else " " if ch == "\u3000" else ch for ch in text)
    text = re.sub(r"\S+", lambda m: homoglyphs(m.group(0)), text)
    return DIGIT_RUN.sub(one_digit_system, text)


LTR_RUN = re.compile(r"[A-Za-z0-9\u0660-\u0669\u06f0-\u06f9 :*./%+,\-()#@&$€£¥]")


def visual_to_logical(chars):
    """A right-to-left recogniser reads left to right: reverse its characters, keeping runs of
    digits and Latin letters in their own order (as PaddleOCR does). chars are tuples whose first
    item is the character; the rest (probability, span) travels with it."""
    groups, cur = [], []
    for c in chars:
        if LTR_RUN.match(c[0]):
            cur.append(c)
        else:
            if cur:
                groups.append(cur)
                cur = []
            groups.append([c])
    if cur:
        groups.append(cur)
    return [c for g in groups[::-1] for c in g]


class Reader:
    def __init__(self, models):
        self.m = models

    def read(self, data, options, cancel):
        t_start = time.time()
        timing = {}
        img = decode(data)
        width, height = img.size
        rx = ry = 0
        region = options.get("region")
        if region is not None:
            if not (isinstance(region, list) and len(region) == 4 and all(isinstance(v, (int, float)) for v in region)):
                raise BadRequest(400, "region must be [x, y, w, h] in the picture's pixels")
            x, y, w, h = region
            x0, y0 = max(0, int(math.floor(x))), max(0, int(math.floor(y)))
            x1, y1 = min(width, int(math.ceil(x + w))), min(height, int(math.ceil(y + h)))
            if x1 - x0 < 4 or y1 - y0 < 4:
                raise BadRequest(400, "region lies outside the picture or is too small")
            img = img.crop((x0, y0, x1, y1))
            rx, ry = x0, y0
        bgr = np.asarray(img.convert("RGB"))[:, :, ::-1].copy()
        cancel()

        # the picture's orientation: how far its text is turned clockwise from upright
        orient = options.get("orientation", "auto")
        t = time.time()
        if orient == "auto":
            orient = 0
            if self.m.doc_ori is not None:
                label, conf = self.m.doc_ori.classify([bgr])[0]
                if conf >= 0.85:
                    orient = int(label)
        elif orient not in (0, 90, 180, 270):
            raise BadRequest(400, "orientation must be \"auto\", 0, 90, 180 or 270")
        timing["orientation"] = int((time.time() - t) * 1000)
        upright = np.ascontiguousarray(np.rot90(bgr, k=orient // 90)) if orient else bgr
        cancel()

        t = time.time()
        quads = self.detect(upright, cancel)
        timing["detect"] = int((time.time() - t) * 1000)

        t = time.time()
        hints = [str(h) for h in (options.get("languages") or ["auto"])]
        lines = self.recognize(upright, quads, hints, cancel)
        timing["recognize"] = int((time.time() - t) * 1000)

        # reading order in the upright frame, then back to the coordinates of the picture as sent
        blocks = layout(lines)
        uh, uw = upright.shape[:2]
        for b in blocks:
            b["polygon"] = [self.unrotate(p, orient, uw, uh, rx, ry) for p in b["polygon"]]
            for l in b["lines"]:
                l["polygon"] = [self.unrotate(p, orient, uw, uh, rx, ry) for p in l["polygon"]]
                l["angle"] = round(((l["angle"] + orient) + 180) % 360 - 180, 1)
        timing["total"] = int((time.time() - t_start) * 1000)
        return {"image": {"width": width, "height": height}, "orientation": orient, "timing_ms": timing, "blocks": blocks}

    @staticmethod
    def unrotate(p, orient, uw, uh, rx, ry):
        x, y = p
        # np.rot90(k) turned the picture counter-clockwise k times; undo it
        for _ in range(orient // 90):
            x, y = uh - y, x  # one clockwise turn of a point in a picture uh high
            uw, uh = uh, uw
        return [round(x + rx, 1), round(y + ry, 1)]

    def detect(self, bgr, cancel):
        """Text lines at the picture's full resolution. A large picture's probability map is made
        in overlapping tiles and stitched, so that lines are found once, on the whole map: a line
        never breaks where two tiles meet."""
        h, w = bgr.shape[:2]
        tile, margin = 2048, 160
        if h * w <= 2560 * 2560:
            return [b for b, _ in self.m.det.boxes(self.m.det.probability(bgr))]
        full = np.zeros((h, w), np.float32)
        step = tile - 2 * margin
        for y in range(0, h, step):
            for x in range(0, w, step):
                cancel()
                y0, x0 = max(0, y - margin), max(0, x - margin)
                part = bgr[y0:min(h, y + step + margin), x0:min(w, x + step + margin)]
                prob = self.m.det.probability(part)
                yy, xx = y - y0, x - x0
                full[y:min(h, y + step), x:min(w, x + step)] = prob[yy:yy + min(step, h - y), xx:xx + min(step, w - x)]
        return [b for b, _ in self.m.det.boxes(full)]

    def recognize(self, bgr, quads, hints, cancel):
        if not quads:
            return []
        # Each line as one or two readings: a wide box reads left to right; a tall one may be a
        # vertical line (or a horizontal one turned a quarter clockwise) read downwards, or one
        # turned a quarter counter-clockwise read upwards. The more confident reading wins.
        candidates = []
        for q in quads:
            c = corners(q)
            top = math.dist(c[0], c[1])
            side = math.dist(c[1], c[2])
            if side >= 1.5 * top:
                candidates.append([(c[1:] + c[:1], "down"), (c[3:] + c[:3], "up"), (c[1:] + c[:1], "stack")])
            else:
                candidates.append([(c, "h")])
        flat = [(i, k) for i, cs in enumerate(candidates) for k in range(len(cs))]
        crops, stacks, lengths = [], {}, {}
        for j, (i, k) in enumerate(flat):
            q, kind = candidates[i][k]
            if kind == "stack":
                # read as CJK printed vertically: its characters upright, one after the other
                col = crop_line(bgr, q[3:] + q[:3], None)
                cells = em_cells(col)
                if len(cells) >= 2:
                    img, pitch = stack(col, cells)
                    crops.append(img)
                    stacks[j] = (cells, pitch, col.shape[0], col.shape[1])
                    lengths[j] = col.shape[0]
                    continue
            crops.append(crop_line(bgr, candidates[i][k][0], 48))
            lengths[j] = crops[-1].shape[1]

        def read(rec, js):
            out = rec.read([crops[j] for j in js], cancel)
            return [unstack(r, *stacks[j]) if j in stacks else r for j, r in zip(js, out)]
        cancel()
        # a horizontal line the classifier takes for upside down (in a photo, say) is also read
        # turned round; the readings decide, as the classifier misjudges scripts it has not seen
        if self.m.line_ori is not None:
            horiz = [j for j, (i, k) in enumerate(flat) if candidates[i][k][1] == "h"]
            for j, (label, conf) in zip(horiz, self.m.line_ori.classify([crops[j] for j in horiz]) if horiz else []):
                if label.startswith("180") and conf >= 0.85:
                    i, k = flat[j]
                    q = candidates[i][k][0]
                    candidates[i].append((q[2:] + q[:2], "h"))
                    flat.append((i, len(candidates[i]) - 1))
                    crops.append(np.ascontiguousarray(crops[j][::-1, ::-1]))
                    lengths[len(crops) - 1] = lengths[j]
        cancel()

        main = read(self.m.rec, list(range(len(crops))))
        readings = {}  # line -> [(candidate index, Reading)]
        for j, (i, k) in enumerate(flat):
            readings.setdefault(i, []).append((j, main[j]))

        def worth(jr):
            j, r = jr
            return r.quality() if j not in stacks or script_of(r.text) in ("Hani", "Jpan", "Hang") else -1.0
        chosen = {i: max(rs, key=worth) for i, rs in readings.items()}
        # Read turned on its side, vertical CJK loses its small marks (、。 and small kana sit at
        # the top right of their cells); read stacked, it keeps them. Where the stacked reading is
        # the other one plus such marks, it is the better one.
        for i, rs in readings.items():
            j, r = chosen[i]
            if j in stacks:
                continue
            for js, alt in rs:
                if js in stacks and len(alt.text) > len(r.text) and alt.text.translate(SMALL_MARKS) == r.text \
                        and worth((js, alt)) >= 0:
                    chosen[i] = (js, alt)

        # Scripts the main recogniser lacks. It does not say so: it leaves such text out, or reads
        # it as look-alike Latin, often with high confidence. So where its reading is doubtful (it
        # leaves stretches of the line unread, or some characters are unsure) every script's
        # recogniser reads the line too, and one whose reading has its own script's characters and
        # explains the line clearly better takes it; the result is then completed from the other
        # readings (splice). A script found so, or asked for by the client, then also reads the
        # page's other lines the main recogniser read as Latin: on a Thai page, "Ins" may be Thai
        # read as Latin with confidence.
        found = set()
        for h in hints:
            base = h.split("-")[0].lower()
            for name, (_, langs) in SCRIPT_RECS.items():
                if base in langs and name in self.m.scripts:
                    found.add(name)
        recs = {n: r for n, r in self.m.scripts.items() if not (n == "eslav" and "cyrillic" in self.m.scripts)}
        auto = not hints or any(h == "auto" for h in hints)
        doubtful = [i for i, (j, r) in chosen.items() if r.doubtful()] if auto else []
        if doubtful:
            base_q = {i: chosen[i][1].quality() for i in doubtful}
            js = [j for i in doubtful for j, _ in readings[i]]
            for name, rec in recs.items():
                cancel()
                for j, alt in zip(js, read(rec, js)):
                    i = flat[j][0]
                    readings[i].append((j, alt))
                    if worth((j, alt)) < 0:
                        continue
                    if has_script(alt.text, rec.script) and alt.quality() > chosen[i][1].quality() + 0.03:
                        if alt.quality() > base_q[i] + 0.1:
                            found.add(name)
                        chosen[i] = (j, alt)
            for i in doubtful:
                j, best = chosen[i]
                others = sorted((r for jj, r in readings[i] if jj == j and r is not best), key=lambda r: -r.quality())
                chosen[i] = (j, splice(best, others))
        tried = set(doubtful)
        for name in found:
            rec = recs[name]
            todo = [i for i, (j, r) in chosen.items() if i not in tried and r.rec is self.m.rec
                    and script_of(r.text) in ("Latn", "Zyyy")]
            if not todo:
                continue
            cancel()
            for i, alt in zip(todo, read(rec, [chosen[i][0] for i in todo])):
                if has_script(alt.text, rec.script) and alt.quality() >= chosen[i][1].quality() - 0.03:
                    chosen[i] = (chosen[i][0], alt)

        lines = []
        for i, (j, r) in sorted(chosen.items()):
            chars = [(ch, p, sp) for ch, p, sp, _ in r.chars()]
            while chars and chars[0][0] == " ":
                chars.pop(0)
            while chars and chars[-1][0] == " ":
                chars.pop()
            if not chars:
                continue
            fixed = tidy("".join(c[0] for c in chars))
            if len(fixed) == len(chars):
                chars = [(t, p, sp) for t, (_, p, sp) in zip(fixed, chars)]
            text = "".join(c[0] for c in chars)
            conf = sum(c[1] for c in chars) / len(chars)
            # A reading this unsure is more likely wrong than right; and textures (railings, road
            # markings, window rows) come back as rows of strokes. No reading beats a wrong one.
            if conf < 0.5 or (conf < 0.85 and STROKES.fullmatch(text)):
                continue
            q, kind = candidates[flat[j][0]][flat[j][1]]
            line_len = math.dist(q[0], q[1])
            ratio = line_len / max(1, lengths[j])
            chars = [(ch, p, (a * ratio, b * ratio)) for ch, p, (a, b) in chars]
            script = script_of(text)
            if r.rec.rtl or script in RTL_SCRIPTS:
                direction = "rtl"
                chars = visual_to_logical(chars)
                chars = [(ch, p, (line_len - b, line_len - a)) for ch, p, (a, b) in chars]  # from the right end
                quad = q[1:] + q[:1]
            elif kind in ("down", "stack") and script in ("Hani", "Jpan", "Hang"):
                direction, quad = "ttb", q
            else:
                direction, quad = "ltr", q
            lines.append({"text": "".join(c[0] for c in chars), "polygon": [[round(x, 1), round(y, 1)] for x, y in quad],
                          "angle": line_angle(quad, direction), "direction": direction, "script": script,
                          "language": language_of(script, hints), "confidence": round(conf, 3),
                          "cps": [[round(a, 1), round(b, 1)] for _, _, (a, b) in chars],
                          "cp_conf": [round(p, 3) for _, p, _ in chars], "chars_estimated": False})
        return lines


# ---- layout -----------------------------------------------------------------------------------


def layout(lines):
    """Lines into blocks, blocks into reading order: columns, then lines."""
    if not lines:
        return []
    # a page photographed askew: blocks, rows and columns are found with the page turned straight
    angles = sorted(l["angle"] for l in lines)
    skew = math.radians(angles[len(angles) // 2])
    cos, sin = math.cos(-skew), math.sin(-skew)
    for l in lines:
        pts = [(x * cos - y * sin, x * sin + y * cos) for x, y in l["polygon"]]
        l["_box"] = (min(p[0] for p in pts), min(p[1] for p in pts), max(p[0] for p in pts), max(p[1] for p in pts))
    vertical = [l for l in lines if l["direction"] == "ttb"]
    horizontal = [l for l in lines if l["direction"] != "ttb"]
    blocks = []
    if horizontal:
        blocks += tables(group(horizontal, vertical=False))
    if vertical:
        blocks += group(vertical, vertical=True)
    blocks = order_blocks(blocks, rtl=sum(l["direction"] == "rtl" for l in lines) > len(lines) / 2,
                          vertical=len(vertical) > len(horizontal))
    out = []
    for l in lines:
        del l["_box"]
    for b in blocks:
        pts = np.array([p for l in b["lines"] for p in l["polygon"]], np.float32)
        rect = cv2.minAreaRect(pts)
        poly = cv2.boxPoints(rect)
        out.append({"polygon": [[round(float(x), 1), round(float(y), 1)] for x, y in clockwise_tl(poly)], "lines": b["lines"]})
    return out


def by_rows(lines):
    """Horizontal lines top to bottom; lines side by side on one row (a label and its value, a
    receipt's item and price) in the row's reading direction."""
    rows = []
    for l in sorted(lines, key=lambda l: (frame(l)[1] + frame(l)[3]) / 2):
        x0, y0, x1, y1 = frame(l)
        if rows:
            r = rows[-1]
            if min(y1, r[2]) - max(y0, r[1]) >= 0.5 * min(y1 - y0, r[3]):
                r[0].append(l)
                r[1], r[2] = min(r[1], y0), max(r[2], y1)
                continue
        rows.append([[l], y0, y1, y1 - y0])
    out = []
    for r in rows:
        rtl = sum(l["direction"] == "rtl" for l in r[0]) * 2 > len(r[0])
        out += sorted(r[0], key=lambda l: -frame(l)[2] if rtl else frame(l)[0])
    return out


def tables(blocks):
    """Blocks side by side whose short lines pair up row by row are one table, read row by row:
    a form's labels and values, a receipt's items, quantities and prices. The columns of an
    article pair up too when their lines share a leading, but their lines are long."""
    def short(b):
        ws = sorted((frame(l)[2] - frame(l)[0]) / max(1.0, frame(l)[3] - frame(l)[1]) for l in b["lines"])
        return ws[len(ws) // 2] < 12

    def partner(l, others):
        x0, y0, x1, y1 = frame(l)
        return any(min(y1, frame(o)[3]) - max(y0, frame(o)[1]) >= 0.5 * min(y1 - y0, frame(o)[3] - frame(o)[1])
                   for o in others)

    n = len(blocks)
    parent = list(range(n))

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a
    for i in range(n):
        for j in range(i + 1, n):
            a, b = blocks[i]["box"], blocks[j]["box"]
            if min(a[3], b[3]) <= max(a[1], b[1]) or not (a[2] <= b[0] or b[2] <= a[0]):
                continue  # not side by side
            if not (short(blocks[i]) and short(blocks[j])):
                continue
            small, big = sorted((blocks[i], blocks[j]), key=lambda b: len(b["lines"]))
            if sum(partner(l, big["lines"]) for l in small["lines"]) >= 0.6 * len(small["lines"]):
                parent[find(i)] = find(j)
    merged = {}
    for i in range(n):
        merged.setdefault(find(i), []).append(blocks[i])
    out = []
    for bs in merged.values():
        if len(bs) == 1:
            out.append(bs[0])
            continue
        ls = by_rows([l for b in bs for l in b["lines"]])
        out.append({"lines": ls, "box": [min(b["box"][0] for b in bs), min(b["box"][1] for b in bs),
                                          max(b["box"][2] for b in bs), max(b["box"][3] for b in bs)]})
    return out


def clockwise_tl(poly):
    poly = np.array(poly, np.float64)
    c = poly.mean(axis=0)
    poly = poly[np.argsort(np.arctan2(poly[:, 1] - c[1], poly[:, 0] - c[0]))]
    return np.roll(poly, -int(np.argmin(poly[:, 0] + poly[:, 1])), axis=0).tolist()


def frame(l):
    """The line's box with the page turned straight: (x0, y0, x1, y1)."""
    if "_box" in l:
        return l["_box"]
    xs = [p[0] for p in l["polygon"]]
    ys = [p[1] for p in l["polygon"]]
    return min(xs), min(ys), max(xs), max(ys)


def group(lines, vertical):
    """Neighbouring lines of similar size, one under the other (or beside, for vertical text), form a block."""
    n = len(lines)
    parent = list(range(n))

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a
    boxes = [frame(l) for l in lines]
    for i in range(n):
        x0, y0, x1, y1 = boxes[i]
        hi = (x1 - x0) if vertical else (y1 - y0)
        for j in range(i + 1, n):
            a0, b0, a1, b1 = boxes[j]
            hj = (a1 - a0) if vertical else (b1 - b0)
            size = max(1.0, min(hi, hj))
            if max(hi, hj) / size > 1.7:
                continue
            if vertical:
                overlap = min(y1, b1) - max(y0, b0)
                gap = max(a0 - x1, x0 - a1)
                span = min(y1 - y0, b1 - b0)
            else:
                overlap = min(x1, a1) - max(x0, a0)
                gap = max(b0 - y1, y0 - b1)
                span = min(x1 - x0, a1 - a0)
            if overlap > 0.3 * max(1.0, span) and gap < 1.2 * size:
                parent[find(i)] = find(j)
    groups = {}
    for i in range(n):
        groups.setdefault(find(i), []).append(lines[i])
    out = []
    for g in groups.values():
        if vertical:
            g.sort(key=lambda l: -frame(l)[2])
        else:
            g = by_rows(g)
        out.append({"lines": g, "box": [min(frame(l)[0] for l in g), min(frame(l)[1] for l in g),
                                         max(frame(l)[2] for l in g), max(frame(l)[3] for l in g)]})
    return out


def order_blocks(blocks, rtl, vertical):
    """Recursive XY cut: split where a gap runs across the whole group, top to bottom first for
    horizontal writing, right to left first for vertical; columns run right to left for RTL and
    vertical writing."""
    def cut(bs, depth=0):
        if len(bs) <= 1 or depth > 40:
            return bs
        for axis in ((0, 1) if vertical else (1, 0)):
            spans = sorted((b["box"][axis], b["box"][axis + 2]) for b in bs)
            reach = spans[0][1]
            for s, e in spans[1:]:
                if s > reach:
                    lo = [b for b in bs if b["box"][axis + 2] <= reach]
                    hi = [b for b in bs if b["box"][axis] >= s]
                    if len(lo) + len(hi) == len(bs) and lo and hi:
                        first, second = lo, hi
                        if axis == 0 and (rtl or vertical):
                            first, second = hi, lo
                        return cut(first, depth + 1) + cut(second, depth + 1)
                reach = max(reach, e)
        return sorted(bs, key=lambda b: (b["box"][1], b["box"][0]))
    return cut(blocks)


def decode(data):
    try:
        img = Image.open(io.BytesIO(data))
        w, h = img.size
        if w * h > MAX_PIXELS:
            raise BadRequest(413, f"the picture has {w * h} pixels; at most {MAX_PIXELS} are read")
        img.load()
    except BadRequest:
        raise
    except Exception as e:
        raise BadRequest(400, f"cannot read the picture: {e.__class__.__name__}")
    if img.mode in ("RGBA", "LA", "P", "PA") or (img.mode == "P" and "transparency" in img.info):
        img = img.convert("RGBA")
        bg = Image.new("RGBA", img.size, (255, 255, 255, 255))
        img = Image.alpha_composite(bg, img)
    if img.mode not in ("RGB", "L"):
        img = img.convert("RGB")
    return img


# ---- server -----------------------------------------------------------------------------------


try:
    _LIBC = ctypes.CDLL("libc.so.6")
except OSError:
    _LIBC = None


def give_back():
    """Hands the memory a picture took back to the system (glibc keeps freed pages otherwise)."""
    if _LIBC is not None:
        _LIBC.malloc_trim(0)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    reader = None
    workers = None

    def log_message(self, fmt, *args):
        pass

    def send(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            self.send(200, {"status": "ok"})
        elif self.path == "/info":
            self.send(200, self.reader.m.info())
        else:
            self.send(404, {"error": "not found"})

    def cancel(self):
        """Raises Cancelled once the caller has hung up."""
        sock = self.connection
        try:
            r, _, _ = select.select([sock], [], [], 0)
            if r and sock.recv(1, socket.MSG_PEEK) == b"":
                raise Cancelled()
        except (OSError, ValueError):
            raise Cancelled()

    def do_POST(self):
        if self.path != "/ocr":
            self.send(404, {"error": "not found"})
            return
        n = int(self.headers.get("Content-Length") or 0)
        data = self.rfile.read(n)
        t0 = time.time()
        try:
            options = json.loads(self.headers.get("X-Options") or "{}")
            if not isinstance(options, dict):
                raise BadRequest(400, "options must be a JSON object")
            with self.workers:
                try:
                    result = self.reader.read(data, options, self.cancel)
                finally:
                    give_back()
            lines = sum(len(b["lines"]) for b in result["blocks"])
            print(f"read {result['image']['width']}x{result['image']['height']}, {len(data)} bytes: {lines} lines "
                  f"in {result['timing_ms']['total']} ms {result['timing_ms']}", flush=True)
            self.send(200, result)
        except Cancelled:
            print(f"cancelled after {int((time.time() - t0) * 1000)} ms: the caller hung up", flush=True)
            self.close_connection = True
        except BadRequest as e:
            self.send(e.status, {"error": str(e)})
        except json.JSONDecodeError as e:
            self.send(400, {"error": f"options are not valid JSON: {e.msg}"})
        except Exception as e:
            print(f"failed: {e.__class__.__name__}: {e}", flush=True)
            self.send(500, {"error": f"{e.__class__.__name__}: {e}"})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--models", required=True)
    ap.add_argument("--det", default="PP-OCRv6_medium_det")
    ap.add_argument("--rec", default="PP-OCRv6_medium_rec")
    ap.add_argument("--scripts", default="korean,arabic,th,devanagari,cyrillic,el,ta,te")
    ap.add_argument("--threads", type=int, default=8)
    ap.add_argument("--workers", type=int, default=2, help="pictures read at once")
    a = ap.parse_args()
    t = time.time()
    models = Models(a.models, a.det, a.rec, [s for s in a.scripts.split(",") if s], a.threads)
    print(f"loaded {models.det.name}, {models.rec.name} and {len(models.scripts)} script recognisers in {time.time() - t:.1f} s", flush=True)
    Handler.reader = Reader(models)
    Handler.workers = threading.BoundedSemaphore(a.workers)
    server = ThreadingHTTPServer(("127.0.0.1", a.port), Handler)
    server.daemon_threads = True
    print(f"listening on 127.0.0.1:{a.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
