package ing.aether.hemera.comfy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import ing.aether.hemera.json.Json;

/**
 * Turns a workflow as ComfyUI's editor saves it — nodes and links, subgraphs, widget values in a
 * list — into the API format that ComfyUI's /prompt runs, the way the editor itself does when it
 * queues a job.
 *
 * What the editor alone understands is resolved here: subgraphs are flattened (inner nodes get ids
 * like "76:12", as the editor gives them), notes and reroutes disappear, bypassed nodes pass their
 * input through, primitives become the value they hold, and a switch whose position is fixed in the
 * workflow becomes the branch it selects. Widget values are matched to input names by this
 * ComfyUI's own node definitions, dynamic combos included.
 *
 * It refuses rather than guesses: a node whose stored values do not line up with its definition,
 * or a value its definition does not allow, is reported as a problem, and a workflow with problems
 * is not used.
 */
public final class EditorWorkflow
{
  /**
   * The graph in API format, cut down to the chosen output and what it depends on; groups of
   * "node.input" refs that one value fed in the editor (so a setting reaches all of them); and
   * what kept it from converting cleanly.
   */
  public record Converted(Map<String, Object> graph, String output, List<List<String>> ties, List<String> problems)
  {
  }

  private static final Set<String> WIDGET = Set.of("INT", "FLOAT", "STRING", "BOOLEAN", "COMBO");
  private static final String DYNAMIC_COMBO = "COMFY_DYNAMICCOMBO_V3";
  private static final Set<String> CONTROL = Set.of("fixed", "increment", "decrement", "randomize");
  private static final Set<String> NOTES = Set.of("MarkdownNote", "Note");
  private static final Set<String> PRIMITIVES = Set.of("PrimitiveInt", "PrimitiveFloat", "PrimitiveBoolean",
      "PrimitiveString", "PrimitiveStringMultiline");
  private static final long SUBGRAPH_IN = -10;
  private static final long SUBGRAPH_OUT = -20;
  private static final int MAX_DEPTH = 64;

  /** Keep the input's own widget value: what an unconnected subgraph input means. */
  private static final Object OWN_VALUE = new Object();

  /** Where an input's value comes from: another node's output, or a value known now. */
  private record Source(String node, long slot, Object value, String tie)
  {
    static Source of(String node, long slot)
    {
      return new Source(node, slot, null, null);
    }

    static Source value(Object v, String tie)
    {
      return new Source(null, 0, v, tie);
    }
  }

  /** One level of the workflow: the top, or one use of a subgraph inside another level. */
  private static final class Scope
  {
    final String prefix;
    final Map<Long, Map<String, Object>> nodes = new LinkedHashMap<>();
    final Map<Long, long[]> links = new HashMap<>();
    final List<Map<String, Object>> inputs;
    final Scope parent;
    final Map<String, Object> instance;
    final Map<Long, Scope> children = new HashMap<>();

    Scope(String prefix, Map<String, Object> level, Scope parent, Map<String, Object> instance)
    {
      this.prefix = prefix;
      this.parent = parent;
      this.instance = instance;
      this.inputs = Json.arrOfObj(level.get("inputs"));
      for (Map<String, Object> n : Json.arrOfObj(level.get("nodes")))
        if (n.get("id") instanceof Number id)
          nodes.put(id.longValue(), n);
      for (Object l : Json.arr(level.get("links")))
      {
        if (l instanceof List<?> a && a.size() >= 5 && a.get(0) instanceof Number)
          links.put(((Number) a.get(0)).longValue(), new long[] { num(a.get(1)), num(a.get(2)), num(a.get(3)), num(a.get(4)) });
        else if (l instanceof Map<?, ?>)
        {
          Map<String, Object> m = Json.obj(l);
          links.put(Json.num(m, "id", 0), new long[] { Json.num(m, "origin_id", 0), Json.num(m, "origin_slot", 0),
              Json.num(m, "target_id", 0), Json.num(m, "target_slot", 0) });
        }
      }
    }

    String flat(long id)
    {
      return prefix + id;
    }
  }

