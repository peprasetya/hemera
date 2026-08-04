package ing.aether.hemera.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser/writer, zero dependencies.
 * Objects -> LinkedHashMap&lt;String,Object&gt;, arrays -> ArrayList&lt;Object&gt;,
 * numbers -> Long (integral) or Double, plus String, Boolean, null.
 */
public final class Json
{
  private final String src;
  private int pos;

  private Json(String src)
  {
    this.src = src;
  }

  public static Object parse(String s)
  {
    Json j = new Json(s);
    j.ws();
    Object v = j.value();
    j.ws();
    if (j.pos < j.src.length())
      throw new IllegalArgumentException("Trailing JSON content at " + j.pos);
    return v;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> parseObj(String s)
  {
    Object v = parse(s);
    if (v instanceof Map)
      return (Map<String, Object>) v;
    throw new IllegalArgumentException("Expected JSON object");
  }

  // ---- parser -------------------------------------------------------------

  private Object value()
  {
    if (pos >= src.length())
      throw err("Unexpected end of JSON");
    char c = src.charAt(pos);
    switch (c)
    {
      case '{': return object();
      case '[': return array();
      case '"': return string();
      case 't': expect("true"); return Boolean.TRUE;
      case 'f': expect("false"); return Boolean.FALSE;
      case 'n': expect("null"); return null;
      default: return number();
    }
  }

  private Map<String, Object> object()
  {
    Map<String, Object> m = new LinkedHashMap<>();
    pos++; // {
    ws();
    if (peek() == '}') { pos++; return m; }
    while (true)
    {
      ws();
      if (peek() != '"')
        throw err("Expected string key");
      String key = string();
      ws();
      if (peek() != ':')
        throw err("Expected ':'");
      pos++;
      ws();
      m.put(key, value());
      ws();
      char c = peek();
      if (c == ',') { pos++; continue; }
      if (c == '}') { pos++; return m; }
      throw err("Expected ',' or '}'");
    }
  }

  private List<Object> array()
  {
    List<Object> l = new ArrayList<>();
    pos++; // [
    ws();
    if (peek() == ']') { pos++; return l; }
    while (true)
    {
      ws();
      l.add(value());
      ws();
      char c = peek();
      if (c == ',') { pos++; continue; }
      if (c == ']') { pos++; return l; }
      throw err("Expected ',' or ']'");
    }
  }

  private String string()
  {
    pos++; // "
    StringBuilder sb = new StringBuilder();
    while (true)
    {
      if (pos >= src.length())
        throw err("Unterminated string");
      char c = src.charAt(pos++);
      if (c == '"')
        return sb.toString();
      if (c == '\\')
      {
        char e = src.charAt(pos++);
        switch (e)
        {
          case '"': sb.append('"'); break;
          case '\\': sb.append('\\'); break;
          case '/': sb.append('/'); break;
          case 'b': sb.append('\b'); break;
          case 'f': sb.append('\f'); break;
          case 'n': sb.append('\n'); break;
          case 'r': sb.append('\r'); break;
          case 't': sb.append('\t'); break;
          case 'u':
            sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
            pos += 4;
            break;
          default: throw err("Bad escape '\\" + e + "'");
        }
      }
      else
        sb.append(c);
    }
  }

  private Object number()
  {
    int start = pos;
    boolean dbl = false;
    while (pos < src.length())
    {
      char c = src.charAt(pos);
      if (c == '-' || c == '+' || (c >= '0' && c <= '9')) { pos++; continue; }
      if (c == '.' || c == 'e' || c == 'E') { dbl = true; pos++; continue; }
      break;
    }
    String s = src.substring(start, pos);
    if (s.isEmpty())
      throw err("Invalid number");
    try
    {
      return dbl ? (Object) Double.parseDouble(s) : (Object) Long.parseLong(s);
    }
    catch (NumberFormatException e)
    {
      return Double.parseDouble(s);
    }
  }

  private void expect(String word)
  {
    if (!src.startsWith(word, pos))
      throw err("Expected '" + word + "'");
    pos += word.length();
  }

  private char peek()
  {
    if (pos >= src.length())
      throw err("Unexpected end of JSON");
    return src.charAt(pos);
  }

  private void ws()
  {
    while (pos < src.length() && Character.isWhitespace(src.charAt(pos)))
      pos++;
  }

  private IllegalArgumentException err(String msg)
  {
    return new IllegalArgumentException(msg + " (offset " + pos + ")");
  }

  // ---- writer -------------------------------------------------------------

  public static String write(Object o)
  {
    StringBuilder sb = new StringBuilder();
    writeValue(sb, o, -1, 0);
    return sb.toString();
  }

  public static String writePretty(Object o)
  {
    StringBuilder sb = new StringBuilder();
    writeValue(sb, o, 2, 0);
    return sb.toString();
  }

  private static void writeValue(StringBuilder sb, Object o, int indent, int depth)
  {
    if (o == null) { sb.append("null"); return; }
    if (o instanceof String s) { writeString(sb, s); return; }
    if (o instanceof Boolean || o instanceof Integer || o instanceof Long) { sb.append(o); return; }
    if (o instanceof Double d)
    {
      if (d.isNaN() || d.isInfinite()) sb.append("null");
      else sb.append(d.doubleValue() == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf(d.longValue()) : d.toString());
      return;
    }
    if (o instanceof Number) { sb.append(o); return; }
    if (o instanceof Map<?, ?> m)
    {
      sb.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : m.entrySet())
      {
        if (!first) sb.append(',');
        first = false;
        nl(sb, indent, depth + 1);
        writeString(sb, String.valueOf(e.getKey()));
        sb.append(':');
        if (indent >= 0) sb.append(' ');
        writeValue(sb, e.getValue(), indent, depth + 1);
      }
      if (!first) nl(sb, indent, depth);
      sb.append('}');
      return;
    }
    if (o instanceof Iterable<?> it)
    {
      sb.append('[');
      boolean first = true;
      for (Object e : it)
      {
        if (!first) sb.append(',');
        first = false;
        nl(sb, indent, depth + 1);
        writeValue(sb, e, indent, depth + 1);
      }
      if (!first) nl(sb, indent, depth);
      sb.append(']');
      return;
    }
    writeString(sb, o.toString());
  }

