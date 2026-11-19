package ing.aether.hemera.api;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;

import ing.aether.hemera.Log;
import ing.aether.hemera.config.Config;
import ing.aether.hemera.engine.Engines;
import ing.aether.hemera.engine.Instance;
import ing.aether.hemera.engine.InstanceManager;
import ing.aether.hemera.http.HangUp;
import ing.aether.hemera.http.HttpError;
import ing.aether.hemera.http.Router;
import ing.aether.hemera.json.Json;

/**
 * Text to motion: words in, a few seconds of a person's whole-body motion out.
 *
 * The motion engine is a separate program hemera starts like any other (its model entry says
 * "task": "motion"), and it answers the whole API itself: POST /v1/motion/generations starts a
 * clip, GET .../{id} reports on it, GET .../{id}/content?format=json|glb|bvh fetches it, DELETE
 * drops it. This side only picks the model, starts its engine, and passes requests and answers
 * through unchanged. It remembers which engine holds which clip, and keeps that engine from being
 * stopped while it makes one. Prompts are not logged, here or by the engine.
 */
public final class MotionApi
{
  private static final String BASE = "/v1/motion/generations";
  private static final int MAX_BODY = 64 * 1024;
  private static final HttpClient ENGINE = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();

  private final Config cfg;
  private final InstanceManager im;
  /** generation id -> the model whose engine holds it */
  private final Map<String, String> owners = new ConcurrentHashMap<>();

  public MotionApi(Config cfg, InstanceManager im, Router router)
  {
    this.cfg = cfg;
    this.im = im;
    router.add("POST", BASE, this::create);
    router.add("GET", BASE, this::list);
    router.addPrefix("GET", BASE + "/", this::forward);
    router.addPrefix("DELETE", BASE + "/", this::forward);
  }

  private List<Map<String, Object>> motionModels()
  {
    return cfg.models().stream().filter(m -> Engines.isMotionModel(cfg, m)).toList();
  }

  /** The model asked for, else the configured "motionModel", else the first motion model. */
  private Map<String, Object> pick(String raw)
  {
    if (raw == null || raw.isBlank())
    {
      List<Map<String, Object>> all = motionModels();
      String preferred = Json.str(cfg.data(), "motionModel", "");
      for (Map<String, Object> m : all)
        if (preferred.equals(Json.str(m, "name", "")))
          return m;
      if (all.isEmpty())
        throw new HttpError(503, "no motion model is set up on this hemera");
      return all.get(0);
    }
    Map<String, Object> m = cfg.findModel(ProxyDispatcher.resolveName(cfg, raw));
    if (m == null)
      throw new HttpError(404, "model '" + raw + "' not found — GET /v1/models lists the motion models (kind \"motion\")");
    if (!Engines.isMotionModel(cfg, m))
      throw new HttpError(400, "model '" + raw + "' does not make motion");
    return m;
  }

  private void create(HttpExchange ex) throws Exception
  {
    byte[] body = readCapped(ex);
    Map<String, Object> req;
    try
    {
      req = Json.parseObj(new String(body, StandardCharsets.UTF_8));
    }
    catch (RuntimeException e)
    {
      throw new HttpError(400, "the body is not a JSON object"); // nothing of it repeated: it holds the prompt
    }
    Map<String, Object> model = pick(Json.str(req, "model", null));
    String name = Json.str(model, "name", "?");
    Instance inst = running(ex, model, HangUp.watch(ex));
    if (inst == null)
      return; // the client hung up while the model loaded
    HttpResponse<byte[]> resp = call(inst, "POST", BASE, body);
    if (resp.statusCode() == 200)
    {
      String id = idOf(resp.body());
      if (id != null)
      {
        owners.put(id, name);
        keepBusy(inst, id);
        Log.info("motion", "'" + name + "': " + id + " started (" + body.length + " bytes asked)");
      }
    }
    pass(ex, resp);
  }

  private void list(HttpExchange ex) throws Exception
  {
    List<Map<String, Object>> data = new ArrayList<>();
    for (Map<String, Object> m : motionModels())
    {
      Instance inst = im.get(Json.str(m, "name", ""));
      if (inst == null)
        continue;
      HttpResponse<byte[]> resp = call(inst, "GET", BASE, null);
      if (resp.statusCode() == 200)
        for (Object o : Json.arr(Json.parseObj(new String(resp.body(), StandardCharsets.UTF_8)).get("data")))
          data.add(Json.obj(o));
    }
    data.sort((a, b) -> Long.compare(Json.num(b, "created_at", 0), Json.num(a, "created_at", 0)));
    Router.sendJson(ex, 200, Json.map("object", "list", "data", data));
  }

