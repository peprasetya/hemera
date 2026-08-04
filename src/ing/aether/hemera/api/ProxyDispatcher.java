package ing.aether.hemera.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

import ing.aether.hemera.config.Config;
import ing.aether.hemera.http.HttpError;
import ing.aether.hemera.http.Router;
import ing.aether.hemera.json.Json;

/**
 * Forwards requests for proxy-configured models (deepseek, openai, gemini via its
 * OpenAI-compatible endpoint, anthropic, ...) to their upstream provider, translating
 * between dialects when the client and upstream speak different ones.
 */
public final class ProxyDispatcher
{
  private final Config cfg;

  public ProxyDispatcher(Config cfg)
  {
    this.cfg = cfg;
  }

  /** Resolve a requested model name against configured models/proxies (ollama ":latest" tolerance). */
  public static String resolveName(Config cfg, String raw)
  {
    if (raw == null)
      return null;
    if (cfg.findModel(raw) != null || cfg.findProxy(raw) != null)
      return raw;
    if (raw.endsWith(":latest"))
    {
      String base = raw.substring(0, raw.length() - 7);
      if (cfg.findModel(base) != null || cfg.findProxy(base) != null)
        return base;
    }
    String tagged = raw + ":latest";
    if (cfg.findModel(tagged) != null || cfg.findProxy(tagged) != null)
      return tagged;
    return raw;
  }

  private static String type(Map<String, Object> proxy)
  {
    return Json.str(proxy, "type", "openai");
  }

  private static String base(Map<String, Object> proxy)
  {
    String b = Json.str(proxy, "baseUrl", "").trim();
    while (b.endsWith("/"))
      b = b.substring(0, b.length() - 1);
    if (b.isEmpty())
      throw new HttpError(400, "proxy '" + Json.str(proxy, "name", "?") + "' has no baseUrl");
    return b;
  }

  private static Map<String, String> headers(Map<String, Object> proxy)
  {
    Map<String, String> h = new LinkedHashMap<>();
    String key = Json.str(proxy, "apiKey", "");
    if ("anthropic".equals(type(proxy)))
    {
      if (!key.isEmpty())
        h.put("x-api-key", key);
      h.put("anthropic-version", "2023-06-01");
    }
    else if (!key.isEmpty())
      h.put("Authorization", "Bearer " + key);
    return h;
  }

  private static String upstreamModel(Map<String, Object> proxy, Map<String, Object> body)
  {
    return Json.str(proxy, "upstreamModel", Json.str(body, "model", ""));
  }

  public static void relay(InputStream is, OutputStream os) throws IOException
  {
    byte[] buf = new byte[8192];
    int n;
    while ((n = is.read(buf)) > 0)
    {
      os.write(buf, 0, n);
      os.flush();
    }
  }

  // ---- OpenAI-dialect client (/v1/chat/completions) -----------------------

  public void openaiChat(HttpExchange ex, Map<String, Object> proxy, Map<String, Object> body) throws Exception
  {
    String requested = Json.str(body, "model", "");
    boolean stream = Json.bool(body, "stream", false);
    if ("anthropic".equals(type(proxy)))
    {
      Map<String, Object> aBody = Dialects.openaiToAnthropic(body);
      aBody.put("model", upstreamModel(proxy, body));
      aBody.put("stream", stream);
      String url = base(proxy) + "/v1/messages";
      if (stream)
      {
        OutputStream os = Router.startStream(ex, "text/event-stream");
        Streams.pumpAnthropicSse(Upstream.postStream(url, aBody, headers(proxy)), new Streams.OpenAiSseSink(os));
      }
      else
        Router.sendJson(ex, 200,
            Dialects.anthropicRespToOpenai(Upstream.postJson(url, aBody, headers(proxy)), requested));
    }
    else
    {
      body.put("model", upstreamModel(proxy, body));
      String url = base(proxy) + "/chat/completions";
      if (stream)
      {
        OutputStream os = Router.startStream(ex, "text/event-stream");
        relay(Upstream.postStream(url, body, headers(proxy)), os);
      }
      else
        Router.sendJson(ex, 200, Upstream.postJson(url, body, headers(proxy)));
    }
  }

  /** OpenAI-dialect passthrough for /v1/completions and /v1/embeddings (openai upstreams only). */
  public void openaiPassthrough(HttpExchange ex, Map<String, Object> proxy, Map<String, Object> body, String path)
      throws Exception
  {
    if ("anthropic".equals(type(proxy)))
      throw new HttpError(400, "endpoint not supported for anthropic upstream proxy");
    body.put("model", upstreamModel(proxy, body));
    String url = base(proxy) + path;
    if (Json.bool(body, "stream", false))
    {
      OutputStream os = Router.startStream(ex, "text/event-stream");
      relay(Upstream.postStream(url, body, headers(proxy)), os);
    }
    else
      Router.sendJson(ex, 200, Upstream.postJson(url, body, headers(proxy)));
  }

  // ---- Ollama-dialect client (/api/chat, /api/generate) -------------------

