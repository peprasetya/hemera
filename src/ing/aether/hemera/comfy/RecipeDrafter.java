package ing.aether.hemera.comfy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import ing.aether.hemera.http.HttpError;
import ing.aether.hemera.json.Json;

/**
 * Turns a workflow exported from ComfyUI ("Save (API Format)") into a first draft of a recipe, so
 * nobody has to write one by hand.
 *
 * ComfyUI knows what every input of every node is — its type, its range, the files it can choose
 * from — and the workflow holds the values its author settled on. Reading the two together is
 * enough to tell which inputs pick model files (they become the recipe's files), which take the
 * prompt, the seed, the size or the number of steps (they become parameters a client may set), and
 * which node saves the result. Everything else stays in the graph with the author's value, and is
 * offered as a candidate: one click exposes it.
 *
 * This is a heuristic and says so: whatever it could not decide comes back in {@code notes}.
 */
final class RecipeDrafter
{
  /** What the draft found: the recipe, every input that could still be exposed, and caveats. */
  record Draft(Map<String, Object> recipe, List<Object> candidates, List<Object> outputs, List<Object> notes)
  {
    Map<String, Object> toJson()
    {
      return Json.map("recipe", recipe, "candidates", candidates, "outputs", outputs, "notes", notes);
    }
  }

  private static final Set<String> FILE_EXT = Set.of(".safetensors", ".gguf", ".ckpt", ".pt", ".pth", ".bin", ".sft");

  /** Where the file named by an input usually lives, in order of preference. */
  private static final Map<String, List<String>> FOLDER_OF = Map.ofEntries(
      Map.entry("unet_name", List.of("diffusion_models", "unet")),
      Map.entry("ckpt_name", List.of("checkpoints")),
      Map.entry("clip_name", List.of("text_encoders", "clip")),
      Map.entry("clip_name1", List.of("text_encoders", "clip")),
      Map.entry("clip_name2", List.of("text_encoders", "clip")),
      Map.entry("vae_name", List.of("vae")),
      Map.entry("lora_name", List.of("loras")),
      Map.entry("control_net_name", List.of("controlnet")),
      Map.entry("model_name", List.of("upscale_models", "diffusion_models")),
      Map.entry("style_model_name", List.of("style_models")),
      Map.entry("name", List.of("diffusion_models")));

  private static final List<String> MODEL_INPUTS = List.of("unet_name", "ckpt_name", "model_name", "name");

  private static final String VARIANT = "(bf16|fp16|fp32|fp8\\w*|int8\\w*|int4\\w*|w4a8|convrot|q\\d\\w*|f16|f32|pruned|ema|gguf)";

  /** A file input found in the workflow. */
  private record FileSlot(String ref, String input, String value, String folder)
  {
  }

  /** What ComfyUI says about one input. */
  private record Spec(String type, List<Object> options, Map<String, Object> opts)
  {
  }

