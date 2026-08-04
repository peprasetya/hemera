/* Hemera configure UI — vanilla JS, no dependencies. */
"use strict";

const $ = (sel, el) => (el || document).querySelector(sel);
const $$ = (sel, el) => Array.from((el || document).querySelectorAll(sel));

// ---------------------------------------------------------------- api helpers

async function api(method, path, body) {
  const opts = { method, headers: {} };
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
const GET = p => api("GET", p);
const POST = (p, b) => api("POST", p, b);
const PUT = (p, b) => api("PUT", p, b);
const DEL = p => api("DELETE", p);

// ---------------------------------------------------------------- state

let CONFIG = null;   // full hemera.json document
let STATUS = null;   // /status
let SCAN = null;     // /scan result
let USAGE = {};      // engine binary -> [model names]
let RELEASES = {};   // engine -> releases list
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

let toastTimer = null;
function toast(msg, isError) {
  const t = $("#toast");
  t.textContent = msg;
  t.className = isError ? "error" : "";
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add("hidden"), isError ? 8000 : 3500);
}
function fail(e) { toast(String(e.message || e), true); console.error(e); }

function openModal(html) {
  $("#modal-card").innerHTML = html;
  $("#modal").classList.remove("hidden");
}
function closeModal() { $("#modal").classList.add("hidden"); }
$("#modal").addEventListener("click", e => { if (e.target.id === "modal") closeModal(); });

// ---------------------------------------------------------------- navigation

const SECTIONS = ["dashboard", "models", "engines", "dirs", "proxies", "settings"];

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
    if (sec === "dashboard") await renderDashboard();
    if (sec === "models") await renderModels();
    if (sec === "engines") await renderEngines();
    if (sec === "dirs") renderDirs();
    if (sec === "proxies") renderProxies();
    if (sec === "settings") renderSettings();
  } catch (e) { fail(e); }
}
window.addEventListener("hashchange", navigate);

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
  const rows = STATUS.instances.map(i => `
    <tr>
      <td class="mono">${esc(i.name)}</td>
      <td>${esc(i.engine)}</td>
      <td class="mono">${i.port}</td>
      <td class="mono">${i.pid}</td>
      <td class="small muted">${esc(i.startedAt.replace("T", " ").slice(0, 19))}</td>
      <td class="small muted">${i.keepAliveSeconds < 0 ? "forever" : i.keepAliveSeconds + "s"}</td>
      <td><button class="btn danger xs" data-stop="${esc(i.name)}">Stop</button></td>
    </tr>`).join("");
  el.innerHTML = `
    <h2>Dashboard</h2>
    <div class="card">
      <div class="row spread">
        <h3>Running models</h3>
        <button class="btn xs" id="dash-refresh">Refresh</button>
      </div>
      <div class="tablewrap"><table>
        <tr><th>Model</th><th>Engine binary</th><th>Port</th><th>PID</th><th>Started</th><th>Keep-alive</th><th></th></tr>
        ${rows || '<tr><td colspan="7" class="muted">Nothing loaded. Models start on first API request.</td></tr>'}
      </table></div>
    </div>
    <div class="card small muted">
      Hemera ${esc(STATUS.version)} · home <span class="mono">${esc(STATUS.home)}</span>
      · port ${STATUS.port} · uptime ${Math.floor(STATUS.uptimeSeconds / 60)} min
      · API endpoints: <span class="mono">/api/*</span> (ollama), <span class="mono">/v1/chat/completions</span> (openai),
      <span class="mono">/v1/messages</span> (claude)
    </div>`;
  $("#dash-refresh").onclick = () => renderDashboard().catch(fail);
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

