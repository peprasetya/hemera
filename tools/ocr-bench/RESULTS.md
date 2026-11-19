# OCR benchmark: results

Measured 2026-11-19 on deepmagpie (AMD Ryzen AI Max+ 395, 32 threads, Radeon 8060S, 128 GB
shared between CPU and GPU), on the generated test set of `make_testset.py` (33 pictures, no
real documents). The raw results (every line read) are on deepmagpie in
`/media/shared/AIModels/ocr/results/`.

## The choice: `pp-ocrv6`, classic PaddleOCR on the CPU

The default OCR model is **`pp-ocrv6`**:
- **Detection and the main reader:** the PP-OCRv6 medium line detector and recogniser (Chinese,
  Japanese, English and 46 Latin-script languages).
- **Other scripts:** PP-OCRv5 mobile recognisers for Korean, Arabic, Thai, Devanagari, Cyrillic,
  Greek, Tamil and Telugu.
- **Orientation:** PP-LCNet classifiers for the page's quarter turns and for lines upside down.
- **Engine:** `engines/ocr/ocr_server.py` runs them on ONNX Runtime, on the CPU. All models are
  Apache-2.0.

Why:

- **Exact text.** It changes the fewest characters by far:
  - Latin 0.3% CER;
  - every other script 0.7–4.7%;
  - 261 of the 262 printed digit strings exact, and both NIKs exact.
- **Positions.**
  - **Line boxes:** 414 of 432 found.
  - **Characters:** each one's place along its line comes from the recogniser's own alignment, so
    `char_boxes` is `exact`, not estimated.
  - **Reading order:** right for 391 of 392 consecutive pairs (columns, then lines; forms and
    receipts row by row).
- **Speed:**
  - 1.1 s for a phone screenshot and 4.5 s for an A4 page at 300 dpi;
  - 0.9 s for a 1080p video frame and 2.8 s for 4K;
  - two pictures at once.
- **No GPU.** It never waits for an LLM, never pauses one, and never competes with one for the
  GPU. Memory: 0.6 GB when idle, up to 3 GB while reading.

The others:

- **PaddleOCR-VL 1.6** (0.9B, llama.cpp on Vulkan) draws the tightest boxes (mean IoU 0.83 against
  0.77) and reads vertical Japanese perfectly. But it:
  - is 4–7× slower (5.6 s for a screenshot, 20 s for an A4 page);
  - changes more characters (Latin 25% CER where pages are tiled, Thai 77%);
  - mixes the reading order of tiled pages;
  - would share the GPU with the assistant.
- **ornith-1.5:9b** reads most scripts well when position is ignored, but:
  - its boxes are unusable (112 of 432);
  - it changes characters (会議 → 會議, half-width brackets);
  - it takes 35 s to 4 minutes a picture.
- **PP-OCRv6 small** is twice as fast, but reads Latin nine times worse (2.7% CER) and missed the
  NIK in the KTP photo.

## Summary

| | pp-ocrv6 (chosen) | PP-OCRv6 small | PaddleOCR-VL 1.6 | ornith-1.5:9b |
|---|---|---|---|---|
| Latin CER | **0.3%** | 2.7% | 25.2% | 62.9% |
| Chinese / Japanese CER | **1.2% / 2.3%** | 0.8% / 4.2% | 3.2% / 6.8% | 60.7% / 88.9% |
| Korean / Arabic / Thai CER | 3.9% / **0.7%** / **4.3%** | **0.8%** / 3.7% / 10.4% | 10.2% / 25.1% / 77.0% | 96.1% / 76.0% / 74.3% |
| Devanagari / Cyrillic CER | **1.4% / 4.7%** | 1.9% / 11.8% | 5.2% / 10.0% | 79.2% / 95.7% |
| digit strings exact | **261/262** | 255/262 | 252/262 | 93/262 |
| NIK (card, photo) | **exact, exact** | exact, missed | exact, exact | exact, wrong |
| line boxes (IoU ≥ 0.5) | 414/432 | 406/432 | **417/432** | 112/432 |
| reading order | **391/392** | 382/384 | 209/395 | 135/239 |
| screenshot / A4 / 4000×3000 photo | 1.2 / 4.5 / 3.5 s | **0.6 / 1.7 / 2.1 s** | 5.6 / 20.5 / 23.1 s | 73 / 223 / 99 s |
| all 33 pictures | 53 s | **29 s** | 349 s | 2377 s |
| runs on | CPU | CPU | GPU (Vulkan) | GPU (Vulkan) |

