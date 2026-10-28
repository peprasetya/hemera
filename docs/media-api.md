# hemera media API — images, video, 3D and speech

Written for whoever builds a client on top of hemera (aether.ing first). It is self-contained:
everything a client needs to discover what a hemera server can generate and to drive it.

hemera is a router. It has no gallery, no viewer and no storage of its own beyond a short-lived
job list; a client fetches results and keeps them.

- Base URL: `http://<host>:12434`
- No authentication. CORS is open (`Access-Control-Allow-Origin: *`), so a browser can call it directly.
- Errors are always `{"error": "<message>"}` with a 4xx/5xx status. The message is written to be shown to a user.

## 1. Discovering models

```
GET /v1/models
GET /v1/models/{id}
```

The list is OpenAI-shaped, and every entry also carries a `hemera` object:

```json
{
  "object": "list",
  "data": [
    { "id": "gemma4:12b", "object": "model", "created": 1791656743, "owned_by": "hemera",
      "hemera": { "kind": "text", "backend": "llama.cpp", "endpoint": "/v1/chat/completions" } },

    { "id": "qwen-image-2.1", "object": "model", "created": 1791656743, "owned_by": "hemera",
      "hemera": { "kind": "image", "backend": "sd.cpp", "endpoint": "/v1/images/generations" } },

    { "id": "whisper-large-v3-turbo", "object": "model", "created": 1791656743, "owned_by": "hemera",
      "hemera": { "kind": "transcription", "backend": "whisper.cpp", "endpoint": "/v1/audio/transcriptions" } },

    { "id": "voxcpm2", "object": "model", "created": 1791656743, "owned_by": "hemera",
      "hemera": { "kind": "speech", "backend": "audio.cpp", "endpoint": "/v1/audio/speech" } },

    { "id": "pixal3d_int8_convrot", "object": "model", "created": 1791656743, "owned_by": "comfyui",
      "hemera": {
        "kind": "3d", "backend": "comfyui", "family": "pixal3d", "title": "Pixal3D",
        "description": "Image to 3D mesh. Follows the source image most closely ...",
        "endpoint": "/v1/3d/generations",
        "parameters": {
          "image":   { "type": "image", "required": true, "description": "..." },
          "texture": { "type": "enum", "default": "vertex", "values": ["vertex", "pbr", "none"], "description": "..." },
          "faces":   { "type": "int", "min": 5000, "max": 2000000, "description": "..." },
          "seed":    { "type": "int", "default": "random", "min": 0 }
        } } }
  ]
}
```

| `hemera` field | Meaning |
|---|---|
| `kind` | `text`, `image`, `video`, `3d`, `transcription` (speech to text), `speech` (text to speech), `voice-changer`, or `diarization` (tells speakers apart; used through `transcription`) — what the model does. Group models by this. |
| `endpoint` | Where to send a generation request for this model. |
| `backend` | `llama.cpp`, `sd.cpp`, `proxy`, `comfyui`, … Informational. |
| `family`, `title`, `description` | ComfyUI models only. `title` and `description` are fit to show to a user. |
| `parameters` | ComfyUI models only. The request fields this model accepts — see below. |

**The list is live.** ComfyUI models are discovered, not configured: when someone adds or removes
a model file in ComfyUI, the list changes within 30 seconds. Do not hard-code model ids; read the
list when the user opens a picker. If ComfyUI is down its models are simply absent.

### The same list, Ollama-style

```
GET  /api/tags
POST /api/show   {"model": "<id>"}
```

For clients built on the Ollama API. Entries have Ollama's usual fields, and every one carries
`capabilities` — Ollama's own way of saying what a model can do, extended with hemera's kinds:

```json
{ "name": "trellis_2_int8_convrot", "model": "trellis_2_int8_convrot", "size": 0, "digest": "...",
  "details": { "format": "comfyui", "family": "trellis2", ... },
  "capabilities": ["3d"],
  "hemera": { "kind": "3d", "endpoint": "/v1/3d/generations", "parameters": { ... } } }
```

| `capabilities` contains | The model |
|---|---|
| `completion` (often with `tools`, `vision`, `audio`) | chats — Ollama's standard words |
| `image` | generates images |
| `video` | generates video |
| `3d` | generates 3D meshes |
| `transcription` | turns speech into text |
| `speech` | turns text into speech |
| `voice-changer` | changes a voice live |
| `diarization` | tells speakers apart (used by `diarized_json`) |

