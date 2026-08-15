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

    List<String> cmd = new ArrayList<>();
    cmd.add(bin);
    // Anything that is a llama-server gets the -m/--mmproj treatment, including forks such as
    // the one ollama bundles. Override with "argStyle": "llama" | "custom" on the engine.
    String argStyle = Json.str(engineCfg, "argStyle", "");
    boolean llamaStyle = argStyle.isEmpty()
        ? Path.of(bin).getFileName().toString().contains("llama-server")
        : "llama".equals(argStyle);
    if (llamaStyle)
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
      for (Map.Entry<String, Object> e : Json.obj(model.get("extraFiles")).entrySet())
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
