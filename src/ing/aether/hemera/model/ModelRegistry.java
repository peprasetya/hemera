package ing.aether.hemera.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import ing.aether.hemera.Log;
import ing.aether.hemera.config.Config;
import ing.aether.hemera.engine.InstanceManager;
import ing.aether.hemera.http.HttpError;
import ing.aether.hemera.json.Json;

/**
 * Configured-model operations shared by the Ollama API and the web UI.
 *
 * <p>Removing a configured model is always a config-only operation — model files are
 * expensive to re-obtain, so nothing on disk is touched. Deleting a file is a separate,
 * explicit action that also drops any configured models pointing at it. Files inside an
 * Ollama repository are never deletable: they belong to ollama's own content store.
 */
public final class ModelRegistry
{
  private static final Pattern PART = Pattern.compile("(.+)-(\\d{5})-of-(\\d{5})\\.gguf$", Pattern.CASE_INSENSITIVE);

  private final Config cfg;
  private final InstanceManager instances;

  public ModelRegistry(Config cfg, InstanceManager instances)
  {
    this.cfg = cfg;
    this.instances = instances;
  }

  /** All file paths a model entry references (main file, mmproj, extra files). */
  public static List<String> references(Map<String, Object> model)
  {
    List<String> refs = new ArrayList<>();
    String file = Json.str(model, "file", "");
    if (!file.isEmpty())
      refs.add(Config.expandUser(file));
    String mmproj = Json.str(model, "mmproj", "");
    if (!mmproj.isEmpty())
      refs.add(Config.expandUser(mmproj));
    for (Object v : Json.obj(model.get("extraFiles")).values())
      refs.add(Config.expandUser(String.valueOf(v)));
    return refs;
  }

  /** Cross-reference: absolute file path -> names of configured models using it. */
  public Map<String, List<String>> fileUsage()
  {
    Map<String, List<String>> usage = new LinkedHashMap<>();
    for (Map<String, Object> model : cfg.models())
    {
      String name = Json.str(model, "name", "?");
      for (String ref : references(model))
        usage.computeIfAbsent(canonical(ref), k -> new ArrayList<>()).add(name);
    }
    return usage;
  }

  /**
   * Remove a configured model. Files on disk are never touched — use
   * {@link #deleteFile(String)} for that.
   */
  public Map<String, Object> deleteModel(String name)
  {
    Map<String, Object> model = cfg.findModel(name);
    if (model == null)
      throw new HttpError(404, "model '" + name + "' not found");
    instances.stop(name);
    cfg.removeModel(name);
    Log.info("models", "Removed configured model '" + name + "' (files left on disk)");
    return Json.map("name", name, "removedModels", List.of(name), "deletedFiles", List.of());
  }

  /**
   * Delete a model file from disk, together with every configured model that referenced
   * it. Multi-part GGUFs are removed as a set. Refuses anything inside an Ollama
   * repository or outside the configured model directories.
   */
  public Map<String, Object> deleteFile(String path)
  {
    String canon = canonical(Config.expandUser(path));
    Path file = Path.of(canon);
    if (!Files.isRegularFile(file))
      throw new HttpError(404, "file not found: " + canon);
    if (isOllamaManaged(file))
      throw new HttpError(403, "this file belongs to an Ollama repository and is managed by "
          + "ollama — remove it with 'ollama rm' instead");
    if (!insideModelDir(file))
      throw new HttpError(403, "refusing to delete a file outside the configured model "
          + "directories: " + canon);

    // Drop configured models that point at this file first, so nothing is left dangling.
    List<String> removedModels = new ArrayList<>();
    for (String name : List.copyOf(fileUsage().getOrDefault(canon, List.of())))
    {
      instances.stop(name);
      cfg.removeModel(name);
      removedModels.add(name);
    }

    List<String> deleted = new ArrayList<>();
    List<String> failed = new ArrayList<>();
    for (Path p : expandParts(file))
    {
      try
      {
        if (Files.deleteIfExists(p))
          deleted.add(p.toString());
      }
      catch (IOException e)
      {
        failed.add(p + " (" + e.getMessage() + ")");
      }
    }
    Log.info("models", "Deleted " + deleted.size() + " file(s) for " + canon
        + (removedModels.isEmpty() ? "" : ", also removed configured model(s): "
            + String.join(", ", removedModels)));
    if (!failed.isEmpty())
      Log.warn("models", "Could not delete: " + String.join(", ", failed));
    return Json.map("path", canon, "deletedFiles", deleted,
        "removedModels", removedModels, "failed", failed);
  }

  /** Files living in an ollama content store (…/blobs/sha256-…) are off limits. */
  public static boolean isOllamaManaged(Path file)
  {
    Path parent = file.toAbsolutePath().getParent();
    if (parent == null)
      return false;
    if (!"blobs".equals(String.valueOf(parent.getFileName())))
      return false;
    Path repo = parent.getParent();
    return repo != null && Files.isDirectory(repo.resolve("manifests"));
  }

  /** Only files under a configured model directory may be deleted. */
  private boolean insideModelDir(Path file)
  {
    Path abs = file.toAbsolutePath().normalize();
    for (String dir : cfg.modelDirs())
    {
      Path root = Path.of(Config.expandUser(dir)).toAbsolutePath().normalize();
      if (abs.startsWith(root))
        return true;
    }
    return false;
  }

  /** For a multi-part gguf, all sibling part files; otherwise just the file itself. */
  private static List<Path> expandParts(Path file)
  {
    List<Path> out = new ArrayList<>();
    Matcher m = PART.matcher(file.getFileName().toString());
    if (m.matches() && file.getParent() != null && Files.isDirectory(file.getParent()))
    {
      String stem = m.group(1);
      try (Stream<Path> list = Files.list(file.getParent()))
      {
        for (Path sib : list.toList())
        {
          Matcher sm = PART.matcher(sib.getFileName().toString());
          if (sm.matches() && sm.group(1).equals(stem))
            out.add(sib);
        }
      }
      catch (IOException ignored)
      {
      }
    }
    if (out.isEmpty())
      out.add(file);
    return out;
  }

  private static String canonical(String path)
  {
    try
    {
      return Path.of(path).toAbsolutePath().normalize().toString();
    }
    catch (Exception e)
    {
      return path;
    }
  }
}