A chat client should offer only models whose capabilities include `completion`. The `hemera`
object is identical to the one in `/v1/models` and is present for ComfyUI models. Generation
itself always goes through the `/v1/...` endpoints below; there is no Ollama-style route for it.

### Parameter descriptions

`parameters` maps a request field name to how to ask for it, so a form can be generated instead
of hand-written per model:

| `type` | Send | Other keys |
|---|---|---|
| `string` | text | |
| `int`, `number` | a number | `min`, `max`; `step` (+ optional `offset`): hemera rounds to a valid value itself |
| `bool` | `true` / `false` | |
| `enum` | one of `values` | `values` |
| `image` | base64, a `data:` URL, or a multipart file | PNG, JPEG or WebP |

`required: true` marks the fields a request cannot omit. `default` is what is used when a field
is left out; `"random"` (seeds) means hemera draws one and reports it back. A parameter with no
`default` falls back to the model's own preset — leave it out unless the user set it.
Fields a model does not list are ignored, so one form can be reused across models of a kind.

Models with `backend` other than `comfyui` have no `parameters`; their fields are the fixed ones
of the endpoint (sections 2 and 3).

## 2. Images — `POST /v1/images/generations`

OpenAI's Images API. Synchronous: the reply is the image.

```bash
curl http://host:12434/v1/images/generations -H 'Content-Type: application/json' -d '{
  "model": "qwen-image-bf16", "prompt": "a red toy robot, product photo",
  "size": "1024x1024", "steps": 20, "seed": 7 }'
```

```json
{ "created": 1791656858, "output_format": "png", "seed": 7, "data": [ { "b64_json": "iVBORw0..." } ] }
```

- Common fields: `prompt` (required), `size` (`"WxH"`), `seed`, `steps`, `cfg_scale`, `negative_prompt`.
- `"stream": true` turns the reply into server-sent events ending in `image_generation.completed`
  (same payload fields plus `b64_json`). Closing the stream cancels the generation.
- Without streaming, send an `X-Hemera-Request-Id: <id>` header and cancel with
  `POST /hemera/api/images/cancel {"id":"<id>"}`.
- `POST /v1/images/edits` (reference images + instruction) works for `sd.cpp` image models only.

## 3. Video — `/v1/videos`

OpenAI's Videos API. Asynchronous: create a job, poll it, download the file.

```bash
curl http://host:12434/v1/videos -H 'Content-Type: application/json' -d '{
  "model": "ltx25_uncensored_v1.1-fp8", "prompt": "a cat walks along a rainy neon alley, soft rain sound",
  "size": "768x448", "seconds": 4 }'
```

| Call | Purpose |
|---|---|
| `POST /v1/videos` | Start. JSON, or `multipart/form-data` with the images as files. |
| `GET /v1/videos/{id}` | Status. |
| `GET /v1/videos/{id}/content` | The file (`video/mp4` from ComfyUI models). `409` until completed. |
| `DELETE /v1/videos/{id}` | Cancel a running job, or forget a finished one. |
| `GET /v1/videos` | All jobs hemera still remembers, newest first. |

Request fields: `model`, `prompt` (required), `size`, `seconds` (or `frames`), `fps`, `seed`,
`steps`, `cfg_scale`, `negative_prompt`, `input_reference` or `image` (first frame — makes it
image-to-video), `end_image` (last frame, where the model lists it). ComfyUI video models
generate a soundtrack with the picture; describe the sound in the prompt.

```json
{ "id": "video_8c3f...", "object": "video", "model": "ltx25_uncensored_v1.1-fp8",
  "status": "in_progress", "progress": 42, "created_at": 1791657044,
  "size": "768x448", "seconds": 4 }
```

`status` is `queued` → `in_progress` → `completed` | `failed` | `cancelled`. On completion the
object gains `completed_at`, `content_type` and `seed`; on failure
`"error": {"code": "failed", "message": "..."}`. Finished videos are kept in memory for two hours.

## 4. 3D — `/v1/3d/generations`

OpenAI defines no 3D endpoint, so this is hemera's own. It deliberately mirrors the Videos API —
same lifecycle, same status values — so one piece of client code can drive both.