async function renderModels() {
  SCAN = await GET("/hemera/api/scan");
  const el = $("#sec-models");

  const cfgRows = (CONFIG.models || []).map(m => `
    <tr>
      <td class="mono">${esc(m.name)}</td>
      <td class="small">${esc(modelBinaryDesc(m))}</td>
      <td class="small mono" title="${esc(m.file || "")}">${esc(basename(m.file))}${m.mmproj ? '<br><span class="badge">mmproj</span>' : ""}</td>
      <td class="small mono">${esc((m.args || []).join(" "))}</td>
      <td>
        <button class="btn xs" data-edit="${esc(m.name)}">Edit</button>
        <button class="btn danger xs" data-del="${esc(m.name)}">Delete</button>
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
      <td class="mono small" title="${esc(f.path)}">${esc(f.name)}
        ${f.source === "ollama" ? '<span class="badge">ollama repo</span>' : ""}
        ${f.mtpHint ? '<span class="badge warn">mtp</span>' : ""}
        ${f.parts > 1 ? '<span class="badge">' + f.parts + " parts</span>" : ""}</td>
      <td class="small">${humanSize(f.size)}</td>
      <td class="small muted">${esc(metaTxt)}</td>
      <td>${used || '<span class="muted small">unused</span>'}</td>
      <td><button class="btn xs primary" data-cfg="${idx}">Configure</button></td>
    </tr>`;
  }).join("");

  const companions = (SCAN.files || []).filter(f => f.role !== "model");
  const compRows = companions.map(f => `
    <tr>
      <td class="mono small" title="${esc(f.path)}">${esc(f.name)}</td>
      <td><span class="badge warn">${esc(f.role)}</span></td>
      <td class="small">${humanSize(f.size)}</td>
      <td>${(f.usedBy || []).map(u => `<span class="badge ok">${esc(u)}</span>`).join("") || '<span class="muted small">unused</span>'}</td>
    </tr>`).join("");

  el.innerHTML = `
    <h2>Models</h2>
    <div class="card">
      <div class="row spread">
        <h3>Configured models</h3>
        <button class="btn xs primary" id="model-new">New model</button>
      </div>
      <div class="tablewrap"><table>
        <tr><th>Name</th><th>Engine binary</th><th>File</th><th>Args</th><th></th></tr>
        ${cfgRows || '<tr><td colspan="5" class="muted">No models configured yet — pick a scanned file below.</td></tr>'}
      </table></div>
      <p class="small muted">One model file can be configured several times under different names
      to vary parameters. Models are reachable via all three APIs by name.</p>
    </div>
    <div class="card">
      <div class="row spread">
        <h3>Scanned model files</h3>
        <span class="small"><a href="https://ollama.com/search" target="_blank" rel="noopener">Browse ollama library &#8599;</a></span>
      </div>
      <div class="tablewrap"><table>
        <tr><th>File</th><th>Size</th><th>Metadata</th><th>Used by</th><th></th></tr>
        ${scanRows || '<tr><td colspan="5" class="muted">Nothing found. Add model directories in the Directories tab.</td></tr>'}
      </table></div>
      ${companions.length ? `<h3 style="margin-top:14px">Companion files (mmproj / mtp)</h3>
      <div class="tablewrap"><table>
        <tr><th>File</th><th>Type</th><th>Size</th><th>Used by</th></tr>${compRows}
      </table></div>` : ""}
    </div>`;

  $("#model-new").onclick = () => modelForm(null, null);
  $$("[data-cfg]", el).forEach(b => b.onclick = () => modelForm(null, modelFiles[+b.dataset.cfg]));
  $$("[data-edit]", el).forEach(b => b.onclick = () => {
    const m = (CONFIG.models || []).find(x => x.name === b.dataset.edit);
    if (m) modelForm(m, null);
  });
  $$("[data-del]", el).forEach(b => b.onclick = () => deleteModelDialog(b.dataset.del));
}

function suggestArgs(meta) {
  const ctx = meta && meta["hemera.context"] ? Math.min(meta["hemera.context"], 32768) : 8192;
  return "-c " + ctx + " -ngl 99 --jinja";
}
function suggestName(fname) {
  return String(fname || "").replace(/\.gguf$/i, "").replace(/-\d{5}-of-\d{5}$/, "")
    .toLowerCase().replace(/[^a-z0-9._-]+/g, "-");
}

