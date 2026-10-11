package ing.aether.hemera.api;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;

import ing.aether.hemera.Log;
import ing.aether.hemera.comfy.ComfyBridge;
import ing.aether.hemera.config.Config;
import ing.aether.hemera.engine.Engines;
import ing.aether.hemera.engine.Instance;
import ing.aether.hemera.engine.InstanceManager;
import ing.aether.hemera.http.HttpError;
import ing.aether.hemera.http.Multipart;
import ing.aether.hemera.http.Router;
import ing.aether.hemera.json.Json;

/**
 * Video generation in the shape of OpenAI's Videos API, on top of stable-diffusion.cpp's
 * sd-server or, for the models ComfyUI offers, a ComfyUI workflow: POST /v1/videos starts a job, GET /v1/videos/{id} reports on it,
 * GET /v1/videos/{id}/content downloads the result and DELETE cancels or discards it.
 *
 * Unlike images this is asynchronous by design — a clip takes minutes — which also means every
 * job has an id from the start, so cancelling needs no special arrangement. Jobs go through the
 * same per-model queue as image requests (see ImageJobs), and finished videos are kept in memory
 * for a while rather than on disk: they are a download waiting to happen, not an archive.
 */
public final class VideosApi
{
  private static final long KEEP_FINISHED_MS = 2 * 60 * 60 * 1000L;
  private static final int KEEP_FINISHED_MAX = 8;

  private static final class Video
  {
    final String id;
    final String model;
    final long createdAt = System.currentTimeMillis() / 1000;
    final Map<String, Object> echo; // size / seconds, as the client asked for them
    volatile String status = "queued";
    volatile String error;
    volatile byte[] data;
    volatile String mimeType;
    volatile long completedAt;
    volatile ImageJobs.Job job;
    volatile ComfyBridge.Run comfy; // set when ComfyUI makes this one; carries real progress

    Video(String id, String model, Map<String, Object> echo)
    {
      this.id = id;
      this.model = model;
      this.echo = echo;
    }
  }

  private final Config cfg;
  private final InstanceManager im;
  private final ImageJobs jobs;
  private final ComfyBridge comfy;
  private final Map<String, Video> videos = new ConcurrentHashMap<>();

  public VideosApi(Config cfg, InstanceManager im, ImageJobs jobs, ComfyBridge comfy, Router router)
  {
    this.cfg = cfg;
    this.im = im;
    this.jobs = jobs;
    this.comfy = comfy;
    router.add("POST", "/v1/videos", this::create);
    router.add("GET", "/v1/videos", ex -> Router.sendJson(ex, 200, Json.map("object", "list", "data", list())));
    router.addPrefix("GET", "/v1/videos/", this::get);
    router.addPrefix("DELETE", "/v1/videos/", this::delete);
  }

  // ---- create ---------------------------------------------------------------

  private void create(HttpExchange ex) throws Exception
  {
    Map<String, Object> req = readRequest(ex);
    String raw = Json.str(req, "model", null);
    if (raw == null)
      throw new HttpError(400, "missing 'model'");
    String name = ProxyDispatcher.resolveName(cfg, raw);
    Map<String, Object> model = cfg.findModel(name);
    ComfyBridge.Offer offer = model == null ? comfy.require(raw, "video") : null;
    if (model == null && offer == null)
      throw new HttpError(404, "model '" + raw + "' not found — configure it in the web UI (/configure/)");

    if (offer != null)
    {
      ComfyBridge.openAiFields(req, offer.recipe());
      comfy.check(offer, req);
    }
    purge();
    String id = "video_" + Long.toHexString(System.nanoTime());
    Video v = new Video(id, raw, Json.map("size", Json.str(req, "size", null), "seconds", req.get("seconds")));
    if (offer != null)
    {
      v.comfy = comfy.newRun();
      v.job = jobs.create(id, offer.id(), "video");
      v.job.onCancel = v.comfy::cancel;
      videos.put(id, v);
      Thread.ofVirtual().name(id).start(() -> run(v, j -> {
        ComfyBridge.Output out = comfy.execute(offer, req, v.comfy);
        v.data = comfy.fetch(out);
        v.mimeType = out.contentType();
        return Json.map();
      }));
    }
    else
    {
      Engines.requireImageModel(cfg, model);
      Map<String, Object> body = nativeBody(req);
      v.job = jobs.create(id, name, "video");
      videos.put(id, v);
      Thread.ofVirtual().name(id).start(() -> run(v, j -> engine(v, j, model, body)));
    }
    Router.sendJson(ex, 200, describe(v));
  }