  private final Map<String, Object> info;
  private final Map<String, Map<String, Object>> subgraphs = new HashMap<>();
  private final Map<String, Object> graph = new LinkedHashMap<>();
  private final Map<String, List<String>> nodeProblems = new LinkedHashMap<>();
  private final List<String> problems = new ArrayList<>();
  private final Map<String, Set<String>> ties = new LinkedHashMap<>();

  /** Every type some node outputs: an input of any other type cannot be a socket, so it is a widget. */
  private final Set<String> produced = new java.util.HashSet<>();

  private EditorWorkflow(Map<String, Object> info)
  {
    this.info = info;
    for (Object def : info.values())
      for (Object t : Json.arr(Json.obj(def).get("output")))
        if (t instanceof String ts)
          for (String part : ts.split(","))
            produced.add(part.trim());
  }

  /** Is this input a widget in the editor (it holds a value) rather than a socket? */
  private boolean isWidget(Object t, Map<String, Object> o)
  {
    if (Json.bool(o, "forceInput", false))
      return false;
    if (t instanceof List)
      return true;
    String ts = String.valueOf(t);
    if (WIDGET.contains(ts) || DYNAMIC_COMBO.equals(ts) || Json.bool(o, "socketless", false)
        || o.get("widgetType") instanceof String || o.containsKey("default"))
      return true;
    if (ts.startsWith("COMFY_"))
      return false; // the growing and grouped sockets of ComfyUI's newer node schema
    for (String part : ts.split(","))
      if (produced.contains(part.trim()))
        return false;
    return true;
  }

  /**
   * Convert an editor workflow. {@code preferredOutput} is the top-level node id the template
   * names as its result, or null; otherwise the output that makes the most (3D, then video, then
   * an image) is taken. Previews never count as a result.
   */
  public static Converted toApi(Map<String, Object> workflow, Map<String, Object> info, String preferredOutput)
  {
    EditorWorkflow w = new EditorWorkflow(info);
    for (Map<String, Object> sg : Json.arrOfObj(Json.obj(workflow.get("definitions")).get("subgraphs")))
      w.subgraphs.put(Json.str(sg, "id", ""), sg);
    Scope top = new Scope("", workflow, null, null);
    if (top.nodes.isEmpty())
      return new Converted(Map.of(), null, List.of(), List.of("the workflow has no nodes"));
    w.emit(top, 0);

    String output = w.pickOutput(preferredOutput);
    if (output == null)
    {
      w.problems.add("no node in it saves an image, a video or a 3D model");
      return new Converted(w.graph, null, List.of(), w.problems);
    }
    Set<String> keep = ancestors(w.graph, output);
    w.graph.keySet().retainAll(keep);
    for (String id : keep)
      w.problems.addAll(w.nodeProblems.getOrDefault(id, List.of()));
    for (Map.Entry<String, Object> n : w.graph.entrySet())
      for (Map.Entry<String, Object> in : Json.obj(Json.obj(n.getValue()).get("inputs")).entrySet())
        if (in.getValue() instanceof List<?> l && l.size() == 2 && l.get(0) instanceof String from && !w.graph.containsKey(from))
          w.problems.add("node " + n.getKey() + " takes '" + in.getKey() + "' from node " + from + ", which is not there");

    List<List<String>> groups = new ArrayList<>();
    for (Set<String> refs : w.ties.values())
    {
      List<String> live = new ArrayList<>();
      for (String ref : refs)
        if (keep.contains(ref.substring(0, ref.indexOf('.'))))
          live.add(ref);
      if (live.size() > 1)
        groups.add(live);
    }
    return new Converted(w.graph, output, groups, w.problems);
  }

  // ---- emitting the executable nodes ---------------------------------------------------------

