package ing.aether.hemera.api;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

import ing.aether.hemera.Hemera;
import ing.aether.hemera.config.Config;
import ing.aether.hemera.engine.Instance;
import ing.aether.hemera.engine.InstanceManager;
import ing.aether.hemera.http.HttpError;
import ing.aether.hemera.http.Router;
import ing.aether.hemera.json.Json;
import ing.aether.hemera.model.GgufReader;
import ing.aether.hemera.model.ModelRegistry;

/** Ollama-compatible API: /api/* — works with the ollama CLI (list, ps, run, stop, rm). */
public final class OllamaApi
{
  private final Config cfg;
  private final InstanceManager im;
  private final ModelRegistry registry;
  private final ProxyDispatcher proxies;

  public OllamaApi(Config cfg, InstanceManager im, ModelRegistry registry, ProxyDispatcher proxies, Router router)
  {
    this.cfg = cfg;
    this.im = im;
    this.registry = registry;
    this.proxies = proxies;

    router.add("GET", "/api/version", ex -> Router.sendJson(ex, 200, Json.map("version", Hemera.VERSION)));
    router.add("GET", "/api/tags", this::tags);
    router.add("GET", "/api/ps", this::ps);
    router.add("POST", "/api/show", this::show);
    router.add("POST", "/api/chat", this::chat);
    router.add("POST", "/api/generate", this::generate);
    router.add("POST", "/api/embed", ex -> embed(ex, true));
    router.add("POST", "/api/embeddings", ex -> embed(ex, false));
    router.add("POST", "/api/stop", this::stop);
    router.add("DELETE", "/api/delete", this::delete);
    router.add("POST", "/api/delete", this::delete);
    router.add("POST", "/api/pull", ex -> {
      throw new HttpError(501, "pull is not supported — use the hemera web UI (/configure/) to "
          + "download engines and configure models");
    });
    router.add("POST", "/api/create", ex -> {
      throw new HttpError(501, "create is not supported — configure models in the web UI (/configure/)");
    });
    router.add("POST", "/api/copy", ex -> {
      throw new HttpError(501, "copy is not supported");
    });
    router.add("POST", "/api/push", ex -> {
      throw new HttpError(501, "push is not supported");
    });
  }

  // ---- listings -----------------------------------------------------------

  private void tags(HttpExchange ex) throws Exception
  {
    List<Object> models = new ArrayList<>();
    for (Map<String, Object> m : cfg.models())
      models.add(tagEntry(m));
    for (Map<String, Object> p : cfg.proxies())
    {
      String name = Json.str(p, "name", "?");
      models.add(Json.map("name", name, "model", name,
          "modified_at", Dialects.now(), "size", 0, "digest", digest(name),
          "details", Json.map("parent_model", "", "format", "proxy",
              "family", Json.str(p, "type", "openai"), "families", List.of(Json.str(p, "type", "openai")),
              "parameter_size", "remote", "quantization_level", "-")));
    }
    Router.sendJson(ex, 200, Json.map("models", models));
  }

  private Map<String, Object> tagEntry(Map<String, Object> m)
  {
    String name = Json.str(m, "name", "?");
    String file = Config.expandUser(Json.str(m, "file", ""));
    long size = 0;
    String modified = Dialects.now();
    Map<String, Object> meta = Json.map();
    if (!file.isEmpty())
    {
      Path p = Path.of(file);
      try
      {
        size = Files.size(p);
        modified = Files.getLastModifiedTime(p).toInstant().toString();
        meta = GgufReader.read(p);
      }
      catch (Exception ignored)
      {
      }
    }
    String arch = Json.str(meta, "hemera.arch", "unknown");
    return Json.map("name", name, "model", name, "modified_at", modified, "size", size,
        "digest", digest(name),
        "details", Json.map("parent_model", "", "format", "gguf",
            "family", arch, "families", List.of(arch),
            "parameter_size", Json.str(meta, "hemera.params", "unknown"),
            "quantization_level", Json.str(meta, "hemera.quant", "unknown")));
  }

