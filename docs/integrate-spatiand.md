# Integrating hemera's speech and Live Text into spatiand

A hand-off note for whoever builds voice and Live Text into spatiand, the stereoscopic 3D desktop (macOS,
Steam Deck, Android on the XREAL Beam Pro). It stands alone; the full API reference is
[media-api.md](media-api.md) (in the hemera repository, `docs/media-api.md`).

## What hemera is, in one paragraph

hemera is a model router on `deepmagpie`, reachable at `http://deepmagpie:12434` over the home
network or Tailscale (a direct Tailscale connection adds little delay; a relayed one adds more).
It speaks the OpenAI API, starts and stops the engines behind each model by itself, and manages
the machine's memory: spatiand only asks for models by name. No authentication. Errors are always
`{"error": "<message fit to show a user>"}`. All speech runs on deepmagpie's GPU; spatiand's
devices only capture and play audio.

Models by `hemera.kind` from `GET /v1/models` (read the list; names can change):
`transcription` → `whisper-large-v3-turbo` for dictation; `voice-changer` → `meanvc2`;
`text` → chat models for clean-up; `speech` → `voxcpm2` if spatiand ever needs to talk;
`ocr` → `pp-ocrv6` for the text in pictures (section 4).

## 1. Contextual dictation

The user holds a key (or a gesture), speaks, releases; the text goes into whatever has focus. The
context of that focus is what turns "l s slash e-t-c slash h-t-t-p-d" into `ls /etc/httpd`.

### Transport: OpenAI's Realtime transcription, push-to-talk

A WebSocket, one per spatiand session (keep it open; hemera keeps the model loaded while it is):

```
connect   ws://deepmagpie:12434/v1/realtime?intent=transcription     (subprotocol "realtime")
send      {"type":"transcription_session.update","session":{
             "input_audio_format":"pcm16",
             "input_audio_transcription":{"model":"whisper-large-v3-turbo","language":"en","prompt":"<context>"},
             "turn_detection":null}}
key down  start sending {"type":"input_audio_buffer.append","audio":"<base64 PCM>"} every 20–100 ms
key up    {"type":"input_audio_buffer.commit"}
receive   conversation.item.input_audio_transcription.delta      → grey draft while speaking
          conversation.item.input_audio_transcription.completed  → the text; insert this
          error                                                  → show it; the session carries on
```

- Audio: 16-bit little-endian mono PCM, 24 kHz by default. hemera's GA spelling also accepts
  `{"type":"session.update","session":{"type":"transcription","audio":{"input":{"format":{"type":"audio/pcm","rate":16000}}, ...}}}`
  for another rate; any rate from 8 to 48 kHz works.
- Latency: the final text arrives about 0.3 s after `commit`.
- `language`: `"en"` or `"id"` when you know it (from the keyboard layout, the focused app, or a
  setting); leave it out to detect, which is less reliable on short phrases.
- Before each utterance, send another `transcription_session.update` with the `prompt` for the
  current focus. It is cheap.

### The prompt: what the focused thing is about

Whisper reads the last ~150 words of `prompt` and leans its spelling and vocabulary towards
them. Put the most relevant text **last**:

| Focus | Good `prompt` |
|---|---|
| Terminal | the last commands and paths from the shell history, the current directory listing, e.g. `cd /etc/httpd; ls conf.d; systemctl status httpd` |
| aether chat | the last few messages of the conversation |
| Code editor | identifiers near the cursor, the file name |
| Anything else | the window title and visible text, if available |

### Spoken symbols and spelling are spatiand's job

Whisper writes what it hears: "l s slash e t c slash h t t p d" may come back as
`LS slash ETC slash HTTPD`. Turning that into `ls /etc/httpd` needs to know that the focus is a
terminal, so it belongs in spatiand. Two steps work well together:

1. **Rules** for the fixed vocabulary: `slash` → `/`, `dot` → `.`, `dash` → `-`, `pipe` → `|`,
   `tilde` → `~`, `space` → ` `, letters spelled one by one joined into a word, numbers as digits.
2. **A small LLM pass** through hemera's chat endpoint when the focus is a terminal or code, with the
   same context:

```json
POST /v1/chat/completions
{ "model": "<a fast text model>", "temperature": 0,
  "messages": [
    { "role": "system", "content": "Turn dictated speech into the exact text to type into a Linux terminal. Spoken symbols become symbols, spelled letters become words. Output only the text." },
    { "role": "user", "content": "Recent terminal:\n<history>\n\nDictated: LS slash ETC slash HTTPD" } ] }
```

Show the result before inserting it into a terminal: a misheard `rm` is costly.

## 2. Voice changer as a virtual microphone

spatiand captures the real microphone, streams it to hemera, and plays what comes back into a
virtual microphone that the apps running on spatiand use — so the change is transparent to them.

### The stream

```
connect   ws://deepmagpie:12434/v1/audio/voice-changer?model=meanvc2&voice=<name>&sample_rate=48000
receive   {"type":"session.created", ...}
send      binary messages: 16-bit little-endian mono PCM at sample_rate, 20–100 ms each
receive   binary messages: the converted speech, same format and rate
send      {"type":"session.update","voice":"<other>"}   to switch voice without reconnecting
receive   {"type":"session.updated"} / {"type":"error","error":{"message":...}}
```

- Delay: 0.11 s median, 0.15 s p90 behind the speaker at 48 kHz, plus the network. Send small
  frames (20–40 ms) and keep a small jitter buffer (40–80 ms) before the virtual microphone.
- Keep sending while the user is silent (silence is fine); the stream may also pause when muted.
- Voices come from hemera's voice library: `GET /v1/audio/voices` lists them (`id`, `owner`,
  `consent`, `transcript`); `GET /v1/audio/voices/{id}/audio` is a preview. New voices are added
  in hemera's web UI (`http://deepmagpie:12434/configure/#voices`) or with
  `POST /v1/audio/voices` (multipart: `name`, `file`, `consent`). Show the owner and consent in
  the picker.
- Any language works; the conversion does not depend on what is said.
- While a voice changer is open, hemera holds bulk background work so the voice stays smooth.

### The virtual microphone, per platform (spatiand's part)

| Platform | Where the converted audio goes |
|---|---|
| macOS | A virtual audio device (a Core Audio HAL plug-in, BlackHole-style) that apps pick as their microphone; spatiand writes the converted PCM into it. |
| Steam Deck (Linux) | PipeWire: create a virtual source (`pw-loopback` or a null sink with a monitor exposed as a source) and play the converted audio into it; apps select it, or spatiand makes it the default source. |
| Android (XREAL Beam Pro) | Android has no system-wide virtual microphone without root. Apps running inside spatiand's own process can be given the converted stream directly; others cannot. |

Use the real microphone's native rate (usually 48 kHz) as `sample_rate`, so nothing is resampled
on the device.

## 3. Audio formats, in one place

- PCM everywhere: 16-bit signed little-endian, mono.
- Realtime transcription: 24 kHz default, any 8–48 kHz via the GA format, or G.711 at 8 kHz.
- Voice changer: whatever `sample_rate` says (8–48 kHz), same in and out.
- Text to speech, if used: `/v1/audio/speech` with `"stream_format":"sse"` gives 24 kHz PCM deltas.

## 4. Live Text: the text in pictures (SpatiLoupe, SpatiReel)

hemera reads the text in a picture and says where each line, word and character is; SpatiLoupe
draws the highlights, the selection and the copy. The reference is [ocr-api.md](ocr-api.md).
Translating the text, or asking about it, is a chat request with the text: `/v1/chat/completions`,
as now.

### The request

`POST /v1/ocr`, multipart: the picture, and an optional `options` part (JSON).
`spatiand-voice`'s `Hemera::post_form` already does it, as hemera takes the picture in a part
named `file` as well as `image`:

```rust
let options = r#"{"languages":["id","auto"],"granularity":"chars"}"#;
let answer = hemera.post_form("/v1/ocr", &[("options", options)], ("picture.png", "image/png", &png), Duration::from_secs(60))?;
```

- **Which picture.** Send the picture as it is, at full resolution: hemera never shrinks it, so
  small print stays readable. A screenshot or a video frame goes as PNG; JPEG adds artefacts to
  text. A photo goes as its own file: EXIF orientation is ignored, and the coordinates are pixels
  of the picture as stored.