function modelForm(existing, scanFile) {
  const m = existing || {};
  const engines = Object.keys(CONFIG.engines || { "llama.cpp": {} });
  const engine = m.engine || "llama.cpp";
  const installed = ((CONFIG.engines || {})[engine] || {}).installed || [];
  const versions = [...new Set(installed.map(i => i.version))].sort().reverse();
  const variants = [...new Set(installed.map(i => i.variant))];
  const name = m.name || (scanFile ? (scanFile.source === "ollama" ? scanFile.name : suggestName(scanFile.name)) : "");
  const file = m.file || (scanFile ? scanFile.path : "");
  const mmproj = m.mmproj || (scanFile && scanFile.mmprojPath ? scanFile.mmprojPath : "");
  const args = (m.args || []).join(" ") || (scanFile ? suggestArgs(scanFile.meta) : "");
  const envLines = Object.entries(m.env || {}).map(([k, v]) => k + "=" + v).join("\n");
  const extraLines = Object.entries(m.extraFiles || {}).map(([k, v]) => k + "=" + v).join("\n");
  const mmprojOptions = (SCAN && SCAN.files || []).filter(f => f.role === "mmproj")
    .map(f => `<option value="${esc(f.path)}" ${f.path === mmproj ? "selected" : ""}>${esc(f.name)}</option>`).join("");

  openModal(`
    <h3>${existing ? "Edit model" : "New model"}</h3>
    <div class="formgrid">
      <div class="full"><label>Name (as exposed to clients)</label>
        <input type="text" id="mf-name" value="${esc(name)}" ${existing ? "readonly" : ""}></div>
      <div><label>Engine</label>
        <select id="mf-engine">${engines.map(e => `<option ${e === engine ? "selected" : ""}>${esc(e)}</option>`).join("")}</select></div>
      <div><label>Engine version</label>
        <select id="mf-version">
          <option value="latest">latest (follow updates)</option>
          ${versions.map(v => `<option ${v === m.engineVersion ? "selected" : ""}>${esc(v)}</option>`).join("")}
        </select></div>
      <div class="full"><label>Engine variant filter (substring, e.g. "rocm" or "vulkan"; empty = any)</label>
        <input type="text" id="mf-variant" value="${esc(m.engineVariant || "")}" list="mf-variants">
        <datalist id="mf-variants">${variants.map(v => `<option>${esc(v)}</option>`).join("")}</datalist></div>
      <div class="full"><label>Model file (.gguf — first part for split models)</label>
        <input type="text" id="mf-file" class="mono" value="${esc(file)}"></div>
      <div class="full"><label>mmproj file (optional, for vision/audio models)</label>
        <input type="text" id="mf-mmproj" class="mono" value="${esc(mmproj)}" list="mf-mmprojs">
        <datalist id="mf-mmprojs">${mmprojOptions}</datalist></div>
      <div class="full"><label>Extra files (one per line: flag=path, e.g. mtp=/path/mtp.gguf)</label>
        <textarea id="mf-extra" class="mono">${esc(extraLines)}</textarea></div>
      <div class="full"><label>Arguments for the engine (space separated)</label>
        <textarea id="mf-args" class="mono">${esc(args)}</textarea></div>
      <div class="full"><label>Environment overrides (one per line: KEY=value, e.g. HSA_OVERRIDE_GFX_VERSION=11.5.1)</label>
        <textarea id="mf-env" class="mono">${esc(envLines)}</textarea></div>
      <div><label>Keep-alive seconds (-1 = never unload)</label>
        <input type="number" id="mf-keepalive" value="${m.keepAliveSeconds != null ? m.keepAliveSeconds : (CONFIG.keepAliveSeconds != null ? CONFIG.keepAliveSeconds : 300)}"></div>
    </div>
    <div class="row" style="margin-top:16px">
      <button class="btn primary" id="mf-save">Save</button>
      <button class="btn" id="mf-cancel">Cancel</button>
    </div>`);

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
        mmproj: $("#mf-mmproj").value.trim(),
        extraFiles: parseKv($("#mf-extra").value),
        args: $("#mf-args").value.trim().split(/\s+/).filter(Boolean),
        env: parseKv($("#mf-env").value),
        keepAliveSeconds: parseInt($("#mf-keepalive").value, 10)
      };
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

function deleteModelDialog(name) {
  const m = (CONFIG.models || []).find(x => x.name === name);
  const lastRef = m && m.file ? fileRefCount(m.file) <= 1 : false;
  const inBlobs = m && m.file && m.file.includes("/blobs/");
  openModal(`
    <h3>Delete model '${esc(name)}'</h3>
    <p class="small">Removes the configured model (same as <span class="mono">ollama rm</span>).</p>
    ${m && m.file ? `<p class="small mono muted">${esc(m.file)}</p>` : ""}
    ${inBlobs ? '<p class="small muted">File lives in an ollama repository — it will not be deleted from disk.</p>'
      : lastRef ? `<label class="row" style="margin:8px 0"><input type="checkbox" id="dm-files" style="width:auto"> also delete the file(s) from disk (last reference)</label>`
      : '<p class="small muted">The file is used by other configured models and will be kept on disk.</p>'}
    <div class="row" style="margin-top:14px">
      <button class="btn danger" id="dm-ok">Delete</button>
      <button class="btn" id="dm-cancel">Cancel</button>
    </div>`);
  $("#dm-cancel").onclick = closeModal;
  $("#dm-ok").onclick = async () => {
    try {
      const delFiles = $("#dm-files") && $("#dm-files").checked;
      const res = await DEL("/hemera/api/models?name=" + encodeURIComponent(name) + "&deleteFiles=" + !!delFiles);
      closeModal();
      toast("Deleted '" + name + "'" + (res.deletedFiles && res.deletedFiles.length ? " + " + res.deletedFiles.length + " file(s)" : ""));
      navigate();
    } catch (e) { fail(e); }
  };
}

