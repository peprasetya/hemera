package ing.aether.hemera.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import ing.aether.hemera.Log;
import ing.aether.hemera.config.Config;
import ing.aether.hemera.json.Json;

/**
 * Works out which extra directories an engine build needs on LD_LIBRARY_PATH.
 *
 * <p>Rather than knowing about ROCm, CUDA, oneAPI or OpenVINO specifically, it reads the
 * engine's own ELF headers to learn which shared libraries it asks for, discards the ones
 * the dynamic loader can already find, and hunts the rest through well-known runtime
 * locations — following transitive dependencies as it goes. Anything it cannot find is
 * reported instead of being silently ignored, which is what turns a mysterious CPU
 * fallback into a readable warning.
 *
 * <p>Purely read-only: nothing outside the config file is modified and no privileges are needed.
 */
public final class LibraryResolver
{
  /**
   * @param searchPaths directories to add to LD_LIBRARY_PATH, in priority order
   * @param resolved    soname -> directory it was found in (only ones needing a search path)
   * @param missing     sonames that could not be found anywhere
   * @param needed      every soname the engine's binaries asked for
   */
  public record Result(List<Path> searchPaths, Map<String, String> resolved,
      List<String> missing, List<String> needed)
  {
    public boolean complete()
    {
      return missing.isEmpty();
    }
  }

  /**
   * Where GPU/accelerator runtimes typically live. Globs are expanded at scan time, so
   * versioned and symlinked layouts (/opt/rocm/lib -> core-7.14/lib, /usr/local/cuda-12.4)
   * are picked up without naming any version. Users can add more via
   * "librarySearchRoots" in hemera.json.
   */
  private static final List<String> DEFAULT_ROOTS = List.of(
      // AMD ROCm / HIP
      "/opt/rocm*/lib", "/opt/rocm*/lib64", "/opt/rocm*/core-*/lib", "/opt/rocm*/hip/lib",
      // NVIDIA CUDA
      "/usr/local/cuda*/lib64", "/usr/local/cuda*/lib", "/usr/local/cuda*/targets/*/lib",
      "/opt/cuda*/lib64", "/opt/cuda*/lib",
      // Intel oneAPI / SYCL
      "/opt/intel/oneapi/*/latest/lib", "/opt/intel/oneapi/*/*/lib", "/opt/intel/oneapi/lib",
      // OpenVINO
      "/opt/intel/openvino*/runtime/lib/*", "/opt/openvino*/runtime/lib/*", "/opt/openvino*/lib",
      // generic vendor drops and user-local installs
      "/opt/*/lib", "/opt/*/lib64",
      "/usr/local/lib", "/usr/local/lib64",
      "~/.local/lib");

  /** Directories the loader already searches without help. */
  private static final List<String> SYSTEM_DIRS = List.of(
      "/lib", "/lib64", "/usr/lib", "/usr/lib64",
      "/lib/x86_64-linux-gnu", "/usr/lib/x86_64-linux-gnu",
      "/lib/aarch64-linux-gnu", "/usr/lib/aarch64-linux-gnu");

  private static final Map<String, Result> CACHE = new ConcurrentHashMap<>();

  private LibraryResolver()
  {
  }

  public static void clearCache()
  {
    CACHE.clear();
  }

  public static Result resolve(Config cfg, String engineName, Path binDir)
  {
    Map<String, Object> engineCfg = cfg.engine(engineName);
    List<String> userPaths = Json.strList(engineCfg, "libraryPaths");
    boolean auto = Json.bool(engineCfg, "libraryAutoDetect", true);
    String key = binDir + "|" + userPaths + "|" + auto + "|" + cfg.librarySearchRoots();
    Result cached = CACHE.get(key);
    if (cached != null)
      return cached;
    Result r = compute(cfg, binDir, userPaths, auto);
    CACHE.put(key, r);
    return r;
  }

