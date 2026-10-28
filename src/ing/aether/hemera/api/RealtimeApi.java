package ing.aether.hemera.api;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.HttpExchange;

import ing.aether.hemera.Log;
import ing.aether.hemera.http.HttpError;
import ing.aether.hemera.http.Router;
import ing.aether.hemera.http.WebSocket;
import ing.aether.hemera.json.Json;

/**
 * Live transcription over OpenAI's Realtime API (GET /v1/realtime, WebSocket): the client streams
 * audio, hemera cuts it into utterances at the pauses and sends back their text as it is heard.
 *
 * Only the transcription side of Realtime is offered; there is no model talking back. Both
 * spellings of the session are understood: the beta "transcription_session.update" with
 * input_audio_format / input_audio_transcription / turn_detection, and the GA "session.update"
 * with session.audio.input.{format, transcription, turn_detection}.
 *
 * Whisper has no streaming mode, so while someone is speaking the utterance so far is
 * transcribed again about once a second. Words that two passes in a row agree on are sent as
 * deltas (they rarely change after that); the whole utterance is transcribed once more when it
 * ends, and that text is the "completed" transcript. Each utterance's text is handed to the next
 * one as context, which keeps names and spelling consistent.
 */
public final class RealtimeApi
{
  private final AudioApi audio;

  /** Whisper works on 16 kHz mono; everything is converted to that on arrival. */
  private static final int RATE = 16000;
  private static final int FRAME = RATE / 50; // 20 ms
  /** Whisper sees 30 s at a time; a longer utterance is cut there and carries on as the next one. */
  private static final int MAX_UTTERANCE = 29 * RATE;
  private static final int MIN_SPEECH = RATE / 4;
  private static final long PARTIAL_MS = 1000;
  private static final int CONTEXT_CHARS = 600;

  public RealtimeApi(AudioApi audio, Router router)
  {
    this.audio = audio;
    router.add("GET", "/v1/realtime", this::connect);
  }

  private void connect(HttpExchange ex) throws Exception
  {
    if (!WebSocket.isUpgrade(ex))
      throw new HttpError(426, "/v1/realtime is a WebSocket — connect with ws://, e.g. "
          + "new WebSocket(\"ws://host:12434/v1/realtime?intent=transcription\")");
    Map<String, String> query = query(ex.getRequestURI().getRawQuery());
    // A browser cannot send headers, so OpenAI's clients put a key in a subprotocol; hemera
    // needs none but must still pick one, and "realtime" is the one they all offer.
    try (WebSocket ws = WebSocket.accept(ex, "realtime"))
    {
      Runnable done = audio.live.enter("transcription session");
      try
      {
        new Session(ws, query).run();
      }
      finally
      {
        done.run();
      }
    }
  }

  private static Map<String, String> query(String raw)
  {
    Map<String, String> out = new LinkedHashMap<>();
    if (raw != null)
      for (String kv : raw.split("&"))
      {
        int i = kv.indexOf('=');
        if (i > 0)
          out.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
              URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
      }
    return out;
  }