| Call | Purpose |
|---|---|
| `POST /v1/3d/generations` | Start. JSON, or `multipart/form-data` with the image as a file. |
| `GET /v1/3d/generations/{id}` | Status. |
| `GET /v1/3d/generations/{id}/content` | The mesh, `model/gltf-binary` (`.glb`). `409` until completed. |
| `DELETE /v1/3d/generations/{id}` | Cancel a running job, or forget a finished one. |
| `GET /v1/3d/generations` | All jobs hemera still remembers, newest first. |

```bash
# multipart
curl http://host:12434/v1/3d/generations -F model=trellis_2_int8_convrot -F image=@teapot.png -F texture=vertex

# JSON
curl http://host:12434/v1/3d/generations -H 'Content-Type: application/json' -d '{
  "model": "pixal3d_int8_convrot", "image": "data:image/png;base64,iVBORw0...", "texture": "pbr" }'
```

Request fields: `model` and `image` are required; everything else comes from the model's
`parameters`. `output_format` may be sent but only `glb` exists. The fields in use today:

| Field | Models | Meaning |
|---|---|---|
| `image` | all | One object, whole, ideally on a plain background. |
| `texture` | TRELLIS.2, Pixal3D | `vertex` (default) — colour per vertex on a dense mesh, minutes. `pbr` — UV-unwrapped mesh with base colour, metallic, roughness, normal and occlusion maps, tens of minutes. `none` — geometry only. |
| `faces` | TRELLIS.2, Pixal3D | Triangle budget. Default 700000 for `vertex`, 200000 for `pbr`. |
| `texture_size` | TRELLIS.2, Pixal3D | PBR map side in pixels (512–4096), default 2048. |
| `remove_background` | TRELLIS.2, Pixal3D | Default `true`. Send `false` for an image that is already cut out (transparent). |
| `steps`, `cfg_scale`, `resolution` | Hunyuan3D 2.1 | Sampling steps, guidance, surface resolution (128–512). |
| `seed` | all | Reproducibility. |

Hunyuan3D 2.1 returns geometry without colour; it has no `texture` parameter.

The job object:

```json
{ "id": "3d_8c3cc8d0de436", "object": "3d.generation", "model": "hunyuan_3d_v2.1",
  "status": "completed", "progress": 100,
  "created_at": 1791656870, "completed_at": 1791657032,
  "parameters": { "steps": 30, "cfg_scale": 5, "resolution": 256, "seed": 7 },
  "output": { "format": "glb", "content_type": "model/gltf-binary",
              "url": "/v1/3d/generations/3d_8c3cc8d0de436/content" } }
```

- `status`: `queued` → `in_progress` → `completed` | `failed` | `cancelled`.
- `progress`: 0–100, an estimate from how much of the workflow has run. It moves unevenly; show it
  as a bar, do not compute a time remaining from it.
- `parameters`: the values actually used, defaults and the drawn seed included — store these with
  the mesh to make a result reproducible.
- `output` appears on completion; `error` (`{"code","message"}`) on failure or cancellation.
- Meshes are 10–100 MB. `content` streams, and supports `HEAD` for the size.
- hemera remembers finished jobs for a day (64 at most) and forgets them when it restarts.
  **Download the mesh when the job completes and store it yourself.**

Displaying a `.glb` in a browser needs nothing from hemera: `<model-viewer src="...">`, three.js
`GLTFLoader` and Babylon.js all load it directly from the `content` URL.

## 5. Speech recognition — `/v1/audio/transcriptions`

```
POST /v1/audio/transcriptions   multipart: file, model, [language], [prompt], [response_format], [temperature]
POST /v1/audio/translations     multipart: file, model, [prompt], [response_format], [temperature]
```

OpenAI's Audio API. Any OpenAI SDK works by pointing its base URL at hemera.

| Field | Notes |
|---|---|
| `file` | The recording. WAV, MP3, FLAC, and whatever ffmpeg reads: WebM/Ogg from `MediaRecorder`, M4A from phones. |
| `model` | A model whose `hemera.kind` is `transcription`. |
| `language` | ISO-639-1 (`id`, `en`, `ja`, …). Leave it out to have it detected; give it when you know it, which is faster and avoids a wrong guess on short clips. |
| `prompt` | Text the speech is likely to follow or contain: the conversation so far, names, jargon. It steers spelling and vocabulary, not content. Whisper reads only its last ~220 tokens (roughly 150 words), so put the most relevant text last. |
| `response_format` | `json` (default, `{"text": "..."}`), `text`, `srt`, `vtt`, `verbose_json` (language, duration, segments with timings and words) or `diarized_json` (who said what — below). |
| `temperature` | `0` for the most literal result. |

