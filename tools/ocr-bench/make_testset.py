#!/usr/bin/env python3
"""hemera's OCR test set: generated pictures with known text, and the truth for each.

usage: make_testset.py <out dir>

Everything in these pictures is made up: the ID card, the names, the addresses and every number.
The fonts are the system's Noto fonts, found through fc-match, so the set comes out the same on
any machine that has them. Needs Python 3 with Pillow (built with libraqm, for Arabic, Indic and
vertical text) and NumPy.

Writes <out>/<name>.png (.jpg for photos) and <out>/truth.json:
  {name: {"width", "height", "kind", "lines": [{"text", "polygon", "script", "direction",
          "language", "digits"}]}}
A polygon has four points, clockwise, starting where the line begins on its top side: the
top-left corner of a left-to-right line, the top-right corner of a right-to-left or
top-to-bottom one. They follow every rotation and warp applied to the picture.
"""
import bisect
import json
import math
import re
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONTS = {}
SPECS = {}
COVERAGE = {}
FALLBACK = "Noto Sans"


def font(family, size):
    key = (family, size)
    if key not in FONTS:
        path, index = match(family)
        FONTS[key] = ImageFont.truetype(path, size, index=index, layout_engine=ImageFont.Layout.RAQM)
        SPECS[id(FONTS[key])] = key
    return FONTS[key]


def match(family):
    spec = subprocess.run(["fc-match", "-f", "%{file}|%{index}", family], capture_output=True, text=True, check=True).stdout
    path, index = spec.split("|")
    return path, int(index)


def covers(family, ch):
    """Whether the font has a glyph for ch. Pillow does not fall back to another font by itself."""
    if family not in COVERAGE:
        path, index = match(family)
        cs = subprocess.run(["fc-query", "-f", "%{charset}", path, "-i", str(index)], capture_output=True, text=True, check=True).stdout
        starts, ends = [], []
        for part in cs.split():
            a, _, b = part.partition("-")
            starts.append(int(a, 16))
            ends.append(int(b or a, 16))
        COVERAGE[family] = (starts, ends)
    starts, ends = COVERAGE[family]
    i = bisect.bisect_right(starts, ord(ch)) - 1
    return ch == " " or (i >= 0 and ord(ch) <= ends[i])


def runs(text, family):
    """Splits text into pieces each drawn with one font: the asked-for one, or Noto Sans where it has no glyph."""
    out = []
    for ch in text:
        fam = family if covers(family, ch) else FALLBACK
        if out and (out[-1][0] == fam or ch == " "):
            out[-1][1] += ch
        else:
            out.append([fam, ch])
    return out


def pt(points, dpi=300):
    """A font size in points, as pixels at the given resolution."""
    return round(points * dpi / 72)


class Canvas:
    def __init__(self, w, h, bg=(255, 255, 255), alpha=False):
        self.img = Image.new("RGB", (w, h), bg)
        self.draw = ImageDraw.Draw(self.img)
        self.alpha = Image.new("L", (w, h), 0) if alpha else None  # where an overlay was drawn
        self.lines = []

    def text(self, xy, text, fnt, fill=(0, 0, 0), script="Latn", language=None, direction="ltr", digits=None, record=True,
             stroke=None):
        """Draws one line and records its ink box. Returns the ink box (x0, y0, x1, y1).
        stroke: (width, colour) of an outline around the letters, as subtitles have."""
        kw = {"language": language} if language else {}
        if direction == "ttb":
            pieces = [(fnt, text, dict(kw, direction="ttb"), xy, None)]
        else:
            # horizontal: one piece per font, on a common baseline; right-to-left lines lay the
            # pieces out from the right, each piece shaped in its own direction
            family, size = SPECS[id(fnt)]
            parts = runs(text, family)
            if direction == "rtl":
                parts = parts[::-1]
            base = xy[1] + fnt.getmetrics()[0]
            x = xy[0]
            pieces = []
            for fam, piece in parts:
                f = font(fam, size)
                pkw = dict(kw, direction="rtl" if direction == "rtl" and fam != FALLBACK else "ltr")
                pieces.append((f, piece, pkw, (round(x), base), "ls"))
                x += self.draw.textlength(piece, font=f, **pkw)
        boxes = [self.draw.textbbox(at, piece, font=f, anchor=anchor, **pkw) for f, piece, pkw, at, anchor in pieces]
        x0, y0 = math.floor(min(b[0] for b in boxes)), math.floor(min(b[1] for b in boxes))
        x1, y1 = math.ceil(max(b[2] for b in boxes)), math.ceil(max(b[3] for b in boxes))
        sw = stroke[0] if stroke else 0
        m = 12 + sw
        layer = Image.new("L", (x1 - x0 + 2 * m, y1 - y0 + 2 * m), 0)
        ld = ImageDraw.Draw(layer)
        for f, piece, pkw, (px, py), anchor in pieces:
            ld.text((px - x0 + m, py - y0 + m), piece, font=f, fill=255, anchor=anchor, **pkw)
        outline = layer
        if sw:
            outline = Image.new("L", layer.size, 0)
            od = ImageDraw.Draw(outline)
            for f, piece, pkw, (px, py), anchor in pieces:
                od.text((px - x0 + m, py - y0 + m), piece, font=f, fill=255, anchor=anchor, stroke_width=sw, stroke_fill=255, **pkw)
            self.img.paste(stroke[1], (x0 - m, y0 - m), mask=outline)
        ink = outline.getbbox()
        self.img.paste(fill, (x0 - m, y0 - m), mask=layer)
        if self.alpha is not None:
            self.alpha.paste(255, (x0 - m, y0 - m), mask=outline)
        box = (x0 - m + ink[0], y0 - m + ink[1], x0 - m + ink[2], y0 - m + ink[3])
        if record:
            self.lines.append({"text": text, "polygon": box_poly(box, direction), "script": script,
                               "direction": direction, "language": language, "digits": digits or []})
        return box

    def join(self, n):
        """Records the last n lines drawn as one printed line (a label's colon and its value)."""
        parts, self.lines = self.lines[-n:], self.lines[:-n]
        xs = [p[0] for l in parts for p in l["polygon"]]
        ys = [p[1] for l in parts for p in l["polygon"]]
        self.lines.append(dict(parts[0], text=" ".join(l["text"] for l in parts),
                               polygon=box_poly((min(xs), min(ys), max(xs), max(ys)), parts[0]["direction"]),
                               digits=[d for l in parts for d in l["digits"]]))

    def wrap(self, x, y, width, text, fnt, leading, **kw):
        """Left-aligned paragraph of words separated by spaces. Returns the y after it."""
        words = text.split(" ")
        line = ""
        for w in words:
            trial = (line + " " + w).strip()
            if line and fnt.getlength(trial) > width:
                self.text((x, y), line, fnt, **kw)
                y += leading
                line = w
            else:
                line = trial
        if line:
            self.text((x, y), line, fnt, **kw)
            y += leading
        return y


