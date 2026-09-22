package ing.aether.hemera.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ing.aether.hemera.Log;
import ing.aether.hemera.json.Json;

/**
 * hemera.json — the whole configuration lives as one JSON document (Map tree),
 * saved atomically on every change. The web UI round-trips this document.
 */
public final class Config
{
  public final Path home;
  private final Path file;
  private Map<String, Object> data;

  public Config(Path home, Path file)
  {
    this.home = home;
    this.file = file;
    load();
  }

  private void load()
  {
    if (Files.isRegularFile(file))
    {
      try
      {
        data = Json.parseObj(Files.readString(file, StandardCharsets.UTF_8));
        Log.info("config", "Loaded " + file);
      }
      catch (Exception e)
      {
        throw new RuntimeException("Cannot parse " + file + ": " + e.getMessage(), e);
      }
    }
    else
    {
      data = defaults();
      save();
      Log.info("config", "Created default config at " + file);
    }
    // Ensure required containers exist so the rest of the code can assume them.
    data.computeIfAbsent("modelDirs", k -> new ArrayList<>());
    data.computeIfAbsent("models", k -> new ArrayList<>());
    data.computeIfAbsent("proxies", k -> new ArrayList<>());
    Map<String, Object> engines = Json.obj(data.computeIfAbsent("engines", k -> new LinkedHashMap<>()));
    if (!(data.get("engines") instanceof Map))
      data.put("engines", engines);
    engines.computeIfAbsent("llama.cpp", k -> defaultLlamaCpp());
    engines.computeIfAbsent("sd.cpp", k -> defaultSdCpp());
  }

  private static Map<String, Object> defaults()
  {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("port", 12434L);
    m.put("logLevel", "info");
    m.put("keepAliveSeconds", 300L);
    m.put("modelDirs", new ArrayList<>());
    Map<String, Object> engines = new LinkedHashMap<>();
    engines.put("llama.cpp", defaultLlamaCpp());
    engines.put("sd.cpp", defaultSdCpp());
    m.put("engines", engines);
    m.put("models", new ArrayList<>());
    m.put("proxies", new ArrayList<>());
    return m;
  }

  private static Map<String, Object> defaultLlamaCpp()
  {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("repo", "ggml-org/llama.cpp");
    e.put("serverBin", "llama-server");
    e.put("healthPath", "/health");
    e.put("trackedVariants", new ArrayList<>(List.of("ubuntu-vulkan-x64", "ubuntu-rocm")));
    e.put("defaultArgs", new ArrayList<>(List.of("--no-webui", "-ngl", "99", "--jinja")));
    e.put("libraryAutoDetect", Boolean.TRUE);
    e.put("libraryPaths", new ArrayList<>());
    e.put("installed", new ArrayList<>());
    return e;
  }

  /**
   * stable-diffusion.cpp's sd-server, for image generation and editing. Its health probe is
   * /v1/models because it has no /health route — and it only starts listening once the whole
   * pipeline is loaded, so answering there means genuinely ready.
   */
  private static Map<String, Object> defaultSdCpp()
  {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("repo", "leejet/stable-diffusion.cpp");
    e.put("serverBin", "sd-server");
    e.put("argStyle", "sd");
    e.put("healthPath", "/v1/models");
    // Linux x86_64 only, so the Windows/macOS assets of every release stay out of the way.
    e.put("trackedVariants", new ArrayList<>(List.of("x86_64-vulkan", "x86_64-rocm")));
    // Deliberately empty: --diffusion-fa measured no faster than without on Vulkan and flash
    // attention is a known source of black images on some backends, so it stays opt-in.
    e.put("defaultArgs", new ArrayList<>());
    e.put("libraryAutoDetect", Boolean.TRUE);
    e.put("libraryPaths", new ArrayList<>());
    e.put("installed", new ArrayList<>());
    return e;
  }

  public synchronized Map<String, Object> data()
  {
    return data;
  }

  /** Replace the whole document (web UI PUT) and persist. */
  public synchronized void replace(Map<String, Object> d)
  {
    data = d;
    save();
  }

