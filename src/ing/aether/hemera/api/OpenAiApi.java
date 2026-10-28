package ing.aether.hemera.api;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

import ing.aether.hemera.comfy.ComfyBridge;
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
  private final ComfyBridge comfy;

  public OpenAiApi(Config cfg, InstanceManager im, ProxyDispatcher proxies, ComfyBridge comfy, Router router)
  {
    this.comfy = comfy;
    this.cfg = cfg;
    this.im = im;
    this.proxies = proxies;

    router.add("GET", "/v1/models", ex -> Router.sendJson(ex, 200, Json.map("object", "list", "data", models())));
    router.addPrefix("GET", "/v1/models/", this::model);
    router.add("POST", "/v1/chat/completions", this::chatCompletions);
    router.add("POST", "/v1/completions", ex -> passthrough(ex, "/v1/completions"));
    router.add("POST", "/v1/embeddings", ex -> passthrough(ex, "/v1/embeddings"));
  }

  /**
   * Every model hemera can serve. Beyond OpenAI's four fields each entry carries a "hemera"
   * object saying what the model is for — text, image, transcription, speech, video or 3d — which is all a client needs
   * to pick the endpoint; for the models ComfyUI offers it also lists their request parameters.
   */
  private List<Object> models()
  {
    List<Object> data = new ArrayList<>();
    long created = System.currentTimeMillis() / 1000;
    for (Map<String, Object> m : cfg.models())
    {
      boolean image = ing.aether.hemera.engine.Engines.isImageModel(cfg, m);
      boolean heard = ing.aether.hemera.engine.Engines.isTranscriptionModel(cfg, m);
      boolean spoken = ing.aether.hemera.engine.Engines.isSpeechModel(cfg, m);
      boolean speakers = ing.aether.hemera.engine.Engines.isDiarizationModel(cfg, m);
      boolean changer = ing.aether.hemera.engine.Engines.isVoiceChangerModel(cfg, m);
      data.add(Json.map("id", Json.str(m, "name", "?"), "object", "model",
          "created", created, "owned_by", "hemera",
          "hemera", Json.map("kind", image ? "image" : heard ? "transcription" : spoken ? "speech"
                  : speakers ? "diarization" : changer ? "voice-changer" : "text",
              "backend", Json.str(m, "engine", "llama.cpp"),
              "endpoint", image ? "/v1/images/generations" : heard ? "/v1/audio/transcriptions"
                  : spoken ? "/v1/audio/speech" : speakers ? "/v1/audio/transcriptions"
                  : changer ? "/v1/audio/voice-changer" : "/v1/chat/completions")));
    }
    for (Map<String, Object> p : cfg.proxies())
      data.add(Json.map("id", Json.str(p, "name", "?"), "object", "model",
          "created", created, "owned_by", "hemera-proxy",
          "hemera", Json.map("kind", "text", "backend", "proxy", "endpoint", "/v1/chat/completions")));
    for (ComfyBridge.Offer o : comfy.snapshot(false).offers())
      if (o.ready())
        data.add(Json.map("id", o.id(), "object", "model", "created", created, "owned_by", "comfyui",
            "hemera", ComfyBridge.describe(o)));
    return data;
  }

  private void model(HttpExchange ex) throws Exception
  {
    String id = ex.getRequestURI().getPath().substring("/v1/models/".length());
    for (Object m : models())
      if (id.equalsIgnoreCase(Json.str(Json.obj(m), "id", "")))
      {
        Router.sendJson(ex, 200, m);
        return;
      }
    throw new HttpError(404, "model '" + id + "' not found");
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
    if (model == null && comfy.find(raw) != null)
      throw new HttpError(400, "model '" + raw + "' is not a text model — use "
          + ComfyBridge.endpoint(comfy.find(raw).recipe().kind));
    if (model == null)
      throw new HttpError(404, "model '" + raw + "' not found — configure it in the web UI (/configure/)");
    ing.aether.hemera.engine.Engines.requireTextModel(cfg, model);
    return im.ensure(model);
  }
}
