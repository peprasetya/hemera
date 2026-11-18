package ing.aether.hemera.comfy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import ing.aether.hemera.json.Json;

/**
 * Recipes made from the workflow templates that ship with ComfyUI, so a model whose files came
 * from a template's own download links works without anyone writing a recipe.
 *
 * Each template names the files it needs (folder and name, on its loader nodes) and is maintained
 * by ComfyUI's authors alongside ComfyUI itself. Converted with {@link EditorWorkflow} and read by
 * the same {@link RecipeDrafter} that drafts a recipe from an uploaded workflow, it becomes an
 * ordinary recipe: the template's model file, its precision or quantisation left open, is the
 * family it recognises.
 *
 * Only what can run unattended from a request is taken: one result (image, video or 3D), at most
 * one image in, no audio or video in. A template that does not fit, or does not convert cleanly
 * against this ComfyUI, is left out with the reason.
 */
public final class TemplateRecipes
{
  /** A recipe drafted from one template, or why there is none; {@code order} ranks simpler first. */
  public record Drafted(String template, Map<String, Object> recipe, List<String> problems, long order)
  {
    public boolean ok()
    {
      return recipe != null && problems.isEmpty();
    }
  }

  public static final String ID_PREFIX = "template-";

  private static final Set<String> IMAGE_IN = Set.of("LoadImage", "LoadImageMask");

  /**
   * The settings a template recipe offers: the ones every client knows. Anything else a template
   * wires up (a size check written as an expression, a crop box) stays as its authors set it.
   */
  private static final Set<String> OFFERED = Set.of("prompt", "negative_prompt", "seed", "steps", "cfg_scale",
      "width", "height", "frames", "fps", "image");

  private TemplateRecipes()
  {
  }

  /** Templates that only call a paid cloud service, judged from the index entry alone. */
  public static boolean isCloudOnly(Map<String, Object> entry)
  {
    String name = Json.str(entry, "name", "");
    return name.startsWith("api_") || Boolean.FALSE.equals(entry.get("openSource"));
  }

  /**
   * Draft a recipe from one template: {@code entry} is its line in ComfyUI's templates index,
   * {@code workflow} the template itself, {@code info} ComfyUI's /object_info.
   */
  public static Drafted draft(Map<String, Object> entry, Map<String, Object> workflow, Map<String, Object> info)
  {
    String name = Json.str(entry, "name", "");
    List<String> problems = new ArrayList<>();
    if (isCloudOnly(entry))
      return new Drafted(name, null, List.of("it runs on a paid cloud service, not on this machine"), 0);

    // the files the template says it needs, by folder: the drafter files them under the right one
    Map<String, List<String>> folders = new LinkedHashMap<>();
    for (Map<String, Object> node : allNodes(workflow))
      for (Map<String, Object> m : Json.arrOfObj(Json.obj(node.get("properties")).get("models")))
      {
        String file = Json.str(m, "name", "");
        String dir = Json.str(m, "directory", "");
        if (!file.isEmpty() && !dir.isEmpty())
          folders.computeIfAbsent(dir, k -> new ArrayList<>()).add(file);
      }
    if (folders.isEmpty())
      return new Drafted(name, null, List.of("it names no model files"), 0);

    String preferred = null;
    for (Map<String, Object> out : Json.arrOfObj(Json.obj(entry.get("io")).get("outputs")))
      if (out.get("nodeId") != null)
        preferred = String.valueOf(out.get("nodeId"));
    EditorWorkflow.Converted c = EditorWorkflow.toApi(workflow, info, preferred);
    problems.addAll(c.problems());
    if (c.output() == null)
      return new Drafted(name, null, problems, 0);
    String outCls = Json.str(Json.obj(c.graph().get(c.output())), "class_type", "");
    if ("audio".equals(EditorWorkflow.RecipeDrafterAccess.kindOf(outCls)))
      problems.add("it makes audio, which hemera does not take from ComfyUI");

    int images = 0;
    for (Object n : c.graph().values())
    {
      String cls = Json.str(Json.obj(n), "class_type", "");
      if (IMAGE_IN.contains(cls))
        images++;
      else if (takesUpload(Json.obj(info.get(cls))))
        problems.add("it takes a " + cls.replaceFirst("^Load", "").toLowerCase(Locale.ROOT)
            + " as input, which a request cannot send");
    }
    if (images > 1)
      problems.add("it takes " + images + " images; hemera offers templates that take at most one");
    if (!problems.isEmpty())
      return new Drafted(name, null, problems, 0);

    String title = Json.str(entry, "title", name);
    RecipeDrafter.Draft d = RecipeDrafter.draft(c.graph(), info, folders, title);
    for (Object note : d.notes())
      problems.add(String.valueOf(note));
    Map<String, Object> recipe = d.recipe();
    Map<String, Object> model = Json.obj(recipe.get("model"));
    if (Json.str(model, "match", "").isEmpty())
      problems.add("it names no model file to recognise");
    if (!problems.isEmpty())
      return new Drafted(name, null, problems, 0);

    // one value fed several inputs in the editor: a setting must reach all of them
    for (Object p : Json.obj(recipe.get("params")).values())
    {
      Map<String, Object> param = Json.obj(p);
      Set<String> set = new LinkedHashSet<>(Json.strList(param, "set"));
      for (List<String> group : c.ties())
        if (group.stream().anyMatch(set::contains))
          set.addAll(group);
      param.put("set", new ArrayList<>(set));
      // the template's sample image is not in this ComfyUI's input folder: a request has to send one
      if ("image".equals(Json.str(param, "type", "")))
        param.put("required", Boolean.TRUE);
    }

    Json.obj(recipe.get("params")).keySet().retainAll(OFFERED);
    settleSamplers(recipe, c.graph());

    String id = ID_PREFIX + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
    if (id.length() > 64)
      id = id.substring(0, 64);
    recipe.put("id", id);
    recipe.put("title", title);
    String description = Json.str(entry, "description", "");
    recipe.put("description", description.length() > 400 ? description.substring(0, 397) + "..." : description);
    recipe.put("source", "template");
    recipe.put("template", name);
    // a rough figure: what the template downloads, twice over for the work. It can only raise
    // comfyui.memoryMB for this recipe, never lower it; a measured memoryMB replaces it.
    long size = Json.num(entry, "size", 0);
    if (size > 0)
      recipe.put("estimatedMemoryMB", size * 2 / (1024 * 1024));
    try
    {
      new Recipe(recipe);
    }
    catch (Exception e)
    {
      return new Drafted(name, null, List.of("the drafted recipe does not parse: " + e.getMessage()), 0);
    }

    // which template gets a file several of them name: for images and video the one that needs no
    // picture, for 3D the one that does; then the one ComfyUI's users run most; then the smaller
    boolean hasImage = images > 0;
    String kind = Json.str(recipe, "kind", "image");
    long usage = Math.max(0, Math.min(Json.num(entry, "usage", 0), 1_000_000_000L));
    long order = (("3d".equals(kind) ? !hasImage : hasImage) ? 1_000_000_000_000L : 0) - usage * 1000
        + Math.min(c.graph().size(), 999);
    return new Drafted(name, recipe, List.of(), order);
  }