  private static Result compute(Config cfg, Path binDir, List<String> userPaths, boolean auto)
  {
    long started = System.currentTimeMillis();

    // Explicit user directories always win, and are trusted without inspection.
    LinkedHashSet<Path> extra = new LinkedHashSet<>();
    for (String p : userPaths)
    {
      Path dir = Path.of(Config.expandUser(p));
      if (Files.isDirectory(dir))
        extra.add(dir);
      else
        Log.warn("libs", "configured libraryPath does not exist: " + dir);
    }

    Set<Path> systemDirs = systemDirs();
    LinkedHashSet<String> allNeeded = new LinkedHashSet<>();
    Map<String, String> resolved = new LinkedHashMap<>();
    List<String> missing = new ArrayList<>();

    // Seed the queue with what the engine's own binaries ask for.
    Deque<String> pending = new ArrayDeque<>();
    LinkedHashSet<Path> ownDirs = new LinkedHashSet<>();
    ownDirs.add(binDir);
    for (Path f : elfFiles(binDir))
    {
      Elf.Info info = Elf.read(f);
      for (String p : info.runPaths())
      {
        Path rp = Path.of(p);
        if (Files.isDirectory(rp))
          ownDirs.add(rp);
      }
      for (String n : info.needed())
        if (allNeeded.add(n))
          pending.add(n);
    }
    if (allNeeded.isEmpty())
      return new Result(List.copyOf(extra), Map.of(), List.of(), List.of());

    List<Path> roots = auto ? candidateRoots(cfg) : List.of();
    int guard = 0;
    while (!pending.isEmpty() && guard++ < 4000)
    {
      String soname = pending.poll();
      // Already reachable? binDir / RPATH / user paths / discovered dirs / system dirs.
      if (containedIn(ownDirs, soname) || containedIn(extra, soname) || containedIn(systemDirs, soname))
        continue;
      Path found = null;
      for (Path root : roots)
        if (Elf.has(root, soname))
        {
          found = root;
          break;
        }
      if (found == null)
      {
        missing.add(soname);
        continue;
      }
      extra.add(found);
      resolved.put(soname, found.toString());
      // Follow this library's own dependencies — chains can cross directories.
      for (String n : Elf.read(found.resolve(soname)).needed())
        if (allNeeded.add(n))
          pending.add(n);
    }

    Result result = new Result(List.copyOf(extra), resolved, List.copyOf(missing), List.copyOf(allNeeded));
    Log.debug("libs", "scanned " + binDir.getFileName() + " in " + (System.currentTimeMillis() - started)
        + "ms: " + allNeeded.size() + " libraries needed, " + resolved.size() + " found by search, "
        + missing.size() + " missing");
    return result;
  }

  private static boolean containedIn(Set<Path> dirs, String soname)
  {
    for (Path d : dirs)
      if (Elf.has(d, soname))
        return true;
    return false;
  }

  /** ELF files directly inside the engine's bin directory (the server plus its plugins). */
  private static List<Path> elfFiles(Path binDir)
  {
    List<Path> out = new ArrayList<>();
    if (!Files.isDirectory(binDir))
      return out;
    try (Stream<Path> list = Files.list(binDir))
    {
      for (Path p : list.sorted().toList())
        if (Files.isRegularFile(p) && Elf.isElf(p))
          out.add(p);
    }
    catch (IOException e)
    {
      Log.debug("libs", "cannot list " + binDir + ": " + e);
    }
    return out;
  }

  /** Loader defaults plus whatever /etc/ld.so.conf.d declares (readable without root). */
  private static Set<Path> systemDirs()
  {
    LinkedHashSet<Path> dirs = new LinkedHashSet<>();
    for (String s : SYSTEM_DIRS)
    {
      Path p = Path.of(s);
      if (Files.isDirectory(p))
        dirs.add(p);
    }
    for (Path conf : confFiles())
    {
      try
      {
        for (String line : Files.readAllLines(conf, StandardCharsets.UTF_8))
        {
          String l = line.trim();
          if (l.isEmpty() || l.startsWith("#") || l.startsWith("include"))
            continue;
          Path p = Path.of(l);
          if (Files.isDirectory(p))
            dirs.add(p);
        }
      }
      catch (IOException ignored)
      {
      }
    }
    return dirs;
  }

  private static List<Path> confFiles()
  {
    List<Path> out = new ArrayList<>();
    Path main = Path.of("/etc/ld.so.conf");
    if (Files.isRegularFile(main))
      out.add(main);
    Path dir = Path.of("/etc/ld.so.conf.d");
    if (Files.isDirectory(dir))
      try (Stream<Path> list = Files.list(dir))
      {
        list.filter(Files::isRegularFile).sorted().forEach(out::add);
      }
      catch (IOException ignored)
      {
      }
    return out;
  }

  /** Expand the built-in and user-configured glob patterns into real directories. */
  private static List<Path> candidateRoots(Config cfg)
  {
    LinkedHashSet<Path> dirs = new LinkedHashSet<>();
    List<String> patterns = new ArrayList<>(cfg.librarySearchRoots()); // user first
    patterns.addAll(DEFAULT_ROOTS);
    for (String pattern : patterns)
      dirs.addAll(expandGlob(Config.expandUser(pattern)));
    return List.copyOf(dirs);
  }

  /** Minimal glob expansion over path segments; only '*' and '?' within one segment. */
  static List<Path> expandGlob(String pattern)
  {
    Path p = Path.of(pattern);
    List<Path> current = new ArrayList<>();
    current.add(p.isAbsolute() ? Path.of("/") : Path.of(""));
    for (Path segment : p.normalize())
    {
      String name = segment.toString();
      List<Path> next = new ArrayList<>();
      if (name.indexOf('*') < 0 && name.indexOf('?') < 0)
      {
        for (Path base : current)
        {
          Path child = base.resolve(name);
          if (Files.isDirectory(child))
            next.add(child);
        }
      }
      else
      {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + name);
        for (Path base : current)
        {
          try (Stream<Path> list = Files.list(base))
          {
            list.filter(Files::isDirectory)
                .filter(c -> matcher.matches(c.getFileName()))
                .sorted()
                .forEach(next::add);
          }
          catch (IOException ignored)
          {
          }
        }
      }
      current = next;
      if (current.isEmpty() || current.size() > 512)
        break;
    }
    return current;
  }
}
