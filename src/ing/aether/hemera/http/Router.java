package ing.aether.hemera.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ing.aether.hemera.Log;
import ing.aether.hemera.json.Json;

/**
 * Tiny routing layer over the JDK's built-in HttpServer.
 * Every request runs on a virtual thread.
 */
public final class Router
{
  @FunctionalInterface
  public interface Handler
  {
    void handle(HttpExchange ex) throws Exception;
  }

  private record Route(String method, String path, boolean prefix, Handler handler)
  {
  }

  private final List<Route> routes = new ArrayList<>();
  private HttpServer server;

  public void add(String method, String path, Handler h)
  {
    routes.add(new Route(method, path, false, h));
  }

  public void addPrefix(String method, String path, Handler h)
  {
    routes.add(new Route(method, path, true, h));
  }

  public void start(int port) throws IOException
  {
    server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 256);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/", this::dispatch);
    server.start();
  }

  public void stop()
  {
    if (server != null)
      server.stop(0);
  }

  private void dispatch(HttpExchange ex)
  {
    String method = ex.getRequestMethod();
    String path = ex.getRequestURI().getPath();
    Log.trace("http", method + " " + ex.getRequestURI());
    try
    {
      if ("OPTIONS".equals(method))
      {
        cors(ex);
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, HEAD, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
        ex.sendResponseHeaders(204, -1);
        ex.close();
        return;
      }
      Route match = null;
      for (Route r : routes)
      {
        if (!r.method.equals(method) && !("HEAD".equals(method) && r.method.equals("GET")))
          continue;
        if (r.prefix ? path.startsWith(r.path) : path.equals(r.path))
        {
          match = r;
          break;
        }
      }
      if (match == null)
      {
        sendJson(ex, 404, Json.map("error", "not found: " + method + " " + path));
        return;
      }
      match.handler.handle(ex);
    }
    catch (HttpError e)
    {
      Log.debug("http", method + " " + path + " -> " + e.status + " " + e.getMessage());
      trySendError(ex, e.status, e.getMessage());
    }
    catch (Exception e)
    {
      Log.error("http", method + " " + path + " failed", e);
      trySendError(ex, 500, String.valueOf(e.getMessage()));
    }
    finally
    {
      ex.close();
    }
  }

  private void trySendError(HttpExchange ex, int status, String msg)
  {
    try
    {
      sendJson(ex, status, Json.map("error", msg));
    }
    catch (Exception ignored)
    {
      // response already started (e.g. mid-stream) — nothing more we can do
    }
  }

  // ---- helpers ------------------------------------------------------------

  public static void cors(HttpExchange ex)
  {
    ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
  }

  public static String readBody(HttpExchange ex) throws IOException
  {
    try (InputStream is = ex.getRequestBody())
    {
      String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
      if (Log.traceOn() && !body.isEmpty())
        Log.trace("http", "request body: " + abbreviate(body, 8000));
      return body;
    }
  }

  /** Raw request bytes, for bodies that are not text (multipart uploads). */
  public static byte[] readBytes(HttpExchange ex) throws IOException
  {
    try (InputStream is = ex.getRequestBody())
    {
      byte[] body = is.readAllBytes();
      if (Log.traceOn())
        Log.trace("http", "request body: " + body.length + " bytes");
      return body;
    }
  }

  public static Map<String, Object> readJson(HttpExchange ex) throws IOException
  {
    String body = readBody(ex);
    if (body.isBlank())
      return new LinkedHashMap<>();
    try
    {
      return Json.parseObj(body);
    }
    catch (IllegalArgumentException e)
    {
      throw new HttpError(400, "invalid JSON body: " + e.getMessage());
    }
  }

  public static void sendJson(HttpExchange ex, int status, Object body) throws IOException
  {
    String json = Json.write(body);
    if (Log.traceOn())
      Log.trace("http", "response " + status + ": " + abbreviate(json, 8000));
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    cors(ex);
    ex.getResponseHeaders().set("Content-Type", "application/json");
    if ("HEAD".equals(ex.getRequestMethod()))
    {
      ex.sendResponseHeaders(status, -1);
      return;
    }
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = ex.getResponseBody())
    {
      os.write(bytes);
    }
  }

  public static void sendText(HttpExchange ex, int status, String text, String contentType) throws IOException
  {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    cors(ex);
    ex.getResponseHeaders().set("Content-Type", contentType);
    if ("HEAD".equals(ex.getRequestMethod()))
    {
      ex.sendResponseHeaders(status, -1);
      return;
    }
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = ex.getResponseBody())
    {
      os.write(bytes);
    }
  }

  public static void sendBytes(HttpExchange ex, int status, byte[] bytes, String contentType) throws IOException
  {
    cors(ex);
    ex.getResponseHeaders().set("Content-Type", contentType);
    if ("HEAD".equals(ex.getRequestMethod()))
    {
      ex.sendResponseHeaders(status, -1);
      return;
    }
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = ex.getResponseBody())
    {
      os.write(bytes);
    }
  }

  /** Begin a chunked streaming response and return the stream to write to. */
  public static OutputStream startStream(HttpExchange ex, String contentType) throws IOException
  {
    cors(ex);
    ex.getResponseHeaders().set("Content-Type", contentType);
    ex.getResponseHeaders().set("Cache-Control", "no-cache");
    ex.sendResponseHeaders(200, 0);
    return ex.getResponseBody();
  }

  public static void sse(OutputStream os, String event, Object data) throws IOException
  {
    StringBuilder sb = new StringBuilder();
    if (event != null)
      sb.append("event: ").append(event).append('\n');
    String payload = data instanceof String s ? s : Json.write(data);
    sb.append("data: ").append(payload).append("\n\n");
    if (Log.traceOn())
      Log.trace("http", "sse out: " + abbreviate(sb.toString().trim(), 2000));
    os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    os.flush();
  }

  public static void ndjson(OutputStream os, Object obj) throws IOException
  {
    String line = Json.write(obj) + "\n";
    if (Log.traceOn())
      Log.trace("http", "ndjson out: " + abbreviate(line.trim(), 2000));
    os.write(line.getBytes(StandardCharsets.UTF_8));
    os.flush();
  }

  public static String query(HttpExchange ex, String key)
  {
    String q = ex.getRequestURI().getRawQuery();
    if (q == null)
      return null;
    for (String part : q.split("&"))
    {
      int i = part.indexOf('=');
      String k = i < 0 ? part : part.substring(0, i);
      if (k.equals(key))
        return i < 0 ? "" : java.net.URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8);
    }
    return null;
  }

  public static String abbreviate(String s, int max)
  {
    return s.length() <= max ? s : s.substring(0, max) + "…(" + s.length() + " chars)";
  }
}