  private void emit(Scope s, int depth)
  {
    if (depth > MAX_DEPTH)
    {
      problems.add("subgraphs are nested too deeply");
      return;
    }
    for (Map.Entry<Long, Map<String, Object>> e : s.nodes.entrySet())
    {
      long id = e.getKey();
      Map<String, Object> node = e.getValue();
      String type = Json.str(node, "type", "");
      long mode = Json.num(node, "mode", 0);
      if (mode == 2 || mode == 4 || NOTES.contains(type) || "Reroute".equals(type) || "PrimitiveNode".equals(type))
        continue;
      if (subgraphs.containsKey(type))
      {
        emit(child(s, id), depth + 1);
        continue;
      }
      String flat = s.flat(id);
      Map<String, Object> def = Json.obj(info.get(type));
      Map<String, Object> inputs = new LinkedHashMap<>();
      List<String> trouble = new ArrayList<>();
      if (def.isEmpty())
        trouble.add("this ComfyUI has no '" + type + "' node — install the custom node it comes from");
      else
        widgetValues(Json.obj(def.get("input")), "", Json.arr(node.get("widgets_values")), inputs, trouble, type);

      for (Map<String, Object> in : Json.arrOfObj(node.get("inputs")))
      {
        String name = Json.str(in, "name", "");
        if (name.isEmpty() || !(in.get("link") instanceof Number link))
          continue;
        Source src = resolveLink(s, link.longValue(), depth);
        if (src == null)
          inputs.remove(name);
        else if (src.node() != null)
          inputs.put(name, List.of(src.node(), src.slot()));
        else
        {
          if (src.value() != OWN_VALUE)
            inputs.put(name, src.value());
          if (src.tie() != null)
            ties.computeIfAbsent(src.tie(), k -> new LinkedHashSet<>()).add(flat + "." + name);
        }
      }
      if (!def.isEmpty())
        check(Json.obj(def.get("input")), inputs, trouble, type);
      String title = Json.str(node, "title", type);
      graph.put(flat, Json.map("class_type", type, "inputs", inputs, "_meta", Json.map("title", title)));
      if (!trouble.isEmpty())
      {
        List<String> withWhere = new ArrayList<>();
        for (String t : trouble)
          withWhere.add(type + " (node " + flat + "): " + t);
        nodeProblems.put(flat, withWhere);
      }
    }
  }

  private Scope child(Scope s, long instanceId)
  {
    return s.children.computeIfAbsent(instanceId, k -> {
      Map<String, Object> inst = s.nodes.get(instanceId);
      return new Scope(s.prefix + instanceId + ":", subgraphs.get(Json.str(inst, "type", "")), s, inst);
    });
  }

  // ---- following links -------------------------------------------------------------------------

  private Source resolveLink(Scope s, long linkId, int depth)
  {
    long[] l = s.links.get(linkId);
    return l == null ? null : resolveOutput(s, l[0], l[1], depth + 1);
  }

