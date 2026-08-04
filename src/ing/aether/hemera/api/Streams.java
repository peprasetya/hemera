package ing.aether.hemera.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import ing.aether.hemera.Log;
import ing.aether.hemera.http.Router;
import ing.aether.hemera.json.Json;

/**
 * Streaming plumbing. Sources produce OpenAI-style chunk maps (from an OpenAI SSE
 * stream or synthesized from an Anthropic SSE stream); sinks render them into the
 * client's dialect (Ollama NDJSON, OpenAI SSE, Anthropic SSE).
 */
public final class Streams
{
  public interface ChunkSink
  {
    void chunk(Map<String, Object> openaiChunk) throws IOException;

    void done() throws IOException;
  }

  // =========================================================================
  // Sources
  // =========================================================================

  /** Pump an OpenAI-style SSE stream ("data: {...}" ... "data: [DONE]") into a sink. */
  public static void pumpOpenAiSse(InputStream is, ChunkSink sink) throws IOException
  {
    try
    {
      Upstream.forEachSse(is, (event, data) -> {
        try
        {
          if ("[DONE]".equals(data))
            return;
          sink.chunk(Json.parseObj(data));
        }
        catch (IOException e)
        {
          throw new UncheckedIOException(e);
        }
        catch (IllegalArgumentException e)
        {
          Log.debug("stream", "unparseable chunk: " + Router.abbreviate(data, 300));
        }
      });
    }
    catch (UncheckedIOException e)
    {
      throw e.getCause();
    }
    sink.done();
  }

  /** Pump an Anthropic SSE stream, synthesizing OpenAI-style chunks into the sink. */
  public static void pumpAnthropicSse(InputStream is, ChunkSink sink) throws IOException
  {
    int[] toolIndex = { -1 };
    long[] inputTokens = { 0 };
    try
    {
      Upstream.forEachSse(is, (event, data) -> {
        try
        {
          Map<String, Object> ev = Json.parseObj(data);
          String type = Json.str(ev, "type", event == null ? "" : event);
          switch (type)
          {
            case "message_start" -> {
              Map<String, Object> usage = Json.obj(Json.obj(ev.get("message")).get("usage"));
              inputTokens[0] = Json.num(usage, "input_tokens", 0);
              sink.chunk(chunk(Json.map("role", "assistant"), null, null));
            }
            case "content_block_start" -> {
              Map<String, Object> block = Json.obj(ev.get("content_block"));
              if ("tool_use".equals(Json.str(block, "type", "")))
              {
                toolIndex[0]++;
                sink.chunk(chunk(Json.map("tool_calls", List.of(Json.map(
                    "index", toolIndex[0], "id", Json.str(block, "id", "call_" + toolIndex[0]),
                    "type", "function",
                    "function", Json.map("name", Json.str(block, "name", ""), "arguments", "")))),
                    null, null));
              }
            }
            case "content_block_delta" -> {
              Map<String, Object> delta = Json.obj(ev.get("delta"));
              String dt = Json.str(delta, "type", "");
              if ("text_delta".equals(dt))
                sink.chunk(chunk(Json.map("content", Json.str(delta, "text", "")), null, null));
              else if ("input_json_delta".equals(dt))
                sink.chunk(chunk(Json.map("tool_calls", List.of(Json.map(
                    "index", Math.max(toolIndex[0], 0),
                    "function", Json.map("arguments", Json.str(delta, "partial_json", ""))))),
                    null, null));
            }
            case "message_delta" -> {
              String stop = Json.str(Json.obj(ev.get("delta")), "stop_reason", null);
              Map<String, Object> usage = Json.obj(ev.get("usage"));
              sink.chunk(chunk(Json.map(), Dialects.finishFromAnthropicStop(stop), Json.map(
                  "prompt_tokens", inputTokens[0],
                  "completion_tokens", Json.num(usage, "output_tokens", 0))));
            }
            default -> {
            }
          }
        }
        catch (IOException e)
        {
          throw new UncheckedIOException(e);
        }
        catch (IllegalArgumentException ignored)
        {
        }
      });
    }
    catch (UncheckedIOException e)
    {
      throw e.getCause();
    }
    sink.done();
  }

