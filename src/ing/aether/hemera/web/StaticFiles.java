package ing.aether.hemera.web;

import java.io.InputStream;

import com.sun.net.httpserver.HttpExchange;

import ing.aether.hemera.http.Router;

/** Serves the embedded /configure/ web UI from jar resources (resources/web/*). */
public final class StaticFiles
{
  public StaticFiles(Router router)
  {
    router.add("GET", "/configure", ex -> redirect(ex, "/configure/"));
    router.addPrefix("GET", "/configure/", this::serve);
  }

  public static void redirect(HttpExchange ex, String to) throws Exception
  {
    Router.cors(ex);
    ex.getResponseHeaders().set("Location", to);
    ex.sendResponseHeaders(302, -1);
  }

  private void serve(HttpExchange ex) throws Exception
  {
    String path = ex.getRequestURI().getPath().substring("/configure/".length());
    if (path.isEmpty())
      path = "index.html";
    if (path.contains(".."))
    {
      Router.sendText(ex, 400, "bad path", "text/plain");
      return;
    }
    try (InputStream is = StaticFiles.class.getResourceAsStream("/web/" + path))
    {
      if (is == null)
      {
        Router.sendText(ex, 404, "not found", "text/plain");
        return;
      }
      Router.cors(ex);
      ex.getResponseHeaders().set("Cache-Control", "no-cache");
      Router.sendBytes(ex, 200, is.readAllBytes(), contentType(path));
    }
  }

  private static String contentType(String path)
  {
    if (path.endsWith(".html")) return "text/html; charset=utf-8";
    if (path.endsWith(".css")) return "text/css; charset=utf-8";
    if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
    if (path.endsWith(".svg")) return "image/svg+xml";
    if (path.endsWith(".png")) return "image/png";
    if (path.endsWith(".ico")) return "image/x-icon";
    return "application/octet-stream";
  }
}