`stream`, `timestamp_granularities[]` and `include[]` are accepted and ignored: the answer always
comes whole, and `verbose_json` always carries both segment and word timings.

`/v1/audio/translations` answers in English whatever was spoken. Whisper's **turbo** models cannot
translate (they were trained only to transcribe) and are refused with a 400 that says so; use a
full model such as `whisper-large-v3` for this.

```js
const form = new FormData();
form.append("model", "whisper-large-v3-turbo");
form.append("language", "id");
form.append("prompt", lastFewMessages);        // context for names and jargon
form.append("file", recordedBlob, "speech.webm");
const { text } = await (await fetch(base + "/v1/audio/transcriptions", { method: "POST", body: form })).json();
```

For dictation, send one utterance per request (cut at the pause), not a running recording: a
few seconds of speech comes back in about a third of a second once the model is loaded. Long
recordings work too; a 21-minute file takes about half a minute.

### Who said what — `response_format=diarized_json`

OpenAI's speaker-labelled transcript, for meetings and interviews:

```json
{ "task": "transcribe", "duration": 81.3, "text": "Madagaskar sejauh ini ... Jika Anda ingin ...",
  "segments": [
    { "type": "transcript.text.segment", "id": "seg_0", "start": 0.92, "end": 7.52, "speaker": "A",
      "text": "Madagaskar sejauh ini adalah yang benua terbesar dan tersendiri dalam hal satwa liar." },
    { "type": "transcript.text.segment", "id": "seg_1", "start": 11.65, "end": 21.83, "speaker": "B",
      "text": "Jika Anda ingin berada dekat dengan pertunjukannya, maka Anda harus datang lebih awal ..." } ] }
```

Speakers are `A`, `B`, `C`, … in the order they first speak, up to eight. A separate model finds
who spoke when, and each speaker's turn is then transcribed on its own, so a sentence is never
split across two people. Labels are per recording: the same person can be `A` in one and `B` in
the next. An 80-second recording takes about 4 s; send the whole meeting, not pieces of it, so
the labels stay consistent. `language` and `prompt` (a glossary of names and terms) apply to every turn.

### Live — `/v1/realtime` (WebSocket)

For captions, dictation and calls: stream audio in, get text back as it is spoken. This is the
transcription side of OpenAI's Realtime API; there is no voice answering back.

```js
const ws = new WebSocket("ws://host:12434/v1/realtime?intent=transcription", ["realtime"]);
ws.onopen = () => ws.send(JSON.stringify({ type: "transcription_session.update", session: {
  input_audio_format: "pcm16",                    // 16-bit mono, 24 kHz unless the GA form says otherwise
  input_audio_transcription: { model: "whisper-large-v3-turbo", language: "id", prompt: "" },
  turn_detection: { type: "server_vad", silence_duration_ms: 500 }   // null = you commit
} }));
// then, every 100 ms or so:
ws.send(JSON.stringify({ type: "input_audio_buffer.append", audio: base64Pcm16 }));
ws.onmessage = e => {
  const ev = JSON.parse(e.data);
  if (ev.type === "conversation.item.input_audio_transcription.delta") showDraft(ev.item_id, ev.delta);
  if (ev.type === "conversation.item.input_audio_transcription.completed") showFinal(ev.item_id, ev.transcript);
};
```

The GA spelling works as well: `session.update` with `session.type: "transcription"` and
`session.audio.input.{format, transcription, turn_detection}`, where `format` is
`{type: "audio/pcm", rate}`, `{type: "audio/pcmu"}` or `{type: "audio/pcma"}`.

| You send | |
|---|---|
| `input_audio_buffer.append` | Base64 audio: `pcm16` (any rate 8–48 kHz; OpenAI allows only 24 kHz), or telephone `g711_ulaw` / `g711_alaw` at 8 kHz. |
| `input_audio_buffer.commit` | End the utterance now (push-to-talk). Needed when `turn_detection` is `null`. |
| `input_audio_buffer.clear` | Throw away what has not been committed. |

