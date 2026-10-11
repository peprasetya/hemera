package ing.aether.hemera.comfy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import ing.aether.hemera.http.HttpError;
import ing.aether.hemera.json.Json;

/**
 * What hemera knows about one model family in ComfyUI.
 *
 * ComfyUI itself only knows files and nodes: it can say that {@code pixal3d_int8.safetensors}
 * sits in {@code diffusion_models}, not that it becomes a textured mesh when wired through
 * twenty particular nodes. A recipe is that missing knowledge, as data — which files make up a
 * model, which request parameters exist and where each lands in the graph, and the graph itself
 * in ComfyUI's own API format. Everything hemera offers from ComfyUI is some file matched to
 * some recipe, so a new family needs a JSON file rather than a new release.
 */
public final class Recipe
{
  /** A file the graph needs besides the model itself: a VAE, a text encoder, a LoRA. */
  public record Component(String name, String folder, List<Pattern> match, List<String> set, boolean optional)
  {
    /** The first file the patterns accept, patterns in order of preference. */
    String pick(List<String> files)
    {
      for (Pattern p : match)
        for (String f : files)
          if (p.matcher(f).find())
            return f;
      return null;
    }
  }

  /** Stores an uploaded image with ComfyUI and returns the name a LoadImage node takes. */
  public interface Uploader
  {
    String upload(byte[] image) throws Exception;
  }

  /** A graph ready to queue, and the values that were actually used for it. */
  public record Built(Map<String, Object> graph, Map<String, Object> used)
  {
  }

  public final String id;
  public final String title;
  public final String kind;
  public final String description;
  public final String modelFolder;
  public final String outputNode;
  public final List<Component> components = new ArrayList<>();

  private final Pattern modelMatch;
  private final Pattern modelExclude;
  private final List<String> modelSet;
  private final Map<String, Map<String, Object>> params = new LinkedHashMap<>();
  private final Map<String, Object> graph;

  public Recipe(Map<String, Object> json)
  {
    id = Json.str(json, "id", "");
    title = Json.str(json, "title", id);
    kind = Json.str(json, "kind", "");
    description = Json.str(json, "description", "");
    if (id.isEmpty() || !List.of("image", "video", "3d").contains(kind))
      throw new IllegalArgumentException("a recipe needs an 'id' and a 'kind' of image, video or 3d");

    Map<String, Object> model = Json.obj(json.get("model"));
    modelFolder = Json.str(model, "folder", "diffusion_models");
    modelMatch = Pattern.compile(Json.str(model, "match", "$^"));
    String exclude = Json.str(model, "exclude", "");
    modelExclude = exclude.isEmpty() ? null : Pattern.compile(exclude);
    modelSet = Json.strList(model, "set");

    for (Map.Entry<String, Object> e : Json.obj(json.get("components")).entrySet())
    {
      Map<String, Object> c = Json.obj(e.getValue());
      List<Pattern> patterns = new ArrayList<>();
      if (c.get("match") instanceof String s)
        patterns.add(Pattern.compile(s));
      for (String s : Json.strList(c, "match"))
        patterns.add(Pattern.compile(s));
      components.add(new Component(e.getKey(), Json.str(c, "folder", ""), patterns,
          Json.strList(c, "set"), Json.bool(c, "optional", false)));
    }
    for (Map.Entry<String, Object> e : Json.obj(json.get("params")).entrySet())
      params.put(e.getKey(), Json.obj(e.getValue()));

    graph = Json.obj(json.get("graph"));
    outputNode = Json.str(Json.obj(json.get("output")), "node", "");
    if (!graph.containsKey(outputNode))
      throw new IllegalArgumentException("recipe '" + id + "': output.node '" + outputNode + "' is not in the graph");
  }

  // ---- discovery ----------------------------------------------------------

  public boolean matches(String file)
  {
    return modelMatch.matcher(file).find() && (modelExclude == null || !modelExclude.matcher(file).find());
  }

  /** Every node class the graph can use; a ComfyUI lacking one cannot run this recipe. */
  public Set<String> classTypes()
  {
    Set<String> out = new LinkedHashSet<>();
    for (Object n : graph.values())
      out.add(Json.str(Json.obj(n), "class_type", ""));
    return out;
  }

