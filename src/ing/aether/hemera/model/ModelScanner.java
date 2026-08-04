package ing.aether.hemera.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import ing.aether.hemera.Log;
import ing.aether.hemera.config.Config;
import ing.aether.hemera.json.Json;

/**
 * Scans the configured model directories for loadable model files.
 * Understands loose GGUF trees (multi-part files, mmproj/mtp companions)
 * and the Ollama repository layout (manifests/ + blobs/).
 */
public final class ModelScanner
{
  /** name-00001-of-00005.gguf */
  private static final Pattern PART = Pattern.compile("(.+)-(\\d{5})-of-(\\d{5})\\.gguf$", Pattern.CASE_INSENSITIVE);

  public static List<Map<String, Object>> scan(Config cfg)
  {
    List<Map<String, Object>> out = new ArrayList<>();
    for (String dirStr : cfg.modelDirs())
    {
      Path dir = Path.of(Config.expandUser(dirStr));
      if (!Files.isDirectory(dir))
      {
        out.add(Json.map("dir", dirStr, "error", "directory not found"));
        continue;
      }
      if (Files.isDirectory(dir.resolve("manifests")) && Files.isDirectory(dir.resolve("blobs")))
        scanOllamaRepo(dir, dirStr, out);
      else
        scanGgufTree(dir, dirStr, out);
    }
    out.sort(Comparator.comparing(m -> Json.str(m, "name", "")));
    return out;
  }

  private static void scanGgufTree(Path dir, String dirStr, List<Map<String, Object>> out)
  {
    try (Stream<Path> walk = Files.walk(dir, 8))
    {
      List<Path> ggufs = walk.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".gguf"))
          .sorted()
          .toList();
      for (Path p : ggufs)
      {
        String fname = p.getFileName().toString();
        Matcher m = PART.matcher(fname);
        int parts = 1;
        long size;
        if (m.matches())
        {
          if (!"00001".equals(m.group(2)))
            continue; // only the first part represents the model
          parts = Integer.parseInt(m.group(3));
          size = 0;
          for (Path sib : ggufs)
          {
            Matcher sm = PART.matcher(sib.getFileName().toString());
            if (sm.matches() && sib.getParent().equals(p.getParent()) && sm.group(1).equals(m.group(1)))
              size += safeSize(sib);
          }
        }
        else
          size = safeSize(p);

        String lower = fname.toLowerCase();
        // only mmproj files are always companions; "mtp" in a name is just a hint —
        // models with built-in MTP weights carry it in their filename too
        String role = lower.contains("mmproj") ? "mmproj" : "model";
        Map<String, Object> entry = Json.map(
            "name", fname,
            "path", p.toAbsolutePath().toString(),
            "dir", dirStr,
            "size", size,
            "parts", parts,
            "role", role,
            "source", "scan");
        if (lower.contains("mtp"))
          entry.put("mtpHint", true);
        if ("model".equals(role))
        {
          Map<String, Object> meta = GgufReader.read(p);
          entry.put("meta", trimMeta(meta));
        }
        out.add(entry);
      }
    }
    catch (IOException e)
    {
      Log.warn("scan", "failed scanning " + dir + ": " + e);
      out.add(Json.map("dir", dirStr, "error", String.valueOf(e.getMessage())));
    }
  }

  /** Ollama repo: manifests/<registry>/<namespace>/<model>/<tag> -> blobs/sha256-... */
  private static void scanOllamaRepo(Path repo, String dirStr, List<Map<String, Object>> out)
  {
    Path manifests = repo.resolve("manifests");
    try (Stream<Path> walk = Files.walk(manifests, 6))
    {
      for (Path mf : walk.filter(Files::isRegularFile).sorted().toList())
      {
        try
        {
          Map<String, Object> manifest = Json.parseObj(Files.readString(mf, StandardCharsets.UTF_8));
          Path rel = manifests.relativize(mf);
          // registry/namespace/model/tag
          String name;
          if (rel.getNameCount() >= 4)
          {
            String ns = rel.getName(rel.getNameCount() - 3).toString();
            String model = rel.getName(rel.getNameCount() - 2).toString();
            String tag = rel.getName(rel.getNameCount() - 1).toString();
            name = ("library".equals(ns) ? model : ns + "/" + model) + ":" + tag;
          }
          else
            name = rel.toString();

          String modelBlob = null, projectorBlob = null;
          long size = 0;
          for (Map<String, Object> layer : Json.arrOfObj(manifest.get("layers")))
          {
            String mediaType = Json.str(layer, "mediaType", "");
            String digest = Json.str(layer, "digest", "");
            Path blob = blobPath(repo, digest);
            if (mediaType.endsWith(".model") && blob != null)
            {
              modelBlob = blob.toString();
              size = safeSize(blob);
            }
            else if (mediaType.endsWith(".projector") && blob != null)
              projectorBlob = blob.toString();
          }
          if (modelBlob == null)
            continue;
          Map<String, Object> entry = Json.map(
              "name", name,
              "path", modelBlob,
              "dir", dirStr,
              "size", size,
              "parts", 1,
              "role", "model",
              "source", "ollama");
          if (projectorBlob != null)
            entry.put("mmprojPath", projectorBlob);
          entry.put("meta", trimMeta(GgufReader.read(Path.of(modelBlob))));
          out.add(entry);
        }
        catch (Exception e)
        {
          Log.debug("scan", "skipping manifest " + mf + ": " + e);
        }
      }
    }
    catch (IOException e)
    {
      Log.warn("scan", "failed scanning ollama repo " + repo + ": " + e);
      out.add(Json.map("dir", dirStr, "error", String.valueOf(e.getMessage())));
    }
  }

  private static Path blobPath(Path repo, String digest)
  {
    if (digest == null || !digest.startsWith("sha256:"))
      return null;
    Path p = repo.resolve("blobs").resolve("sha256-" + digest.substring(7));
    return Files.isRegularFile(p) ? p : null;
  }

  /** Keep only the compact, interesting metadata for API/UI responses. */
  private static Map<String, Object> trimMeta(Map<String, Object> meta)
  {
    Map<String, Object> t = Json.map();
    for (String k : new String[] { "hemera.arch", "hemera.name", "hemera.quant", "hemera.params",
        "hemera.context", "general.architecture", "general.name", "general.size_label", "error" })
      if (meta.containsKey(k))
        t.put(k, meta.get(k));
    return t;
  }

  private static long safeSize(Path p)
  {
    try
    {
      return Files.size(p);
    }
    catch (IOException e)
    {
      return 0;
    }
  }
}
