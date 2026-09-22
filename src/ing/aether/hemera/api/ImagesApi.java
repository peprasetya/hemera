package ing.aether.hemera.api;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

  public ImagesApi(Config cfg, InstanceManager im, ProxyDispatcher proxies, Router router)
  {
    this.cfg = cfg;
    this.im = im;
    this.proxies = proxies;
    router.add("POST", "/v1/images/generations", this::generations);
    router.add("POST", "/v1/images/edits", this::edits);
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
    Map<String, Object> model = localModel(name, raw);
    Map<String, Object> out = new LinkedHashMap<>(body);
    out.remove("model"); // sd-server serves whichever pipeline it was started with
    applyPrompt(model, out, false);

    Instance inst = im.ensure(model);
    Map<String, Object> resp = Upstream.postJson(
        "http://127.0.0.1:" + inst.port + "/v1/images/generations", out, null);
    inst.touch();
    Router.sendJson(ex, 200, resp);
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
    Map<String, Object> model = localModel(name, raw);
    fields.remove("model");
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
    Instance inst = im.ensure(model);
    Map<String, Object> resp = Upstream.postBytes(
        "http://127.0.0.1:" + inst.port + "/v1/images/edits", payload,
        "multipart/form-data; boundary=" + outBoundary);
    inst.touch();
    Router.sendJson(ex, 200, resp);
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
