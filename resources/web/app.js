/* Hemera configure UI — vanilla JS, no dependencies. */
"use strict";

const $ = (sel, el) => (el || document).querySelector(sel);
const $$ = (sel, el) => Array.from((el || document).querySelectorAll(sel));

// ---------------------------------------------------------------- api helpers

async function api(method, path, body, headers) {
  const opts = { method, headers: { ...(headers || {}) } };
  if (body !== undefined) {
    opts.headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(body);
  }
  const resp = await fetch(path, opts);
  let data = null;
  try { data = await resp.json(); } catch (e) { /* empty body */ }
  if (!resp.ok) throw new Error((data && data.error) || (method + " " + path + " -> " + resp.status));
  return data;
}
/** Multipart POST — the browser sets its own boundary, so no Content-Type here. */
async function postForm(path, form, headers) {
  const resp = await fetch(path, { method: "POST", body: form, headers: headers || {} });
  let data = null;
  try { data = await resp.json(); } catch (e) { /* empty body */ }
  if (!resp.ok) throw new Error((data && data.error) || ("POST " + path + " -> " + resp.status));
  return data;
}
const GET = p => api("GET", p);
const POST = (p, b, h) => api("POST", p, b, h);
const PUT = (p, b) => api("PUT", p, b);
const DEL = p => api("DELETE", p);

// ---------------------------------------------------------------- state

let CONFIG = null;   // full hemera.json document
let STATUS = null;   // /status
let SCAN = null;     // /scan result
let USAGE = {};      // engine binary -> [model names]
let RELEASES = {};   // engine -> releases list
let COMFY = null;    // /comfyui: what the ComfyUI bridge currently offers
let LIBS = {};      // engine binary -> runtime library status
let showAllAssets = false;

