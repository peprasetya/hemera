# Hemera

**A flexible wrapper and router for local AI engines — part of the [aether.ing](https://aether.ing) project.**

## Why

llama.cpp moves fast. On a single day (2026-08-04) it shipped `b10253`, `b10254`, and `b10255` —
three releases, each with a dozen platform variants. That pace is a gift and a problem: new
architectures and quantizations land almost immediately, but a build that ran your model perfectly
yesterday can regress today, and every distro package trails weeks behind.

Most wrappers pin one binary and hide it. Hemera does the opposite:

- **Every release stays available.** Binaries install side by side under
  `binary/llama.cpp/<version>/<variant>/`. Updating never removes what worked — pin a model back to
  an older build in one click when a new one misbehaves.
- **Multiple backends at once.** Keep ROCm and Vulkan (or CUDA, SYCL, CPU) installed together and
  choose per model, not per machine.
- **Routing is the point.** One GGUF file becomes as many named models as you want, each with its
  own context size, offload settings, chat template, and environment. Point a client at a name;
  Hemera starts the right binary with the right arguments and gets out of the way.
- **One endpoint, three dialects.** Ollama, OpenAI, and Anthropic APIs on the same port, so every
  tool in the stack — the `ollama` CLI, OpenAI SDKs, Claude Code — talks to the same models.
- **Remote models look local.** DeepSeek, OpenAI, Gemini, and Claude can be routed as ordinary
  model names alongside your local ones.

The engine layer is deliberately generic: llama.cpp today, with
[ds4](https://github.com/antirez/ds4) already pluggable, and vLLM or stable-diffusion.cpp to follow.

Zero dependencies: no Maven, no Gradle, no frameworks, no web server library — just a JDK 21+ and
one 112 KB jar you can drop on any machine.

## Build & run

```sh
./build.sh                  # -> hemera.jar (compiles into bin/, same output dir as Eclipse)
```

```sh
java -jar hemera.jar [--home <dir>] [--port 12434] [--log info|debug|trace]
```

Data (config `hemera.json`, engine binaries under `binary/`) lives next to the jar by default;
override with `--home` or `HEMERA_HOME`. For systemd, install [hemera.service](hemera.service) to
`/etc/systemd/system/` — logs land in `journalctl -u hemera`.

## Web UI

Open `http://host:12434/` in a browser → redirected to `/configure/`. Vanilla HTML/CSS/JS, no
third-party assets, follows your OS light/dark theme, works on phone and desktop.

- **Dashboard** — running models with ports, PIDs, and stop buttons.
- **Models** — scanned GGUF files (multi-part grouped, mmproj/mtp companions detected, ollama
  repositories read natively) with per-file usage cross-reference; configure one file as several
  named variants with different args, environment, and engine pins.
- **Engines** — installed versions/variants showing which models use each binary, a GitHub release
  browser filtered to your tracked variants, download progress, and the full rollback history.
- **Directories** — model search paths, including ollama repositories.
- **Proxies** — external providers exposed as local model names.
- **Settings** — port, log level, keep-alive, per-engine default arguments.

## APIs

| Dialect | Endpoints |
|---|---|
| Ollama | `/api/tags` `/api/ps` `/api/show` `/api/chat` `/api/generate` `/api/embed` `/api/delete` `/api/stop` `/api/version` |
| OpenAI | `/v1/models` `/v1/chat/completions` `/v1/completions` `/v1/embeddings` |
| Anthropic | `/v1/messages` `/v1/messages/count_tokens` |

The ollama CLI works unchanged: `OLLAMA_HOST=host:12434 ollama list / ps / run / stop / rm`.
`ollama rm` removes the configured model and deletes its file(s) only when no other configured
model references them — files inside an ollama repository are never touched.

Streaming, tool calling, and reasoning tokens translate across all three dialects, which is what
lets Claude Code drive a local GGUF through `/v1/messages`. Vision and other multimodal input
follow the model's own capabilities: configure an `mmproj` file and send images the ollama or
OpenAI way.

## Configuration

Everything is one JSON document (`hemera.json`), editable in the UI or by hand:

```jsonc
{
  "port": 12434,
  "logLevel": "info",              // info | debug (engine output) | trace (+ http bodies)
  "keepAliveSeconds": 300,
  "loadTimeoutSeconds": 600,
  "modelDirs": ["/srv/models/gguf", "/usr/share/ollama/.ollama/models"],
  "engines": {
    "llama.cpp": {
      "repo": "ggml-org/llama.cpp",
      "serverBin": "llama-server",
      "trackedVariants": ["ubuntu-rocm", "ubuntu-vulkan-x64"],
      "defaultArgs": ["--no-webui"],
      "installed": [ /* managed by hemera; every version kept for rollback */ ]
    },
    "ds4": { "binaryPath": "/opt/ds4/ds4", "healthPath": "/health",
             "defaultArgs": ["serve", "--port", "{port}", "--model", "{file}"] }
  },
  "models": [
    {
      "name": "qwen3-coder",
      "engine": "llama.cpp",
      "engineVersion": "latest",       // or a pinned tag like "b10254" to roll back
      "engineVariant": "rocm",         // substring match against installed variants
      "file": "/models/Qwen3-Coder-Q4_K_M.gguf",
      "mmproj": "",                    // optional projector for vision/audio
      "extraFiles": { "mtp": "/models/mtp.gguf" },   // extra flag=path pairs
      "args": ["-c", "32768", "-ngl", "99", "--jinja"],
      "env": { "HSA_OVERRIDE_GFX_VERSION": "11.5.1" },
      "keepAliveSeconds": 300
    }
  ],
  "proxies": [
    { "name": "deepseek-chat", "type": "openai",
      "baseUrl": "https://api.deepseek.com/v1", "apiKey": "sk-…", "upstreamModel": "deepseek-chat" }
  ]
}
```

Engines with a `binaryPath` (like ds4) run as-is with `{port}`, `{file}`, and `{mmproj}`
placeholders substituted into their arguments. llama.cpp binaries are downloaded per
version/variant and launched with `LD_LIBRARY_PATH` pointed at their own directory, so several
backends coexist without interfering.

## Layout

```
src/ing/aether/hemera/
  Hemera.java          entry point, route wiring
  Log.java             stdout logging (journalctl-friendly, no timestamps)
  json/Json.java       hand-rolled JSON parser/writer
  http/Router.java     routing over com.sun.net.httpserver, virtual threads
  config/Config.java   hemera.json load/save
  engine/              GitHub releases, tar/zip extraction, process supervision
  model/               directory scanning, GGUF metadata, safe deletion
  api/                 Ollama / OpenAI / Anthropic dialects, translation, proxying
  web/                 admin endpoints + embedded UI
resources/web/         index.html, app.css, app.js
```
