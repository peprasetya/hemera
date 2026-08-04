package ing.aether.hemera;

/**
 * Minimal stdout logger. No timestamps: journalctl adds them.
 * Levels: info (default) &lt; debug (engine output, flow detail) &lt; trace (http bodies).
 */
public final class Log
{
  public static final int INFO = 0, DEBUG = 1, TRACE = 2;

  private static volatile int level = INFO;

  private Log()
  {
  }

  public static void setLevel(String name)
  {
    level = switch (name == null ? "info" : name.toLowerCase())
    {
      case "trace" -> TRACE;
      case "debug" -> DEBUG;
      default -> INFO;
    };
  }

  public static String levelName()
  {
    return switch (level)
    {
      case TRACE -> "trace";
      case DEBUG -> "debug";
      default -> "info";
    };
  }

  public static boolean debugOn()
  {
    return level >= DEBUG;
  }

  public static boolean traceOn()
  {
    return level >= TRACE;
  }

  public static void info(String comp, String msg)
  {
    out("INFO ", comp, msg);
  }

  public static void warn(String comp, String msg)
  {
    out("WARN ", comp, msg);
  }

  public static void error(String comp, String msg, Throwable t)
  {
    out("ERROR", comp, t == null ? msg : msg + ": " + t);
    if (t != null && level >= DEBUG)
      t.printStackTrace(System.out);
  }

  public static void debug(String comp, String msg)
  {
    if (level >= DEBUG)
      out("DEBUG", comp, msg);
  }

  public static void trace(String comp, String msg)
  {
    if (level >= TRACE)
      out("TRACE", comp, msg);
  }

  private static void out(String lvl, String comp, String msg)
  {
    System.out.println(lvl + " [" + comp + "] " + msg);
  }
}
