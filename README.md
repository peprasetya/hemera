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
`/etc/systemd/system/` — logs land in `journalctl -u hemera`. That unit sets
`OOMPolicy=continue`: engines are children of hemera and share its cgroup, so without it a model
too large for RAM takes the whole gateway down with it. Hemera also marks engine processes as
preferred OOM victims, so an oversized model costs you that model rather than every loaded model.

## Web UI

Open `http://host:12434/` in a browser → redirected to `/configure/`. Vanilla HTML/CSS/JS, no
third-party assets, follows your OS light/dark theme, works on phone and desktop.

- **Dashboard** — running models with ports, PIDs, and stop buttons.
- **Models** — scanned GGUF files (multi-part grouped, vision projectors paired automatically,
  ollama repositories read natively) with per-file usage cross-reference; configure one file as
  several named variants with different args, environment, and engine pins.
- **Engines** — installed versions/variants showing which models use each binary, whether the GPU
  runtime each build needs was actually found, a GitHub release browser filtered to your tracked
  variants, download progress, and the full rollback history. **Add engine** registers a binary you
  installed yourself, such as ds4.
- **Directories** — model search paths, including ollama repositories.
- **Proxies** — external providers exposed as local model names.
- **Settings** — port, log level, keep-alive, per-engine default arguments.

## Vision projectors

An `mmproj` projector only works with the model it shipped with — pairing one model's projector
with another produces garbage output. So hemera pairs them for you rather than offering a list to
choose from: a projector found in a model's own directory is attached to that model, and for
ollama models the projector layer is read from the manifest. Configuring a model shows the
detected projector with a checkbox to leave it off; there is no cross-directory picker.

Some models have no separate projector because the vision (or audio) tower is embedded in the
model file itself. Hemera detects that from the GGUF metadata and labels it, rather than implying
the model is text-only. Whether images then work depends on the engine build: upstream llama.cpp
rejects the combined file, while ollama's fork loads it when `file` and `mmproj` point at the same
path — see Custom engines.

## Custom engines

Engines with a `repo` are downloaded and version-managed by hemera. Engines with a `binaryPath`
run a binary you installed yourself — **Add engine** in the Engines tab, with a preset for
[ds4](https://github.com/antirez/ds4) (DwarfStar). Use `{port}`, `{file}` and `{mmproj}` in the
arguments and hemera substitutes them at launch:

```jsonc
"ds4": {
  "binaryPath": "binary/ds4/ds4-server",
  "healthPath": "/v1/models",
  "defaultArgs": ["--host", "127.0.0.1", "--port", "{port}", "-m", "{file}"]
}
```

Runtime library detection applies to these too, so a self-installed engine gets the same
ROCm/CUDA handling as a managed one. Any binary whose name contains `llama-server` is driven in
llama.cpp style — `-m` and `--mmproj` are supplied automatically — which the `argStyle` key can
override.

That covers a useful case: **ollama's bundled fork**. Models pulled with `ollama pull` sometimes
carry metadata upstream llama.cpp does not accept yet (`qwen3.5:9b` fails with
`qwen35.rope.dimension_sections has wrong array length`), and models whose vision tower lives
inside the model file rather than a separate projector need a runtime that understands that
layout. Registering ollama's own `llama-server` as an engine runs those models, vision included:

```jsonc
"ollama-runner": {
  "binaryPath": "/usr/local/lib/ollama/llama-server",
  "healthPath": "/health",
  "defaultArgs": ["--no-webui", "--offline", "-np", "1"],
  "libraryPaths": ["/usr/local/lib/ollama", "/usr/local/lib/ollama/rocm_v7_2"]
}
```

Point `libraryPaths` at the backend subdirectory for your GPU (`rocm_v*`, `vulkan`, `cuda_v*`).
For a model with an embedded tower, set both `file` and `mmproj` to the same path.

## Finding GPU runtimes

A llama.cpp ROCm or CUDA build needs vendor libraries that are usually *not* on the default loader
path — and when they are missing the engine does not fail, it quietly runs on CPU at a fraction of
the speed. Hemera detects this instead of leaving you to guess.

Before launching an engine it reads the ELF headers of the build's own binaries to learn which
shared libraries they ask for, discards the ones the dynamic loader can already resolve, and
searches well-known runtime locations for the rest, following transitive dependencies. Whatever it
finds is added to `LD_LIBRARY_PATH` for that process only; whatever it cannot find is reported:

```
INFO  [instance] Runtime libraries for 'qwen3-coder' found in /opt/rocm/lib
WARN  [instance] 'qwen3-coder' may fall back to CPU: 3 libraries not found
                 (libamdhip64.so.7, librocblas.so.5, libhipblas.so.3) ...
```

Nothing is vendor-specific or version-specific: ROCm, CUDA, oneAPI/SYCL and OpenVINO are covered by
the same mechanism, versioned and symlinked layouts (`/opt/rocm/lib` → `core-7.14`,
`/usr/local/cuda-12.4`) are matched by glob, and a build linked against ROCm 7.2 happily uses a
ROCm 7.14 install because only the SONAME major versions have to agree. Nothing is written outside
`hemera.json` and no root is required.

The Engines tab shows the result per installed binary. To cover an unusual location, add a glob to
`librarySearchRoots` (global) or a directory to an engine's `libraryPaths` (highest priority), both
editable under Settings; `libraryAutoDetect: false` turns the search off entirely.

## APIs

| Dialect | Endpoints |
|---|---|
| Ollama | `/api/tags` `/api/ps` `/api/show` `/api/chat` `/api/generate` `/api/embed` `/api/delete` `/api/stop` `/api/version` |
| OpenAI | `/v1/models` `/v1/chat/completions` `/v1/completions` `/v1/embeddings` |
| Anthropic | `/v1/messages` `/v1/messages/count_tokens` |

The ollama CLI works unchanged: `OLLAMA_HOST=host:12434 ollama list / ps / run / stop / rm`.
Unlike upstream ollama, `ollama rm` (and **Remove** in the UI) only drops the configuration —
model files are never deleted, since they are shared between configurations and expensive to
re-obtain. Deleting a file is a separate, deliberate action in the Models tab that names every
configured model it will take with it and asks you to type the filename to confirm. Files inside
an ollama repository and files outside your configured model directories can never be deleted.

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
  "librarySearchRoots": [],         // extra globs for GPU runtimes; built-ins cover the usual paths
  "engines": {
    "llama.cpp": {
      "repo": "ggml-org/llama.cpp",
      "serverBin": "llama-server",
      "trackedVariants": ["ubuntu-rocm", "ubuntu-vulkan-x64"],
      "defaultArgs": ["--no-webui"],
      "libraryAutoDetect": true,    // find ROCm/CUDA/oneAPI/OpenVINO libs automatically
      "libraryPaths": [],           // explicit dirs, prepended to the search
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
  engine/              GitHub releases, tar/zip extraction, ELF/library discovery,
                       process supervision
  model/               directory scanning, GGUF metadata, safe deletion
  api/                 Ollama / OpenAI / Anthropic dialects, translation, proxying
  web/                 admin endpoints + embedded UI
resources/web/         index.html, app.css, app.js
```

## License

[MIT](LICENSE)