def box_poly(box, direction):
    x0, y0, x1, y1 = box
    if direction == "ltr":
        return [[x0, y0], [x1, y0], [x1, y1], [x0, y1]]
    return [[x1, y0], [x1, y1], [x0, y1], [x0, y0]]


# ---- geometry ---------------------------------------------------------------------------------

def homography(src, dst):
    a = []
    for (x, y), (u, v) in zip(src, dst):
        a.append([x, y, 1, 0, 0, 0, -u * x, -u * y, u])
        a.append([0, 0, 0, x, y, 1, -v * x, -v * y, v])
    a = np.array(a, dtype=np.float64)
    h = np.linalg.solve(a[:, :8], a[:, 8])
    return np.append(h, 1).reshape(3, 3)


def apply(h, pts):
    out = []
    for x, y in pts:
        u, v, w = h @ np.array([x, y, 1.0])
        out.append([round(u / w, 1), round(v / w, 1)])
    return out


def warp(img, lines, dst_quad, size, background):
    """Puts img onto background so that its corners land on dst_quad (clockwise from top-left)."""
    w, h = img.size
    src = [(0, 0), (w, 0), (w, h), (0, h)]
    hm = homography(src, dst_quad)
    inv = np.linalg.inv(hm)
    coeffs = (inv / inv[2, 2]).flatten()[:8]
    out = background.copy()
    paper = img.transform(size, Image.Transform.PERSPECTIVE, tuple(coeffs), resample=Image.Resampling.BICUBIC)
    mask = Image.new("L", img.size, 255).transform(size, Image.Transform.PERSPECTIVE, tuple(coeffs), resample=Image.Resampling.BILINEAR)
    out.paste(paper, (0, 0), mask)
    moved = [dict(l, polygon=apply(hm, l["polygon"])) for l in lines]
    return out, moved


def rotate(img, lines, quarter_turns_ccw):
    """Rotates by 90, 180 or 270 degrees counter-clockwise, as Image.transpose does."""
    w, h = img.size
    op = {1: Image.Transpose.ROTATE_90, 2: Image.Transpose.ROTATE_180, 3: Image.Transpose.ROTATE_270}[quarter_turns_ccw]
    f = {1: lambda x, y: (y, w - x), 2: lambda x, y: (w - x, h - y), 3: lambda x, y: (h - y, x)}[quarter_turns_ccw]
    moved = [dict(l, polygon=[list(f(x, y)) for x, y in l["polygon"]]) for l in lines]
    return img.transpose(op), moved