  private void ps(HttpExchange ex) throws Exception
  {
    List<Object> out = new ArrayList<>();
    for (Instance i : im.list())
    {
      Map<String, Object> m = cfg.findModel(i.name);
      long size = 0;
      if (m != null)
      {
        try
        {
          size = Files.size(Path.of(Config.expandUser(Json.str(m, "file", ""))));
        }
        catch (Exception ignored)
        {
        }
      }
      String expires = i.keepAliveSec < 0 ? "0001-01-01T00:00:00Z"
          : Instant.ofEpochMilli(i.lastUsed + i.keepAliveSec * 1000L).toString();
      out.add(Json.map("name", i.name, "model", i.name, "size", size, "digest", digest(i.name),
          "details", Json.map("parent_model", "", "format", "gguf", "family", "llama",
              "families", List.of("llama"), "parameter_size", "", "quantization_level", ""),
          "expires_at", expires, "size_vram", size));
    }
    Router.sendJson(ex, 200, Json.map("models", out));
  }

  private void show(HttpExchange ex) throws Exception
  {
    Map<String, Object> body = Router.readJson(ex);
    String name = ProxyDispatcher.resolveName(cfg, bodyModel(body));
    Map<String, Object> proxy = cfg.findProxy(name);
    if (proxy != null)
    {
      Router.sendJson(ex, 200, Json.map(
          "modelfile", "# hemera proxy to " + Json.str(proxy, "baseUrl", "") + "\n",
          "parameters", "", "template", "",
          "details", Json.map("parent_model", "", "format", "proxy",
              "family", Json.str(proxy, "type", "openai"), "families", List.of(),
              "parameter_size", "remote", "quantization_level", "-"),
          "model_info", Json.map(), "capabilities", List.of("completion", "tools")));
      return;
    }
    Map<String, Object> m = cfg.findModel(name);
    if (m == null)
      throw new HttpError(404, "model '" + name + "' not found");
    Map<String, Object> meta = Json.map();
    String file = Config.expandUser(Json.str(m, "file", ""));
    if (!file.isEmpty())
      meta = GgufReader.read(Path.of(file));
    String arch = Json.str(meta, "hemera.arch", "unknown");
    List<Object> caps = new ArrayList<>(List.of("completion", "tools"));
    if (!Json.str(m, "mmproj", "").isEmpty() || Json.bool(meta, "hemera.vision", false))
      caps.add("vision");
    if (Json.bool(meta, "hemera.audio", false))
      caps.add("audio");
    Router.sendJson(ex, 200, Json.map(
        "modelfile", "# managed by hemera\n# file: " + file + "\n",
        "parameters", String.join(" ", Json.strList(m, "args")),
        "template", "",
        "details", Json.map("parent_model", "", "format", "gguf",
            "family", arch, "families", List.of(arch),
            "parameter_size", Json.str(meta, "hemera.params", "unknown"),
            "quantization_level", Json.str(meta, "hemera.quant", "unknown")),
        "model_info", meta,
        "capabilities", caps));
  }

  // ---- inference ----------------------------------------------------------