  private final class Session
  {
    private final WebSocket ws;
    private final String id = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    private final boolean ga;
    /** Recognition runs here, one job at a time, so texts come back in the order spoken. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private final AtomicBoolean partialRunning = new AtomicBoolean();

    // settings
    private Map<String, Object> model;
    private String modelName;
    private String language;
    private String prompt = "";
    private String format = "pcm16";
    private int inputRate = 24000;
    private boolean serverVad = true;
    private double threshold = 0.5;
    private int prefixMs = 300;
    private int silenceMs = 500;
    private Runnable unpin;

    // audio state
    private final Pcm.Resampler resampler = new Pcm.Resampler(24000, RATE);
    private short[] pending = new short[0];          // 16 kHz samples not yet a whole VAD frame
    private final ShortBuffer prefix = new ShortBuffer();
    private final ShortBuffer utterance = new ShortBuffer();
    private boolean speaking;
    private int loudFrames;
    private int quietSamples;
    private double noiseDb = -60;
    private long samplesSeen;                         // since the session began, for *_ms fields
    private long utteranceStart;
    private long lastPartial;
    private String itemId;
    private String previousItemId;
    private final StringBuilder context = new StringBuilder();

    Session(WebSocket ws, Map<String, String> query)
    {
      this.ws = ws;
      this.ga = !"transcription".equals(query.get("intent"));
      if (query.containsKey("model"))
        modelName = query.get("model");
      if (query.containsKey("language"))
        language = query.get("language");
    }

    void run() throws Exception
    {
      send(Json.map("type", ga ? "session.created" : "transcription_session.created", "session", describe()));
      try
      {
        WebSocket.Message m;
        while ((m = ws.read()) != null)
        {
          if (!m.text())
          {
            error("invalid_request_error", "binary_not_supported", "send events as JSON text messages");
            continue;
          }
          Map<String, Object> event;
          try
          {
            event = Json.parseObj(m.string());
          }
          catch (RuntimeException e)
          {
            error("invalid_request_error", "invalid_json", "not a JSON event: " + e.getMessage());
            continue;
          }
          try
          {
            handle(event);
          }
          catch (HttpError e)
          {
            error("invalid_request_error", null, e.getMessage());
          }
        }
      }
      catch (java.io.IOException e)
      {
        Log.debug("realtime", id + " ended: " + e.getMessage());
      }
      finally
      {
        worker.shutdownNow();
        if (unpin != null)
          unpin.run();
        Log.info("realtime", "Transcription session " + id + " closed");
      }
    }

    private void handle(Map<String, Object> event) throws Exception
    {
      String type = Json.str(event, "type", "");
      switch (type)
      {
        case "transcription_session.update", "session.update" -> update(Json.obj(event.get("session")), type);
        case "input_audio_buffer.append" -> append(Json.str(event, "audio", ""));
        case "input_audio_buffer.commit" -> commit();
        case "input_audio_buffer.clear" ->
        {
          utterance.clear();
          speaking = false;
          send(Json.map("type", "input_audio_buffer.cleared"));
        }
        default -> error("invalid_request_error", "unknown_event",
            "'" + type + "' is not supported here — this endpoint only transcribes (session update,"
                + " input_audio_buffer.append / commit / clear)");
      }
    }

    // ---- settings -----------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void update(Map<String, Object> s, String type) throws Exception
    {
      Map<String, Object> in = Json.obj(Json.obj(s.get("audio")).get("input"));
      Map<String, Object> transcription = Json.obj(in.isEmpty() ? s.get("input_audio_transcription") : in.get("transcription"));
      if (!transcription.isEmpty())
      {
        if (transcription.containsKey("model"))
          modelName = Json.str(transcription, "model", modelName);
        if (transcription.containsKey("language"))
          language = Json.str(transcription, "language", null);
        if (transcription.containsKey("prompt"))
          prompt = Json.str(transcription, "prompt", "");
      }
      Object fmt = in.isEmpty() ? s.get("input_audio_format") : in.get("format");
      if (fmt instanceof String f)
        setFormat(f, inputRate);
      else if (fmt instanceof Map<?, ?> f)
        setFormat(Json.str((Map<String, Object>) f, "type", "audio/pcm"),
            (int) Json.num((Map<String, Object>) f, "rate", 24000));
      Map<String, Object> tdHolder = in.isEmpty() ? s : in;
      if (tdHolder.containsKey("turn_detection"))
      {
        Object td = tdHolder.get("turn_detection");
        serverVad = td != null;
        if (td instanceof Map<?, ?> t)
        {
          Map<String, Object> m = (Map<String, Object>) t;
          threshold = Json.numd(m, "threshold", threshold);
          prefixMs = (int) Json.num(m, "prefix_padding_ms", prefixMs);
          silenceMs = (int) Json.num(m, "silence_duration_ms", silenceMs);
        }
      }
      chooseModel();
      send(Json.map("type", type.replace("update", "updated"), "session", describe()));
    }

    private void setFormat(String f, int rate)
    {
      switch (f)
      {
        case "pcm16", "audio/pcm" ->
        {
          format = "pcm16";
          inputRate = rate;
        }
        case "g711_ulaw", "audio/pcmu" ->
        {
          format = "g711_ulaw";
          inputRate = 8000;
        }
        case "g711_alaw", "audio/pcma" ->
        {
          format = "g711_alaw";
          inputRate = 8000;
        }
        default -> throw new HttpError(400, "audio format '" + f + "' is not supported — use pcm16, g711_ulaw or g711_alaw");
      }
      if (inputRate < 8000 || inputRate > 48000)
        throw new HttpError(400, "sample rate " + inputRate + " is out of range (8000–48000)");
      resampler.setRate(inputRate);
    }

    /** Settle on a model and load it now, so the first words are not kept waiting for that. */
    private void chooseModel() throws Exception
    {
      Map<String, Object> chosen = modelName == null || modelName.isBlank()
          ? audio.defaultTranscriptionModel() : audio.transcriptionModel(modelName);
      if (chosen == model)
        return;
      model = chosen;
      Runnable old = unpin;
      unpin = audio.pin(model);
      if (old != null)
        old.run();
      Log.info("realtime", "Transcription session " + id + " uses '" + Json.str(model, "name", "?") + "'");
    }

