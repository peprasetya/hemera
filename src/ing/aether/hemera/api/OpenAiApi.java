package ing.aether.hemera.api;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

import ing.aether.hemera.config.Config;
import ing.aether.hemera.engine.Instance;
import ing.aether.hemera.engine.InstanceManager;
import ing.aether.hemera.http.HttpError;
import ing.aether.hemera.http.Router;
import ing.aether.hemera.json.Json;

/** OpenAI-compatible API: /v1/models, /v1/chat/completions, /v1/completions, /v1/embeddings. */
public final class OpenAiApi
{
  private final Config cfg;
  private final InstanceManager im;
  private final ProxyDispatcher proxies;

  public OpenAiApi(Config cfg, InstanceManager im, ProxyDispatcher proxies, Router router)
  {
    this.cfg = cfg;
    this.im = im;
    this.proxies = proxies;

    router.add("GET", "/v1/models", this::models);
    router.add("POST", "/v1/chat/completions", this::chatCompletions);
    router.add("POST", "/v1/completions", ex -> passthrough(ex, "/v1/completions"));
    router.add("POST", "/v1/embeddings", ex -> passthrough(ex, "/v1/embeddings"));
  }

  private void models(HttpExchange ex) throws Exception
  {
    List<Object> data = new ArrayList<>();
    long created = System.currentTimeMillis() / 1000;
    for (Map<String, Object> m : cfg.models())
      data.add(Json.map("id", Json.str(m, "name", "?"), "object", "model",
          "created", created, "owned_by", "hemera"));
    for (Map<String, Object> p : cfg.proxies())
      data.add(Json.map("id", Json.str(p, "name", "?"), "object", "model",
          "created", created, "owned_by", "hemera-proxy"));
    Router.sendJson(ex, 200, Json.map("object", "list", "data", data));
  }

  private void chatCompletions(HttpExchange ex) throws Exception
  {
    Map<String, Object> body = Router.readJson(ex);
    String raw = Json.str(body, "model", null);
    if (raw == null)
      throw new HttpError(400, "missing 'model'");
    String name = ProxyDispatcher.resolveName(cfg, raw);

    Map<String, Object> proxy = cfg.findProxy(name);
    if (proxy != null)
    {
      proxies.openaiChat(ex, proxy, body);
      return;
    }
    Instance inst = ensureLocal(name, raw);
    String url = "http://127.0.0.1:" + inst.port + "/v1/chat/completions";
    if (Json.bool(body, "stream", false))
    {
      OutputStream os = Router.startStream(ex, "text/event-stream");
      Streams.relaySseWithModel(Upstream.postStream(url, body, null), os, raw);
    }
    else
    {
      Map<String, Object> resp = Upstream.postJson(url, body, null);
      Streams.applyModel(resp, raw);
      Router.sendJson(ex, 200, resp);
    }
    inst.touch();
  }

  private void passthrough(HttpExchange ex, String path) throws Exception
  {
    Map<String, Object> body = Router.readJson(ex);
    String raw = Json.str(body, "model", null);
    if (raw == null)
      throw new HttpError(400, "missing 'model'");
    String name = ProxyDispatcher.resolveName(cfg, raw);

    Map<String, Object> proxy = cfg.findProxy(name);
    if (proxy != null)
    {
      proxies.openaiPassthrough(ex, proxy, body, path.replace("/v1", ""));
      return;
    }
    Instance inst = ensureLocal(name, raw);
    String url = "http://127.0.0.1:" + inst.port + path;
    if (Json.bool(body, "stream", false))
    {
      OutputStream os = Router.startStream(ex, "text/event-stream");
      Streams.relaySseWithModel(Upstream.postStream(url, body, null), os, raw);
    }
    else
    {
      Map<String, Object> resp = Upstream.postJson(url, body, null);
      Streams.applyModel(resp, raw);
      Router.sendJson(ex, 200, resp);
    }
    inst.touch();
  }

  private Instance ensureLocal(String name, String raw) throws Exception
  {
    Map<String, Object> model = cfg.findModel(name);
    if (model == null)
      throw new HttpError(404, "model '" + raw + "' not found — configure it in the web UI (/configure/)");
    ing.aether.hemera.engine.Engines.requireTextModel(cfg, model);
    return im.ensure(model);
  }
}