  private void chat(HttpExchange ex) throws Exception
  {
    Map<String, Object> body = Router.readJson(ex);
    String raw = bodyModel(body);
    if (raw == null)
      throw new HttpError(400, "missing 'model'");
    String name = ProxyDispatcher.resolveName(cfg, raw);
    Long keepAlive = InstanceManager.parseKeepAlive(body.get("keep_alive"));
    List<Object> messages = Json.arr(body.get("messages"));

    Map<String, Object> proxy = cfg.findProxy(name);
    if (proxy != null)
    {
      if (messages.isEmpty())
      {
        Router.sendJson(ex, 200, loadResponse(raw, keepAlive != null && keepAlive == 0 ? "unload" : "load"));
        return;
      }
      proxies.ollamaChat(ex, proxy, body);
      return;
    }

    Map<String, Object> model = require(name, raw);
    if (keepAlive != null && keepAlive == 0 && messages.isEmpty())
    {
      im.stop(name);
      Router.sendJson(ex, 200, loadResponse(raw, "unload"));
      return;
    }
    Instance inst = im.ensure(model);
    if (keepAlive != null)
      inst.keepAliveSec = keepAlive;
    if (messages.isEmpty())
    {
      Router.sendJson(ex, 200, loadResponse(raw, "load"));
      return;
    }

    long startNs = System.nanoTime();
    Map<String, Object> oBody = Dialects.ollamaChatToOpenai(body);
    String url = "http://127.0.0.1:" + inst.port + "/v1/chat/completions";
    if (Json.bool(body, "stream", true))
    {
      OutputStream os = Router.startStream(ex, "application/x-ndjson");
      Streams.pumpOpenAiSse(Upstream.postStream(url, oBody, null), new Streams.OllamaChatSink(os, raw));
    }
    else
      Router.sendJson(ex, 200,
          Dialects.openaiRespToOllamaChat(Upstream.postJson(url, oBody, null), raw, startNs));
    inst.touch();
  }

  private void generate(HttpExchange ex) throws Exception
  {
    Map<String, Object> body = Router.readJson(ex);
    String raw = bodyModel(body);
    if (raw == null)
      throw new HttpError(400, "missing 'model'");
    String name = ProxyDispatcher.resolveName(cfg, raw);
    Long keepAlive = InstanceManager.parseKeepAlive(body.get("keep_alive"));
    String prompt = Json.str(body, "prompt", "");

    Map<String, Object> proxy = cfg.findProxy(name);
    if (proxy != null)
    {
      if (prompt.isEmpty())
      {
        Router.sendJson(ex, 200, genLoadResponse(raw, keepAlive != null && keepAlive == 0 ? "unload" : "load"));
        return;
      }
      proxies.ollamaGenerate(ex, proxy, body);
      return;
    }

    Map<String, Object> model = require(name, raw);
    if (keepAlive != null && keepAlive == 0 && prompt.isEmpty())
    {
      im.stop(name);
      Router.sendJson(ex, 200, genLoadResponse(raw, "unload"));
      return;
    }
    Instance inst = im.ensure(model);
    if (keepAlive != null)
      inst.keepAliveSec = keepAlive;
    if (prompt.isEmpty())
    {
      Router.sendJson(ex, 200, genLoadResponse(raw, "load"));
      return;
    }

    long startNs = System.nanoTime();
    Map<String, Object> oBody = Dialects.ollamaGenerateToOpenai(body);
    boolean multimodal = oBody.containsKey("messages");
    String url = "http://127.0.0.1:" + inst.port + (multimodal ? "/v1/chat/completions" : "/v1/completions");
    if (Json.bool(body, "stream", true))
    {
      OutputStream os = Router.startStream(ex, "application/x-ndjson");
      Streams.pumpOpenAiSse(Upstream.postStream(url, oBody, null), new Streams.OllamaGenerateSink(os, raw));
    }
    else
      Router.sendJson(ex, 200,
          Dialects.openaiRespToOllamaGenerate(Upstream.postJson(url, oBody, null), raw, startNs));
    inst.touch();
  }