CER is the edit distance over the true text's length, without spaces (spacing is not scored,
every letter, digit and punctuation mark is). The pp-ocrv6 times are through hemera
(`POST /v1/ocr`), the others' are the model alone. The VLMs read in tiles of 1264 px
(PaddleOCR-VL's limit is 1.6 MP) or 1600 px (ornith), so that small print keeps its resolution.

## Speed through hemera

Warm, median of five, `POST /v1/ocr` on deepmagpie with nothing else running:

| picture | pixels | time |
|---|---|---|
| phone screenshot | 1170×2532 | 1.12 s |
| A4 page at 300 dpi | 2480×3508 | 4.53 s |
| street-sign photo | 4000×3000 | 3.59 s |
| dense two-column A4 page | 2480×3508 | 3.73 s |
| video frame, 1080p | 1920×1080 | 0.90 s |
| video frame, 4K | 3840×2160 | 2.84 s |
| KTP photo | 4000×3000 | 4.18 s |

- **Two screenshots at once:** 1.6–1.7 s each.
- **Four at once:** two finish at 1.6 s and two at 3.2 s. The engine reads two at a time
  (`--workers 2`, each with 8 threads); the rest wait for a free worker.
- **Cold start:** the first request after the engine was let go adds about 1.3 s.

## Memory, and living beside the LLMs

- **Size:** the engine holds 0.57 GB when idle. While reading two large pictures at once it peaks
  near 3 GB, and gives that back after each picture: ONNX Runtime's arena shrinks after every run,
  and glibc is asked to return freed pages.
- **Loading:** it loads on the first request (about 1.3 s; that request waits for it).
- **Letting go:** it is unloaded after `keepAliveSeconds` without use (900 is suggested). Being
  idle and small, it is also the first unloaded when a model needs room.
- **Never evicts:** it never unloads another model to make room for itself, so it cannot push out
  the assistant's model.
- **Off the GPU:** starting it does not make ComfyUI release the GPU, and it is never paused for
  speech or cleared for ComfyUI.

## How it was measured

- **Test set:** `make_testset.py` draws every picture from known text, so the truth is exact,
  polygons included.
  - **Documents:** a made-up KTP (card and 4000×3000 photo), receipts (flat and photographed),
    light and dark phone screenshots, an A4 page, a dense two-column page, small print.
  - **Scripts:** pages in Japanese (horizontal and vertical), Chinese (simplified, traditional),
    Korean, Arabic, Thai, Hindi, Russian, and one of mixed scripts (mixed within lines too).
  - **Photos:** street signs (also at low contrast), a slanted page, a low-contrast page, the
    receipt and screenshot turned 90/180/270°.
  - **Video:** seven frames from 1080p to 4K, encoded with libx264 or libx265 at 2–20 Mbit/s and
    grabbed back from the stream. They show a panning street scene (signs blurred by the motion),
    a scrolling news ticker, burned-in subtitles in Indonesian, English, Japanese, Chinese,
    Korean, Arabic and Thai, and a channel's corner text.
- **Scoring:** `bench.py` pairs what was read with the truth by place.
  - A true line and a read line belong together when most of either lies inside the other.
  - A row read in pieces (a receipt's item, quantity and price), or two lines read as one, is
    compared as a whole.
  - "Box" is the overlap of the true and read areas.
  - "Reading order" asks, for each two consecutive true lines, whether the first was read first.

## What it still gets wrong

- **Motion-blurred signs in video frames** are often missed or partly read. The 1080p clip panned
  at 36 px a frame is the worst case. Subtitles, tickers and corner text read cleanly at every
  bitrate.
- **Vertical Japanese** sometimes loses a 、 (3.8% CER on that page). Vertical lines are also read
  as their characters stacked upright, which keeps most small marks (、。っ).
- **Full-width and half-width punctuation** can be confused in CJK lines (（Zoom） read as (Zoom)).
  Outside CJK text, full-width forms are turned into ASCII.
- **Look-alikes the recogniser itself confuses:** l and 1 in a monospace font (Jl. → J1.); the
  Thai โทร → โns.
- **A colon followed by a gap** is joined to the next word (": RINA" → ":RINA"). Spaces are not
  scored.

## Reproduce

On deepmagpie, with the venv of `/media/shared/AIModels/ocr`:

```bash
python -I make_testset.py testset
python -I bench.py run testset hemera url=http://127.0.0.1:12434 model=pp-ocrv6 > pp-ocrv6.json
python -I bench.py run testset vlm url=http://127.0.0.1:18432 model=paddleocr-vl tile=1264 fmt=paddle > vl.json
python -I bench.py report pp-ocrv6.json vl.json
```

PaddleOCR-VL ran as `llama-server -m PaddleOCR-VL-1.6-GGUF.gguf --mmproj PaddleOCR-VL-1.6-GGUF-mmproj.gguf
--temp 0 --special --image-max-tokens 2048 -ngl 99`. `--image-max-tokens 2048` is the 1,605,632 pixels
its "Spotting:" task needs.

## Every picture

The output of `bench.py report`, one section per model.

### pp-ocrv6 (PP-OCRv6 medium + PP-OCRv5 script recognisers), through hemera

| script | CER | text CER (position-free) |
|---|---|---|
| Arab | 0.7% (2/267) | 2.6% |
| Cyrl | 4.7% (10/211) | 4.7% |
| Deva | 1.4% (3/212) | 1.4% |
| Hang | 3.9% (5/127) | 3.9% |
| Hani | 1.2% (3/252) | 1.2% |
| Jpan | 2.3% (7/307) | 2.3% |
| Latn | 0.3% (27/9017) | 3.8% |
| Thai | 4.3% (10/230) | 4.3% |

- digit strings read exactly: 261/262
- NIK: ktp_card: exact, ktp_photo: exact
- line boxes: 414/432 found (IoU 0.5 or more), mean IoU 0.77
- reading order: 391/392 pairs of consecutive lines read in order

| picture | size | time | CER | digits | boxes | order |
|---|---|---|---|---|---|---|
| screenshot_light | 1170x2532 | 1.15 s | 0.0% | 15/15 | 24/24 | 23/23 |
| screenshot_dark | 1170x2532 | 1.08 s | 0.0% | 15/15 | 24/24 | 23/23 |
| page_a4 | 2480x3508 | 4.49 s | 0.0% | 4/4 | 34/34 | 33/33 |
| article_2col | 2480x3508 | 3.72 s | 0.0% | 5/5 | 44/44 | 43/43 |
| small_print | 2480x1400 | 1.05 s | 0.0% | 15/15 | 4/4 | 3/3 |
| ktp_card | 1712x1080 | 0.95 s | 0.0% | 5/5 | 31/31 | 30/30 |
| ktp_photo | 4000x3000 | 4.15 s | 0.3% | 5/5 | 31/31 | 29/30 |
| receipt | 1100x1500 | 0.83 s | 0.3% | 31/31 | 12/15 | 14/14 |
| receipt_photo | 2400x3200 | 2.46 s | 0.0% | 31/31 | 12/15 | 14/14 |
| ja_horizontal | 1370x780 | 0.48 s | 0.7% | 10/10 | 6/6 | 5/5 |
| zh_simplified | 1252x780 | 0.41 s | 0.0% | 4/4 | 6/6 | 5/5 |
| zh_traditional | 1130x670 | 0.34 s | 0.0% | 4/4 | 5/5 | 4/4 |
| ko | 1274x670 | 0.85 s | 2.1% | 6/6 | 5/5 | 4/4 |
| ar | 1315x670 | 0.86 s | 0.0% | 2/2 | 5/5 | 4/4 |
| th | 991x670 | 0.73 s | 1.6% | 5/5 | 5/5 | 4/4 |
| hi | 1646x670 | 1.14 s | 1.1% | 3/4 | 5/5 | 4/4 |
| ru | 1798x670 | 1.20 s | 3.2% | 7/7 | 5/5 | 4/4 |
| ja_vertical | 1400x1300 | 0.92 s | 3.8% | 0/0 | 7/7 | 6/6 |
| mixed_scripts | 1132x890 | 0.79 s | 3.8% | 8/8 | 7/7 | 6/6 |
| street_sign | 4000x3000 | 3.52 s | 0.0% | 5/5 | 8/9 | 8/8 |
| street_sign_low_contrast | 4000x3000 | 3.70 s | 0.0% | 5/5 | 7/9 | 8/8 |
| slanted | 3600x2700 | 4.17 s | 0.0% | 2/2 | 38/38 | 37/37 |
| low_contrast | 2000x1300 | 1.27 s | 0.0% | 0/0 | 10/10 | 9/9 |
| rotated_90 | 1500x1100 | 0.92 s | 0.3% | 31/31 | 12/15 | 14/14 |
| rotated_180 | 1170x2532 | 1.09 s | 0.0% | 15/15 | 24/24 | 23/23 |
| rotated_270 | 1400x2480 | 1.11 s | 0.0% | 15/15 | 4/4 | 3/3 |
| video_1080_h264_id | 1920x1080 | 0.91 s | 0.6% | 3/3 | 7/7 | 6/6 |
| video_1080_hevc_ja | 1920x1080 | 0.69 s | 0.0% | 1/1 | 6/6 | 5/5 |
| video_1080_h264_en_low | 1920x1080 | 0.69 s | 13.1% | 3/3 | 5/7 | 3/3 |
| video_1080_hevc_ar | 1920x1080 | 0.95 s | 2.3% | 1/1 | 6/6 | 5/5 |
| video_1440_h264_th | 2560x1440 | 1.12 s | 9.5% | 1/1 | 5/6 | 3/3 |
| video_4k_h264_zh | 3840x2160 | 2.82 s | 4.0% | 3/3 | 6/7 | 6/6 |
| video_4k_hevc_ko | 3840x2160 | 2.80 s | 14.0% | 1/1 | 4/6 | 3/3 |

### pp-ocrv6-small

| script | CER | text CER (position-free) |
|---|---|---|
| Arab | 3.7% (10/267) | 3.7% |
| Cyrl | 11.8% (25/211) | 11.8% |
| Deva | 1.9% (4/212) | 1.9% |
| Hang | 0.8% (1/127) | 0.8% |
| Hani | 0.8% (2/252) | 0.8% |
| Jpan | 4.2% (13/307) | 4.2% |
| Latn | 2.7% (243/9017) | 6.1% |
| Thai | 10.4% (24/230) | 10.4% |

- digit strings read exactly: 255/262
- NIK: ktp_card: exact, ktp_photo: wrong ()
- line boxes: 406/432 found (IoU 0.5 or more), mean IoU 0.76
- reading order: 382/384 pairs of consecutive lines read in order

| picture | size | time | CER | digits | boxes | order |
|---|---|---|---|---|---|---|
| screenshot_light | 1170x2532 | 0.58 s | 0.0% | 15/15 | 23/24 | 23/23 |
| screenshot_dark | 1170x2532 | 0.46 s | 0.0% | 15/15 | 23/24 | 23/23 |
| page_a4 | 2480x3508 | 1.69 s | 0.0% | 4/4 | 34/34 | 33/33 |
| article_2col | 2480x3508 | 1.46 s | 0.0% | 5/5 | 44/44 | 43/43 |
| small_print | 2480x1400 | 0.51 s | 0.0% | 15/15 | 4/4 | 3/3 |
| ktp_card | 1712x1080 | 0.48 s | 0.0% | 5/5 | 31/31 | 30/30 |
| ktp_photo | 4000x3000 | 1.87 s | 34.7% | 3/5 | 22/31 | 20/20 |
| receipt | 1100x1500 | 0.30 s | 0.3% | 31/31 | 12/15 | 14/14 |
| receipt_photo | 2400x3200 | 1.13 s | 33.0% | 26/31 | 7/15 | 9/9 |
| ja_horizontal | 1370x780 | 0.17 s | 0.7% | 10/10 | 6/6 | 5/5 |
| zh_simplified | 1252x780 | 0.16 s | 0.0% | 4/4 | 6/6 | 5/5 |
| zh_traditional | 1130x670 | 0.14 s | 0.0% | 4/4 | 5/5 | 4/4 |
| ko | 1274x670 | 0.69 s | 1.0% | 6/6 | 5/5 | 4/4 |
| ar | 1315x670 | 0.60 s | 1.1% | 2/2 | 5/5 | 4/4 |
| th | 991x670 | 0.44 s | 17.5% | 5/5 | 5/5 | 4/4 |
| hi | 1646x670 | 0.88 s | 1.7% | 4/4 | 5/5 | 4/4 |
| ru | 1798x670 | 0.77 s | 13.4% | 7/7 | 5/5 | 4/4 |
| ja_vertical | 1400x1300 | 0.54 s | 9.6% | 0/0 | 7/7 | 6/6 |
| mixed_scripts | 1132x890 | 0.47 s | 6.6% | 8/8 | 7/7 | 6/6 |
| street_sign | 4000x3000 | 2.13 s | 0.9% | 5/5 | 9/9 | 7/8 |
| street_sign_low_contrast | 4000x3000 | 2.46 s | 0.9% | 5/5 | 9/9 | 7/8 |
| slanted | 3600x2700 | 2.44 s | 0.1% | 2/2 | 38/38 | 37/37 |
| low_contrast | 2000x1300 | 0.54 s | 0.0% | 0/0 | 10/10 | 9/9 |
| rotated_90 | 1500x1100 | 0.31 s | 0.3% | 31/31 | 12/15 | 14/14 |
| rotated_180 | 1170x2532 | 0.60 s | 0.0% | 15/15 | 23/24 | 23/23 |
| rotated_270 | 1400x2480 | 0.66 s | 0.0% | 15/15 | 4/4 | 3/3 |
| video_1080_h264_id | 1920x1080 | 0.27 s | 0.0% | 3/3 | 7/7 | 6/6 |
| video_1080_hevc_ja | 1920x1080 | 0.33 s | 0.0% | 1/1 | 6/6 | 5/5 |
| video_1080_h264_en_low | 1920x1080 | 0.37 s | 4.8% | 3/3 | 7/7 | 6/6 |
| video_1080_hevc_ar | 1920x1080 | 0.45 s | 2.3% | 1/1 | 6/6 | 5/5 |
| video_1440_h264_th | 2560x1440 | 0.99 s | 1.9% | 1/1 | 6/6 | 5/5 |
| video_4k_h264_zh | 3840x2160 | 1.96 s | 4.0% | 3/3 | 7/7 | 6/6 |
| video_4k_hevc_ko | 3840x2160 | 2.23 s | 2.0% | 1/1 | 6/6 | 5/5 |

### PaddleOCR-VL-1.6

| script | CER | text CER (position-free) |
|---|---|---|
| Arab | 25.1% (67/267) | 7.1% |
| Cyrl | 10.0% (21/211) | 7.6% |
| Deva | 5.2% (11/212) | 7.5% |
| Hang | 10.2% (13/127) | 3.9% |
| Hani | 3.2% (8/252) | 4.8% |
| Jpan | 6.8% (21/307) | 2.3% |
| Latn | 25.2% (2269/9017) | 16.9% |
| Thai | 77.0% (177/230) | 68.3% |

- digit strings read exactly: 252/262
- NIK: ktp_card: exact, ktp_photo: exact
- line boxes: 417/432 found (IoU 0.5 or more), mean IoU 0.83
- reading order: 209/395 pairs of consecutive lines read in order

| picture | size | time | CER | digits | boxes | order |
|---|---|---|---|---|---|---|
| screenshot_light | 1170x2532 | 5.59 s | 6.9% | 14/15 | 24/24 | 12/23 |
| screenshot_dark | 1170x2532 | 5.50 s | 0.3% | 14/15 | 24/24 | 12/23 |
| page_a4 | 2480x3508 | 20.50 s | 63.8% | 4/4 | 34/34 | 16/33 |
| article_2col | 2480x3508 | 16.28 s | 2.1% | 4/5 | 44/44 | 20/43 |
| small_print | 2480x1400 | 4.98 s | 8.6% | 15/15 | 4/4 | 1/3 |
| ktp_card | 1712x1080 | 4.66 s | 2.3% | 5/5 | 31/31 | 17/30 |
| ktp_photo | 4000x3000 | 24.17 s | 13.8% | 5/5 | 31/31 | 15/30 |
| receipt | 1100x1500 | 4.28 s | 50.2% | 31/31 | 12/15 | 9/14 |
| receipt_photo | 2400x3200 | 16.03 s | 48.0% | 31/31 | 12/15 | 8/14 |
| ja_horizontal | 1370x780 | 2.85 s | 8.6% | 10/10 | 6/6 | 3/5 |
| zh_simplified | 1252x780 | 2.18 s | 0.0% | 4/4 | 6/6 | 3/5 |
| zh_traditional | 1130x670 | 1.65 s | 0.0% | 4/4 | 5/5 | 3/4 |
| ko | 1274x670 | 2.37 s | 7.2% | 6/6 | 5/5 | 4/4 |
| ar | 1315x670 | 2.47 s | 19.9% | 2/2 | 5/5 | 1/4 |
| th | 991x670 | 40.93 s | 100.0% | 0/5 | 0/5 | 0/0 |
| hi | 1646x670 | 2.64 s | 5.2% | 4/4 | 5/5 | 3/4 |
| ru | 1798x670 | 2.85 s | 11.2% | 5/7 | 5/5 | 2/4 |
| ja_vertical | 1400x1300 | 3.52 s | 0.0% | 0/0 | 7/7 | 3/6 |
| mixed_scripts | 1132x890 | 2.33 s | 7.7% | 8/8 | 7/7 | 4/6 |
| street_sign | 4000x3000 | 23.09 s | 43.2% | 5/5 | 9/9 | 4/8 |
| street_sign_low_contrast | 4000x3000 | 25.05 s | 43.2% | 5/5 | 9/9 | 4/8 |
| slanted | 3600x2700 | 23.67 s | 34.5% | 2/2 | 38/38 | 20/37 |
| low_contrast | 2000x1300 | 5.75 s | 8.1% | 0/0 | 10/10 | 5/9 |
| rotated_90 | 1500x1100 | 4.55 s | 6.4% | 31/31 | 12/15 | 9/14 |
| rotated_180 | 1170x2532 | 6.44 s | 7.5% | 15/15 | 24/24 | 11/23 |
| rotated_270 | 1400x2480 | 7.32 s | 16.3% | 15/15 | 4/4 | 1/3 |
| video_1080_h264_id | 1920x1080 | 4.17 s | 9.9% | 3/3 | 7/7 | 2/6 |
| video_1080_hevc_ja | 1920x1080 | 3.97 s | 13.5% | 1/1 | 6/6 | 3/5 |
| video_1080_h264_en_low | 1920x1080 | 3.91 s | 10.3% | 3/3 | 7/7 | 2/6 |
| video_1080_hevc_ar | 1920x1080 | 4.01 s | 35.6% | 1/1 | 6/6 | 3/5 |
| video_1440_h264_th | 2560x1440 | 45.18 s | 44.8% | 1/1 | 5/6 | 2/5 |
| video_4k_h264_zh | 3840x2160 | 12.81 s | 10.7% | 3/3 | 7/7 | 4/6 |
| video_4k_hevc_ko | 3840x2160 | 12.82 s | 16.0% | 1/1 | 6/6 | 3/5 |

### ornith-1.5:9b

| script | CER | text CER (position-free) |
|---|---|---|
| Arab | 76.0% (203/267) | 4.5% |
| Cyrl | 95.7% (202/211) | 2.4% |
| Deva | 79.2% (168/212) | 0.0% |
| Hang | 96.1% (122/127) | 21.3% |
| Hani | 60.7% (153/252) | 4.0% |
| Jpan | 88.9% (273/307) | 1.3% |
| Latn | 62.9% (5673/9017) | 8.0% |
| Thai | 74.3% (171/230) | 7.0% |

- digit strings read exactly: 93/262
- NIK: ktp_card: exact, ktp_photo: wrong (8889534495509)
- line boxes: 112/432 found (IoU 0.5 or more), mean IoU 0.31
- reading order: 135/239 pairs of consecutive lines read in order

| picture | size | time | CER | digits | boxes | order |
|---|---|---|---|---|---|---|
| screenshot_light | 1170x2532 | 72.57 s | 131.3% | 3/15 | 4/24 | 3/7 |
| screenshot_dark | 1170x2532 | 58.12 s | 149.9% | 4/15 | 0/24 | 5/8 |
| page_a4 | 2480x3508 | 223.24 s | 96.2% | 4/4 | 19/34 | 12/25 |
| article_2col | 2480x3508 | 240.53 s | 120.3% | 2/5 | 15/44 | 18/33 |
| small_print | 2480x1400 | 33.19 s | 95.8% | 10/15 | 0/4 | 1/2 |
| ktp_card | 1712x1080 | 103.81 s | 117.2% | 1/5 | 0/31 | 7/12 |
| ktp_photo | 4000x3000 | 162.31 s | 74.5% | 4/5 | 7/31 | 15/26 |
| receipt | 1100x1500 | 66.68 s | 125.1% | 6/31 | 2/15 | 4/7 |
| receipt_photo | 2400x3200 | 137.76 s | 34.9% | 30/31 | 5/15 | 9/14 |
| ja_horizontal | 1370x780 | 24.77 s | 154.3% | 0/10 | 1/6 | 2/2 |
| zh_simplified | 1252x780 | 23.32 s | 172.0% | 0/4 | 1/6 | 0/0 |
| zh_traditional | 1130x670 | 14.74 s | 40.0% | 2/4 | 1/5 | 2/3 |
| ko | 1274x670 | 17.53 s | 150.5% | 0/6 | 1/5 | 1/1 |
| ar | 1315x670 | 26.24 s | 138.6% | 0/2 | 1/5 | 1/2 |
| th | 991x670 | 16.91 s | 170.6% | 0/5 | 0/5 | 1/1 |
| hi | 1646x670 | 32.16 s | 121.8% | 0/4 | 1/5 | 1/2 |
| ru | 1798x670 | 21.29 s | 154.0% | 0/7 | 1/5 | 1/1 |
| ja_vertical | 1400x1300 | 38.12 s | 200.0% | 0/0 | 0/7 | 0/0 |
| mixed_scripts | 1132x890 | 27.75 s | 157.4% | 0/8 | 0/7 | 1/2 |
| street_sign | 4000x3000 | 98.46 s | 34.2% | 5/5 | 7/9 | 4/8 |
| street_sign_low_contrast | 4000x3000 | 88.17 s | 33.3% | 5/5 | 7/9 | 4/8 |
| slanted | 3600x2700 | 230.52 s | 88.9% | 2/2 | 9/38 | 19/26 |
| low_contrast | 2000x1300 | 52.13 s | 128.2% | 0/0 | 2/10 | 3/7 |
| rotated_90 | 1500x1100 | 73.71 s | 199.7% | 0/31 | 0/15 | 0/0 |
| rotated_180 | 1170x2532 | 96.12 s | 122.4% | 6/15 | 1/24 | 6/10 |
| rotated_270 | 1400x2480 | 42.82 s | 202.5% | 0/15 | 0/4 | 0/0 |
| video_1080_h264_id | 1920x1080 | 35.18 s | 22.7% | 2/3 | 5/7 | 1/4 |
| video_1080_hevc_ja | 1920x1080 | 29.12 s | 7.7% | 1/1 | 1/6 | 3/5 |
| video_1080_h264_en_low | 1920x1080 | 34.30 s | 13.8% | 2/3 | 6/7 | 1/4 |
| video_1080_hevc_ar | 1920x1080 | 27.82 s | 2.3% | 1/1 | 3/6 | 3/5 |
| video_1440_h264_th | 2560x1440 | 48.75 s | 38.1% | 0/1 | 4/6 | 1/3 |
| video_4k_h264_zh | 3840x2160 | 102.84 s | 61.3% | 2/3 | 5/7 | 3/6 |
| video_4k_hevc_ko | 3840x2160 | 75.83 s | 58.0% | 1/1 | 3/6 | 3/5 |

