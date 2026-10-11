package ing.aether.hemera.comfy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpExchange;

import ing.aether.hemera.Log;
import ing.aether.hemera.config.Config;
import ing.aether.hemera.http.HttpError;
import ing.aether.hemera.http.Multipart;
import ing.aether.hemera.http.Router;
import ing.aether.hemera.json.Json;

/**
 * The bridge to a ComfyUI server: what it can do, and doing it.
 *
 * Nothing is installed into ComfyUI and nothing about it is configured here beyond where it is.
 * hemera asks ComfyUI which model files and which nodes it has, matches the files against its
 * recipes (see {@link Recipe}), and offers whatever is complete as models of its own — so a file
 * dropped into a ComfyUI folder shows up in /v1/models without anyone touching hemera. A request
 * for one becomes a graph queued over ComfyUI's ordinary HTTP API, the same one its own web page
 * uses.
 */
public final class ComfyBridge
{
  private static final String[] BUILTIN = { "trellis2", "pixal3d", "hunyuan3d-2.1", "ltx-2.5",
      "minimax-h3-turbo", "minimax-h3", "qwen-image" };
  private static final long SNAPSHOT_TTL_MS = 30_000;
  private static final long NODES_TTL_MS = 10 * 60_000;
  /** Where uploads and results go inside ComfyUI's input/ and output/, to keep them apart from its own. */
  private static final String SUBFOLDER = "hemera";
  /** Companion files that turn up beside diffusion models when folders overlap. */
  private static final java.util.regex.Pattern NOT_A_MODEL = java.util.regex.Pattern
      .compile("(?i)vae|upscal|encoder|lora|clip|mmproj");