function esc(s) {
  return String(s == null ? "" : s).replace(/[&<>"']/g,
    c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}
function humanSize(n) {
  if (!n) return "-";
  const u = ["B", "KB", "MB", "GB", "TB"];
  let i = 0, v = n;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return v.toFixed(v >= 100 || i === 0 ? 0 : 1) + " " + u[i];
}
function basename(p) { return String(p || "").split("/").pop(); }

/**
 * What to show for a configured model's file. An ollama blob is named after its 64-character
 * hash, which says nothing and is too long to fit: show the ollama model it belongs to when the
 * scan knows it, otherwise a shortened hash. The full path stays in the tooltip.
 */
function fileLabel(path) {
  const hit = (SCAN && SCAN.files || []).find(f => f.path === path);
  if (hit && hit.source === "ollama") return "ollama: " + hit.name;
  const name = basename(path);
  const m = /^sha256-([0-9a-f]{64})$/.exec(name);
  return m ? "sha256-" + m[1].slice(0, 12) + "…" + m[1].slice(-4) : name;
}

let toastTimer = null;
function toast(msg, isError) {
  const t = $("#toast");
  t.textContent = msg;
  t.className = isError ? "error" : "";
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add("hidden"), isError ? 8000 : 3500);
}
function fail(e) { toast(String(e.message || e), true); console.error(e); }

let modalDirty = false;
function openModal(html) {
  $("#modal-card").innerHTML = html;
  $("#modal").classList.remove("hidden");
  $("#modal").scrollTop = 0;
  modalDirty = false;
}
function closeModal() { $("#modal").classList.add("hidden"); modalDirty = false; }
function modalOpen() { return !$("#modal").classList.contains("hidden"); }

// Tapping outside is a convenient dismiss for a read-only dialog, but on a phone the backdrop
// is exactly where you tap to put the keyboard away — doing that silently threw away a
// half-filled form. Once anything has been typed, only Save/Cancel closes the dialog.
$("#modal-card").addEventListener("input", () => { modalDirty = true; });
$("#modal").addEventListener("click", e => {
  if (e.target.id === "modal" && !modalDirty) closeModal();
});

/**
 * Swap a section's markup while keeping the page where the user had scrolled to.
 * Background refreshes re-render whole sections, and on a phone that otherwise jumps
 * the reader back to the top of a long table every couple of seconds.
 */
function paint(el, html) {
  const y = window.scrollY;
  el.innerHTML = html;
  if (y) window.scrollTo(0, y);
}

// ---------------------------------------------------------------- navigation

const SECTIONS = ["dashboard", "models", "engines", "images", "dirs", "proxies", "settings"];

function currentSection() {
  const h = location.hash.replace("#", "");
  return SECTIONS.includes(h) ? h : "dashboard";
}

async function navigate() {
  const sec = currentSection();
  $$("#nav a").forEach(a => a.classList.toggle("active", a.hash === "#" + sec));
  SECTIONS.forEach(s => $("#sec-" + s).classList.toggle("visible", s === sec));
  try {
    CONFIG = await GET("/hemera/api/config");
    if (sec === "models" || sec === "images") COMFY = await GET("/hemera/api/comfyui").catch(() => COMFY);
    if (sec === "dashboard") await renderDashboard();
    if (sec === "models") await renderModels();
    if (sec === "engines") await renderEngines();
    if (sec === "dirs") renderDirs();
    if (sec === "images") renderImages();
    if (sec === "proxies") renderProxies();
    if (sec === "settings") renderSettings();
  } catch (e) { fail(e); }
}
// paint() deliberately preserves scroll for background refreshes, so switching tabs has to
// ask for the top of the page explicitly.
window.addEventListener("hashchange", () => { window.scrollTo(0, 0); navigate(); });

async function refreshStatusChip() {
  try {
    STATUS = await GET("/hemera/api/status");
    $("#statuschip").className = "ok";
    $("#statuschip").textContent =
      "v" + STATUS.version + " · " + STATUS.instances.length + " running · log " + STATUS.logLevel;
  } catch (e) {
    $("#statuschip").className = "err";
    $("#statuschip").textContent = "offline";
  }
}

// ---------------------------------------------------------------- dashboard

async function renderDashboard() {
  STATUS = await GET("/hemera/api/status");
  const el = $("#sec-dashboard");
  const loadRows = (STATUS.loading || []).map(l => `
    <tr>
      <td class="mono" data-label="Model">${esc(l.name)}</td>
      <td colspan="5" class="small ${l.error ? "" : "muted"}" data-label="Status" ${l.error ? 'style="color:var(--danger)"' : ""}>
        ${l.error ? "load failed: " + esc(l.error) : "loading… " + l.elapsedSeconds + "s"}</td>
      <td class="actions">${l.error ? `<button class="btn xs" data-dismiss="${esc(l.name)}">Dismiss</button>`
        : '<span class="badge warn">starting</span>'}</td>
    </tr>`).join("");
  const rows = STATUS.instances.map(i => `
    <tr>
      <td class="mono" data-label="Model">${esc(i.name)}</td>
      <td data-label="Engine binary">${esc(i.engine)}</td>
      <td class="mono" data-label="Port">${i.port}</td>
      <td class="mono" data-label="PID">${i.pid}</td>
      <td class="small muted" data-label="Started">${esc(i.startedAt.replace("T", " ").slice(0, 19))}</td>
      <td class="small muted" data-label="Keep-alive">${i.keepAliveSeconds < 0 ? "forever" : i.keepAliveSeconds + "s"}</td>
      <td class="actions"><button class="btn danger xs" data-stop="${esc(i.name)}">Stop</button></td>
    </tr>`).join("");
  paint(el, `
    <h2>Dashboard</h2>
    <div class="card">
      <div class="row spread">
        <h3>Running models</h3>
        <button class="btn xs" id="dash-refresh">Refresh</button>
      </div>
      <div class="tablewrap"><table class="stack">
        <thead><tr><th>Model</th><th>Engine binary</th><th>Port</th><th>PID</th><th>Started</th><th>Keep-alive</th><th></th></tr></thead>
        <tbody>
        ${loadRows}
        ${rows || (loadRows ? "" : '<tr><td colspan="7" class="muted">Nothing loaded. Load a model from the Models tab, or it starts on the first API request.</td></tr>')}
        </tbody>
      </table></div>
    </div>
    <div class="card small muted">
      Hemera ${esc(STATUS.version)} · home <span class="mono">${esc(STATUS.home)}</span>
      · port ${STATUS.port} · uptime ${Math.floor(STATUS.uptimeSeconds / 60)} min
      · API endpoints: <span class="mono">/api/*</span> (ollama), <span class="mono">/v1/chat/completions</span> (openai),
      <span class="mono">/v1/messages</span> (claude)
    </div>`);
  $("#dash-refresh").onclick = () => renderDashboard().catch(fail);
  $$("[data-dismiss]", el).forEach(b => b.onclick = async () => {
    try { await POST("/hemera/api/models/stop", { name: b.dataset.dismiss }); renderDashboard(); }
    catch (e) { fail(e); }
  });
  if ((STATUS.loading || []).some(l => !l.error)) pollLoading();
  $$("[data-stop]", el).forEach(b => b.onclick = async () => {
    try { await POST("/hemera/api/models/stop", { name: b.dataset.stop }); toast("Stopped " + b.dataset.stop); renderDashboard(); }
    catch (e) { fail(e); }
  });
}

// ---------------------------------------------------------------- models

function modelBinaryDesc(m) {
  const v = m.engineVersion || "latest", va = m.engineVariant || "";
  return (m.engine || "llama.cpp") + " " + v + (va ? " (" + va + ")" : "");
}

function fileRefCount(path) {
  let n = 0;
  for (const m of CONFIG.models || []) {
    const refs = [m.file, m.mmproj, ...(Object.values(m.extraFiles || {}))];
    if (refs.includes(path)) n++;
  }
  return n;
}

async function renderModels(reuseScan) {
  SCAN = (reuseScan && SCAN) ? SCAN : await GET("/hemera/api/scan");
  STATUS = await GET("/hemera/api/status").catch(() => STATUS);
  const el = $("#sec-models");
  const running = new Set((STATUS && STATUS.instances || []).map(i => i.name));
  const loading = new Map(((STATUS && STATUS.loading) || []).map(l => [l.name, l]));

  const cfgRows = (CONFIG.models || []).map(m => `
    <tr>
      <td class="mono" data-label="Name">${esc(m.name)}</td>
      <td class="small" data-label="Engine binary">${esc(modelBinaryDesc(m))}</td>
      <td class="small mono wrap" data-label="File" title="${esc(m.file || "")}">${esc(fileLabel(m.file))}${m.mmproj ? '<br><span class="badge">mmproj</span>' : ""}</td>
      <td class="small mono wrap" data-label="Args">${esc((m.args || []).join(" "))}</td>
      <td class="actions nowrap">
        ${running.has(m.name)
          ? `<span class="badge ok">loaded</span><button class="btn xs" data-unload="${esc(m.name)}">Unload</button>`
          : loading.has(m.name) && !loading.get(m.name).error
          ? `<span class="badge warn">loading ${loading.get(m.name).elapsedSeconds}s</span>`
          : `<button class="btn xs" data-load="${esc(m.name)}">Load</button>`}
        ${loading.has(m.name) && loading.get(m.name).error
          ? `<span class="badge danger" title="${esc(loading.get(m.name).error)}">failed</span>` : ""}
        <button class="btn xs" data-edit="${esc(m.name)}">Edit</button>
        <button class="btn danger xs" data-del="${esc(m.name)}">Remove</button>
      </td>
    </tr>`).join("");

  const modelFiles = (SCAN.files || []).filter(f => f.role === "model" && !f.error);
  const scanRows = modelFiles.map((f, idx) => {
    const meta = f.meta || {};
    const used = (f.usedBy || []).map(u => `<span class="badge ok">${esc(u)}</span>`).join("");
    const metaTxt = [meta["hemera.arch"], meta["hemera.params"], meta["hemera.quant"],
      meta["hemera.context"] ? "ctx " + meta["hemera.context"] : null].filter(Boolean).join(" · ");
    return `
    <tr>
      <td class="mono small wrap" data-label="File" title="${esc(f.path)}">${esc(f.name)}
        ${f.source === "ollama" ? '<span class="badge">ollama repo</span>' : ""}
        ${f.mmprojPath ? '<span class="badge ok" title="' + esc(f.mmprojPath) + '">+ vision (projector)</span>'
          : (f.meta && f.meta["hemera.vision"]) ? '<span class="badge warn" title="vision tower is inside the model file; no separate projector exists">+ vision (embedded)</span>' : ""}
        ${f.mtpPath ? '<span class="badge ok" title="' + esc(f.mtpPath) + '">+ mtp draft (' + esc(basename(f.mtpPath)) + ')</span>'
          : f.mtpEmbedded ? '<span class="badge ok" title="MTP head baked into this file — loaded with --spec-type draft-mtp, no companion needed">+ mtp (embedded)</span>' : ""}
        ${(f.meta && f.meta["hemera.audio"]) ? '<span class="badge warn">+ audio</span>' : ""}
        ${(f.meta && f.meta["hemera.embedding"]) ? '<span class="badge" title="needs --embedding to serve /api/embed correctly">embedding</span>' : ""}
        ${(f.meta && f.meta["hemera.kind"] === "image") ? '<span class="badge warn" title="diffusion model — runs on sd.cpp, serves /v1/images/*">image</span>' : ""}
        ${f.imageComponents ? '<span class="badge ok" title="' + esc(Object.keys(f.imageComponents).join(", ")) + ' found beside it">+ ' + esc(Object.keys(f.imageComponents).join(" + ")) + '</span>' : ""}
        ${f.parts > 1 ? '<span class="badge">' + f.parts + " parts</span>" : ""}</td>
      <td class="small" data-label="Size">${humanSize(f.size)}</td>
      <td class="small muted" data-label="Metadata">${esc(metaTxt)}</td>
      <td data-label="Used by">${used || '<span class="muted small">unused</span>'}</td>
      <td class="actions nowrap">
        <button class="btn xs primary" data-cfg="${idx}">Configure</button>
        ${f.source === "ollama"
          ? '<button class="btn danger xs" disabled title="managed by ollama — use ollama rm">Delete file</button>'
          : `<button class="btn danger xs" data-delfile="${idx}">Delete file</button>`}
      </td>
    </tr>`;
  }).join("");

  const companions = (SCAN.files || []).filter(f => f.role !== "model");
  const compRows = companions.map((f, ci) => `
    <tr>
      <td class="mono small wrap" data-label="File" title="${esc(f.path)}">${esc(f.name)}</td>
      <td data-label="Type"><span class="badge warn">${esc(f.role)}</span></td>
      <td class="small" data-label="Size">${humanSize(f.size)}</td>
      <td data-label="Paired with">${(f.boundTo || []).map(u => `<span class="badge">${esc(u)}</span>`).join("")
        || (f.usedBy || []).map(u => `<span class="badge ok">${esc(u)}</span>`).join("")
        || '<span class="muted small">unpaired</span>'}</td>
      <td class="actions">${f.source === "ollama"
        ? '<button class="btn danger xs" disabled title="managed by ollama">Delete file</button>'
        : `<button class="btn danger xs" data-delcomp="${ci}">Delete file</button>`}</td>
    </tr>`).join("");

  const comfyRows = ((COMFY && COMFY.models) || []).filter(m => m.ready).map(m => `
    <tr>
      <td class="mono" data-label="Name">${esc(m.id)}</td>
      <td data-label="Makes"><span class="badge warn">${esc(m.kind)}</span></td>
      <td data-label="Family">${esc(m.title)}</td>
      <td class="small mono wrap" data-label="File">${esc(m.folder + "/" + m.file)}</td>
    </tr>`).join("");

  paint(el, `
    <h2>Models</h2>
    <div class="card">
      <div class="row spread">
        <h3>Configured models</h3>
        <button class="btn xs primary" id="model-new">New model</button>
      </div>
      <div class="tablewrap"><table class="stack">
        <thead><tr><th>Name</th><th>Engine binary</th><th>File</th><th>Args</th><th></th></tr></thead>
        <tbody>${cfgRows || '<tr><td colspan="5" class="muted">No models configured yet — pick a scanned file below.</td></tr>'}</tbody>
      </table></div>
      <p class="small muted">One model file can be configured several times under different names
      to vary parameters. Models are reachable via all three APIs by name.
      <strong>Remove</strong> only deletes the configuration — the file on disk is kept.</p>
    </div>
    ${comfyRows ? `<div class="card">
      <h3>ComfyUI models</h3>
      <div class="tablewrap"><table class="stack">
        <thead><tr><th>Name</th><th>Makes</th><th>Family</th><th>File</th></tr></thead>
        <tbody>${comfyRows}</tbody>
      </table></div>
      <p class="small muted">Found in ComfyUI and offered under these names, in <span class="mono">/v1/models</span>
      like the ones above. Nothing to configure here: the list follows ComfyUI's model folders.
      ComfyUI itself is set up in the <a href="#dirs">Directories</a> tab.</p>
    </div>` : ""}
    <div class="card">
      <div class="row spread">
        <h3>Scanned model files</h3>
        <span class="small"><a href="https://ollama.com/search" target="_blank" rel="noopener">Browse ollama library &#8599;</a></span>
      </div>
      <div class="tablewrap"><table class="stack">
        <thead><tr><th>File</th><th>Size</th><th>Metadata</th><th>Used by</th><th>Actions</th></tr></thead>
        <tbody>${scanRows || '<tr><td colspan="5" class="muted">Nothing found. Add model directories in the Directories tab.</td></tr>'}</tbody>
      </table></div>
      ${companions.length ? `<h3 style="margin-top:14px">Companion files (mmproj / mtp)</h3>
      <div class="tablewrap"><table class="stack">
        <thead><tr><th>File</th><th>Type</th><th>Size</th><th>Paired with</th><th>Actions</th></tr></thead>
        <tbody>${compRows}</tbody>
      </table></div>` : ""}
    </div>`);

  $("#model-new").onclick = () => modelForm(null, null);
  $$("[data-cfg]", el).forEach(b => b.onclick = () => modelForm(null, modelFiles[+b.dataset.cfg]));
  $$("[data-edit]", el).forEach(b => b.onclick = () => {
    const m = (CONFIG.models || []).find(x => x.name === b.dataset.edit);
    if (m) modelForm(m, null);
  });
  $$("[data-load]", el).forEach(b => b.onclick = async () => {
    try {
      await POST("/hemera/api/models/load", { name: b.dataset.load });
      toast("Loading '" + b.dataset.load + "' — this can take a while for large models");
      pollLoading();
    } catch (e) { fail(e); }
  });
  $$("[data-unload]", el).forEach(b => b.onclick = async () => {
    try { await POST("/hemera/api/models/stop", { name: b.dataset.unload }); toast("Unloaded"); navigate(); }
    catch (e) { fail(e); }
  });
  if (loading.size) pollLoading();
  $$("[data-del]", el).forEach(b => b.onclick = () => removeModelDialog(b.dataset.del));
  $$("[data-delfile]", el).forEach(b => b.onclick = () => deleteFileDialog(modelFiles[+b.dataset.delfile]));
  $$("[data-delcomp]", el).forEach(b => b.onclick = () => deleteFileDialog(companions[+b.dataset.delcomp]));
}

function findDetectedMmproj(file) {
  const hit = (SCAN && SCAN.files || []).find(f => f.path === file);
  return hit ? (hit.mmprojPath || "") : "";
}
function findDetectedMtp(file) {
  const hit = (SCAN && SCAN.files || []).find(f => f.path === file);
  return hit ? (hit.mtpPath || "") : "";
}

/** True for an engine that runs stable-diffusion.cpp's sd-server (image generation, not chat). */
function isSdEngine(name) {
  const e = (CONFIG.engines || {})[name] || {};
  return e.argStyle ? e.argStyle === "sd" : /sd-server/.test(e.serverBin || e.binaryPath || "");
}
function firstSdEngine() {
  return Object.keys(CONFIG.engines || {}).find(isSdEngine) || "sd.cpp";
}

/** Qwen's own recommended wording for RGBA output; {prompt} is where the request's text goes. */
const RGBA_PROMPT_TEMPLATE = "This is an RGBA image with transparency. {prompt}. "
  + "The image has alpha channel and the background is transparent.";

function suggestArgs(scanFile) {
  const meta = (scanFile && scanFile.meta) || {};
  // An image model takes no context size — its defaults are sampler and guidance instead.
  if (meta["hemera.kind"] === "image")
    return meta["hemera.arch"] === "qwen-image" ? "--cfg-scale 6.0 --sampling-method euler" : "";
  // Follow the model's own trained context straight from its gguf metadata — no arbitrary cap.
  const ctx = meta["hemera.context"] || 8192;
  let extra = meta["hemera.embedding"] ? " --embedding" : "";
  // --spec-type draft-mtp is needed whether the head is baked into this same file
  // (mtpEmbedded) or lives in a separate draft gguf paired via --model-draft (mtpPath) —
  // llama-server needs the flag either way to actually use the head for speculative decoding.
  if (scanFile && (scanFile.mtpEmbedded || scanFile.mtpPath))
    extra += " --spec-type draft-mtp --spec-draft-n-max 3";
  return "-c " + ctx + extra;
}
function suggestName(fname) {
  return String(fname || "").replace(/\.gguf$/i, "").replace(/-\d{5}-of-\d{5}$/, "")
    .toLowerCase().replace(/[^a-z0-9._-]+/g, "-");
}

function modelForm(existing, scanFile) {
  const m = existing || {};
  const engines = Object.keys(CONFIG.engines || { "llama.cpp": {} });
  const scanMeta = (scanFile && scanFile.meta) || {};
  // A diffusion model can only run on an sd.cpp engine, so don't make the user notice that.
  const engine = m.engine || (scanMeta["hemera.kind"] === "image" ? firstSdEngine() : "llama.cpp");
  const installed = ((CONFIG.engines || {})[engine] || {}).installed || [];
  const versions = [...new Set(installed.map(i => i.version))].sort().reverse();
  const variants = [...new Set(installed.map(i => i.variant))];
  const name = m.name || (scanFile ? (scanFile.source === "ollama" ? scanFile.name : suggestName(scanFile.name)) : "");
  const file = m.file || (scanFile ? scanFile.path : "");
  // A projector belongs to the model it shipped with, so it is detected from the model's own
  // directory (or the ollama manifest) and attached automatically — never chosen from a list.
  const detected = scanFile ? (scanFile.mmprojPath || "") : (existing ? findDetectedMmproj(m.file) : "");
  const detectedName = scanFile ? (scanFile.mmprojName || basename(scanFile.mmprojPath))
    : basename(detected);
  const mmproj = m.mmproj || detected;
  const args = (m.args || []).join(" ") || (scanFile ? suggestArgs(scanFile) : "");
  const envLines = Object.entries(m.env || {}).map(([k, v]) => k + "=" + v).join("\n");
  // A separate MTP draft file is bound by directory the same way mmproj is (see
  // ModelScanner.bindCompanions) — prefill it as --model-draft unless the user already set/cleared
  // "model-draft" themselves. This is a genuinely separate small draft checkpoint, distinct from
  // the "mtpEmbedded" case above where the head lives inside the main file itself.
  const detectedMtp = scanFile ? (scanFile.mtpPath || "") : (existing ? findDetectedMtp(m.file) : "");
  const extraFiles = { ...(m.extraFiles || {}) };
  if (detectedMtp && !extraFiles["model-draft"])
    extraFiles["model-draft"] = detectedMtp;
  // A diffusion transformer is only part of a pipeline: prefill the VAE and text encoder found
  // beside it (vae/, text_encoders/) so the flags don't have to be typed out by hand.
  const components = (scanFile && scanFile.imageComponents) || {};
  for (const [flag, paths] of Object.entries(components))
    if (!extraFiles[flag] && paths.length)
      extraFiles[flag] = paths[0];
  const extraLines = Object.entries(extraFiles).map(([k, v]) => k + "=" + v).join("\n");
  const alternatives = Object.entries(components).filter(([, p]) => p.length > 1)
    .map(([flag, p]) => flag + ": " + p.slice(1).map(basename).join(", "));
  const transparentPrompt = m.transparentPrompt
    || (scanMeta["hemera.arch"] === "qwen-image" ? RGBA_PROMPT_TEMPLATE : "");

  openModal(`
    <h3>${existing ? "Edit model" : "New model"}</h3>
    <div class="formgrid">
      <div class="full"><label>Name (as exposed to clients)</label>
        <input type="text" id="mf-name" value="${esc(name)}" ${existing ? "readonly" : ""}></div>
      <div><label>Engine</label>
        <select id="mf-engine">${engines.map(e => `<option ${e === engine ? "selected" : ""}>${esc(e)}</option>`).join("")}</select></div>
      <div class="mf-managed"><label>Engine version</label>
        <select id="mf-version">
          <option value="latest">latest (follow updates)</option>
          ${versions.map(v => `<option ${v === m.engineVersion ? "selected" : ""}>${esc(v)}</option>`).join("")}
        </select></div>
      <div class="full mf-managed"><label>Engine variant filter (substring, e.g. "rocm" or "vulkan"; empty = any)</label>
        <input type="text" id="mf-variant" value="${esc(m.engineVariant || "")}" list="mf-variants">
        <datalist id="mf-variants">${variants.map(v => `<option>${esc(v)}</option>`).join("")}</datalist></div>
      <div class="full"><label>Model file (.gguf — first part for split models)</label>
        <input type="text" id="mf-file" class="mono" value="${esc(file)}"></div>
      <div class="full mf-text"><label>Vision projector (mmproj)</label>
        ${mmproj ? `<div class="row" style="gap:8px">
            <input type="checkbox" id="mf-mmproj-on" checked style="width:auto">
            <span class="small">attach <span class="mono">${esc(detectedName || basename(mmproj))}</span>
            ${detected ? '<span class="badge ok">auto-detected</span>' : '<span class="badge">configured</span>'}</span>
          </div>
          <input type="hidden" id="mf-mmproj" value="${esc(mmproj)}">
          <p class="small muted mono" style="word-break:break-all">${esc(mmproj)}</p>`
        : (scanFile && scanFile.meta && scanFile.meta["hemera.vision"])
        ? `<input type="hidden" id="mf-mmproj" value="">
           <p class="small muted">This model's vision tower is <strong>embedded in the model file</strong> —
           there is no separate projector to attach. Whether images work depends on the engine build
           supporting this architecture; upstream llama.cpp may need a newer release than the one you pinned.</p>`
        : `<input type="hidden" id="mf-mmproj" value="">
           <p class="small muted">No projector found next to this model — it will run text-only.
           Projectors are paired from the model's own directory, so they are never mixed between models.</p>`}
      </div>
      <div class="full"><label>Extra files (one per line: flag=path, e.g. vae=/path/vae.safetensors)</label>
        <textarea id="mf-extra" class="mono">${esc(extraLines)}</textarea>
        ${alternatives.length ? `<p class="small muted">Also found beside this model — swap a path above to use one:
          ${alternatives.map(a => `<span class="mono">${esc(a)}</span>`).join("; ")}</p>` : ""}</div>
      <div class="full mf-image"><label>Transparent-background prompt (for background=transparent)</label>
        <textarea id="mf-transparent" class="mono">${esc(transparentPrompt)}</textarea>
        <p class="small muted">Models like Qwen-Image 2.1 decide to paint an alpha channel because the
        <strong>prompt</strong> asked for it — there is no switch. Hemera wraps the request's text with this
        template when a client sends <span class="mono">"background": "transparent"</span>;
        <span class="mono">{prompt}</span> is where that text goes. Leave empty if the model cannot do RGBA.</p></div>
      <div class="full"><label>Arguments for the engine (space separated)</label>
        <textarea id="mf-args" class="mono">${esc(args)}</textarea></div>
      <div class="full"><label>Environment overrides (one per line: KEY=value, e.g. HSA_OVERRIDE_GFX_VERSION=11.5.1)</label>
        <textarea id="mf-env" class="mono">${esc(envLines)}</textarea></div>
      <div><label>Keep-alive seconds (-1 = never unload)</label>
        <input type="number" id="mf-keepalive" value="${m.keepAliveSeconds != null ? m.keepAliveSeconds : (CONFIG.keepAliveSeconds != null ? CONFIG.keepAliveSeconds : 300)}"></div>
    </div>
    <div class="row actions">
      <button class="btn primary" id="mf-save">Save</button>
      <button class="btn" id="mf-cancel">Cancel</button>
    </div>`);

  const versionSelect = $("#mf-version");
  const variantList = $("#mf-variants");
  const syncEngineFields = () => {
    const sel = $("#mf-engine").value;
    const manual = !!(CONFIG.engines[sel] || {}).binaryPath;
    $$(".mf-managed").forEach(d => d.style.display = manual ? "none" : "");
    // A diffusion pipeline has no mmproj, and only it can do prompt-driven transparency.
    const image = isSdEngine(sel);
    $$(".mf-text").forEach(d => d.style.display = image ? "none" : "");
    $$(".mf-image").forEach(d => d.style.display = image ? "" : "none");
    // Version/variant options were only ever built for the model's original engine, so
    // switching engines has to rebuild them for whichever one is now selected.
    const curVersion = versionSelect.value;
    const selInstalled = ((CONFIG.engines[sel] || {}).installed || []);
    const selVersions = [...new Set(selInstalled.map(i => i.version))].sort().reverse();
    const selVariants = [...new Set(selInstalled.map(i => i.variant))];
    versionSelect.innerHTML = `<option value="latest">latest (follow updates)</option>` +
      selVersions.map(v => `<option ${v === curVersion ? "selected" : ""}>${esc(v)}</option>`).join("");
    variantList.innerHTML = selVariants.map(v => `<option>${esc(v)}</option>`).join("");
  };
  $("#mf-engine").onchange = syncEngineFields;
  syncEngineFields();

  $("#mf-cancel").onclick = closeModal;
  $("#mf-save").onclick = async () => {
    try {
      const parseKv = txt => Object.fromEntries(txt.split("\n").map(l => l.trim()).filter(l => l && l.includes("="))
        .map(l => [l.slice(0, l.indexOf("=")).trim(), l.slice(l.indexOf("=") + 1).trim()]));
      const body = {
        name: $("#mf-name").value.trim(),
        engine: $("#mf-engine").value,
        engineVersion: $("#mf-version").value,
        engineVariant: $("#mf-variant").value.trim(),
        file: $("#mf-file").value.trim(),
        mmproj: ($("#mf-mmproj-on") && !$("#mf-mmproj-on").checked) ? "" : $("#mf-mmproj").value.trim(),
        extraFiles: parseKv($("#mf-extra").value),
        args: $("#mf-args").value.trim().split(/\s+/).filter(Boolean),
        env: parseKv($("#mf-env").value),
        keepAliveSeconds: parseInt($("#mf-keepalive").value, 10)
      };
      const transparent = $("#mf-transparent").value.trim();
      if (transparent && isSdEngine(body.engine)) body.transparentPrompt = transparent;
      if (!body.mmproj) delete body.mmproj;
      if (!Object.keys(body.extraFiles).length) delete body.extraFiles;
      if (!Object.keys(body.env).length) delete body.env;
      await POST("/hemera/api/models", body);
      closeModal();
      toast("Model '" + body.name + "' saved");
      navigate();
    } catch (e) { fail(e); }
  };
}

function removeModelDialog(name) {
  const m = (CONFIG.models || []).find(x => x.name === name);
  openModal(`
    <h3>Remove model '${esc(name)}'</h3>
    <p class="small">Removes the configuration entry only. <strong>The model file stays on disk</strong>
    and can be configured again at any time.</p>
    ${m && m.file ? `<p class="small mono muted">${esc(m.file)}</p>` : ""}
    <div class="row" style="margin-top:14px">
      <button class="btn danger" id="dm-ok">Remove configuration</button>
      <button class="btn" id="dm-cancel">Cancel</button>
    </div>`);
  $("#dm-cancel").onclick = closeModal;
  $("#dm-ok").onclick = async () => {
    try {
      await DEL("/hemera/api/models?name=" + encodeURIComponent(name));
      closeModal();
      toast("Removed configuration for '" + name + "' (file kept)");
      navigate();
    } catch (e) { fail(e); }
  };
}

function deleteFileDialog(file) {
  const users = file.usedBy || [];
  openModal(`
    <h3>Delete file from disk</h3>
    <p class="small mono">${esc(file.path)}</p>
    <p class="small">This permanently deletes ${file.parts > 1 ? "all <strong>" + file.parts + " parts</strong> of this model" : "the file"}
    (${humanSize(file.size)}) from disk. It cannot be undone — you would have to download the model again.</p>
    ${users.length ? `<div class="card" style="border-color:var(--danger)">
        <strong class="small">These ${users.length} configured model${users.length > 1 ? "s" : ""} will also be deleted:</strong>
        <div style="margin-top:6px">${users.map(u => `<span class="badge danger">${esc(u)}</span>`).join("")}</div>
      </div>` : '<p class="small muted">No configured model uses this file.</p>'}
    <label>Type the file name to confirm: <span class="mono">${esc(file.name)}</span></label>
    <input type="text" id="df-confirm" class="mono" autocomplete="off">
    <div class="row" style="margin-top:14px">
      <button class="btn danger" id="df-ok" disabled>Delete permanently</button>
      <button class="btn" id="df-cancel">Cancel</button>
    </div>`);
  $("#df-cancel").onclick = closeModal;
  $("#df-confirm").oninput = ev => { $("#df-ok").disabled = ev.target.value.trim() !== file.name; };
  $("#df-ok").onclick = async () => {
    try {
      const res = await DEL("/hemera/api/files?path=" + encodeURIComponent(file.path));
      closeModal();
      toast("Deleted " + res.deletedFiles.length + " file(s)"
        + (res.removedModels.length ? " and " + res.removedModels.length + " configured model(s)" : ""));
      navigate();
    } catch (e) { fail(e); }
  };
}

let loadTimer = null;
function pollLoading() {
  clearTimeout(loadTimer);
  loadTimer = setTimeout(async () => {
    try {
      const st = await GET("/hemera/api/status");
      const active = (st.loading || []).filter(l => !l.error);
      // Re-rendering replaces the whole section. Doing that under an open dialog resets the
      // form the user is filling in, so hold off until they are done — polling continues, the
      // list just catches up when the dialog closes.
      if (!modalOpen()) {
        const sec = currentSection();
        // The scan re-reads gguf headers off disk; far too heavy to repeat every 2s, and
        // nothing it returns changes while a model is loading.
        if (sec === "models") await renderModels(true);
        else if (sec === "dashboard") await renderDashboard();
      }
      if (active.length) pollLoading();
    } catch (e) { /* transient */ }
  }, 2000);
}

// ---------------------------------------------------------------- engines

async function renderEngines() {
  USAGE = await GET("/hemera/api/engines/usage");
  LIBS = await GET("/hemera/api/engines/libraries").catch(() => ({}));
  const el = $("#sec-engines");
  const engines = CONFIG.engines || {};

  const blocks = Object.entries(engines).map(([name, e]) => {
    const installed = (e.installed || []).slice().sort((a, b) => (b.version || "").localeCompare(a.version || "", undefined, { numeric: true }));
    const rows = installed.map(i => {
      const key = name + "/" + i.version + "/" + i.variant;
      const used = USAGE[key] || [];
      const lib = LIBS[key];
      let libCell = '<span class="muted small">-</span>';
      if (lib) {
        if (lib.complete) {
          const dirs = Object.values(lib.resolved || {});
          const where = [...new Set(dirs)];
          libCell = where.length
            ? `<span class="badge ok" title="${esc(where.join("\n"))}">found: ${esc(where.map(basename).join(", "))}</span>`
            : '<span class="badge ok">self-contained</span>';
        } else {
          libCell = `<span class="badge danger" title="${esc(lib.missing.join("\n"))}">missing ${lib.missing.length}</span>`;
        }
      }
      return `
      <tr>
        <td class="mono">${esc(i.version)}</td>
        <td class="mono small">${esc(i.variant)}</td>
        <td class="small muted">${esc((i.installedAt || "").slice(0, 10))}</td>
        <td>${libCell}</td>
        <td>${used.map(u => `<span class="badge ok">${esc(u)}</span>`).join("") || '<span class="badge">unused</span>'}</td>
        <td><button class="btn danger xs" data-rm-engine="${esc(name)}" data-version="${esc(i.version)}"
             data-variant="${esc(i.variant)}" ${used.length ? "disabled title='in use'" : ""}>Remove</button></td>
      </tr>`;
    }).join("");
    return `
    <div class="card">
      <div class="row spread">
        <h3>${esc(name)} <span class="muted small">${esc(e.repo || (e.binaryPath ? "manual binary" : ""))}</span></h3>
        <span>
          ${e.repo ? `<button class="btn xs primary" data-releases="${esc(name)}">Check releases</button>` : ""}
          ${e.binaryPath !== undefined ? `<button class="btn xs" data-editengine="${esc(name)}">Edit</button>
            <button class="btn danger xs" data-delengine="${esc(name)}">Remove</button>` : ""}
        </span>
      </div>
      ${e.binaryPath !== undefined ? `<div class="small muted mono" style="word-break:break-all">${esc(e.binaryPath)}
        ${e.healthPath ? " · health " + esc(e.healthPath) : ""}
        ${(() => { const l = LIBS[name + "//"]; if (!l) return "";
          return l.complete ? '<span class="badge ok">libraries ok</span>'
            : '<span class="badge danger" title="' + esc(l.missing.join("\n")) + '">missing ' + l.missing.length + ' librar' + (l.missing.length === 1 ? "y" : "ies") + '</span>'; })()}</div>
        <div class="small mono muted" style="margin-top:4px">${esc((e.defaultArgs || []).join(" "))}</div>` : ""}
      ${e.repo ? `<div class="tablewrap"><table>
        <tr><th>Version</th><th>Variant</th><th>Installed</th><th>Runtime libs</th><th>Used by models</th><th></th></tr>
        ${rows || '<tr><td colspan="6" class="muted">No binaries installed yet.</td></tr>'}
      </table></div>` : ""}
      ${e.repo ? `<p class="small muted">Older versions are kept as rollback history — pin a model to a previous
      version in its edit form if a new binary misbehaves.
      <strong>Runtime libs</strong> shows whether the GPU runtime this build needs (ROCm, CUDA, oneAPI…)
      was found on this machine; if it says <em>missing</em>, the engine would silently fall back to CPU —
      hover for the exact library names, and add its directory under Settings.</p>` : ""}
      <div id="releases-${esc(name)}"></div>
    </div>`;
  }).join("");

  paint(el, `
    <div class="row spread"><h2>Engines</h2>
      <button class="btn xs primary" id="engine-new">Add engine</button></div>
    <div id="engine-progress"></div>${blocks}
    <div class="card small muted">
      <strong>Managed engines</strong> (with a GitHub repo) are downloaded and version-managed by hemera.
      <strong>Custom engines</strong> point at a binary you installed yourself — use
      <span class="mono">{port}</span>, <span class="mono">{file}</span> and <span class="mono">{mmproj}</span>
      in the arguments and hemera substitutes them when launching a model.
    </div>`);
  $("#engine-new").onclick = () => engineForm(null);
  $$("[data-editengine]", el).forEach(b => b.onclick = () => engineForm(b.dataset.editengine));
  $$("[data-delengine]", el).forEach(b => b.onclick = async () => {
    const n = b.dataset.delengine;
    const inUse = (CONFIG.models || []).filter(m => (m.engine || "llama.cpp") === n).map(m => m.name);
    if (inUse.length) { toast("Engine is used by: " + inUse.join(", "), true); return; }
    if (!confirm("Remove engine '" + n + "' from the configuration? The binary itself is not deleted.")) return;
    delete CONFIG.engines[n];
    try { await PUT("/hemera/api/config", CONFIG); toast("Engine removed"); navigate(); } catch (e) { fail(e); }
  });
  $$("[data-releases]", el).forEach(b => b.onclick = () => loadReleases(b.dataset.releases).catch(fail));
  $$("[data-rm-engine]", el).forEach(b => b.onclick = async () => {
    if (!confirm("Remove " + b.dataset.rmEngine + " " + b.dataset.version + " " + b.dataset.variant + " from disk?")) return;
    try {
      await DEL("/hemera/api/engines?engine=" + encodeURIComponent(b.dataset.rmEngine)
        + "&version=" + encodeURIComponent(b.dataset.version) + "&variant=" + encodeURIComponent(b.dataset.variant));
      toast("Removed"); navigate();
    } catch (e) { fail(e); }
  });
  renderProgress();
}

const ENGINE_PRESETS = {
  ds4: {
    binaryPath: "binary/ds4/ds4-server",
    healthPath: "/v1/models",
    defaultArgs: "--host 127.0.0.1 --port {port} -m {file}",
    note: "DwarfStar serves OpenAI and Anthropic APIs itself. Add --rocm, --metal or --cpu to pin a backend."
  },
  "ollama-runner": {
    binaryPath: "/usr/local/lib/ollama/llama-server",
    healthPath: "/health",
    defaultArgs: "--no-webui --offline -np 1",
    libraryPaths: "/usr/local/lib/ollama, /usr/local/lib/ollama/rocm_v7_2",
    note: "The llama.cpp fork bundled with ollama. Useful for models ollama can load but upstream " +
          "llama.cpp cannot yet. -m and --mmproj are added automatically. Point libraryPaths at the " +
          "backend subdirectory for your GPU (rocm_v*, vulkan, cuda_v*)."
  },
  custom: { binaryPath: "", healthPath: "/health", defaultArgs: "--host 127.0.0.1 --port {port} -m {file}", note: "" }
};

function engineForm(existingName) {
  const e = existingName ? (CONFIG.engines[existingName] || {}) : {};
  const preset = existingName ? null : "ds4";
  const p = ENGINE_PRESETS[preset] || {};
  openModal(`
    <h3>${existingName ? "Edit engine '" + esc(existingName) + "'" : "Add custom engine"}</h3>
    ${existingName ? "" : `<label>Start from</label>
      <select id="ef-preset">
        <option value="ds4">ds4 / DwarfStar</option>
        <option value="ollama-runner">ollama's bundled llama-server</option>
        <option value="custom">custom binary</option>
      </select>`}
    <div class="formgrid">
      <div class="full"><label>Engine name (referenced by models)</label>
        <input type="text" id="ef-name" value="${esc(existingName || "ds4")}" ${existingName ? "readonly" : ""}></div>
      <div class="full"><label>Server binary path (relative to hemera home, or absolute)</label>
        <input type="text" id="ef-bin" class="mono" value="${esc(e.binaryPath || p.binaryPath || "")}"></div>
      <div><label>Health check path</label>
        <input type="text" id="ef-health" class="mono" value="${esc(e.healthPath || p.healthPath || "/health")}"></div>
      <div><label>Server binary name (for library detection)</label>
        <input type="text" id="ef-serverbin" class="mono" value="${esc(e.serverBin || "")}" placeholder="optional"></div>
      <div class="full"><label>Launch arguments — {port}, {file}, {mmproj} are substituted</label>
        <textarea id="ef-args" class="mono">${esc((e.defaultArgs || []).join(" ") || p.defaultArgs || "")}</textarea></div>
      <div class="full"><label>Extra library directories (comma separated)</label>
        <input type="text" id="ef-libs" class="mono" value="${esc((e.libraryPaths || []).join(", ") || p.libraryPaths || "")}"></div>
    </div>
    <p class="small muted" id="ef-note">${esc(p.note || "")}</p>
    <div class="row" style="margin-top:14px">
      <button class="btn primary" id="ef-save">Save</button>
      <button class="btn" id="ef-cancel">Cancel</button>
    </div>`);
  $("#ef-cancel").onclick = closeModal;
  if ($("#ef-preset")) $("#ef-preset").onchange = ev => {
    const q = ENGINE_PRESETS[ev.target.value] || {};
    $("#ef-name").value = ev.target.value === "custom" ? "" : ev.target.value;
    $("#ef-bin").value = q.binaryPath || "";
    $("#ef-health").value = q.healthPath || "/health";
    $("#ef-args").value = q.defaultArgs || "";
    $("#ef-libs").value = q.libraryPaths || "";
    $("#ef-note").textContent = q.note || "";
  };
  $("#ef-save").onclick = async () => {
    const name = $("#ef-name").value.trim();
    const bin = $("#ef-bin").value.trim();
    if (!name || !bin) { toast("name and binary path are required", true); return; }
    const entry = Object.assign({}, existingName ? e : {}, {
      binaryPath: bin,
      healthPath: $("#ef-health").value.trim() || "/health",
      defaultArgs: $("#ef-args").value.trim().split(/\s+/).filter(Boolean)
    });
    const sb = $("#ef-serverbin").value.trim();
    if (sb) entry.serverBin = sb;
    if (entry.libraryAutoDetect === undefined) entry.libraryAutoDetect = true;
    entry.libraryPaths = $("#ef-libs").value.split(",").map(x => x.trim()).filter(Boolean);
    CONFIG.engines[name] = entry;
    try { await PUT("/hemera/api/config", CONFIG); closeModal(); toast("Engine '" + name + "' saved"); navigate(); }
    catch (err) { fail(err); }
  };
}

async function loadReleases(engine) {
  const holder = $("#releases-" + CSS.escape(engine));
  holder.innerHTML = '<p class="muted small">Loading releases from GitHub…</p>';
  try {
    const data = await GET("/hemera/api/engines/releases?engine=" + encodeURIComponent(engine) + "&count=8");
    RELEASES[engine] = data.releases || [];
    renderReleaseList(engine);
  } catch (e) {
    holder.innerHTML = "";
    fail(e);
  }
}

function renderReleaseList(engine) {
  const holder = $("#releases-" + CSS.escape(engine));
  const tracked = ((CONFIG.engines || {})[engine] || {}).trackedVariants || [];
  const installedKeys = new Set((((CONFIG.engines || {})[engine] || {}).installed || [])
    .map(i => i.version + "/" + i.variant));
  const rows = (RELEASES[engine] || []).map(rel => {
    const assets = (rel.assets || []).filter(a =>
      showAllAssets || tracked.some(t => a.name.includes(t)));
    if (!assets.length) return "";
    const assetRows = assets.map(a => {
      const installed = [...installedKeys].some(k => k.startsWith(rel.tag + "/") && a.name.includes(k.split("/")[1]));
      return `<div class="row spread" style="padding:3px 0">
        <span class="mono small">${esc(a.name)} <span class="muted">(${humanSize(a.size)})</span></span>
        <button class="btn xs ${installed ? "" : "primary"}" data-install="${esc(engine)}"
          data-tag="${esc(rel.tag)}" data-asset="${esc(a.name)}" data-url="${esc(a.url)}">
          ${installed ? "Reinstall" : "Install"}</button>
      </div>`;
    }).join("");
    return `<div style="margin-top:10px"><strong class="mono">${esc(rel.tag)}</strong>
      <span class="muted small">${esc((rel.published || "").slice(0, 10))}</span>${assetRows}</div>`;
  }).join("");
  holder.innerHTML = `
    <div class="row spread" style="margin-top:10px">
      <h3>Available releases</h3>
      <label class="row small" style="margin:0"><input type="checkbox" id="rel-all-${esc(engine)}"
        ${showAllAssets ? "checked" : ""} style="width:auto"> show all assets (not just tracked variants)</label>
    </div>
    ${rows || '<p class="muted small">No assets match the tracked variants (' + esc(tracked.join(", ")) + '). Enable "show all assets".</p>'}`;
  $("#rel-all-" + CSS.escape(engine)).onchange = ev => { showAllAssets = ev.target.checked; renderReleaseList(engine); };
  $$("[data-install]", holder).forEach(b => b.onclick = async () => {
    try {
      await POST("/hemera/api/engines/install",
        { engine: b.dataset.install, tag: b.dataset.tag, asset: b.dataset.asset, url: b.dataset.url });
      toast("Download started: " + b.dataset.asset);
      pollProgress();
    } catch (e) { fail(e); }
  });
}

let progressTimer = null;
async function renderProgress() {
  const holder = $("#engine-progress");
  if (!holder) return;
  try {
    const jobs = await GET("/hemera/api/engines/progress");
    const active = Object.values(jobs).filter(j => j.status === "downloading" || j.status === "extracting" || j.status === "starting");
    const recent = Object.values(jobs).filter(j => j.status === "error");
    const items = [...active, ...recent].map(j => {
      const pct = j.total > 0 ? Math.round(100 * j.done / j.total) : 0;
      return `<div class="card">
        <div class="row spread"><span class="mono small">${esc(j.engine)} ${esc(j.version)} — ${esc(j.asset)}</span>
        <span class="badge ${j.status === "error" ? "danger" : "warn"}">${esc(j.status)}</span></div>
        ${j.status === "downloading" ? `<div class="bar"><div class="fill" style="width:${pct}%"></div></div>
          <div class="small muted">${humanSize(j.done)} / ${humanSize(j.total)} (${pct}%)</div>` : ""}
        ${j.error ? `<div class="small" style="color:var(--danger)">${esc(j.error)}</div>` : ""}
      </div>`;
    }).join("");
    holder.innerHTML = items;
    if (active.length) pollProgress();
    else if (progressTimer) { clearTimeout(progressTimer); progressTimer = null; }
  } catch (e) { /* ignore */ }
}
function pollProgress() {
  clearTimeout(progressTimer);
  progressTimer = setTimeout(async () => {
    await renderProgress();
    // refresh installed table when a job finished
    const jobs = await GET("/hemera/api/engines/progress").catch(() => ({}));
    if (Object.values(jobs).every(j => j.status !== "downloading" && j.status !== "extracting" && j.status !== "starting")
      && currentSection() === "engines" && !modalOpen()) navigate();
  }, 1500);
}

// ---------------------------------------------------------------- directories

function renderDirs() {
  const el = $("#sec-dirs");
  const dirs = CONFIG.modelDirs || [];
  paint(el, `
    <h2>Model directories</h2>
    <div class="card">
      <div class="tablewrap"><table>
        <tr><th>Directory</th><th></th></tr>
        ${dirs.map((d, i) => `<tr><td class="mono">${esc(d)}</td>
          <td><button class="btn danger xs" data-rmdir="${i}">Remove</button></td></tr>`).join("")
          || '<tr><td colspan="2" class="muted">No directories configured.</td></tr>'}
      </table></div>
      <div class="row" style="margin-top:12px">
        <input type="text" id="dir-new" class="mono grow" placeholder="/path/to/gguf-models or /usr/share/ollama/.ollama/models">
        <button class="btn primary" id="dir-add">Add</button>
      </div>
      <p class="small muted">Directories are scanned recursively for <span class="mono">.gguf</span> files
      (multi-part models are grouped; mmproj/mtp companions detected).
      A directory containing <span class="mono">manifests/</span> + <span class="mono">blobs/</span>
      is read as an ollama repository — its models appear in the scan, and files inside it are never deleted by hemera.</p>
    </div>
    <div id="comfy"></div>`);
  renderComfy();
  $("#dir-add").onclick = async () => {
    const v = $("#dir-new").value.trim();
    if (!v) return;
    CONFIG.modelDirs = [...dirs, v];
    try { await PUT("/hemera/api/config", CONFIG); toast("Directory added"); navigate(); } catch (e) { fail(e); }
  };
  $$("[data-rmdir]", el).forEach(b => b.onclick = async () => {
    CONFIG.modelDirs = dirs.filter((_, i) => i !== +b.dataset.rmdir);
    try { await PUT("/hemera/api/config", CONFIG); toast("Directory removed"); navigate(); } catch (e) { fail(e); }
  });
}

// ---------------------------------------------------------------- comfyui

/**
 * ComfyUI is pointed at rather than configured: hemera asks it what it has, so this card is
 * two fields and a report of what came back.
 */
async function renderComfy(refresh) {
  const el = $("#comfy");
  if (!el) return;
  const c = CONFIG.comfyui || {};
  let st = null;
  try { st = await GET("/hemera/api/comfyui" + (refresh ? "?refresh=1" : "")); } catch (e) { fail(e); }
  if (!$("#comfy")) return;
  const models = (st && st.models) || [];
  const loose = (st && st.unassigned) || [];
  const state = !st || !st.configured ? '<span class="badge">not configured</span>'
    : st.reachable ? `<span class="badge ok">connected</span> <span class="small muted">ComfyUI ${esc(st.version || "")}${st.startedByHemera ? ", started by hemera" : ""}</span>`
    : `<span class="badge danger">unreachable</span> <span class="small muted">${esc(st.error || "")}</span>`;
  const rows = models.map(m => `<tr>
      <td data-label="Model" class="mono">${esc(m.id)}</td>
      <td data-label="Makes"><span class="badge warn">${esc(m.kind)}</span></td>
      <td data-label="Family">${esc(m.title)}</td>
      <td data-label="Status">${m.ready ? '<span class="badge ok">offered</span>'
        : `<span class="badge danger">incomplete</span><div class="small muted">${m.problems.map(esc).join("<br>")}</div>`}</td>
    </tr>`).join("");
  const looseRows = loose.map((f, i) => `<tr>
      <td data-label="File" class="mono">${esc(f.file)}</td>
      <td data-label="Use as"><div class="row"><select data-comfy-recipe="${i}">
        ${f.recipes.map(r => `<option>${esc(r)}</option>`).join("")}</select>
        <button class="btn xs" data-comfy-assign="${i}">Assign</button>
        <button class="btn xs" data-comfy-hide="${i}">Hide</button></div></td>
    </tr>`).join("");
  paint(el, `
    <h2>ComfyUI</h2>
    <div class="card">
      <p class="small muted">Image, video and 3D models that live in a ComfyUI install. hemera asks ComfyUI which
      model files and nodes it has and offers every model it has a workflow for — nothing is installed into ComfyUI,
      and a file added to its folders appears here by itself.</p>
      <div class="formgrid">
        <div><label>ComfyUI folder (the one containing main.py) — hemera starts it when needed</label>
          <input type="text" id="comfy-dir" class="mono" value="${esc(c.dir || "")}" placeholder="/path/to/ComfyUI"></div>
        <div><label>URL (only if it runs elsewhere, or on another port)</label>
          <input type="text" id="comfy-url" class="mono" value="${esc(c.url || "")}" placeholder="http://127.0.0.1:8188"></div>
      </div>
      <div class="row" style="margin-top:12px">
        <button class="btn primary" id="comfy-save">Save</button>
        <button class="btn" id="comfy-refresh">Look again</button>
        <span>${state}</span>
      </div>
      ${rows ? `<div class="tablewrap" style="margin-top:12px"><table class="stack">
        <tr><th>Model</th><th>Makes</th><th>Family</th><th>Status</th></tr>${rows}</table></div>` : ""}
      ${looseRows ? `<h3 style="margin-top:16px">Not recognised</h3>
        <p class="small muted">Model files whose name matches no workflow. If one is a variant of a family hemera
        knows, say which.</p>
        <div class="tablewrap"><table class="stack"><tr><th>File</th><th>Use as</th></tr>${looseRows}</table></div>` : ""}
    </div>`);
  const save = async (msg) => {
    try { await PUT("/hemera/api/config", CONFIG); toast(msg); renderComfy(true); } catch (e) { fail(e); }
  };
  $("#comfy-save").onclick = () => {
    const dir = $("#comfy-dir").value.trim(), url = $("#comfy-url").value.trim();
    const next = Object.assign({}, c);
    if (dir) next.dir = dir; else delete next.dir;
    if (url) next.url = url; else delete next.url;
    if (Object.keys(next).length) CONFIG.comfyui = next; else delete CONFIG.comfyui;
    save("ComfyUI settings saved");
  };
  $("#comfy-refresh").onclick = () => renderComfy(true);
  const assign = (i, recipe) => {
    CONFIG.comfyui = Object.assign({}, c, { assign: Object.assign({}, c.assign, { [loose[i].file]: recipe }) });
    save(recipe === "-" ? "File hidden" : "File assigned");
  };
  $$("[data-comfy-assign]", el).forEach(b => b.onclick = () =>
    assign(+b.dataset.comfyAssign, $(`[data-comfy-recipe="${b.dataset.comfyAssign}"]`, el).value));
  $$("[data-comfy-hide]", el).forEach(b => b.onclick = () => assign(+b.dataset.comfyHide, "-"));
}

// ---------------------------------------------------------------- proxies

// ---------------------------------------------------------------- images

// Kept outside the render so switching tabs (or a background refresh) doesn't throw away a
// result that took minutes to produce.
let IMG_STATE = { results: [], busy: false };

function renderImages() {
  const el = $("#sec-images");
  const models = (CONFIG.models || []).filter(m => isSdEngine(m.engine)).concat(
    ((COMFY && COMFY.models) || []).filter(m => m.ready && m.kind === "image").map(m => ({ name: m.id })));
  if (!models.length) {
    paint(el, `<h2>Images</h2>
      <div class="card"><p>No image models configured yet.</p>
      <p class="small muted">Install the <strong>sd.cpp</strong> engine in the Engines tab, then pick a
      diffusion model (badged <span class="badge warn">image</span>) in the Models tab. Its VAE and text
      encoder are detected automatically.</p></div>`);
    return;
  }
  const sel = IMG_STATE.model && models.some(m => m.name === IMG_STATE.model)
    ? IMG_STATE.model : models[0].name;
  const transparentCapable = (models.find(m => m.name === sel) || {}).transparentPrompt;

  paint(el, `
    <h2>Images</h2>
    <div class="card">
      <div class="formgrid">
        <div class="full"><label>Prompt</label>
          <textarea id="im-prompt" placeholder="a lovely cat holding a sign that says hemera">${esc(IMG_STATE.prompt || "")}</textarea></div>
        <div><label>Model</label>
          <select id="im-model">${models.map(m => `<option ${m.name === sel ? "selected" : ""}>${esc(m.name)}</option>`).join("")}</select></div>
        <div><label>Size (must divide by 32)</label>
          <input type="text" id="im-size" class="mono" value="${esc(IMG_STATE.size || "1024x1024")}"></div>
        <div><label>Steps</label>
          <input type="number" id="im-steps" value="${esc(IMG_STATE.steps || 20)}"></div>
        <div><label>Seed (-1 = random)</label>
          <input type="number" id="im-seed" value="${esc(IMG_STATE.seed != null ? IMG_STATE.seed : -1)}"></div>
        <div class="full"><label>Reference images — attach to edit instead of generate</label>
          <input type="file" id="im-files" accept="image/*" multiple>
          <p class="small muted">Several are allowed, and the prompt can refer to them in order
          ("put the person from the first image next to the person from the second").</p>
          <label class="row" style="margin:6px 0 0;gap:8px">
            <input type="checkbox" id="im-keepsize" style="width:auto" ${IMG_STATE.keepSize ? "checked" : ""}>
            <span class="small">Keep each reference's own framing — by default they are centre-cropped
            to the output size, which cuts the edges off photos of a different shape (faces included)</span>
          </label></div>
        <div class="full"><label class="row" style="margin:0;gap:8px">
          <input type="checkbox" id="im-transparent" style="width:auto" ${IMG_STATE.transparent ? "checked" : ""}
            ${transparentCapable ? "" : "disabled"}>
          <span>Transparent background${transparentCapable ? "" : " — needs a transparent-prompt template on this model"}</span>
        </label></div>
      </div>
      <div class="row actions">
        <button class="btn primary" id="im-go">${IMG_STATE.busy ? "Working…" : "Generate"}</button>
        <button class="btn danger" id="im-stop" style="${IMG_STATE.busy ? "" : "display:none"}">Stop</button>
        <span class="small muted" id="im-status"></span>
      </div>
    </div>
    <div id="im-results"></div>`);

  const filesInput = $("#im-files");
  const go = $("#im-go");
  const stop = $("#im-stop");
  const status = $("#im-status");

  const label = () => go.textContent = filesInput.files.length
    ? "Edit " + filesInput.files.length + " image" + (filesInput.files.length > 1 ? "s" : "") : "Generate";
  filesInput.onchange = label;
  label();
  $("#im-model").onchange = ev => { IMG_STATE.model = ev.target.value; renderImages(); };

  go.onclick = async () => {
    if (IMG_STATE.busy) return;
    const prompt = $("#im-prompt").value.trim();
    if (!prompt) { toast("Enter a prompt first", true); return; }
    // Naming the request up front is what makes Stop possible: the id is known before the
    // (minutes-long) response arrives, so the cancel endpoint can be told which job to drop.
    const reqId = "ui_" + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
    const idHeader = { "X-Hemera-Request-Id": reqId };
    IMG_STATE = { ...IMG_STATE, busy: true, stopped: false, reqId, prompt, model: $("#im-model").value,
      size: $("#im-size").value.trim(), steps: +$("#im-steps").value, seed: +$("#im-seed").value,
      transparent: $("#im-transparent").checked, keepSize: $("#im-keepsize").checked };
    go.disabled = true;
    stop.style.display = "";
    const started = Date.now();
    // Generation is slow enough that silence looks like a hang — count up while we wait.
    const tick = setInterval(() => status.textContent =
      "running " + Math.round((Date.now() - started) / 1000) + "s — the first request also loads the model",
      500);
    try {
      const body = { model: IMG_STATE.model, prompt, size: IMG_STATE.size, steps: IMG_STATE.steps };
      if (IMG_STATE.seed >= 0) body.seed = IMG_STATE.seed;
      if (IMG_STATE.transparent) body.background = "transparent";
      let data;
      if (filesInput.files.length) {
        const form = new FormData();
        Object.entries(body).forEach(([k, v]) => form.append(k, v));
        // Multipart fields are all text, so the native option rides along in the prompt's own
        // escape hatch rather than as a form field.
        if (IMG_STATE.keepSize)
          form.set("prompt", prompt + ' <sd_cpp_extra_args>{"auto_resize_ref_image":false}</sd_cpp_extra_args>');
        for (const f of filesInput.files) form.append("image[]", f, f.name);
        data = await postForm("/v1/images/edits", form, idHeader);
      } else {
        data = await POST("/v1/images/generations", body, idHeader);
      }
      const secs = Math.round((Date.now() - started) / 1000);
      IMG_STATE.results = (data.data || []).map(d => ({
        src: "data:image/" + (data.output_format || "png") + ";base64," + d.b64_json,
        prompt, secs, at: new Date().toLocaleTimeString()
      })).concat(IMG_STATE.results).slice(0, 8);
      status.textContent = "done in " + secs + "s";
    } catch (e) {
      // A request we cancelled ourselves comes back as an error; that is the expected outcome.
      if (IMG_STATE.stopped) status.textContent = "stopped — the GPU has been released";
      else { status.textContent = ""; fail(e); }
    } finally {
      clearInterval(tick);
      IMG_STATE.busy = false;
      go.disabled = false;
      stop.style.display = "none";
      paintResults();
    }
  };
  stop.onclick = async () => {
    if (!IMG_STATE.busy || !IMG_STATE.reqId) return;
    IMG_STATE.stopped = true;
    stop.disabled = true;
    try { await POST("/hemera/api/images/cancel", { id: IMG_STATE.reqId }); }
    catch (e) { IMG_STATE.stopped = false; fail(e); }
    finally { stop.disabled = false; }
  };
  paintResults();
}

function paintResults() {
  const holder = $("#im-results");
  if (!holder) return;
  holder.innerHTML = IMG_STATE.results.map((r, i) => `
    <div class="card">
      <img src="${r.src}" alt="${esc(r.prompt)}" style="max-width:100%;border-radius:8px;display:block">
      <div class="row spread" style="margin-top:8px">
        <span class="small muted">${esc(r.at)} · ${r.secs}s · ${esc(r.prompt).slice(0, 80)}</span>
        <a class="btn xs" download="hemera-${i}.png" href="${r.src}">Download</a>
      </div>
    </div>`).join("");
}

function renderProxies() {
  const el = $("#sec-proxies");
  const proxies = CONFIG.proxies || [];
  paint(el, `
    <h2>External model proxies</h2>
    <div class="card">
      <div class="row spread">
        <h3>Configured proxies</h3>
        <button class="btn xs primary" id="proxy-new">New proxy</button>
      </div>
      <div class="tablewrap"><table>
        <tr><th>Name</th><th>Type</th><th>Base URL</th><th>Upstream model</th><th></th></tr>
        ${proxies.map((p, i) => `<tr>
          <td class="mono">${esc(p.name)}</td>
          <td><span class="badge">${esc(p.type || "openai")}</span></td>
          <td class="mono small">${esc(p.baseUrl || "")}</td>
          <td class="mono small">${esc(p.upstreamModel || "")}</td>
          <td><button class="btn xs" data-pedit="${i}">Edit</button>
              <button class="btn danger xs" data-pdel="${i}">Delete</button></td>
        </tr>`).join("") || '<tr><td colspan="5" class="muted">No proxies configured.</td></tr>'}
      </table></div>
      <p class="small muted">Proxy models appear in all three APIs under their name.
      Type <b>openai</b> covers OpenAI, DeepSeek (<span class="mono">https://api.deepseek.com/v1</span>),
      Gemini's OpenAI endpoint (<span class="mono">https://generativelanguage.googleapis.com/v1beta/openai</span>) and
      other OpenAI-compatible services. Type <b>anthropic</b> is the Claude API
      (<span class="mono">https://api.anthropic.com</span>).</p>
    </div>`);
  $("#proxy-new").onclick = () => proxyForm(null);
  $$("[data-pedit]", el).forEach(b => b.onclick = () => proxyForm(proxies[+b.dataset.pedit]));
  $$("[data-pdel]", el).forEach(b => b.onclick = async () => {
    if (!confirm("Delete proxy '" + proxies[+b.dataset.pdel].name + "'?")) return;
    CONFIG.proxies = proxies.filter((_, i) => i !== +b.dataset.pdel);
    try { await PUT("/hemera/api/config", CONFIG); toast("Proxy deleted"); navigate(); } catch (e) { fail(e); }
  });
}

function proxyForm(existing) {
  const p = existing || {};
  openModal(`
    <h3>${existing ? "Edit proxy" : "New proxy"}</h3>
    <div class="formgrid">
      <div><label>Name (model name exposed to clients)</label>
        <input type="text" id="pf-name" value="${esc(p.name || "")}" ${existing ? "readonly" : ""}></div>
      <div><label>Type</label>
        <select id="pf-type">
          <option value="openai" ${p.type !== "anthropic" ? "selected" : ""}>openai (OpenAI-compatible)</option>
          <option value="anthropic" ${p.type === "anthropic" ? "selected" : ""}>anthropic (Claude API)</option>
        </select></div>
      <div class="full"><label>Base URL</label>
        <input type="text" id="pf-url" class="mono" value="${esc(p.baseUrl || "")}"
          placeholder="https://api.deepseek.com/v1"></div>
      <div><label>API key</label>
        <input type="password" id="pf-key" value="${esc(p.apiKey || "")}"></div>
      <div><label>Upstream model id</label>
        <input type="text" id="pf-model" class="mono" value="${esc(p.upstreamModel || "")}"
          placeholder="deepseek-chat"></div>
    </div>
    <div class="row" style="margin-top:16px">
      <button class="btn primary" id="pf-save">Save</button>
      <button class="btn" id="pf-cancel">Cancel</button>
    </div>`);
  $("#pf-cancel").onclick = closeModal;
  $("#pf-save").onclick = async () => {
    const entry = {
      name: $("#pf-name").value.trim(),
      type: $("#pf-type").value,
      baseUrl: $("#pf-url").value.trim(),
      apiKey: $("#pf-key").value,
      upstreamModel: $("#pf-model").value.trim()
    };
    if (!entry.name || !entry.baseUrl) { toast("name and base URL are required", true); return; }
    const list = (CONFIG.proxies || []).filter(x => x.name !== entry.name);
    list.push(entry);
    CONFIG.proxies = list;
    try { await PUT("/hemera/api/config", CONFIG); closeModal(); toast("Proxy saved"); navigate(); } catch (e) { fail(e); }
  };
}

// ---------------------------------------------------------------- settings

function renderSettings() {
  const el = $("#sec-settings");
  const engines = CONFIG.engines || {};
  const engineBlocks = Object.entries(engines).map(([name, e]) => `
    <div class="card">
      <h3>${esc(name)}</h3>
      <label>Tracked variants for update checks (comma separated substrings of asset names)</label>
      <input type="text" class="mono" data-set-tracked="${esc(name)}" value="${esc((e.trackedVariants || []).join(", "))}">
      <label>Default arguments (prepended for every model on this engine)</label>
      <input type="text" class="mono" data-set-args="${esc(name)}" value="${esc((e.defaultArgs || []).join(" "))}">
      <label>Extra library directories (comma separated; added to LD_LIBRARY_PATH, highest priority)</label>
      <input type="text" class="mono" data-set-libs="${esc(name)}" value="${esc((e.libraryPaths || []).join(", "))}">
      <label class="row" style="margin-top:10px"><input type="checkbox" style="width:auto"
        data-set-autolib="${esc(name)}" ${e.libraryAutoDetect === false ? "" : "checked"}>
        auto-detect runtime libraries (ROCm / CUDA / oneAPI / OpenVINO)</label>
      ${e.binaryPath !== undefined ? `<label>Manual binary path</label>
        <input type="text" class="mono" data-set-bin="${esc(name)}" value="${esc(e.binaryPath || "")}">` : ""}
    </div>`).join("");

  paint(el, `
    <h2>Settings</h2>
    <div class="card">
      <div class="formgrid">
        <div><label>Port (restart required)</label>
          <input type="number" id="set-port" value="${CONFIG.port || 12434}"></div>
        <div><label>Log level (applies immediately; trace = http bodies + engine output)</label>
          <select id="set-log">
            ${["info", "debug", "trace"].map(l => `<option ${CONFIG.logLevel === l ? "selected" : ""}>${l}</option>`).join("")}
          </select></div>
        <div><label>Default keep-alive seconds</label>
          <input type="number" id="set-keepalive" value="${CONFIG.keepAliveSeconds != null ? CONFIG.keepAliveSeconds : 300}"></div>
        <div><label>Model load timeout seconds</label>
          <input type="number" id="set-timeout" value="${CONFIG.loadTimeoutSeconds != null ? CONFIG.loadTimeoutSeconds : 600}"></div>
        <div class="full"><label>Extra library search roots (comma separated; globs allowed, e.g. /opt/myrocm*/lib)</label>
          <input type="text" id="set-libroots" class="mono" value="${esc((CONFIG.librarySearchRoots || []).join(", "))}">
          <p class="small muted">Searched in addition to the built-in locations for ROCm, CUDA, oneAPI and
          OpenVINO. Only needed when a runtime lives somewhere unusual.</p></div>
      </div>
    </div>
    ${engineBlocks}
    <div class="row">
      <button class="btn primary" id="set-save">Save settings</button>
    </div>`);
  $("#set-log").onchange = async ev => {
    try { await POST("/hemera/api/loglevel", { level: ev.target.value }); toast("Log level: " + ev.target.value); refreshStatusChip(); }
    catch (e) { fail(e); }
  };
  $("#set-save").onclick = async () => {
    try {
      CONFIG.port = parseInt($("#set-port").value, 10) || 12434;
      CONFIG.keepAliveSeconds = parseInt($("#set-keepalive").value, 10);
      CONFIG.loadTimeoutSeconds = parseInt($("#set-timeout").value, 10);
      CONFIG.librarySearchRoots = $("#set-libroots").value.split(",").map(s => s.trim()).filter(Boolean);
      $$("[data-set-tracked]").forEach(i => {
        engines[i.dataset.setTracked].trackedVariants = i.value.split(",").map(s => s.trim()).filter(Boolean);
      });
      $$("[data-set-args]").forEach(i => {
        engines[i.dataset.setArgs].defaultArgs = i.value.trim().split(/\s+/).filter(Boolean);
      });
      $$("[data-set-bin]").forEach(i => {
        engines[i.dataset.setBin].binaryPath = i.value.trim();
      });
      $$("[data-set-libs]").forEach(i => {
        engines[i.dataset.setLibs].libraryPaths = i.value.split(",").map(s => s.trim()).filter(Boolean);
      });
      $$("[data-set-autolib]").forEach(i => {
        engines[i.dataset.setAutolib].libraryAutoDetect = i.checked;
      });
      await PUT("/hemera/api/config", CONFIG);
      toast("Settings saved");
    } catch (e) { fail(e); }
  };
}

// ---------------------------------------------------------------- boot

refreshStatusChip();
setInterval(refreshStatusChip, 10000);
navigate();