- **SpatiReel.** Send the decoded frame at its own size (1080p to 4K) as PNG, with
  `"orientation": 0`, since a frame is upright and that saves the guess. For subtitles only, send
  `"region": [x, y, w, h]` around the subtitle band: it is quicker, and the coordinates are still
  the frame's.
- **Languages.** Use the device's languages, then `"auto"`, for example `["id", "auto"]`.
  `"auto"` finds the other scripts by itself (Korean, Arabic, Thai, Devanagari, Cyrillic…); a
  list without it is a little quicker and reads only Latin, Chinese and Japanese, plus the
  scripts it names.
- **Moving on.** When the user moves to another picture, drop the request: closing the
  connection stops hemera's work.

### The answer, and drawing it

- **`text`:** everything in reading order (lines joined by `\n`, blocks by `\n\n`), for copying
  the whole picture.
- **`blocks[].lines[]`:** each line has its `polygon`: four points, clockwise, from the line's own
  top-left in its reading direction. That is the top-left corner for `ltr`, and the top-right for
  `rtl` (Arabic) and `ttb` (vertical CJK).
- **`chars`:** one `[start, end]` per grapheme cluster, the distance in pixels along the reading
  direction from `polygon[0]`. A selection from character `a` to `b` is the quad between the
  line's top and bottom sides at those distances. `words[]` come with that quad ready, and with
  `start` and `end` as grapheme indices.
- **`confidence`** is given per line and per word. Digits and letters are as printed, never
  corrected, and lines below 0.5 are left out. A word below about 0.9 is worth showing as unsure
  before someone copies an ID number from it.

### Times, and errors

- **Times** (warm): 1.1 s for a phone screenshot, 0.9 s for a 1080p frame, 2.8 s for 4K, 3.6 s for
  a 4000×3000 photo, 4.5 s for an A4 page at 300 dpi. hemera reads two pictures at once; OCR is
  never held behind background jobs or a live voice session.
- **First use:** the first request after a quiet spell also starts the engine (about 1.3 s, inside
  that request).
- **Errors** are hemera's usual `{"error": "<message fit to show a user>"}`:
  - 400: a bad picture or options;
  - 413: more than 50 MB or 50 million pixels;
  - 503: the engine is still starting; `Retry-After` says when to retry;
  - 504: the reading took longer than 60 s.

### Where this differs from the request

hemera's own conventions, which Live Text follows:

- **Path:** `POST /v1/ocr` and `GET /v1/ocr`. hemera's generation APIs are under `/v1/`;
  `/hemera/api/` is administration.
- **Errors:** `{"error": "<message>"}` with the HTTP status, as everywhere in hemera. There is no
  `code` field: the status is the code.
- **Authentication:** none, as for the rest of hemera on the home network.
- **The picture's part:** `image` or `file`.
- **Discovery:** OCR models are listed in `GET /v1/models` with `"kind": "ocr"`, besides
  `GET /v1/ocr`.

Everything else is as asked: the options, the answer's fields, the polygon convention, grapheme
clusters, the limits, and 503 with `Retry-After`.

## What stays in spatiand

Microphone capture, push-to-talk input, collecting the focus context, spoken-symbol rules and the
clean-up prompt, inserting text into the focused app, the virtual microphone, voice picking UI,
and reconnecting if the network drops. For Live Text: choosing the picture or frame, drawing the
highlights and the selection, copying, and translating. hemera stores nothing from these sessions,
and keeps no picture or text once it has answered.

## Quick checks

```bash
curl -s http://deepmagpie:12434/v1/models | jq '.data[] | select(.hemera.kind=="transcription" or .hemera.kind=="voice-changer") | .id'
curl -s http://deepmagpie:12434/v1/audio/voices | jq '.data[].id'
curl -s -F image=@screenshot.png -F 'options={"languages":["id","auto"]}' http://deepmagpie:12434/v1/ocr | jq -r .text
# a WebSocket smoke test, if websocat is installed:
websocat -t "ws://deepmagpie:12434/v1/realtime?intent=transcription" --protocol realtime
```