  public synchronized void save()
  {
    try
    {
      Files.createDirectories(file.toAbsolutePath().getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(tmp, Json.writePretty(data) + "\n", StandardCharsets.UTF_8);
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    catch (IOException e)
    {
      Log.error("config", "Failed to save " + file, e);
    }
  }

  // ---- accessors ----------------------------------------------------------

  public synchronized int port()
  {
    return (int) Json.num(data, "port", 12434);
  }

  public synchronized String logLevel()
  {
    return Json.str(data, "logLevel", "info");
  }

  public synchronized void setLogLevel(String level)
  {
    data.put("logLevel", level);
    save();
  }

  public synchronized long defaultKeepAlive()
  {
    return Json.num(data, "keepAliveSeconds", 300);
  }

  public synchronized List<String> modelDirs()
  {
    return Json.strList(data, "modelDirs");
  }

  /** Extra glob patterns searched for engine runtime libraries, on top of the built-in defaults. */
  public synchronized List<String> librarySearchRoots()
  {
    return Json.strList(data, "librarySearchRoots");
  }

  public synchronized List<Map<String, Object>> models()
  {
    return Json.arrOfObj(data.get("models"));
  }

  public synchronized Map<String, Object> findModel(String name)
  {
    if (name == null)
      return null;
    for (Map<String, Object> m : models())
      if (name.equals(Json.str(m, "name", null)))
        return m;
    return null;
  }

  public synchronized List<Map<String, Object>> proxies()
  {
    return Json.arrOfObj(data.get("proxies"));
  }

  public synchronized Map<String, Object> findProxy(String name)
  {
    if (name == null)
      return null;
    for (Map<String, Object> p : proxies())
      if (name.equals(Json.str(p, "name", null)))
        return p;
    return null;
  }

  public synchronized Map<String, Object> engines()
  {
    return Json.obj(data.get("engines"));
  }

  public synchronized Map<String, Object> engine(String name)
  {
    return Json.obj(engines().get(name));
  }

  public synchronized List<Map<String, Object>> installed(String engine)
  {
    return Json.arrOfObj(engine(engine).get("installed"));
  }

  @SuppressWarnings("unchecked")
  public synchronized void addInstalled(String engine, Map<String, Object> entry)
  {
    Map<String, Object> e = engine(engine);
    if (!engines().containsKey(engine))
      engines().put(engine, e);
    List<Object> inst = (List<Object>) e.computeIfAbsent("installed", k -> new ArrayList<>());
    // replace an existing entry for the same version+variant
    inst.removeIf(o -> o instanceof Map<?, ?> m
        && Json.str((Map<String, Object>) m, "version", "").equals(entry.get("version"))
        && Json.str((Map<String, Object>) m, "variant", "").equals(entry.get("variant")));
    inst.add(entry);
    save();
  }

  @SuppressWarnings("unchecked")
  public synchronized boolean removeInstalled(String engine, String version, String variant)
  {
    Object o = engine(engine).get("installed");
    if (!(o instanceof List))
      return false;
    boolean removed = ((List<Object>) o).removeIf(x -> x instanceof Map<?, ?> m
        && version.equals(((Map<String, Object>) m).get("version"))
        && variant.equals(((Map<String, Object>) m).get("variant")));
    if (removed)
      save();
    return removed;
  }

  @SuppressWarnings("unchecked")
  public synchronized void putModel(Map<String, Object> model)
  {
    List<Object> list = (List<Object>) data.computeIfAbsent("models", k -> new ArrayList<>());
    String name = Json.str(model, "name", "");
    list.removeIf(o -> o instanceof Map<?, ?> m && name.equals(((Map<String, Object>) m).get("name")));
    list.add(model);
    save();
  }

  @SuppressWarnings("unchecked")
  public synchronized boolean removeModel(String name)
  {
    Object o = data.get("models");
    if (!(o instanceof List))
      return false;
    boolean removed = ((List<Object>) o)
        .removeIf(x -> x instanceof Map<?, ?> m && name.equals(((Map<String, Object>) m).get("name")));
    if (removed)
      save();
    return removed;
  }

  /** Resolve a possibly-relative path (e.g. installed binary dirs) against home. */
  public Path resolve(String path)
  {
    Path p = Path.of(expandUser(path));
    return p.isAbsolute() ? p : home.resolve(p);
  }

  public static String expandUser(String path)
  {
    if (path != null && path.startsWith("~"))
      return System.getProperty("user.home") + path.substring(1);
    return path;
  }
}