  private static final HttpClient HC = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(3))
      .build();

  /** One model hemera can offer from ComfyUI; problems is empty when it is ready to use. */
  public record Offer(String id, Recipe recipe, String file, Map<String, String> components, List<String> problems)
  {
    public boolean ready()
    {
      return problems.isEmpty();
    }
  }

  /** What ComfyUI had the last time hemera looked. */
  public record Snapshot(boolean reachable, String error, String version, List<Offer> offers,
      List<Map<String, Object>> unassigned, long at)
  {
  }

  /** A result file, still sitting in ComfyUI's output folder. */
  public record Output(String filename, String subfolder, String type, String contentType)
  {
  }

  /** The caller's handle on one job: progress to read, and a way to stop it. */
  public final class Run
  {
    private volatile String promptId;
    private volatile boolean cancelled;
    private volatile boolean started;
    private volatile int progress;
    private volatile Map<String, Object> used = Map.of();

    public int progress()
    {
      return progress;
    }

    /** True once ComfyUI has begun executing it, as opposed to holding it in its queue. */
    public boolean started()
    {
      return started;
    }

    /** The values the job ran with, defaults and the drawn seed included. */
    public Map<String, Object> used()
    {
      return used;
    }

    public boolean isCancelled()
    {
      return cancelled;
    }

    /** Stop it, wherever it is: dropped from ComfyUI's queue, or interrupted if it is running. */
    public void cancel()
    {
      cancelled = true;
      String id = promptId;
      if (id == null)
        return;
      try
      {
        post("/queue", Json.map("delete", List.of(id)), 10);
        post("/interrupt", Json.map("prompt_id", id), 10);
      }
      catch (Exception e)
      {
        Log.debug("comfyui", "could not cancel " + id + ": " + e);
      }
    }
  }

  private final Config cfg;
  private final AtomicInteger active = new AtomicInteger();
  private volatile Snapshot snapshot;
  private volatile Set<String> nodes;
  private volatile long nodesAt;
  private volatile long lastUsed;
  private volatile boolean mayHoldModels;
  private Process process;

  public ComfyBridge(Config cfg)
  {
    this.cfg = cfg;
    Thread.ofVirtual().name("comfyui-idle").start(this::idleLoop);
    Runtime.getRuntime().addShutdownHook(new Thread(this::stopProcess));
    if (configured() && !dir().isEmpty() && Json.bool(conf(), "autoStart", true))
      Thread.ofVirtual().name("comfyui-start").start(() -> {
        try
        {
          ensureUp();
        }
        catch (Exception e)
        {
          Log.warn("comfyui", "could not start ComfyUI: " + e.getMessage());
        }
      });
  }

  // ---- configuration ------------------------------------------------------

  private Map<String, Object> conf()
  {
    return Json.obj(cfg.data().get("comfyui"));
  }

  private String dir()
  {
    return Json.str(conf(), "dir", "").trim();
  }

  /** ComfyUI is in play once hemera has been told where it is: a URL, or a folder to run it from. */
  public boolean configured()
  {
    return !Json.str(conf(), "url", "").isBlank() || !dir().isEmpty();
  }

  private String url()
  {
    String u = Json.str(conf(), "url", "").trim();
    if (u.isEmpty())
      u = "http://127.0.0.1:8188";
    return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
  }

  // ---- discovery ----------------------------------------------------------

  /** The offered model with this id, or null. Ids are matched the forgiving way ollama clients expect. */
  public Offer find(String name)
  {
    if (name == null || !configured())
      return null;
    String wanted = name.endsWith(":latest") ? name.substring(0, name.length() - 7) : name;
    for (Offer o : snapshot(false).offers())
      if (o.ready() && o.id().equalsIgnoreCase(wanted))
        return o;
    return null;
  }

  /** Like find, but explains itself when the model exists and cannot be used. */
  public Offer require(String name, String kind)
  {
    Offer o = find(name);
    if (o == null)
      return null;
    if (!o.recipe().kind.equals(kind))
      throw new HttpError(400, "model '" + o.id() + "' makes " + noun(o.recipe().kind) + ", not "
          + noun(kind) + " — use " + endpoint(o.recipe().kind));
    return o;
  }

  public static String endpoint(String kind)
  {
    return switch (kind)
    {
      case "image" -> "/v1/images/generations";
      case "video" -> "/v1/videos";
      default -> "/v1/3d/generations";
    };
  }

  private static String noun(String kind)
  {
    return "3d".equals(kind) ? "3D models" : kind + "s";
  }

  /** Look again at the next opportunity, e.g. because the configuration changed. */
  public void invalidate()
  {
    snapshot = null;
    nodes = null;
  }

  /**
   * Rewrite the fields OpenAI clients send into the parameter names recipes use: "size" becomes
   * width and height, "seconds" a frame count at the model's frame rate, "input_reference" the
   * image. A field the client already spelled the recipe's way is left alone.
   */
  public static void openAiFields(Map<String, Object> req, Recipe recipe)
  {
    String size = String.valueOf(req.getOrDefault("size", "")).toLowerCase(Locale.ROOT).trim();
    if (!size.isEmpty() && !"auto".equals(size))
    {
      String[] wh = size.split("x");
      if (wh.length != 2)
        throw new HttpError(400, "'size' must look like 1280x720");
      req.putIfAbsent("width", wh[0].trim());
      req.putIfAbsent("height", wh[1].trim());
    }
    if (req.get("input_reference") != null)
      req.putIfAbsent("image", req.get("input_reference"));
    if (req.get("guidance_scale") != null)
      req.putIfAbsent("cfg_scale", req.get("guidance_scale"));
    if (req.get("seconds") != null && req.get("frames") == null)
      try
      {
        Object fps = req.get("fps") != null ? req.get("fps") : recipe.paramDefault("fps");
        double rate = fps == null ? 24 : Double.parseDouble(String.valueOf(fps));
        req.put("frames", Math.round(Double.parseDouble(String.valueOf(req.get("seconds"))) * rate) + 1);
      }
      catch (NumberFormatException e)
      {
        throw new HttpError(400, "'seconds' and 'fps' must be numbers");
      }
  }

  public Snapshot snapshot(boolean force)
  {
    Snapshot s = snapshot;
    if (!force && s != null && System.currentTimeMillis() - s.at() < SNAPSHOT_TTL_MS)
      return s;
    synchronized (this)
    {
      s = snapshot;
      if (!force && s != null && System.currentTimeMillis() - s.at() < SNAPSHOT_TTL_MS)
        return s;
      return snapshot = look();
    }
  }

  private Snapshot look()
  {
    long now = System.currentTimeMillis();
    if (!configured())
      return new Snapshot(false, "not configured", null, List.of(), List.of(), now);
    try
    {
      String version = Json.str(Json.obj(Json.obj(get("/system_stats", 3)).get("system")), "comfyui_version", "?");
      if (nodes == null || now - nodesAt > NODES_TTL_MS)
      {
        nodes = new HashSet<>(Json.obj(get("/object_info", 60)).keySet());
        nodesAt = now;
      }
      List<Recipe> recipes = recipes();
      Map<String, List<String>> folders = new LinkedHashMap<>();
      for (Recipe r : recipes)
      {
        folders.computeIfAbsent(r.modelFolder, this::listFolder);
        for (Recipe.Component c : r.components)
          folders.computeIfAbsent(c.folder(), this::listFolder);
      }

      Map<String, Object> assign = Json.obj(conf().get("assign"));
      Set<String> claimed = new HashSet<>();
      Set<String> ids = new HashSet<>();
      Set<String> modelFolders = new java.util.LinkedHashSet<>();
      List<Offer> offers = new ArrayList<>();
      for (Recipe r : recipes)
      {
        modelFolders.add(r.modelFolder);
        for (String file : folders.get(r.modelFolder))
        {
          Object forced = assign.get(file);
          if (forced != null ? !r.id.equals(forced) : !r.matches(file))
            continue;
          if (!claimed.add(r.modelFolder + "/" + file))
            continue;
          Map<String, String> found = new LinkedHashMap<>();
          List<String> problems = new ArrayList<>();
          for (Recipe.Component c : r.components)
          {
            String picked = c.pick(folders.get(c.folder()));
            if (picked != null)
              found.put(c.name(), picked);
            else if (!c.optional())
              problems.add("no " + c.name() + " in ComfyUI's '" + c.folder() + "' folder (looking for "
                  + c.match().get(0) + ")");
          }
          for (String type : r.classTypes())
            if (!nodes.contains(type))
              problems.add("this ComfyUI has no '" + type + "' node — update it, or install the custom node");
          String id = stem(file);
          if (!ids.add(id.toLowerCase(Locale.ROOT)))
            problems.add("another file already goes by the name '" + id + "'");
          else if (cfg.findModel(id) != null || cfg.findProxy(id) != null)
            problems.add("a model configured in hemera is already called '" + id + "'");
          offers.add(new Offer(id, r, file, found, problems));
        }
      }

      // What is left over in the folders models live in: probably a model no recipe recognises by
      // name. Files that are really a VAE or an encoder, seen through an overlapping path, are not.
      Set<String> parts = new HashSet<>();
      folders.forEach((folder, files) -> {
        if (!modelFolders.contains(folder))
          files.forEach(f -> parts.add(leaf(f)));
      });
      List<Map<String, Object>> unassigned = new ArrayList<>();
      for (String folder : modelFolders)
        for (String file : folders.get(folder))
          if (!claimed.contains(folder + "/" + file) && !parts.contains(leaf(file))
              && !NOT_A_MODEL.matcher(file).find() && assign.get(file) == null)
          {
            List<String> fits = new ArrayList<>();
            for (Recipe r : recipes)
              if (r.modelFolder.equals(folder))
                fits.add(r.id);
            unassigned.add(Json.map("file", file, "folder", folder, "recipes", fits));
          }
      return new Snapshot(true, null, version, offers, unassigned, now);
    }
    catch (Exception e)
    {
      nodes = null;
      Log.debug("comfyui", "not reachable at " + url() + ": " + e);
      return new Snapshot(false, e instanceof HttpError ? e.getMessage()
          : "ComfyUI is not answering at " + url() + " (" + e.getClass().getSimpleName() + ")",
          null, List.of(), List.of(), now);
    }
  }

  /** Recipes from <home>/comfyui/*.json first, so one of the same id replaces the built-in. */
  private List<Recipe> recipes()
  {
    Map<String, Recipe> out = new LinkedHashMap<>();
    Path own = cfg.home.resolve("comfyui");
    if (Files.isDirectory(own))
      try (Stream<Path> list = Files.list(own))
      {
        for (Path p : list.filter(f -> f.toString().endsWith(".json")).sorted().toList())
          try
          {
            Recipe r = new Recipe(Json.parseObj(Files.readString(p, StandardCharsets.UTF_8)));
            out.put(r.id, r);
          }
          catch (Exception e)
          {
            Log.warn("comfyui", "ignoring recipe " + p + ": " + e.getMessage());
          }
      }
      catch (IOException e)
      {
        Log.warn("comfyui", "cannot read " + own + ": " + e.getMessage());
      }
    for (String name : BUILTIN)
      try (InputStream is = ComfyBridge.class.getResourceAsStream("/comfyui/" + name + ".json"))
      {
        if (is == null)
          throw new IOException("missing from the jar");
        Recipe r = new Recipe(Json.parseObj(new String(is.readAllBytes(), StandardCharsets.UTF_8)));
        out.putIfAbsent(r.id, r);
      }
      catch (Exception e)
      {
        Log.warn("comfyui", "built-in recipe '" + name + "' is unusable: " + e.getMessage());
      }
    return new ArrayList<>(out.values());
  }

  private List<String> listFolder(String folder)
  {
    List<String> out = new ArrayList<>();
    try
    {
      for (Object o : Json.arr(get("/models/" + enc(folder), 10)))
        out.add(String.valueOf(o));
    }
    catch (Exception e)
    {
      Log.debug("comfyui", "no model folder '" + folder + "': " + e.getMessage());
    }
    return out;
  }

  private static String leaf(String file)
  {
    return file.substring(Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\')) + 1);
  }

  private static String stem(String file)
  {
    String name = leaf(file);
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }

  /** An offer the way a client sees it: enough to pick the endpoint and draw a form. */
  public static Map<String, Object> describe(Offer o)
  {
    Recipe r = o.recipe();
    return Json.map(
        "kind", r.kind,
        "backend", "comfyui",
        "family", r.id,
        "title", r.title,
        "description", r.description,
        "endpoint", endpoint(r.kind),
        "parameters", r.describeParams(o.components()));
  }

  /** Everything the configure page shows about the bridge. */
  public Map<String, Object> status(boolean refresh)
  {
    Snapshot s = snapshot(refresh);
    List<Object> models = new ArrayList<>();
    for (Offer o : s.offers())
      models.add(Json.map("id", o.id(), "kind", o.recipe().kind, "recipe", o.recipe().id,
          "title", o.recipe().title, "folder", o.recipe().modelFolder, "file", o.file(),
          "components", o.components(), "ready", o.ready(), "problems", o.problems()));
    boolean managed;
    synchronized (this)
    {
      managed = process != null && process.isAlive();
    }
    return Json.map(
        "configured", configured(),
        "url", url(),
        "reachable", s.reachable(),
        "error", s.error(),
        "version", s.version(),
        "startedByHemera", managed,
        "activeJobs", active.get(),
        "models", models,
        "unassigned", s.unassigned());
  }

  // ---- running a job ------------------------------------------------------

  public Run newRun()
  {
    return new Run();
  }

  /**
   * Refuse a request that cannot work before it becomes a job, so the client hears about a
   * missing image or an impossible size in the reply to its POST rather than from a failed job.
   */
  public void check(Offer offer, Map<String, Object> request) throws Exception
  {
    offer.recipe().build(offer.file(), offer.components(), request, SUBFOLDER, image -> "check");
  }

  /** Queue the request with ComfyUI and wait for its result file. Blocks for as long as that takes. */
  public Output execute(Offer offer, Map<String, Object> request, Run run) throws Exception
  {
    active.incrementAndGet();
    WebSocket ws = null;
    try
    {
      ensureUp();
      String clientId = UUID.randomUUID().toString();
      Recipe.Built built = offer.recipe().build(offer.file(), offer.components(), request,
          SUBFOLDER + "/" + offer.recipe().kind + "_" + clientId.substring(0, 8), this::upload);
      run.used = built.used();
      ws = watch(clientId, run, built.graph().size());
      if (ws == null)
        run.started = true; // nothing will say when it leaves the queue, so do not claim it is waiting

      mayHoldModels = true;
      String promptId = queue(built.graph(), clientId, offer);
      run.promptId = promptId;
      if (run.cancelled)
        run.cancel();
      Log.info("comfyui", "'" + offer.id() + "' queued as " + promptId + " (" + built.graph().size() + " nodes)");

      int unreachable = 0;
      for (long tick = 1;; tick++)
      {
        Thread.sleep(1000);
        Map<String, Object> entry;
        try
        {
          entry = Json.obj(Json.obj(get("/history/" + promptId, 15)).get(promptId));
          // a ComfyUI that restarted has forgotten the job; waiting on would never end
          if (entry.isEmpty() && tick % 15 == 0 && !run.cancelled
              && !Json.write(get("/queue", 15)).contains(promptId))
          {
            // unless it finished in the moment between the two questions
            entry = Json.obj(Json.obj(get("/history/" + promptId, 15)).get(promptId));
            if (entry.isEmpty())
              throw new HttpError(502, "ComfyUI no longer knows this job — it was probably restarted");
          }
          unreachable = 0;
        }
        catch (IOException e)
        {
          if (++unreachable < 20)
            continue;
          throw new HttpError(502, "ComfyUI stopped answering while generating — it most likely crashed"
              + " or ran out of memory");
        }
        if (entry.isEmpty())
        {
          if (run.cancelled && tick % 5 == 0)
            run.cancel();
          continue;
        }
        if (run.cancelled)
          throw new HttpError(409, "the request was cancelled");
        Map<String, Object> status = Json.obj(entry.get("status"));
        if (!"success".equals(Json.str(status, "status_str", "")))
          throw new HttpError(502, failure(status));
        run.progress = 100;
        return output(Json.obj(Json.obj(entry.get("outputs")).get(offer.recipe().outputNode)), offer);
      }
    }
    finally
    {
      if (ws != null)
        ws.abort();
      lastUsed = System.currentTimeMillis();
      active.decrementAndGet();
    }
  }

  private String queue(Map<String, Object> graph, String clientId, Offer offer) throws Exception
  {
    HttpResponse<String> resp = send("/prompt", Json.map("prompt", graph, "client_id", clientId), 60);
    Map<String, Object> body = parse(resp.body());
    if (resp.statusCode() / 100 == 2 && body.get("prompt_id") instanceof String id)
      return id;
    // ComfyUI validates the whole graph before queueing; say which node it disliked and why
    StringBuilder why = new StringBuilder(Json.str(Json.obj(body.get("error")), "message", "HTTP " + resp.statusCode()));
    for (Object n : Json.obj(body.get("node_errors")).values())
      for (Map<String, Object> err : Json.arrOfObj(Json.obj(n).get("errors")))
        why.append("; ").append(Json.str(Json.obj(n), "class_type", "?")).append(": ")
            .append(Json.str(err, "message", "")).append(' ').append(Json.str(err, "details", ""));
    throw new HttpError(502, "ComfyUI refused the '" + offer.recipe().id + "' workflow: " + why.toString().trim());
  }

  private static String failure(Map<String, Object> status)
  {
    for (Object m : Json.arr(status.get("messages")))
      if (m instanceof List<?> pair && pair.size() == 2 && "execution_error".equals(pair.get(0)))
      {
        Map<String, Object> d = Json.obj(pair.get(1));
        return "ComfyUI failed in " + Json.str(d, "node_type", "a node") + ": "
            + Router.abbreviate(Json.str(d, "exception_message", "unknown error").trim(), 600);
      }
    return "ComfyUI ended the job as '" + Json.str(status, "status_str", "unknown") + "'";
  }

  private static Output output(Map<String, Object> nodeOutput, Offer offer)
  {
    for (Object v : nodeOutput.values())
      for (Map<String, Object> f : Json.arrOfObj(v))
        if (f.get("filename") instanceof String name)
          return new Output(name, Json.str(f, "subfolder", ""), Json.str(f, "type", "output"), contentType(name));
    throw new HttpError(502, "ComfyUI finished '" + offer.id() + "' without producing a file");
  }

  public static String contentType(String name)
  {
    String n = name.toLowerCase(Locale.ROOT);
    if (n.endsWith(".glb")) return "model/gltf-binary";
    if (n.endsWith(".mp4")) return "video/mp4";
    if (n.endsWith(".webm")) return "video/webm";
    if (n.endsWith(".mkv")) return "video/x-matroska";
    if (n.endsWith(".png")) return "image/png";
    if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
    if (n.endsWith(".webp")) return "image/webp";
    return "application/octet-stream";
  }

  /**
   * Follow the job over ComfyUI's websocket, which is the only place it reports progress. Purely
   * informative: completion is read from the history, so a socket that fails costs nothing but
   * the percentage.
   */
  private WebSocket watch(String clientId, Run run, int totalNodes)
  {
    Set<String> done = new HashSet<>();
    String[] current = { null };
    WebSocket.Listener listener = new WebSocket.Listener()
    {
      private final StringBuilder text = new StringBuilder();

      @Override
      public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last)
      {
        text.append(data);
        if (last)
        {
          try
          {
            Map<String, Object> msg = Json.parseObj(text.toString());
            Map<String, Object> d = Json.obj(msg.get("data"));
            double part = 0;
            switch (Json.str(msg, "type", ""))
            {
              case "execution_start" -> run.started = true;
              case "execution_cached" -> Json.arr(d.get("nodes")).forEach(n -> done.add(String.valueOf(n)));
              case "executing" -> {
                run.started = true;
                if (current[0] != null)
                  done.add(current[0]);
                current[0] = d.get("node") == null ? null : String.valueOf(d.get("node"));
              }
              case "progress" -> part = Json.numd(d, "value", 0) / Math.max(1, Json.numd(d, "max", 1));
              default -> {
              }
            }
            run.progress = (int) Math.min(99, Math.max(run.progress, 100 * (done.size() + part) / totalNodes));
          }
          catch (RuntimeException ignored)
          {
            // a message hemera does not understand is no reason to stop listening
          }
          text.setLength(0);
        }
        socket.request(1);
        return null;
      }
    };
    try
    {
      URI uri = URI.create(url().replaceFirst("^http", "ws") + "/ws?clientId=" + clientId);
      return HC.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(uri, listener)
          .get(8, TimeUnit.SECONDS);
    }
    catch (Exception e)
    {
      Log.debug("comfyui", "no progress for this job, websocket failed: " + e);
      return null;
    }
  }

  /** Images are stored under their own hash, so sending the same one twice uploads nothing new. */
  private String upload(byte[] image) throws Exception
  {
    String ext = image.length > 3 && (image[0] & 0xFF) == 0x89 && image[1] == 'P' ? "png"
        : image.length > 2 && (image[0] & 0xFF) == 0xFF && (image[1] & 0xFF) == 0xD8 ? "jpg"
            : image.length > 12 && image[8] == 'W' && image[9] == 'E' ? "webp" : null;
    if (ext == null)
      throw new HttpError(400, "the image must be a PNG, JPEG or WebP file");
    String name = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image), 0, 12) + "." + ext;
    String boundary = Multipart.randomBoundary();
    byte[] body = Multipart.write(List.of(
        Multipart.Part.file("image", name, "image/" + ("jpg".equals(ext) ? "jpeg" : ext), image),
        Multipart.Part.field("subfolder", SUBFOLDER),
        Multipart.Part.field("type", "input"),
        Multipart.Part.field("overwrite", "true")), boundary);
    HttpResponse<String> resp = HC.send(HttpRequest.newBuilder(URI.create(url() + "/upload/image"))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .timeout(Duration.ofMinutes(2))
        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() / 100 != 2)
      throw new HttpError(502, "ComfyUI did not accept the image (HTTP " + resp.statusCode() + ")");
    Map<String, Object> stored = parse(resp.body());
    String sub = Json.str(stored, "subfolder", "");
    return (sub.isEmpty() ? "" : sub + "/") + Json.str(stored, "name", name);
  }

  // ---- results ------------------------------------------------------------

  public byte[] fetch(Output o) throws Exception
  {
    try (InputStream is = open(o).body())
    {
      return is.readAllBytes();
    }
  }

  /** Pass a result straight through to a client without holding it in memory. */
  public void stream(Output o, HttpExchange ex, String downloadName) throws Exception
  {
    HttpResponse<InputStream> resp = open(o);
    try (InputStream is = resp.body())
    {
      Router.cors(ex);
      ex.getResponseHeaders().set("Content-Type", o.contentType());
      ex.getResponseHeaders().set("Content-Disposition", "inline; filename=\"" + downloadName + "\"");
      long length = resp.headers().firstValueAsLong("Content-Length").orElse(0);
      if ("HEAD".equals(ex.getRequestMethod()))
      {
        ex.sendResponseHeaders(200, -1);
        return;
      }
      ex.sendResponseHeaders(200, length);
      try (OutputStream os = ex.getResponseBody())
      {
        is.transferTo(os);
      }
    }
  }

  private HttpResponse<InputStream> open(Output o) throws Exception
  {
    HttpResponse<InputStream> resp = HC.send(HttpRequest.newBuilder(URI.create(url() + "/view?filename="
        + enc(o.filename()) + "&subfolder=" + enc(o.subfolder()) + "&type=" + enc(o.type())))
        .timeout(Duration.ofMinutes(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    if (resp.statusCode() / 100 != 2)
    {
      resp.body().close();
      throw new HttpError(resp.statusCode() == 404 ? 404 : 502, "the result '" + o.filename()
          + "' is no longer in ComfyUI's output folder (HTTP " + resp.statusCode() + ")");
    }
    return resp;
  }

  // ---- the ComfyUI process ------------------------------------------------

  private boolean answering()
  {
    try
    {
      get("/system_stats", 3);
      return true;
    }
    catch (Exception e)
    {
      return false;
    }
  }

  /**
   * Make sure there is a ComfyUI to talk to. Given only a URL that is somebody else's job; given
   * the folder it lives in, hemera starts it the way a person would, with its own Python.
   */
  private synchronized void ensureUp() throws Exception
  {
    if (!configured())
      throw new HttpError(503, "ComfyUI is not configured — give hemera its URL or folder in the web UI (/configure/)");
    if (answering())
      return;
    if (dir().isEmpty())
      throw new HttpError(503, "ComfyUI is not answering at " + url() + " — start it, or give hemera"
          + " its folder so it can");
    if (process == null || !process.isAlive())
      start();
    long deadline = System.currentTimeMillis() + Json.num(conf(), "startTimeoutSeconds", 300) * 1000;
    while (!answering())
    {
      if (!process.isAlive())
        throw new HttpError(503, "ComfyUI exited while starting (code " + process.exitValue()
            + ") — its output is in the hemera log at debug level");
      if (System.currentTimeMillis() > deadline)
        throw new HttpError(503, "ComfyUI did not come up at " + url() + " in time");
      Thread.sleep(1000);
    }
    Log.info("comfyui", "ComfyUI is ready at " + url());
    snapshot = null;
  }

  private void start() throws IOException
  {
    Path home = Path.of(Config.expandUser(dir()));
    if (!Files.isRegularFile(home.resolve("main.py")))
      throw new HttpError(503, "'" + home + "' is not a ComfyUI folder: there is no main.py in it");
    URI uri = URI.create(url());
    List<String> cmd = new ArrayList<>(List.of(python(home), "main.py",
        "--listen", uri.getHost(), "--port", String.valueOf(uri.getPort() < 0 ? 80 : uri.getPort())));
    cmd.addAll(Json.strList(conf(), "args"));
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(home.toFile()).redirectErrorStream(true);
    Json.obj(conf().get("env")).forEach((k, v) -> pb.environment().put(k, String.valueOf(v)));
    Log.info("comfyui", "Starting ComfyUI: " + String.join(" ", cmd) + " (in " + home + ")");
    process = pb.start();
    // if memory runs out, the kernel should take ComfyUI rather than the gateway
    try
    {
      Path oom = Path.of("/proc/" + process.pid() + "/oom_score_adj");
      if (Files.isWritable(oom))
        Files.writeString(oom, "500\n");
    }
    catch (IOException | RuntimeException ignored)
    {
    }
    Process p = process;
    Thread.ofVirtual().name("comfyui-out").start(() -> {
      try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)))
      {
        String line;
        while ((line = r.readLine()) != null)
          Log.debug("comfyui", line);
      }
      catch (IOException ignored)
      {
      }
    });
  }

  /** The configured interpreter, or the virtualenv a ComfyUI install normally keeps beside it. */
  private String python(Path home)
  {
    String explicit = Json.str(conf(), "python", "").trim();
    if (!explicit.isEmpty())
      return Config.expandUser(explicit);
    for (String candidate : new String[] { "venv", ".venv", "../venv", "../.venv" })
    {
      Path p = home.resolve(candidate).resolve("bin/python").normalize();
      if (Files.isExecutable(p))
        return p.toString();
    }
    return "python3";
  }

  private synchronized void stopProcess()
  {
    if (process != null && process.isAlive())
      process.destroy();
  }

  /**
   * ComfyUI keeps the last models in memory for the next job, which on a machine shared with the
   * language models is memory they cannot have. After a quiet spell hemera asks for it back.
   */
  private void idleLoop()
  {
    while (true)
    {
      try
      {
        Thread.sleep(20_000);
        long keep = Json.num(conf(), "keepAliveSeconds", 300);
        if (!mayHoldModels || keep < 0 || active.get() > 0
            || System.currentTimeMillis() - lastUsed < keep * 1000)
          continue;
        mayHoldModels = false;
        post("/free", Json.map("unload_models", Boolean.TRUE, "free_memory", Boolean.TRUE), 30);
        Log.info("comfyui", "Asked idle ComfyUI to unload its models (keep-alive " + keep + "s)");
      }
      catch (InterruptedException e)
      {
        return;
      }
      catch (Exception e)
      {
        Log.debug("comfyui", "idle unload failed: " + e);
      }
    }
  }

  // ---- HTTP ---------------------------------------------------------------

  private Object get(String path, int timeoutSeconds) throws IOException, InterruptedException
  {
    HttpResponse<String> resp = HC.send(HttpRequest.newBuilder(URI.create(url() + path))
        .timeout(Duration.ofSeconds(timeoutSeconds)).GET().build(), HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() / 100 != 2)
      throw new HttpError(502, "ComfyUI answered " + path + " with HTTP " + resp.statusCode());
    return Json.parse(resp.body());
  }

  private HttpResponse<String> send(String path, Map<String, Object> body, int timeoutSeconds)
      throws IOException, InterruptedException
  {
    return HC.send(HttpRequest.newBuilder(URI.create(url() + path))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private void post(String path, Map<String, Object> body, int timeoutSeconds) throws IOException, InterruptedException
  {
    send(path, body, timeoutSeconds);
  }

  private static Map<String, Object> parse(String body)
  {
    try
    {
      return Json.parseObj(body);
    }
    catch (RuntimeException e)
    {
      return new LinkedHashMap<>();
    }
  }

  private static String enc(String s)
  {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