    private Map<String, Object> describe()
    {
      Map<String, Object> td = serverVad ? Json.map("type", "server_vad", "threshold", threshold,
          "prefix_padding_ms", prefixMs, "silence_duration_ms", silenceMs) : null;
      Map<String, Object> tr = Json.map("model", model == null ? modelName : Json.str(model, "name", null),
          "language", language, "prompt", prompt);
      if (ga)
        return Json.map("id", id, "object", "realtime.session", "type", "transcription",
            "audio", Json.map("input", Json.map(
                "format", "pcm16".equals(format) ? Json.map("type", "audio/pcm", "rate", inputRate)
                    : Json.map("type", "g711_ulaw".equals(format) ? "audio/pcmu" : "audio/pcma"),
                "transcription", tr, "turn_detection", td)));
      return Json.map("id", id, "object", "realtime.transcription_session",
          "input_audio_format", format, "input_audio_transcription", tr, "turn_detection", td);
    }

    // ---- audio ----------------------------------------------------------------

    private void append(String b64) throws Exception
    {
      if (model == null)
        chooseModel();
      byte[] raw;
      try
      {
        raw = Base64.getDecoder().decode(b64);
      }
      catch (IllegalArgumentException e)
      {
        throw new HttpError(400, "'audio' is not base64");
      }
      short[] in = decode(raw);
      short[] out = resampler.process(in);
      if (!serverVad)
      {
        utterance.add(out, 0, out.length);
        samplesSeen += out.length;
        if (utterance.size() >= MAX_UTTERANCE)
          commit();
        return;
      }
      short[] all = new short[pending.length + out.length];
      System.arraycopy(pending, 0, all, 0, pending.length);
      System.arraycopy(out, 0, all, pending.length, out.length);
      int off = 0;
      for (; off + FRAME <= all.length; off += FRAME)
        frame(all, off);
      pending = Arrays.copyOfRange(all, off, all.length);
    }

    private short[] decode(byte[] raw)
    {
      if ("pcm16".equals(format))
        return Pcm.fromLittleEndian(raw);
      boolean ulaw = "g711_ulaw".equals(format);
      short[] s = new short[raw.length];
      for (int i = 0; i < raw.length; i++)
        s[i] = ulaw ? Pcm.ulaw(raw[i]) : Pcm.alaw(raw[i]);
      return s;
    }