  static Draft draft(Map<String, Object> workflow, Map<String, Object> info,
      Map<String, List<String>> folders, String title)
  {
    Map<String, Object> graph = unwrap(workflow);
    List<Object> notes = new ArrayList<>();
    List<Object> candidates = new ArrayList<>();
    List<Object> outputs = new ArrayList<>();
    List<FileSlot> files = new ArrayList<>();
    Map<String, Map<String, Object>> params = new LinkedHashMap<>();

    Map<String, List<String[]>> consumers = new LinkedHashMap<>();
    for (Map.Entry<String, Object> n : graph.entrySet())
      for (Map.Entry<String, Object> in : Json.obj(Json.obj(n.getValue()).get("inputs")).entrySet())
        if (in.getValue() instanceof List<?> l && l.size() == 2 && l.get(0) instanceof String from)
          consumers.computeIfAbsent(from, k -> new ArrayList<>()).add(new String[] { n.getKey(), in.getKey() });

    List<String> ids = new ArrayList<>(graph.keySet());
    ids.sort((a, b) -> {
      try
      {
        return Long.compare(Long.parseLong(a), Long.parseLong(b));
      }
      catch (NumberFormatException e)
      {
        return a.compareTo(b);
      }
    });

    String kind = null;
    String outputNode = null;
    Set<String> unknown = new java.util.LinkedHashSet<>();
    for (String id : ids)
    {
      Map<String, Object> node = Json.obj(graph.get(id));
      String cls = Json.str(node, "class_type", "");
      Map<String, Object> def = Json.obj(info.get(cls));
      if (def.isEmpty())
      {
        unknown.add(cls);
        continue;
      }
      String nodeTitle = Json.str(Json.obj(node.get("_meta")), "title", cls);
      if (Json.bool(def, "output_node", false) && !cls.startsWith("Preview"))
      {
        String k = kindOf(cls);
        outputs.add(Json.map("node", id, "class", cls, "title", nodeTitle, "kind", k));
        // a workflow may save a preview image beside the real result: 3D beats video beats image
        if (outputNode == null || rank(k) > rank(kind))
        {
          outputNode = id;
          kind = k;
        }
      }
      for (Map.Entry<String, Object> in : Json.obj(node.get("inputs")).entrySet())
      {
        String name = in.getKey();
        Object value = in.getValue();
        if (value instanceof List)
          continue;
        Spec spec = spec(def, name);
        if (spec == null)
          continue;
        String ref = id + "." + name;
        boolean combo = spec.options() != null;
        if (combo && value instanceof String s && isFile(s))
        {
          files.add(new FileSlot(ref, name, s, folderOf(name, s, folders)));
          continue;
        }
        Map<String, Object> c = candidate(ref, id, cls, nodeTitle, name, value, spec);
        if (c == null || name.equals("filename_prefix"))
          continue; // hemera names its own outputs
        candidates.add(c);
        expose(params, c, spec, name, id, consumers, graph, kind);
      }
    }
    if (!unknown.isEmpty())
      notes.add("this ComfyUI has no definition for " + String.join(", ", unknown)
          + " — install the custom node it comes from; those nodes were left out of the choices below");
    if ("3d".equals(kind) && params.get("image") != null)
      params.get("image").put("required", Boolean.TRUE);
    if (outputNode == null)
      notes.add("no node that saves a result was found (SaveImage, SaveVideo, SaveGLB …) — pick one under Result");

    // ---- files: one model, the rest components ------------------------------------------
    FileSlot model = null;
    for (String pref : MODEL_INPUTS)
      for (FileSlot f : files)
        if (model == null && f.input().equals(pref))
          model = f;
    if (model == null && !files.isEmpty())
      model = files.get(0);
    Map<String, Object> modelJson = new LinkedHashMap<>();
    Map<String, Object> components = new LinkedHashMap<>();
    if (model == null)
    {
      notes.add("the workflow names no model file — set the model's folder and pattern by hand");
      modelJson.put("folder", "diffusion_models");
      modelJson.put("match", "");
      modelJson.put("set", List.of());
    }
    else
    {
      modelJson.put("folder", model.folder());
      modelJson.put("match", generic(model.value()));
      List<String> set = new ArrayList<>();
      for (FileSlot f : files)
        if (f.input().equals(model.input()) && f.value().equals(model.value()))
          set.add(f.ref());
      modelJson.put("set", set);
      Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
      for (FileSlot f : files)
      {
        if (set.contains(f.ref()))
          continue;
        String key = f.folder() + "/" + f.value();
        Map<String, Object> existing = byKey.get(key);
        if (existing != null)
        {
          @SuppressWarnings("unchecked")
          List<String> s = (List<String>) existing.get("set");
          s.add(f.ref());
          continue;
        }
        String cname = componentName("clip_vision".equals(f.folder()) ? "clip_vision_name" : f.input(),
            components.keySet());
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("folder", f.folder());
        comp.put("match", List.of("(?i)(^|/)" + esc(leaf(f.value())) + "$", generic(f.value())));
        comp.put("set", new ArrayList<>(List.of(f.ref())));
        components.put(cname, comp);
        byKey.put(key, comp);
      }
    }

    String base = model == null ? "" : baseOf(model.value());
    String recipeId = slug(title != null && !title.isBlank() ? title : base);
    if (recipeId.isEmpty())
      recipeId = "new-recipe";

    Map<String, Object> recipe = new LinkedHashMap<>();
    recipe.put("id", recipeId);
    recipe.put("title", title != null && !title.isBlank() ? title : pretty(base.isEmpty() ? recipeId : base));
    recipe.put("kind", kind == null ? "image" : kind);
    recipe.put("description", "");
    recipe.put("model", modelJson);
    recipe.put("components", components);
    recipe.put("params", params);
    recipe.put("output", Json.map("node", outputNode == null ? "" : outputNode));
    recipe.put("graph", blank(graph, files));
    return new Draft(recipe, candidates, outputs, notes);
  }

  // ---- reading the workflow ------------------------------------------------------------------

  private static Map<String, Object> unwrap(Map<String, Object> w)
  {
    if (w.get("nodes") instanceof List && w.get("links") instanceof List)
      throw new HttpError(400, "that is a workflow saved from the editor; in ComfyUI use the menu's"
          + " 'Save (API Format)' (or 'Export (API)') and give hemera that file");
    Map<String, Object> g = w.get("prompt") instanceof Map && !(w.get("prompt") instanceof List) && w.size() <= 3
        ? Json.obj(w.get("prompt")) : w;
    if (g.isEmpty())
      throw new HttpError(400, "the workflow is empty");
    for (Map.Entry<String, Object> e : g.entrySet())
      if (!(e.getValue() instanceof Map<?, ?> m) || !(m.get("class_type") instanceof String))
        throw new HttpError(400, "this is not a workflow in API format: '" + e.getKey()
            + "' has no class_type — in ComfyUI use 'Save (API Format)'");
    return g;
  }