  public boolean hasParam(String name)
  {
    return params.containsKey(name);
  }

  /** The declared default of a parameter, or null. */
  public Object paramDefault(String name)
  {
    Map<String, Object> p = params.get(name);
    return p == null ? null : p.get("default");
  }

  /** The request parameters as a client should see them, for building a form. */
  public Map<String, Object> describeParams(Map<String, String> found)
  {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, Object>> e : params.entrySet())
    {
      Map<String, Object> p = e.getValue();
      if (unavailable(p, found))
        continue;
      Map<String, Object> d = new LinkedHashMap<>();
      d.put("type", Json.str(p, "type", "string"));
      if (Json.bool(p, "required", false))
        d.put("required", Boolean.TRUE);
      Object def = defaultOf(p, found);
      if (def != null)
        d.put("default", def);
      for (String k : new String[] { "min", "max", "step", "offset", "description" })
        if (p.get(k) != null)
          d.put(k, p.get(k));
      if (p.get("values") instanceof Map<?, ?> m)
        d.put("values", new ArrayList<>(m.keySet()));
      else if (p.get("values") instanceof List<?> l)
        d.put("values", l);
      out.put(e.getKey(), d);
    }
    return out;
  }

  // ---- building a graph ---------------------------------------------------

  /**
   * Turn a request into a graph: file names in, parameters in, then everything the chosen output
   * does not depend on cut away — ComfyUI runs every output node it is given, so a preview left
   * dangling on an unused branch would still cost that whole branch.
   */
  public Built build(String modelFile, Map<String, String> found, Map<String, Object> request,
      String outputPrefix, Uploader uploader) throws Exception
  {
    Map<String, Object> g = copy(graph);
    for (String target : modelSet)
      put(g, target, modelFile);
    for (Component c : components)
      if (found.get(c.name()) != null)
        for (String target : c.set())
          put(g, target, found.get(c.name()));

    Map<String, Object> used = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, Object>> e : params.entrySet())
    {
      String name = e.getKey();
      Map<String, Object> p = e.getValue();
      String type = Json.str(p, "type", "string");
      Object raw = unavailable(p, found) ? null : request.get(name);
      if (raw instanceof String s && s.isBlank() && !"string".equals(type))
        raw = null;

      if ("image".equals(type))
      {
        if (raw == null)
        {
          if (Json.bool(p, "required", false))
            throw new HttpError(400, "'" + name + "' is required: " + title + " works from an image");
          continue;
        }
        byte[] bytes = raw instanceof byte[] b ? b : decodeImage(String.valueOf(raw), name);
        String stored = uploader.upload(bytes);
        for (String target : Json.strList(p, "set"))
          put(g, target, stored);
        for (Map.Entry<String, Object> l : Json.obj(p.get("link")).entrySet())
          put(g, l.getKey(), l.getValue());
        continue;
      }

      Object value = raw != null ? coerce(name, p, type, raw) : defaultOf(p, found);
      if ("random".equals(value))
        value = ThreadLocalRandom.current().nextLong(1L << 48);
      if (value == null)
      {
        if (Json.bool(p, "required", false))
          throw new HttpError(400, "missing '" + name + "'");
        continue;
      }
      used.put(name, value);
      Object apply = "bool".equals(type) ? Json.obj(p.get("apply")).get(String.valueOf(value))
          : Json.obj(p.get("values")).get(String.valueOf(value));
      for (Map.Entry<String, Object> a : Json.obj(apply).entrySet())
        put(g, a.getKey(), a.getValue());
      for (String target : Json.strList(p, "set"))
        put(g, target, value);
    }

    Map<String, Object> out = Json.obj(g.get(outputNode));
    if (Json.obj(out.get("inputs")).containsKey("filename_prefix"))
      put(g, outputNode + ".filename_prefix", outputPrefix);
    prune(g);

    // a file that is only needed down a branch nobody asked for is not worth refusing over
    for (Component c : components)
      if (found.get(c.name()) == null)
        for (String target : c.set())
          if (g.containsKey(target.substring(0, target.indexOf('.'))))
            throw new HttpError(400, "this request needs a " + c.name() + " file in ComfyUI's '"
                + c.folder() + "' folder (matching " + c.match().get(0) + ") and none is there");
    return new Built(g, used);
  }

  /** A parameter that depends on a file ComfyUI does not have is simply not offered. */
  private static boolean unavailable(Map<String, Object> p, Map<String, String> found)
  {
    String requires = Json.str(p, "requires", "");
    return !requires.isEmpty() && found.get(requires) == null;
  }

  private static Object defaultOf(Map<String, Object> p, Map<String, String> found)
  {
    if (unavailable(p, found))
      return "bool".equals(Json.str(p, "type", "")) ? Boolean.FALSE : null;
    return p.get("default");
  }

  private static Object coerce(String name, Map<String, Object> p, String type, Object raw)
  {
    switch (type)
    {
      case "int":
      case "number":
      {
        double v;
        try
        {
          v = raw instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(raw).trim());
        }
        catch (NumberFormatException e)
        {
          throw new HttpError(400, "'" + name + "' must be a number, got '" + raw + "'");
        }
        double step = Json.numd(p, "step", 0);
        if (step > 0)
        {
          // sizes round to the nearest value the model accepts; lengths round up, never short
          double offset = Json.numd(p, "offset", 0);
          double units = (v - offset) / step;
          v = offset + step * (p.containsKey("offset") ? Math.ceil(units - 1e-9) : Math.rint(units));
        }
        if (p.get("min") instanceof Number min && v < min.doubleValue()
            || p.get("max") instanceof Number max && v > max.doubleValue())
          throw new HttpError(400, "'" + name + "' must be between " + p.get("min") + " and " + p.get("max")
              + ", got " + raw);
        return "int".equals(type) ? (Object) Math.round(v) : (Object) v;
      }
      case "bool":
        if (raw instanceof Boolean)
          return raw;
        return "true".equalsIgnoreCase(String.valueOf(raw).trim()) || "1".equals(String.valueOf(raw).trim());
      case "enum":
      {
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        List<String> allowed = new ArrayList<>();
        if (p.get("values") instanceof Map<?, ?> m)
          m.keySet().forEach(k -> allowed.add(String.valueOf(k)));
        else
          Json.arr(p.get("values")).forEach(k -> allowed.add(String.valueOf(k)));
        if (!allowed.contains(s))
          throw new HttpError(400, "'" + name + "' must be one of " + allowed + ", got '" + raw + "'");
        return s;
      }
      default:
        return String.valueOf(raw);
    }
  }

  /** Raw base64 or a data: URL. */
  private static byte[] decodeImage(String s, String name)
  {
    String b64 = s.startsWith("data:") && s.contains(",") ? s.substring(s.indexOf(',') + 1) : s;
    try
    {
      return java.util.Base64.getDecoder().decode(b64.replaceAll("\\s", ""));
    }
    catch (IllegalArgumentException e)
    {
      throw new HttpError(400, "'" + name + "' is neither base64 nor a data: URL");
    }
  }

  /** Write one value at "node.input". */
  private void put(Map<String, Object> g, String target, Object value)
  {
    int dot = target.indexOf('.');
    Map<String, Object> node = Json.obj(g.get(dot < 0 ? target : target.substring(0, dot)));
    if (dot < 0 || !(node.get("inputs") instanceof Map))
      throw new IllegalStateException("recipe '" + id + "' points at '" + target + "', which is not in its graph");
    Json.obj(node.get("inputs")).put(target.substring(dot + 1), value);
  }

  /** Keep only the output node and what it depends on. */
  private void prune(Map<String, Object> g)
  {
    Set<String> keep = new LinkedHashSet<>();
    Deque<String> todo = new ArrayDeque<>(List.of(outputNode));
    while (!todo.isEmpty())
    {
      String nodeId = todo.pop();
      if (!keep.add(nodeId))
        continue;
      for (Object v : Json.obj(Json.obj(g.get(nodeId)).get("inputs")).values())
        if (v instanceof List<?> link && link.size() == 2 && link.get(0) instanceof String from
            && link.get(1) instanceof Number && g.containsKey(from))
          todo.push(from);
    }
    g.keySet().retainAll(keep);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> copy(Map<String, Object> m)
  {
    return (Map<String, Object>) Json.parse(Json.write(m));
  }
}
