package ing.aether.hemera.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ing.aether.hemera.Log;
import ing.aether.hemera.config.Config;
import ing.aether.hemera.http.HttpError;
import ing.aether.hemera.json.Json;

/**
 * Spawns and supervises engine processes (llama-server etc.), one per configured model.
 * Ports are ephemeral localhost ports; idle instances are reaped after their keep-alive.
 */
public final class InstanceManager
{
  private final Config cfg;
  private final Map<String, Instance> instances = new ConcurrentHashMap<>();
  private final Map<String, Object> startLocks = new ConcurrentHashMap<>();
  private final HttpClient health = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();

  public InstanceManager(Config cfg)
  {
    this.cfg = cfg;
    Thread.ofVirtual().name("instance-reaper").start(this::reaperLoop);
    Runtime.getRuntime().addShutdownHook(new Thread(this::stopAll));
  }

  /** A load started from the UI: in progress while error is null, otherwise a failure to report. */
  public record LoadState(String name, long startedAt, String error)
  {
    public boolean failed()
    {
      return error != null;
    }
  }

  private final Map<String, LoadState> loads = new ConcurrentHashMap<>();

  /**
   * Start a model in the background and report progress through {@link #loadStates()}.
   * Loading a large model takes minutes, far longer than a browser will wait on one request,
   * so the UI polls instead of blocking.
   */
  public void loadAsync(Map<String, Object> model)
  {
    String name = Json.str(model, "name", null);
    if (name == null)
      throw new HttpError(400, "model has no name");
    if (get(name) != null)
      return; // already running
    LoadState existing = loads.get(name);
    if (existing != null && !existing.failed())
      return; // already loading
    loads.put(name, new LoadState(name, System.currentTimeMillis(), null));
    Thread.ofVirtual().name("load-" + name).start(() -> {
      long started = System.currentTimeMillis();
      try
      {
        ensure(model);
        loads.remove(name);
      }
      catch (Exception e)
      {
        Log.error("instance", "Loading '" + name + "' failed", e);
        loads.put(name, new LoadState(name, started, String.valueOf(e.getMessage())));
      }
    });
  }

  public List<LoadState> loadStates()
  {
    return List.copyOf(loads.values());
  }

  public void clearLoadState(String name)
  {
    loads.remove(name);
  }

  /** Ensure the model's engine process is running and warm; returns the instance. */
  public Instance ensure(Map<String, Object> model) throws Exception
  {
    String name = Json.str(model, "name", null);
    if (name == null)
      throw new HttpError(400, "model has no name");
    Object lock = startLocks.computeIfAbsent(name, k -> new Object());
    synchronized (lock)
    {
      Instance existing = instances.get(name);
      if (existing != null)
      {
        if (existing.alive())
        {
          existing.touch();
          return existing;
        }
        instances.remove(name);
      }
      Instance started = start(model);
      instances.put(name, started);
      loads.remove(name);
      return started;
    }
  }

  /**
   * stable-diffusion.cpp flags that name a piece of a split pipeline. Their presence in a model's
   * extraFiles is what distinguishes a standalone diffusion transformer from an all-in-one
   * checkpoint (see the --diffusion-model / --model choice below).
   */
  private static final java.util.Set<String> SD_COMPONENT_FLAGS = java.util.Set.of(
      "vae", "llm", "llm_vision", "t5xxl", "clip_l", "clip_g", "clip_vision", "qwen2vl",
      "qwen2vl_vision", "tae", "taesd", "audio-vae", "embeddings-connectors", "high-noise-diffusion-model", "uncond-diffusion-model");