  private static Map<String, Object> chunk(Map<String, Object> delta, String finishReason, Map<String, Object> usage)
  {
    Map<String, Object> c = Json.map("object", "chat.completion.chunk",
        "choices", List.of(Json.map("index", 0, "delta", delta, "finish_reason", finishReason)));
    if (usage != null)
      c.put("usage", usage);
    return c;
  }

  /**
   * Relay an SSE stream unchanged except for the model identifier, which is rewritten to the
   * name the client asked for. Engines report the file path they were launched with, and
   * upstream providers report their own ids; neither is meaningful to the caller.
   */
  public static void relaySseWithModel(InputStream is, OutputStream os, String model) throws IOException
  {
    try
    {
      Upstream.forEachSse(is, (event, data) -> {
        try
        {
          if (data.isEmpty() || data.startsWith("["))
          {
            Router.sse(os, event, data); // [DONE] and other sentinels pass straight through
            return;
          }
          Map<String, Object> chunk = Json.parseObj(data);
          applyModel(chunk, model);
          Router.sse(os, event, chunk);
        }
        catch (IOException e)
        {
          throw new UncheckedIOException(e);
        }
        catch (IllegalArgumentException e)
        {
          try
          {
            Router.sse(os, event, data); // not JSON: forward verbatim
          }
          catch (IOException io)
          {
            throw new UncheckedIOException(io);
          }
        }
      });
    }
    catch (UncheckedIOException e)
    {
      throw e.getCause();
    }
  }

  /**
   * Replace the model identifier in a response body with the configured name.
   * Covers the OpenAI shape ({@code model}) and the Anthropic streaming shape
   * ({@code message.model} inside message_start).
   */
  @SuppressWarnings("unchecked")
  public static void applyModel(Map<String, Object> body, String model)
  {
    if (body == null || model == null)
      return;
    if (body.containsKey("model"))
      body.put("model", model);
    if (body.get("message") instanceof Map<?, ?> msg && ((Map<String, Object>) msg).containsKey("model"))
      ((Map<String, Object>) msg).put("model", model);
  }

  // =========================================================================
  // Sinks
  // =========================================================================

  /** Renders OpenAI chunks back out as OpenAI SSE (for proxied cross-dialect streams). */
  public static final class OpenAiSseSink implements ChunkSink
  {
    private final OutputStream os;
    private final String model;

    public OpenAiSseSink(OutputStream os, String model)
    {
      this.os = os;
      this.model = model;
    }

    @Override
    public void chunk(Map<String, Object> c) throws IOException
    {
      // chunks synthesized from another dialect carry no identifier of their own
      if (model != null)
        c.put("model", model);
      Router.sse(os, null, c);
    }

    @Override
    public void done() throws IOException
    {
      Router.sse(os, null, "[DONE]");
    }
  }

  /** Base for the two Ollama NDJSON sinks: accumulates tool calls, usage, finish reason. */
  private abstract static class OllamaSink implements ChunkSink
  {
    final OutputStream os;
    final String model;
    final long startNs = System.nanoTime();
    String finishReason = "stop";
    Map<String, Object> usage = Json.map();
    final TreeMap<Integer, Map<String, Object>> tools = new TreeMap<>();

    OllamaSink(OutputStream os, String model)
    {
      this.os = os;
      this.model = model;
    }

