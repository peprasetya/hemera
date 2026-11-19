package ing.aether.hemera.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * offered.
 *
 * The OCR engine is a separate program hemera starts like any other (its model entry says
 * "task": "ocr"), and it answers POST /v1/ocr itself: it checks the request, reads the picture
 * and gives the answer its shape. This side only picks the model (the options' "model", else
 * "ocrModel"), starts its engine, and passes the request and the answer through unchanged. It
 * also stops the engine's work when the client hangs up or the reading takes too long.
 *
 * Someone is waiting with the pointer on a picture: an OCR request is never held behind
 * background work or live sessions. Pictures and text are not kept, and never logged: the log
 * has sizes, statuses and times.
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

  private void read(HttpExchange ex) throws Exception
  {
    long started = System.currentTimeMillis();
    HangUp hangUp = HangUp.watch(ex);
    byte[] body = readCapped(ex);
    String type = ex.getRequestHeaders().getFirst("Content-Type");
    Map<String, Object> model = pick(modelAsked(type, body));
    String name = Json.str(model, "name", "?");
    Instance inst = running(ex, model, hangUp);
    if (inst == null)
      return; // the client hung up while the model loaded

    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + inst.port + "/v1/ocr"))
        .timeout(Duration.ofSeconds(timeoutSeconds() + 5))
        .header("Content-Type", type != null ? type : "application/octet-stream")
        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();

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
    Log.info("ocr", "'" + name + "': " + body.length + " bytes, answered " + resp.statusCode() + " ("
        + resp.body().length + " bytes) in " + (System.currentTimeMillis() - started) + " ms");
    pass(ex, resp, hangUp.spoiled());
  }

  /**
   * The model the options name, if any: the one thing hemera reads of the request, to know where
   * it goes. Anything else wrong with it is the engine's to say.
   */
  private static String modelAsked(String type, byte[] body)
  {
    String boundary = Multipart.boundary(type);
    if (boundary == null)
      return null;
    try
    {
      for (Multipart.Part p : Multipart.parse(body, boundary))
        if ("options".equals(p.name()))
          return Json.parseObj(p.value()).get("model") instanceof String s ? s : null;
    }
    catch (RuntimeException | IOException e)
    {
      return null;
    }
    return null;
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

  /**
   * The engine's answer as it is: status, type and body. Written here rather than through
   * Router.sendJson, whose trace logging would record the text.
   */
  private static void pass(HttpExchange ex, HttpResponse<byte[]> resp, boolean close) throws IOException
  {
    Router.cors(ex);
    ex.getResponseHeaders().set("Content-Type", resp.headers().firstValue("Content-Type").orElse("application/json"));
    if (close)
      ex.getResponseHeaders().set("Connection", "close");
    ex.sendResponseHeaders(resp.statusCode(), resp.body().length);
    try (OutputStream os = ex.getResponseBody())
    {
      os.write(resp.body());
    }
  }
}
