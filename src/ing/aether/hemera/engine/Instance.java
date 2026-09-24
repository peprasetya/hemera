package ing.aether.hemera.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** One running engine process serving one configured model. */
public final class Instance
{
  public final String name;
  public final Process process;
  public final int port;
  public final long startedAt;
  public final String engineDesc; // e.g. "llama.cpp b10254 ubuntu-rocm-7.2-x64"

  public volatile long lastUsed;
  public volatile long keepAliveSec;

  private final ArrayDeque<String> recentOutput = new ArrayDeque<>();

  public Instance(String name, Process process, int port, String engineDesc, long keepAliveSec)
  {
    this.name = name;
    this.process = process;
    this.port = port;
    this.engineDesc = engineDesc;
    this.keepAliveSec = keepAliveSec;
    this.startedAt = System.currentTimeMillis();
    this.lastUsed = startedAt;
  }

  public boolean alive()
  {
    return process.isAlive();
  }

  public void touch()
  {
    lastUsed = System.currentTimeMillis();
  }

  /**
   * Requests currently being served. The keep-alive clock only restarts when a request finishes,
   * so without this an image that takes longer to draw than the model's keep-alive has its engine
   * unloaded under it half way through.
   */
  private final java.util.concurrent.atomic.AtomicInteger busy = new java.util.concurrent.atomic.AtomicInteger();

  public void beginRequest()
  {
    busy.incrementAndGet();
    touch();
  }

  public void endRequest()
  {
    busy.decrementAndGet();
    touch();
  }

  public boolean busy()
  {
    return busy.get() > 0;
  }

  public void addOutput(String line)
  {
    synchronized (recentOutput)
    {
      recentOutput.addLast(line);
      while (recentOutput.size() > 80)
        recentOutput.removeFirst();
    }
  }

  public List<String> recentOutput()
  {
    synchronized (recentOutput)
    {
      return new ArrayList<>(recentOutput);
    }
  }
}
