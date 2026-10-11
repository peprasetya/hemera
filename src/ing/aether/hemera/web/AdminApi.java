package ing.aether.hemera.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpExchange;

import ing.aether.hemera.Hemera;
import ing.aether.hemera.Log;
import ing.aether.hemera.comfy.ComfyBridge;
import ing.aether.hemera.config.Config;
import ing.aether.hemera.engine.EngineDownloader;
import ing.aether.hemera.engine.Engines;
import ing.aether.hemera.engine.Instance;
import ing.aether.hemera.engine.LibraryResolver;
import ing.aether.hemera.engine.InstanceManager;
import ing.aether.hemera.http.HttpError;
import ing.aether.hemera.http.Router;
import ing.aether.hemera.json.Json;
import ing.aether.hemera.model.ModelRegistry;
import ing.aether.hemera.model.ModelScanner;

/** Admin endpoints under /hemera/api/ used by the /configure/ web UI. */
public final class AdminApi
{
  private final Config cfg;
  private final InstanceManager im;
  private final ModelRegistry registry;
  private final ComfyBridge comfy;
  private final long startedAt = System.currentTimeMillis();

  public AdminApi(Config cfg, InstanceManager im, ModelRegistry registry, ComfyBridge comfy, Router router)
  {
    this.comfy = comfy;
    this.cfg = cfg;
    this.im = im;
    this.registry = registry;

    router.add("GET", "/hemera/api/status", this::status);
    router.add("GET", "/hemera/api/config", ex -> Router.sendJson(ex, 200, cfg.data()));
    router.add("PUT", "/hemera/api/config", this::putConfig);
    router.add("GET", "/hemera/api/scan", this::scan);
    router.add("GET", "/hemera/api/engines/releases", this::releases);
    router.add("POST", "/hemera/api/engines/install", this::install);
    router.add("GET", "/hemera/api/engines/progress", ex -> Router.sendJson(ex, 200, EngineDownloader.jobs()));
    router.add("GET", "/hemera/api/engines/usage", ex -> Router.sendJson(ex, 200, Engines.usage(cfg)));
    router.add("GET", "/hemera/api/engines/libraries", this::libraries);
    router.add("DELETE", "/hemera/api/engines", this::removeEngine);
    router.add("POST", "/hemera/api/models", this::putModel);
    router.add("DELETE", "/hemera/api/models", this::deleteModel);
    router.add("DELETE", "/hemera/api/files", this::deleteFile);
    router.add("POST", "/hemera/api/models/load", this::loadModel);
    router.add("POST", "/hemera/api/models/stop", this::stopModel);
    router.add("POST", "/hemera/api/loglevel", this::logLevel);
    router.add("GET", "/hemera/api/comfyui",
        ex -> Router.sendJson(ex, 200, comfy.status("1".equals(Router.query(ex, "refresh")))));
  }

  private void status(HttpExchange ex) throws Exception
  {
    List<Object> instances = new ArrayList<>();
    for (Instance i : im.list())
      instances.add(Json.map(
          "name", i.name, "port", i.port, "pid", i.process.pid(),
          "engine", i.engineDesc,
          "startedAt", Instant.ofEpochMilli(i.startedAt).toString(),
          "lastUsed", Instant.ofEpochMilli(i.lastUsed).toString(),
          "keepAliveSeconds", i.keepAliveSec));
    List<Object> loading = new ArrayList<>();
    for (InstanceManager.LoadState l : im.loadStates())
      loading.add(Json.map("name", l.name(), "error", l.error(),
          "elapsedSeconds", (System.currentTimeMillis() - l.startedAt()) / 1000));
    Router.sendJson(ex, 200, Json.map(
        "version", Hemera.VERSION,
        "home", cfg.home.toString(),
        "loading", loading,
        "port", cfg.port(),
        "logLevel", Log.levelName(),
        "uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000,
        "instances", instances));
  }

