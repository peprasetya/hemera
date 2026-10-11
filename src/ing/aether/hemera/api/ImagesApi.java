package ing.aether.hemera.api;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * OpenAI-compatible image endpoints (/v1/images/generations, /v1/images/edits) on top of
 * stable-diffusion.cpp's sd-server.
 *
 * sd-server already speaks this dialect, so most of the body travels untouched. What hemera adds
 * is the part clients cannot express: OpenAI's `background: "transparent"`, friendly sampling
 * fields (steps/seed/cfg_scale/...) folded into sd.cpp's native escape hatch, JSON as an
 * alternative to multipart for edits, and the edit-strength default that makes multi-reference
 * editing behave the way the model was trained for.
 */
public final class ImagesApi
{
  private final Config cfg;
  private final InstanceManager im;
  private final ProxyDispatcher proxies;
  private final ImageJobs jobs;
  private final ComfyBridge comfy;

  /** Optional client-chosen id for a request, so it can be cancelled before it has answered. */
  static final String REQUEST_ID_HEADER = "X-Hemera-Request-Id";

  /**
   * How often a streaming request checks that its client is still there. A write into a closed
   * connection usually succeeds once (it only fills the socket buffer) before the next one
   * fails, so noticing a Stop takes up to two of these.
   */
  private static final long HEARTBEAT_MS = 1000;

  public ImagesApi(Config cfg, InstanceManager im, ProxyDispatcher proxies, ComfyBridge comfy, Router router)
  {
    this.comfy = comfy;
    this.cfg = cfg;
    this.im = im;
    this.proxies = proxies;
    this.jobs = new ImageJobs(im);
    router.add("POST", "/v1/images/generations", this::generations);
    router.add("POST", "/v1/images/edits", this::edits);
    router.add("GET", "/hemera/api/images/jobs", ex -> Router.sendJson(ex, 200, Json.map("jobs", jobs.list())));
    router.add("POST", "/hemera/api/images/cancel", this::cancel);
    // video shares the per-model queue: one engine draws one thing at a time, whatever it is
    new VideosApi(cfg, im, jobs, comfy, router);
    new ThreeDApi(cfg, jobs, comfy, router);
  }

  // ---- generations (JSON) -------------------------------------------------

  private void generations(HttpExchange ex) throws Exception
  {
    Map<String, Object> body = Router.readJson(ex);
    String raw = Json.str(body, "model", null);
    if (raw == null)
      throw new HttpError(400, "missing 'model'");
    String name = ProxyDispatcher.resolveName(cfg, raw);

    Map<String, Object> proxy = cfg.findProxy(name);
    if (proxy != null)
    {
      proxies.openaiPassthrough(ex, proxy, body, "/images/generations");
      return;
    }
    ComfyBridge.Offer offer = cfg.findModel(name) == null ? comfy.require(raw, "image") : null;
    if (offer != null)
    {
      comfyGeneration(ex, offer, body);
      return;
    }
    Map<String, Object> model = localModel(name, raw);
    Map<String, Object> out = new LinkedHashMap<>(body);
    out.remove("model"); // sd-server serves whichever pipeline it was started with
    boolean stream = takeStream(out);
    boolean transparent = transparentRequested(out);
    String size = Json.str(out, "size", "auto");
    applyPrompt(model, out, false);

    ImageJobs.Job job = jobs.create(ex.getRequestHeaders().getFirst(REQUEST_ID_HEADER), name, "generation");
    respond(ex, job, stream, "image_generation", size, transparent, j -> {
      Instance inst = im.ensure(model);
      ImageJobs.checkpoint(j);
      return serve(inst, () -> Upstream.postJson(
          "http://127.0.0.1:" + inst.port + "/v1/images/generations", out, null));
    });
  }

  /** The same request, for a model ComfyUI offers: a workflow run instead of an sd-server call. */
  private void comfyGeneration(HttpExchange ex, ComfyBridge.Offer offer, Map<String, Object> body) throws Exception
  {
    Map<String, Object> req = new LinkedHashMap<>(body);
    boolean stream = takeStream(req);
    if (transparentRequested(req))
      throw new HttpError(400, "background=transparent is not available for '" + offer.id() + "'");
    String size = Json.str(req, "size", "auto");
    ComfyBridge.openAiFields(req, offer.recipe());
    comfy.check(offer, req);

    ImageJobs.Job job = jobs.create(ex.getRequestHeaders().getFirst(REQUEST_ID_HEADER), offer.id(), "generation");
    ComfyBridge.Run run = comfy.newRun();
    job.onCancel = run::cancel;
    respond(ex, job, stream, "image_generation", size, false, j -> {
      ComfyBridge.Output out = comfy.execute(offer, req, run);
      String name = out.filename();
      return Json.map(
          "created", System.currentTimeMillis() / 1000,
          "output_format", name.substring(name.lastIndexOf('.') + 1),
          "seed", run.used().get("seed"),
          "data", List.of(Json.map("b64_json", Base64.getEncoder().encodeToString(comfy.fetch(out)))));
    });
  }