  /** What arrives from output {@code slot} of node {@code id} in scope {@code s}; null for nothing. */
  private Source resolveOutput(Scope s, long id, long slot, int depth)
  {
    if (depth > MAX_DEPTH)
      return null;
    if (id == SUBGRAPH_IN)
    {
      if (s.parent == null || slot < 0 || slot >= s.inputs.size())
        return null;
      String name = Json.str(s.inputs.get((int) slot), "name", "");
      for (Map<String, Object> in : Json.arrOfObj(s.instance.get("inputs")))
        if (name.equals(Json.str(in, "name", "")) && in.get("link") instanceof Number link)
          return resolveLink(s.parent, link.longValue(), depth);
      // left unconnected on the subgraph: every inner node it feeds keeps its own value
      return Source.value(OWN_VALUE, s.prefix + "in" + slot);
    }
    Map<String, Object> node = s.nodes.get(id);
    if (node == null)
      return null;
    String type = Json.str(node, "type", "");
    long mode = Json.num(node, "mode", 0);
    if (mode == 2)
      return null;
    if (mode == 4)
    {
      // bypassed: the first input of the same type as the output passes straight through
      String want = Json.str(Json.arrOfObj(node.get("outputs")).size() > slot
          ? Json.arrOfObj(node.get("outputs")).get((int) slot) : Map.of(), "type", "");
      for (Map<String, Object> in : Json.arrOfObj(node.get("inputs")))
        if (want.equals(Json.str(in, "type", "")) && in.get("link") instanceof Number link)
          return resolveLink(s, link.longValue(), depth);
      return null;
    }
    if (subgraphs.containsKey(type))
    {
      Scope inner = child(s, id);
      for (long[] l : inner.links.values())
        if (l[2] == SUBGRAPH_OUT && l[3] == slot)
          return resolveOutput(inner, l[0], l[1], depth + 1);
      return null;
    }
    if ("Reroute".equals(type))
    {
      List<Map<String, Object>> ins = Json.arrOfObj(node.get("inputs"));
      return !ins.isEmpty() && ins.get(0).get("link") instanceof Number link ? resolveLink(s, link.longValue(), depth) : null;
    }
    if ("PrimitiveNode".equals(type))
    {
      List<Object> v = Json.arr(node.get("widgets_values"));
      return v.isEmpty() ? null : Source.value(v.get(0), s.flat(id));
    }
    if (PRIMITIVES.contains(type))
    {
      for (Map<String, Object> in : Json.arrOfObj(node.get("inputs")))
        if ("value".equals(Json.str(in, "name", "")) && in.get("link") instanceof Number link)
          return resolveLink(s, link.longValue(), depth);
      List<Object> v = Json.arr(node.get("widgets_values"));
      return v.isEmpty() ? Source.of(s.flat(id), slot) : Source.value(v.get(0), s.flat(id));
    }
    if ("ComfySwitchNode".equals(type))
    {
      Object position = null;
      boolean linked = false;
      for (Map<String, Object> in : Json.arrOfObj(node.get("inputs")))
        if ("switch".equals(Json.str(in, "name", "")) && in.get("link") instanceof Number link)
        {
          linked = true;
          Source sw = resolveLink(s, link.longValue(), depth);
          if (sw != null && sw.node() == null && sw.value() != OWN_VALUE)
            position = sw.value();
        }
      if (!linked)
      {
        List<Object> v = Json.arr(node.get("widgets_values"));
        position = v.isEmpty() ? null : v.get(0);
      }
      if (position instanceof Boolean on)
      {
        String branch = on ? "on_true" : "on_false";
        for (Map<String, Object> in : Json.arrOfObj(node.get("inputs")))
          if (branch.equals(Json.str(in, "name", "")))
            return in.get("link") instanceof Number link ? resolveLink(s, link.longValue(), depth) : null;
        return null;
      }
    }
    return Source.of(s.flat(id), slot);
  }

  // ---- widget values -------------------------------------------------------------------------

  /** Read the editor's list of widget values against the node's inputs, in definition order. */
  private void widgetValues(Map<String, Object> def, String prefix, List<Object> values,
      Map<String, Object> out, List<String> trouble, String type)
  {
    // values left over at the end belong to widgets only the editor draws (a preview, an upload
    // button); they shift nothing before them, and a value out of place is caught by check()
    section(def, prefix, values, new int[] { 0 }, out);
  }

  private void section(Map<String, Object> def, String prefix, List<Object> values, int[] at,
      Map<String, Object> out)
  {
    for (String sec : new String[] { "required", "optional" })
      for (Map.Entry<String, Object> e : Json.obj(def.get(sec)).entrySet())
      {
        List<Object> spec = Json.arr(e.getValue());
        if (spec.isEmpty())
          continue;
        Object t = spec.get(0);
        Map<String, Object> o = spec.size() > 1 ? Json.obj(spec.get(1)) : Map.of();
        if (Json.bool(o, "forceInput", false))
          continue;
        String name = prefix + e.getKey();
        if (DYNAMIC_COMBO.equals(t))
        {
          List<Map<String, Object>> options = Json.arrOfObj(o.get("options"));
          Object v = at[0] < values.size() ? values.get(at[0]++)
              : options.isEmpty() ? null : options.get(0).get("key");
          out.put(name, v);
          for (Map<String, Object> opt : options)
            if (same(opt.get("key"), v))
              section(Json.obj(opt.get("inputs")), name + ".", values, at, out);
        }
        else if (isWidget(t, o))
        {
          Object v = at[0] < values.size() ? values.get(at[0]++) : defaultOf(t, o);
          out.put(name, v);
          if (Json.bool(o, "control_after_generate", false) && at[0] < values.size()
              && values.get(at[0]) instanceof String c && CONTROL.contains(c))
            at[0]++;
          if (Json.bool(o, "image_upload", false) || Json.bool(o, "video_upload", false)
              || Json.bool(o, "audio_upload", false))
            while (at[0] < values.size() && (values.get(at[0]) instanceof Map || values.get(at[0]) == null
                || values.get(at[0]) instanceof String u && List.of("image", "upload", "video", "audio").contains(u)))
              at[0]++;
        }
      }
  }