    /**
     * Voice activity, 20 ms at a time: loud enough above the room's own noise means speech. The
     * noise level follows the quietest recent frames, so a fan or a hum is learned and ignored.
     */
    private void frame(short[] a, int off) throws Exception
    {
      double sum = 0;
      for (int i = off; i < off + FRAME; i++)
        sum += (double) a[i] * a[i];
      double db = 10 * Math.log10(sum / FRAME / (32768.0 * 32768.0) + 1e-12);
      noiseDb = db < noiseDb ? noiseDb + (db - noiseDb) * 0.3 : noiseDb + 0.01;
      boolean loud = db > Math.max(noiseDb + 6 + 18 * threshold, -55);
      samplesSeen += FRAME;

      if (!speaking)
      {
        prefix.add(a, off, FRAME);
        prefix.keepLast(prefixMs * RATE / 1000 + 3 * FRAME);
        loudFrames = loud ? loudFrames + 1 : 0;
        if (loudFrames >= 3)
          startSpeech();
        return;
      }
      utterance.add(a, off, FRAME);
      quietSamples = loud ? 0 : quietSamples + FRAME;
      if (quietSamples >= silenceMs * RATE / 1000)
      {
        endSpeech(true);
        return;
      }
      if (utterance.size() >= MAX_UTTERANCE)
      {
        endSpeech(false);
        startSpeech();
        return;
      }
      long now = System.currentTimeMillis();
      if (utterance.size() >= RATE && now - lastPartial >= PARTIAL_MS && partialRunning.compareAndSet(false, true))
      {
        lastPartial = now;
        short[] snapshot = utterance.toArray();
        String item = itemId;
        worker.submit(() -> {
          try
          {
            partial(item, snapshot);
          }
          finally
          {
            partialRunning.set(false);
          }
        });
      }
    }

    private void startSpeech() throws Exception
    {
      speaking = true;
      quietSamples = 0;
      loudFrames = 0;
      itemId = newItemId();
      utterance.clear();
      utterance.add(prefix.toArray(), 0, prefix.size());
      prefix.clear();
      utteranceStart = samplesSeen - utterance.size();
      lastPartial = System.currentTimeMillis();
      emitted.put(itemId, new ArrayList<>());
      send(Json.map("type", "input_audio_buffer.speech_started", "audio_start_ms", ms(utteranceStart),
          "item_id", itemId));
    }

    private void endSpeech(boolean silent) throws Exception
    {
      speaking = false;
      if (silent)
        send(Json.map("type", "input_audio_buffer.speech_stopped", "audio_end_ms", ms(samplesSeen),
            "item_id", itemId));
      finish(itemId, utterance.toArray());
      utterance.clear();
    }

    /** Client-driven turns (turn_detection null, or a push-to-talk "send now"). */
    private void commit() throws Exception
    {
      if (speaking)
      {
        endSpeech(false);
        return;
      }
      if (utterance.size() == 0)
      {
        error("invalid_request_error", "input_audio_buffer_commit_empty", "the audio buffer is empty");
        return;
      }
      itemId = newItemId();
      emitted.put(itemId, new ArrayList<>());
      finish(itemId, utterance.toArray());
      utterance.clear();
    }

    private void finish(String item, short[] samples) throws Exception
    {
      send(Json.map("type", "input_audio_buffer.committed", "previous_item_id", previousItemId, "item_id", item));
      previousItemId = item;
      worker.submit(() -> complete(item, samples));
    }

    // ---- recognition (on the worker) -------------------------------------------

    /** Words already sent as deltas, per item. */
    private final Map<String, List<String>> emitted = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, List<String>> lastHypothesis = new java.util.concurrent.ConcurrentHashMap<>();