// ---------------------------------------------------------------- engines

async function renderEngines() {
  USAGE = await GET("/hemera/api/engines/usage");
  const el = $("#sec-engines");
  const engines = CONFIG.engines || {};

  const blocks = Object.entries(engines).map(([name, e]) => {
    const installed = (e.installed || []).slice().sort((a, b) => (b.version || "").localeCompare(a.version || "", undefined, { numeric: true }));
    const rows = installed.map(i => {
      const key = name + "/" + i.version + "/" + i.variant;
      const used = USAGE[key] || [];
      return `
      <tr>
        <td class="mono">${esc(i.version)}</td>
        <td class="mono small">${esc(i.variant)}</td>
        <td class="small muted">${esc((i.installedAt || "").slice(0, 10))}</td>
        <td>${used.map(u => `<span class="badge ok">${esc(u)}</span>`).join("") || '<span class="badge">unused</span>'}</td>
        <td><button class="btn danger xs" data-rm-engine="${esc(name)}" data-version="${esc(i.version)}"
             data-variant="${esc(i.variant)}" ${used.length ? "disabled title='in use'" : ""}>Remove</button></td>
      </tr>`;
    }).join("");
    return `
    <div class="card">
      <div class="row spread">
        <h3>${esc(name)} <span class="muted small">${esc(e.repo || (e.binaryPath ? "manual: " + e.binaryPath : ""))}</span></h3>
        ${e.repo ? `<button class="btn xs primary" data-releases="${esc(name)}">Check releases</button>` : ""}
      </div>
      <div class="tablewrap"><table>
        <tr><th>Version</th><th>Variant</th><th>Installed</th><th>Used by models</th><th></th></tr>
        ${rows || '<tr><td colspan="5" class="muted">No binaries installed yet.</td></tr>'}
      </table></div>
      <p class="small muted">Older versions are kept as rollback history — pin a model to a previous
      version in its edit form if a new binary misbehaves.</p>
      <div id="releases-${esc(name)}"></div>
    </div>`;
  }).join("");

  el.innerHTML = `<h2>Engines</h2><div id="engine-progress"></div>${blocks}`;
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
      && currentSection() === "engines") navigate();
  }, 1500);
}

// ---------------------------------------------------------------- directories

function renderDirs() {
  const el = $("#sec-dirs");
  const dirs = CONFIG.modelDirs || [];
  el.innerHTML = `
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
    </div>`;
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

// ---------------------------------------------------------------- proxies

function renderProxies() {
  const el = $("#sec-proxies");
  const proxies = CONFIG.proxies || [];
  el.innerHTML = `
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
    </div>`;
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
      ${e.binaryPath !== undefined ? `<label>Manual binary path</label>
        <input type="text" class="mono" data-set-bin="${esc(name)}" value="${esc(e.binaryPath || "")}">` : ""}
    </div>`).join("");

  el.innerHTML = `
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
      </div>
    </div>
    ${engineBlocks}
    <div class="row">
      <button class="btn primary" id="set-save">Save settings</button>
    </div>`;
  $("#set-log").onchange = async ev => {
    try { await POST("/hemera/api/loglevel", { level: ev.target.value }); toast("Log level: " + ev.target.value); refreshStatusChip(); }
    catch (e) { fail(e); }
  };
  $("#set-save").onclick = async () => {
    try {
      CONFIG.port = parseInt($("#set-port").value, 10) || 12434;
      CONFIG.keepAliveSeconds = parseInt($("#set-keepalive").value, 10);
      CONFIG.loadTimeoutSeconds = parseInt($("#set-timeout").value, 10);
      $$("[data-set-tracked]").forEach(i => {
        engines[i.dataset.setTracked].trackedVariants = i.value.split(",").map(s => s.trim()).filter(Boolean);
      });
      $$("[data-set-args]").forEach(i => {
        engines[i.dataset.setArgs].defaultArgs = i.value.trim().split(/\s+/).filter(Boolean);
      });
      $$("[data-set-bin]").forEach(i => {
        engines[i.dataset.setBin].binaryPath = i.value.trim();
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