  private void putConfig(HttpExchange ex) throws Exception
  {
    Map<String, Object> body = Router.readJson(ex);
    if (!(body.get("port") instanceof Number))
      throw new HttpError(400, "config must contain a numeric 'port'");
    cfg.replace(body);
    Log.setLevel(cfg.logLevel());
    LibraryResolver.clearCache();
    comfy.invalidate();
    Log.info("admin", "Configuration replaced via web UI");
    Router.sendJson(ex, 200, Json.map("status", "ok",
        "note", "port changes take effect after restart"));
  }

  private void scan(HttpExchange ex) throws Exception
  {
    List<Map<String, Object>> files = ModelScanner.scan(cfg);
    Map<String, List<String>> usage = registry.fileUsage();
    for (Map<String, Object> f : files)
    {
      String path = Json.str(f, "path", "");
      List<String> used = usage.get(path);
      f.put("usedBy", used == null ? List.of() : used);
    }
    Router.sendJson(ex, 200, Json.map("files", files, "dirs", cfg.modelDirs()));
  }

  private void releases(HttpExchange ex) throws Exception
  {
    String engine = or(Router.query(ex, "engine"), "llama.cpp");
    String repo = Json.str(cfg.engine(engine), "repo", null);
    if (repo == null)
      throw new HttpError(400, "engine '" + engine + "' has no GitHub repo configured");
    int count = (int) parseLong(or(Router.query(ex, "count"), "10"), 10);
    Router.sendJson(ex, 200, Json.map("engine", engine, "repo", repo,
        "releases", EngineDownloader.releases(repo, count)));
  }

  private void install(HttpExchange ex) throws Exception
  {
    Map<String, Object> body = Router.readJson(ex);
    String engine = Json.str(body, "engine", "llama.cpp");
    String tag = Json.str(body, "tag", null);
    String asset = Json.str(body, "asset", null);
    String url = Json.str(body, "url", null);
    if (tag == null || asset == null || url == null)
      throw new HttpError(400, "required: engine, tag, asset, url");
    String id = EngineDownloader.install(cfg, engine, tag, asset, url);
    Router.sendJson(ex, 200, Json.map("status", "started", "id", id));
  }

  /** Per-installed-binary runtime library status: what it needs, what was found, what is missing. */
  private void libraries(HttpExchange ex) throws Exception
  {
    Map<String, Object> out = Json.map();
    for (String engine : cfg.engines().keySet())
    {
      // engines run from a manually installed binary have no version list of their own
      if (Engines.isManualBinary(cfg, engine))
      {
        Path binDir = cfg.resolve(Json.str(cfg.engine(engine), "binaryPath", "")).getParent();
        if (binDir != null)
        {
          LibraryResolver.Result r = LibraryResolver.resolve(cfg, engine, binDir);
          out.put(engine + "//", Json.map(
              "searchPaths", r.searchPaths().stream().map(Path::toString).toList(),
              "resolved", r.resolved(),
              "missing", r.missing(),
              "neededCount", r.needed().size(),
              "complete", r.complete()));
        }
        continue;
      }
      for (Map<String, Object> inst : cfg.installed(engine))
      {
        String version = Json.str(inst, "version", "");
        String variant = Json.str(inst, "variant", "");
        Path binDir = cfg.resolve(Json.str(inst, "binDir", Json.str(inst, "path", "")));
        LibraryResolver.Result r = LibraryResolver.resolve(cfg, engine, binDir);
        out.put(engine + "/" + version + "/" + variant, Json.map(
            "searchPaths", r.searchPaths().stream().map(Path::toString).toList(),
            "resolved", r.resolved(),
            "missing", r.missing(),
            "neededCount", r.needed().size(),
            "complete", r.complete()));
      }
    }
    Router.sendJson(ex, 200, out);
  }

