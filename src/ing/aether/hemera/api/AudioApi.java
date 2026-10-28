package ing.aether.hemera.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sun.net.httpserver.HttpExchange;

import ing.aether.hemera.config.Config;
import ing.aether.hemera.engine.Engines;
import ing.aether.hemera.engine.Instance;
import ing.aether.hemera.engine.InstanceManager;
import ing.aether.hemera.http.HttpError;
import ing.aether.hemera.http.Multipart;
import ing.aether.hemera.http.Router;
import ing.aether.hemera.json.Json;

/**
 * OpenAI-compatible speech recognition (/v1/audio/transcriptions, /v1/audio/translations) on top
 * of whisper.cpp's whisper-server.
 *
 * whisper-server already takes OpenAI's form fields (file, language, prompt, response_format,
 * temperature) and answers in the same formats, so hemera's part is choosing and starting the
 * engine, turning the translations endpoint into whisper's translate task, and refusing the
 * fields it cannot honour instead of quietly answering something else.
 */
public final class AudioApi
{
  private final Config cfg;
  private final InstanceManager im;

  /** Passed through as they are; everything else OpenAI defines is accepted and dropped. */
  private static final Set<String> FORWARDED = Set.of("language", "prompt", "response_format", "temperature");

  private static final Set<String> FORMATS = Set.of("json", "text", "srt", "vtt", "verbose_json");

  public AudioApi(Config cfg, InstanceManager im, Router router)
  {
    this.cfg = cfg;
    this.im = im;
    router.add("POST", "/v1/audio/transcriptions", ex -> recognise(ex, false));
    router.add("POST", "/v1/audio/translations", ex -> recognise(ex, true));
  }

  private void recognise(HttpExchange ex, boolean translate) throws Exception
  {
    String boundary = Multipart.boundary(ex.getRequestHeaders().getFirst("Content-Type"));
    if (boundary == null)
      throw new HttpError(400, "send the audio as multipart/form-data, with the recording in a 'file' field");
    Multipart.Part audio = null;
    String raw = null;
    List<Multipart.Part> out = new ArrayList<>();
    for (Multipart.Part p : Multipart.parse(Router.readBytes(ex), boundary))
    {
      if ("file".equals(p.name()) && p.isFile())
        audio = p;
      else if ("model".equals(p.name()))
        raw = p.value().trim();
      else if (FORWARDED.contains(p.name()) && !p.value().isBlank())
      {
        if ("response_format".equals(p.name()) && !FORMATS.contains(p.value().trim()))
          throw new HttpError(400, "response_format '" + p.value().trim() + "' is not available — use one of "
              + String.join(", ", FORMATS.stream().sorted().toList()));
        // translations always end in English; the spoken language is whisper's to detect
        if (translate && "language".equals(p.name()))
          continue;
        out.add(Multipart.Part.field(p.name(), p.value().trim()));
      }
    }
    if (audio == null)
      throw new HttpError(400, "missing 'file' — the recording to transcribe");
    if (raw == null || raw.isEmpty())
      throw new HttpError(400, "missing 'model'");

    String name = ProxyDispatcher.resolveName(cfg, raw);
    if (cfg.findProxy(name) != null)
      throw new HttpError(400, "speech recognition is not supported for proxied models yet");
    Map<String, Object> model = cfg.findModel(name);
    if (model == null)
      throw new HttpError(404, "model '" + raw + "' not found" + available());
    Engines.requireTranscriptionModel(cfg, model);
    if (translate)
    {
      // Whisper's turbo models were trained on transcription only: asked to translate, they
      // answer in the spoken language as if nothing had been asked.
      boolean turbo = Json.str(model, "file", "").toLowerCase(java.util.Locale.ROOT).contains("turbo");
      if (!Json.bool(model, "translates", !turbo))
        throw new HttpError(400, "model '" + name + "' cannot translate (whisper's turbo models only transcribe)"
            + " — use a full model such as large-v3, or set \"translates\": true on the model if it can");
      out.add(Multipart.Part.field("translate", "true"));
    }
    String fileName = audio.fileName().isBlank() ? "audio" : audio.fileName();
    out.add(Multipart.Part.file("file", fileName, audio.contentType(), audio.data()));

    String outBoundary = Multipart.randomBoundary();
    byte[] payload = Multipart.write(out, outBoundary);
    Instance inst = im.ensure(model);
    Upstream.Raw reply;
    inst.beginRequest();
    try
    {
      reply = Upstream.postBytesRaw("http://127.0.0.1:" + inst.port + "/inference", payload,
          "multipart/form-data; boundary=" + outBoundary);
    }
    finally
    {
      inst.endRequest();
    }
    String format = out.stream().filter(p -> "response_format".equals(p.name())).map(Multipart.Part::value)
        .findFirst().orElse("json");
    Router.sendBytes(ex, 200, tidy(format, reply.body()), reply.contentType());
  }

  /**
   * whisper-server ends every segment with a newline and starts it with the space that separated
   * it from the one before; OpenAI's text is one clean run. Joining without the newlines puts the
   * spaces back where they belong (and adds none to languages written without them).
   */
  private static byte[] tidy(String format, byte[] body)
  {
    if ("json".equals(format) || "verbose_json".equals(format))
    {
      Map<String, Object> reply = Json.parseObj(new String(body, java.nio.charset.StandardCharsets.UTF_8));
      if (reply.get("text") instanceof String t)
        reply.put("text", oneLine(t));
      return Json.write(reply).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    if ("text".equals(format))
      return (oneLine(new String(body, java.nio.charset.StandardCharsets.UTF_8)) + "\n")
          .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    return body;
  }

  private static String oneLine(String text)
  {
    return text.replace("\r", "").replace("\n", "").strip();
  }

  /** The speech models there are, for a "not found" that says what to ask for instead. */
  private String available()
  {
    List<String> names = new ArrayList<>();
    for (Map<String, Object> m : cfg.models())
      if (Engines.isTranscriptionModel(cfg, m))
        names.add(Json.str(m, "name", "?"));
    return names.isEmpty() ? " — no speech-recognition model is configured"
        : " — speech-recognition models: " + String.join(", ", names);
  }
}
