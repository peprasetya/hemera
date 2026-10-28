package ing.aether.hemera.http;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import com.sun.net.httpserver.HttpExchange;

/**
 * Server side of a WebSocket (RFC 6455) on the JDK's built-in HttpServer.
 *
 * The JDK server has no upgrade support: it treats a GET's body as empty and would frame anything
 * written as HTTP. So the handshake is answered on the connection's raw streams, which sit in the
 * exchange's private fields; the jar's manifest opens the package for that (Add-Opens). The
 * server leaves a connection alone while its exchange is unfinished, so the socket is the
 * WebSocket's until close().
 */
public final class WebSocket implements Closeable
{
  private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
  private static final int MAX_MESSAGE = 16 * 1024 * 1024;

  private final InputStream in;
  private final OutputStream out;
  private volatile boolean closed;

  private WebSocket(InputStream in, OutputStream out)
  {
    this.in = in;
    this.out = out;
  }

  /** True when the request asks to become a WebSocket. */
  public static boolean isUpgrade(HttpExchange ex)
  {
    String up = ex.getRequestHeaders().getFirst("Upgrade");
    return up != null && up.equalsIgnoreCase("websocket");
  }

  /**
   * Answer the handshake and take over the connection. A browser that offers subprotocols drops
   * the connection unless one is chosen, so the first of {@code preferred} it offers is, or else
   * the first it offered.
   */
  public static WebSocket accept(HttpExchange ex, String... preferred) throws IOException
  {
    String key = ex.getRequestHeaders().getFirst("Sec-WebSocket-Key");
    if (key == null || !isUpgrade(ex))
      throw new HttpError(400, "expected a WebSocket upgrade request");
    InputStream in;
    OutputStream out;
    try
    {
      Object impl = field(ex, "impl");
      in = (InputStream) field(impl, "ris");
      out = (OutputStream) field(impl, "ros");
    }
    catch (ReflectiveOperationException | RuntimeException e)
    {
      throw new HttpError(501, "WebSocket is unavailable: start hemera with java -jar hemera.jar"
          + " (its manifest opens jdk.httpserver), or add --add-opens"
          + " jdk.httpserver/sun.net.httpserver=ALL-UNNAMED (" + e + ")");
    }
    String accept;
    try
    {
      accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
          .digest((key.trim() + GUID).getBytes(StandardCharsets.US_ASCII)));
    }
    catch (java.security.NoSuchAlgorithmException e)
    {
      throw new IOException(e);
    }
    StringBuilder resp = new StringBuilder("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
        + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n");
    String protocol = chooseProtocol(ex.getRequestHeaders().getFirst("Sec-WebSocket-Protocol"), preferred);
    if (protocol != null)
      resp.append("Sec-WebSocket-Protocol: ").append(protocol).append("\r\n");
    out.write(resp.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
    out.flush();
    return new WebSocket(in, out);
  }

  private static String chooseProtocol(String offered, String[] preferred)
  {
    if (offered == null || offered.isBlank())
      return null;
    String[] list = offered.split(",");
    for (String p : preferred)
      for (String o : list)
        if (o.trim().equals(p))
          return p;
    return list[0].trim();
  }

  private static Object field(Object o, String name) throws ReflectiveOperationException
  {
    for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass())
    {
      try
      {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
      }
      catch (NoSuchFieldException ignored)
      {
      }
    }
    throw new NoSuchFieldException(name);
  }

  /** A whole message: text or binary. */
  public record Message(boolean text, byte[] data)
  {
    public String string()
    {
      return new String(data, StandardCharsets.UTF_8);
    }
  }

  /**
   * The next text or binary message, reassembled from its fragments; pings are answered on the
   * way. Null when the peer closed the connection.
   */
  public Message read() throws IOException
  {
    ByteArrayOutputStream message = new ByteArrayOutputStream();
    int messageOp = -1;
    while (true)
    {
      int b0 = in.read();
      int b1 = in.read();
      if (b0 < 0 || b1 < 0)
        return null;
      boolean fin = (b0 & 0x80) != 0;
      int op = b0 & 0x0f;
      long len = b1 & 0x7f;
      if (len == 126)
      {
        byte[] l = readFully(2);
        len = (l[0] & 0xff) << 8 | l[1] & 0xff;
      }
      else if (len == 127)
      {
        byte[] l = readFully(8);
        len = 0;
        for (byte x : l)
          len = len << 8 | (x & 0xff);
      }
      if (len > MAX_MESSAGE || message.size() + len > MAX_MESSAGE)
        throw new IOException("WebSocket message larger than " + MAX_MESSAGE + " bytes");
      byte[] mask = (b1 & 0x80) != 0 ? readFully(4) : null;
      byte[] payload = readFully((int) len);
      if (mask != null)
        for (int i = 0; i < payload.length; i++)
          payload[i] ^= mask[i & 3];
      switch (op)
      {
        case 0x8 -> // close: answer it and stop
        {
          close();
          return null;
        }
        case 0x9 -> send(0xA, payload);
        case 0xA ->
        {
        }
        default ->
        {
          if (op != 0)
            messageOp = op;
          message.write(payload);
          if (fin)
            return new Message(messageOp == 0x1, message.toByteArray());
        }
      }
    }
  }

  private byte[] readFully(int n) throws IOException
  {
    byte[] b = in.readNBytes(n);
    if (b.length < n)
      throw new EOFException("WebSocket connection closed mid-frame");
    return b;
  }

  public void sendText(String text) throws IOException
  {
    send(0x1, text.getBytes(StandardCharsets.UTF_8));
  }

  public void sendBinary(byte[] data) throws IOException
  {
    send(0x2, data);
  }

  /** Frames from several threads must not interleave, so sending is one frame at a time. */
  private synchronized void send(int op, byte[] payload) throws IOException
  {
    if (closed)
      throw new IOException("WebSocket is closed");
    ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 10);
    f.write(0x80 | op);
    if (payload.length < 126)
      f.write(payload.length);
    else if (payload.length < 65536)
    {
      f.write(126);
      f.write(payload.length >>> 8);
      f.write(payload.length & 0xff);
    }
    else
    {
      f.write(127);
      for (int s = 56; s >= 0; s -= 8)
        f.write((int) ((long) payload.length >>> s) & 0xff);
    }
    f.write(payload);
    out.write(f.toByteArray());
    out.flush();
  }

  public boolean isOpen()
  {
    return !closed;
  }

  /** Say goodbye (status 1000) and drop the connection. */
  @Override
  public void close()
  {
    if (closed)
      return;
    try
    {
      send(0x8, new byte[] {0x03, (byte) 0xe8});
    }
    catch (IOException ignored)
    {
    }
    closed = true;
    try
    {
      out.close();
    }
    catch (IOException ignored)
    {
    }
    try
    {
      in.close();
    }
    catch (IOException ignored)
    {
    }
  }
}
