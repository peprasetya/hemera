package ing.aether.hemera.http;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import com.sun.net.httpserver.HttpExchange;

/**
 * Whether the client of a request has hung up, asked while hemera is still working on its answer.
 * The JDK's server has no call for this, so the connection's channel is reached the way
 * {@link WebSocket} reaches its streams (hemera.jar's manifest opens jdk.httpserver). The channel
 * is non-blocking and nobody else reads it while the request is being answered: a read finds
 * nothing while the client waits, and the end of the stream once it has gone.
 */
public final class HangUp
{
  private final SocketChannel chan;
  private boolean spoiled;

  private HangUp(SocketChannel chan)
  {
    this.chan = chan;
  }

  /** Watches the exchange's connection; when it cannot be reached, never reports a hang-up. */
  public static HangUp watch(HttpExchange ex)
  {
    try
    {
      Object impl = field(ex, "impl");
      Object connection = field(impl, "connection");
      Object chan = field(connection, "chan");
      return new HangUp(chan instanceof SocketChannel s && !s.isBlocking() ? s : null);
    }
    catch (ReflectiveOperationException | RuntimeException e)
    {
      return new HangUp(null);
    }
  }

  /** True once the client has closed its side of the connection. */
  public synchronized boolean gone()
  {
    if (chan == null || spoiled)
      return false;
    try
    {
      int n = chan.read(ByteBuffer.allocate(1));
      if (n > 0)
        spoiled = true; // the client is there, and has started its next request
      return n < 0;
    }
    catch (IOException e)
    {
      return true;
    }
  }

  /** A byte of the client's next request was read: the connection must close after this answer. */
  public synchronized boolean spoiled()
  {
    return spoiled;
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
}
