# hemera OCR API: text in pictures, with where it is

This is for a client that shows the text in a picture as text a person can select, copy or look
up. Spatiand's SpatiLoupe (Live Text) and the paused frames of SpatiReel are the first. The OCR
engine reads the picture and says where each line, word and character is. It stops there:
translating the text, or answering questions about it, goes to `/v1/chat/completions` like any
other text. The engine, ocr-server, is a separate program: it answers this API itself, and hemera
starts it and passes requests and answers through unchanged.

- **Base URL:** `http://<host>:12434`. There is no authentication. CORS is open, as for the rest
  of hemera ([media-api.md](media-api.md)).
- **Errors:** always `{"error": "<message>"}` with a 4xx or 5xx status. The message is written to
  be shown to a user.
- **Privacy:** pictures and text are not kept once the answer is sent, and never logged. hemera's
  log has sizes, counts and times. Nothing leaves the server.

## 1. What is offered: `GET /v1/ocr`

```bash
curl http://deepmagpie:12434/v1/ocr
```

```json
{
  "version": 1,
  "ready": true,
  "default_model": "pp-ocrv6",
  "max_pixels": 50000000,
  "max_bytes": 52428800,
  "models": [
    { "name": "pp-ocrv6", "ready": true,
      "scripts": ["Latn", "Hani", "Jpan", "Hang", "Arab", "Thai", "Deva", "Cyrl", "Grek", "Taml", "Telu"],
      "char_boxes": "exact", "licence": "Apache-2.0 (PaddleOCR models)" }
  ]
}
```

| field | meaning |
|---|---|
| `ready` | The model is loaded now. One that is not loads on the first request (about 1.3 s, inside that request). |
| `scripts` | ISO 15924 codes the model reads well. Others are best effort. |
| `char_boxes` | `exact`: each character's place comes from the recogniser. `estimated`: spread evenly along the line. |
| `max_pixels`, `max_bytes` | The largest picture read. Larger ones get 413. |

OCR models are also in `GET /v1/models`, with `"kind": "ocr"` and `"endpoint": "/v1/ocr"`.

## 2. Reading a picture: `POST /v1/ocr`

Send `multipart/form-data` with these parts:

| part | content |
|---|---|
| `image` | The picture: PNG, JPEG, WebP or TIFF. It may also be named `file`, as for hemera's other uploads. Coordinates in the answer are pixels of this picture as stored. EXIF orientation is ignored: a phone photo stored sideways is read sideways, and found to be so (`orientation`). |
| `options` | Optional, JSON. See below. |

```bash
# a photo, everything automatic
curl -F image=@photo.jpg http://deepmagpie:12434/v1/ocr

# an Indonesian letter, words only
curl -F image=@letter.png -F 'options={"languages":["id"],"granularity":"words"}' http://deepmagpie:12434/v1/ocr

# the subtitle band of a paused 1080p frame, known to be upright
curl -F image=@frame.png -F 'options={"region":[0,780,1920,300],"orientation":0}' http://deepmagpie:12434/v1/ocr
```

### Options

All are optional. The defaults are `{"languages": ["auto"], "granularity": "chars", "region": null, "orientation": "auto", "model": null}`.

| option | meaning |
|---|---|
| `languages` | BCP-47 tags of the languages expected, or `["auto"]`. Each line's `language` is a given tag that fits the line's script. A tag for a script the main recogniser lacks (`ko`, `ar`, `th`, `hi`, `ru`, `el`, `ta`, `te`…) makes that script's recogniser read the lines the main one took for Latin, and keep its reading where it has that script's letters. With `"auto"` in the list, the other scripts are found by themselves: doubtful lines are read by every script's recogniser. A list without `"auto"` (say `["id"]`) is the fastest: lines are read once, with no search for other scripts. |
| `granularity` | `"lines"`, `"words"` (lines with words) or `"chars"` (lines, words and characters). |
| `region` | `[x, y, w, h]` in the picture's pixels: read only there. Coordinates in the answer are still the whole picture's. |
| `orientation` | `"auto"`, or how far the text is turned clockwise: `0`, `90`, `180`, `270`. A client that knows (a screenshot, a video frame) saves the guess. |
| `model` | A name from `GET /v1/ocr`, or `null` for the default. |

### The answer