  /** Generate on sd-server: start its job, then poll until it has something. */
  private Map<String, Object> engine(Video v, ImageJobs.Job j, Map<String, Object> model,
      Map<String, Object> body) throws Exception
  {
    Instance inst = im.ensure(model);
    ImageJobs.checkpoint(j);
    String base = "http://127.0.0.1:" + inst.port;
    inst.beginRequest();
    try
    {
      String jobId = Json.str(Upstream.postJson(base + "/sdcpp/v1/vid_gen", body, null), "id", null);
      if (jobId == null)
        throw new HttpError(502, "engine did not return a job id");
      while (true)
      {
        Thread.sleep(2000);
        ImageJobs.checkpoint(j);
        Map<String, Object> st = Upstream.getJson(base + "/sdcpp/v1/jobs/" + jobId);
        String status = Json.str(st, "status", "");
        if ("completed".equals(status))
        {
          Map<String, Object> result = Json.obj(st.get("result"));
          v.data = Base64.getDecoder().decode(Json.str(result, "b64_json", ""));
          v.mimeType = Json.str(result, "mime_type", "video/webm");
          return st;
        }
        if ("failed".equals(status) || "cancelled".equals(status))
          throw new HttpError(502, Json.str(Json.obj(st.get("error")), "message", "generation " + status));
      }
    }
    finally
    {
      inst.endRequest();
    }
  }

  private void run(Video v, ImageJobs.Work work)
  {
    try
    {
      jobs.run(v.job, j -> {
        v.status = "in_progress";
        return work.run(j);
      });
      v.status = "completed";
    }
    catch (Throwable t)
    {
      v.error = t.getMessage() != null ? t.getMessage()
          : t.getClass().getSimpleName() + " (the engine most likely crashed — see its output in the hemera log)";
      v.status = v.job.isCancelled() ? "cancelled" : "failed";
      if (!v.job.isCancelled())
        Log.warn("videos", "'" + v.id + "' failed: " + v.error);
    }
    finally
    {
      v.completedAt = System.currentTimeMillis() / 1000;
    }
  }

  /** OpenAI's field names (plus hemera's friendly sampling ones) -> sd.cpp's native vid_gen body. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> nativeBody(Map<String, Object> req)
  {
    String prompt = Json.str(req, "prompt", "");
    if (prompt.isBlank())
      throw new HttpError(400, "missing 'prompt'");
    Map<String, Object> body = new LinkedHashMap<>();
    // anything sd.cpp understands can be passed straight through; named fields below win
    if (req.get("sd_cpp_extra_args") instanceof Map<?, ?> m)
      m.forEach((k, val) -> body.put(String.valueOf(k), val));
    body.put("prompt", prompt);
    if (req.get("negative_prompt") != null)
      body.put("negative_prompt", String.valueOf(req.get("negative_prompt")));

    String size = Json.str(req, "size", "");
    if (!size.isBlank())
    {
      String[] wh = size.toLowerCase(java.util.Locale.ROOT).split("x");
      if (wh.length != 2)
        throw new HttpError(400, "'size' must look like 1280x720");
      body.put("width", number(wh[0], "size"));
      body.put("height", number(wh[1], "size"));
    }
    Number fps = req.get("fps") == null ? null : number(req.get("fps"), "fps");
    if (fps != null)
      body.put("fps", fps);
    if (req.get("frames") != null)
      body.put("video_frames", number(req.get("frames"), "frames"));
    else if (req.get("seconds") != null)
    {
      // sd.cpp counts frames, not time, and keeps the largest 4n+1 that fits
      double seconds = number(req.get("seconds"), "seconds").doubleValue();
      body.put("video_frames", Math.round(seconds * (fps == null ? 24 : fps.doubleValue())) + 1);
      body.putIfAbsent("fps", 24L);
    }
    if (req.get("seed") != null)
      body.put("seed", number(req.get("seed"), "seed"));

    Map<String, Object> sample = (Map<String, Object>) body.computeIfAbsent("sample_params",
        k -> new LinkedHashMap<String, Object>());
    if (req.get("steps") != null)
      sample.put("sample_steps", number(req.get("steps"), "steps"));
    if (req.get("sampler") != null)
      sample.put("sample_method", String.valueOf(req.get("sampler")));
    Object cfgScale = req.get("cfg_scale") != null ? req.get("cfg_scale") : req.get("guidance_scale");
    if (cfgScale != null)
      ((Map<String, Object>) sample.computeIfAbsent("guidance", k -> new LinkedHashMap<String, Object>()))
          .put("txt_cfg", number(cfgScale, "cfg_scale"));
    if (sample.isEmpty())
      body.remove("sample_params");

    // OpenAI calls the start frame input_reference; "image" is accepted as the obvious synonym
    Object start = req.get("input_reference") != null ? req.get("input_reference") : req.get("image");
    if (start != null)
      body.put("init_image", String.valueOf(start));
    if (req.get("end_image") != null)
      body.put("end_image", String.valueOf(req.get("end_image")));
    body.put("output_format", Json.str(req, "output_format", "webm"));
    return body;
  }

  /** JSON, or the multipart form the OpenAI SDKs send; uploaded files become base64 strings. */
  static Map<String, Object> readRequest(HttpExchange ex) throws Exception
  {
    String boundary = Multipart.boundary(ex.getRequestHeaders().getFirst("Content-Type"));
    if (boundary == null)
      return Router.readJson(ex);
    Map<String, Object> req = new LinkedHashMap<>();
    for (Multipart.Part p : Multipart.parse(Router.readBytes(ex), boundary))
      if (p.name() != null)
        req.put(p.name(), p.isFile() ? Base64.getEncoder().encodeToString(p.data()) : p.value());
    return req;
  }

