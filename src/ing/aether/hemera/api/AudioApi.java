package ing.aether.hemera.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
  final LiveGate live = new LiveGate();

  /** Passed through as they are; everything else OpenAI defines is accepted and dropped. */
  private static final Set<String> FORWARDED = Set.of("language", "prompt", "response_format", "temperature");

  private static final Set<String> FORMATS = Set.of("json", "text", "srt", "vtt", "verbose_json", "diarized_json");

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
    boolean diarize = false;
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
        // who said it comes from a separate model, and each speaker's turn is transcribed alone
        if ("response_format".equals(p.name()) && "diarized_json".equals(p.value().trim()))
        {
          diarize = true;
          continue;
        }
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

    if (diarize)
    {
      if (translate)
        throw new HttpError(400, "diarized_json is for transcriptions, not translations");
      live.awaitTurn(ex, "speaker-labelled transcription on '" + name + "'");
      String language = out.stream().filter(p -> "language".equals(p.name())).map(Multipart.Part::value).findFirst().orElse(null);
      String prompt = out.stream().filter(p -> "prompt".equals(p.name())).map(Multipart.Part::value).findFirst().orElse("");
      Router.sendJson(ex, 200, diarized(model, audio.data(), language, prompt));
      return;
    }
    String outBoundary = Multipart.randomBoundary();
    byte[] payload = Multipart.write(out, outBoundary);
    live.awaitTurn(ex, (translate ? "translation" : "transcription") + " on '" + name + "'");
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

  // ---- speaker labels -------------------------------------------------------------

  /** One stretch of one speaker, in seconds. */
  private record Turn(double start, double end, String speaker)
  {
  }

  /**
   * OpenAI's diarized_json. The speaker-separation model says who spoke when; each speaker's turn
   * (neighbouring turns of one speaker joined) is then transcribed on its own. Transcribing per turn rather than labelling one long transcript
   * afterwards keeps every word with its speaker: whisper's own timestamps drift by seconds
   * across pauses, exactly where speakers change.
   */
  private Map<String, Object> diarized(Map<String, Object> model, byte[] recording, String language, String prompt)
      throws Exception
  {
    short[] pcm;
    if (SpeechApi.hasFfmpeg())
      pcm = Pcm.fromLittleEndian(SpeechApi.ffmpeg(recording, List.of("-ac", "1", "-ar", "16000", "-f", "s16le")));
    else if (Pcm.wavRate(recording) == 16000)
      pcm = Pcm.fromLittleEndian(java.util.Arrays.copyOfRange(recording, 44, recording.length));
    else
      throw new HttpError(400, "speaker labels need ffmpeg on the server, or a 16 kHz mono WAV recording");

    List<Turn> turns = join(speakerTurns(model, Pcm.wav(pcm, 16000)));
    Map<String, String> labels = new LinkedHashMap<>();
    List<Object> segments = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    for (Turn t : turns)
    {
      int from = Math.max(0, (int) ((t.start() - 0.1) * 16000));
      int to = Math.min(pcm.length, (int) ((t.end() + 0.1) * 16000));
      if (to - from < 16000 / 4)
        continue;
      // only the client's prompt: whisper copies the style of what it is given, so one turn
      // that came back without punctuation would take the punctuation out of all that follow
      String said = transcribe(model, Pcm.wav(java.util.Arrays.copyOfRange(pcm, from, to), 16000), language, prompt);
      if (said.isBlank())
        continue;
      String label = labels.computeIfAbsent(t.speaker(), k -> label(labels.size()));
      segments.add(Json.map("type", "transcript.text.segment", "id", "seg_" + segments.size(),
          "start", Math.round(t.start() * 100) / 100.0, "end", Math.round(t.end() * 100) / 100.0,
          "text", said, "speaker", label));
      text.append(text.length() == 0 ? "" : " ").append(said);
    }
    return Json.map("task", "transcribe", "duration", Math.round(pcm.length / 16.0) / 1000.0,
        "text", text.toString(), "segments", segments);
  }

  /** Neighbouring turns of the same speaker, less than a second apart, are one turn. */
  private static List<Turn> join(List<Turn> turns)
  {
    List<Turn> sorted = new ArrayList<>(turns);
    sorted.sort(java.util.Comparator.comparingDouble(Turn::start));
    List<Turn> out = new ArrayList<>();
    for (Turn t : sorted)
    {
      Turn last = out.isEmpty() ? null : out.get(out.size() - 1);
      if (last != null && last.speaker().equals(t.speaker()) && t.start() - last.end() < 1.0)
        out.set(out.size() - 1, new Turn(last.start(), Math.max(last.end(), t.end()), last.speaker()));
      else
        out.add(t);
    }
    return out;
  }

  /**
   * Who spoke when, from the speaker-separation model: the one the speech model names as its
   * "diarizer", or else the first configured. It takes 16 kHz mono WAV.
   */
  private List<Turn> speakerTurns(Map<String, Object> model, byte[] wav) throws Exception
  {
    Map<String, Object> diarizer = null;
    String wanted = Json.str(model, "diarizer", null);
    for (Map<String, Object> m : cfg.models())
      if (Engines.isDiarizationModel(cfg, m) && (wanted == null || wanted.equals(Json.str(m, "name", null))))
      {
        diarizer = m;
        break;
      }
    if (diarizer == null)
      throw new HttpError(400, wanted != null ? "the diarizer '" + wanted + "' is not a configured speaker-separation model"
          : "diarized_json needs a speaker-separation model (an audio.cpp model with \"task\": \"diar\") — none is configured");
    String name = Json.str(diarizer, "name", "?");
    String boundary = Multipart.randomBoundary();
    byte[] payload = Multipart.write(List.of(Multipart.Part.field("model", name),
        Multipart.Part.file("file", "speech.wav", "audio/wav", wav)), boundary);
    Instance inst = im.ensure(diarizer);
    Map<String, Object> result;
    inst.beginRequest();
    try
    {
      result = Json.parseObj(new String(Upstream.postBytesRaw("http://127.0.0.1:" + inst.port
          + "/v1/audio/transcriptions/details", payload, "multipart/form-data; boundary=" + boundary).body(),
          java.nio.charset.StandardCharsets.UTF_8));
    }
    finally
    {
      inst.endRequest();
    }
    double rate = Json.numd(result, "sample_rate", 16000);
    List<Turn> turns = new ArrayList<>();
    for (Map<String, Object> t : Json.arrOfObj(result.get("speaker_turns")))
      turns.add(new Turn(Json.numd(t, "start_sample", 0) / rate, Json.numd(t, "end_sample", 0) / rate,
          Json.str(t, "speaker_id", "?")));
    return turns;
  }

  /** A, B, ... Z, then AA, AB, ... */
  private static String label(int i)
  {
    return i < 26 ? String.valueOf((char) ('A' + i)) : label(i / 26 - 1) + (char) ('A' + i % 26);
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

  /** A configured speech model by the name a client used, or an HttpError that says what exists. */
  Map<String, Object> transcriptionModel(String raw)
  {
    Map<String, Object> model = cfg.findModel(ProxyDispatcher.resolveName(cfg, raw));
    if (model == null)
      throw new HttpError(404, "model '" + raw + "' not found" + available());
    Engines.requireTranscriptionModel(cfg, model);
    return model;
  }

  /** The first configured speech model, for clients that leave the choice to the server. */
  Map<String, Object> defaultTranscriptionModel()
  {
    for (Map<String, Object> m : cfg.models())
      if (Engines.isTranscriptionModel(cfg, m))
        return m;
    throw new HttpError(404, "no speech-recognition model is configured");
  }

  /** Transcribe one WAV recording and return its text as one clean line. */
  String transcribe(Map<String, Object> model, byte[] wav, String language, String prompt) throws Exception
  {
    List<Multipart.Part> parts = new ArrayList<>();
    if (language != null && !language.isBlank())
      parts.add(Multipart.Part.field("language", language));
    if (prompt != null && !prompt.isBlank())
      parts.add(Multipart.Part.field("prompt", prompt));
    parts.add(Multipart.Part.field("response_format", "json"));
    parts.add(Multipart.Part.field("temperature", "0"));
    parts.add(Multipart.Part.file("file", "speech.wav", "audio/wav", wav));
    String boundary = Multipart.randomBoundary();
    byte[] payload = Multipart.write(parts, boundary);
    Instance inst = im.ensure(model);
    inst.beginRequest();
    try
    {
      Upstream.Raw reply = Upstream.postBytesRaw("http://127.0.0.1:" + inst.port + "/inference", payload,
          "multipart/form-data; boundary=" + boundary);
      return oneLine(Json.str(Json.parseObj(new String(reply.body(), java.nio.charset.StandardCharsets.UTF_8)),
          "text", ""));
    }
    finally
    {
      inst.endRequest();
    }
  }

  /** Keep a model loaded while a live session may need it at any moment; returns how to let go. */
  Runnable pin(Map<String, Object> model) throws Exception
  {
    Instance inst = im.ensure(model);
    inst.beginRequest();
    return inst::endRequest;
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
