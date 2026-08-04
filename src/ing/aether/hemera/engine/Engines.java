package ing.aether.hemera.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ing.aether.hemera.config.Config;
import ing.aether.hemera.http.HttpError;
import ing.aether.hemera.json.Json;

/** Resolves which installed binary a configured model uses, and the reverse cross-reference. */
public final class Engines
{
  /** "b10254" -> 10254 for natural version ordering. */
  public static long versionKey(String tag)
  {
    String digits = tag == null ? "" : tag.replaceAll("\\D", "");
    try
    {
      return digits.isEmpty() ? 0 : Long.parseLong(digits);
    }
    catch (NumberFormatException e)
    {
      return 0;
    }
  }

  /**
   * Find the installed entry a model resolves to.
   * engineVersion "latest" (or empty) floats to the newest installed matching the variant;
   * engineVariant is a substring match ("rocm" matches "ubuntu-rocm-7.2-x64"), empty matches any.
   */
  public static Map<String, Object> resolve(Config cfg, Map<String, Object> model)
  {
    String engine = Json.str(model, "engine", "llama.cpp");
    String version = Json.str(model, "engineVersion", "latest");
    String variant = Json.str(model, "engineVariant", "");

    List<Map<String, Object>> candidates = new ArrayList<>();
    for (Map<String, Object> inst : cfg.installed(engine))
      if (variant.isEmpty() || Json.str(inst, "variant", "").contains(variant))
        candidates.add(inst);
    if (candidates.isEmpty())
      throw new HttpError(400, "no installed " + engine + " binary"
          + (variant.isEmpty() ? "" : " matching variant '" + variant + "'")
          + " — install one from the web UI (/configure/)");

    if (version.isEmpty() || "latest".equals(version))
    {
      candidates.sort(Comparator.comparingLong(m -> versionKey(Json.str(m, "version", ""))));
      return candidates.get(candidates.size() - 1);
    }
    for (Map<String, Object> inst : candidates)
      if (version.equals(Json.str(inst, "version", "")))
        return inst;
    throw new HttpError(400, "engine " + engine + " version '" + version + "'"
        + (variant.isEmpty() ? "" : " variant '" + variant + "'") + " is not installed");
  }

  /** True when this engine is run from a manually configured binary (e.g. ds4), not a managed install. */
  public static boolean isManualBinary(Config cfg, String engine)
  {
    return !Json.str(cfg.engine(engine), "binaryPath", "").isEmpty();
  }

  /**
   * Cross-reference: "engine/version/variant" -> names of configured models resolving to it.
   * Models pinned to "latest" count against the binary they currently resolve to.
   */
  public static Map<String, List<String>> usage(Config cfg)
  {
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (String engine : cfg.engines().keySet())
      for (Map<String, Object> inst : cfg.installed(engine))
        out.put(engine + "/" + Json.str(inst, "version", "") + "/" + Json.str(inst, "variant", ""),
            new ArrayList<>());
    for (Map<String, Object> model : cfg.models())
    {
      String engine = Json.str(model, "engine", "llama.cpp");
      if (isManualBinary(cfg, engine))
        continue;
      try
      {
        Map<String, Object> inst = resolve(cfg, model);
        String key = engine + "/" + Json.str(inst, "version", "") + "/" + Json.str(inst, "variant", "");
        out.computeIfAbsent(key, k -> new ArrayList<>()).add(Json.str(model, "name", "?"));
      }
      catch (HttpError ignored)
      {
        // model has no resolvable binary — shows up as unconfigured in the UI
      }
    }
    return out;
  }
}