  private static Object defaultOf(Object t, Map<String, Object> o)
  {
    if (o.containsKey("default"))
      return o.get("default");
    if (t instanceof List<?> l)
      return l.isEmpty() ? "" : l.get(0);
    List<Object> options = Json.arr(o.get("options"));
    if (!options.isEmpty())
      return options.get(0);
    return switch (String.valueOf(t))
    {
      case "INT" -> o.get("min") instanceof Number n ? n.longValue() : 0L;
      case "FLOAT" -> o.get("min") instanceof Number n ? n.doubleValue() : 0.0;
      case "BOOLEAN" -> Boolean.FALSE;
      default -> "";
    };
  }

  /** Does each value fit what the definition allows? Values that came over a link are not looked at. */
  private void check(Map<String, Object> def, Map<String, Object> inputs, List<String> trouble, String type)
  {
    Map<String, List<Object>> widgets = new LinkedHashMap<>();
    Set<String> requiredSockets = new LinkedHashSet<>();
    collectSpecs(def, "", inputs, widgets, requiredSockets);
    for (String name : requiredSockets)
      if (!inputs.containsKey(name))
        trouble.add("nothing is connected to '" + name + "'");
    for (Map.Entry<String, List<Object>> e : widgets.entrySet())
    {
      String name = e.getKey();
      Object v = inputs.get(name);
      if (v == null || v instanceof List)
        continue;
      List<Object> spec = e.getValue();
      Object t = spec.get(0);
      Map<String, Object> o = spec.size() > 1 ? Json.obj(spec.get(1)) : Map.of();
      List<Object> options = t instanceof List<?> l ? Json.arr(l)
          : "COMBO".equals(t) || DYNAMIC_COMBO.equals(t) ? comboKeys(o) : null;
      if (options != null)
      {
        if (Json.bool(o, "image_upload", false) || Json.bool(o, "video_upload", false)
            || Json.bool(o, "audio_upload", false) || Json.bool(o, "multiselect", false) && v instanceof List)
          continue;
        if (v instanceof String s && RecipeDrafterAccess.isFile(s))
          continue; // a model file: the recipe finds the right one
        boolean ok = false;
        for (Object opt : options)
          ok |= same(opt, v);
        if (!ok)
          trouble.add("'" + name + "' is " + Json.write(v) + ", which this ComfyUI does not offer");
      }
      else if ("INT".equals(t) || "FLOAT".equals(t))
      {
        if (!(v instanceof Number))
          trouble.add("'" + name + "' should be a number but is " + Json.write(v));
      }
      else if ("BOOLEAN".equals(t))
      {
        if (!(v instanceof Boolean))
          trouble.add("'" + name + "' should be true or false but is " + Json.write(v));
      }
      else if ("STRING".equals(t) && !(v instanceof String))
        trouble.add("'" + name + "' should be text but is " + Json.write(v));
    }
  }

