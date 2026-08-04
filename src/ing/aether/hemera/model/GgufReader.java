package ing.aether.hemera.model;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ing.aether.hemera.Log;

/**
 * Reads only the GGUF header + metadata KV section (never tensor data).
 * Large arrays (e.g. tokenizer vocab) are skipped, not stored.
 * Results are cached by (path, mtime).
 */
public final class GgufReader
{
  private record Cached(long mtime, Map<String, Object> meta)
  {
  }

  private static final ConcurrentHashMap<String, Cached> CACHE = new ConcurrentHashMap<>();

  public static Map<String, Object> read(Path p)
  {
    try
    {
      long mtime = Files.getLastModifiedTime(p).toMillis();
      Cached c = CACHE.get(p.toString());
      if (c != null && c.mtime == mtime)
        return c.meta;
      Map<String, Object> meta = readMeta(p);
      CACHE.put(p.toString(), new Cached(mtime, meta));
      return meta;
    }
    catch (Exception e)
    {
      Log.debug("gguf", "cannot read " + p + ": " + e);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("error", String.valueOf(e.getMessage()));
      return m;
    }
  }

  private static Map<String, Object> readMeta(Path p) throws IOException
  {
    try (InputStream raw = Files.newInputStream(p); BufferedInputStream in = new BufferedInputStream(raw, 1 << 16))
    {
      byte[] magic = readN(in, 4);
      if (!(magic[0] == 'G' && magic[1] == 'G' && magic[2] == 'U' && magic[3] == 'F'))
        throw new IOException("not a GGUF file");
      long version = u32(in);
      if (version < 2 || version > 3)
        throw new IOException("unsupported GGUF version " + version);
      long tensorCount = u64(in);
      long kvCount = u64(in);
      if (kvCount < 0 || kvCount > 100_000)
        throw new IOException("implausible kv count " + kvCount);

      Map<String, Object> meta = new LinkedHashMap<>();
      meta.put("gguf.version", version);
      meta.put("gguf.tensor_count", tensorCount);
      for (long i = 0; i < kvCount; i++)
      {
        String key = string(in);
        int type = (int) u32(in);
        Object val = value(in, type);
        // tokenizer payloads are huge and useless for configuration purposes
        if (val != SKIPPED && !key.startsWith("tokenizer."))
          meta.put(key, val);
      }
      derive(meta);
      return meta;
    }
  }

  private static void derive(Map<String, Object> meta)
  {
    Object arch = meta.get("general.architecture");
    if (arch instanceof String a)
    {
      meta.put("hemera.arch", a);
      Object ctx = meta.get(a + ".context_length");
      if (ctx instanceof Number n)
        meta.put("hemera.context", n.longValue());
    }
    Object name = meta.get("general.name");
    if (name instanceof String s && !s.isBlank())
      meta.put("hemera.name", s);
    Object ft = meta.get("general.file_type");
    if (ft instanceof Number n)
      meta.put("hemera.quant", quantName(n.intValue()));
    Object pc = meta.get("general.parameter_count");
    if (pc instanceof Number n)
      meta.put("hemera.params", humanParams(n.longValue()));
    else if (meta.get("general.size_label") instanceof String sl)
      meta.put("hemera.params", sl);
  }

  public static String humanParams(long count)
  {
    if (count >= 1_000_000_000L)
      return String.format("%.1fB", count / 1e9);
    if (count >= 1_000_000L)
      return String.format("%.0fM", count / 1e6);
    return String.valueOf(count);
  }

