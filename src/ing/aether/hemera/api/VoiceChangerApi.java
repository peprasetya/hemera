package ing.aether.hemera.api;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;

import ing.aether.hemera.Log;
import ing.aether.hemera.config.Config;
import ing.aether.hemera.engine.Engines;
import ing.aether.hemera.engine.Instance;
import ing.aether.hemera.engine.InstanceManager;
import ing.aether.hemera.http.HttpError;
import ing.aether.hemera.http.Router;
import ing.aether.hemera.http.WebSocket;
import ing.aether.hemera.json.Json;

/**
 * Live voice changing (GET /v1/audio/voice-changer, WebSocket): speech in, the same speech in a
 * voice from the library out, about 150 ms behind.
 *
 * There is no OpenAI API for this, so the protocol is hemera's and kept small:
 *
 * <pre>
 * connect   ws://host:12434/v1/audio/voice-changer?model=meanvc2&amp;voice=maria&amp;sample_rate=48000
 * send      binary messages: 16-bit little-endian mono PCM at sample_rate, any length (20–100 ms is good)
 * receive   binary messages: the converted audio, same format and rate
 *           text messages: {"type": "session.created" | "session.updated" | "error", ...}
 * send      {"type": "session.update", "voice": "other"} to change voice without reconnecting
 * </pre>
 *
 * Behind it is audio.cpp's /v1/audio/speech/live, which streams a chunked request body and the
 * converted audio back on one connection at the same time. The JDK's HTTP client sends a whole
 * body before reading the answer, so hemera speaks that HTTP itself over a plain socket.
 */
public final class VoiceChangerApi
{
  private final Config cfg;
  private final InstanceManager im;
  private final LiveGate live;

  public VoiceChangerApi(Config cfg, InstanceManager im, AudioApi audio, Router router)
  {
    this.cfg = cfg;
    this.im = im;
    this.live = audio.live;
    router.add("GET", "/v1/audio/voice-changer", this::connect);
  }

  private void connect(HttpExchange ex) throws Exception
  {
    if (!WebSocket.isUpgrade(ex))
      throw new HttpError(426, "/v1/audio/voice-changer is a WebSocket — connect with ws://, e.g. "
          + "new WebSocket(\"ws://host:12434/v1/audio/voice-changer?model=meanvc2&voice=maria&sample_rate=48000\")");
    String rawModel = Router.query(ex, "model");
    Map<String, Object> model = rawModel == null ? firstVoiceChanger() : cfg.findModel(rawModel);
    if (model == null)
      throw new HttpError(404, "model '" + rawModel + "' not found");
    if (!Engines.isVoiceChangerModel(cfg, model))
      throw new HttpError(400, "model '" + rawModel + "' is not a voice-changer model (an audio.cpp model with \"task\": \"vc\")");
    if (!"streaming".equals(Json.str(model, "mode", "offline")))
      throw new HttpError(400, "model '" + Json.str(model, "name", "?") + "' needs \"mode\": \"streaming\" to change voices live");
    String voice = Router.query(ex, "voice");
    requireVoice(voice);
    int rate = parseRate(Router.query(ex, "sample_rate"));

    Instance inst = im.ensure(model);
    try (WebSocket ws = WebSocket.accept(ex))
    {
      inst.beginRequest();
      Runnable done = live.enter("voice changer");
      try
      {
        new Session(ws, inst, model, voice, rate).run();
      }
      finally
      {
        done.run();
        inst.endRequest();
      }
    }
  }

  private Map<String, Object> firstVoiceChanger()
  {
    for (Map<String, Object> m : cfg.models())
      if (Engines.isVoiceChangerModel(cfg, m))
        return m;
    throw new HttpError(404, "no voice-changer model is configured");
  }