  // ---- edits (multipart, or JSON for convenience) -------------------------

  private void edits(HttpExchange ex) throws Exception
  {
    String contentType = ex.getRequestHeaders().getFirst("Content-Type");
    String boundary = Multipart.boundary(contentType);
    List<Multipart.Part> parts = boundary != null
        ? Multipart.parse(Router.readBytes(ex), boundary)
        : partsFromJson(Router.readJson(ex));

    Map<String, Object> fields = new LinkedHashMap<>();
    List<Multipart.Part> files = new ArrayList<>();
    for (Multipart.Part p : parts)
    {
      if (p.isFile())
        files.add(p);
      else if (p.name() != null)
        fields.put(p.name(), p.value());
    }
    if (files.isEmpty())
      throw new HttpError(400, "at least one image is required — send image[] files (multipart) "
          + "or an \"images\" array of base64/data-URL strings (JSON)");

    String raw = Json.str(fields, "model", null);
    if (raw == null)
      throw new HttpError(400, "missing 'model'");
    String name = ProxyDispatcher.resolveName(cfg, raw);
    if (cfg.findProxy(name) != null)
      throw new HttpError(400, "image editing is not supported for proxied models yet");
    if (cfg.findModel(name) == null && comfy.find(raw) != null)
      throw new HttpError(400, "model '" + raw + "' cannot edit images");
    Map<String, Object> model = localModel(name, raw);
    fields.remove("model");
    boolean stream = takeStream(fields);
    boolean transparent = transparentRequested(fields);
    String size = Json.str(fields, "size", "auto");
    applyPrompt(model, fields, true);

    List<Multipart.Part> outParts = new ArrayList<>();
    for (Map.Entry<String, Object> e : fields.entrySet())
      outParts.add(Multipart.Part.field(e.getKey(), String.valueOf(e.getValue())));
    for (int i = 0; i < files.size(); i++)
    {
      Multipart.Part f = files.get(i);
      // sd-server takes several references only under "image[]"; a plain "image" is its legacy
      // single-image field, so normalise whatever the client used onto the array spelling.
      String fileName = f.fileName() == null || f.fileName().isBlank() ? "image" + i + ".png" : f.fileName();
      outParts.add(Multipart.Part.file("image[]", fileName, f.contentType(), f.data()));
    }

    String outBoundary = Multipart.randomBoundary();
    byte[] payload = Multipart.write(outParts, outBoundary);
    ImageJobs.Job job = jobs.create(ex.getRequestHeaders().getFirst(REQUEST_ID_HEADER), name, "edit");
    respond(ex, job, stream, "image_edit", size, transparent, j -> {
      Instance inst = im.ensure(model);
      ImageJobs.checkpoint(j);
      return serve(inst, () -> Upstream.postBytes(
          "http://127.0.0.1:" + inst.port + "/v1/images/edits", payload,
          "multipart/form-data; boundary=" + outBoundary));
    });
  }

  // ---- cancellation and streaming -----------------------------------------

  /**
   * Cancel an image request. OpenAI's Images API has no cancel endpoint — a streaming client
   * cancels by closing the stream, which hemera notices. This is for clients that don't stream:
   * by the id they sent in X-Hemera-Request-Id (exact), or by model (stops whatever is
   * generating on it right now; requests still queued behind it carry on).
   */
  private void cancel(HttpExchange ex) throws Exception
  {
    Map<String, Object> body = Router.readJson(ex);
    String id = Json.str(body, "id", null);
    String rawModel = Json.str(body, "model", null);
    ImageJobs.Job job;
    if (id != null)
    {
      job = jobs.find(id);
      if (job == null)
        throw new HttpError(404, "no image request '" + id + "' is in progress");
    }
    else if (rawModel != null)
    {
      job = jobs.running(ProxyDispatcher.resolveName(cfg, rawModel));
      if (job == null)
      {
        Router.sendJson(ex, 200, Json.map("cancelled", false,
            "reason", "nothing is generating on '" + rawModel + "'"));
        return;
      }
    }
    else
      throw new HttpError(400, "give the request's 'id' (from X-Hemera-Request-Id), or a 'model'"
          + " to stop whatever it is generating");
    boolean wasRunning = job.isRunning();
    jobs.cancel(job, "cancel requested");
    Router.sendJson(ex, 200, Json.map("cancelled", true, "id", job.id, "model", job.model,
        "wasRunning", wasRunning));
  }

