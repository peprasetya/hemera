package ing.aether.hemera.api;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;

import ing.aether.hemera.Log;
import ing.aether.hemera.config.Config;
import ing.aether.hemera.engine.Engines;
import ing.aether.hemera.engine.Instance;
import ing.aether.hemera.engine.InstanceManager;
import ing.aether.hemera.http.HttpError;
import ing.aether.hemera.http.Multipart;
import ing.aether.hemera.http.Router;
import ing.aether.hemera.json.Json;

/**
 * OpenAI-compatible text to speech (/v1/audio/speech) on top of audio.cpp's audiocpp_server, and
 * the voice library it clones from (/v1/audio/voices).
 *
 * audio.cpp already answers /v1/audio/speech in OpenAI's shape, including the SSE stream. What
 * hemera adds: voices by name from its own library (a recording plus what is said in it),
 * OpenAI's output formats (audio.cpp writes WAV; ffmpeg makes the rest), OpenAI's 24 kHz for
 * raw PCM whatever rate the model speaks at, and `instructions` spelled the way each model
 * family takes a voice description.
 */
public final class SpeechApi
{
  private final Config cfg;
  private final InstanceManager im;
  private final AudioApi audio;

  /** OpenAI's built-in voice names: SDKs send one even when the user chose nothing. */
  private static final Set<String> OPENAI_VOICES = Set.of("alloy", "ash", "ballad", "coral", "echo", "fable",
      "nova", "onyx", "sage", "shimmer", "verse", "marin", "cedar");

  private static final Map<String, String> CONTENT_TYPES = Map.of("mp3", "audio/mpeg", "opus", "audio/ogg",
      "aac", "audio/aac", "flac", "audio/flac", "wav", "audio/wav", "pcm", "audio/pcm");

  /** What OpenAI streams and promises for "pcm": 16-bit mono at 24 kHz. */
  private static final int PCM_RATE = 24000;

  private static final Pattern VOICE_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

  /** The rate each running engine speaks at, learned once: audio.cpp's stream does not say. */
  private final Map<Instance, Integer> rates = new WeakHashMap<>();

  public SpeechApi(Config cfg, InstanceManager im, AudioApi audio, Router router)
  {
    this.cfg = cfg;
    this.im = im;
    this.audio = audio;
    router.add("POST", "/v1/audio/speech", this::speech);
    router.add("GET", "/v1/audio/voices", ex -> Router.sendJson(ex, 200, Json.map("object", "list", "data", voices())));
    router.add("POST", "/v1/audio/voices", this::addVoice);
    router.addPrefix("GET", "/v1/audio/voices/", this::voiceFile);
    router.addPrefix("DELETE", "/v1/audio/voices/", this::deleteVoice);
  }

  // ---- speech -------------------------------------------------------------------

  private void speech(HttpExchange ex) throws Exception
  {
    Map<String, Object> body = Router.readJson(ex);
    String raw = Json.str(body, "model", null);
    if (raw == null)
      throw new HttpError(400, "missing 'model'");
    String name = ProxyDispatcher.resolveName(cfg, raw);
    if (cfg.findProxy(name) != null)
      throw new HttpError(400, "text to speech is not supported for proxied models yet");
    Map<String, Object> model = cfg.findModel(name);
    if (model == null)
      throw new HttpError(404, "model '" + raw + "' not found" + speechModels());
    Engines.requireSpeechModel(cfg, model);
    String input = Json.str(body, "input", "");
    if (input.isBlank())
      throw new HttpError(400, "missing 'input' — the text to speak");
    String format = Json.str(body, "response_format", "mp3");
    if (!CONTENT_TYPES.containsKey(format))
      throw new HttpError(400, "response_format '" + format + "' is not available — use one of mp3, opus, aac, flac, wav, pcm");
    String streamFormat = Json.str(body, "stream_format", null);
    if (streamFormat != null && !Set.of("sse", "audio").contains(streamFormat))
      throw new HttpError(400, "stream_format must be 'sse' or 'audio'");
    if (streamFormat != null && !"pcm".equals(format) && body.containsKey("response_format"))
      throw new HttpError(400, "streaming sends 16-bit PCM at 24 kHz; leave response_format out or set it to 'pcm'");
    if (!"wav".equals(format) && !"pcm".equals(format) && streamFormat == null && !hasFfmpeg())
      throw new HttpError(400, "response_format '" + format + "' needs ffmpeg on the server — ask for wav or pcm");

    Map<String, Object> up = upstreamRequest(name, model, body, input);
    Instance inst = im.ensure(model);
    inst.beginRequest();
    try
    {
      String url = "http://127.0.0.1:" + inst.port + "/v1/audio/speech";
      if (streamFormat == null)
      {
        byte[] wav = Upstream.postJsonRaw(url, up).body();
        Router.sendBytes(ex, 200, convert(wav, format), CONTENT_TYPES.get(format));
      }
      else if ("streaming".equals(Json.str(model, "mode", "offline")))
        relayStream(ex, inst, url, up, "sse".equals(streamFormat));
      else
        sendWhole(ex, Upstream.postJsonRaw(url, up).body(), "sse".equals(streamFormat));
    }
    finally
    {
      inst.endRequest();
    }
  }

