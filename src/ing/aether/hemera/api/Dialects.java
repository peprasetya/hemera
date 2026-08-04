package ing.aether.hemera.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ing.aether.hemera.json.Json;

/**
 * Request/response translation between the three dialects (Ollama, OpenAI, Anthropic).
 * OpenAI is the pivot format: everything converts to/from it, because that is what
 * llama-server natively speaks.
 */
public final class Dialects
{
  public static String now()
  {
    return Instant.now().toString();
  }

  // =========================================================================
  // Ollama -> OpenAI (requests)
  // =========================================================================

  public static Map<String, Object> ollamaChatToOpenai(Map<String, Object> body)
  {
    Map<String, Object> out = Json.map();
    out.put("model", Json.str(body, "model", ""));
    List<Object> messages = new ArrayList<>();
    for (Map<String, Object> msg : Json.arrOfObj(body.get("messages")))
    {
      String role = Json.str(msg, "role", "user");
      String content = Json.str(msg, "content", "");
      List<Object> images = Json.arr(msg.get("images"));
      List<Map<String, Object>> toolCalls = Json.arrOfObj(msg.get("tool_calls"));

      if ("tool".equals(role))
      {
        messages.add(Json.map("role", "tool",
            "tool_call_id", Json.str(msg, "tool_call_id", Json.str(msg, "tool_name", "call_0")),
            "content", content));
        continue;
      }
      Map<String, Object> m = Json.map("role", role);
      if (!images.isEmpty())
      {
        List<Object> parts = new ArrayList<>();
        if (!content.isEmpty())
          parts.add(Json.map("type", "text", "text", content));
        for (Object img : images)
          parts.add(Json.map("type", "image_url",
              "image_url", Json.map("url", "data:image/jpeg;base64," + img)));
        m.put("content", parts);
      }
      else
        m.put("content", content);
      if (!toolCalls.isEmpty())
      {
        List<Object> tcs = new ArrayList<>();
        int i = 0;
        for (Map<String, Object> tc : toolCalls)
        {
          Map<String, Object> fn = Json.obj(tc.get("function"));
          Object args = fn.get("arguments");
          tcs.add(Json.map("id", Json.str(tc, "id", "call_" + i++), "type", "function",
              "function", Json.map("name", Json.str(fn, "name", ""),
                  "arguments", args instanceof String s ? s : Json.write(args))));
        }
        m.put("tool_calls", tcs);
      }
      messages.add(m);
    }
    out.put("messages", messages);

    boolean stream = Json.bool(body, "stream", true);
    out.put("stream", stream);
    if (stream)
      out.put("stream_options", Json.map("include_usage", true));
    Map<String, Object> options = Json.obj(body.get("options"));
    copyOption(options, "temperature", out, "temperature");
    copyOption(options, "top_p", out, "top_p");
    copyOption(options, "num_predict", out, "max_tokens");
    copyOption(options, "seed", out, "seed");
    if (options.get("stop") != null)
      out.put("stop", options.get("stop"));
    if (body.get("tools") != null)
      out.put("tools", body.get("tools"));
    if (body.get("format") != null && "json".equals(body.get("format")))
      out.put("response_format", Json.map("type", "json_object"));
    return out;
  }

  public static Map<String, Object> ollamaGenerateToOpenai(Map<String, Object> body)
  {
    Map<String, Object> out = Json.map();
    out.put("model", Json.str(body, "model", ""));
    out.put("prompt", Json.str(body, "prompt", ""));
    boolean stream = Json.bool(body, "stream", true);
    out.put("stream", stream);
    if (stream)
      out.put("stream_options", Json.map("include_usage", true));
    Map<String, Object> options = Json.obj(body.get("options"));
    copyOption(options, "temperature", out, "temperature");
    copyOption(options, "top_p", out, "top_p");
    copyOption(options, "num_predict", out, "max_tokens");
    copyOption(options, "seed", out, "seed");
    if (options.get("stop") != null)
      out.put("stop", options.get("stop"));
    return out;
  }

  private static void copyOption(Map<String, Object> from, String key, Map<String, Object> to, String toKey)
  {
    if (from.get(key) instanceof Number n)
      to.put(toKey, n);
  }