  private Instance start(Map<String, Object> model) throws Exception
  {
    String name = Json.str(model, "name", "?");
    String engineName = Json.str(model, "engine", "llama.cpp");
    Map<String, Object> engineCfg = cfg.engine(engineName);

    Path binDir;
    String bin;
    String engineDesc;
    if (Engines.isManualBinary(cfg, engineName))
    {
      Path binaryPath = cfg.resolve(Json.str(engineCfg, "binaryPath", ""));
      bin = binaryPath.toString();
      binDir = binaryPath.toAbsolutePath().getParent();
      engineDesc = engineName + " (manual: " + bin + ")";
    }
    else
    {
      Map<String, Object> inst = Engines.resolve(cfg, model);
      binDir = cfg.resolve(Json.str(inst, "binDir", Json.str(inst, "path", "")));
      bin = binDir.resolve(Json.str(engineCfg, "serverBin", "llama-server")).toString();
      engineDesc = engineName + " " + Json.str(inst, "version", "?") + " " + Json.str(inst, "variant", "?");
    }
    if (!Files.isRegularFile(Path.of(bin)))
      throw new HttpError(500, "engine binary missing: " + bin);

    int port = freePort();
    String file = Config.expandUser(Json.str(model, "file", ""));
    String mmproj = Config.expandUser(Json.str(model, "mmproj", ""));
    Runnable release = gpuReleaser;
    if (release != null)
      release.run();
    makeRoom(name, estimate(model, file, mmproj));

    List<String> cmd = new ArrayList<>();
    cmd.add(bin);
    // Anything that is a llama-server gets the -m/--mmproj treatment, including forks such as
    // the one ollama bundles; sd-server gets stable-diffusion.cpp's own spelling. Override with
    // "argStyle": "llama" | "sd" | "custom" on the engine.
    String style = Engines.argStyle(cfg, engineName);
    Map<String, Object> extraFiles = Json.obj(model.get("extraFiles"));
    if ("llama".equals(style))
    {
      cmd.add("--host");
      cmd.add("127.0.0.1");
      cmd.add("--port");
      cmd.add(String.valueOf(port));
      if (!file.isEmpty())
      {
        cmd.add("-m");
        cmd.add(file);
      }
      if (!mmproj.isEmpty())
      {
        cmd.add("--mmproj");
        cmd.add(mmproj);
      }
    }
    else if ("sd".equals(style))
    {
      cmd.add("--listen-ip");
      cmd.add("127.0.0.1");
      cmd.add("--listen-port");
      cmd.add(String.valueOf(port));
      if (!file.isEmpty())
      {
        // A split pipeline (Qwen-Image, Flux, ...) names its transformer with --diffusion-model
        // and loads the text encoder and VAE separately; a self-contained checkpoint (SD1.5,
        // SDXL) carries all three and is loaded whole with --model. The companion files are the
        // tell, so the flag follows them rather than asking the user to know the difference.
        boolean split = extraFiles.keySet().stream()
            .anyMatch(k -> SD_COMPONENT_FLAGS.contains(k.replaceFirst("^--?", "")));
        cmd.add(split ? "--diffusion-model" : "--model");
        cmd.add(file);
      }
    }
    else if ("whisper".equals(style))
    {
      cmd.add("--host");
      cmd.add("127.0.0.1");
      cmd.add("--port");
      cmd.add(String.valueOf(port));
      if (!file.isEmpty())
      {
        cmd.add("-m");
        cmd.add(file);
      }
      List<String> given = new ArrayList<>(Json.strList(engineCfg, "defaultArgs"));
      given.addAll(Json.strList(model, "args"));
      // whisper-server assumes English unless told otherwise; OpenAI detects the language when
      // a request names none, so start it that way and let each request narrow it down.
      if (!given.contains("-l") && !given.contains("--language"))
      {
        cmd.add("-l");
        cmd.add("auto");
      }
      // On its own it reads only WAV, MP3 and FLAC. Browsers record WebM/Ogg and phones M4A, so
      // let it hand everything to ffmpeg when there is one.
      if (!given.contains("--convert") && onPath("ffmpeg"))
        cmd.add("--convert");
    }
    else if ("audiocpp".equals(style))
    {
      // audiocpp_server learns its models from a config file, so each hemera model gets one of
      // its own: a single entry under the hemera name, which is what requests then ask for.
      Map<String, Object> entry = Json.map("id", name,
          "family", Json.str(model, "family", ""),
          "path", file,
          "task", Json.str(model, "task", "tts"),
          "mode", Json.str(model, "mode", "offline"));
      if (Json.str(model, "family", "").isEmpty())
        throw new HttpError(400, "model '" + name + "' needs a \"family\" (audio.cpp's model family, e.g. voxcpm2)");
      for (String k : List.of("load_options", "session_options", "default_request_options", "live_ingest"))
        if (model.get(k) instanceof Map<?, ?> m && !m.isEmpty())
          entry.put(k, m);
      // a voice changer runs as long as someone talks through it, and goes quiet while they
      // are muted: audio.cpp's limits for a dictation (10 min, 30 s of silence) would cut it off
      if ("vc".equals(entry.get("task")) && !entry.containsKey("live_ingest"))
        entry.put("live_ingest", Json.map("total_timeout_ms", 0, "idle_timeout_ms", 0));
      Path conf = cfg.home.resolve("run").resolve(name.replaceAll("[^A-Za-z0-9._-]", "_") + ".audiocpp.json");
      Files.createDirectories(conf.getParent());
      // the voice library doubles as audio.cpp's, so live voice conversion can name a voice
      Files.writeString(conf, Json.writePretty(Json.map("host", "127.0.0.1", "port", port,
          "lazy_load", false, "voice_dir", cfg.resolve(Json.str(cfg.data(), "voicesDir", "voices")).toString(),
          "models", List.of(entry))));
      cmd.add("--config");
      cmd.add(conf.toString());
      cmd.add("--host");
      cmd.add("127.0.0.1");
      cmd.add("--port");
      cmd.add(String.valueOf(port));
      cmd.add("--no-ui");
    }
    if (!"custom".equals(style) && !"audiocpp".equals(style))
    {
      for (Map.Entry<String, Object> e : extraFiles.entrySet())
      {
        String flag = e.getKey().startsWith("-") ? e.getKey() : "--" + e.getKey();
        cmd.add(flag);
        cmd.add(Config.expandUser(String.valueOf(e.getValue())));
      }
    }
    for (String a : Json.strList(engineCfg, "defaultArgs"))
      cmd.add(substitute(a, port, file, mmproj));
    for (String a : Json.strList(model, "args"))
      cmd.add(substitute(a, port, file, mmproj));

    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(true);
    pb.directory(binDir.toFile());
    Map<String, String> env = pb.environment();
    for (Map.Entry<String, Object> e : Json.obj(engineCfg.get("env")).entrySet())
      env.put(e.getKey(), String.valueOf(e.getValue()));
    for (Map.Entry<String, Object> e : Json.obj(model.get("env")).entrySet())
      env.put(e.getKey(), String.valueOf(e.getValue()));

    // The engine's own directory first, then whatever its GPU runtime needs (ROCm, CUDA,
    // oneAPI, ...), then anything the environment or model config already asked for.
    LibraryResolver.Result libs = LibraryResolver.resolve(cfg, engineName, binDir);
    LinkedHashSet<String> ldPath = new LinkedHashSet<>();
    ldPath.add(binDir.toString());
    for (Path p : libs.searchPaths())
      ldPath.add(p.toString());
    String inherited = env.get("LD_LIBRARY_PATH");
    if (inherited != null && !inherited.isEmpty())
      ldPath.addAll(List.of(inherited.split(":")));
    env.put("LD_LIBRARY_PATH", String.join(":", ldPath));

    // GPU backends split into their own directory (as Ollama's bundled fork does for
    // ROCm/Vulkan/CUDA) aren't linked into the binary — ggml dlopen's them itself and needs
    // an explicit file path, not just a search directory, or it silently stays on CPU.
    if (!env.containsKey("GGML_BACKEND_PATH"))
      findBackendPlugin(Json.strList(engineCfg, "libraryPaths"))
          .ifPresent(p -> env.put("GGML_BACKEND_PATH", p.toString()));

    if (!libs.missing().isEmpty())
      Log.warn("instance", "'" + name + "' may fall back to CPU: " + libs.missing().size()
          + " shared librar" + (libs.missing().size() == 1 ? "y" : "ies") + " not found ("
          + String.join(", ", libs.missing()) + "). Install the matching runtime, or add its"
          + " directory to engines." + engineName + ".libraryPaths in hemera.json.");
    else if (!libs.resolved().isEmpty())
      Log.info("instance", "Runtime libraries for '" + name + "' found in "
          + String.join(", ", libs.searchPaths().stream().map(Path::toString).toList()));

    Log.info("instance", "Starting '" + name + "' on port " + port + " (" + engineDesc + ")");
    Log.debug("instance", "command: " + String.join(" ", cmd));
    Log.debug("instance", "LD_LIBRARY_PATH=" + env.get("LD_LIBRARY_PATH"));

    Process proc = pb.start();
    preferOomVictim(proc);
    long keepAlive = Json.num(model, "keepAliveSeconds", cfg.defaultKeepAlive());
    Instance instance = new Instance(name, proc, port, engineDesc, keepAlive);
    pumpOutput(instance);

    String healthPath = Json.str(engineCfg, "healthPath", "/health");
    long timeoutMs = Json.num(cfg.data(), "loadTimeoutSeconds", 600) * 1000L;
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline)
    {
      if (!proc.isAlive())
        throw new HttpError(500, "engine process for '" + name + "' exited (code " + proc.exitValue()
            + "). Recent output:\n" + String.join("\n", instance.recentOutput()));
      try
      {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + healthPath))
            .timeout(Duration.ofMillis(700)).GET().build();
        HttpResponse<String> resp = health.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 200)
        {
          Log.info("instance", "'" + name + "' is ready on port " + port);
          instance.touch();
          return instance;
        }
        // 503 = still loading the model: keep waiting
      }
      catch (IOException | InterruptedException ignored)
      {
      }
      Thread.sleep(500);
    }
    stopProcess(proc);
    throw new HttpError(500, "timeout waiting for '" + name + "' to become ready ("
        + timeoutMs / 1000 + "s; adjust loadTimeoutSeconds in hemera.json)");
  }

  /**
   * Finds a ggml GPU backend plugin (libggml-hip.so, libggml-vulkan.so, ...) sitting directly
   * in one of the engine's configured libraryPaths, in the order given so earlier entries win.
   * libggml-base.so and the libggml-cpu-* variants are not backend plugins and are skipped.
   */
  private static java.util.Optional<Path> findBackendPlugin(List<String> libraryPaths)
  {
    for (String p : libraryPaths)
    {
      Path dir = Path.of(Config.expandUser(p));
      if (!Files.isDirectory(dir))
        continue;
      try (var stream = Files.list(dir))
      {
        java.util.Optional<Path> found = stream
            .filter(f -> {
              String fn = f.getFileName().toString();
              return fn.startsWith("libggml-") && fn.endsWith(".so")
                  && !fn.startsWith("libggml-cpu") && !fn.equals("libggml-base.so");
            })
            .findFirst();
        if (found.isPresent())
          return found;
      }
      catch (IOException e)
      {
        Log.debug("instance", "could not scan " + dir + " for backend plugins: " + e);
      }
    }
    return java.util.Optional.empty();
  }

  /** Things outside this manager that hold memory and can give it back when idle (ComfyUI). */
  private final List<java.util.function.BooleanSupplier> reclaimers = new java.util.concurrent.CopyOnWriteArrayList<>();

  private volatile Runnable gpuReleaser;

  /** Called before an engine starts: lets ComfyUI step aside, since the two hang each other on a shared GPU. */
  public void setGpuReleaser(Runnable releaser)
  {
    gpuReleaser = releaser;
  }

  /**
   * Unload every engine so ComfyUI has the GPU to itself. An engine still answering a request is
   * waited for rather than cut off.
   */
  public void clearGpu(long maxWaitSeconds)
  {
    long deadline = System.currentTimeMillis() + maxWaitSeconds * 1000;
    while (instances.values().stream().anyMatch(i -> i.alive() && i.busy())
        && System.currentTimeMillis() < deadline)
      try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
    for (Instance i : List.copyOf(instances.values()))
      if (!i.busy())
      {
        Log.info("instance", "Unloading '" + i.name + "' so ComfyUI has the GPU to itself");
        stop(i.name);
      }
  }

  public void addReclaimer(java.util.function.BooleanSupplier reclaimer)
  {
    reclaimers.add(reclaimer);
  }

  /** Bytes a model will want: its files, plus working memory for the context or the image being drawn. */
  private long estimate(Map<String, Object> model, String file, String mmproj)
  {
    long mb = 1024L * 1024;
    long total = size(file) + size(mmproj);
    for (Object v : Json.obj(model.get("extraFiles")).values())
      total += size(Config.expandUser(String.valueOf(v)));
    boolean sd = "sd".equals(Engines.argStyle(cfg, Json.str(model, "engine", "llama.cpp")));
    long extra = sd ? 8192 * mb : 2048 * mb;
    return Json.num(model, "memoryMB", 0) > 0 ? Json.num(model, "memoryMB", 0) * mb : total + extra;
  }

  private static long size(String path)
  {
    try
    {
      return path.isEmpty() ? 0 : Files.size(Path.of(path));
    }
    catch (IOException e)
    {
      return 0;
    }
  }

  /**
   * Memory the GPU runtime and the kernel can still hand out. On a machine where the GPU shares
   * system RAM the GPU's own counter is the tighter one, so the smaller of the two wins.
   */
  public static long available()
  {
    long avail = Long.MAX_VALUE;
    try
    {
      for (String line : Files.readAllLines(Path.of("/proc/meminfo")))
        if (line.startsWith("MemAvailable:"))
          avail = Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024;
      try (var cards = Files.newDirectoryStream(Path.of("/sys/class/drm"), "card?"))
      {
        for (Path card : cards)
        {
          Path dev = card.resolve("device");
          long total = Long.parseLong(Files.readString(dev.resolve("mem_info_gtt_total")).trim());
          long used = Long.parseLong(Files.readString(dev.resolve("mem_info_gtt_used")).trim());
          avail = Math.min(avail, total - used);
        }
      }
    }
    catch (IOException | RuntimeException ignored)
    {
    }
    return avail;
  }

  /**
   * Before a model loads, unload whatever is idle until it fits: the longest-idle model first,
   * then ComfyUI's cached models. A model still serving a request is never touched, and if that
   * is not enough hemera loads anyway and lets the kernel decide, as it did before.
   */
  public synchronized void makeRoom(String forModel, long need)
  {
    long mb = 1024L * 1024;
    long reserve = Json.num(cfg.data(), "memoryReserveMB", 2048) * mb;
    if (available() >= need + reserve)
      return;
    Log.info("instance", "'" + forModel + "' needs about " + need / (1024 * mb) + " GB, "
        + available() / (1024 * mb) + " GB free: unloading idle models");
    while (available() < need + reserve)
    {
      Instance victim = instances.values().stream()
          .filter(i -> i.alive() && !i.busy() && !i.name.equals(forModel))
          .min(Comparator.comparingLong(i -> i.lastUsed)).orElse(null);
      if (victim != null)
        stop(victim.name);
      else if (reclaimers.stream().noneMatch(java.util.function.BooleanSupplier::getAsBoolean))
      {
        Log.warn("instance", "nothing idle left to unload for '" + forModel + "'; "
            + available() / (1024 * mb) + " GB free, loading anyway");
        return;
      }
      for (int i = 0; i < 20 && available() < need + reserve; i++) // the kernel frees pages a moment later
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
    }
  }

  /**
   * Make the engine a more attractive target for the kernel's OOM killer than hemera itself.
   * A model that does not fit should cost you that one model, not the gateway and every other
   * model currently loaded. Raising one's own children's score never requires privileges.
   */
  private static void preferOomVictim(Process proc)
  {
    Path oomScore = Path.of("/proc/" + proc.pid() + "/oom_score_adj");
    try
    {
      if (Files.isWritable(oomScore))
        Files.writeString(oomScore, "500\n");
    }
    catch (IOException | RuntimeException e)
    {
      Log.debug("instance", "could not adjust oom_score_adj for pid " + proc.pid() + ": " + e);
    }
  }

  private static boolean onPath(String program)
  {
    for (String dir : System.getenv().getOrDefault("PATH", "").split(":"))
      if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, program)))
        return true;
    return false;
  }

  private static String substitute(String arg, int port, String file, String mmproj)
  {
    return arg.replace("{port}", String.valueOf(port))
        .replace("{file}", file)
        .replace("{mmproj}", mmproj);
  }

  private void pumpOutput(Instance instance)
  {
    Thread.ofVirtual().name("engine-out-" + instance.name).start(() -> {
      try (BufferedReader r = new BufferedReader(
          new InputStreamReader(instance.process.getInputStream(), StandardCharsets.UTF_8)))
      {
        String line;
        while ((line = r.readLine()) != null)
        {
          instance.addOutput(line);
          Log.debug("engine:" + instance.name, line);
        }
      }
      catch (IOException ignored)
      {
      }
    });
  }

  public Instance get(String name)
  {
    Instance i = instances.get(name);
    if (i != null && !i.alive())
    {
      instances.remove(name);
      return null;
    }
    return i;
  }

  public boolean stop(String name)
  {
    Instance i = instances.remove(name);
    if (i == null)
      return false;
    Log.info("instance", "Stopping '" + name + "' (pid " + i.process.pid() + ")");
    stopProcess(i.process);
    return true;
  }

  /**
   * Stop an engine without the graceful SIGTERM grace period. For abandoning work in progress:
   * sd-server busy in a GPU loop does not act on SIGTERM promptly, so the polite stop always ran
   * out its full wait, and there is nothing in a half-drawn image worth shutting down cleanly for.
   */
  public boolean kill(String name)
  {
    Instance i = instances.remove(name);
    if (i == null)
      return false;
    Log.info("instance", "Killing '" + name + "' (pid " + i.process.pid() + ")");
    i.process.destroyForcibly();
    try
    {
      i.process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    catch (InterruptedException e)
    {
      Thread.currentThread().interrupt();
    }
    return true;
  }

  public void stopAll()
  {
    for (String name : List.copyOf(instances.keySet()))
      stop(name);
  }

  public List<Instance> list()
  {
    List<Instance> out = new ArrayList<>();
    for (Instance i : instances.values())
      if (i.alive())
        out.add(i);
      else
        instances.remove(i.name);
    out.sort(Comparator.comparing(i -> i.name));
    return out;
  }

  private static void stopProcess(Process p)
  {
    p.destroy();
    try
    {
      if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
        p.destroyForcibly();
    }
    catch (InterruptedException e)
    {
      p.destroyForcibly();
      Thread.currentThread().interrupt();
    }
  }

  private void reaperLoop()
  {
    while (true)
    {
      try
      {
        Thread.sleep(15_000);
        long now = System.currentTimeMillis();
        for (Instance i : List.copyOf(instances.values()))
        {
          if (!i.alive())
          {
            instances.remove(i.name);
            continue;
          }
          if (i.busy())
            continue;
          if (i.keepAliveSec >= 0 && now - i.lastUsed > i.keepAliveSec * 1000L)
          {
            Log.info("instance", "Unloading idle '" + i.name + "' (keep-alive " + i.keepAliveSec + "s)");
            stop(i.name);
          }
        }
      }
      catch (InterruptedException e)
      {
        return;
      }
      catch (Exception e)
      {
        Log.error("instance", "reaper error", e);
      }
    }
  }

  private static int freePort() throws IOException
  {
    try (ServerSocket s = new ServerSocket(0))
    {
      return s.getLocalPort();
    }
  }

  /** Ollama keep_alive: number (seconds), or "5m"/"2h"/"0"/"-1" style strings. */
  public static Long parseKeepAlive(Object v)
  {
    if (v == null)
      return null;
    if (v instanceof Number n)
      return n.longValue();
    String s = String.valueOf(v).trim();
    if (s.isEmpty())
      return null;
    try
    {
      long mult = 1;
      if (s.endsWith("ms")) { return Long.parseLong(s.substring(0, s.length() - 2)) / 1000; }
      char last = s.charAt(s.length() - 1);
      if (last == 's') { s = s.substring(0, s.length() - 1); }
      else if (last == 'm') { mult = 60; s = s.substring(0, s.length() - 1); }
      else if (last == 'h') { mult = 3600; s = s.substring(0, s.length() - 1); }
      return Long.parseLong(s) * mult;
    }
    catch (NumberFormatException e)
    {
      return null;
    }
  }
}
