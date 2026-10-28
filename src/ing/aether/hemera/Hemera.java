package ing.aether.hemera;

import java.nio.file.Files;
import java.nio.file.Path;

import ing.aether.hemera.api.AudioApi;
import ing.aether.hemera.api.ClaudeApi;
import ing.aether.hemera.api.ImagesApi;
import ing.aether.hemera.api.OllamaApi;
import ing.aether.hemera.api.OpenAiApi;
import ing.aether.hemera.api.ProxyDispatcher;
import ing.aether.hemera.api.RealtimeApi;
import ing.aether.hemera.comfy.ComfyBridge;
import ing.aether.hemera.config.Config;
import ing.aether.hemera.engine.InstanceManager;
import ing.aether.hemera.http.Router;
import ing.aether.hemera.model.ModelRegistry;
import ing.aether.hemera.web.AdminApi;
import ing.aether.hemera.web.StaticFiles;

/**
 * Hemera — pure-Java AI engine manager and API gateway.
 * Manages llama.cpp (and other) engine binaries, serves Ollama/OpenAI/Anthropic
 * compatible APIs, and hosts its configuration UI on the same port.
 */
public final class Hemera
{
  public static final String VERSION = "0.1.0";

  public static void main(String[] args) throws Exception
  {
    Path home = null;
    Path configFile = null;
    Integer portArg = null;
    String logArg = null;

    for (int i = 0; i < args.length; i++)
    {
      switch (args[i])
      {
        case "--home" -> home = Path.of(args[++i]).toAbsolutePath();
        case "--config" -> configFile = Path.of(args[++i]).toAbsolutePath();
        case "--port" -> portArg = Integer.parseInt(args[++i]);
        case "--log" -> logArg = args[++i];
        case "--version" -> {
          System.out.println("hemera " + VERSION);
          return;
        }
        case "--help" -> {
          System.out.println("""
              hemera %s — AI engine manager & API gateway (Ollama/OpenAI/Anthropic compatible)
              usage: java -jar hemera.jar [options]
                --home <dir>     data directory (default: directory containing the jar, or $HEMERA_HOME)
                --config <file>  config file (default: <home>/hemera.json)
                --port <n>       listen port (default: from config, 12434)
                --log <level>    info | debug | trace
                --version        print version and exit""".formatted(VERSION));
          return;
        }
        default -> {
          System.err.println("unknown option: " + args[i] + " (try --help)");
          return;
        }
      }
    }

    if (home == null)
    {
      String env = System.getenv("HEMERA_HOME");
      home = env != null && !env.isBlank() ? Path.of(env).toAbsolutePath() : jarDir();
    }
    Files.createDirectories(home);
    if (configFile == null)
      configFile = home.resolve("hemera.json");

    Config cfg = new Config(home, configFile);
    Log.setLevel(logArg != null ? logArg : cfg.logLevel());
    int port = portArg != null ? portArg : cfg.port();

    InstanceManager im = new InstanceManager(cfg);
    ModelRegistry registry = new ModelRegistry(cfg, im);
    ProxyDispatcher proxies = new ProxyDispatcher(cfg);
    ComfyBridge comfy = new ComfyBridge(cfg);
    im.addReclaimer(comfy::reclaimIfIdle);
    im.setGpuReleaser(comfy::releaseGpu);
    comfy.setGpuClearer(im::clearGpu);
    comfy.setRoomMaker(need -> im.makeRoom("ComfyUI", need));

    Router router = new Router();
    // Root: ollama clients probe "GET /" for the plain-text banner; browsers get the UI.
    router.add("GET", "/", ex -> {
      String accept = ex.getRequestHeaders().getFirst("Accept");
      if (accept != null && accept.contains("text/html"))
        StaticFiles.redirect(ex, "/configure/");
      else
        Router.sendText(ex, 200, "Ollama is running", "text/plain");
    });
    new OllamaApi(cfg, im, registry, proxies, comfy, router);
    new OpenAiApi(cfg, im, proxies, comfy, router);
    new ImagesApi(cfg, im, proxies, comfy, router);
    new RealtimeApi(new AudioApi(cfg, im, router), router);
    new ClaudeApi(cfg, im, proxies, router);
    new AdminApi(cfg, im, registry, comfy, router);
    new StaticFiles(router);

    router.start(port);
    Log.info("hemera", "Hemera " + VERSION + " listening on port " + port);
    Log.info("hemera", "home: " + home);
    Log.info("hemera", "config: " + configFile);
    Log.info("hemera", "web UI: http://localhost:" + port + "/configure/");
  }

  /** Directory containing hemera.jar; falls back to the working directory when run from classes. */
  private static Path jarDir()
  {
    try
    {
      Path p = Path.of(Hemera.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      if (Files.isRegularFile(p))
        return p.toAbsolutePath().getParent();
    }
    catch (Exception ignored)
    {
    }
    return Path.of("").toAbsolutePath();
  }
}
