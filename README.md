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
- **Not only text.** Images and video through stable-diffusion.cpp, and — by fronting a ComfyUI
  install — the image, video and 3D models that only exist as ComfyUI workflows. They are all
  just model names in the same list, each saying what it makes and which endpoint to call.

The engine layer is deliberately generic: llama.cpp and stable-diffusion.cpp are managed
release by release, [ds4](https://github.com/antirez/ds4) and other servers plug in as a binary
path, and ComfyUI is bridged rather than managed.

Hemera is a router, not an application: it has no gallery, no chat window and no viewer beyond
what configuring it needs. Applications such as [aether.ing](https://aether.ing) sit on top and
discover what a hemera server offers through its API.

Zero dependencies: no Maven, no Gradle, no frameworks, no web server library — just a JDK 21+ and
one jar of about 240 KB you can drop on any machine.

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

- **Dashboard** — running models with ports, PIDs, and stop buttons, plus anything currently
  loading and any load that failed.
- **Models** — scanned GGUF files (multi-part grouped, vision projectors paired automatically,
  ollama repositories read natively) with per-file usage cross-reference; configure one file as
  several named variants with different args, environment, and engine pins. **Load** starts a
  model immediately instead of waiting for the first API request — useful for warming a large
  model or for checking that a new configuration actually runs; the load happens in the
  background and its progress, or its error, is reported as it goes. Models found in ComfyUI
  are listed here too, read-only: there is nothing to configure about them.
- **Engines** — installed versions/variants showing which models use each binary, whether the GPU
  runtime each build needs was actually found, a GitHub release browser filtered to your tracked
  variants, download progress, and the full rollback history. **Add engine** registers a binary you
  installed yourself, such as ds4.
- **Images** — try an image model without writing a curl command: generate, edit with
  reference images, stop a generation.
- **Directories** — model search paths, including ollama repositories, and where ComfyUI is:
  what hemera found there, which models it offers from it, and what is missing for the rest.
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

Ollama's backends are plugins (`libggml-hip.so`, `libggml-vulkan.so`, ...) that ggml loads with
`dlopen` at its own discretion rather than binaries linked at startup, so being on
`LD_LIBRARY_PATH` is not enough to get one picked up — without more information the process
loads fine and quietly runs on CPU. Hemera looks for a `libggml-<backend>.so` directly inside
the configured `libraryPaths` (first match wins) and points `GGML_BACKEND_PATH` at it, the same
way ollama's own launcher does.

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
| OpenAI | `/v1/models` `/v1/chat/completions` `/v1/completions` `/v1/embeddings` `/v1/images/generations` `/v1/images/edits` `/v1/videos` |
| hemera | `/v1/3d/generations` (OpenAI has no 3D endpoint; see [docs/media-api.md](docs/media-api.md)) |
| Anthropic | `/v1/messages` `/v1/messages/count_tokens` |

**What is each model for?** A server now holds chat models next to image, video and 3D ones, and
neither OpenAI's nor Ollama's model list has a field for that, so hemera adds one to each, in
that dialect's own style. `/v1/models` entries carry a `hemera` object — `kind` (`text`, `image`,
`video`, `3d`), the `endpoint` to call, and for ComfyUI models their request `parameters`, enough
to generate a form. `/api/tags` and `/api/show` carry Ollama's `capabilities` list, with `image`,
`video` and `3d` added to its `completion` / `tools` / `vision`: a chat client offers the models
that have `completion` and ignores the rest. Asking a model for the wrong thing is answered with
the endpoint that would have worked. [docs/media-api.md](docs/media-api.md) spells all of it out.

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

## Image generation (stable-diffusion.cpp)

The `sd.cpp` engine runs [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp)'s
`sd-server` and is managed exactly like llama.cpp — pick a release in the Engines tab. A diffusion
model is recognised by its tensor names (these GGUFs often carry no metadata at all), and the VAE
and text encoder sitting beside it in `vae/` and `text_encoders/` are offered automatically, so
configuring one is a single click from the scan list.

```bash
curl http://host:12434/v1/images/generations -H 'Content-Type: application/json' -d '{
  "model": "qwen-image-2.1", "prompt": "a lovely cat holding a sign that says hemera",
  "size": "1024x1024", "steps": 20, "seed": 7 }'
```

The reply is OpenAI-shaped: `data[].b64_json`. Editing takes one or more reference images, as
multipart (`image[]`, what the OpenAI SDKs send) or as JSON with base64/data-URL strings, which is
easier to drive from `curl`:

```bash
curl http://host:12434/v1/images/edits -H 'Content-Type: application/json' -d '{
  "model": "qwen-image-2.1", "prompt": "put the fox from the second image on the apple",
  "images": ["'"$(base64 -w0 apple.png)"'", "'"$(base64 -w0 fox.png)"'"], "size": "768x512" }'
```

Beyond the OpenAI fields (`prompt`, `n`, `size`, `output_format`, `output_compression`), hemera
accepts `steps`, `seed`, `cfg_scale`, `sampler`, `scheduler`, `negative_prompt` and `strength`,
and folds them into stable-diffusion.cpp's own `<sd_cpp_extra_args>` block — which you can still
write yourself for anything else in its
[API](https://github.com/leejet/stable-diffusion.cpp/blob/master/examples/server/api.md), LoRAs
and hires included.

Two behaviours are hemera's own, because the underlying endpoint is surprising:

- **Edits default to `strength: 1.0`.** `sd-server` makes the first upload an *init* image as well
  as a reference, so at its own default an instruction like "put the fox on the apple" comes back
  as a mild img2img redraw of the apple with the second image ignored. A full-strength denoise
  leaves the uploads as pure conditioning, which is what instruction-edit models are trained for.
  Pass `strength` yourself for classic img2img.
- **`background: "transparent"` works through the prompt.** Models such as Qwen-Image 2.1 emit an
  alpha channel because the prompt asked for it; there is no flag. Each model carries a
  `transparentPrompt` template (pre-filled for Qwen-Image, `{prompt}` marks the insertion point),
  and hemera wraps the request's text with it and forces a format that can hold alpha. Asking for
  transparency with `output_format: "jpeg"`, or on a model with no template, is an error rather
  than a silently opaque image.

Image models are refused by the chat, completion and embedding endpoints with a message pointing
at the right one, and vice versa.

### Cancelling

OpenAI's Images API has no cancel endpoint; hemera supports both ways a client can stop a
generation, and either one actually frees the GPU rather than just abandoning the wait:

- **Streaming** (`"stream": true`, the OpenAI way): the reply is a stream of
  `image_generation.completed` / `image_edit.completed` events, with a comment heartbeat every
  second while the image is drawn. Closing the stream is the cancel — hemera notices within about
  two seconds. `partial_images` is accepted but sd.cpp produces no previews, so only the finished
  image is sent.
- **Explicitly**, for clients that don't stream: name the request with an
  `X-Hemera-Request-Id: <your-id>` header (hemera generates one and returns it in the same header
  otherwise), then

  ```bash
  curl -X POST http://host:12434/hemera/api/images/cancel -H 'Content-Type: application/json' -d '{"id":"<your-id>"}'
  ```

  The cancelled request answers `409` with `"was cancelled"`. `{"model":"..."}` instead of an id
  stops whatever is generating on that model. `GET /hemera/api/images/jobs` lists what is running
  and queued.

sd-server cannot interrupt a generation over HTTP (it rejects that with 409 "cannot be interrupted
yet"), so a cancel stops the engine process. That costs little — sd.cpp loads its weights lazily
on the next image, from page cache — and hemera queues image requests itself, one per model, so
only the cancelled job is ever inside the engine: a request still waiting just leaves the queue,
and nobody else's work is lost.

### Video

Video models (LTX, Wan, ...) run on the same `sd.cpp` engine and are driven in the shape of
OpenAI's Videos API. A clip takes minutes, so it is a job rather than one long request:

```bash
curl http://host:12434/v1/videos -H 'Content-Type: application/json' -d '{
  "model": "ltx-2.5", "prompt": "a red convertible on a coastal road at sunset",
  "size": "1280x720", "seconds": 5, "steps": 8 }'          # -> {"id":"video_...","status":"queued"}
curl http://host:12434/v1/videos/video_...                  # queued | in_progress | completed | failed
curl http://host:12434/v1/videos/video_.../content -o clip.webm
curl -X DELETE http://host:12434/v1/videos/video_...        # cancel, or discard a finished one
```

`input_reference` (or `image`) is the start frame for image-to-video and `end_image` the last
one, as base64 or an uploaded file. `fps`, `frames`, `seed`, `cfg_scale`, `sampler`,
`negative_prompt` and `output_format` (`webm`, `webp`, `avi`) are accepted, and
`sd_cpp_extra_args` passes anything else in sd.cpp's native `vid_gen` schema. Finished videos
are held in memory for two hours. Jobs share the per-model queue with image requests, and
deleting a running one frees the GPU the same way cancelling an image does.

The same endpoint serves the video models ComfyUI offers (next section), which is where newer
ones run when stable-diffusion.cpp cannot load them yet. Those return `video/mp4` with a
soundtrack and report real `progress`.

## ComfyUI (image, video and 3D models)

Some models only run as a ComfyUI workflow — the 3D ones, and video models newer than
stable-diffusion.cpp supports. hemera does not replace ComfyUI for these; it fronts it. Tell it
where ComfyUI is, in the Directories tab or in `hemera.json`:

```jsonc
"comfyui": {
  "dir": "/opt/ComfyUI",            // the folder with main.py: hemera starts it when needed
  "url": "http://127.0.0.1:8188"    // or just this, for a ComfyUI that is already running somewhere
}
```

That is the whole setup. Nothing is installed into ComfyUI and none of its files are touched.
hemera asks it, over the same HTTP API its own page uses, which model files and which nodes it
has, and every model it knows a workflow for appears in `/v1/models` under the name of its file:

```bash
curl -s http://host:12434/v1/models | jq -r '.data[] | select(.owned_by=="comfyui") | "\(.hemera.kind)\t\(.id)"'
3d      trellis_2_int8_convrot
3d      pixal3d_int8_convrot
3d      hunyuan_3d_v2.1
video   ltx25_uncensored_v1.1-fp8
video   Omoda_v1.5_MiniMaxH3_fl2va_int8_pruned
image   qwen-image-bf16
```

The list follows ComfyUI: drop another file into its `diffusion_models` folder (or any folder
its `extra_model_paths.yaml` names) and it is offered within half a minute; remove one and it is
gone. Image models answer on `/v1/images/generations`, video models on `/v1/videos`, exactly
like the sd.cpp ones, and 3D models on an endpoint of hemera's own:

```bash
curl http://host:12434/v1/3d/generations -F model=trellis_2_int8_convrot -F image=@teapot.png
                                                             # -> {"id":"3d_...","status":"queued"}
curl http://host:12434/v1/3d/generations/3d_...             # queued | in_progress (progress 0-100) | completed
curl http://host:12434/v1/3d/generations/3d_.../content -o teapot.glb
curl -X DELETE http://host:12434/v1/3d/generations/3d_...   # cancel, or discard
```

The full contract for client authors — discovery, every field, the job objects — is in
[docs/media-api.md](docs/media-api.md).

**Recipes.** What turns a file into a model is a *recipe*: a small JSON document naming the files
a family needs (by pattern, per ComfyUI folder), the request parameters and where each lands, and
the workflow itself in ComfyUI's API format. Built-in recipes cover TRELLIS.2, Pixal3D,
Hunyuan3D 2.1, LTX-2.5, MiniMax H3 (plain, and turbo-merged checkpoints) and Qwen-Image; they are
the files in [resources/comfyui/](resources/comfyui/). A file `<home>/comfyui/<name>.json` adds a
family, or replaces the built-in of the same `id`, with no rebuild — export a workflow from
ComfyUI with *Save (API format)*, wrap it the way the built-ins are wrapped, and the next look
picks it up. A model is offered only when ComfyUI has every file and node its recipe needs; the
Directories tab says what is missing otherwise.

Community checkpoints rarely follow a naming scheme, so a file no pattern recognises is listed
under *Not recognised*, where it can be assigned to a family by hand (stored as
`comfyui.assign: {"file.safetensors": "minimax-h3"}`) or hidden.

**Memory.** ComfyUI keeps its last models loaded. On a machine it shares with language models
that is memory they cannot use, so after `comfyui.keepAliveSeconds` (default 300, `-1` never) of
no jobs hemera asks it to unload. Results are written to `output/hemera/` and uploads to
`input/hemera/` inside ComfyUI; hemera never deletes there, so clear them out now and then.

Other keys: `python` (interpreter, when ComfyUI's virtualenv is not `venv/` or `.venv/` in or
beside its folder), `args` (extra arguments for `main.py`), `env`, `autoStart` (default `true`:
start with hemera rather than at the first request).

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
  "comfyui": {                      // optional; see ComfyUI above
    "dir": "/opt/ComfyUI",          // hemera starts it from here; or "url" for one already running
    "keepAliveSeconds": 300,        // idle time before it is asked to unload its models
    "assign": {}                    // file name -> recipe id, for files no pattern recognises
  },
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
    "sd.cpp": {
      "repo": "leejet/stable-diffusion.cpp",
      "serverBin": "sd-server",
      "argStyle": "sd",             // --listen-ip/--listen-port, --diffusion-model, --vae, --llm
      "healthPath": "/v1/models",   // sd-server has no /health, and only listens once loaded
      "trackedVariants": ["x86_64-vulkan", "x86_64-rocm"]
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
      "extraFiles": { "model-draft": "/models/mtp-draft.gguf" },   // extra flag=path pairs
      "args": ["-c", "32768", "-ngl", "99", "--jinja", "--spec-type", "draft-mtp", "--spec-draft-n-max", "3"],
      // --spec-type draft-mtp is suggested whenever an MTP head is detected, whether it is
      // baked into the model's own file ("mtp" in the filename, no much-smaller sibling) or
      // lives in a separate draft gguf beside it (bound above as extraFiles.model-draft)
      "env": { "HSA_OVERRIDE_GFX_VERSION": "11.5.1" },
      "keepAliveSeconds": 300
    },
    {
      "name": "qwen-image-2.1",
      "engine": "sd.cpp",
      "file": "/models/Qwen-Image-2.1/qwen-image-2.1-Q8_0.gguf",   // --diffusion-model
      "extraFiles": {                                              // the rest of the pipeline
        "vae": "/models/Qwen-Image-2.1/vae/qwen_image_2.1_vae_bf16.safetensors",
        "llm": "/models/Qwen-Image-2.1/text_encoders/qwen3vl_8b_bf16.safetensors"
      },
      "args": ["--cfg-scale", "6.0", "--sampling-method", "euler"],
      // wording that makes an RGBA-capable model paint an alpha channel; see Image generation
      "transparentPrompt": "This is an RGBA image with transparency. {prompt}. The image has alpha channel and the background is transparent.",
      "keepAliveSeconds": 900
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
  comfy/               the ComfyUI bridge: discovery, recipes, running workflows
  web/                 admin endpoints + embedded UI
resources/web/         index.html, app.css, app.js
resources/comfyui/     built-in recipes, one per model family
docs/media-api.md      the image / video / 3D contract for client authors
```

## License

[MIT](LICENSE)