  /** The request as audio.cpp takes it: library voices become a reference recording and its text. */
  @SuppressWarnings("unchecked")
  private Map<String, Object> upstreamRequest(String name, Map<String, Object> model, Map<String, Object> body, String input)
  {
    Map<String, Object> up = new LinkedHashMap<>();
    up.put("model", name);
    Map<String, Object> options = new LinkedHashMap<>();
    if (body.get("options") instanceof Map<?, ?> o)
      options.putAll((Map<String, Object>) o);

    String voice = Json.str(body, "voice", null);
    if (voice == null || voice.isBlank() || OPENAI_VOICES.contains(voice) && voiceDir(voice) == null)
      voice = Json.str(model, "voice", null);
    if (voice != null && !voice.isBlank())
    {
      Map<String, Object> v = voice(voice);
      if (v != null)
      {
        up.put("voice_ref", Json.map("type", "path", "path", voicesDir().resolve(voice + ".wav").toString()));
        if (!Json.str(v, "transcript", "").isBlank())
          up.put("reference_text", Json.str(v, "transcript", ""));
      }
      else
        up.put("voice", voice); // one the model knows itself, such as Kokoro's af_heart
    }

    String instructions = Json.str(body, "instructions", "");
    String family = Json.str(model, "family", "");
    if (!instructions.isBlank())
    {
      // VoxCPM2 reads a voice description from parentheses at the start of the text; OmniVoice
      // takes it as an option; anything else gets OpenAI's field and decides for itself.
      if (family.startsWith("voxcpm"))
        input = "(" + instructions.strip() + ")" + input;
      else if ("omnivoice".equals(family))
        options.putIfAbsent("instruct", instructions.strip());
      else
        up.put("instructions", instructions);
    }
    up.put("input", input);
    if (body.get("speed") instanceof Number speed)
      up.put("speed", speed);
    if (body.containsKey("seed"))
      up.put("seed", body.get("seed"));
    if (!options.isEmpty())
      up.put("options", options);
    return up;
  }

  /** Stream the engine's own audio as it is made, at OpenAI's 24 kHz. */
  private void relayStream(HttpExchange ex, Instance inst, String url, Map<String, Object> up, boolean sse) throws Exception
  {
    int rate = rate(inst, url, up);
    Map<String, Object> streamed = new LinkedHashMap<>(up);
    streamed.put("response_format", "pcm");
    streamed.put("stream_format", "sse");
    Pcm.Resampler resampler = new Pcm.Resampler(rate, PCM_RATE);
    try (InputStream in = Upstream.postStream(url, streamed, Map.of("Accept", "text/event-stream")))
    {
      OutputStream out = Router.startStream(ex, sse ? "text/event-stream" : CONTENT_TYPES.get("pcm"));
      BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      String line;
      while ((line = r.readLine()) != null)
      {
        if (!line.startsWith("data:"))
          continue;
        String data = line.substring(5).trim();
        if (data.equals("[DONE]"))
          break;
        Map<String, Object> event = Json.parseObj(data);
        String type = Json.str(event, "type", "");
        if ("speech.audio.delta".equals(type))
        {
          short[] pcm = resampler.process(Pcm.fromLittleEndian(Base64.getDecoder().decode(Json.str(event, "audio", ""))));
          writeAudio(out, Pcm.toLittleEndian(pcm), sse);
        }
        else if (sse)
          Router.sse(out, null, event); // done, or an error the client should see
      }
      out.close();
    }
  }