    private void partial(String item, short[] samples)
    {
      try
      {
        List<String> words = words(audio.transcribe(model, Pcm.wav(samples, RATE), language, contextPrompt()));
        List<String> before = lastHypothesis.put(item, words);
        if (before == null)
          return;
        int agreed = 0;
        while (agreed < words.size() && agreed < before.size() && words.get(agreed).equals(before.get(agreed)))
          agreed++;
        sendDelta(item, words.subList(0, agreed));
      }
      catch (Exception e)
      {
        Log.debug("realtime", id + " partial failed: " + e.getMessage());
      }
    }

    private void complete(String item, short[] samples)
    {
      try
      {
        String text = samples.length < MIN_SPEECH ? ""
            : audio.transcribe(model, Pcm.wav(samples, RATE), language, contextPrompt());
        sendDelta(item, words(text));
        lastHypothesis.remove(item);
        emitted.remove(item);
        send(Json.map("type", "conversation.item.input_audio_transcription.completed",
            "item_id", item, "content_index", 0, "transcript", text));
        if (!text.isEmpty())
        {
          context.append(context.length() == 0 ? "" : " ").append(text);
          if (context.length() > CONTEXT_CHARS)
            context.delete(0, context.length() - CONTEXT_CHARS);
        }
      }
      catch (Exception e)
      {
        Log.warn("realtime", id + " could not transcribe an utterance: " + e.getMessage());
        try
        {
          send(Json.map("type", "conversation.item.input_audio_transcription.failed", "item_id", item,
              "content_index", 0, "error", Json.map("type", "server_error", "message", String.valueOf(e.getMessage()))));
        }
        catch (Exception ignored)
        {
        }
      }
    }

    /** Send what {@code agreed} has beyond what was already sent, if it still starts the same way. */
    private void sendDelta(String item, List<String> agreed) throws Exception
    {
      List<String> sent = emitted.get(item);
      if (sent == null || agreed.size() <= sent.size() || !agreed.subList(0, sent.size()).equals(sent))
        return;
      List<String> fresh = agreed.subList(sent.size(), agreed.size());
      String delta = (sent.isEmpty() ? "" : " ") + String.join(" ", fresh);
      sent.addAll(fresh);
      send(Json.map("type", "conversation.item.input_audio_transcription.delta", "item_id", item,
          "content_index", 0, "delta", delta));
    }

    /** The session's own prompt, then what was said just before: Whisper reads the end most. */
    private String contextPrompt()
    {
      String p = (prompt == null ? "" : prompt.strip());
      return context.length() == 0 ? p : (p.isEmpty() ? "" : p + "\n") + context;
    }

    // ---- plumbing ------------------------------------------------------------------

    private List<String> words(String text)
    {
      return text.isBlank() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(text.strip().split("\\s+")));
    }

    private String newItemId()
    {
      return "item_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private long ms(long samples)
    {
      return samples * 1000 / RATE;
    }

    private void error(String type, String code, String message) throws Exception
    {
      send(Json.map("type", "error", "error", Json.map("type", type, "code", code, "message", message)));
    }

    private void send(Map<String, Object> event) throws Exception
    {
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("event_id", "event_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
      e.putAll(event);
      ws.sendText(Json.write(e));
    }
  }

  // ---- audio helpers ------------------------------------------------------------------

  /** A growable run of 16-bit samples. */
  private static final class ShortBuffer
  {
    private short[] a = new short[RATE];
    private int n;

    void add(short[] src, int off, int len)
    {
      if (n + len > a.length)
        a = Arrays.copyOf(a, Math.max(a.length * 2, n + len));
      System.arraycopy(src, off, a, n, len);
      n += len;
    }

    void keepLast(int len)
    {
      if (n > len)
      {
        System.arraycopy(a, n - len, a, 0, len);
        n = len;
      }
    }

    int size()
    {
      return n;
    }

    void clear()
    {
      n = 0;
    }

    short[] toArray()
    {
      return Arrays.copyOf(a, n);
    }
  }
}