    @Override
    public void chunk(Map<String, Object> c) throws IOException
    {
      if (c.get("usage") instanceof Map)
        usage = Json.obj(c.get("usage"));
      Map<String, Object> choice = Dialects.firstChoice(c);
      String fr = Json.str(choice, "finish_reason", null);
      if (fr != null)
        finishReason = fr;
      Map<String, Object> delta = Json.obj(choice.get("delta"));
      String content = Json.str(delta, "content", "");
      if (!content.isEmpty())
        content(content);
      String reasoning = Json.str(delta, "reasoning_content", "");
      if (!reasoning.isEmpty())
        thinking(reasoning);
      for (Map<String, Object> tc : Json.arrOfObj(delta.get("tool_calls")))
      {
        int idx = (int) Json.num(tc, "index", 0);
        Map<String, Object> acc = tools.computeIfAbsent(idx,
            k -> Json.map("id", "", "name", "", "args", new StringBuilder()));
        if (!Json.str(tc, "id", "").isEmpty())
          acc.put("id", Json.str(tc, "id", ""));
        Map<String, Object> fn = Json.obj(tc.get("function"));
        if (!Json.str(fn, "name", "").isEmpty())
          acc.put("name", Json.str(fn, "name", ""));
        ((StringBuilder) acc.get("args")).append(Json.str(fn, "arguments", ""));
      }
    }

    abstract void content(String text) throws IOException;

    void thinking(String text) throws IOException
    {
      // sinks that support ollama's thinking field override this
    }

    List<Object> finishedToolCalls()
    {
      List<Object> out = new ArrayList<>();
      for (Map<String, Object> acc : tools.values())
      {
        String args = acc.get("args").toString();
        Object parsed;
        try
        {
          parsed = Json.parse(args.isBlank() ? "{}" : args);
        }
        catch (Exception e)
        {
          parsed = Json.map("_raw", args);
        }
        out.add(Json.map("function", Json.map("name", acc.get("name"), "arguments", parsed)));
      }
      return out;
    }
  }

  /** Renders OpenAI chunks as Ollama /api/chat NDJSON. */
  public static final class OllamaChatSink extends OllamaSink
  {
    public OllamaChatSink(OutputStream os, String model)
    {
      super(os, model);
    }

    @Override
    void content(String text) throws IOException
    {
      Router.ndjson(os, Json.map("model", model, "created_at", Dialects.now(),
          "message", Json.map("role", "assistant", "content", text), "done", false));
    }

    @Override
    void thinking(String text) throws IOException
    {
      Router.ndjson(os, Json.map("model", model, "created_at", Dialects.now(),
          "message", Json.map("role", "assistant", "content", "", "thinking", text), "done", false));
    }

    @Override
    public void done() throws IOException
    {
      if (!tools.isEmpty())
        Router.ndjson(os, Json.map("model", model, "created_at", Dialects.now(),
            "message", Json.map("role", "assistant", "content", "", "tool_calls", finishedToolCalls()),
            "done", false));
      Map<String, Object> fin = Json.map("model", model, "created_at", Dialects.now(),
          "message", Json.map("role", "assistant", "content", ""),
          "done", true, "done_reason", Dialects.doneReason(finishReason));
      Dialects.putOllamaStats(fin, usage, startNs);
      Router.ndjson(os, fin);
    }
  }

  /** Renders OpenAI chunks as Ollama /api/generate NDJSON. */
  public static final class OllamaGenerateSink extends OllamaSink
  {
    public OllamaGenerateSink(OutputStream os, String model)
    {
      super(os, model);
    }

    @Override
    public void chunk(Map<String, Object> c) throws IOException
    {
      // completions chunks carry "text" instead of delta.content
      Map<String, Object> choice = Dialects.firstChoice(c);
      String text = Json.str(choice, "text", "");
      if (!text.isEmpty())
        content(text);
      else
        super.chunk(c);
    }

    @Override
    void content(String text) throws IOException
    {
      Router.ndjson(os, Json.map("model", model, "created_at", Dialects.now(),
          "response", text, "done", false));
    }

    @Override
    public void done() throws IOException
    {
      Map<String, Object> fin = Json.map("model", model, "created_at", Dialects.now(),
          "response", "", "done", true, "done_reason", Dialects.doneReason(finishReason));
      Dialects.putOllamaStats(fin, usage, startNs);
      Router.ndjson(os, fin);
    }
  }