  /** GET .../{id}, GET .../{id}/content and DELETE .../{id}: to the engine that holds the clip. */
  private void forward(HttpExchange ex) throws Exception
  {
    String path = ex.getRequestURI().getRawPath();
    String query = ex.getRequestURI().getRawQuery();
    String id = path.substring(BASE.length() + 1).split("/", 2)[0];
    String method = ex.getRequestMethod();
    List<String> holders = new ArrayList<>();
    String owner = owners.get(id);
    if (owner != null)
      holders.add(owner);
    else
      for (Map<String, Object> m : motionModels())
        holders.add(Json.str(m, "name", ""));
    for (String holder : holders)
    {
      Instance inst = im.get(holder);
      if (inst == null)
        continue;
      HttpResponse<byte[]> resp = call(inst, method, path + (query != null ? "?" + query : ""), null);
      if (resp.statusCode() == 404 && owner == null)
        continue;
      if (resp.statusCode() == 404 || ("DELETE".equals(method) && resp.statusCode() == 200))
        owners.remove(id);
      pass(ex, resp);
      return;
    }
    owners.remove(id);
    throw new HttpError(404, "no motion generation '" + id + "' — finished ones are kept for an hour, until deleted, "
        + "or until the motion model is stopped");
  }

  // ---- the engine ------------------------------------------------------------------------------

  private Instance running(HttpExchange ex, Map<String, Object> model, HangUp hangUp) throws Exception
  {
    String name = Json.str(model, "name", "?");
    if (im.get(name) != null)
      return im.ensure(model);
    im.loadAsync(model);
    long until = System.currentTimeMillis() + Json.num(cfg.data(), "motionLoadWaitSeconds", 90) * 1000L;
    while (System.currentTimeMillis() < until)
    {
      if (im.get(name) != null)
        return im.ensure(model);
      for (InstanceManager.LoadState s : im.loadStates())
        if (s.name().equals(name) && s.failed())
        {
          im.clearLoadState(name);
          throw new HttpError(500, "the motion model '" + name + "' failed to load: " + s.error());
        }
      if (hangUp.gone())
        return null;
      Thread.sleep(200);
    }
    ex.getResponseHeaders().set("Retry-After", "10");
    throw new HttpError(503, "the motion model '" + name + "' is loading; try again in a few seconds");
  }

  private static HttpResponse<byte[]> call(Instance inst, String method, String pathAndQuery, byte[] body)
      throws Exception
  {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + inst.port + pathAndQuery))
        .timeout(Duration.ofSeconds(60));
    if (body != null)
      b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(body));
    else
      b.method(method, HttpRequest.BodyPublishers.noBody());
    inst.beginRequest();
    try
    {
      return ENGINE.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    catch (IOException e)
    {
      throw new HttpError(502, "the motion engine is not answering (" + e.getClass().getSimpleName() + ")");
    }
    finally
    {
      inst.endRequest();
    }
  }

  /** The engine's answer as it is: status, type, file name and body. */
  private static void pass(HttpExchange ex, HttpResponse<byte[]> resp) throws IOException
  {
    resp.headers().firstValue("Content-Disposition").ifPresent(v -> ex.getResponseHeaders().set("Content-Disposition", v));
    resp.headers().firstValue("Retry-After").ifPresent(v -> ex.getResponseHeaders().set("Retry-After", v));
    Router.sendBytes(ex, resp.statusCode(), resp.body(),
        resp.headers().firstValue("Content-Type").orElse("application/json"));
  }

  /**
   * Hold the engine busy until the clip is made: its answer to POST comes at once, and an engine
   * with no request open would otherwise look idle to the keep-alive clock and to whoever needs
   * the memory, and be stopped half way through.
   */
  private void keepBusy(Instance inst, String id)
  {
    Thread.ofVirtual().name("motion-" + id).start(() -> {
      inst.beginRequest();
      try
      {
        long until = System.currentTimeMillis() + 15 * 60_000L;
        while (inst.alive() && System.currentTimeMillis() < until)
        {
          Thread.sleep(500);
          HttpResponse<byte[]> r = ENGINE.send(HttpRequest.newBuilder(
              URI.create("http://127.0.0.1:" + inst.port + BASE + "/" + id)).timeout(Duration.ofSeconds(10)).GET().build(),
              HttpResponse.BodyHandlers.ofByteArray());
          if (r.statusCode() != 200)
            break;
          String status = Json.str(Json.parseObj(new String(r.body(), StandardCharsets.UTF_8)), "status", "");
          if (!"queued".equals(status) && !"in_progress".equals(status))
            break;
        }
      }
      catch (Exception e)
      {
        Log.debug("motion", id + ": stopped watching (" + e.getClass().getSimpleName() + ")");
      }
      finally
      {
        inst.endRequest();
      }
    });
  }

  private static String idOf(byte[] body)
  {
    try
    {
      return Json.str(Json.parseObj(new String(body, StandardCharsets.UTF_8)), "id", null);
    }
    catch (RuntimeException e)
    {
      return null;
    }
  }

  private static byte[] readCapped(HttpExchange ex) throws IOException
  {
    try (InputStream in = ex.getRequestBody())
    {
      byte[] body = in.readNBytes(MAX_BODY + 1);
      if (body.length > MAX_BODY)
        throw new HttpError(413, "the request is larger than 64 KB");
      return body;
    }
  }
}