  private void removeEngine(HttpExchange ex) throws Exception
  {
    String engine = Router.query(ex, "engine");
    String version = Router.query(ex, "version");
    String variant = Router.query(ex, "variant");
    if (engine == null || version == null || variant == null)
      throw new HttpError(400, "required query params: engine, version, variant");
    List<String> usedBy = Engines.usage(cfg).get(engine + "/" + version + "/" + variant);
    if (usedBy != null && !usedBy.isEmpty())
      throw new HttpError(409, "binary is used by: " + String.join(", ", usedBy)
          + " — repin or remove those models first");
    Path dir = cfg.home.resolve("binary").resolve(engine).resolve(version).resolve(variant).normalize();
    if (!dir.startsWith(cfg.home.resolve("binary")))
      throw new HttpError(400, "invalid path");
    if (Files.isDirectory(dir))
      deleteRecursively(dir);
    cfg.removeInstalled(engine, version, variant);
    Log.info("admin", "Removed engine binary " + engine + " " + version + " " + variant);
    Router.sendJson(ex, 200, Json.map("status", "removed"));
  }

  private void putModel(HttpExchange ex) throws Exception
  {
    Map<String, Object> body = Router.readJson(ex);
    String name = Json.str(body, "name", "").trim();
    if (name.isEmpty())
      throw new HttpError(400, "model needs a name");
    if (cfg.findProxy(name) != null)
      throw new HttpError(400, "a proxy with that name exists");
    if (Json.str(body, "file", "").isEmpty()
        && !Engines.isManualBinary(cfg, Json.str(body, "engine", "llama.cpp")))
      throw new HttpError(400, "model needs a file");
    im.stop(name); // restart with new settings on next request
    cfg.putModel(body);
    Log.info("admin", "Model '" + name + "' saved");
    Router.sendJson(ex, 200, Json.map("status", "ok"));
  }

  /** Config-only removal; files are never touched here. */
  private void deleteModel(HttpExchange ex) throws Exception
  {
    String name = Router.query(ex, "name");
    if (name == null)
      throw new HttpError(400, "required query param: name");
    Router.sendJson(ex, 200, registry.deleteModel(name));
  }

  /** Explicit file deletion: removes the file(s) and any configured models using them. */
  private void deleteFile(HttpExchange ex) throws Exception
  {
    String path = Router.query(ex, "path");
    if (path == null)
      throw new HttpError(400, "required query param: path");
    Router.sendJson(ex, 200, registry.deleteFile(path));
  }

  /** Start a model now rather than waiting for the first API request; returns immediately. */
  private void loadModel(HttpExchange ex) throws Exception
  {
    Map<String, Object> body = Router.readJson(ex);
    String name = Json.str(body, "name", null);
    if (name == null)
      throw new HttpError(400, "missing 'name'");
    Map<String, Object> model = cfg.findModel(name);
    if (model == null)
      throw new HttpError(404, "model '" + name + "' not found");
    im.loadAsync(model);
    Router.sendJson(ex, 200, Json.map("status", "loading", "name", name));
  }

  private void stopModel(HttpExchange ex) throws Exception
  {
    Map<String, Object> body = Router.readJson(ex);
    String name = Json.str(body, "name", null);
    if (name == null)
      throw new HttpError(400, "missing 'name'");
    im.clearLoadState(name);
    Router.sendJson(ex, 200, Json.map("stopped", im.stop(name)));
  }

  private void logLevel(HttpExchange ex) throws Exception
  {
    Map<String, Object> body = Router.readJson(ex);
    String level = Json.str(body, "level", "info");
    Log.setLevel(level);
    cfg.setLogLevel(Log.levelName());
    Log.info("admin", "Log level set to " + Log.levelName());
    Router.sendJson(ex, 200, Json.map("level", Log.levelName()));
  }

  private static void deleteRecursively(Path dir) throws Exception
  {
    try (Stream<Path> walk = Files.walk(dir))
    {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList())
        Files.deleteIfExists(p);
    }
  }

  private static String or(String v, String def)
  {
    return v == null || v.isEmpty() ? def : v;
  }

  private static long parseLong(String s, long def)
  {
    try
    {
      return Long.parseLong(s);
    }
    catch (NumberFormatException e)
    {
      return def;
    }
  }
}