  // =========================================================================
  // OpenAI -> Ollama (non-streaming responses)
  // =========================================================================

  public static Map<String, Object> openaiRespToOllamaChat(Map<String, Object> resp, String model, long startNs)
  {
    Map<String, Object> choice = firstChoice(resp);
    Map<String, Object> message = Json.obj(choice.get("message"));
    Map<String, Object> msg = Json.map("role", "assistant", "content", Json.str(message, "content", ""));
    String reasoning = Json.str(message, "reasoning_content", "");
    if (!reasoning.isEmpty())
      msg.put("thinking", reasoning);
    List<Map<String, Object>> tcs = Json.arrOfObj(message.get("tool_calls"));
    if (!tcs.isEmpty())
      msg.put("tool_calls", openaiToolCallsToOllama(tcs));
    Map<String, Object> out = Json.map("model", model, "created_at", now(), "message", msg,
        "done", true, "done_reason", doneReason(Json.str(choice, "finish_reason", "stop")));
    putOllamaStats(out, Json.obj(resp.get("usage")), startNs);
    return out;
  }

  public static Map<String, Object> openaiRespToOllamaGenerate(Map<String, Object> resp, String model, long startNs)
  {
    Map<String, Object> choice = firstChoice(resp);
    String text = Json.str(choice, "text", "");
    if (text.isEmpty())
      text = Json.str(Json.obj(choice.get("message")), "content", "");
    Map<String, Object> out = Json.map("model", model, "created_at", now(), "response", text,
        "done", true, "done_reason", doneReason(Json.str(choice, "finish_reason", "stop")));
    putOllamaStats(out, Json.obj(resp.get("usage")), startNs);
    return out;
  }

  public static List<Object> openaiToolCallsToOllama(List<Map<String, Object>> tcs)
  {
    List<Object> out = new ArrayList<>();
    for (Map<String, Object> tc : tcs)
    {
      Map<String, Object> fn = Json.obj(tc.get("function"));
      Object args = fn.get("arguments");
      Object parsed = args;
      if (args instanceof String s)
        try
        {
          parsed = Json.parse(s.isBlank() ? "{}" : s);
        }
        catch (Exception e)
        {
          parsed = Json.map("_raw", s);
        }
      out.add(Json.map("function", Json.map("name", Json.str(fn, "name", ""), "arguments", parsed)));
    }
    return out;
  }

  public static void putOllamaStats(Map<String, Object> out, Map<String, Object> usage, long startNs)
  {
    long durNs = System.nanoTime() - startNs;
    out.put("total_duration", durNs);
    out.put("load_duration", 0L);
    out.put("prompt_eval_count", Json.num(usage, "prompt_tokens", 0));
    out.put("prompt_eval_duration", 0L);
    out.put("eval_count", Json.num(usage, "completion_tokens", 0));
    out.put("eval_duration", durNs);
  }

  public static String doneReason(String finishReason)
  {
    return switch (finishReason == null ? "stop" : finishReason)
    {
      case "length" -> "length";
      case "tool_calls" -> "tool_calls";
      default -> "stop";
    };
  }

  public static Map<String, Object> firstChoice(Map<String, Object> resp)
  {
    List<Map<String, Object>> choices = Json.arrOfObj(resp.get("choices"));
    return choices.isEmpty() ? Json.map() : choices.get(0);
  }

  // =========================================================================
  // Anthropic -> OpenAI (requests)
  // =========================================================================

