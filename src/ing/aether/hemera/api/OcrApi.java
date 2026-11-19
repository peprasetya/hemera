package ing.aether.hemera.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.sun.net.httpserver.HttpExchange;

import ing.aether.hemera.Log;
import ing.aether.hemera.config.Config;
import ing.aether.hemera.engine.Engines;
import ing.aether.hemera.engine.Instance;
import ing.aether.hemera.engine.InstanceManager;
import ing.aether.hemera.http.HangUp;
import ing.aether.hemera.http.HttpError;
import ing.aether.hemera.http.Multipart;
import ing.aether.hemera.http.Router;
import ing.aether.hemera.json.Json;

/**
 * Text in pictures, with where it is: POST /v1/ocr reads a picture, GET /v1/ocr says what is
 * offered. The reading happens in an OCR engine hemera starts like any other (an entry with
 * "task": "ocr"); this side checks the request, picks the model, and gives the answer its final
 * shape: each line's characters as grapheme clusters, its words, and the text in reading order.
 *
 * Someone is waiting with the pointer on a picture: an OCR request is never held behind
 * background work or live sessions. Pictures and text are not kept, and never logged: the log
 * has sizes, counts and times.
 */
public final class OcrApi
{
  static final long MAX_BYTES = 50L * 1024 * 1024;
  static final long MAX_PIXELS = 50_000_000L;

  private static final HttpClient ENGINE = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();

  private final Config cfg;
  private final InstanceManager im;

  public OcrApi(Config cfg, InstanceManager im, Router router)
  {
    this.cfg = cfg;
    this.im = im;
    router.add("POST", "/v1/ocr", this::read);
    router.add("GET", "/v1/ocr", this::describe);
  }

  // ---- what is offered ------------------------------------------------------------------------

  private void describe(HttpExchange ex) throws IOException
  {
    List<Object> models = new ArrayList<>();
    for (Map<String, Object> m : ocrModels())
      models.add(Json.map("name", Json.str(m, "name", "?"), "ready", im.get(Json.str(m, "name", "")) != null,
          "scripts", Json.strList(m, "scripts"), "char_boxes", Json.str(m, "charBoxes", "exact"),
          "licence", Json.str(m, "licence", "")));
    Map<String, Object> def = defaultModel();
    String name = def == null ? null : Json.str(def, "name", null);
    Router.sendJson(ex, 200, Json.map("version", 1, "ready", name != null && im.get(name) != null,
        "default_model", name, "max_pixels", MAX_PIXELS, "max_bytes", MAX_BYTES, "models", models));
  }

  private List<Map<String, Object>> ocrModels()
  {
    return cfg.models().stream().filter(m -> Engines.isOcrModel(cfg, m)).toList();
  }

  /** The configured "ocrModel", else the first OCR model. */
  private Map<String, Object> defaultModel()
  {
    List<Map<String, Object>> all = ocrModels();
    String preferred = Json.str(cfg.data(), "ocrModel", "");
    for (Map<String, Object> m : all)
      if (preferred.equals(Json.str(m, "name", "")))
        return m;
    return all.isEmpty() ? null : all.get(0);
  }

  // ---- reading --------------------------------------------------------------------------------

  private record Options(List<String> languages, String granularity, List<Double> region, Object orientation, String model)
  {
  }