  private static void nl(StringBuilder sb, int indent, int depth)
  {
    if (indent < 0)
      return;
    sb.append('\n');
    sb.append(" ".repeat(indent * depth));
  }

  private static void writeString(StringBuilder sb, String s)
  {
    sb.append('"');
    for (int i = 0; i < s.length(); i++)
    {
      char c = s.charAt(i);
      switch (c)
      {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\b': sb.append("\\b"); break;
        case '\f': sb.append("\\f"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default:
          if (c < 0x20)
            sb.append(String.format("\\u%04x", (int) c));
          else
            sb.append(c);
      }
    }
    sb.append('"');
  }

  // ---- typed accessors ----------------------------------------------------

  @SuppressWarnings("unchecked")
  public static Map<String, Object> obj(Object o)
  {
    return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
  }

  @SuppressWarnings("unchecked")
  public static List<Object> arr(Object o)
  {
    return o instanceof List ? (List<Object>) o : new ArrayList<>();
  }

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> arrOfObj(Object o)
  {
    List<Map<String, Object>> out = new ArrayList<>();
    if (o instanceof List<?> l)
      for (Object e : l)
        if (e instanceof Map)
          out.add((Map<String, Object>) e);
    return out;
  }

  public static String str(Map<String, Object> m, String key, String def)
  {
    Object v = m == null ? null : m.get(key);
    return v instanceof String s ? s : def;
  }

  public static long num(Map<String, Object> m, String key, long def)
  {
    Object v = m == null ? null : m.get(key);
    return v instanceof Number n ? n.longValue() : def;
  }

  public static double numd(Map<String, Object> m, String key, double def)
  {
    Object v = m == null ? null : m.get(key);
    return v instanceof Number n ? n.doubleValue() : def;
  }

  public static boolean bool(Map<String, Object> m, String key, boolean def)
  {
    Object v = m == null ? null : m.get(key);
    return v instanceof Boolean b ? b : def;
  }

  public static List<String> strList(Map<String, Object> m, String key)
  {
    List<String> out = new ArrayList<>();
    if (m != null)
      for (Object o : arr(m.get(key)))
        if (o instanceof String s)
          out.add(s);
    return out;
  }

  /** Convenience builder: Json.map("a", 1, "b", "x") */
  public static Map<String, Object> map(Object... kv)
  {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2)
      m.put(String.valueOf(kv[i]), kv[i + 1]);
    return m;
  }
}