  /**
   * A workflow with several sampling stages (a 3D model's shape, then its texture) is tuned stage by
   * stage: one seed reaches every stage, while steps and guidance stay as the template set them
   * rather than changing one stage of several. A 3D result has no size, so width and height,
   * which then only size some step along the way, are not offered either.
   */
  private static void settleSamplers(Map<String, Object> recipe, Map<String, Object> graph)
  {
    Map<String, Object> params = Json.obj(recipe.get("params"));
    List<String> seeds = new ArrayList<>();
    int steps = 0;
    int cfg = 0;
    for (Map.Entry<String, Object> n : graph.entrySet())
    {
      Map<String, Object> inputs = Json.obj(Json.obj(n.getValue()).get("inputs"));
      for (String k : new String[] { "seed", "noise_seed" })
        if (inputs.get(k) instanceof Number)
          seeds.add(n.getKey() + "." + k);
      if (inputs.get("steps") instanceof Number)
        steps++;
      if (inputs.get("cfg") instanceof Number)
        cfg++;
    }
    if (params.get("seed") instanceof Map<?, ?> && !seeds.isEmpty())
    {
      Set<String> set = new LinkedHashSet<>(Json.strList(Json.obj(params.get("seed")), "set"));
      set.addAll(seeds);
      Json.obj(params.get("seed")).put("set", new ArrayList<>(set));
    }
    if (steps > 1)
      params.remove("steps");
    if (cfg > 1)
      params.remove("cfg_scale");
    if ("3d".equals(Json.str(recipe, "kind", "")))
    {
      params.remove("width");
      params.remove("height");
    }
  }

  private static boolean takesUpload(Map<String, Object> def)
  {
    for (String sec : new String[] { "required", "optional" })
      for (Object spec : Json.obj(Json.obj(def.get("input")).get(sec)).values())
      {
        List<Object> l = Json.arr(spec);
        Map<String, Object> o = l.size() > 1 ? Json.obj(l.get(1)) : Map.of();
        if (Json.bool(o, "image_upload", false) || Json.bool(o, "video_upload", false)
            || Json.bool(o, "audio_upload", false) || Json.bool(o, "file_upload", false))
          return true;
      }
    return false;
  }

  private static List<Map<String, Object>> allNodes(Map<String, Object> workflow)
  {
    List<Map<String, Object>> out = new ArrayList<>(Json.arrOfObj(workflow.get("nodes")));
    for (Map<String, Object> sg : Json.arrOfObj(Json.obj(workflow.get("definitions")).get("subgraphs")))
      out.addAll(Json.arrOfObj(sg.get("nodes")));
    return out;
  }
}