  private void read(HttpExchange ex) throws Exception
  {
    long started = System.currentTimeMillis();
    HangUp hangUp = HangUp.watch(ex);
    String boundary = Multipart.boundary(ex.getRequestHeaders().getFirst("Content-Type"));
    if (boundary == null)
      throw new HttpError(400, "send multipart/form-data with the picture in a part named 'image'");
    byte[] image = null;
    String optionsJson = null;
    for (Multipart.Part p : Multipart.parse(readCapped(ex), boundary))
    {
      if ("image".equals(p.name()) || "file".equals(p.name())) // "file", as hemera's other uploads name it
        image = p.data();
      else if ("options".equals(p.name()))
        optionsJson = new String(p.data(), StandardCharsets.UTF_8);
    }
    if (image == null || image.length == 0)
      throw new HttpError(400, "the picture is missing: send it in a part named 'image'");
    if (!pictureFormat(image))
      throw new HttpError(400, "the picture is not PNG, JPEG, WebP or TIFF");
    Options o = options(optionsJson);

    Map<String, Object> model = pick(o.model());
    String name = Json.str(model, "name", "?");
    Instance inst = running(ex, model, hangUp);
    if (inst == null)
      return; // the client hung up while the model loaded

    Map<String, Object> engineOptions = new LinkedHashMap<>();
    engineOptions.put("languages", o.languages());
    engineOptions.put("orientation", o.orientation());
    if (o.region() != null)
      engineOptions.put("region", o.region());
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + inst.port + "/ocr"))
        .timeout(Duration.ofSeconds(timeoutSeconds() + 5))
        .header("Content-Type", "application/octet-stream")
        .header("X-Options", Json.write(engineOptions))
        .POST(HttpRequest.BodyPublishers.ofByteArray(image)).build();

    inst.beginRequest();
    HttpResponse<byte[]> resp;
    try
    {
      CompletableFuture<HttpResponse<byte[]>> pending = ENGINE.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray());
      long deadline = started + timeoutSeconds() * 1000L;
      while (true)
      {
        try
        {
          resp = pending.get(150, TimeUnit.MILLISECONDS);
          break;
        }
        catch (TimeoutException e)
        {
          if (hangUp.gone())
          {
            // closing the connection to the engine is what tells it to stop
            pending.cancel(true);
            Log.info("ocr", "'" + name + "': the client hung up after " + (System.currentTimeMillis() - started) + " ms; stopped");
            return;
          }
          if (System.currentTimeMillis() > deadline)
          {
            pending.cancel(true);
            throw new HttpError(504, "reading the picture took longer than " + timeoutSeconds() + " s");
          }
        }
        catch (java.util.concurrent.ExecutionException e)
        {
          ex.getResponseHeaders().set("Retry-After", "2");
          throw new HttpError(503, "the OCR engine is not answering (" + e.getCause().getClass().getSimpleName()
              + "); try again");
        }
      }
    }
    finally
    {
      inst.endRequest();
      inst.touch();
    }

    Map<String, Object> body;
    try
    {
      body = Json.parseObj(new String(resp.body(), StandardCharsets.UTF_8));
    }
    catch (IllegalArgumentException e)
    {
      throw new HttpError(502, "the OCR engine answered something that is not JSON");
    }
    if (resp.statusCode() != 200)
    {
      Object err = body.get("error");
      int status = resp.statusCode() == 400 || resp.statusCode() == 413 ? resp.statusCode() : 500;
      throw new HttpError(status, err == null ? "the OCR engine failed (" + resp.statusCode() + ")" : String.valueOf(err));
    }

    Map<String, Object> answer = shape(body, name, o.granularity(), System.currentTimeMillis() - started);
    Map<String, Object> size = Json.obj(answer.get("image"));
    int lines = Json.arr(answer.get("blocks")).stream().mapToInt(b -> Json.arr(Json.obj(b).get("lines")).size()).sum();
    Log.info("ocr", "'" + name + "' read " + Json.num(size, "width", 0) + "x" + Json.num(size, "height", 0) + " ("
        + image.length + " bytes): " + lines + " lines in " + Json.num(Json.obj(answer.get("timing_ms")), "total", 0) + " ms");
    send(ex, answer, hangUp.spoiled());
  }

  private long timeoutSeconds()
  {
    return Json.num(cfg.data(), "ocrTimeoutSeconds", 60);
  }

  private Map<String, Object> pick(String name)
  {
    if (name != null && !name.isBlank())
    {
      Map<String, Object> m = cfg.findModel(name);
      if (m == null || !Engines.isOcrModel(cfg, m))
        throw new HttpError(400, "'" + name + "' is not an OCR model here; GET /v1/ocr lists them");
      return m;
    }
    Map<String, Object> m = defaultModel();
    if (m == null)
      throw new HttpError(503, "no OCR model is configured");
    return m;
  }

  /**
   * The model's engine, started if it was not running. A start that takes longer than a few
   * seconds answers 503 with Retry-After, and goes on in the background.
   */
  private Instance running(HttpExchange ex, Map<String, Object> model, HangUp hangUp) throws Exception
  {
    String name = Json.str(model, "name", "?");
    if (im.get(name) != null)
      return im.ensure(model);
    im.loadAsync(model);
    long until = System.currentTimeMillis() + Json.num(cfg.data(), "ocrLoadWaitSeconds", 10) * 1000L;
    while (System.currentTimeMillis() < until)
    {
      Instance inst = im.get(name);
      if (inst != null)
        return im.ensure(model);
      for (InstanceManager.LoadState s : im.loadStates())
        if (s.name().equals(name) && s.failed())
        {
          im.clearLoadState(name);
          throw new HttpError(500, "the OCR model '" + name + "' failed to load: " + s.error());
        }
      if (hangUp.gone())
        return null;
      Thread.sleep(200);
    }
    ex.getResponseHeaders().set("Retry-After", "3");
    throw new HttpError(503, "the OCR model '" + name + "' is loading; try again in a few seconds");
  }

  private static byte[] readCapped(HttpExchange ex) throws IOException
  {
    String declared = ex.getRequestHeaders().getFirst("Content-Length");
    if (declared != null)
    {
      try
      {
        if (Long.parseLong(declared.trim()) > MAX_BYTES + 64 * 1024)
          throw new HttpError(413, "the request is larger than " + MAX_BYTES + " bytes; send a picture of at most "
              + MAX_BYTES / 1024 / 1024 + " MB and " + MAX_PIXELS / 1_000_000 + " million pixels");
      }
      catch (NumberFormatException ignored)
      {
      }
    }
    try (InputStream in = ex.getRequestBody())
    {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[1 << 16];
      int n;
      while ((n = in.read(buf)) > 0)
      {
        out.write(buf, 0, n);
        if (out.size() > MAX_BYTES + 64 * 1024)
          throw new HttpError(413, "the request is larger than " + MAX_BYTES + " bytes");
      }
      return out.toByteArray();
    }
  }

  private static boolean pictureFormat(byte[] b)
  {
    if (b.length < 12)
      return false;
    boolean png = (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    boolean jpeg = (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8;
    boolean webp = b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    boolean tiff = (b[0] == 'I' && b[1] == 'I' && b[2] == 42 && b[3] == 0) || (b[0] == 'M' && b[1] == 'M' && b[2] == 0 && b[3] == 42);
    return png || jpeg || webp || tiff;
  }

  private static Options options(String json)
  {
    Map<String, Object> m = new LinkedHashMap<>();
    if (json != null && !json.isBlank())
    {
      try
      {
        m = Json.parseObj(json);
      }
      catch (IllegalArgumentException e)
      {
        throw new HttpError(400, "options are not a JSON object: " + e.getMessage());
      }
    }
    List<String> languages = new ArrayList<>();
    Object langs = m.get("languages");
    if (langs != null)
    {
      if (!(langs instanceof List<?> l))
        throw new HttpError(400, "languages must be a list of BCP-47 tags, or [\"auto\"]");
      for (Object x : l)
      {
        if (!(x instanceof String s) || !s.matches("[A-Za-z0-9-]{1,35}"))
          throw new HttpError(400, "languages must be a list of BCP-47 tags, or [\"auto\"]");
        languages.add(s);
      }
    }
    if (languages.isEmpty())
      languages.add("auto");
    String granularity = Json.str(m, "granularity", "chars");
    if (!List.of("lines", "words", "chars").contains(granularity))
      throw new HttpError(400, "granularity must be \"lines\", \"words\" or \"chars\"");
    List<Double> region = null;
    Object r = m.get("region");
    if (r != null)
    {
      if (!(r instanceof List<?> l) || l.size() != 4 || !l.stream().allMatch(v -> v instanceof Number))
        throw new HttpError(400, "region must be [x, y, w, h] in the picture's pixels, or null");
      region = l.stream().map(v -> ((Number) v).doubleValue()).toList();
      if (region.get(2) <= 0 || region.get(3) <= 0)
        throw new HttpError(400, "region's width and height must be positive");
    }
    Object orientation = m.getOrDefault("orientation", "auto");
    if (orientation == null)
      orientation = "auto";
    if (orientation instanceof Number n && List.of(0, 90, 180, 270).contains(n.intValue()) && n.doubleValue() == n.intValue())
      orientation = n.intValue();
    else if (orientation instanceof String s && s.matches("0|90|180|270"))
      orientation = Integer.parseInt(s);
    else if (!"auto".equals(orientation))
      throw new HttpError(400, "orientation must be \"auto\", 0, 90, 180 or 270");
    Object model = m.get("model");
    if (model != null && !(model instanceof String))
      throw new HttpError(400, "model must be a model's name, or null");
    return new Options(languages, granularity, region, orientation, (String) model);
  }

  // ---- the answer's shape ---------------------------------------------------------------------

  /**
   * The engine's answer, with grapheme clusters and words. The engine gives each code point's
   * span along its line; a grapheme cluster (Unicode's extended grapheme cluster, as Java's
   * BreakIterator finds it) spans its code points.
   */
  static Map<String, Object> shape(Map<String, Object> engine, String model, String granularity, long totalMs)
  {
    List<Object> blocks = new ArrayList<>();
    StringBuilder all = new StringBuilder();
    for (Object bo : Json.arr(engine.get("blocks")))
    {
      Map<String, Object> b = Json.obj(bo);
      List<Object> lines = new ArrayList<>();
      StringBuilder blockText = new StringBuilder();
      for (Object lo : Json.arr(b.get("lines")))
      {
        Map<String, Object> l = Json.obj(lo);
        lines.add(line(l, granularity));
        if (blockText.length() > 0)
          blockText.append('\n');
        blockText.append(Json.str(l, "text", ""));
      }
      if (lines.isEmpty())
        continue;
      blocks.add(Json.map("polygon", b.get("polygon"), "lines", lines));
      if (all.length() > 0)
        all.append("\n\n");
      all.append(blockText);
    }
    Map<String, Object> timing = new LinkedHashMap<>();
    timing.put("total", totalMs);
    for (Map.Entry<String, Object> e : Json.obj(engine.get("timing_ms")).entrySet())
      if (!e.getKey().equals("total"))
        timing.put(e.getKey(), e.getValue());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("version", 1);
    out.put("model", model);
    out.put("image", engine.get("image"));
    out.put("orientation", engine.getOrDefault("orientation", 0));
    out.put("timing_ms", timing);
    out.put("text", all.toString());
    out.put("blocks", blocks);
    return out;
  }

  private static Map<String, Object> line(Map<String, Object> l, String granularity)
  {
    String text = Json.str(l, "text", "");
    String direction = Json.str(l, "direction", "ltr");
    List<double[]> poly = points(l.get("polygon"));
    double length = poly.size() == 4 ? dist(poly.get(0), "rtl".equals(direction) ? poly.get(3) : poly.get(1)) : 0;

    // grapheme clusters, as [start, end) code point indices
    BreakIterator it = BreakIterator.getCharacterInstance(Locale.ROOT);
    it.setText(text);
    List<int[]> clusters = new ArrayList<>();
    for (int s = it.first(), e = it.next(); e != BreakIterator.DONE; s = e, e = it.next())
      clusters.add(new int[] { text.codePointCount(0, s), text.codePointCount(0, e), s, e });

    List<double[]> cps = new ArrayList<>();
    for (Object c : Json.arr(l.get("cps")))
    {
      List<Object> p = Json.arr(c);
      if (p.size() == 2 && p.get(0) instanceof Number a && p.get(1) instanceof Number b)
        cps.add(new double[] { a.doubleValue(), b.doubleValue() });
    }
    int codePoints = text.codePointCount(0, text.length());
    boolean estimated = Json.bool(l, "chars_estimated", false) || cps.size() != codePoints;
    List<Double> cpConf = new ArrayList<>();
    for (Object c : Json.arr(l.get("cp_conf")))
      if (c instanceof Number n)
        cpConf.add(n.doubleValue());
    if (cpConf.size() != codePoints)
      cpConf = null; // words then carry the line's confidence
    List<double[]> chars = new ArrayList<>();
    for (int i = 0; i < clusters.size(); i++)
    {
      int[] c = clusters.get(i);
      if (!estimated)
      {
        double s = Double.MAX_VALUE, e = -Double.MAX_VALUE;
        for (int k = c[0]; k < c[1]; k++)
        {
          s = Math.min(s, cps.get(k)[0]);
          e = Math.max(e, cps.get(k)[1]);
        }
        chars.add(new double[] { s, e });
      }
      else
      {
        // no positions from the reader: spread the clusters evenly along the line
        double w = length / Math.max(1, clusters.size());
        chars.add(new double[] { i * w, (i + 1) * w });
      }
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("text", text);
    out.put("polygon", l.get("polygon"));
    out.put("angle", l.getOrDefault("angle", 0.0));
    out.put("direction", direction);
    out.put("script", l.get("script"));
    out.put("language", l.get("language"));
    out.put("confidence", l.get("confidence"));
    if (!granularity.equals("lines"))
    {
      List<Object> words = new ArrayList<>();
      int start = -1;
      for (int i = 0; i <= clusters.size(); i++)
      {
        boolean space = i == clusters.size() || isSpace(text.substring(clusters.get(i)[2], clusters.get(i)[3]));
        if (!space && start < 0)
          start = i;
        if (space && start >= 0)
        {
          double s = Double.MAX_VALUE, e = -Double.MAX_VALUE;
          for (int k = start; k < i; k++)
          {
            s = Math.min(s, chars.get(k)[0]);
            e = Math.max(e, chars.get(k)[1]);
          }
          Object confidence = l.get("confidence");
          if (cpConf != null)
          {
            double sum = 0;
            int from = clusters.get(start)[0], to = clusters.get(i - 1)[1];
            for (int k = from; k < to; k++)
              sum += cpConf.get(k);
            confidence = Math.round(sum / Math.max(1, to - from) * 1000) / 1000.0;
          }
          words.add(Json.map("text", text.substring(clusters.get(start)[2], clusters.get(i - 1)[3]),
              "polygon", segment(poly, direction, s, e, length), "start", start, "end", i,
              "confidence", confidence));
          start = -1;
        }
      }
      out.put("words", words);
    }
    if (granularity.equals("chars"))
    {
      List<Object> spans = new ArrayList<>();
      for (double[] c : chars)
        spans.add(List.of(round(c[0]), round(c[1])));
      out.put("chars", spans);
      out.put("chars_estimated", estimated);
    }
    return out;
  }

  private static boolean isSpace(String cluster)
  {
    return cluster.codePoints().allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp));
  }

  /**
   * The part of a line between two distances along its reading direction, as a polygon in the
   * line's own convention: clockwise, from where the part begins on the line's top side.
   */
  private static List<Object> segment(List<double[]> poly, String direction, double from, double to, double length)
  {
    if (poly.size() != 4 || length <= 0)
      return List.of();
    double a = Math.max(0, Math.min(1, from / length)), b = Math.max(0, Math.min(1, to / length));
    double[] p0 = poly.get(0), p1 = poly.get(1), p2 = poly.get(2), p3 = poly.get(3);
    if ("rtl".equals(direction))
    {
      // top side runs p0 -> p3 (right to left), bottom side p1 -> p2
      return List.of(pt(lerp(p0, p3, a)), pt(lerp(p1, p2, a)), pt(lerp(p1, p2, b)), pt(lerp(p0, p3, b)));
    }
    // top side runs p0 -> p1, bottom side p3 -> p2
    return List.of(pt(lerp(p0, p1, a)), pt(lerp(p0, p1, b)), pt(lerp(p3, p2, b)), pt(lerp(p3, p2, a)));
  }

  private static double[] lerp(double[] p, double[] q, double t)
  {
    return new double[] { p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t };
  }

  private static List<Object> pt(double[] p)
  {
    return List.of(round(p[0]), round(p[1]));
  }

  private static double round(double v)
  {
    return Math.round(v * 10) / 10.0;
  }

  private static double dist(double[] p, double[] q)
  {
    return Math.hypot(q[0] - p[0], q[1] - p[1]);
  }

  private static List<double[]> points(Object polygon)
  {
    List<double[]> out = new ArrayList<>();
    for (Object o : Json.arr(polygon))
    {
      List<Object> p = Json.arr(o);
      if (p.size() == 2 && p.get(0) instanceof Number x && p.get(1) instanceof Number y)
        out.add(new double[] { x.doubleValue(), y.doubleValue() });
    }
    return out;
  }

  /** Written here rather than through Router.sendJson, whose trace logging would record the text. */
  private static void send(HttpExchange ex, Map<String, Object> answer, boolean close) throws IOException
  {
    byte[] bytes = Json.write(answer).getBytes(StandardCharsets.UTF_8);
    Router.cors(ex);
    ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    if (close)
      ex.getResponseHeaders().set("Connection", "close");
    ex.sendResponseHeaders(200, bytes.length);
    try (OutputStream os = ex.getResponseBody())
    {
      os.write(bytes);
    }
  }
}
