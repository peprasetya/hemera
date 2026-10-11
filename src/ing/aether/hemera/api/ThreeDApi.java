package ing.aether.hemera.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;

import ing.aether.hemera.Log;
import ing.aether.hemera.comfy.ComfyBridge;
import ing.aether.hemera.config.Config;
import ing.aether.hemera.http.HttpError;
import ing.aether.hemera.http.Router;
import ing.aether.hemera.json.Json;

/**
 * 3D generation: an image in, a mesh out.
 *
 * OpenAI has no 3D endpoint to be compatible with, so this one is hemera's own, cut to the
 * pattern of its Videos API because the work has the same shape — minutes long, so a job with an
 * id rather than one held-open request: POST /v1/3d/generations starts it,
 * GET /v1/3d/generations/{id} reports on it, GET .../content downloads the mesh and DELETE cancels
 * or discards it. The mesh is always binary glTF (.glb), the one format every engine, browser
 * viewer and DCC tool reads, and it stays in ComfyUI's output folder: a download is passed
 * straight through rather than held here.
 */
public final class ThreeDApi
{
  private static final String BASE = "/v1/3d/generations";
  private static final long KEEP_FINISHED_SECONDS = 24 * 60 * 60;
  private static final int KEEP_FINISHED_MAX = 64;

  private static final class Mesh
  {
    final String id;
    final String model;
    final long createdAt = System.currentTimeMillis() / 1000;
    final ComfyBridge.Run run;
    volatile String status = "queued";
    volatile String error;
    volatile ComfyBridge.Output output;
    volatile long completedAt;
    volatile ImageJobs.Job job;

    Mesh(String id, String model, ComfyBridge.Run run)
    {
      this.id = id;
      this.model = model;
      this.run = run;
    }
  }

  private final Config cfg;
  private final ImageJobs jobs;
  private final ComfyBridge comfy;
  private final Map<String, Mesh> meshes = new ConcurrentHashMap<>();

  public ThreeDApi(Config cfg, ImageJobs jobs, ComfyBridge comfy, Router router)
  {
    this.cfg = cfg;
    this.jobs = jobs;
    this.comfy = comfy;
    router.add("POST", BASE, this::create);
    router.add("GET", BASE, ex -> Router.sendJson(ex, 200, Json.map("object", "list", "data", list())));
    router.addPrefix("GET", BASE + "/", this::get);
    router.addPrefix("DELETE", BASE + "/", this::delete);
  }

  private void create(HttpExchange ex) throws Exception
  {
    Map<String, Object> req = VideosApi.readRequest(ex);
    String raw = Json.str(req, "model", null);
    if (raw == null)
      throw new HttpError(400, "missing 'model'");
    if (cfg.findModel(ProxyDispatcher.resolveName(cfg, raw)) != null)
      throw new HttpError(400, "model '" + raw + "' does not make 3D models");
    ComfyBridge.Offer offer = comfy.require(raw, "3d");
    if (offer == null)
      throw new HttpError(404, "model '" + raw + "' not found — 3D models come from ComfyUI; GET /v1/models"
          + " lists the ones it currently has");
    String format = Json.str(req, "output_format", "glb");
    if (!"glb".equalsIgnoreCase(format))
      throw new HttpError(400, "output_format '" + format + "' is not available — meshes are returned as glb");
    ComfyBridge.openAiFields(req, offer.recipe());
    comfy.check(offer, req);

    purge();
    String id = "3d_" + Long.toHexString(System.nanoTime());
    Mesh m = new Mesh(id, offer.id(), comfy.newRun());
    m.job = jobs.create(id, offer.id(), "3d");
    m.job.onCancel = m.run::cancel;
    meshes.put(id, m);
    Thread.ofVirtual().name(id).start(() -> run(m, offer, req));
    Router.sendJson(ex, 200, describe(m));
  }

  private void run(Mesh m, ComfyBridge.Offer offer, Map<String, Object> req)
  {
    try
    {
      jobs.run(m.job, j -> {
        m.status = "in_progress";
        m.output = comfy.execute(offer, req, m.run);
        return Json.map();
      });
      m.status = "completed";
    }
    catch (Throwable t)
    {
      m.error = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
      m.status = m.job.isCancelled() ? "cancelled" : "failed";
      if (!m.job.isCancelled())
        Log.warn("3d", "'" + m.id + "' failed: " + m.error);
    }
    finally
    {
      m.completedAt = System.currentTimeMillis() / 1000;
    }
  }

  private void get(HttpExchange ex) throws Exception
  {
    String rest = ex.getRequestURI().getPath().substring(BASE.length() + 1);
    boolean content = rest.endsWith("/content");
    Mesh m = find(content ? rest.substring(0, rest.length() - "/content".length()) : rest);
    if (!content)
    {
      Router.sendJson(ex, 200, describe(m));
      return;
    }
    if (m.output == null)
      throw new HttpError(409, "3D generation '" + m.id + "' is " + m.status
          + (m.error != null ? ": " + m.error : " — nothing to download yet"));
    comfy.stream(m.output, ex, m.id + ".glb");
  }

  private void delete(HttpExchange ex) throws Exception
  {
    Mesh m = find(ex.getRequestURI().getPath().substring(BASE.length() + 1));
    boolean inFlight = m.completedAt == 0;
    if (inFlight)
      jobs.cancel(m.job, "3D generation deleted");
    meshes.remove(m.id);
    Router.sendJson(ex, 200, Json.map("id", m.id, "object", "3d.generation.deleted", "deleted", true,
        "cancelled", inFlight));
  }

  private Mesh find(String id)
  {
    Mesh m = meshes.get(id);
    if (m == null)
      throw new HttpError(404, "no 3D generation '" + id + "' — finished ones are kept for a day");
    return m;
  }

  private List<Object> list()
  {
    List<Mesh> sorted = new ArrayList<>(meshes.values());
    sorted.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
    List<Object> out = new ArrayList<>();
    for (Mesh m : sorted)
      out.add(describe(m));
    return out;
  }

  private static Map<String, Object> describe(Mesh m)
  {
    String status = m.status;
    if ("in_progress".equals(status) && m.completedAt == 0 && (!m.job.isRunning() || !m.run.started()))
      status = "queued";
    Map<String, Object> d = Json.map(
        "id", m.id,
        "object", "3d.generation",
        "model", m.model,
        "status", status,
        "progress", "completed".equals(status) ? 100L : m.run.progress(),
        "created_at", m.createdAt);
    if (m.completedAt != 0)
      d.put("completed_at", m.completedAt);
    if (!m.run.used().isEmpty())
      d.put("parameters", m.run.used());
    if (m.output != null)
      d.put("output", Json.map("format", "glb", "content_type", m.output.contentType(),
          "url", BASE + "/" + m.id + "/content"));
    if (m.error != null)
      d.put("error", Json.map("code", m.status, "message", m.error));
    return d;
  }

  /** Forget finished jobs that are old, or beyond the most recent few; the files stay in ComfyUI. */
  private void purge()
  {
    long cutoff = System.currentTimeMillis() / 1000 - KEEP_FINISHED_SECONDS;
    List<Mesh> finished = new ArrayList<>();
    for (Mesh m : meshes.values())
      if (m.completedAt != 0)
        finished.add(m);
    finished.sort((a, b) -> Long.compare(b.completedAt, a.completedAt));
    for (int i = 0; i < finished.size(); i++)
      if (i >= KEEP_FINISHED_MAX - 1 || finished.get(i).completedAt < cutoff)
        meshes.remove(finished.get(i).id);
  }
}
