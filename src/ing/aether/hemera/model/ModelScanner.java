package ing.aether.hemera.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    List<Map<String, Object>> found = new ArrayList<>();
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
        // mmproj is always a companion. A file with "mtp" in its name is not: many releases
        // bundle the MTP head straight into the one full checkpoint and just advertise that in
        // the filename (used directly via --spec-type draft-mtp, no second file involved) — only
        // a *much smaller* sibling model file in the same directory marks it as a genuine
        // separate draft/head file, decided once every file's size is known (see
        // reclassifyMtpCompanions below).
        String role = lower.contains("mmproj") ? "mmproj" : "model";
        Map<String, Object> entry = Json.map(
            "name", fname,
            "path", p.toAbsolutePath().toString(),
            "dir", dirStr,
            "size", size,
            "parts", parts,
            "role", role,
            "source", "scan");
        if ("model".equals(role))
        {
          Map<String, Object> meta = GgufReader.read(p);
          entry.put("meta", trimMeta(meta));
          if (lower.contains("mtp"))
            entry.put("mtpEmbedded", true);
        }
        found.add(entry);
      }
      reclassifyMtpCompanions(found);
      bindProjectors(found);
      for (Map<String, Object> e : found)
        if (Json.obj(e.get("meta")).get("hemera.kind") instanceof String k && "image".equals(k))
          bindImageComponents(e);
      out.addAll(found);
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
          {
            entry.put("mmprojPath", projectorBlob);
            entry.put("mmprojName", "projector (from ollama manifest)");
          }
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

  /** Weight files a diffusion pipeline is assembled from — not all of them are GGUF. */
  private static final Pattern WEIGHT_FILE =
      Pattern.compile(".*\\.(safetensors|sft|gguf|ckpt|pth)$", Pattern.CASE_INSENSITIVE);

  /**
   * A diffusion transformer is only half a pipeline: it also needs a VAE and a text encoder,
   * which ship as separate files (usually in vae/ and text_encoders/ beside it) and are passed
   * to sd-server as --vae / --llm. Collect the candidates per flag so the model form can offer
   * them instead of making the user type four absolute paths.
   *
   * Only the model's own directory and its obvious component subdirectories are searched: a
   * diffusion model sitting loose in a big shared model tree must not adopt its neighbours.
   */
  private static void bindImageComponents(Map<String, Object> model)
  {
    Path self = Path.of(Json.str(model, "path", ""));
    Path dir = self.getParent();
    if (dir == null)
      return;
    Map<String, Object> components = new LinkedHashMap<>();
    List<Path> candidates = new ArrayList<>();
    try (Stream<Path> top = Files.list(dir))
    {
      for (Path p : top.sorted().toList())
      {
        if (Files.isRegularFile(p))
          candidates.add(p);
        else if (Files.isDirectory(p) && COMPONENT_DIR.matcher(p.getFileName().toString()).matches())
          try (Stream<Path> sub = Files.list(p))
          {
            sub.filter(Files::isRegularFile).sorted().forEach(candidates::add);
          }
      }
    }
    catch (IOException e)
    {
      Log.debug("scan", "could not scan components beside " + self + ": " + e);
      return;
    }
    for (Path p : candidates)
    {
      if (p.equals(self) || !WEIGHT_FILE.matcher(p.getFileName().toString()).matches())
        continue;
      String flag = componentFlag(p);
      if (flag == null)
        continue;
      @SuppressWarnings("unchecked")
      List<Object> forFlag = (List<Object>) components.computeIfAbsent(flag, k -> new ArrayList<>());
      forFlag.add(p.toAbsolutePath().toString());
    }
    if (!components.isEmpty())
      model.put("imageComponents", components);
  }

  private static final Pattern COMPONENT_DIR = Pattern.compile(
      "vae|text_encoders?|encoders?|clip|llm", Pattern.CASE_INSENSITIVE);

  /** Which sd-server flag a component file belongs to, or null when it is not one. */
  private static String componentFlag(Path p)
  {
    String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    String parent = p.getParent() == null ? "" : p.getParent().getFileName().toString().toLowerCase(java.util.Locale.ROOT);
    if (name.contains("mmproj"))
      return "llm_vision";
    // a video model's sound track has a VAE of its own, passed separately from the picture's
    if (name.contains("audio") && (name.contains("vae") || parent.equals("vae")))
      return "audio-vae";
    if (name.contains("vae") || parent.equals("vae"))
      return "vae";
    if (name.contains("clip_l"))
      return "clip_l";
    if (name.contains("clip_g"))
      return "clip_g";
    if (name.contains("t5"))
      return "t5xxl";
    if (name.contains("qwen2vl") || name.contains("qwen2.5vl"))
      return "qwen2vl";
    if (parent.startsWith("text_encoder") || parent.equals("encoders") || parent.equals("llm")
        || name.contains("text_encoder"))
      return "llm";
    return null;
  }

  /**
   * A file named with "mtp" is only a separate draft/head companion when a clearly bigger
   * sibling model sits in the same directory — otherwise it is a full model whose MTP head is
   * baked into that same file (loaded with --spec-type draft-mtp, no companion needed at all).
   */
  private static void reclassifyMtpCompanions(List<Map<String, Object>> entries)
  {
    Map<String, List<Map<String, Object>>> byDir = new LinkedHashMap<>();
    for (Map<String, Object> e : entries)
      byDir.computeIfAbsent(parentOf(Json.str(e, "path", "")), k -> new ArrayList<>()).add(e);

    for (List<Map<String, Object>> siblings : byDir.values())
      for (Map<String, Object> e : siblings)
      {
        if (!"model".equals(Json.str(e, "role", "")) || !Boolean.TRUE.equals(e.get("mtpEmbedded")))
          continue;
        long size = Json.num(e, "size", 0);
        boolean hasBiggerSibling = siblings.stream().anyMatch(o -> o != e
            && "model".equals(Json.str(o, "role", "")) && Json.num(o, "size", 0) > size * 2);
        if (hasBiggerSibling)
        {
          e.put("role", "mtp");
          e.remove("meta");
          e.remove("mtpEmbedded");
        }
      }
  }

  /**
   * Attach each model to the companion files (vision projector, MTP head, ...) sitting beside
   * it. A companion only ever belongs to the model it shipped with, so pairing is by directory
   * rather than being left to the user: mixing one from another model produces garbage. When a
   * directory holds several of the same kind, they are matched on filename similarity.
   */
  private static void bindProjectors(List<Map<String, Object>> entries)
  {
    Map<String, List<Map<String, Object>>> byDir = new LinkedHashMap<>();
    for (Map<String, Object> e : entries)
      byDir.computeIfAbsent(parentOf(Json.str(e, "path", "")), k -> new ArrayList<>()).add(e);

    for (List<Map<String, Object>> siblings : byDir.values())
    {
      bindCompanions(siblings, "mmproj", "mmprojPath", "mmprojName");
      bindCompanions(siblings, "mtp", "mtpPath", "mtpName");
    }
  }

  private static void bindCompanions(List<Map<String, Object>> siblings, String role, String pathKey,
      String nameKey)
  {
    List<Map<String, Object>> candidates = siblings.stream()
        .filter(e -> role.equals(Json.str(e, "role", ""))).toList();
    if (candidates.isEmpty())
      return;
    for (Map<String, Object> model : siblings)
    {
      if (!"model".equals(Json.str(model, "role", "")))
        continue;
      Map<String, Object> best = candidates.size() == 1 ? candidates.get(0)
          : bestMatch(Json.str(model, "name", ""), candidates);
      model.put(pathKey, Json.str(best, "path", ""));
      model.put(nameKey, Json.str(best, "name", ""));
      List<Object> bound = Json.arr(best.computeIfAbsent("boundTo", k -> new ArrayList<>()));
      bound.add(Json.str(model, "name", ""));
    }
  }

  /** Pick the projector whose filename shares the longest prefix with the model's. */
  private static Map<String, Object> bestMatch(String modelName, List<Map<String, Object>> projectors)
  {
    Map<String, Object> best = projectors.get(0);
    int bestScore = -1;
    String needle = modelName.toLowerCase();
    for (Map<String, Object> p : projectors)
    {
      String cand = Json.str(p, "name", "").toLowerCase()
          .replace("mmproj", "").replace("mtp", "").replace("-", "");
      int score = 0;
      String stem = needle.replace("-", "");
      while (score < cand.length() && score < stem.length() && cand.charAt(score) == stem.charAt(score))
        score++;
      if (score > bestScore)
      {
        bestScore = score;
        best = p;
      }
    }
    return best;
  }

  private static String parentOf(String path)
  {
    Path p = Path.of(path).getParent();
    return p == null ? "" : p.toString();
  }

  /** Keep only the compact, interesting metadata for API/UI responses. */
  private static Map<String, Object> trimMeta(Map<String, Object> meta)
  {
    Map<String, Object> t = Json.map();
    for (String k : new String[] { "hemera.arch", "hemera.name", "hemera.quant", "hemera.params",
        "hemera.context", "hemera.vision", "hemera.audio", "hemera.embedding", "hemera.kind",
        "general.architecture", "general.name", "general.size_label", "error" })
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
