#!/usr/bin/env python3
"""Measures OCR models against the generated test set (make_testset.py).

usage:
  bench.py run <testset dir> <adapter> [name=value ...] [--only a,b] > results.json
  bench.py report results.json [results.json ...] > table.md
  bench.py rescore results.json <testset dir> > results.json

Adapters:
  hemera    POST /v1/ocr on hemera (url=http://127.0.0.1:12434, model=<name>)
  engine    POST /ocr on an OCR engine directly (url=http://127.0.0.1:<port>)
  vlm       a vision LLM through an OpenAI-style /v1/chat/completions, asked for lines with
            boxes (url, model, tile=1600, coords=1000|pixels, fmt=json|paddle); fmt=paddle asks
            PaddleOCR-VL for "Spotting:" and reads its <|LOC_n|> corners

For each picture it records the time, and compares the lines read with the truth:
  CER        edit distance / length of the true text, per line, summed per script
  digits     how many of the digit strings printed (NIK, totals, dates, phone numbers) come
             back exactly
  box        intersection over union of each true line with the best line read; "hit" when
             it is 0.5 or more
  text CER   the same edit distance with each true line paired to the read line closest in
             text, wherever it was found: how well the model reads, apart from where it says the
             text is
Needs only Python 3 and Pillow.
"""
import base64
import io
import json
import re
import sys
import time
import unicodedata
import urllib.request
import uuid
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

# ---- geometry ---------------------------------------------------------------------------------


def area(poly):
    s = 0.0
    for (x1, y1), (x2, y2) in zip(poly, poly[1:] + poly[:1]):
        s += x1 * y2 - x2 * y1
    return abs(s) / 2


def clockwise(poly):
    s = sum(x1 * y2 - x2 * y1 for (x1, y1), (x2, y2) in zip(poly, poly[1:] + poly[:1]))
    return poly if s > 0 else poly[::-1]  # y points down: positive is clockwise on screen


def clip(subject, clipper):
    """Sutherland-Hodgman: subject clipped by a convex clipper, both clockwise on screen."""
    def inside(p, a, b):
        return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) >= 0

    def cross(p, q, a, b):
        x1, y1, x2, y2, x3, y3, x4, y4 = *p, *q, *a, *b
        d = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if d == 0:
            return q
        t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / d
        return (x1 + t * (x2 - x1), y1 + t * (y2 - y1))

    out = subject
    for a, b in zip(clipper, clipper[1:] + clipper[:1]):
        if not out:
            break
        inp, out = out, []
        for p, q in zip(inp, inp[1:] + inp[:1]):
            if inside(q, a, b):
                if not inside(p, a, b):
                    out.append(cross(p, q, a, b))
                out.append(q)
            elif inside(p, a, b):
                out.append(cross(p, q, a, b))
    return out


def overlap(a, b):
    """(intersection, area a, area b) of two convex quadrilaterals."""
    a, b = clockwise([tuple(p) for p in a]), clockwise([tuple(p) for p in b])
    inter = clip(a, b)
    return (area(inter) if len(inter) >= 3 else 0.0), area(a), area(b)


def iou(a, b):
    i, aa, ab = overlap(a, b)
    return i / (aa + ab - i) if aa + ab - i > 0 else 0.0


# ---- text -------------------------------------------------------------------------------------

def norm(text):
    """Letters, digits and punctuation: NFC, whitespace left out (spacing is not scored)."""
    return re.sub(r"\s+", "", unicodedata.normalize("NFC", text))


