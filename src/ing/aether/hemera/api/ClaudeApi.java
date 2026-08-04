package ing.aether.hemera.api;

import java.io.OutputStream;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

import ing.aether.hemera.config.Config;
import ing.aether.hemera.engine.Instance;
import ing.aether.hemera.engine.InstanceManager;
import ing.aether.hemera.http.HttpError;
import ing.aether.hemera.http.Router;
import ing.aether.hemera.json.Json;

/**
 * Anthropic-compatible API: /v1/messages (+ count_tokens).
 * Lets Claude-dialect clients (e.g. Claude Code) talk to local models or proxies.
 */
public final class ClaudeApi
{
  private final Config cfg;
  private final InstanceManager im;
  private final ProxyDispatcher proxies;

  public ClaudeApi(Config cfg, InstanceManager im, ProxyDispatcher proxies, Router router)
  {
    this.cfg = cfg;
    this.im = im;
    this.proxies = proxies;

    router.add("POST", "/v1/messages", this::messages);
    router.add("POST", "/v1/messages/count_tokens", this::countTokens);
  }

  private void messages(HttpExchange ex) throws Exception
  {
    Map<String, Object> body = Router.readJson(ex);
    String raw = Json.str(body, "model", null);
    if (raw == null)
      throw new HttpError(400, "missing 'model'");
    String name = ProxyDispatcher.resolveName(cfg, raw);

    Map<String, Object> proxy = cfg.findProxy(name);
    if (proxy != null)
    {
      proxies.anthropicMessages(ex, proxy, body);
      return;
    }
    Map<String, Object> model = cfg.findModel(name);
    if (model == null)
      throw new HttpError(404, "model '" + raw + "' not found — configure it in the web UI (/configure/)");
    Instance inst = im.ensure(model);

    Map<String, Object> oBody = Dialects.anthropicToOpenai(body);
    String url = "http://127.0.0.1:" + inst.port + "/v1/chat/completions";
    if (Json.bool(body, "stream", false))
    {
      OutputStream os = Router.startStream(ex, "text/event-stream");
      Streams.pumpOpenAiSse(Upstream.postStream(url, oBody, null), new Streams.AnthropicSseSink(os, raw));
    }
    else
      Router.sendJson(ex, 200, Dialects.openaiRespToAnthropic(Upstream.postJson(url, oBody, null), raw));
    inst.touch();
  }

  /** Rough estimate (chars/4) — enough for clients that only need a ballpark. */
  private void countTokens(HttpExchange ex) throws Exception
  {
    Map<String, Object> body = Router.readJson(ex);
    long chars = Dialects.anthropicContentToText(body.get("system")).length();
    for (Map<String, Object> msg : Json.arrOfObj(body.get("messages")))
    {
      Object content = msg.get("content");
      if (content instanceof String s)
        chars += s.length();
      else
        chars += Dialects.anthropicContentToText(content).length();
    }
    Router.sendJson(ex, 200, Json.map("input_tokens", Math.max(1, chars / 4)));
  }
}
