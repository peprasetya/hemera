package ing.aether.hemera.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Just enough multipart/form-data to read an image-edit upload and write it back out again.
 * Parsing is byte-exact: file parts are never decoded as text, so image bytes survive the
 * round trip to the engine untouched.
 */
public final class Multipart
{
  /** One form part. fileName is null for a plain field, in which case value() is its text. */
  public record Part(String name, String fileName, String contentType, byte[] data)
  {
    public static Part field(String name, String value)
    {
      return new Part(name, null, null, value.getBytes(StandardCharsets.UTF_8));
    }

    public static Part file(String name, String fileName, String contentType, byte[] data)
    {
      return new Part(name, fileName, contentType == null ? "application/octet-stream" : contentType, data);
    }

    public boolean isFile()
    {
      return fileName != null;
    }

    public String value()
    {
      return new String(data, StandardCharsets.UTF_8);
    }
  }

  /** Pull the boundary out of a Content-Type header, or null when this is not multipart. */
  public static String boundary(String contentType)
  {
    if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/form-data"))
      return null;
    for (String piece : contentType.split(";"))
    {
      String p = piece.trim();
      if (p.regionMatches(true, 0, "boundary=", 0, 9))
      {
        String b = p.substring(9).trim();
        if (b.length() > 1 && b.startsWith("\"") && b.endsWith("\""))
          b = b.substring(1, b.length() - 1);
        return b;
      }
    }
    return null;
  }

  public static List<Part> parse(byte[] body, String boundary) throws IOException
  {
    byte[] delim = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
    List<Part> parts = new ArrayList<>();
    int pos = indexOf(body, delim, 0);
    if (pos < 0)
      throw new HttpError(400, "malformed multipart body: opening boundary not found");
    pos += delim.length;
    while (pos < body.length)
    {
      // after a boundary comes either "--" (end of body) or CRLF and then the part
      if (pos + 1 < body.length && body[pos] == '-' && body[pos + 1] == '-')
        break;
      while (pos < body.length && (body[pos] == '\r' || body[pos] == '\n'))
        pos++;
      int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.UTF_8), pos);
      if (headerEnd < 0)
        break;
      String headers = new String(body, pos, headerEnd - pos, StandardCharsets.UTF_8);
      int dataStart = headerEnd + 4;
      int next = indexOf(body, delim, dataStart);
      if (next < 0)
        throw new HttpError(400, "malformed multipart body: closing boundary not found");
      int dataEnd = next;
      // the CRLF before the next boundary belongs to the delimiter, not to the data
      if (dataEnd - 2 >= dataStart && body[dataEnd - 2] == '\r' && body[dataEnd - 1] == '\n')
        dataEnd -= 2;
      byte[] data = new byte[Math.max(0, dataEnd - dataStart)];
      System.arraycopy(body, dataStart, data, 0, data.length);
      parts.add(new Part(header(headers, "name"), header(headers, "filename"),
          headerValue(headers, "content-type"), data));
      pos = next + delim.length;
    }
    return parts;
  }

  public static byte[] write(List<Part> parts, String boundary) throws IOException
  {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (Part p : parts)
    {
      StringBuilder head = new StringBuilder("--").append(boundary).append("\r\n")
          .append("Content-Disposition: form-data; name=\"").append(escape(p.name())).append('"');
      if (p.isFile())
        head.append("; filename=\"").append(escape(p.fileName())).append('"');
      head.append("\r\n");
      if (p.contentType() != null)
        head.append("Content-Type: ").append(p.contentType()).append("\r\n");
      head.append("\r\n");
      out.write(head.toString().getBytes(StandardCharsets.UTF_8));
      out.write(p.data());
      out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
    out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return out.toByteArray();
  }

  public static String randomBoundary()
  {
    return "----hemera" + Long.toHexString(System.nanoTime())
        + Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong());
  }

  private static String escape(String s)
  {
    return s.replace("\"", "%22").replace("\r", "").replace("\n", "");
  }

  /** Value of a Content-Disposition attribute such as name="x" / filename="y". */
  private static String header(String headers, String attr)
  {
    java.util.regex.Matcher m = java.util.regex.Pattern
        .compile(attr + "\\s*=\\s*\"([^\"]*)\"", java.util.regex.Pattern.CASE_INSENSITIVE)
        .matcher(headers);
    return m.find() ? m.group(1) : null;
  }

  private static String headerValue(String headers, String name)
  {
    for (String line : headers.split("\r\n"))
      if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1))
        return line.substring(name.length() + 1).trim();
    return null;
  }

  private static int indexOf(byte[] haystack, byte[] needle, int from)
  {
    outer:
    for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++)
    {
      for (int j = 0; j < needle.length; j++)
        if (haystack[i + j] != needle[j])
          continue outer;
      return i;
    }
    return -1;
  }

  private Multipart()
  {
  }
}