  private static Spec spec(Map<String, Object> def, String input)
  {
    Map<String, Object> in = Json.obj(def.get("input"));
    Object v = Json.obj(in.get("required")).get(input);
    if (v == null)
      v = Json.obj(in.get("optional")).get(input);
    if (!(v instanceof List<?> l) || l.isEmpty())
      return null;
    Map<String, Object> opts = l.size() > 1 && l.get(1) instanceof Map ? Json.obj(l.get(1)) : new LinkedHashMap<>();
    if (l.get(0) instanceof List<?> options)
      return new Spec("COMBO", new ArrayList<>(options), opts);
    String type = String.valueOf(l.get(0));
    if ("COMBO".equals(type))
      return new Spec("COMBO", new ArrayList<>(Json.arr(opts.get("options"))), opts);
    return new Spec(type, null, opts);
  }

  private static boolean isFile(String s)
  {
    String low = s.toLowerCase(Locale.ROOT);
    for (String e : FILE_EXT)
      if (low.endsWith(e))
        return true;
    return false;
  }

  private static String folderOf(String input, String value, Map<String, List<String>> folders)
  {
    List<String> prefer = FOLDER_OF.getOrDefault(input, List.of());
    for (String f : prefer)
      if (folders.getOrDefault(f, List.of()).contains(value))
        return f;
    for (Map.Entry<String, List<String>> e : folders.entrySet())
      if (e.getValue().contains(value))
        return e.getKey();
    return prefer.isEmpty() ? "diffusion_models" : prefer.get(0);
  }

  private static int rank(String kind)
  {
    return "3d".equals(kind) ? 2 : "video".equals(kind) ? 1 : 0;
  }

  private static String kindOf(String cls)
  {
    String c = cls.toLowerCase(Locale.ROOT);
    if (c.contains("glb") || c.contains("mesh") || c.contains("3d") || c.contains("obj") || c.contains("gltf"))
      return "3d";
    if (c.contains("video") || c.contains("webm") || c.contains("animated"))
      return "video";
    return "image";
  }

  /** One input that could be offered to clients, with whatever ComfyUI says about its range. */
  private static Map<String, Object> candidate(String ref, String node, String cls, String title, String input,
      Object value, Spec spec)
  {
    String type;
    switch (spec.type())
    {
      case "INT":
        type = "int";
        break;
      case "FLOAT":
        type = "number";
        break;
      case "BOOLEAN":
        type = "bool";
        break;
      case "STRING":
        type = "string";
        break;
      case "COMBO":
        type = Json.bool(spec.opts(), "image_upload", false) ? "image" : "enum";
        break;
      default:
        return null;
    }
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("ref", ref);
    c.put("node", node);
    c.put("class", cls);
    c.put("title", title);
    c.put("input", input);
    c.put("type", type);
    c.put("value", value);
    if ("int".equals(type) || "number".equals(type))
    {
      if (spec.opts().get("min") instanceof Number n)
        c.put("min", n);
      if (spec.opts().get("max") instanceof Number n)
        c.put("max", n);
      if ("int".equals(type) && spec.opts().get("step") instanceof Number n && n.doubleValue() > 1)
        c.put("step", n);
    }
    if ("enum".equals(type))
      c.put("values", spec.options().size() > 200 ? spec.options().subList(0, 200) : spec.options());
    if ("string".equals(type))
      c.put("multiline", Json.bool(spec.opts(), "multiline", false));
    return c;
  }

  // ---- deciding what clients may set ---------------------------------------------------------