  public void ollamaChat(HttpExchange ex, Map<String, Object> proxy, Map<String, Object> body) throws Exception
  {
    String name = Json.str(body, "model", "");
    boolean stream = Json.bool(body, "stream", true);
    long startNs = System.nanoTime();
    Map<String, Object> oBody = Dialects.ollamaChatToOpenai(body);
    oBody.put("model", upstreamModel(proxy, body));
    if ("anthropic".equals(type(proxy)))
    {
      Map<String, Object> aBody = Dialects.openaiToAnthropic(oBody);
      aBody.put("model", upstreamModel(proxy, body));
      aBody.put("stream", stream);
      String url = base(proxy) + "/v1/messages";
      if (stream)
      {
        OutputStream os = Router.startStream(ex, "application/x-ndjson");
        Streams.pumpAnthropicSse(Upstream.postStream(url, aBody, headers(proxy)),
            new Streams.OllamaChatSink(os, name));
      }
      else
        Router.sendJson(ex, 200, Dialects.openaiRespToOllamaChat(
            Dialects.anthropicRespToOpenai(Upstream.postJson(url, aBody, headers(proxy)), name), name, startNs));
    }
    else
    {
      String url = base(proxy) + "/chat/completions";
      if (stream)
      {
        OutputStream os = Router.startStream(ex, "application/x-ndjson");
        Streams.pumpOpenAiSse(Upstream.postStream(url, oBody, headers(proxy)),
            new Streams.OllamaChatSink(os, name));
      }
      else
        Router.sendJson(ex, 200,
            Dialects.openaiRespToOllamaChat(Upstream.postJson(url, oBody, headers(proxy)), name, startNs));
    }
  }

  public void ollamaGenerate(HttpExchange ex, Map<String, Object> proxy, Map<String, Object> body) throws Exception
  {
    // remote providers have no raw-completion endpoint worth targeting: wrap the prompt as a chat
    Map<String, Object> chatBody = Json.map(
        "model", Json.str(body, "model", ""),
        "messages", List.of(Json.map("role", "user", "content", Json.str(body, "prompt", ""))),
        "stream", Json.bool(body, "stream", true),
        "options", Json.obj(body.get("options")));
    String name = Json.str(body, "model", "");
    boolean stream = Json.bool(body, "stream", true);
    long startNs = System.nanoTime();
    Map<String, Object> oBody = Dialects.ollamaChatToOpenai(chatBody);
    oBody.put("model", upstreamModel(proxy, body));
    if ("anthropic".equals(type(proxy)))
    {
      Map<String, Object> aBody = Dialects.openaiToAnthropic(oBody);
      aBody.put("model", upstreamModel(proxy, body));
      aBody.put("stream", stream);
      String url = base(proxy) + "/v1/messages";
      if (stream)
      {
        OutputStream os = Router.startStream(ex, "application/x-ndjson");
        Streams.pumpAnthropicSse(Upstream.postStream(url, aBody, headers(proxy)),
            new Streams.OllamaGenerateSink(os, name));
      }
      else
        Router.sendJson(ex, 200, Dialects.openaiRespToOllamaGenerate(
            Dialects.anthropicRespToOpenai(Upstream.postJson(url, aBody, headers(proxy)), name), name, startNs));
    }
    else
    {
      String url = base(proxy) + "/chat/completions";
      if (stream)
      {
        OutputStream os = Router.startStream(ex, "application/x-ndjson");
        Streams.pumpOpenAiSse(Upstream.postStream(url, oBody, headers(proxy)),
            new Streams.OllamaGenerateSink(os, name));
      }
      else
        Router.sendJson(ex, 200,
            Dialects.openaiRespToOllamaGenerate(Upstream.postJson(url, oBody, headers(proxy)), name, startNs));
    }
  }

  // ---- Anthropic-dialect client (/v1/messages) ----------------------------

  public void anthropicMessages(HttpExchange ex, Map<String, Object> proxy, Map<String, Object> body)
      throws Exception
  {
    String requested = Json.str(body, "model", "");
    boolean stream = Json.bool(body, "stream", false);
    if ("anthropic".equals(type(proxy)))
    {
      body.put("model", upstreamModel(proxy, body));
      String url = base(proxy) + "/v1/messages";
      if (stream)
      {
        OutputStream os = Router.startStream(ex, "text/event-stream");
        relay(Upstream.postStream(url, body, headers(proxy)), os);
      }
      else
        Router.sendJson(ex, 200, Upstream.postJson(url, body, headers(proxy)));
    }
    else
    {
      Map<String, Object> oBody = Dialects.anthropicToOpenai(body);
      oBody.put("model", upstreamModel(proxy, body));
      String url = base(proxy) + "/chat/completions";
      if (stream)
      {
        OutputStream os = Router.startStream(ex, "text/event-stream");
        Streams.pumpOpenAiSse(Upstream.postStream(url, oBody, headers(proxy)),
            new Streams.AnthropicSseSink(os, requested));
      }
      else
        Router.sendJson(ex, 200,
            Dialects.openaiRespToAnthropic(Upstream.postJson(url, oBody, headers(proxy)), requested));
    }
  }
}