  /** Renders OpenAI chunks as an Anthropic /v1/messages SSE event stream. */
  public static final class AnthropicSseSink implements ChunkSink
  {
    private final OutputStream os;
    private final String model;
    private int blockIndex = -1;
    private String blockType = null; // "text" | "tool_use"
    private final Map<Integer, Integer> toolToBlock = new LinkedHashMap<>();
    private String finishReason = "stop";
    private Map<String, Object> usage = Json.map();
    private boolean started = false;

    public AnthropicSseSink(OutputStream os, String model)
    {
      this.os = os;
      this.model = model;
    }

    private void ensureStarted() throws IOException
    {
      if (started)
        return;
      started = true;
      Router.sse(os, "message_start", Json.map("type", "message_start", "message", Json.map(
          "id", "msg_" + Long.toHexString(System.nanoTime()),
          "type", "message", "role", "assistant", "model", model,
          "content", List.of(), "stop_reason", null, "stop_sequence", null,
          "usage", Json.map("input_tokens", 0, "output_tokens", 0))));
      Router.sse(os, "ping", Json.map("type", "ping"));
    }

    private void closeBlock() throws IOException
    {
      if (blockType != null)
      {
        Router.sse(os, "content_block_stop", Json.map("type", "content_block_stop", "index", blockIndex));
        blockType = null;
      }
    }

    @Override
    public void chunk(Map<String, Object> c) throws IOException
    {
      ensureStarted();
      if (c.get("usage") instanceof Map)
        usage = Json.obj(c.get("usage"));
      Map<String, Object> choice = Dialects.firstChoice(c);
      String fr = Json.str(choice, "finish_reason", null);
      if (fr != null)
        finishReason = fr;
      Map<String, Object> delta = Json.obj(choice.get("delta"));
      String content = Json.str(delta, "content", "");
      if (!content.isEmpty())
      {
        if (!"text".equals(blockType))
        {
          closeBlock();
          blockIndex++;
          blockType = "text";
          Router.sse(os, "content_block_start", Json.map("type", "content_block_start",
              "index", blockIndex, "content_block", Json.map("type", "text", "text", "")));
        }
        Router.sse(os, "content_block_delta", Json.map("type", "content_block_delta",
            "index", blockIndex, "delta", Json.map("type", "text_delta", "text", content)));
      }
      for (Map<String, Object> tc : Json.arrOfObj(delta.get("tool_calls")))
      {
        int idx = (int) Json.num(tc, "index", 0);
        Map<String, Object> fn = Json.obj(tc.get("function"));
        String name = Json.str(fn, "name", "");
        if (!toolToBlock.containsKey(idx))
        {
          closeBlock();
          blockIndex++;
          blockType = "tool_use";
          toolToBlock.put(idx, blockIndex);
          Router.sse(os, "content_block_start", Json.map("type", "content_block_start",
              "index", blockIndex, "content_block", Json.map("type", "tool_use",
                  "id", Json.str(tc, "id", "toolu_" + idx), "name", name, "input", Json.map())));
        }
        String args = Json.str(fn, "arguments", "");
        if (!args.isEmpty())
          Router.sse(os, "content_block_delta", Json.map("type", "content_block_delta",
              "index", toolToBlock.get(idx),
              "delta", Json.map("type", "input_json_delta", "partial_json", args)));
      }
    }

    @Override
    public void done() throws IOException
    {
      ensureStarted();
      closeBlock();
      Router.sse(os, "message_delta", Json.map("type", "message_delta",
          "delta", Json.map("stop_reason", Dialects.anthropicStopReason(finishReason), "stop_sequence", null),
          "usage", Json.map("output_tokens", Json.num(usage, "completion_tokens", 0))));
      Router.sse(os, "message_stop", Json.map("type", "message_stop"));
    }
  }
}