  /**
   * Answer either as plain JSON or, with stream=true, as OpenAI's image streaming events. The
   * stream carries a comment heartbeat while the image is drawn; a heartbeat that cannot be
   * written means the client closed the stream, and the job is cancelled — which is how a
   * streaming client's Stop reaches the GPU.
   */
  private void respond(HttpExchange ex, ImageJobs.Job job, boolean stream, String eventPrefix,
      String size, boolean transparent, ImageJobs.Work work) throws Exception
  {
    ex.getResponseHeaders().set(REQUEST_ID_HEADER, job.id);
    // a cross-origin page can only read a custom response header it has been told about
    ex.getResponseHeaders().set("Access-Control-Expose-Headers", REQUEST_ID_HEADER);
    if (!stream)
    {
      Router.sendJson(ex, 200, jobs.run(job, work));
      return;
    }

    java.io.OutputStream os;
    try
    {
      os = Router.startStream(ex, "text/event-stream");
    }
    catch (java.io.IOException e)
    {
      jobs.forget(job);
      throw e;
    }
    java.util.concurrent.CompletableFuture<Map<String, Object>> result = new java.util.concurrent.CompletableFuture<>();
    Thread.ofVirtual().name("image-" + job.id).start(() -> {
      try
      {
        result.complete(jobs.run(job, work));
      }
      catch (Throwable t)
      {
        result.completeExceptionally(t);
      }
    });

    long started = System.currentTimeMillis();
    while (true)
    {
      try
      {
        Map<String, Object> resp = result.get(HEARTBEAT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        long created = Json.num(resp, "created", System.currentTimeMillis() / 1000);
        String format = Json.str(resp, "output_format", "png");
        try
        {
          for (Map<String, Object> d : Json.arrOfObj(resp.get("data")))
            Router.sse(os, eventPrefix + ".completed", Json.map(
                "type", eventPrefix + ".completed",
                "b64_json", Json.str(d, "b64_json", ""),
                "created_at", created,
                "size", size,
                "background", transparent ? "transparent" : "opaque",
                "output_format", format));
        }
        catch (java.io.IOException gone)
        {
          Log.debug("images", "client left just before '" + job.id + "' was delivered");
        }
        return;
      }
      catch (java.util.concurrent.TimeoutException stillWorking)
      {
        try
        {
          Router.sseComment(os, (job.isRunning() ? "generating " : "queued ")
              + (System.currentTimeMillis() - started) / 1000 + "s");
        }
        catch (java.io.IOException gone)
        {
          jobs.cancel(job, "client closed the stream");
          return;
        }
      }
      catch (java.util.concurrent.ExecutionException failed)
      {
        Throwable cause = failed.getCause();
        try
        {
          Router.sse(os, "error", Json.map("type", "error", "error", Json.map(
              "code", job.isCancelled() ? "cancelled" : "generation_failed",
              "message", String.valueOf(cause.getMessage()))));
        }
        catch (java.io.IOException ignored)
        {
          // client already gone
        }
        return;
      }
    }
  }

  /** Mark the engine busy for the length of the call, so the idle reaper leaves it alone. */
  private static Map<String, Object> serve(Instance inst, java.util.concurrent.Callable<Map<String, Object>> call)
      throws Exception
  {
    inst.beginRequest();
    try
    {
      return call.call();
    }
    finally
    {
      inst.endRequest();
    }
  }

  /**
   * Remove OpenAI's streaming fields before the request goes to sd-server, which has no
   * streaming of its own. partial_images is accepted and ignored: sd.cpp produces no previews,
   * so a stream carries only the finished image — which the OpenAI spec allows, since a fast
   * generation may finish before any partial is due.
   */
  private static boolean takeStream(Map<String, Object> fields)
  {
    fields.remove("partial_images");
    Object v = fields.remove("stream");
    return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
  }

  /** Accept a JSON edit body so a plain curl/fetch client need not build multipart by hand. */
  private static List<Multipart.Part> partsFromJson(Map<String, Object> body)
  {
    List<Multipart.Part> parts = new ArrayList<>();
    for (Map.Entry<String, Object> e : body.entrySet())
    {
      Object v = e.getValue();
      if (v == null || "image".equals(e.getKey()) || "images".equals(e.getKey()) || "mask".equals(e.getKey()))
        continue;
      parts.add(Multipart.Part.field(e.getKey(), String.valueOf(v)));
    }
    List<Object> images = new ArrayList<>();
    for (String key : new String[] { "image", "images" })
    {
      Object v = body.get(key);
      if (v instanceof List<?> list)
        images.addAll(list);
      else if (v instanceof String s && !s.isBlank())
        images.add(s);
    }
    int i = 0;
    for (Object o : images)
    {
      byte[] bytes = decodeImage(String.valueOf(o));
      parts.add(Multipart.Part.file("image[]", "image" + i++ + "." + extension(bytes), typeOf(bytes), bytes));
    }
    if (body.get("mask") instanceof String m && !m.isBlank())
    {
      byte[] bytes = decodeImage(m);
      parts.add(Multipart.Part.file("mask", "mask." + extension(bytes), typeOf(bytes), bytes));
    }
    return parts;
  }

  /** Raw base64 or a data: URL, as sd.cpp's own image fields accept. */
  private static byte[] decodeImage(String s)
  {
    String b64 = s.startsWith("data:") && s.contains(",") ? s.substring(s.indexOf(',') + 1) : s;
    try
    {
      return Base64.getDecoder().decode(b64.replaceAll("\\s", ""));
    }
    catch (IllegalArgumentException e)
    {
      throw new HttpError(400, "image is neither base64 nor a data: URL: " + e.getMessage());
    }
  }

  private static String typeOf(byte[] b)
  {
    if (b.length > 3 && (b[0] & 0xFF) == 0x89 && b[1] == 'P')
      return "image/png";
    if (b.length > 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8)
      return "image/jpeg";
    if (b.length > 12 && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P')
      return "image/webp";
    return "application/octet-stream";
  }

  private static String extension(byte[] b)
  {
    String t = typeOf(b);
    return t.startsWith("image/") ? t.substring(6) : "bin";
  }

  // ---- shared request shaping --------------------------------------------

  /**
   * Rewrite the prompt so it carries everything sd-server needs: the model's transparency
   * wrapper when the request asked for a transparent background, and any friendly sampling
   * fields folded into sd.cpp's {@code <sd_cpp_extra_args>} block. Consumed fields are removed
   * from the outgoing request.
   */
  private void applyPrompt(Map<String, Object> model, Map<String, Object> fields, boolean edit)
  {
    String prompt = Json.str(fields, "prompt", "");
    if (prompt.isBlank())
      throw new HttpError(400, "missing 'prompt'");

    // A client may already be using the native escape hatch; keep its values and merge around it.
    Map<String, Object> extra = new LinkedHashMap<>();
    int open = prompt.indexOf(EXTRA_OPEN);
    if (open >= 0)
    {
      int close = prompt.indexOf(EXTRA_CLOSE, open);
      if (close < 0)
        throw new HttpError(400, "unterminated <sd_cpp_extra_args> block in prompt");
      try
      {
        extra.putAll(Json.parseObj(prompt.substring(open + EXTRA_OPEN.length(), close)));
      }
      catch (IllegalArgumentException e)
      {
        throw new HttpError(400, "invalid JSON inside <sd_cpp_extra_args>: " + e.getMessage());
      }
      prompt = (prompt.substring(0, open) + prompt.substring(close + EXTRA_CLOSE.length())).trim();
    }
    if (fields.get("sd_cpp_extra_args") instanceof Map<?, ?> m)
      m.forEach((k, v) -> extra.put(String.valueOf(k), v));
    fields.remove("sd_cpp_extra_args");

    if (transparentRequested(fields))
      prompt = transparentPrompt(model, prompt, fields);

    // Every upload on the edits endpoint becomes a reference image, but sd-server also makes the
    // first one an init image — which turns an instruction like "put the cat on the table" into a
    // 0.75-strength img2img of that photo, ignoring the rest. A full-strength denoise leaves the
    // references as pure conditioning, which is what instruction-edit models are trained for.
    if (edit)
      extra.putIfAbsent("strength", 1.0d);

    moveNumber(fields, extra, "seed", "seed");
    moveSampling(fields, extra, "steps", "sample_steps");
    moveSampling(fields, extra, "sampler", "sample_method");
    moveSampling(fields, extra, "scheduler", "scheduler");
    moveGuidance(fields, extra, "cfg_scale");
    moveGuidance(fields, extra, "guidance_scale");
    if (fields.get("negative_prompt") != null)
      extra.putIfAbsent("negative_prompt", String.valueOf(fields.remove("negative_prompt")));
    if (fields.get("strength") != null)
      extra.put("strength", number(fields.remove("strength"), "strength"));

    if (!extra.isEmpty())
      prompt = prompt + " " + EXTRA_OPEN + Json.write(extra) + EXTRA_CLOSE;
    fields.put("prompt", prompt);
  }

  private static final String EXTRA_OPEN = "<sd_cpp_extra_args>";
  private static final String EXTRA_CLOSE = "</sd_cpp_extra_args>";

  private static boolean transparentRequested(Map<String, Object> fields)
  {
    return "transparent".equalsIgnoreCase(Json.str(fields, "background", ""));
  }

  /**
   * Transparency is not a switch on these models: the diffusion model decides to paint an alpha
   * channel because the prompt told it to, so the wording is model-specific and lives on the
   * model's config.
   */
  private static String transparentPrompt(Map<String, Object> model, String prompt,
      Map<String, Object> fields)
  {
    fields.remove("background");
    String template = Json.str(model, "transparentPrompt", "");
    if (template.isBlank())
      throw new HttpError(400, "model '" + Json.str(model, "name", "?") + "' has no"
          + " transparentPrompt configured — transparency on stable-diffusion.cpp comes from the"
          + " prompt, so only models trained for RGBA (e.g. Qwen-Image 2.1) can do it. Set the"
          + " template in the web UI (/configure/) to enable background=transparent.");
    String format = Json.str(fields, "output_format", "");
    if (format.equalsIgnoreCase("jpeg") || format.equalsIgnoreCase("jpg"))
      throw new HttpError(400, "output_format '" + format + "' cannot carry transparency — use png or webp");
    if (format.isBlank())
      fields.put("output_format", "png");
    return template.contains("{prompt}") ? template.replace("{prompt}", prompt) : template + " " + prompt;
  }

  private static void moveNumber(Map<String, Object> fields, Map<String, Object> extra,
      String from, String to)
  {
    Object v = fields.remove(from);
    if (v != null)
      extra.putIfAbsent(to, number(v, from));
  }

  @SuppressWarnings("unchecked")
  private static void moveSampling(Map<String, Object> fields, Map<String, Object> extra,
      String from, String to)
  {
    Object v = fields.remove(from);
    if (v == null)
      return;
    Map<String, Object> sample = (Map<String, Object>) extra.computeIfAbsent("sample_params",
        k -> new LinkedHashMap<String, Object>());
    sample.putIfAbsent(to, "sample_steps".equals(to) ? number(v, from) : String.valueOf(v));
  }

  @SuppressWarnings("unchecked")
  private static void moveGuidance(Map<String, Object> fields, Map<String, Object> extra, String from)
  {
    Object v = fields.remove(from);
    if (v == null)
      return;
    Map<String, Object> sample = (Map<String, Object>) extra.computeIfAbsent("sample_params",
        k -> new LinkedHashMap<String, Object>());
    Map<String, Object> guidance = (Map<String, Object>) sample.computeIfAbsent("guidance",
        k -> new LinkedHashMap<String, Object>());
    guidance.putIfAbsent("txt_cfg", number(v, from));
  }

  /** Multipart fields always arrive as text, so numbers have to be parsed either way. */
  private static Object number(Object v, String field)
  {
    if (v instanceof Number n)
      return n;
    try
    {
      String s = String.valueOf(v).trim();
      return s.contains(".") ? (Object) Double.parseDouble(s) : (Object) Long.parseLong(s);
    }
    catch (NumberFormatException e)
    {
      throw new HttpError(400, "'" + field + "' must be a number, got '" + v + "'");
    }
  }

  private Map<String, Object> localModel(String name, String raw)
  {
    Map<String, Object> model = cfg.findModel(name);
    if (model == null)
      throw new HttpError(404, "model '" + raw + "' not found — configure it in the web UI (/configure/)");
    Engines.requireImageModel(cfg, model);
    return model;
  }
}
