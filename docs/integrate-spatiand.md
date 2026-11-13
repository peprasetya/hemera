# Integrating hemera's speech into spatiand

A hand-off note for whoever builds voice into spatiand, the stereoscopic 3D desktop (macOS,
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
`text` → chat models for clean-up; `speech` → `voxcpm2` if spatiand ever needs to talk.

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

## What stays in spatiand

Microphone capture, push-to-talk input, collecting the focus context, spoken-symbol rules and the
clean-up prompt, inserting text into the focused app, the virtual microphone, voice picking UI,
and reconnecting if the network drops. hemera stores nothing from these sessions.

## Quick checks

```bash
curl -s http://deepmagpie:12434/v1/models | jq '.data[] | select(.hemera.kind=="transcription" or .hemera.kind=="voice-changer") | .id'
curl -s http://deepmagpie:12434/v1/audio/voices | jq '.data[].id'
# a WebSocket smoke test, if websocat is installed:
websocat -t "ws://deepmagpie:12434/v1/realtime?intent=transcription" --protocol realtime
```