```json
{
  "version": 1,
  "model": "pp-ocrv6",
  "image": { "width": 1712, "height": 1080 },
  "orientation": 0,
  "timing_ms": { "total": 1529, "orientation": 5, "detect": 753, "recognize": 702 },
  "text": "PROVINSI DKI JAKARTA\nKOTA JAKARTA SELATAN\n\nNIK\n: 3171…",
  "blocks": [
    {
      "polygon": [[540, 38], [1236, 38], [1236, 156], [540, 156]],
      "lines": [
        {
          "text": "PROVINSI DKI JAKARTA",
          "polygon": [[556.2, 41.1], [1229.8, 41.1], [1229.8, 92.9], [556.2, 92.9]],
          "angle": 0, "direction": "ltr", "script": "Latn", "language": "id", "confidence": 0.998,
          "words": [
            { "text": "PROVINSI", "polygon": [[561.7, 41.1], [781.2, 41.1], [781.2, 92.9], [561.7, 92.9]],
              "start": 0, "end": 8, "confidence": 0.999 },
            …
          ],
          "chars": [[5.5, 33.9], [33.9, 62.0], …],
          "chars_estimated": false
        }
      ]
    }
  ]
}
```

| field | meaning |
|---|---|
| `orientation` | How far the picture's text is turned clockwise (0, 90, 180, 270), as found or as given. |
| `timing_ms` | `total` is the engine's whole answer, from the request arriving; the rest are its steps. A model that had to load first adds that time outside `total`. |
| `text` | All the text in reading order: lines joined by `\n`, blocks by `\n\n`. No text gives `""` and no blocks, with status 200. |
| `blocks` | Paragraphs, columns, a form's rows, in reading order: columns, then lines. A form's labels and values, and a receipt's items and prices, are read row by row. |
| `line.polygon` | Four points, clockwise, starting at the line's own top-left in its reading direction (see below). |
| `line.angle` | Degrees the line is turned clockwise from upright, from -180 to 180. |
| `line.direction` | `ltr`, `rtl` (Arabic, Hebrew) or `ttb` (vertical CJK). |
| `line.script` | ISO 15924 code of the line's script: `Latn`, `Hani`, `Jpan` (kana with or without kanji), `Hang`, `Arab`, `Thai`, `Deva`, `Cyrl`… `Zyyy` for digits and punctuation only. A line mixing scripts is labelled by its non-Latin one. |
| `line.language` | A tag from `languages` that fits the script, or the script's own language where it has only one (`ja`, `ko`, `th`…), else `null`. |
| `confidence` | From 0 to 1: the recogniser's mean certainty over the line's (or word's) characters. Lines under 0.5 are left out, because a wrong reading is worse than none. |
| `words` | Runs of characters between spaces. `start` and `end` are grapheme indices into `chars` (end exclusive). Each word has its own `polygon`, in the line's convention. |
| `chars` | One `[start, end]` per extended grapheme cluster of `text`: é written as e + U+0301 is one, as is a flag or an emoji with its skin tone. The numbers are distances in pixels along the reading direction from the line polygon's first point. |
| `chars_estimated` | `true` when the positions are spread evenly instead of read. |

**Polygons and character positions.** A line's polygon starts at its own top-left as a reader
sees it. The reading direction runs along its top side:

| direction | polygon[0] | reading direction along the top side | bottom side |
|---|---|---|---|
| `ltr` | top-left | polygon[0] → polygon[1] | polygon[3] → polygon[2] |
| `rtl` | top-right | polygon[0] → polygon[3] | polygon[1] → polygon[2] |
| `ttb` | top-right (the column's top) | polygon[0] → polygon[1] (downwards) | polygon[3] → polygon[2] |

Character `i` lies between `chars[i][0]` and `chars[i][1]` along that direction, so a highlight
for characters `a` to `b` is the quad on the top and bottom sides at those distances. Words'
polygons are exactly that. For a photo taken askew, the top and bottom sides are not parallel:
measure along each side as a fraction of the line's length (`start / length` and `end / length`,
where `length` is the length of the top side).

### Errors

| status | when |
|---|---|
| 400 | Not multipart, no `image` part, not PNG/JPEG/WebP/TIFF or unreadable, options not valid (the message says which), a model that is not an OCR model here. |
| 413 | More than 50 MB, or more than 50 million pixels. The message gives the limits. |
| 503 | The model is loading (a start that takes longer than 10 s), or its engine is not answering. `Retry-After` says when to try again. Also when no OCR model is configured. |
| 504 | Reading took longer than `ocrTimeoutSeconds` (60 by default). |
| 500, 502 | The engine failed, or answered something unreadable. |

**Hanging up stops the work.** A client that closes the connection (the person moved on to
another picture) stops the reading: hemera notices and cancels the engine's work.

**Interactive first.** An OCR request is never held behind background jobs
(`X-Hemera-Priority: background`), nor while a Realtime voice session is open. The engine reads
two pictures at once; a third waits for one of them.

### How long it takes

On deepmagpie, warm. The whole benchmark is the engine's `bench/RESULTS.md`, on deepmagpie in
`/media/shared/AIModels/ocr/server/bench/`.

| picture | time |
|---|---|
| phone screenshot 1170×2532 | 1.1 s |
| video frame 1080p / 4K | 0.9 s / 2.8 s |
| photo 4000×3000 | 3.6 s |
| A4 page at 300 dpi | 4.5 s |

Pictures are read at full resolution. Large ones are not shrunk: they are read in overlapping
tiles whose results are stitched before any line is cut, so small print stays readable and no
line breaks where tiles meet. Sending a picture smaller than its print needs costs accuracy;
sending only the `region` that matters saves time.

## 3. Setting it up (hemera.json)

The engine is ocr-server, a separate project. On deepmagpie it is at
`/media/shared/AIModels/ocr/server`, and its README has the whole setup:
- a Python 3.13 venv with numpy, ONNX Runtime, OpenCV (headless), Pillow, PyYAML and `regex`;
- PaddleOCR's ONNX models, 221 MB. Each comes from huggingface.co/PaddlePaddle at a fixed
  revision, checked by sha256. All are Apache-2.0:
  - `PP-OCRv6_medium_det_onnx` and `PP-OCRv6_medium_rec_onnx`;
  - `korean_`, `arabic_`, `th_`, `devanagari_`, `cyrillic_`, `el_`, `ta_` and
    `te_PP-OCRv5_mobile_rec_onnx`;
  - `PP-LCNet_x1_0_doc_ori_onnx` and `PP-LCNet_x1_0_textline_ori_onnx`.

```json
"engines": {
  "hemera-ocr": {
    "binaryPath": "/media/shared/AIModels/ocr/venv/bin/python",
    "argStyle": "custom",
    "healthPath": "/health",
    "gpu": false,
    "defaultArgs": ["-I", "/media/shared/AIModels/ocr/server/ocr_server.py", "--port", "{port}", "--models", "{file}"]
  }
},
"models": [
  { "name": "pp-ocrv6", "engine": "hemera-ocr", "task": "ocr", "file": "/media/shared/AIModels/ocr/models",
    "args": ["--threads", "8", "--workers", "2", "--name", "pp-ocrv6"], "keepAliveSeconds": 900,
    "scripts": ["Latn", "Hani", "Jpan", "Hang", "Arab", "Thai", "Deva", "Cyrl", "Grek", "Taml", "Telu"],
    "charBoxes": "exact", "licence": "Apache-2.0 (PaddleOCR models)" }
],
"ocrModel": "pp-ocrv6",
"ocrTimeoutSeconds": 60
```

- **`task: "ocr"`:** makes a model an OCR model. `ocrModel` names the default; without it, the
  first OCR model is the default.
- **What hemera does:** it reads one thing of a request, the options' `model`, to know where it
  goes. It also refuses a request over 50 MB, waits up to `ocrLoadWaitSeconds` (10) for the
  engine to start, gives up after `ocrTimeoutSeconds`, and stops the engine's work when the
  client hangs up.
- **What the engine does:** everything else: the checks, the reading, and the answer.
- **`--name`:** the model name the answers carry.
- **`--workers`:** how many pictures are read at once; each gets `--threads` threads.
- **`--det` and `--rec`:** choose other PP-OCR models, for example `PP-OCRv6_small_det` and
  `PP-OCRv6_small_rec`, which are twice as fast and read Latin worse.

**Memory.**
- The engine holds about 0.6 GB idle, and up to about 3 GB while reading two large pictures,
  which it gives back after each one.
- It loads on the first request, and is unloaded after `keepAliveSeconds` without use. Being idle
  and small, it is also the first unloaded when another model needs room.
- It never unloads another model to make room for itself, so it cannot push out the assistant's
  model.
- It is off the GPU, so it never pauses an LLM, and starting it leaves ComfyUI's models where they
  are.