  @SuppressWarnings("unchecked")
  public static Map<String, Object> anthropicToOpenai(Map<String, Object> body)
  {
    Map<String, Object> out = Json.map();
    out.put("model", Json.str(body, "model", ""));
    List<Object> messages = new ArrayList<>();

    Object system = body.get("system");
    String systemText = anthropicContentToText(system);
    if (!systemText.isEmpty())
      messages.add(Json.map("role", "system", "content", systemText));

    for (Map<String, Object> msg : Json.arrOfObj(body.get("messages")))
    {
      String role = Json.str(msg, "role", "user");
      Object content = msg.get("content");
      if (content instanceof String s)
      {
        messages.add(Json.map("role", role, "content", s));
        continue;
      }
      List<Object> parts = new ArrayList<>();     // text/image parts for this message
      List<Object> toolCalls = new ArrayList<>(); // assistant tool_use blocks
      StringBuilder text = new StringBuilder();
      for (Map<String, Object> block : Json.arrOfObj(content))
      {
        String type = Json.str(block, "type", "");
        switch (type)
        {
          case "text" -> {
            text.append(Json.str(block, "text", ""));
            parts.add(Json.map("type", "text", "text", Json.str(block, "text", "")));
          }
          case "image" -> {
            Map<String, Object> source = Json.obj(block.get("source"));
            String mediaType = Json.str(source, "media_type", "image/jpeg");
            String data = Json.str(source, "data", "");
            parts.add(Json.map("type", "image_url",
                "image_url", Json.map("url", "data:" + mediaType + ";base64," + data)));
          }
          case "tool_use" -> toolCalls.add(Json.map(
              "id", Json.str(block, "id", "call_0"), "type", "function",
              "function", Json.map("name", Json.str(block, "name", ""),
                  "arguments", Json.write(Json.obj(block.get("input"))))));
          case "tool_result" -> messages.add(Json.map("role", "tool",
              "tool_call_id", Json.str(block, "tool_use_id", "call_0"),
              "content", anthropicContentToText(block.get("content"))));
          default -> {
            // thinking / document / unsupported block types: fold any text in
            if (block.get("text") instanceof String t)
              text.append(t);
          }
        }
      }
      boolean hasImages = parts.stream().anyMatch(
          p -> p instanceof Map && "image_url".equals(((Map<String, Object>) p).get("type")));
      if ("assistant".equals(role))
      {
        Map<String, Object> m = Json.map("role", "assistant", "content", text.toString());
        if (!toolCalls.isEmpty())
          m.put("tool_calls", toolCalls);
        if (!text.isEmpty() || !toolCalls.isEmpty())
          messages.add(m);
      }
      else if (hasImages)
        messages.add(Json.map("role", role, "content", parts));
      else if (!text.isEmpty())
        messages.add(Json.map("role", role, "content", text.toString()));
    }
    out.put("messages", messages);

    if (body.get("max_tokens") instanceof Number n)
      out.put("max_tokens", n);
    if (body.get("temperature") instanceof Number n)
      out.put("temperature", n);
    if (body.get("top_p") instanceof Number n)
      out.put("top_p", n);
    if (body.get("stop_sequences") != null)
      out.put("stop", body.get("stop_sequences"));
    boolean stream = Json.bool(body, "stream", false);
    out.put("stream", stream);
    if (stream)
      out.put("stream_options", Json.map("include_usage", true));

    List<Map<String, Object>> tools = Json.arrOfObj(body.get("tools"));
    if (!tools.isEmpty())
    {
      List<Object> outTools = new ArrayList<>();
      for (Map<String, Object> t : tools)
      {
        if (t.get("input_schema") == null)
          continue; // skip anthropic server-side tools (web_search etc.)
        outTools.add(Json.map("type", "function", "function", Json.map(
            "name", Json.str(t, "name", ""),
            "description", Json.str(t, "description", ""),
            "parameters", t.get("input_schema"))));
      }
      if (!outTools.isEmpty())
        out.put("tools", outTools);
    }
    Map<String, Object> toolChoice = Json.obj(body.get("tool_choice"));
    switch (Json.str(toolChoice, "type", ""))
    {
      case "auto" -> out.put("tool_choice", "auto");
      case "any" -> out.put("tool_choice", "required");
      case "tool" -> out.put("tool_choice", Json.map("type", "function",
          "function", Json.map("name", Json.str(toolChoice, "name", ""))));
      case "none" -> out.put("tool_choice", "none");
      default -> {
      }
    }
    return out;
  }

  /** Anthropic content (string or list of blocks) -> plain text. */
  public static String anthropicContentToText(Object content)
  {
    if (content instanceof String s)
      return s;
    StringBuilder sb = new StringBuilder();
    for (Map<String, Object> block : Json.arrOfObj(content))
      if ("text".equals(Json.str(block, "type", "")))
        sb.append(Json.str(block, "text", ""));
    return sb.toString();
  }