  private void embed(HttpExchange ex, boolean modern) throws Exception
  {
    Map<String, Object> body = Router.readJson(ex);
    String raw = bodyModel(body);
    String name = ProxyDispatcher.resolveName(cfg, raw);
    Object input = modern ? body.get("input") : body.get("prompt");
    if (input == null)
      throw new HttpError(400, "missing input");

    Map<String, Object> oBody = Json.map("model", raw, "input", input);
    Map<String, Object> proxy = cfg.findProxy(name);
    Map<String, Object> resp;
    if (proxy != null)
    {
      oBody.put("model", Json.str(proxy, "upstreamModel", raw));
      String b = Json.str(proxy, "baseUrl", "");
      while (b.endsWith("/"))
        b = b.substring(0, b.length() - 1);
      Map<String, String> h = new java.util.LinkedHashMap<>();
      String key = Json.str(proxy, "apiKey", "");
      if (!key.isEmpty())
        h.put("Authorization", "Bearer " + key);
      resp = Upstream.postJson(b + "/embeddings", oBody, h);
    }
    else
    {
      Map<String, Object> model = require(name, raw);
      Instance inst = im.ensure(model);
      resp = Upstream.postJson("http://127.0.0.1:" + inst.port + "/v1/embeddings", oBody, null);
      inst.touch();
    }
    List<Object> embeddings = new ArrayList<>();
    for (Map<String, Object> d : Json.arrOfObj(resp.get("data")))
      embeddings.add(d.get("embedding"));
    if (modern)
      Router.sendJson(ex, 200, Json.map("model", raw, "embeddings", embeddings));
    else
      Router.sendJson(ex, 200, Json.map("embedding", embeddings.isEmpty() ? List.of() : embeddings.get(0)));
  }

  // ---- lifecycle ----------------------------------------------------------

  private void stop(HttpExchange ex) throws Exception
  {
    Map<String, Object> body = Router.readJson(ex);
    String name = ProxyDispatcher.resolveName(cfg, bodyModel(body));
    if (name == null)
      throw new HttpError(400, "missing 'model'");
    boolean stopped = im.stop(name);
    Router.sendJson(ex, 200, Json.map("status", "success",
        "message", stopped ? "model '" + name + "' stopped" : "model '" + name + "' was not running"));
  }

  /**
   * ollama rm: removes the configured model only. Unlike upstream ollama this never
   * deletes model files — they are shared, expensive to re-download, and often not
   * hemera's to remove. Deleting a file is an explicit action in the web UI.
   */
  private void delete(HttpExchange ex) throws Exception
  {
    Map<String, Object> body = Router.readJson(ex);
    String name = ProxyDispatcher.resolveName(cfg, bodyModel(body));
    if (name == null)
      throw new HttpError(400, "missing 'model'");
    if (cfg.findProxy(name) != null)
      throw new HttpError(400, "'" + name + "' is a proxy — remove it in the web UI (/configure/)");
    Map<String, Object> result = registry.deleteModel(name);
    result.put("status", "success");
    Router.sendJson(ex, 200, result);
  }

  // ---- helpers ------------------------------------------------------------

  /** The ollama CLI sends both "model" and "name", one of them empty — take the non-blank one. */
  private static String bodyModel(Map<String, Object> body)
  {
    String m = Json.str(body, "model", "");
    if (m.isBlank())
      m = Json.str(body, "name", "");
    return m.isBlank() ? null : m;
  }


  private Map<String, Object> require(String name, String raw)
  {
    Map<String, Object> model = cfg.findModel(name);
    if (model == null)
      throw new HttpError(404, "model '" + raw + "' not found — configure it in the web UI (/configure/)");
    return model;
  }

  private static Map<String, Object> loadResponse(String model, String reason)
  {
    return Json.map("model", model, "created_at", Dialects.now(),
        "message", Json.map("role", "assistant", "content", ""), "done", true, "done_reason", reason);
  }

  private static Map<String, Object> genLoadResponse(String model, String reason)
  {
    return Json.map("model", model, "created_at", Dialects.now(),
        "response", "", "done", true, "done_reason", reason);
  }

  /** Stable fake digest so ollama clients that slice digest strings don't break. */
  static String digest(String name)
  {
    try
    {
      byte[] h = MessageDigest.getInstance("SHA-256").digest(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : h)
        sb.append(String.format("%02x", b));
      return sb.toString();
    }
    catch (Exception e)
    {
      return "0000000000000000000000000000000000000000000000000000000000000000";
    }
  }
}