  /** A model that cannot stream answers whole; it still goes out in the shape the client asked for. */
  private void sendWhole(HttpExchange ex, byte[] wav, boolean sse) throws Exception
  {
    byte[] pcm = convert(wav, "pcm");
    OutputStream out = Router.startStream(ex, sse ? "text/event-stream" : CONTENT_TYPES.get("pcm"));
    for (int off = 0; off < pcm.length; off += PCM_RATE)   // half-second pieces
      writeAudio(out, java.util.Arrays.copyOfRange(pcm, off, Math.min(pcm.length, off + PCM_RATE)), sse);
    if (sse)
      Router.sse(out, null, Json.map("type", "speech.audio.done"));
    out.close();
  }

  private static void writeAudio(OutputStream out, byte[] pcm, boolean sse) throws IOException
  {
    if (pcm.length == 0)
      return;
    if (sse)
      Router.sse(out, null, Json.map("type", "speech.audio.delta", "audio", Base64.getEncoder().encodeToString(pcm)));
    else
    {
      out.write(pcm);
      out.flush();
    }
  }

  /** The engine's own sample rate, from the header of one short whole answer. */
  private int rate(Instance inst, String url, Map<String, Object> up) throws Exception
  {
    synchronized (rates)
    {
      Integer known = rates.get(inst);
      if (known != null)
        return known;
    }
    Map<String, Object> probe = new LinkedHashMap<>(up);
    probe.put("input", "Hi.");
    int r = Pcm.wavRate(Upstream.postJsonRaw(url, probe).body());
    if (r <= 0)
      throw new HttpError(502, "the speech engine did not answer with a WAV file");
    synchronized (rates)
    {
      rates.put(inst, r);
    }
    Log.info("speech", "'" + inst.name + "' speaks at " + r + " Hz");
    return r;
  }

  // ---- formats ------------------------------------------------------------------

  /** WAV from the engine into what the client asked for. */
  private static byte[] convert(byte[] wav, String format) throws Exception
  {
    List<String> args = switch (format)
    {
      case "wav" -> null;
      case "pcm" -> List.of("-ac", "1", "-ar", String.valueOf(PCM_RATE), "-f", "s16le");
      case "mp3" -> List.of("-c:a", "libmp3lame", "-b:a", "128k", "-f", "mp3");
      case "opus" -> List.of("-c:a", "libopus", "-b:a", "64k", "-f", "ogg");
      case "aac" -> List.of("-c:a", "aac", "-b:a", "128k", "-f", "adts");
      case "flac" -> List.of("-f", "flac");
      default -> throw new HttpError(400, "unknown format " + format);
    };
    if (args == null)
      return wav;
    if ("pcm".equals(format) && !hasFfmpeg())
    {
      // no ffmpeg: raw PCM can still be cut out of the WAV and brought to 24 kHz here
      int rate = Pcm.wavRate(wav);
      short[] s = Pcm.fromLittleEndian(java.util.Arrays.copyOfRange(wav, 44, wav.length));
      return Pcm.toLittleEndian(new Pcm.Resampler(rate, PCM_RATE).process(s));
    }
    return ffmpeg(wav, args);
  }