  private void requireVoice(String voice)
  {
    if (voice == null || voice.isBlank())
      throw new HttpError(400, "missing 'voice' — the name of a voice in the library (GET /v1/audio/voices)");
    if (!voice.matches("[a-z0-9][a-z0-9_-]{0,63}")
        || !java.nio.file.Files.isRegularFile(cfg.resolve(Json.str(cfg.data(), "voicesDir", "voices")).resolve(voice + ".wav")))
      throw new HttpError(404, "voice '" + voice + "' is not in the library (GET /v1/audio/voices lists them)");
  }

  private static int parseRate(String raw)
  {
    try
    {
      int r = raw == null ? 24000 : Integer.parseInt(raw);
      if (r < 8000 || r > 48000)
        throw new HttpError(400, "sample_rate must be between 8000 and 48000");
      return r;
    }
    catch (NumberFormatException e)
    {
      throw new HttpError(400, "sample_rate must be a number");
    }
  }

  private final class Session
  {
    private final WebSocket ws;
    private final Instance inst;
    private final Map<String, Object> model;
    private final int rate;
    /** The rate the model speaks at; MeanVC2 works at 16 kHz. */
    private final int modelRate;
    private String voice;
    private Link link;

    Session(WebSocket ws, Instance inst, Map<String, Object> model, String voice, int rate)
    {
      this.ws = ws;
      this.inst = inst;
      this.model = model;
      this.voice = voice;
      this.rate = rate;
      this.modelRate = (int) Json.num(model, "outputSampleRate", 16000);
    }

    void run() throws Exception
    {
      link = new Link();
      send(Json.map("type", "session.created", "model", Json.str(model, "name", "?"), "voice", voice,
          "sample_rate", rate, "format", "s16le"));
      Log.info("voice", "Voice changer on '" + Json.str(model, "name", "?") + "' speaking as '" + voice + "'");
      try
      {
        WebSocket.Message m;
        byte[] odd = new byte[0];
        while ((m = ws.read()) != null)
        {
          if (!m.text())
          {
            // whole samples only; a byte left over waits for the next message
            byte[] data = m.data();
            if (odd.length > 0)
            {
              byte[] joined = new byte[odd.length + data.length];
              System.arraycopy(odd, 0, joined, 0, odd.length);
              System.arraycopy(data, 0, joined, odd.length, data.length);
              data = joined;
            }
            int whole = data.length & ~1;
            odd = java.util.Arrays.copyOfRange(data, whole, data.length);
            if (whole > 0)
              link.write(java.util.Arrays.copyOf(data, whole));
            continue;
          }
          Map<String, Object> event;
          try
          {
            event = Json.parseObj(m.string());
          }
          catch (RuntimeException e)
          {
            error("not a JSON event: " + e.getMessage());
            continue;
          }
          if ("session.update".equals(Json.str(event, "type", "")) && event.containsKey("voice"))
          {
            try
            {
              requireVoice(Json.str(event, "voice", null));
            }
            catch (HttpError e)
            {
              error(e.getMessage());
              continue;
            }
            voice = Json.str(event, "voice", voice);
            link.finish();
            link = new Link();
            send(Json.map("type", "session.updated", "voice", voice));
          }
          else
            error("only binary audio and {\"type\": \"session.update\", \"voice\": ...} are understood here");
        }
      }
      catch (IOException e)
      {
        Log.debug("voice", "voice changer ended: " + e.getMessage());
      }
      finally
      {
        link.finish();
        Log.info("voice", "Voice changer closed");
      }
    }

    private void send(Map<String, Object> event) throws IOException
    {
      ws.sendText(Json.write(event));
    }

    private void error(String message) throws IOException
    {
      send(Json.map("type", "error", "error", Json.map("message", message)));
    }

    /**
     * One live request to audio.cpp: a chunked request body of the speaker's audio going in, the
     * converted audio coming back on the same connection while it does.
     */
    private final class Link
    {
      private final Socket socket;
      private final OutputStream out;
      private volatile boolean finished;

