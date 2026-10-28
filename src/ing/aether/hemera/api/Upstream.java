package ing.aether.hemera.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.BiConsumer;

import ing.aether.hemera.Log;
import ing.aether.hemera.http.HttpError;
import ing.aether.hemera.json.Json;

/** HTTP client helpers for talking to local engine instances and remote providers. */
public final class Upstream
{
  private static final HttpClient HC = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.ALWAYS)
      .connectTimeout(Duration.ofSeconds(30))
      .build();

  private static HttpRequest.Builder request(String url, Map<String, Object> body, Map<String, String> headers)
  {
    HttpRequest.Builder b = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofHours(1))
        .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
    if (headers != null)
      headers.forEach(b::header);
    return b;
  }

  /** POST returning the raw body stream (for SSE relays). Throws HttpError on non-2xx. */
  public static InputStream postStream(String url, Map<String, Object> body, Map<String, String> headers)
      throws IOException, InterruptedException
  {
    if (Log.traceOn())
      Log.trace("upstream", "POST " + url + " " + abbrev(Json.write(body)));
    HttpResponse<InputStream> resp = HC.send(request(url, body, headers).build(),
        HttpResponse.BodyHandlers.ofInputStream());
    if (resp.statusCode() / 100 != 2)
    {
      String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
      throw new HttpError(resp.statusCode() >= 500 ? 502 : resp.statusCode(),
          "upstream " + url + " returned " + resp.statusCode() + ": " + abbrev(err));
    }
    return resp.body();
  }

  /** POST returning parsed JSON. Throws HttpError on non-2xx. */
  public static Map<String, Object> postJson(String url, Map<String, Object> body, Map<String, String> headers)
      throws IOException, InterruptedException
  {
    if (Log.traceOn())
      Log.trace("upstream", "POST " + url + " " + abbrev(Json.write(body)));
    HttpResponse<String> resp = HC.send(request(url, body, headers).build(), HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() / 100 != 2)
      throw new HttpError(resp.statusCode() >= 500 ? 502 : resp.statusCode(),
          "upstream " + url + " returned " + resp.statusCode() + ": " + abbrev(resp.body()));
    if (Log.traceOn())
      Log.trace("upstream", "response: " + abbrev(resp.body()));
    return Json.parseObj(resp.body());
  }

  /** GET returning parsed JSON. Throws HttpError on non-2xx. */
  public static Map<String, Object> getJson(String url) throws IOException, InterruptedException
  {
    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofMinutes(2)).GET().build();
    HttpResponse<String> resp = HC.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() / 100 != 2)
      throw new HttpError(resp.statusCode() >= 500 ? 502 : resp.statusCode(),
          "upstream " + url + " returned " + resp.statusCode() + ": " + abbrev(resp.body()));
    return Json.parseObj(resp.body());
  }

  /** POST a pre-encoded body (e.g. multipart) and parse the JSON reply. Throws HttpError on non-2xx. */
  public static Map<String, Object> postBytes(String url, byte[] body, String contentType)
      throws IOException, InterruptedException
  {
    if (Log.traceOn())
      Log.trace("upstream", "POST " + url + " (" + body.length + " bytes, " + contentType + ")");
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", contentType)
        .timeout(Duration.ofHours(1))
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build();
    HttpResponse<String> resp = HC.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() / 100 != 2)
      throw new HttpError(resp.statusCode() >= 500 ? 502 : resp.statusCode(),
          "upstream " + url + " returned " + resp.statusCode() + ": " + abbrev(resp.body()));
    return Json.parseObj(resp.body());
  }

  /** A reply passed on as it came: its bytes and what they are. */
  public record Raw(String contentType, byte[] body)
  {
  }

  /**
   * POST a pre-encoded body and keep the reply as it is, for answers that need not be JSON
   * (SRT, VTT and plain-text transcripts). Throws HttpError on non-2xx.
   */
  public static Raw postBytesRaw(String url, byte[] body, String contentType)
      throws IOException, InterruptedException
  {
    if (Log.traceOn())
      Log.trace("upstream", "POST " + url + " (" + body.length + " bytes, " + contentType + ")");
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", contentType)
        .timeout(Duration.ofHours(1))
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build();
    HttpResponse<byte[]> resp = HC.send(req, HttpResponse.BodyHandlers.ofByteArray());
    if (resp.statusCode() / 100 != 2)
      throw new HttpError(resp.statusCode() >= 500 ? 502 : resp.statusCode(),
          "upstream " + url + " returned " + resp.statusCode() + ": "
              + abbrev(new String(resp.body(), StandardCharsets.UTF_8)));
    return new Raw(resp.headers().firstValue("Content-Type").orElse("application/octet-stream"), resp.body());
  }

  /** POST JSON and keep the reply as it is (audio, for instance). Throws HttpError on non-2xx. */
  public static Raw postJsonRaw(String url, Map<String, Object> body) throws IOException, InterruptedException
  {
    if (Log.traceOn())
      Log.trace("upstream", "POST " + url + " " + abbrev(Json.write(body)));
    HttpResponse<byte[]> resp = HC.send(request(url, body, null).build(), HttpResponse.BodyHandlers.ofByteArray());
    if (resp.statusCode() / 100 != 2)
      throw new HttpError(resp.statusCode() >= 500 ? 502 : resp.statusCode(),
          "upstream " + url + " returned " + resp.statusCode() + ": "
              + abbrev(new String(resp.body(), StandardCharsets.UTF_8)));
    return new Raw(resp.headers().firstValue("Content-Type").orElse("application/octet-stream"), resp.body());
  }

  /**
   * Read a server-sent-event stream and invoke consumer(eventName, data) per data line.
   * eventName is the most recent "event:" line (null for plain OpenAI-style streams).
   */
  public static void forEachSse(InputStream is, BiConsumer<String, String> consumer) throws IOException
  {
    try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)))
    {
      String event = null;
      String line;
      while ((line = r.readLine()) != null)
      {
        if (line.startsWith("event:"))
        {
          event = line.substring(6).trim();
        }
        else if (line.startsWith("data:"))
        {
          String data = line.substring(5).trim();
          if (Log.traceOn())
            Log.trace("upstream", "sse in: " + (event != null ? event + " " : "") + abbrev(data));
          consumer.accept(event, data);
        }
        else if (line.isEmpty())
        {
          event = null;
        }
      }
    }
  }

  private static String abbrev(String s)
  {
    return ing.aether.hemera.http.Router.abbreviate(s, 4000);
  }
}