  private static void expose(Map<String, Map<String, Object>> params, Map<String, Object> c, Spec spec,
      String input, String node, Map<String, List<String[]>> consumers, Map<String, Object> graph, String kind)
  {
    String type = (String) c.get("type");
    String lower = input.toLowerCase(Locale.ROOT);
    String name = null;
    Object def = c.get("value");
    switch (type)
    {
      case "int":
        if (lower.equals("seed") || lower.equals("noise_seed"))
        {
          name = "seed";
          def = "random";
        }
        else if (lower.equals("steps"))
          name = "steps";
        else if (lower.equals("width") || lower.equals("height"))
          name = lower;
        else if (lower.equals("length") || lower.equals("frames") || lower.equals("num_frames"))
          name = "frames";
        else if (lower.equals("frame_rate") || lower.equals("fps"))
          name = "fps";
        break;
      case "number":
        if (lower.equals("cfg"))
          name = "cfg_scale";
        else if (lower.equals("frame_rate") || lower.equals("fps"))
          name = "fps";
        break;
      case "string":
        if (Json.bool(c, "multiline", false) || lower.equals("text") || lower.equals("prompt"))
        {
          if (lower.equals("prompt") || lower.equals("negative_prompt"))
            name = lower;
          else if (lower.equals("text") || lower.equals("positive"))
            name = trace(node, consumers);
          else
            name = lower;
          // the workflow's own sample text is not a default worth keeping for a required prompt
          if (name.equals("prompt") || name.equals("negative_prompt"))
            def = "";
        }
        break;
      case "image":
        name = "image";
        def = null;
        break;
      default:
        break;
    }
    if (name == null || params.containsKey(name))
      return;
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("type", type);
    if ("string".equals(type) && "prompt".equals(name))
      p.put("required", Boolean.TRUE);
    if ("image".equals(type) && "3d".equals(kind))
      p.put("required", Boolean.TRUE);
    if (def != null)
      p.put("default", def);
    if ("int".equals(type) || "number".equals(type))
    {
      for (String k : new String[] { "min", "max", "step" })
        if (c.get(k) != null)
          p.put(k, c.get(k));
      if ("seed".equals(name))
      {
        p.put("min", 0L);
        p.remove("max");
      }
      if ("frames".equals(name) && p.get("step") instanceof Number st && c.get("min") instanceof Number mn
          && st.longValue() > 1 && mn.longValue() % st.longValue() != 0)
        p.put("offset", mn.longValue() % st.longValue());
    }
    p.put("set", new ArrayList<>(List.of((String) c.get("ref"))));
    params.put(name, p);
  }

  /** "prompt" or "negative_prompt": which sampler input does this text end up feeding? */
  private static String trace(String start, Map<String, List<String[]>> consumers)
  {
    Deque<String[]> todo = new ArrayDeque<>();
    todo.add(new String[] { start, "0" });
    Set<String> seen = new HashSet<>();
    while (!todo.isEmpty())
    {
      String[] at = todo.poll();
      if (!seen.add(at[0]) || Integer.parseInt(at[1]) > 6)
        continue;
      for (String[] use : consumers.getOrDefault(at[0], List.of()))
      {
        String in = use[1].toLowerCase(Locale.ROOT);
        if (in.equals("negative"))
          return "negative_prompt";
        if (in.equals("positive"))
          return "prompt";
        todo.add(new String[] { use[0], String.valueOf(Integer.parseInt(at[1]) + 1) });
      }
    }
    return "prompt";
  }

  // ---- naming and patterns -------------------------------------------------------------------

  private static String componentName(String input, Set<String> taken)
  {
    String n = switch (input)
    {
      case "clip_name", "clip_name1", "clip_name2" -> "text_encoder";
      case "vae_name" -> "vae";
      case "lora_name" -> "lora";
      case "control_net_name" -> "controlnet";
      default -> input.replaceAll("_name\\d*$", "");
    };
    String name = n;
    for (int i = 2; taken.contains(name); i++)
      name = n + "_" + i;
    return name;
  }

  static String leaf(String file)
  {
    return file.substring(Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\')) + 1);
  }

  private static String baseOf(String file)
  {
    String name = leaf(file);
    int dot = name.lastIndexOf('.');
    String stem = dot > 0 ? name.substring(0, dot) : name;
    String base = stem.replaceAll("(?i)([-_.]" + VARIANT + ")+$", "");
    return base.isEmpty() ? stem : base;
  }

  /** The file's own name, and the same name with any precision or quantisation left open. */
  static String generic(String file)
  {
    String name = leaf(file);
    int dot = name.lastIndexOf('.');
    String ext = dot > 0 ? name.substring(dot) : "";
    return "(?i)(^|/)" + esc(baseOf(file)) + "([-_.]" + VARIANT + ")*" + esc(ext) + "$";
  }

  private static String esc(String s)
  {
    return s.replaceAll("([\\\\.\\[\\]{}()*+?^$|])", "\\\\$1");
  }

  private static String slug(String s)
  {
    return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.]+", "-").replaceAll("^-+|-+$", "");
  }

  private static String pretty(String s)
  {
    String t = s.replaceAll("[_-]+", " ").trim();
    return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
  }

  /** The workflow as the recipe carries it: file names empty, because a recipe fills them in. */
  private static Map<String, Object> blank(Map<String, Object> graph, List<FileSlot> files)
  {
    @SuppressWarnings("unchecked")
    Map<String, Object> g = (Map<String, Object>) Json.parse(Json.write(graph));
    for (FileSlot f : files)
    {
      int dot = f.ref().indexOf('.');
      Json.obj(Json.obj(g.get(f.ref().substring(0, dot))).get("inputs")).put(f.ref().substring(dot + 1), "");
    }
    return g;
  }
}
