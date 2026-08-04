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
 * Configured-model operations shared by the Ollama API (`ollama rm`) and the web UI:
 * file-usage cross-reference and safe deletion (a model file is only removed from disk
 * when no other configured model references it, and never inside an Ollama blob store).
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
   * Delete a configured model (ollama rm semantics). When deleteFiles is true, files it
   * referenced are removed from disk unless another configured model still uses them or
   * they live inside an Ollama blob store (those belong to ollama).
   */
  public Map<String, Object> deleteModel(String name, boolean deleteFiles)
  {
    Map<String, Object> model = cfg.findModel(name);
    if (model == null)
      throw new HttpError(404, "model '" + name + "' not found");
    instances.stop(name);
    cfg.removeModel(name);

    List<String> deleted = new ArrayList<>();
    List<String> kept = new ArrayList<>();
    if (deleteFiles)
    {
      Map<String, List<String>> usage = fileUsage(); // usage after removal
      for (String ref : references(model))
      {
        String canon = canonical(ref);
        if (usage.containsKey(canon))
        {
          kept.add(ref + " (used by " + String.join(", ", usage.get(canon)) + ")");
          continue;
        }
        if (canon.contains("/blobs/"))
        {
          kept.add(ref + " (ollama-managed blob, not deleted)");
          continue;
        }
        for (Path p : expandParts(Path.of(canon)))
        {
          try
          {
            if (Files.deleteIfExists(p))
            {
              deleted.add(p.toString());
              Log.info("models", "Deleted file " + p);
            }
          }
          catch (IOException e)
          {
            kept.add(p + " (delete failed: " + e.getMessage() + ")");
          }
        }
      }
    }
    Log.info("models", "Removed model '" + name + "'"
        + (deleted.isEmpty() ? "" : ", deleted " + deleted.size() + " file(s)"));
    return Json.map("name", name, "deletedFiles", deleted, "keptFiles", kept);
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