def edits(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def raster_iou(truths, reads):
    """Intersection over union of two sets of polygons, each set taken as the union of its polygons."""
    pts = [p for poly in truths + reads for p in poly]
    x0, y0 = min(p[0] for p in pts), min(p[1] for p in pts)
    x1, y1 = max(p[0] for p in pts), max(p[1] for p in pts)
    k = min(1.0, 400 / max(1.0, x1 - x0, y1 - y0))
    size = (int((x1 - x0) * k) + 2, int((y1 - y0) * k) + 2)

    def mask(polys):
        m = Image.new("1", size)
        d = ImageDraw.Draw(m)
        for poly in polys:
            d.polygon([((x - x0) * k, (y - y0) * k) for x, y in poly], fill=1)
        return m

    a, b = mask(truths), mask(reads)
    inter = ImageChops.logical_and(a, b).histogram()[255]
    union = ImageChops.logical_or(a, b).histogram()[255]
    return inter / max(1, union)


# ---- scoring ----------------------------------------------------------------------------------


def score(truth, lines):
    """Pairs what was read with the truth by place. A true line and a read line belong together
    when most of either lies inside the other, so a line read in pieces (a receipt row as item,
    quantity and price) and two lines read as one (a label and its value) are each compared as a
    whole: the group's true lines in order against its read lines in the order they came back.
    Spacing is not scored; every letter, digit and punctuation mark is."""
    gts = truth["lines"]
    reads = [l for l in lines if len(l.get("polygon") or []) == 4]
    extra = sum(len(norm(l.get("text", ""))) for l in lines if len(l.get("polygon") or []) != 4)
    parent = list(range(len(gts) + len(reads)))

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    for j, r in enumerate(reads):
        for i, g in enumerate(gts):
            inter, a_r, a_g = overlap(r["polygon"], g["polygon"])
            if inter > 0 and (inter >= 0.5 * a_r or inter >= 0.5 * a_g):
                parent[find(i)] = find(len(gts) + j)
    groups = {}
    for k in range(len(parent)):
        groups.setdefault(find(k), []).append(k)
    per_script, rows, ious = {}, [], [0.0] * len(gts)
    digits_ok = digits_all = 0
    for members in groups.values():
        gi = sorted(k for k in members if k < len(gts))
        rs = [reads[k - len(gts)] for k in sorted(members) if k >= len(gts)]
        if not gi:
            extra += sum(len(norm(r.get("text", ""))) for r in rs)
            continue
        t = "".join(norm(gts[i]["text"]) for i in gi)
        read = " ".join(r.get("text", "") for r in rs)
        e = edits(t, norm(read))
        for i in gi:  # the group's edits shared among its scripts by length
            s = per_script.setdefault(gts[i]["script"], [0.0, 0])
            s[0] += e * len(norm(gts[i]["text"])) / max(1, len(t))
            s[1] += len(norm(gts[i]["text"]))
        u = raster_iou([gts[i]["polygon"] for i in gi], [r["polygon"] for r in rs]) if rs else 0.0
        for i in gi:
            ious[i] = u
            for d in gts[i].get("digits") or []:
                digits_all += 1
                digits_ok += norm(d) in norm(read)
        rows.append({"truth": " | ".join(gts[i]["text"] for i in gi), "read": " | ".join(r.get("text", "") for r in rs),
                     "edits": e, "iou": round(u, 3)})
    # reading order: of each two true lines read one after the other, is the first read first?
    first_read = {}
    for members in groups.values():
        gi = [k for k in members if k < len(gts)]
        ri = [k - len(gts) for k in members if k >= len(gts)]
        for i in gi:
            if ri:
                first_read[i] = min(lines.index(reads[k]) for k in ri)
    pairs = [(i, i + 1) for i in range(len(gts) - 1) if i in first_read and i + 1 in first_read]
    in_order = sum(first_read[a] <= first_read[b] for a, b in pairs)
    total_e = sum(v[0] for v in per_script.values()) + extra
    total_n = sum(v[1] for v in per_script.values())
    # position-free: each true line against the closest unused reading
    pool = [norm(l.get("text", "")) for l in lines]
    text_script = {}
    for g in sorted(gts, key=lambda g: -len(g["text"])):
        t = norm(g["text"])
        best, which = len(t), None
        for j, r in enumerate(pool):
            if r is None:
                continue
            e = edits(t, r)
            if e < best:
                best, which = e, j
        if which is not None:
            pool[which] = None
        ts = text_script.setdefault(g["script"], [0, 0])
        ts[0] += best
        ts[1] += len(t)
    return {"cer": total_e / max(1, total_n), "per_script": per_script, "extra_chars": extra, "text_per_script": text_script,
            "digits": [digits_ok, digits_all], "iou_mean": sum(ious) / max(1, len(gts)),
            "hits": sum(u >= 0.5 for u in ious), "lines": len(gts), "order": [in_order, len(pairs)], "rows": rows}


# ---- adapters ---------------------------------------------------------------------------------


def multipart(fields, files):
    boundary = uuid.uuid4().hex
    out = io.BytesIO()
    for k, v in fields.items():
        out.write(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode())
    for k, (name, ctype, data) in files.items():
        out.write(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"; filename="{name}"\r\nContent-Type: {ctype}\r\n\r\n'.encode())
        out.write(data)
        out.write(b"\r\n")
    out.write(f"--{boundary}--\r\n".encode())
    return out.getvalue(), "multipart/form-data; boundary=" + boundary


def ocr_api(path, url, endpoint, model, options):
    data = path.read_bytes()
    ctype = "image/jpeg" if path.suffix == ".jpg" else "image/png"
    opts = dict(options)
    if model:
        opts["model"] = model
    if endpoint == "/ocr":  # the engine itself takes the picture as the body, the options in a header
        req = urllib.request.Request(url + endpoint, data, {"Content-Type": ctype, "X-Options": json.dumps(opts)})
    else:
        body, ct = multipart({"options": json.dumps(opts)}, {"image": (path.name, ctype, data)})
        req = urllib.request.Request(url + endpoint, body, {"Content-Type": ct})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=600) as r:
        answer = json.load(r)
    el = time.time() - t0
    lines = [l for b in answer.get("blocks", []) for l in b.get("lines", [])]
    return lines, el, {"model": answer.get("model"), "timing_ms": answer.get("timing_ms")}


VLM_PROMPT = ("Spotting all the text in the image with line-level, and output in JSON format: a list of "
              '{"bbox_2d": [x1, y1, x2, y2], "text_content": "..."}, one entry per printed line, in reading order. '
              "Copy every character exactly as printed: do not correct, complete, translate or reformat anything.")


LOC_LINE = re.compile(r"(.*?)((?:<\|LOC_\d+\|>){8})")


def paddle_lines(content):
    """PaddleOCR-VL's "Spotting:" answer: each line's text, then its four corners as
    <|LOC_n|> pairs on a 0-1000 scale, clockwise from the top-left."""
    out = []
    for m in LOC_LINE.finditer(content.replace("</s>", "")):
        text = m.group(1).strip()
        v = [int(x) for x in re.findall(r"\d+", m.group(2))]
        out.append({"text_content": text, "quad": [[v[0], v[1]], [v[2], v[3]], [v[4], v[5]], [v[6], v[7]]]})
    return out


def vlm(path, url, model, tile=1600, coords="1000", max_tokens=8192, fmt="json"):
    img = Image.open(path).convert("RGB")
    w, h = img.size
    lines = []
    t0 = time.time()
    tiles = []
    step = tile - tile // 8
    for y in range(0, max(1, h - tile // 8), step) if h > tile else [0]:
        for x in range(0, max(1, w - tile // 8), step) if w > tile else [0]:
            tiles.append((x, y, min(w, x + tile), min(h, y + tile)))
    usage = []
    for box in tiles:
        part = img.crop(box)
        buf = io.BytesIO()
        part.save(buf, "PNG")
        body = {"model": model, "temperature": 0, "max_tokens": max_tokens,
                "messages": [{"role": "user", "content": [
                    {"type": "image_url", "image_url": {"url": "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()}},
                    {"type": "text", "text": "Spotting:" if fmt == "paddle" else VLM_PROMPT}]}]}
        req = urllib.request.Request(url + "/v1/chat/completions", json.dumps(body).encode(), {"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=1800) as r:
            answer = json.load(r)
        usage.append(answer.get("usage"))
        content = answer["choices"][0]["message"].get("content") or ""
        pw, ph = part.size
        for item in (paddle_lines(content) if fmt == "paddle" else parse_json_lines(content)):
            text = item.get("text_content") or item.get("text") or ""
            if "quad" in item:
                quad = [[x * pw / 1000 + box[0], y * ph / 1000 + box[1]] for x, y in item["quad"]]
                lines.append({"text": text, "polygon": quad})
                continue
            b = item.get("bbox_2d") or item.get("bbox")
            if not b or len(b) != 4:
                continue
            x1, y1, x2, y2 = (float(v) for v in b)
            if coords == "1000":
                x1, x2, y1, y2 = x1 * pw / 1000, x2 * pw / 1000, y1 * ph / 1000, y2 * ph / 1000
            x1, x2, y1, y2 = x1 + box[0], x2 + box[0], y1 + box[1], y2 + box[1]
            lines.append({"text": text, "polygon": [[x1, y1], [x2, y1], [x2, y2], [x1, y2]]})
    return dedupe(lines), time.time() - t0, {"tiles": len(tiles), "usage": usage}


def parse_json_lines(content):
    m = re.search(r"\[.*\]", content, re.S)
    if not m:
        return []
    try:
        data = json.loads(m.group(0))
    except json.JSONDecodeError:
        # cut off: keep the complete objects
        data = []
        for obj in re.finditer(r"\{[^{}]*\}", m.group(0)):
            try:
                data.append(json.loads(obj.group(0)))
            except json.JSONDecodeError:
                pass
    return [d for d in data if isinstance(d, dict)]


def dedupe(lines):
    """Tiles overlap: a line read twice keeps its longer reading."""
    out = []
    for l in sorted(lines, key=lambda l: -len(l["text"])):
        if any(iou(l["polygon"], o["polygon"]) > 0.5 for o in out):
            continue
        out.append(l)
    return out


def run(testset, adapter, params, only):
    truth = json.loads((testset / "truth.json").read_text())
    results = {"adapter": adapter, "params": params, "pictures": {}}
    for name, t in truth.items():
        if only and name not in only:
            continue
        path = testset / t["file"]
        try:
            if adapter == "vlm":
                lines, el, info = vlm(path, params.get("url", "http://127.0.0.1:12434"), params["model"],
                                      int(params.get("tile", 1600)), params.get("coords", "1000"), fmt=params.get("fmt", "json"))
            else:
                endpoint = "/v1/ocr" if adapter == "hemera" else "/ocr"
                lines, el, info = ocr_api(path, params.get("url", "http://127.0.0.1:12434"), endpoint, params.get("model"),
                                          json.loads(params.get("options", "{}")))
        except Exception as e:
            print(f"{name}: {e}", file=sys.stderr, flush=True)
            results["pictures"][name] = {"error": str(e)}
            continue
        s = score(t, lines)
        s.update({"seconds": round(el, 3), "kind": t["kind"], "size": [t["width"], t["height"]], "info": info, "read": lines})
        results["pictures"][name] = s
        print(f"{name:26s} {el:7.2f}s  CER {100 * s['cer']:5.1f}%  digits {s['digits'][0]}/{s['digits'][1]}  "
              f"box hits {s['hits']}/{s['lines']} (IoU {s['iou_mean']:.2f})  order {s['order'][0]}/{s['order'][1]}", file=sys.stderr, flush=True)
    return results


# ---- report -----------------------------------------------------------------------------------


def report(files):
    out = []
    for f in files:
        r = json.loads(Path(f).read_text())
        label = r["params"].get("label") or r["params"].get("model") or r["adapter"]
        pics = {k: v for k, v in r["pictures"].items() if "error" not in v}
        scripts, texts = {}, {}
        for v in pics.values():
            for s, (e, n) in v["per_script"].items():
                scripts.setdefault(s, [0, 0])
                scripts[s][0] += e
                scripts[s][1] += n
            for s, (e, n) in v.get("text_per_script", {}).items():
                texts.setdefault(s, [0, 0])
                texts[s][0] += e
                texts[s][1] += n
        d_ok = sum(v["digits"][0] for v in pics.values())
        d_all = sum(v["digits"][1] for v in pics.values())
        hits = sum(v["hits"] for v in pics.values())
        lines = sum(v["lines"] for v in pics.values())
        ious = sum(v["iou_mean"] * v["lines"] for v in pics.values())
        nik = [v for k, v in pics.items() if k.startswith("ktp")]
        out.append(f"### {label}\n")
        out.append("| script | CER | text CER (position-free) |\n|---|---|---|")
        for s in sorted(scripts):
            e, n = scripts[s]
            te, tn = texts.get(s, [0, 0])
            out.append(f"| {s} | {100 * e / max(1, n):.1f}% ({e:.0f}/{n}) | {100 * te / max(1, tn):.1f}% |")
        out.append("")
        out.append(f"- digit strings read exactly: {d_ok}/{d_all}")
        niks = [(k, r) for k, v in pics.items() for r in v["rows"] if re.search(r"\d{16}", r["truth"])]
        out.append("- NIK: " + ", ".join(f"{k}: {'exact' if re.search(r'\d{16}', r['truth']).group(0) in norm(r['read']) else 'wrong (' + r['read'] + ')'}" for k, r in niks))
        out.append(f"- line boxes: {hits}/{lines} found (IoU 0.5 or more), mean IoU {ious / max(1, lines):.2f}")
        o_ok = sum(v.get("order", [0, 0])[0] for v in pics.values())
        o_all = sum(v.get("order", [0, 0])[1] for v in pics.values())
        out.append(f"- reading order: {o_ok}/{o_all} pairs of consecutive lines read in order")
        out.append("")
        out.append("| picture | size | time | CER | digits | boxes | order |\n|---|---|---|---|---|---|---|")
        for k, v in r["pictures"].items():
            if "error" in v:
                out.append(f"| {k} | | error | {v['error'][:60]} | | |")
                continue
            out.append(f"| {k} | {v['size'][0]}x{v['size'][1]} | {v['seconds']:.2f} s | {100 * v['cer']:.1f}% | "
                       f"{v['digits'][0]}/{v['digits'][1]} | {v['hits']}/{v['lines']} | {v.get('order', [0, 0])[0]}/{v.get('order', [0, 0])[1]} |")
        out.append("")
    return "\n".join(out)


if __name__ == "__main__":
    if sys.argv[1] == "run":
        testset, adapter = Path(sys.argv[2]), sys.argv[3]
        params = dict(a.split("=", 1) for a in sys.argv[4:] if "=" in a and not a.startswith("--"))
        only = None
        if "--only" in sys.argv:
            only = set(sys.argv[sys.argv.index("--only") + 1].split(","))
        print(json.dumps(run(testset, adapter, params, only), ensure_ascii=False, indent=1))
    elif sys.argv[1] == "report":
        print(report(sys.argv[2:]))
    elif sys.argv[1] == "rescore":
        # bench.py rescore results.json <testset dir>: the same readings, scored again
        r = json.loads(Path(sys.argv[2]).read_text())
        truth = json.loads((Path(sys.argv[3]) / "truth.json").read_text())
        for name, v in r["pictures"].items():
            if "error" not in v:
                kept = {k: v[k] for k in ("seconds", "kind", "size", "info", "read")}
                v.clear()
                v.update(score(truth[name], kept["read"]))
                v.update(kept)
        print(json.dumps(r, ensure_ascii=False, indent=1))