  private static Number number(Object v, String field)
  {
    if (v instanceof Number n)
      return n;
    try
    {
      String s = String.valueOf(v).trim();
      return s.contains(".") ? (Number) Double.parseDouble(s) : (Number) Long.parseLong(s);
    }
    catch (NumberFormatException e)
    {
      throw new HttpError(400, "'" + field + "' must be a number, got '" + v + "'");
    }
  }

  // ---- read / delete ----------------------------------------------------------

  private void get(HttpExchange ex) throws Exception
  {
    String rest = ex.getRequestURI().getPath().substring("/v1/videos/".length());
    boolean content = rest.endsWith("/content");
    Video v = find(content ? rest.substring(0, rest.length() - "/content".length()) : rest);
    if (!content)
    {
      Router.sendJson(ex, 200, describe(v));
      return;
    }
    if (v.data == null)
      throw new HttpError(409, "video '" + v.id + "' is " + v.status
          + (v.error != null ? ": " + v.error : " — nothing to download yet"));
    Router.sendBytes(ex, 200, v.data, v.mimeType);
  }

  private void delete(HttpExchange ex) throws Exception
  {
    Video v = find(ex.getRequestURI().getPath().substring("/v1/videos/".length()));
    boolean inFlight = v.completedAt == 0;
    if (inFlight)
      jobs.cancel(v.job, "video deleted");
    videos.remove(v.id);
    Router.sendJson(ex, 200, Json.map("id", v.id, "object", "video.deleted", "deleted", true,
        "cancelled", inFlight));
  }

  private Video find(String id)
  {
    Video v = videos.get(id);
    if (v == null)
      throw new HttpError(404, "no video '" + id + "' — finished videos are kept for two hours");
    return v;
  }

  private List<Object> list()
  {
    List<Video> sorted = new ArrayList<>(videos.values());
    sorted.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
    List<Object> out = new ArrayList<>();
    for (Video v : sorted)
      out.add(describe(v));
    return out;
  }

  private static Map<String, Object> describe(Video v)
  {
    String status = v.status;
    if ("in_progress".equals(status) && v.completedAt == 0
        && (!v.job.isRunning() || v.comfy != null && !v.comfy.started()))
      status = "queued";
    Map<String, Object> m = Json.map(
        "id", v.id,
        "object", "video",
        "model", v.model,
        "status", status,
        "progress", "completed".equals(status) ? 100L : v.comfy != null ? v.comfy.progress() : 0L,
        "created_at", v.createdAt);
    if (v.completedAt != 0)
      m.put("completed_at", v.completedAt);
    v.echo.forEach((k, val) -> {
      if (val != null)
        m.put(k, val);
    });
    if (v.comfy != null && v.comfy.used().get("seed") != null)
      m.put("seed", v.comfy.used().get("seed"));
    if (v.data != null)
      m.put("content_type", v.mimeType);
    if (v.error != null)
      m.put("error", Json.map("code", v.status, "message", v.error));
    return m;
  }

  /** Forget finished videos that are old, or beyond the few most recent. */
  private void purge()
  {
    long cutoff = System.currentTimeMillis() / 1000 - KEEP_FINISHED_MS / 1000;
    List<Video> finished = new ArrayList<>();
    for (Video v : videos.values())
      if (v.completedAt != 0)
        finished.add(v);
    finished.sort((a, b) -> Long.compare(b.completedAt, a.completedAt));
    for (int i = 0; i < finished.size(); i++)
      if (i >= KEEP_FINISHED_MAX - 1 || finished.get(i).completedAt < cutoff)
        videos.remove(finished.get(i).id);
  }
}