      Link() throws IOException
      {
        socket = new Socket("127.0.0.1", inst.port);
        socket.setTcpNoDelay(true);
        out = socket.getOutputStream();
        String query = "model=" + enc(Json.str(model, "name", "")) + "&voice=" + enc(voice) + "&sample_rate=" + rate
            + "&channels=1&sample_format=s16le&stream_format=audio";
        out.write(("POST /v1/audio/speech/live?" + query + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
            + "Content-Type: application/octet-stream\r\nTransfer-Encoding: chunked\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream in = new BufferedInputStream(socket.getInputStream());
        Thread.ofVirtual().name("voice-changer-out").start(() -> relay(in));
      }

      void write(byte[] pcm) throws IOException
      {
        if (finished)
          return;
        out.write((Integer.toHexString(pcm.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(pcm);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
      }

      /** End the request properly (a cut connection is an error to audio.cpp) and let go. */
      void finish()
      {
        if (finished)
          return;
        finished = true;
        try
        {
          out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
          out.flush();
          socket.shutdownOutput();
        }
        catch (IOException ignored)
        {
        }
        Thread.ofVirtual().start(() -> {
          try
          {
            Thread.sleep(2000); // let the last converted audio drain out
            socket.close();
          }
          catch (InterruptedException | IOException ignored)
          {
          }
        });
      }

      /** Read audio.cpp's answer: status, headers, then chunked PCM at the model's rate. */
      private void relay(InputStream in)
      {
        Pcm.Resampler back = new Pcm.Resampler(modelRate, rate);
        try
        {
          String status = line(in);
          int contentLength = -1;
          boolean chunked = false;
          String h;
          while (!(h = line(in)).isEmpty())
          {
            String lower = h.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("transfer-encoding:") && lower.contains("chunked"))
              chunked = true;
            else if (lower.startsWith("content-length:"))
              contentLength = Integer.parseInt(h.substring(15).trim());
          }
          if (!status.contains(" 200 "))
          {
            byte[] body = contentLength >= 0 ? in.readNBytes(contentLength) : in.readNBytes(4096);
            String msg = new String(body, StandardCharsets.UTF_8);
            Log.warn("voice", "audio.cpp refused the voice changer: " + status + " " + msg);
            error("the voice-changer engine refused: " + msg);
            return;
          }
          byte[] odd = new byte[0];
          while (true)
          {
            byte[] piece;
            if (chunked)
            {
              String size = line(in).trim();
              int semi = size.indexOf(';');
              int n = Integer.parseInt(semi >= 0 ? size.substring(0, semi) : size, 16);
              if (n == 0)
                break;
              piece = in.readNBytes(n);
              line(in);
            }
            else
            {
              piece = in.readNBytes(Math.max(2, Math.min(8192, in.available())));
              if (piece.length == 0)
                break;
            }
            ByteArrayOutputStream joined = new ByteArrayOutputStream(odd.length + piece.length);
            joined.write(odd);
            joined.write(piece);
            byte[] all = joined.toByteArray();
            int whole = all.length & ~1;
            odd = java.util.Arrays.copyOfRange(all, whole, all.length);
            short[] converted = back.process(Pcm.fromLittleEndian(java.util.Arrays.copyOf(all, whole)));
            if (converted.length > 0 && ws.isOpen())
              ws.sendBinary(Pcm.toLittleEndian(converted));
          }
        }
        catch (IOException | RuntimeException e)
        {
          if (!finished)
            Log.debug("voice", "voice changer stream ended: " + e.getMessage());
        }
      }
    }
  }

  private static String line(InputStream in) throws IOException
  {
    StringBuilder sb = new StringBuilder();
    int c;
    while ((c = in.read()) >= 0 && c != '\n')
      if (c != '\r')
        sb.append((char) c);
    if (c < 0 && sb.length() == 0)
      throw new IOException("connection closed");
    return sb.toString();
  }

  private static String enc(String s)
  {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
