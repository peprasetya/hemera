package ing.aether.hemera.api;

import com.sun.net.httpserver.HttpExchange;

import ing.aether.hemera.Log;

/**
 * Live audio before background work. A call, a dictation or a voice being changed cannot wait;
 * a four-hour subtitle job can. While any live session is open, requests the client marked as
 * background ({@code X-Hemera-Priority: background}) are held before they start, and carry on
 * once the last live session ends. One already running finishes first; a job that sends its
 * work in pieces therefore pauses within one piece.
 *
 * Unmarked requests are never held: hemera cannot tell a dictation from a film by looking.
 */
final class LiveGate
{
  static final String PRIORITY_HEADER = "X-Hemera-Priority";

  private int live;

  /** A live session begins; run the returned action when it ends. */
  synchronized Runnable enter(String what)
  {
    live++;
    Log.debug("live", what + " started (" + live + " live)");
    return () -> leave(what);
  }

  private synchronized void leave(String what)
  {
    live--;
    Log.debug("live", what + " ended (" + live + " live)");
    if (live == 0)
      notifyAll();
  }

  static boolean background(HttpExchange ex)
  {
    String p = ex.getRequestHeaders().getFirst(PRIORITY_HEADER);
    return p != null && (p.equalsIgnoreCase("background") || p.equalsIgnoreCase("batch"));
  }

  /** Hold a background request while anything live runs; anything else passes straight through. */
  void awaitTurn(HttpExchange ex, String what) throws InterruptedException
  {
    if (!background(ex))
      return;
    synchronized (this)
    {
      if (live == 0)
        return;
      long since = System.currentTimeMillis();
      Log.info("live", "Holding background " + what + " while " + live + " live session(s) run");
      while (live > 0)
        wait(1000);
      Log.info("live", "Background " + what + " resumes after " + (System.currentTimeMillis() - since) / 1000 + " s");
    }
  }

  synchronized int live()
  {
    return live;
  }
}