  static byte[] ffmpeg(byte[] in, List<String> outputArgs) throws Exception
  {
    List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-i", "pipe:0"));
    cmd.addAll(outputArgs);
    cmd.add("pipe:1");
    Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.PIPE).start();
    Thread feeder = Thread.ofVirtual().start(() -> {
      try (OutputStream os = p.getOutputStream())
      {
        os.write(in);
      }
      catch (IOException ignored)
      {
        // ffmpeg stopped reading; its exit status says why
      }
    });
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Thread errors = Thread.ofVirtual().start(() -> {
      try
      {
        p.getErrorStream().transferTo(err);
      }
      catch (IOException ignored)
      {
      }
    });
    byte[] out = p.getInputStream().readAllBytes();
    feeder.join();
    errors.join();
    if (p.waitFor() != 0)
      throw new HttpError(500, "ffmpeg could not convert the audio: " + err.toString(StandardCharsets.UTF_8).strip());
    return out;
  }

  private static volatile Boolean ffmpeg;

  static boolean hasFfmpeg()
  {
    if (ffmpeg == null)
    {
      boolean found = false;
      for (String dir : System.getenv().getOrDefault("PATH", "").split(":"))
        found |= !dir.isEmpty() && Files.isExecutable(Path.of(dir, "ffmpeg"));
      ffmpeg = found;
    }
    return ffmpeg;
  }

  private String speechModels()
  {
    List<String> names = new ArrayList<>();
    for (Map<String, Object> m : cfg.models())
      if (Engines.isSpeechModel(cfg, m))
        names.add(Json.str(m, "name", "?"));
    return names.isEmpty() ? " — no text-to-speech model is configured"
        : " — text-to-speech models: " + String.join(", ", names);
  }

  // ---- voice library ---------------------------------------------------------------
  //
  // One folder: <name>.wav (the recording, mono 16-bit) and <name>.json (what is said in it, whose
  // voice it is, and the consent it was recorded under). Any speech model that clones can use it.

  private Path voicesDir()
  {
    return cfg.resolve(Json.str(cfg.data(), "voicesDir", "voices"));
  }

  private Path voiceDir(String name)
  {
    return VOICE_NAME.matcher(name).matches() && Files.isRegularFile(voicesDir().resolve(name + ".wav"))
        ? voicesDir() : null;
  }

  private Map<String, Object> voice(String name)
  {
    if (voiceDir(name) == null)
      return null;
    Path meta = voicesDir().resolve(name + ".json");
    try
    {
      Map<String, Object> v = Json.map("id", name, "object", "voice");
      if (Files.isRegularFile(meta))
        v.putAll(Json.parseObj(Files.readString(meta)));
      return v;
    }
    catch (IOException | RuntimeException e)
    {
      return Json.map("id", name, "object", "voice");
    }
  }

  private List<Object> voices() throws IOException
  {
    List<Object> out = new ArrayList<>();
    Path dir = voicesDir();
    if (!Files.isDirectory(dir))
      return out;
    try (var files = Files.list(dir))
    {
      for (Path p : files.sorted().toList())
      {
        String f = p.getFileName().toString();
        if (f.endsWith(".wav"))
        {
          Map<String, Object> v = voice(f.substring(0, f.length() - 4));
          if (v != null)
            out.add(v);
        }
      }
    }
    return out;
  }

  /**
   * Add a voice: a recording of someone speaking, ideally 5–20 s of clear speech. Without a
   * transcript, the first speech-recognition model writes one; cloning is closer with it.
   */
  private void addVoice(HttpExchange ex) throws Exception
  {
    String boundary = Multipart.boundary(ex.getRequestHeaders().getFirst("Content-Type"));
    if (boundary == null)
      throw new HttpError(400, "send multipart/form-data: name, file, consent, and optionally transcript and owner");
    Map<String, String> fields = new LinkedHashMap<>();
    byte[] recording = null;
    for (Multipart.Part p : Multipart.parse(Router.readBytes(ex), boundary))
      if ("file".equals(p.name()) && p.isFile())
        recording = p.data();
      else if (p.name() != null)
        fields.put(p.name(), p.value().strip());
    String name = fields.getOrDefault("name", "").toLowerCase(java.util.Locale.ROOT);
    if (!VOICE_NAME.matcher(name).matches())
      throw new HttpError(400, "'name' must be lowercase letters, digits, '-' or '_' (up to 64), e.g. \"paulus\"");
    if (OPENAI_VOICES.contains(name))
      throw new HttpError(400, "'" + name + "' is one of OpenAI's voice names; choose another so the two never mix");
    if (recording == null || recording.length == 0)
      throw new HttpError(400, "missing 'file' — a recording of the voice");
    String consent = fields.getOrDefault("consent", "");
    if (consent.isEmpty())
      throw new HttpError(400, "missing 'consent' — say whose voice this is and that they agreed to it being cloned,"
          + " e.g. \"my own voice\" or \"recorded with Maria's permission for the parish announcements\"");

    // one format for every engine: mono 16-bit, at most 30 s (more does not clone better)
    byte[] wav;
    if (hasFfmpeg())
      // raw samples, wrapped here: a WAV that ffmpeg writes into a pipe has no lengths in its
      // header, and audio.cpp refuses such a file
      wav = Pcm.wav(Pcm.fromLittleEndian(ffmpeg(recording, List.of("-t", "30", "-ac", "1", "-ar", "24000", "-f", "s16le"))), 24000);
    else if (Pcm.wavRate(recording) > 0)
      wav = recording;
    else
      throw new HttpError(400, "without ffmpeg on the server the recording must be a WAV file");
    double seconds = (wav.length - 44) / 2.0 / Math.max(1, Pcm.wavRate(wav));
    if (seconds < 2)
      throw new HttpError(400, "the recording is only " + String.format("%.1f", seconds) + " s — send 5–20 s of speech");

    String transcript = fields.getOrDefault("transcript", "");
    if (transcript.isEmpty())
    {
      try
      {
        transcript = audio.transcribe(audio.defaultTranscriptionModel(), wav, fields.get("language"), null);
      }
      catch (Exception e)
      {
        Log.warn("speech", "Could not transcribe voice '" + name + "' (" + e.getMessage() + "); stored without a transcript");
      }
    }
    Map<String, Object> meta = Json.map("transcript", transcript, "owner", fields.getOrDefault("owner", ""),
        "consent", consent, "created_at", Instant.now().getEpochSecond(),
        "duration_sec", Math.round(seconds * 10) / 10.0);
    Files.createDirectories(voicesDir());
    Files.write(voicesDir().resolve(name + ".wav"), wav);
    Files.writeString(voicesDir().resolve(name + ".json"), Json.writePretty(meta));
    Log.info("speech", "Voice '" + name + "' saved (" + meta.get("duration_sec") + " s)");
    Router.sendJson(ex, 200, voice(name));
  }

  /** GET /v1/audio/voices/{name} for its details, /v1/audio/voices/{name}/audio for the recording. */
  private void voiceFile(HttpExchange ex) throws Exception
  {
    String rest = ex.getRequestURI().getPath().substring("/v1/audio/voices/".length());
    boolean wantAudio = rest.endsWith("/audio");
    String name = wantAudio ? rest.substring(0, rest.length() - "/audio".length()) : rest;
    Map<String, Object> v = voice(name);
    if (v == null)
      throw new HttpError(404, "voice '" + name + "' not found");
    if (wantAudio)
      Router.sendBytes(ex, 200, Files.readAllBytes(voicesDir().resolve(name + ".wav")), "audio/wav");
    else
      Router.sendJson(ex, 200, v);
  }

  private void deleteVoice(HttpExchange ex) throws Exception
  {
    String name = ex.getRequestURI().getPath().substring("/v1/audio/voices/".length());
    if (voice(name) == null)
      throw new HttpError(404, "voice '" + name + "' not found");
    Files.deleteIfExists(voicesDir().resolve(name + ".wav"));
    Files.deleteIfExists(voicesDir().resolve(name + ".json"));
    Log.info("speech", "Voice '" + name + "' deleted");
    Router.sendJson(ex, 200, Json.map("id", name, "object", "voice", "deleted", true));
  }
}