  private static String quantName(int fileType)
  {
    return switch (fileType)
    {
      case 0 -> "F32"; case 1 -> "F16"; case 2 -> "Q4_0"; case 3 -> "Q4_1";
      case 7 -> "Q8_0"; case 8 -> "Q5_0"; case 9 -> "Q5_1"; case 10 -> "Q2_K";
      case 11 -> "Q3_K_S"; case 12 -> "Q3_K_M"; case 13 -> "Q3_K_L";
      case 14 -> "Q4_K_S"; case 15 -> "Q4_K_M"; case 16 -> "Q5_K_S"; case 17 -> "Q5_K_M";
      case 18 -> "Q6_K"; case 19 -> "IQ2_XXS"; case 20 -> "IQ2_XS"; case 21 -> "Q2_K_S";
      case 22 -> "IQ3_XS"; case 23 -> "IQ3_XXS"; case 24 -> "IQ1_S"; case 25 -> "IQ4_NL";
      case 26 -> "IQ3_S"; case 27 -> "IQ3_M"; case 28 -> "IQ2_S"; case 29 -> "IQ2_M";
      case 30 -> "IQ4_XS"; case 31 -> "IQ1_M"; case 32 -> "BF16";
      case 36 -> "TQ1_0"; case 37 -> "TQ2_0"; case 38 -> "MXFP4";
      default -> "type-" + fileType;
    };
  }

  // ---- GGUF value decoding ------------------------------------------------

  private static final Object SKIPPED = new Object();

  private static Object value(InputStream in, int type) throws IOException
  {
    switch (type)
    {
      case 0: return (long) readN(in, 1)[0] & 0xFF;          // uint8
      case 1: return (long) readN(in, 1)[0];                 // int8
      case 2: return u16(in);                                // uint16
      case 3: return (long) (short) u16(in);                 // int16
      case 4: return u32(in);                                // uint32
      case 5: return (long) (int) u32(in);                   // int32
      case 6: return (double) Float.intBitsToFloat((int) u32(in));
      case 7: return readN(in, 1)[0] != 0;                   // bool
      case 8: return string(in);
      case 9: return array(in);
      case 10: return u64(in);                               // uint64 (may wrap; fine for metadata)
      case 11: return u64(in);                               // int64
      case 12: return Double.longBitsToDouble(u64(in));
      default: throw new IOException("unknown GGUF value type " + type);
    }
  }

  private static Object array(InputStream in) throws IOException
  {
    int elemType = (int) u32(in);
    long count = u64(in);
    int fixed = switch (elemType)
    {
      case 0, 1, 7 -> 1;
      case 2, 3 -> 2;
      case 4, 5, 6 -> 4;
      case 10, 11, 12 -> 8;
      default -> 0; // string / nested array: variable size
    };
    if (count > 48)
    {
      // skip without storing
      if (fixed > 0)
        skipN(in, count * fixed);
      else
        for (long i = 0; i < count; i++)
          if (elemType == 8)
            skipN(in, u64(in));
          else
            value(in, elemType);
      return SKIPPED;
    }
    List<Object> out = new ArrayList<>();
    for (long i = 0; i < count; i++)
    {
      Object v = value(in, elemType);
      out.add(v == SKIPPED ? null : v);
    }
    return out;
  }

  private static String string(InputStream in) throws IOException
  {
    long len = u64(in);
    if (len < 0 || len > 10_000_000)
      throw new IOException("implausible string length " + len);
    return new String(readN(in, (int) len), StandardCharsets.UTF_8);
  }

  private static byte[] readN(InputStream in, int n) throws IOException
  {
    byte[] b = in.readNBytes(n);
    if (b.length != n)
      throw new EOFException("truncated GGUF");
    return b;
  }

  private static void skipN(InputStream in, long n) throws IOException
  {
    long left = n;
    while (left > 0)
    {
      long s = in.skip(left);
      if (s <= 0)
      {
        if (in.read() < 0)
          throw new EOFException("truncated GGUF");
        s = 1;
      }
      left -= s;
    }
  }

  private static long u16(InputStream in) throws IOException
  {
    byte[] b = readN(in, 2);
    return (b[0] & 0xFFL) | (b[1] & 0xFFL) << 8;
  }

  private static long u32(InputStream in) throws IOException
  {
    byte[] b = readN(in, 4);
    return (b[0] & 0xFFL) | (b[1] & 0xFFL) << 8 | (b[2] & 0xFFL) << 16 | (b[3] & 0xFFL) << 24;
  }

  private static long u64(InputStream in) throws IOException
  {
    byte[] b = readN(in, 8);
    long v = 0;
    for (int i = 7; i >= 0; i--)
      v = v << 8 | (b[i] & 0xFFL);
    return v;
  }
}
