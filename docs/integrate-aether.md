# Integrating hemera's speech into aether.ing

A hand-off note for whoever builds aether.ing's voice features. It stands alone; the full API
reference is [media-api.md](media-api.md) (in the hemera repository, `docs/media-api.md`).

## What hemera is, in one paragraph

hemera is a model router on `deepmagpie`, reachable at `http://deepmagpie:12434` over the home
network or Tailscale. It speaks the OpenAI API (and Ollama's and Anthropic's), starts and stops
the engines behind each model by itself, and manages the machine's memory. **aether never needs
to know which engine runs a model, load anything, or free memory**: it asks hemera for a model by
name and hemera handles the rest. No authentication; CORS is open, so a browser can call it
directly. Errors are always `{"error": "<message fit to show a user>"}`.

## Pick models by kind, never by hard-coded name

`GET /v1/models` lists everything, each entry with a `hemera.kind` and `hemera.endpoint`:

| `kind` | Use it for | Endpoint | Today on deepmagpie |
|---|---|---|---|
| `text` | chat, agents | `/v1/chat/completions` | qwen3.8, gemma4, … |
| `transcription` | speech → text | `/v1/audio/transcriptions`, `/v1/realtime` | `whisper-large-v3-turbo` (fast, for anything live), `whisper-large-v3` (most accurate, can translate) |
| `speech` | text → speech | `/v1/audio/speech` | `voxcpm2` (streams, Apache-2.0), `voxcpm2-npu` (the same on the NPU, never pauses the LLM), `omnivoice` (non-commercial license) |
| `voice-changer` | live voice conversion | `/v1/audio/voice-changer` | `meanvc2` |
| `diarization` | used inside transcription for speaker labels | — | `nemotron-diarization` |
| `image`, `video`, `3d` | as before | see media-api.md | |

Read the list when a picker opens; the names can change.

## 1. Voice-over (text to speech)

OpenAI's speech API. Any OpenAI SDK works with `baseURL: "http://deepmagpie:12434/v1"`.

```js
const res = await fetch(HEMERA + "/v1/audio/speech", {
  method: "POST", headers: { "Content-Type": "application/json" },
  body: JSON.stringify({
    model: "voxcpm2",
    input: "Selamat pagi semuanya. Good morning, everyone.",
    voice: "narrator",            // a name from the voice library (section 3), or leave out
    instructions: "",             // OR describe a voice instead: "an elderly man, slow and calm"
    response_format: "mp3"        // mp3 (default) | opus | aac | flac | wav | pcm
  })
});
const audio = await res.blob();   // audio/mpeg
```

- **Languages**: Indonesian, English and mixed sentences ("cek lagi schedule-nya") all work with
  `voxcpm2`. Latin liturgical words are the weak spot ("Kyrie" comes out wrong); spelling them
  phonetically in the input may help.
- **A voice**: either `voice` (a cloned voice from the library) or `instructions` (a voice
  described in words). OpenAI's names (`alloy`, `nova`, …) just mean "the default voice".
- **Two models, your choice.** `voxcpm2` and `voxcpm2-npu` take the same requests, voices and
  languages at the same quality. Offer both in the picker or choose per use; hemera does not choose
  for you. They differ in this:
  - **`voxcpm2` runs on the GPU and pauses hemera's LLMs while speech is made**, because the shared
    GPU cannot do both smoothly (see section 4). You need do nothing, but a chat stream's tokens
    stop for those seconds. A sentence takes roughly 40% of its spoken length to make (about 1.7 s
    for 4 s of speech), whatever else is running.
  - **`voxcpm2-npu` runs on the NPU and pauses nothing**, so the LLM keeps generating. A sentence
    takes about 55% of its spoken length, and 80–97% next to a generating LLM: just ahead of the
    listener, with no dropouts when measured. The first request after the model loads and the first
    use of a library voice take about 4 s to the first audio.
- **Long voice-over jobs** (a whole article, a batch of clips): send the header
  `X-Hemera-Priority: background`. They then wait while anyone is in a live call or dictation on
  the machine (see section 6), and do not pause the LLMs. Without the pause `voxcpm2` runs far slower
  next to a generating LLM (about 0.5× realtime), which is fine when nobody listens live, but
  do not stream those to a listener.

### Streaming, for anything conversational

Add `"stream_format": "sse"`: audio arrives as it is made, the first piece about 0.25 s after
the request with `voxcpm2`, 0.25–0.4 s with `voxcpm2-npu`.

```
data: {"type":"speech.audio.delta","audio":"<base64 PCM>"}
data: {"type":"speech.audio.delta","audio":"..."}
data: {"type":"speech.audio.done"}
```

The PCM is always 16-bit little-endian mono at **24 kHz**, whatever the model's own rate.
`"stream_format": "audio"` gives the same PCM as a plain chunked body instead of events. A model
that cannot stream (`omnivoice`) answers in the same shape, all at once at the end.

## 2. Dictation into aether's prompt box

Two ways; pick by how the recording reaches hemera.

**A. One request per utterance (simplest).** Record until the user stops (button or a pause),
then:

```js
const form = new FormData();
form.append("model", "whisper-large-v3-turbo");
form.append("language", "id");                 // leave out to detect; giving it is faster and safer on short clips
form.append("prompt", recentChatText);         // context: see below
form.append("file", blob, "speech.webm");      // MediaRecorder's webm/ogg is fine, as are wav/mp3/m4a
const { text } = await (await fetch(HEMERA + "/v1/audio/transcriptions", { method: "POST", body: form })).json();
```

About 0.3 s for a few seconds of speech once the model is loaded.

**B. Live, text appearing while the user speaks** — OpenAI's Realtime transcription over WebSocket:

```js
const ws = new WebSocket("ws://deepmagpie:12434/v1/realtime?intent=transcription", ["realtime"]);
ws.onopen = () => ws.send(JSON.stringify({ type: "transcription_session.update", session: {
  input_audio_format: "pcm16",                       // 16-bit mono 24 kHz
  input_audio_transcription: { model: "whisper-large-v3-turbo", language: "id", prompt: recentChatText },
  turn_detection: null                               // push-to-talk: you say when it ends
}}));
// while the button is held, every ~100 ms:
ws.send(JSON.stringify({ type: "input_audio_buffer.append", audio: base64Pcm16 }));
// on release:
ws.send(JSON.stringify({ type: "input_audio_buffer.commit" }));
ws.onmessage = e => {
  const ev = JSON.parse(e.data);
  if (ev.type === "conversation.item.input_audio_transcription.delta") showDraft(ev.delta);
  if (ev.type === "conversation.item.input_audio_transcription.completed") insert(ev.transcript);
};
```

With `turn_detection: { type: "server_vad", silence_duration_ms: 800 }` instead of `null`, hemera
cuts utterances at the pauses itself. **Show deltas as a grey draft and replace them with
`completed`**: the final text is corrected and punctuated, the deltas are not.

**Context (`prompt`) is what makes dictation good.** Put the last few chat messages, names, and
product or church terms there. Whisper reads only the last ~150 words of it, so put the most
relevant text last. In the Realtime session, send another `transcription_session.update` with a
new `prompt` whenever the conversation moves on.

## 3. The voice library

Shared by every speech model and the voice changer. aether can offer its own UI on top of it, or
send users to hemera's web UI (`http://deepmagpie:12434/configure/#voices`).

```
GET    /v1/audio/voices                 → {"data": [{id, transcript, owner, consent, created_at, duration_sec}]}
POST   /v1/audio/voices                 multipart: name, file, consent, [transcript], [owner], [language]
GET    /v1/audio/voices/{name}/audio    the recording (WAV), for a play button
DELETE /v1/audio/voices/{name}
```

- The recording: 5–20 s of one person speaking naturally, any audio format. A slow read-aloud
  recording gives a slow voice.
- `consent` is required: whose voice it is and that they agreed (`"my own voice"`, `"recorded with
  Maria's permission for the parish announcements"`). Show it next to the voice.
- Without `transcript`, hemera writes one with whisper.
- `name`: lowercase letters, digits, `-`, `_`.

## 4. Real-time voice conversation (aether's WebRTC)

aether owns the WebRTC side. hemera supplies the three pieces of a cascade, which aether's server
chains for each call:

```
caller audio ─WebRTC→ aether server ─WS /v1/realtime (server_vad)→ text
             text + history → /v1/chat/completions (stream) → reply sentences
             each sentence → /v1/audio/speech (stream_format: sse) → 24 kHz PCM ─WebRTC→ caller
```

- Speak the reply **sentence by sentence** as the LLM streams it, not after it finishes.
- **With `voxcpm2`, hemera freezes the LLM while each speech request runs**: tokens stop for about
  40% of that sentence's spoken length, then carry on. An LLM writes faster than anyone speaks, so
  the reply stays ahead of the voice. Without this the voice would stutter badly. Do not treat a
  pause in tokens as a stalled stream, and keep any "no token for N seconds" timeout well above a
  sentence. With `voxcpm2-npu` nothing freezes and tokens keep coming while the voice is made.
- Budget, roughly: end of the caller's sentence → final transcript 0.3–0.7 s; LLM first sentence
  depends on the model; first audio of the reply 0.25–0.4 s after each speech request.
- `/v1/realtime` keeps its model loaded while the socket is open; open it when the call starts,
  close it when the call ends.
- For telephone audio, the Realtime session also takes `g711_ulaw` / `g711_alaw` at 8 kHz.

## 5. Meeting notes (if aether does them)

Send the whole recording once, asking for speaker labels:

```js
form.append("model", "whisper-large-v3-turbo");
form.append("response_format", "diarized_json");
form.append("prompt", "Romo Yohanes, Bu Maria, Kyrie, Agnus Dei, komuni");   // names and terms
```

```json
{ "text": "...", "duration": 81.3,
  "segments": [ { "speaker": "A", "start": 0.92, "end": 7.52, "text": "..." },
                { "speaker": "B", "start": 11.65, "end": 21.83, "text": "..." } ] }
```

Speakers are `A`, `B`, … per recording (up to eight); naming them is aether's job (ask the user,
or let the LLM guess from the content). Then summarise with a chat model. Several phones in one
room: choose the clearest phone per stretch of speech before sending; that combining is the
meeting app's job, not hemera's.

## 6. Live work first

hemera holds requests carrying `X-Hemera-Priority: background` while any live session runs (a
Realtime socket, a voice changer, a streamed speech request). Mark bulk work with it (long
voice-overs, transcribing archives) and give those requests a long timeout. **Never mark
interactive requests**: dictation and anything a user is waiting on must go without it.

## What stays in aether

The WebRTC transport, the conversation loop and turn-taking, choosing models from `/v1/models`,
the prompt context for dictation, naming speakers, summaries, and any UI. hemera does not store
transcripts, audio or conversations, apart from the voice library.

## Quick checks

```bash
curl -s http://deepmagpie:12434/v1/models | jq '.data[] | {id, kind: .hemera.kind}'
curl -s http://deepmagpie:12434/v1/audio/speech -H 'Content-Type: application/json' \
  -d '{"model":"voxcpm2","input":"Halo, apa kabar?"}' -o hello.mp3
curl -s http://deepmagpie:12434/v1/audio/transcriptions -F model=whisper-large-v3-turbo -F file=@hello.mp3
```