| You get | |
|---|---|
| `input_audio_buffer.speech_started` / `speech_stopped` | hemera heard someone start / pause (`audio_start_ms`, `audio_end_ms` from the session start). |
| `input_audio_buffer.committed` | The utterance is complete and being transcribed. |
| `conversation.item.input_audio_transcription.delta` | More words of the utterance, while it is still being spoken. |
| `conversation.item.input_audio_transcription.completed` | The utterance's text. **This is the one to keep.** |
| `error` | Something in the last event was wrong; the session carries on. |

How it behaves:

- **Show deltas as a draft, replace them with `completed`.** Whisper does not stream, so hemera
  re-reads the utterance about once a second and sends words two readings agree on. The first
  delta comes 2–3 s into an utterance; the final text has punctuation and corrections the
  deltas lack ("gambing" became "Kambing").
- **The final text comes 0.3–0.7 s after the speaker pauses**; three sessions speaking at the
  very same moment wait up to 1.6 s. Utterances longer than 29 s are cut and continue as the
  next item.
- **Context carries over.** Each utterance is read with the session's `prompt` plus the last few
  hundred characters already transcribed, so names and spelling stay consistent. Put a glossary
  or the conversation so far in `prompt`, and update it with another `session.update` at any time.
- **The model stays loaded while the socket is open** and is never unloaded to make room for
  something else. Close the socket when you are done.
- Pause detection is by loudness above the room's background noise. `threshold` (0–1, default
  0.5) makes it less eager to call something speech, `silence_duration_ms` (default 500) how long
  a pause ends an utterance; dictation is usually happier with 800–1000.

## 6. Text to speech — `/v1/audio/speech`

```
POST /v1/audio/speech   JSON: model, input, [voice], [instructions], [response_format], [speed], [stream_format]
```

OpenAI's speech API; any OpenAI SDK works against hemera.

| Field | Notes |
|---|---|
| `model` | A model whose `hemera.kind` is `speech`. |
| `input` | The text. Indonesian, English and mixtures of the two are fine with `voxcpm2`. |
| `voice` | A name from hemera's voice library (below) to speak in that person's voice, or a voice the model has built in. OpenAI's names (`alloy`, `nova`, …) mean "the model's default voice". |
| `instructions` | A description of the voice to make up when no `voice` is given: "a young woman, warm and clear", "an elderly man, slow and calm". |
| `response_format` | `mp3` (default), `opus` (Ogg), `aac`, `flac`, `wav`, or `pcm` (raw 16-bit mono at 24 kHz). |
| `speed` | A multiplier, for the models that support it. |
| `stream_format` | `sse` to receive audio while it is being made, as `speech.audio.delta` events (base64 PCM, 16-bit mono 24 kHz) and a final `speech.audio.done`; `audio` for the same PCM as a plain chunked body. |

```js
// play as it arrives
const res = await fetch(base + "/v1/audio/speech", { method: "POST", headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ model: "voxcpm2", input: text, voice: "paulus", stream_format: "sse" }) });
for await (const ev of readSse(res.body)) {
  if (ev.type === "speech.audio.delta") player.enqueue(base64ToInt16(ev.audio), 24000);
}
```

With `voxcpm2`, streamed audio starts 0.15 s after the request and a sentence is made about 2.5×
faster than it is spoken. Models that cannot stream (the `hemera` entry does not say; try it)
answer in the same shape, all at once when they are done.

### Voices

The voice library is shared by all speech models: a recording of someone and what they say in it.

```
GET    /v1/audio/voices                 the library: [{id, transcript, owner, consent, created_at, duration_sec}]
POST   /v1/audio/voices                 multipart: name, file, consent, [transcript], [owner], [language]
GET    /v1/audio/voices/{name}          one voice's details
GET    /v1/audio/voices/{name}/audio    its recording (WAV)
DELETE /v1/audio/voices/{name}
```

- **The recording**: 5–20 s of one person speaking clearly, in any format; longer is cut to 30 s.
  Natural, conversational speech gives a natural voice; a slow reading gives a slow one.
- **`transcript`** is what is said in the recording. Leave it out and hemera writes it with its
  speech recogniser; giving it (or correcting it) makes the clone closer.