def texture(size, rng, base, spread):
    """A blotchy background: something a photo might have behind its subject."""
    w, h = size
    small = (rng.normal(0, 1, (h // 64 + 2, w // 64 + 2, 3)) * spread + np.array(base)).clip(0, 255).astype(np.uint8)
    img = Image.fromarray(small).resize(size, Image.Resampling.BICUBIC)
    noise = rng.normal(0, 6, (h, w, 1))
    return Image.fromarray((np.asarray(img, dtype=np.float32) + noise).clip(0, 255).astype(np.uint8))


def photo_finish(img, rng, light=0.25, noise=5.0, blur=0.8):
    """Uneven light, sensor noise and a little blur."""
    a = np.asarray(img, dtype=np.float32)
    h, w = a.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w]
    gx, gy = rng.uniform(-1, 1, 2)
    shade = 1 - light * 0.5 * (1 + (gx * (xx / w - 0.5) + gy * (yy / h - 0.5)))
    a = a * shade[..., None] + rng.normal(0, noise, a.shape)
    return Image.fromarray(a.clip(0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(blur))


# ---- the pictures -----------------------------------------------------------------------------

ID_PARAS = [
    "Rapat koordinasi bulanan diadakan pada hari Selasa, 14 Oktober 2026, pukul 09.30 WIB di ruang serbaguna lantai tiga.",
    "Agenda utama adalah evaluasi anggaran kuartal ketiga, rencana pelatihan karyawan baru, dan perbaikan sistem pencatatan arsip.",
    "Bagian keuangan melaporkan realisasi anggaran sebesar Rp 1.284.750.000 atau 87,4% dari target tahunan.",
    "Pengadaan laptop untuk tim lapangan ditunda sampai Januari karena harga naik sekitar 12% dibandingkan bulan lalu.",
    "Tim teknologi informasi akan memindahkan server surel ke pusat data baru pada akhir pekan tanggal 25-26 Oktober.",
    "Selama pemindahan, layanan surel mungkin tidak dapat diakses selama kurang lebih empat jam.",
    "Seluruh kepala bagian diminta mengirimkan daftar kebutuhan pelatihan paling lambat 31 Oktober 2026.",
    "Formulir dapat diunduh dari portal internal pada menu Dokumen, lalu Personalia, lalu Pelatihan.",
    "Untuk pertanyaan, hubungi Sdri. Maya Lestari di nomor 0812-3456-7809 atau pesawat 214.",
    "Notulen ini dibuat oleh sekretariat dan disahkan oleh ketua rapat pada hari yang sama.",
]

EN_PARAS = [
    "The town library will stay open until nine in the evening from the first of December, two hours later than it does now.",
    "The change follows a survey in which 1,482 residents took part; almost two thirds asked for later opening on weekdays.",
    "Three new study rooms on the second floor can be booked for up to four hours at a time, free of charge.",
    "Children's reading sessions move from Saturday morning to Sunday afternoon, starting at 2:30 pm in the garden room.",
    "The library's budget for new books rises by 18 percent next year, to a total of 245,000 euros.",
    "About a third of that goes to audiobooks and e-books, which were borrowed 37,906 times last year.",
    "Volunteers who help with the evening shifts receive a yearly membership and a small allowance for travel.",
    "Anyone interested can write to the head librarian, Ms. Clara Jansen, or call 0570 61 42 18 on weekdays.",
    "The renovation of the reading hall, closed since March, is expected to finish by the end of February.",
    "Until then, newspapers and magazines are available at the information desk near the main entrance.",
]


def page_a4(rng):
    """A dense single-column page, as a 300 dpi scan: 10 pt body, a heading, a footer."""
    c = Canvas(2480, 3508)
    c.text((236, 236), "NOTULEN RAPAT KOORDINASI BULANAN", font("Noto Sans:bold", pt(14)), language="id")
    c.text((236, 330), "Nomor: 117/SEK/X/2026", font("Noto Sans", pt(10)), language="id", digits=["117", "2026"])
    y = 470
    body = font("Noto Serif", pt(10))
    for p in ID_PARAS * 2:
        if y > 3000:
            break
        y = c.wrap(236, y, 2008, p, body, pt(10) + 18, language="id")
        y += 36
    c.text((236, 3300), "Halaman 1 dari 2", font("Noto Sans", pt(8)), language="id", digits=["1", "2"])
    img = c.img.rotate(0.4, resample=Image.Resampling.BICUBIC, fillcolor=(255, 255, 255))
    # the 0.4 degree turn of a scanner: carry it to the truth too
    lines = turn_lines(c.lines, 0.4, c.img.size)
    img = Image.fromarray((np.asarray(img, dtype=np.float32) + rng.normal(0, 4, (3508, 2480, 3))).clip(0, 255).astype(np.uint8))
    return img, lines


def turn_lines(lines, degrees, size):
    """The truth for Image.rotate(degrees) about the centre, counter-clockwise, same size."""
    w, h = size
    t = math.radians(degrees)
    cx, cy = w / 2, h / 2

    def f(x, y):
        dx, dy = x - cx, y - cy
        return [round(cx + dx * math.cos(t) + dy * math.sin(t), 1), round(cy - dx * math.sin(t) + dy * math.cos(t), 1)]
    return [dict(l, polygon=[f(x, y) for x, y in l["polygon"]]) for l in lines]


def article_2col(rng):
    """A two-column article on A4 at 300 dpi, with a footnote in small print."""
    c = Canvas(2480, 3508)
    c.text((236, 220), "Library Extends Its Opening Hours", font("Noto Serif:bold", pt(22)), language="en")
    c.text((236, 360), "By the editorial team, 19 November 2026", font("Noto Sans:italic", pt(9)), language="en", digits=["19", "2026"])
    body = font("Noto Serif", pt(9.5))
    lead = pt(9.5) + 16
    col_w, gutter = 940, 128
    ys = [500, 500]
    paras = EN_PARAS + ID_PARAS[:4]
    for i, p in enumerate(paras):
        col = 0 if i < 7 else 1
        lang = "en" if p in EN_PARAS else "id"
        ys[col] = c.wrap(236 + col * (col_w + gutter), ys[col], col_w, p, body, lead, language=lang) + 30
    c.text((236, 3290), "* Figures from the library's annual report 2025, page 14. Survey margin of error 2.5%.", font("Noto Sans", pt(6.5)), language="en", digits=["2025", "14", "2.5"])
    return c.img, c.lines


def small_print(rng):
    """Terms in 7, 6, 5 and 4 point at 300 dpi: what has to survive at full resolution."""
    c = Canvas(2480, 1400)
    y = 120
    rows = [
        (7, "Garansi berlaku 12 bulan sejak tanggal pembelian 03/08/2026 dengan menunjukkan nota asli no. 77104."),
        (6, "Klaim diajukan ke pusat servis resmi; biaya kirim ditanggung pembeli. Layanan pelanggan: 1500-889 (24 jam)."),
        (5, "Kode produk SKU-4417-BX-09, nomor seri 8C1F-22A0-5E91, diproduksi oleh PT Contoh Elektronik Nusantara."),
        (4, "Ketentuan lengkap: www.contoh-elektronik.example/garansi, revisi 2026.4, berlaku mulai 1 April 2026."),
    ]
    for size, text in rows:
        digits = re.findall(r"\d[\d/.\-]*\d|\d", text)
        c.text((120, y), text, font("Noto Sans", pt(size)), language="id", digits=digits)
        y += pt(size) * 2 + 60
    return c.img, c.lines


def ktp(rng):
    """A made-up Indonesian ID card (KTP layout): invented NIK, name and address."""
    c = Canvas(1712, 1080, (176, 214, 235))
    a = np.asarray(c.img, dtype=np.float32)
    yy, xx = np.mgrid[0:1080, 0:1712]
    a += (np.sin(xx / 37.0) * np.cos(yy / 23.0) * 6)[..., None]
    c.img.paste(Image.fromarray(a.clip(0, 255).astype(np.uint8)))
    c.draw = ImageDraw.Draw(c.img)
    nik = "".join(str(rng.integers(0, 10)) for _ in range(16))
    bold = font("Noto Sans:bold", 46)
    c.text((560, 40), "PROVINSI DKI JAKARTA", bold, language="id")
    c.text((520, 100), "KOTA JAKARTA SELATAN", bold, language="id")
    c.text((60, 190), "NIK", font("Noto Sans Mono:bold", 52), language="id")
    c.text((250, 190), ":", font("Noto Sans Mono:bold", 52), language="id")
    c.text((290, 190), nik, font("Noto Sans Mono:bold", 58), language="id", digits=[nik])
    c.join(2)
    label = font("Noto Sans", 34)
    rows = [("Nama", "RINA KARTIKA SARI"), ("Tempat/Tgl Lahir", "BANDUNG, 29-07-1988"), ("Jenis Kelamin", "PEREMPUAN"),
            ("Alamat", "JL. MELATI RAYA NO. 17"), ("RT/RW", "004/011"), ("Kel/Desa", "CIPETE UTARA"), ("Kecamatan", "KEBAYORAN BARU"),
            ("Agama", "ISLAM"), ("Status Perkawinan", "KAWIN"), ("Pekerjaan", "KARYAWAN SWASTA"), ("Kewarganegaraan", "WNI"),
            ("Berlaku Hingga", "SEUMUR HIDUP")]
    y = 300
    for k, v in rows:
        indent = 90 if k in ("RT/RW", "Kel/Desa", "Kecamatan") else 60
        c.text((indent, y), k, label, language="id")
        c.text((410, y), ":", label, language="id")
        digits = re.findall(r"\d+(?:[-/]\d+)*", v)
        c.text((440, y), v, label, language="id", digits=digits)
        c.join(2)
        if k == "Jenis Kelamin":  # on the same row, read after it
            c.text((860, 416), "Gol. Darah : O", label, language="id")
        y += 58
    c.draw.rectangle((1300, 190, 1640, 620), fill=(214, 92, 72))
    c.text((1330, 680), "JAKARTA SELATAN", font("Noto Sans", 30), language="id")
    c.text((1385, 724), "12-03-2019", font("Noto Sans", 30), language="id", digits=["12-03-2019"])
    return c.img, c.lines, nik


def ktp_photo(rng):
    card, lines, nik = ktp(rng)
    bg = texture((4000, 3000), rng, (120, 100, 80), 30)
    quad = [(820, 760), (3150, 610), (3260, 2100), (900, 2320)]
    img, lines = warp(card, lines, quad, (4000, 3000), bg)
    return photo_finish(img, rng), lines


RECEIPT = [
    ("TOKO SERBA ADA SEJAHTERA", True),
    ("Jl. Kenanga No. 21, Bogor", False),
    ("NPWP: 01.234.567.8-404.000", False),
    ("No: 2026111900374  Kasir: DEWI", False),
    ("19-11-2026 14:32", False),
    ("Beras Pandan 5kg   1 x 74.500   74.500", False),
    ("Minyak Goreng 2L   2 x 36.900   73.800", False),
    ("Gula Pasir 1kg     1 x 17.250   17.250", False),
    ("Telur Ayam 1kg     1 x 28.900   28.900", False),
    ("Teh Celup 25s      3 x  6.200   18.600", False),
    ("Sabun Mandi        2 x  4.750    9.500", False),
    ("TOTAL                          222.550", True),
    ("TUNAI                          250.000", False),
    ("KEMBALI                         27.450", False),
    ("Terima kasih atas kunjungan Anda", False),
]


def receipt(rng):
    c = Canvas(1100, 1500, (250, 249, 244))
    y = 60
    for text, strong in RECEIPT:
        f = font("Noto Sans Mono:bold" if strong else "Noto Sans Mono", 34)
        digits = re.findall(r"\d[\d.:\-/]*\d|\d", text)
        c.text((50, y), text, f, language="id", digits=digits)
        y += 58 if not strong else 70
        if text.startswith("19-11") or text.startswith("Sabun") or text.startswith("KEMBALI"):
            c.draw.line((50, y + 4, 1050, y + 4), fill=(60, 60, 60), width=2)
            y += 24
    return c.img, c.lines


def receipt_photo(rng):
    img, lines = receipt(rng)
    bg = texture((2400, 3200), rng, (70, 75, 85), 20)
    quad = [(560, 420), (1830, 520), (1760, 2980), (470, 2850)]
    img, lines = warp(img, lines, quad, (2400, 3200), bg)
    return photo_finish(img, rng, light=0.35), lines


def screenshot(rng, dark):
    bg, fg, dim, bubble, mine = ((28, 28, 30), (242, 242, 247), (152, 152, 160), (44, 44, 46), (10, 90, 200)) if dark else \
        ((255, 255, 255), (12, 12, 14), (110, 110, 118), (233, 233, 237), (0, 122, 255))
    c = Canvas(1170, 2532, bg)
    big, mid, small = font("Noto Sans:bold", 64), font("Noto Sans", 46), font("Noto Sans", 36)
    c.text((90, 52), "09:41", font("Noto Sans:bold", 44), fill=fg, digits=["09:41"])
    c.text((60, 210), "Pesan", big, fill=fg, language="id")
    c.text((60, 320), "Bank Contoh", font("Noto Sans:bold", 48), fill=fg)
    y = 430
    msgs = [
        (False, "Kode verifikasi Anda: 482913. Jangan berikan kode ini kepada siapa pun.", ["482913"]),
        (False, "Transfer Rp 2.750.000 ke rekening 1234-5678-90 berhasil pada 18/11 pukul 20:14.", ["2.750.000", "1234-5678-90", "18/11", "20:14"]),
        (True, "Oke, terima kasih. Saya cek dulu ya.", []),
        (False, "Saldo akhir Anda Rp 14.982.350. Info lebih lanjut hubungi 1500-123.", ["14.982.350", "1500-123"]),
        (True, "Meeting moved to 3 pm, see you at the lobby.", ["3"]),
    ]
    for me, text, digits in msgs:
        width = 760
        words = text.split(" ")
        rows, line = [], ""
        for w in words:
            trial = (line + " " + w).strip()
            if line and mid.getlength(trial) > width:
                rows.append(line)
                line = w
            else:
                line = trial
        rows.append(line)
        h = len(rows) * 64 + 50
        x0 = 1170 - 60 - width - 60 if me else 60
        c.draw.rounded_rectangle((x0, y, x0 + width + 60, y + h), radius=36, fill=mine if me else bubble)
        ty = y + 22
        for r in rows:
            d = [g for g in digits if g in r]
            c.text((x0 + 30, ty), r, mid, fill=(255, 255, 255) if me else fg, language="en" if text.startswith("Meeting") else "id", digits=d)
            ty += 64
        y += h + 40
    c.text((60, y + 40), "Kemarin 21.07", small, fill=dim, language="id", digits=["21.07"])
    y += 140
    for k, v in [("Wi-Fi", "Kantor-5G"), ("Baterai", "87%"), ("Penyimpanan", "112,4 GB dari 256 GB"), ("Versi perangkat lunak", "14.2.1")]:
        c.text((60, y), k, mid, fill=fg, language="id")
        vb = c.draw.textbbox((0, 0), v, font=mid)
        c.text((1110 - (vb[2] - vb[0]), y), v, mid, fill=dim, language="id", digits=re.findall(r"\d[\d.,]*\d|\d", v))
        y += 110
    return c.img, c.lines


SCRIPTS = {
    "ja_horizontal": ("Noto Sans CJK JP", "Jpan", "ja", "ltr", [
        "本日の会議は午後2時から第3会議室で行います。",
        "資料は前日までに共有フォルダへ保存してください。",
        "交通費の精算は月末締め、翌月10日払いです。",
        "お問い合わせは内線421番の佐藤までお願いします。",
        "東京都千代田区丸の内2丁目7番3号",
        "電話：03-1234-5678 受付時間 9:00〜17:30",
    ]),
    "zh_simplified": ("Noto Sans CJK SC", "Hani", "zh-Hans", "ltr", [
        "欢迎参加2026年秋季读书分享会。",
        "活动时间：11月8日（星期六）下午两点至五点。",
        "地点：市图书馆三楼多功能厅。",
        "请提前十分钟到场签到，现场提供茶水。",
        "报名电话：010-8765-4321",
        "名额有限，额满即止。",
    ]),
    "zh_traditional": ("Noto Sans CJK TC", "Hani", "zh-Hant", "ltr", [
        "本店營業時間為每日上午十點至晚上九點。",
        "週一公休，國定假日照常營業。",
        "消費滿新台幣500元即可免費停車兩小時。",
        "地址：台北市大安區忠孝東路四段188號",
        "訂位專線：(02) 2771-3456",
    ]),
    "ko": ("Noto Sans CJK KR", "Hang", "ko", "ltr", [
        "오늘 회의는 오후 3시에 2층 회의실에서 시작합니다.",
        "발표 자료는 회의 전날까지 공유 폴더에 올려 주세요.",
        "주차는 지하 1층부터 3층까지 이용할 수 있습니다.",
        "문의: 김민수 대리 (내선 3021)",
        "서울특별시 중구 세종대로 110",
    ]),
    "ar": ("Noto Naskh Arabic", "Arab", "ar", "rtl", [
        "مرحبا بكم في المعرض الدولي للكتاب",
        "يفتح المعرض أبوابه يوميا من العاشرة صباحا حتى التاسعة مساء",
        "سعر تذكرة الدخول 15 درهما للكبار",
        "الدخول مجاني للأطفال دون سن الثانية عشرة",
        "للاستفسار يرجى الاتصال على الرقم 0501234567",
    ]),
    "th": ("Noto Sans Thai", "Thai", "th", "ltr", [
        "ยินดีต้อนรับสู่ร้านกาแฟของเรา",
        "เปิดทุกวันตั้งแต่เวลา 07.00 ถึง 20.00 น.",
        "กาแฟเย็นแก้วละ 55 บาท",
        "สมาชิกรับส่วนลด 10 เปอร์เซ็นต์",
        "โทร 02-123-4567",
    ]),
    "hi": ("Noto Sans Devanagari", "Deva", "hi", "ltr", [
        "हमारे पुस्तकालय में आपका स्वागत है।",
        "पुस्तकालय सोमवार से शनिवार तक सुबह 9 बजे से शाम 6 बजे तक खुला रहता है।",
        "किताबें चौदह दिनों के लिए उधार ली जा सकती हैं।",
        "सदस्यता शुल्क 200 रुपये प्रति वर्ष है।",
        "संपर्क: 011-2345-6789",
    ]),
    "ru": ("Noto Sans", "Cyrl", "ru", "ltr", [
        "Добро пожаловать в городскую библиотеку.",
        "Библиотека работает с понедельника по субботу с 9:00 до 19:00.",
        "Книги выдаются на срок до 14 дней.",
        "Читательский билет стоит 150 рублей в год.",
        "Телефон для справок: +7 (495) 123-45-67",
    ]),
}


def script_page(name, rng):
    family, script, lang, direction, rows = SCRIPTS[name]
    return line_page([(r, script, lang, direction, family, re.findall(r"\d[\d.:\-]*\d|\d", r)) for r in rows], 52)


def line_page(rows, size):
    """One line under the other, right-aligned for right-to-left text; as wide as the longest line."""
    probe = Canvas(10, 10)
    widths = [probe.text((0, 0), text, font(family, size), language=lang, direction=d, record=False) for text, _, lang, d, family, _ in rows]
    width = max(b[2] - b[0] for b in widths) + 160
    c = Canvas(width, 120 + 110 * len(rows))
    y = 60
    for (text, script, lang, direction, family, digits), b in zip(rows, widths):
        x = width - 80 - (b[2] - b[0]) if direction == "rtl" else 80
        c.text((x, y), text, font(family, size), script=script, language=lang, direction=direction, digits=digits)
        y += 110
    return c.img, c.lines


JA_VERTICAL = [
    "春の朝、川沿いの道を歩くと、",
    "桜の花びらが静かに水面へ落ちていく。",
    "遠くで子どもたちの笑い声が聞こえ、",
    "町はゆっくりと目を覚ます。",
    "私は小さな喫茶店に入り、",
    "温かいお茶を一杯注文した。",
    "窓の外を自転車が通り過ぎていった。",
]


def ja_vertical(rng):
    """Vertical Japanese: columns read top to bottom, right to left."""
    c = Canvas(1400, 1300, (252, 250, 244))
    f = font("Noto Serif CJK JP", 58)
    x = 1260
    for col in JA_VERTICAL:
        c.text((x, 80), col, f, script="Jpan", language="ja", direction="ttb")
        x -= 120
    return c.img, c.lines


MIXED = [
    ("Rapat dengan 김민수 jam 15.00", "Latn", "id", "ltr", "Noto Sans CJK KR", ["15.00"]),
    ("会議ID: MTG-2026-0457（Zoom）", "Jpan", "ja", "ltr", "Noto Sans CJK JP", ["2026-0457"]),
    ("Harga promo: ¥1,980 → Rp 210.000", "Latn", "id", "ltr", "Noto Sans CJK JP", ["1,980", "210.000"]),
    ("Москва — Jakarta: рейс GA 851", "Cyrl", "ru", "ltr", "Noto Sans", ["851"]),
    ("ราคา 1,250 THB (Rp 560.000)", "Thai", "th", "ltr", "Noto Sans Thai", ["1,250", "560.000"]),
    ("मीटिंग का लिंक: meet.example.com/abc-defg", "Deva", "hi", "ltr", "Noto Sans Devanagari", []),
    ("الإصدار 2.4.1 من تطبيق Hemera", "Arab", "ar", "rtl", "Noto Naskh Arabic", ["2.4.1"]),
]


def mixed(rng):
    return line_page(MIXED, 50)


def street_sign(rng, low_contrast=False):
    """A 4000x3000 photo: a road sign and a shop sign in perspective, and a small plate."""
    sign = Canvas(2000, 900, (0, 104, 71) if not low_contrast else (96, 128, 112))
    white = (255, 255, 255) if not low_contrast else (170, 196, 182)
    sign.draw.rounded_rectangle((20, 20, 1980, 880), radius=40, outline=white, width=12)
    sign.text((90, 80), "Jl. Jenderal Sudirman", font("Noto Sans:bold", 150), fill=white, language="id")
    sign.text((90, 330), "Bundaran HI", font("Noto Sans:bold", 120), fill=white, language="id")
    sign.text((1500, 330), "2 km", font("Noto Sans:bold", 120), fill=white, language="id", digits=["2"])
    sign.text((90, 560), "Blok M", font("Noto Sans:bold", 120), fill=white, language="id")
    sign.text((1500, 560), "8 km", font("Noto Sans:bold", 120), fill=white, language="id", digits=["8"])
    shop = Canvas(1400, 360, (250, 250, 250) if not low_contrast else (210, 210, 205))
    shop.text((60, 50), "APOTEK SEHAT 24 JAM", font("Noto Sans:bold", 110), fill=(200, 20, 30) if not low_contrast else (180, 120, 120), language="id", digits=["24"])
    shop.text((60, 220), "Buka setiap hari, termasuk hari libur", font("Noto Sans", 58), fill=(40, 40, 40) if not low_contrast else (150, 150, 145), language="id")
    plate = Canvas(520, 300, (230, 230, 225))
    plate.text((40, 40), "No. 128", font("Noto Sans:bold", 90), fill=(30, 30, 30), language="id", digits=["128"])
    plate.text((40, 180), "Parkir 06.00-21.00", font("Noto Sans", 50), fill=(30, 30, 30), language="id", digits=["06.00-21.00"])
    bg = texture((4000, 3000), rng, (130, 150, 170) if not low_contrast else (150, 155, 160), 35)
    img, lines = warp(sign.img, sign.lines, [(420, 380), (2350, 520), (2300, 1380), (380, 1340)], (4000, 3000), bg)
    img, l2 = warp(shop.img, shop.lines, [(2500, 1700), (3800, 1560), (3820, 1940), (2520, 2060)], (4000, 3000), img)
    img, l3 = warp(plate.img, plate.lines, [(900, 2200), (1310, 2230), (1300, 2450), (890, 2420)], (4000, 3000), img)
    img = photo_finish(img, rng, light=0.3 if not low_contrast else 0.15, noise=6, blur=1.0)
    return img, lines + l2 + l3


def slanted(rng):
    """Part of the article, photographed at an angle: slanted lines and perspective."""
    img, lines = article_2col(rng)
    crop = img.crop((0, 0, 2480, 1700))
    lines = [l for l in lines if max(y for _, y in l["polygon"]) < 1700]
    bg = texture((3600, 2700), rng, (90, 85, 80), 25)
    img, lines = warp(crop, lines, [(520, 300), (3150, 620), (2980, 2420), (380, 2150)], (3600, 2700), bg)
    return photo_finish(img, rng), lines


def low_contrast_page(rng):
    c = Canvas(2000, 1300, (200, 200, 196))
    y = 100
    for p in ID_PARAS[:5]:
        y = c.wrap(100, y, 1800, p, font("Noto Sans", 40), 62, fill=(150, 150, 146), language="id") + 30
    return c.img, c.lines


# ---- video frames -----------------------------------------------------------------------------
# Paused video, as SpatiReel sends it: a street scene the camera pans across (its signs blurred by
# the motion), a news ticker scrolling, burned-in subtitles and a channel's corner text over it;
# encoded with H.264 or HEVC at an ordinary bitrate and one frame grabbed back from the stream.

VIDEOS = [
    # name, size, encoder, bitrate, pan in px per frame at 1080p, subtitle language, ticker
    ("video_1080_h264_id", (1920, 1080), "libx264", "5M", 20, "id", True),
    ("video_1080_hevc_ja", (1920, 1080), "libx265", "3M", 16, "ja", False),
    ("video_1080_h264_en_low", (1920, 1080), "libx264", "2M", 36, "en", True),
    ("video_1080_hevc_ar", (1920, 1080), "libx265", "2500k", 20, "ar", False),
    ("video_1440_h264_th", (2560, 1440), "libx264", "8M", 24, "th", False),
    ("video_4k_h264_zh", (3840, 2160), "libx264", "20M", 20, "zh", True),
    ("video_4k_hevc_ko", (3840, 2160), "libx265", "12M", 20, "ko", False),
]

SUBTITLES = {  # script, direction, font, two lines
    "id": ("Latn", "ltr", "Noto Sans:bold", ["Kita harus sampai di stasiun sebelum jam 07.15,", "kalau tidak, keretanya sudah berangkat."]),
    "en": ("Latn", "ltr", "Noto Sans:bold", ["I told you the meeting moved to room 4B.", "Nobody reads that email on a Friday."]),
    "ja": ("Jpan", "ltr", "Noto Sans CJK JP:bold", ["明日の朝、駅の東口で待っています。", "遅れたら電話してね。"]),
    "zh": ("Hani", "ltr", "Noto Sans CJK SC:bold", ["我们明天早上八点在车站见面。", "别忘了带上你的护照。"]),
    "ko": ("Hang", "ltr", "Noto Sans CJK KR:bold", ["내일 아침 아홉 시에 역 앞에서 만나요.", "늦으면 꼭 전화해 주세요."]),
    "ar": ("Arab", "rtl", "Noto Sans Arabic:bold", ["سنلتقي غدا أمام المحطة في الساعة التاسعة.", "لا تنس أن تحضر جواز سفرك."]),
    "th": ("Thai", "ltr", "Noto Sans Thai:bold", ["พรุ่งนี้เราจะเจอกันที่สถานีตอนเก้าโมงเช้า", "อย่าลืมเอาหนังสือเดินทางมาด้วยนะ"]),
}

SIGNS = {  # (text, script, direction, font)
    "id": [("TOKO ROTI MAWAR", "Latn", "ltr", "Noto Sans:bold"), ("Jl. Kebon Sirih No. 45", "Latn", "ltr", "Noto Sans")],
    "en": [("PHARMACY", "Latn", "ltr", "Noto Sans:bold"), ("OPEN 24 HOURS", "Latn", "ltr", "Noto Sans")],
    "ja": [("駅前通り", "Jpan", "ltr", "Noto Sans CJK JP:bold"), ("コンビニ 24時間", "Jpan", "ltr", "Noto Sans CJK JP")],
    "zh": [("便利店", "Hani", "ltr", "Noto Sans CJK SC:bold"), ("营业时间 7:00-23:00", "Hani", "ltr", "Noto Sans CJK SC")],
    "ko": [("편의점", "Hang", "ltr", "Noto Sans CJK KR:bold"), ("OPEN 24H", "Latn", "ltr", "Noto Sans:bold")],
    "ar": [("صيدلية النور", "Arab", "rtl", "Noto Sans Arabic:bold"), ("Pharmacy", "Latn", "ltr", "Noto Sans")],
    "th": [("ร้านกาแฟ", "Thai", "ltr", "Noto Sans Thai:bold"), ("COFFEE 45 THB", "Latn", "ltr", "Noto Sans:bold")],
}

TICKERS = {
    "id": "BERITA TERKINI: Banjir di 12 kelurahan, warga diminta waspada",
    "en": "BREAKING: Rail services resume at 18:30 after a signal fault",
    "zh": "最新消息：地铁3号线今晚延长运营至凌晨1点",
}


def street_world(rng, w, h):
    """Sky, a row of buildings with windows, a road: something to pan across."""
    a = np.zeros((h, w, 3), np.float32)
    a[:] = np.linspace([120, 165, 215], [200, 215, 230], h)[:, None, :]
    x = 0
    while x < w:
        bw = int(rng.integers(h // 4, h // 2))
        top = int(h * rng.uniform(0.08, 0.35))
        colour = rng.integers(60, 180, 3)
        a[top:int(h * 0.78), x:x + bw] = colour
        for wy in range(top + h // 30, int(h * 0.7), h // 12):
            for wx in range(x + h // 40, x + bw - h // 30, h // 14):
                a[wy:wy + h // 24, wx:wx + h // 30] = colour * 0.55 + rng.uniform(0, 30)
        x += bw + int(rng.integers(0, h // 20))
    a[int(h * 0.78):] = (70, 70, 72)
    dash = h // 10
    for x in range(0, w, 2 * dash):  # the lane's dashes
        a[int(h * 0.885):int(h * 0.885) + max(2, h // 150), x:x + dash] = 230
    a += rng.normal(0, 3, (h, w, 1))
    return a.clip(0, 255)


def video_frame(rng, out, name, size, encoder, bitrate, pan, lang, ticker):
    w, h = size
    k = h / 1080
    frames, grab, subsamples = 40, 34, 5
    pan = pan * k
    shutter = [0.5 * i / (subsamples - 1) for i in range(subsamples)]  # a 180 degree shutter
    mid = grab + 0.25
    lines = []

    # the scene, with its signs placed to be in view in the grabbed frame
    world = Canvas(int(w + pan * frames + 64), h)
    world.img = Image.fromarray(street_world(rng, world.img.size[0], h).astype(np.uint8))
    world.draw = ImageDraw.Draw(world.img)
    off = pan * mid
    for (text, script, direction, family), (fx, fy) in zip(SIGNS[lang], [(0.08, 0.22), (0.58, 0.42)]):
        f = font(family, round(64 * k))
        probe = Canvas(10, 10).text((0, 0), text, f, language=lang, direction=direction, record=False)
        tw, th = probe[2] - probe[0], probe[3] - probe[1]
        x, y = round(fx * w + off), round(fy * h)
        pad = round(24 * k)
        world.draw.rectangle((x - pad, y - pad, x + tw + pad, y + th + pad * 2), fill=tuple(int(v) for v in rng.integers(150, 240, 3)))
        world.text((x - probe[0], y - probe[1]), text, f, fill=(20, 20, 30), script=script, language=lang, direction=direction)
    for l in world.lines:
        lines.append(dict(l, polygon=[[x - off, y] for x, y in l["polygon"]]))
    scene = np.asarray(world.img, np.float32)

    # the ticker, scrolling the other way
    band = None
    if ticker:
        bh = round(h * 0.075)
        speed = 6 * k
        strip = Canvas(int(w + speed * frames + 64), bh, (25, 35, 95))
        f = font("Noto Sans CJK SC:bold" if lang == "zh" else "Noto Sans:bold", round(bh * 0.55))
        s_off = speed * mid
        probe = Canvas(10, 10).text((0, 0), TICKERS[lang], f, language=lang, record=False)
        strip.text((round(0.04 * w + s_off) - probe[0], (bh - (probe[3] - probe[1])) // 2 - probe[1]), TICKERS[lang], f,
                   fill=(255, 255, 255), script="Hani" if lang == "zh" else "Latn", language=lang,
                   digits=re.findall(r"\d+(?::\d+)?", TICKERS[lang]))
        by0 = h - bh - round(h * 0.03)
        for l in strip.lines:
            lines.append(dict(l, polygon=[[x - s_off, y + by0] for x, y in l["polygon"]]))
        band = (by0, np.asarray(strip.img, np.float32), speed)

    # what the broadcaster burns in: subtitles, the channel's name, the time
    over = Canvas(w, h, (0, 0, 0), alpha=True)
    script, direction, family, rows = SUBTITLES[lang]
    f = font(family, round(52 * k))
    bottom = (band[0] if band else h) - round(h * 0.06)
    boxes = [Canvas(10, 10).text((0, 0), t, f, language=lang, direction=direction, record=False) for t in rows]
    step = round(f.size * 1.35)
    for i, (t, b) in enumerate(zip(rows, boxes)):
        y = bottom - (len(rows) - i) * step
        over.text(((w - (b[2] - b[0])) // 2 - b[0], y), t, f, fill=(255, 255, 255), script=script, language=lang,
                  direction=direction, stroke=(max(2, round(4 * k)), (0, 0, 0)), digits=re.findall(r"\d+(?:[.:]\d+)?", t))
    corner = font("Noto Sans:bold", round(34 * k))
    over.text((round(0.03 * w), round(0.04 * h)), "KANAL 7 HD", corner, fill=(255, 255, 255), language="id",
              stroke=(max(1, round(2 * k)), (0, 0, 0)))
    clock = Canvas(10, 10).text((0, 0), "20:45", corner, record=False)
    over.text((round(0.97 * w) - clock[2], round(0.04 * h)), "20:45", corner, fill=(255, 255, 255), language="id",
              stroke=(max(1, round(2 * k)), (0, 0, 0)), digits=["20:45"])
    lines += over.lines
    alpha = np.asarray(over.alpha, np.float32)[..., None] / 255
    ink = np.asarray(over.img, np.float32)

    video = out / (name + ".mp4")
    cmd = ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-r", "30", "-i", "-",
           "-c:v", encoder, "-preset", "medium", "-b:v", bitrate, "-maxrate", bitrate, "-bufsize", bitrate, "-g", "60",
           "-pix_fmt", "yuv420p"]
    if encoder == "libx265":
        cmd += ["-x265-params", "log-level=error"]
    enc = subprocess.Popen(cmd + [str(video)], stdin=subprocess.PIPE)
    for t in range(frames):
        frame = sum(scene[:, round(pan * (t + s)):round(pan * (t + s)) + w] for s in shutter) / subsamples
        if band:
            by0, sa, speed = band
            frame[by0:by0 + sa.shape[0]] = sum(sa[:, round(speed * (t + s)):round(speed * (t + s)) + w] for s in shutter) / subsamples
        frame = frame * (1 - alpha) + ink * alpha
        enc.stdin.write(frame.clip(0, 255).astype(np.uint8).tobytes())
    enc.stdin.close()
    if enc.wait() != 0:
        raise RuntimeError("ffmpeg could not encode " + name)
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(video), "-vf", f"select=eq(n\\,{grab})", "-fps_mode", "passthrough",
                          "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], capture_output=True, check=True).stdout
    video.unlink()
    img = Image.fromarray(np.frombuffer(raw, np.uint8).reshape(h, w, 3))
    for l in lines:
        assert all(0 <= x <= w and 0 <= y <= h for x, y in l["polygon"]), (name, l["text"])
    # as a viewer reads it: from the top, left to right along a row
    lines.sort(key=lambda l: (round(min(p[1] for p in l["polygon"]) / (0.06 * h)), min(p[0] for p in l["polygon"])))
    return img, lines


def build(out):
    out.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(20261119)
    items = {}

    def save(name, kind, img, lines, jpg=False):
        path = out / (name + (".jpg" if jpg else ".png"))
        if jpg:
            img.save(path, quality=88)
        else:
            img.save(path, optimize=False, compress_level=3)
        for l in lines:
            l["polygon"] = [[float(x), float(y)] for x, y in l["polygon"]]
        items[name] = {"file": path.name, "kind": kind, "width": img.size[0], "height": img.size[1], "lines": lines}
        print(f"{name}: {img.size[0]}x{img.size[1]}, {len(lines)} lines", flush=True)

    img, lines = screenshot(rng, dark=False)
    save("screenshot_light", "screenshot", img, lines)
    img, lines = screenshot(rng, dark=True)
    save("screenshot_dark", "screenshot", img, lines)
    save("page_a4", "page", *page_a4(rng))
    save("article_2col", "page", *article_2col(rng))
    save("small_print", "page", *small_print(rng))
    card, lines, _ = ktp(rng)
    save("ktp_card", "card", card, lines)
    save("ktp_photo", "photo", *ktp_photo(rng), jpg=True)
    img, lines = receipt(rng)
    save("receipt", "receipt", img, lines)
    save("receipt_photo", "photo", *receipt_photo(rng), jpg=True)
    for name in SCRIPTS:
        save(name, "script", *script_page(name, rng))
    save("ja_vertical", "script", *ja_vertical(rng))
    save("mixed_scripts", "script", *mixed(rng))
    save("street_sign", "photo", *street_sign(rng), jpg=True)
    save("street_sign_low_contrast", "photo", *street_sign(rng, low_contrast=True), jpg=True)
    save("slanted", "photo", *slanted(rng), jpg=True)
    save("low_contrast", "page", *low_contrast_page(rng))
    img, lines = receipt(rng)
    save("rotated_90", "rotated", *rotate(img, lines, 1))
    img, lines = screenshot(rng, dark=False)
    save("rotated_180", "rotated", *rotate(img, lines, 2))
    img, lines = small_print(rng)
    save("rotated_270", "rotated", *rotate(img, lines, 3))
    for name, *spec in VIDEOS:
        save(name, "video", *video_frame(rng, out, name, *spec))
    (out / "truth.json").write_text(json.dumps(items, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    build(Path(sys.argv[1] if len(sys.argv) > 1 else "testset"))