  // =========================================================================
  // OpenAI -> Anthropic (responses)
  // =========================================================================

  public static Map<String, Object> openaiRespToAnthropic(Map<String, Object> resp, String model)
  {
    Map<String, Object> choice = firstChoice(resp);
    Map<String, Object> message = Json.obj(choice.get("message"));
    List<Object> content = new ArrayList<>();
    String text = Json.str(message, "content", "");
    if (!text.isEmpty())
      content.add(Json.map("type", "text", "text", text));
    for (Map<String, Object> tc : Json.arrOfObj(message.get("tool_calls")))
    {
      Map<String, Object> fn = Json.obj(tc.get("function"));
      Object input;
      try
      {
        String args = Json.str(fn, "arguments", "{}");
        input = Json.parse(args.isBlank() ? "{}" : args);
      }
      catch (Exception e)
      {
        input = Json.map();
      }
      content.add(Json.map("type", "tool_use", "id", Json.str(tc, "id", "toolu_0"),
          "name", Json.str(fn, "name", ""), "input", input));
    }
    Map<String, Object> usage = Json.obj(resp.get("usage"));
    return Json.map(
        "id", "msg_" + Long.toHexString(System.nanoTime()),
        "type", "message",
        "role", "assistant",
        "model", model,
        "content", content,
        "stop_reason", anthropicStopReason(Json.str(choice, "finish_reason", "stop")),
        "stop_sequence", null,
        "usage", Json.map(
            "input_tokens", Json.num(usage, "prompt_tokens", 0),
            "output_tokens", Json.num(usage, "completion_tokens", 0)));
  }

  public static String anthropicStopReason(String finishReason)
  {
    return switch (finishReason == null ? "stop" : finishReason)
    {
      case "length" -> "max_tokens";
      case "tool_calls" -> "tool_use";
      default -> "end_turn";
    };
  }

  public static String finishFromAnthropicStop(String stopReason)
  {
    return switch (stopReason == null ? "end_turn" : stopReason)
    {
      case "max_tokens" -> "length";
      case "tool_use" -> "tool_calls";
      default -> "stop";
    };
  }

  // =========================================================================
  // OpenAI -> Anthropic (requests, for anthropic upstream proxies)
  // =========================================================================

  public static Map<String, Object> openaiToAnthropic(Map<String, Object> body)
  {
    Map<String, Object> out = Json.map();
    out.put("model", Json.str(body, "model", ""));
    StringBuilder system = new StringBuilder();
    List<Object> messages = new ArrayList<>();
    for (Map<String, Object> msg : Json.arrOfObj(body.get("messages")))
    {
      String role = Json.str(msg, "role", "user");
      Object content = msg.get("content");
      switch (role)
      {
        case "system", "developer" -> system.append(openaiContentToText(content)).append('\n');
        case "tool" -> messages.add(Json.map("role", "user", "content", List.of(Json.map(
            "type", "tool_result",
            "tool_use_id", Json.str(msg, "tool_call_id", "call_0"),
            "content", openaiContentToText(content)))));
        case "assistant" -> {
          List<Object> blocks = new ArrayList<>();
          String text = openaiContentToText(content);
          if (!text.isEmpty())
            blocks.add(Json.map("type", "text", "text", text));
          for (Map<String, Object> tc : Json.arrOfObj(msg.get("tool_calls")))
          {
            Map<String, Object> fn = Json.obj(tc.get("function"));
            Object input;
            try
            {
              String args = Json.str(fn, "arguments", "{}");
              input = Json.parse(args.isBlank() ? "{}" : args);
            }
            catch (Exception e)
            {
              input = Json.map();
            }
            blocks.add(Json.map("type", "tool_use", "id", Json.str(tc, "id", "call_0"),
                "name", Json.str(fn, "name", ""), "input", input));
          }
          if (!blocks.isEmpty())
            messages.add(Json.map("role", "assistant", "content", blocks));
        }
        default -> {
          List<Object> blocks = new ArrayList<>();
          if (content instanceof List)
          {
            for (Map<String, Object> part : Json.arrOfObj(content))
            {
              String type = Json.str(part, "type", "");
              if ("text".equals(type))
                blocks.add(Json.map("type", "text", "text", Json.str(part, "text", "")));
              else if ("image_url".equals(type))
              {
                String url = Json.str(Json.obj(part.get("image_url")), "url", "");
                Map<String, Object> src = dataUriToAnthropicSource(url);
                if (src != null)
                  blocks.add(Json.map("type", "image", "source", src));
              }
            }
          }
          else
            blocks.add(Json.map("type", "text", "text", openaiContentToText(content)));
          messages.add(Json.map("role", "user", "content", blocks));
        }
      }
    }
    if (!system.isEmpty())
      out.put("system", system.toString().trim());
    out.put("messages", messages);
    out.put("max_tokens", Json.num(body, "max_tokens", 4096));
    if (body.get("temperature") instanceof Number n)
      out.put("temperature", n);
    if (body.get("top_p") instanceof Number n)
      out.put("top_p", n);
    if (body.get("stop") != null)
      out.put("stop_sequences", body.get("stop") instanceof List ? body.get("stop") : List.of(body.get("stop")));
    out.put("stream", Json.bool(body, "stream", false));
    List<Map<String, Object>> tools = Json.arrOfObj(body.get("tools"));
    if (!tools.isEmpty())
    {
      List<Object> outTools = new ArrayList<>();
      for (Map<String, Object> t : tools)
      {
        Map<String, Object> fn = Json.obj(t.get("function"));
        outTools.add(Json.map("name", Json.str(fn, "name", ""),
            "description", Json.str(fn, "description", ""),
            "input_schema", fn.get("parameters") != null ? fn.get("parameters")
                : Json.map("type", "object", "properties", Json.map())));
      }
      out.put("tools", outTools);
    }
    return out;
  }