- **`consent` is required**: whose voice it is and that they agreed, e.g. `"my own voice"` or
  `"recorded with Maria's permission for the parish announcements"`. It is stored with the voice
  and shown in the list.
- `name` is lowercase letters, digits, `-` and `_`, and cannot be one of OpenAI's voice names.
- The web UI's **Voices** tab does the same by hand: upload, listen, delete, and try a voice.

### Live voice changing — `/v1/audio/voice-changer` (WebSocket)

Someone speaks; the same words come out in a voice from the library, about 0.1–0.15 s later.
There is no OpenAI API for this, so the protocol is hemera's:

```js
const ws = new WebSocket("ws://host:12434/v1/audio/voice-changer?model=meanvc2&voice=maria&sample_rate=48000");
ws.binaryType = "arraybuffer";
micNode.port.onmessage = e => ws.send(e.data);            // Int16Array frames, 20–100 ms each
ws.onmessage = e => typeof e.data === "string"
  ? console.log(JSON.parse(e.data))                        // session.created / session.updated / error
  : speakerNode.port.postMessage(new Int16Array(e.data));  // the converted audio, same format
// change voice without reconnecting:
ws.send(JSON.stringify({ type: "session.update", voice: "narrator" }));
```

| | |
|---|---|
| `model` | A model whose `hemera.kind` is `voice-changer` (the first one if left out). |
| `voice` | A name from the voice library. Required. |
| `sample_rate` | Of the audio you send and get back: 8000–48000, default 24000. |
| Audio | Binary messages, 16-bit little-endian mono PCM, both ways. |

It works for any language. A browser can only open the microphone on an `https://` or
`localhost` page.

## 7. Live work first

A call, a dictation or a voice being changed cannot wait; a long subtitle job can. Mark bulk
requests with a header:

```
X-Hemera-Priority: background
```

While any live session is open (a `/v1/realtime` or voice-changer socket, or a streamed
`/v1/audio/speech`), hemera holds background requests before they start and lets them go when the
last live session ends. A request that is already running finishes first, so a job that sends
its work in pieces of a few seconds pauses within one piece. Requests without the header are
never held: a dictation sent to `/v1/audio/transcriptions` goes straight through during a call.
Give background requests a long client timeout; they may wait as long as a call lasts.

## 8. A client loop for video and 3D

```js
async function generate(base, endpoint, form, onProgress) {
  let job = await (await fetch(base + endpoint, { method: "POST", body: form })).json();
  if (job.error) throw new Error(job.error);
  while (job.status === "queued" || job.status === "in_progress") {
    await new Promise(r => setTimeout(r, 2000));
    job = await (await fetch(`${base}${endpoint}/${job.id}`)).json();
    onProgress(job.status, job.progress);
  }
  if (job.status !== "completed") throw new Error(job.error ? job.error.message : job.status);
  return await (await fetch(`${base}${endpoint}/${job.id}/content`)).blob();
}
// cancel: fetch(`${base}${endpoint}/${id}`, { method: "DELETE" })
```

`endpoint` is the model's `hemera.endpoint`; `form` is a `FormData` with `model` plus the fields
built from `hemera.parameters`.

## 9. Things worth knowing

- **One at a time.** The machine has one GPU. Jobs for the same model queue inside hemera, jobs
  for different ComfyUI models queue inside ComfyUI; either way a job reports `queued` until it
  is its turn. Nothing is rejected for being busy.
- **First use is slow.** A model is loaded on first use (tens of seconds, up to a few minutes for
  the largest), then stays loaded for five minutes of idleness.
- **Typical times** on the reference machine (Ryzen AI Max+ 395): image 1–2 min; Hunyuan3D 2–3 min;
  TRELLIS.2 / Pixal3D with vertex colours 5–10 min, with `pbr` 20–40 min; video 2–10 min depending
  on size and length; speech about 35× faster than it was spoken with `whisper-large-v3-turbo`
  (0.3 s for a short utterance), about 13× with `whisper-large-v3` (0.9 s), which makes
  fewer mistakes (7% of words against 8% in Indonesian).
- **Speech requests queue too.** A speech model works on one recording at a time; requests made
  together are answered one after another (three short utterances: 0.3, 0.6 and 0.9 s).
- **Text models** are a separate matter: `/v1/chat/completions` (OpenAI), `/v1/messages`
  (Anthropic) and `/api/chat` (Ollama) all work, with streaming.