  /** The widget inputs in force (dynamic combos followed into their chosen option), and the required sockets. */
  private void collectSpecs(Map<String, Object> def, String prefix, Map<String, Object> inputs,
      Map<String, List<Object>> widgets, Set<String> requiredSockets)
  {
    for (String sec : new String[] { "required", "optional" })
      for (Map.Entry<String, Object> e : Json.obj(def.get(sec)).entrySet())
      {
        List<Object> spec = Json.arr(e.getValue());
        if (spec.isEmpty())
          continue;
        String name = prefix + e.getKey();
        Object t = spec.get(0);
        Map<String, Object> o = spec.size() > 1 ? Json.obj(spec.get(1)) : Map.of();
        boolean widget = isWidget(t, o);
        if (widget)
          widgets.put(name, spec);
        else if ("required".equals(sec) && !(t instanceof String ts && ts.startsWith("COMFY_")))
          requiredSockets.add(name);
        if (DYNAMIC_COMBO.equals(t))
          for (Map<String, Object> opt : Json.arrOfObj(o.get("options")))
            if (same(opt.get("key"), inputs.get(name)))
              collectSpecs(Json.obj(opt.get("inputs")), name + ".", inputs, widgets, requiredSockets);
      }
  }

  private static List<Object> comboKeys(Map<String, Object> o)
  {
    List<Object> out = new ArrayList<>();
    for (Object opt : Json.arr(o.get("options")))
      out.add(opt instanceof Map<?, ?> m ? Json.obj(m).get("key") : opt);
    return out;
  }

  private static boolean same(Object a, Object b)
  {
    if (a instanceof Number x && b instanceof Number y)
      return x.doubleValue() == y.doubleValue();
    return a != null && a.equals(b);
  }

  // ---- choosing the result -------------------------------------------------------------------

  private String pickOutput(String preferred)
  {
    String best = null;
    int bestRank = -1;
    for (Map.Entry<String, Object> e : graph.entrySet())
    {
      String cls = Json.str(Json.obj(e.getValue()), "class_type", "");
      if (cls.contains("Preview") || cls.contains("Compare") || !Json.bool(Json.obj(info.get(cls)), "output_node", false))
        continue;
      int rank = RecipeDrafterAccess.rank(RecipeDrafterAccess.kindOf(cls));
      boolean isPreferred = e.getKey().equals(preferred);
      if (rank > bestRank || rank == bestRank && isPreferred)
      {
        best = e.getKey();
        bestRank = rank;
      }
    }
    return best;
  }

  private static Set<String> ancestors(Map<String, Object> g, String from)
  {
    Set<String> keep = new LinkedHashSet<>();
    Deque<String> todo = new ArrayDeque<>(List.of(from));
    while (!todo.isEmpty())
    {
      String id = todo.pop();
      if (!keep.add(id))
        continue;
      for (Object v : Json.obj(Json.obj(g.get(id)).get("inputs")).values())
        if (v instanceof List<?> l && l.size() == 2 && l.get(0) instanceof String src && g.containsKey(src))
          todo.push(src);
    }
    return keep;
  }

  private static long num(Object o)
  {
    return o instanceof Number n ? n.longValue() : 0;
  }

  /** The bits of RecipeDrafter this class shares, so the two judge files and outputs alike. */
  static final class RecipeDrafterAccess
  {
    static boolean isFile(String s)
    {
      String low = s.toLowerCase(Locale.ROOT);
      return low.endsWith(".safetensors") || low.endsWith(".gguf") || low.endsWith(".ckpt") || low.endsWith(".pt")
          || low.endsWith(".pth") || low.endsWith(".bin") || low.endsWith(".sft") || low.endsWith(".onnx");
    }

    static String kindOf(String cls)
    {
      String c = cls.toLowerCase(Locale.ROOT);
      if (c.contains("glb") || c.contains("mesh") || c.contains("3d") || c.contains("obj") || c.contains("gltf"))
        return "3d";
      if (c.contains("video") || c.contains("webm") || c.contains("animated"))
        return "video";
      if (c.contains("audio"))
        return "audio";
      return "image";
    }

    static int rank(String kind)
    {
      return switch (kind)
      {
        case "3d" -> 3;
        case "video" -> 2;
        case "image" -> 1;
        default -> 0;
      };
    }
  }
}