  private static Map<String, Object> dataUriToAnthropicSource(String url)
  {
    if (!url.startsWith("data:"))
      return null;
    int semi = url.indexOf(';');
    int comma = url.indexOf(',');
    if (semi < 0 || comma < 0)
      return null;
    return Json.map("type", "base64",
        "media_type", url.substring(5, semi),
        "data", url.substring(comma + 1));
  }

  /** OpenAI content (string or parts) -> plain text. */
  public static String openaiContentToText(Object content)
  {
    if (content instanceof String s)
      return s;
    StringBuilder sb = new StringBuilder();
    for (Map<String, Object> part : Json.arrOfObj(content))
      if ("text".equals(Json.str(part, "type", "")))
        sb.append(Json.str(part, "text", ""));
    return sb.toString();
  }

  // =========================================================================
  // Anthropic -> OpenAI (responses, for anthropic upstream proxies)
  // =========================================================================

  public static Map<String, Object> anthropicRespToOpenai(Map<String, Object> resp, String model)
  {
    StringBuilder text = new StringBuilder();
    List<Object> toolCalls = new ArrayList<>();
    for (Map<String, Object> block : Json.arrOfObj(resp.get("content")))
    {
      String type = Json.str(block, "type", "");
      if ("text".equals(type))
        text.append(Json.str(block, "text", ""));
      else if ("tool_use".equals(type))
        toolCalls.add(Json.map("id", Json.str(block, "id", "call_0"), "type", "function",
            "function", Json.map("name", Json.str(block, "name", ""),
                "arguments", Json.write(Json.obj(block.get("input"))))));
    }
    Map<String, Object> message = Json.map("role", "assistant", "content", text.toString());
    if (!toolCalls.isEmpty())
      message.put("tool_calls", toolCalls);
    Map<String, Object> usage = Json.obj(resp.get("usage"));
    return Json.map(
        "id", Json.str(resp, "id", "chatcmpl-hemera"),
        "object", "chat.completion",
        "created", System.currentTimeMillis() / 1000,
        "model", model,
        "choices", List.of(Json.map("index", 0, "message", message,
            "finish_reason", finishFromAnthropicStop(Json.str(resp, "stop_reason", null)))),
        "usage", Json.map(
            "prompt_tokens", Json.num(usage, "input_tokens", 0),
            "completion_tokens", Json.num(usage, "output_tokens", 0),
            "total_tokens", Json.num(usage, "input_tokens", 0) + Json.num(usage, "output_tokens", 0)));
  }
}
