import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import ing.aether.hemera.json.Json;

/**
 * POST and GET /v1/ocr, against a real hemera and a fake OCR engine (fake_ocr_engine.py): the
 * answer's shape (grapheme clusters, words, polygons, the text in reading order), the errors,
 * and that a client hanging up stops the engine's work. Run it with tests/ocr/run.sh.
 */
public class OcrApiTest
{
  private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5)).build();
  private static String base;
  private static int port;
  private static Path dir;
  private static int failures;
  private static int checks;

  public static void main(String[] args) throws Exception
  {
    dir = Path.of(args[0]).toAbsolutePath();
    Path engine = Path.of(args[1]).toAbsolutePath();
    port = freePort();
    base = "http://127.0.0.1:" + port;
    Files.writeString(dir.resolve("hemera.json"), config(engine));
    Process hemera = startHemera();
    try
    {
      waitUp();
      describe();
      shape();
      granularities();
      requestErrors();
      engineTooLarge();
      loading();
      twoAtOnce();
      timeout();
      hangUp();
    }
    finally
    {
      hemera.descendants().forEach(ProcessHandle::destroy);
      hemera.destroy();
      hemera.waitFor();
    }
    System.out.println(checks + " checks, " + failures + " failed");
    System.exit(failures == 0 ? 0 : 1);
  }

  // ---- the tests ------------------------------------------------------------------------------

  private static void describe() throws Exception
  {
    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/v1/ocr")).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    check("GET /v1/ocr answers 200", r.statusCode() == 200, r.body());
    Map<String, Object> m = Json.parseObj(r.body());
    check("GET: version 1", num(m.get("version")) == 1, r.body());
    check("GET: default_model is ocrModel", "ocr-fast".equals(m.get("default_model")), r.body());
    check("GET: max_pixels", num(m.get("max_pixels")) == 50_000_000, r.body());
    check("GET: max_bytes", num(m.get("max_bytes")) == 52_428_800, r.body());
    List<Object> models = Json.arr(m.get("models"));
    check("GET: every OCR model listed", models.size() == 4, r.body());
    Map<String, Object> fast = Json.obj(models.get(0));
    check("GET: model fields", "ocr-fast".equals(fast.get("name")) && List.of("Latn").equals(fast.get("scripts"))
        && "exact".equals(fast.get("char_boxes")) && "test".equals(fast.get("licence")) && fast.get("ready") instanceof Boolean, r.body());
  }

  private static void shape() throws Exception
  {
    HttpResponse<String> r = ready("{\"languages\":[\"id\",\"en\"],\"granularity\":\"chars\",\"region\":null,"
        + "\"orientation\":\"auto\",\"model\":null}");
    check("POST answers 200", r.statusCode() == 200, r.body());
    Map<String, Object> m = Json.parseObj(r.body());
    check("version 1, model named", num(m.get("version")) == 1 && "ocr-fast".equals(m.get("model")), r.body());
    Map<String, Object> image = Json.obj(m.get("image"));
    check("image size", num(image.get("width")) == 400 && num(image.get("height")) == 300, r.body());
    check("orientation", num(m.get("orientation")) == 0, r.body());
    Map<String, Object> timing = Json.obj(m.get("timing_ms"));
    check("timing_ms: hemera's total and the engine's parts", timing.get("total") instanceof Number && timing.containsKey("detect"),
        r.body());
    String latin = "Café 👍🏽 ok";
    check("text: lines joined by \\n, blocks by \\n\\n", (latin + "\nNIK 3171\n\nمرحبا 15").equals(m.get("text")),
        String.valueOf(m.get("text")));
    List<Object> blocks = Json.arr(m.get("blocks"));
    check("two blocks, each with its polygon", blocks.size() == 2 && Json.arr(Json.obj(blocks.get(0)).get("polygon")).size() == 4,
        r.body());
    Map<String, Object> line = Json.obj(Json.arr(Json.obj(blocks.get(0)).get("lines")).get(0));
    check("line fields", latin.equals(line.get("text")) && "ltr".equals(line.get("direction")) && "Latn".equals(line.get("script"))
        && "en".equals(line.get("language")) && line.get("confidence") instanceof Number && line.get("angle") instanceof Number
        && Json.arr(line.get("polygon")).size() == 4, r.body());
    List<Object> chars = Json.arr(line.get("chars"));
    check("chars: one per grapheme cluster (e + U+0301 is one, a thumb with its skin tone is one)", chars.size() == 9,
        String.valueOf(chars));
    check("chars: a cluster spans its code points", span(chars.get(3), 30, 50) && span(chars.get(5), 60, 80), String.valueOf(chars));
    check("chars_estimated false", Boolean.FALSE.equals(line.get("chars_estimated")), r.body());
    List<Object> words = Json.arr(line.get("words"));
    check("three words", words.size() == 3, String.valueOf(words));
    Map<String, Object> w0 = Json.obj(words.get(0)), w1 = Json.obj(words.get(1)), w2 = Json.obj(words.get(2));
    check("words: text and grapheme indices", "Café".equals(w0.get("text")) && num(w0.get("start")) == 0 && num(w0.get("end")) == 4
        && num(w1.get("start")) == 5 && num(w1.get("end")) == 6 && "ok".equals(w2.get("text")) && num(w2.get("start")) == 7
        && num(w2.get("end")) == 9, String.valueOf(words));
    check("words: confidence from their own characters", Math.abs(dbl(w0.get("confidence")) - 0.6) < 1e-9
        && Math.abs(dbl(w1.get("confidence")) - 1.0) < 1e-9, String.valueOf(words));
    check("words: polygon along the line", polygon(w0.get("polygon"), 0, 0, 50, 0, 50, 10, 0, 10), String.valueOf(w0.get("polygon")));
    Map<String, Object> rtl = Json.obj(Json.arr(Json.obj(blocks.get(1)).get("lines")).get(0));
    List<Object> rw = Json.arr(rtl.get("words"));
    check("right to left: a word's polygon starts at its top-right", "rtl".equals(rtl.get("direction")) && rw.size() == 2
        && polygon(Json.obj(rw.get(1)).get("polygon"), 220, 100, 220, 110, 200, 110, 200, 100), String.valueOf(rw));
    String log = Files.readString(dir.resolve("fast.log"));
    check("the engine gets the languages and orientation", log.contains("\"languages\": [\"id\", \"en\"]") || log.contains(
        "\"languages\":[\"id\",\"en\"]"), log);
  }

  private static void granularities() throws Exception
  {
    Map<String, Object> lines = line0(post(png(), "{\"granularity\":\"lines\"}", null));
    check("granularity lines: no words, no chars", !lines.containsKey("words") && !lines.containsKey("chars"), String.valueOf(lines));
    Map<String, Object> words = line0(post(png(), "{\"granularity\":\"words\"}", null));
    check("granularity words: words, no chars", words.containsKey("words") && !words.containsKey("chars"), String.valueOf(words));
    String asFile = new String(multipart(png(), null), StandardCharsets.ISO_8859_1).replace("name=\"image\"", "name=\"file\"");
    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/v1/ocr"))
        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
        .POST(HttpRequest.BodyPublishers.ofByteArray(asFile.getBytes(StandardCharsets.ISO_8859_1))).build(), HttpResponse.BodyHandlers.ofString());
    check("the picture may come as 'file', as for hemera's other uploads", r.statusCode() == 200, r.statusCode() + " " + r.body());
  }

  private static void requestErrors() throws Exception
  {
    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/v1/ocr")).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
    error("not multipart", r, 400);
    error("no image part", post(null, "{}", null), 400);
    error("not a picture", post("hello, this is text".getBytes(StandardCharsets.UTF_8), null, null), 400);
    error("options not JSON", post(png(), "{languages", null), 400);
    error("languages not a list", post(png(), "{\"languages\":\"en\"}", null), 400);
    error("granularity unknown", post(png(), "{\"granularity\":\"letters\"}", null), 400);
    error("region not positive", post(png(), "{\"region\":[0,0,-5,5]}", null), 400);
    error("orientation not a quarter turn", post(png(), "{\"orientation\":45}", null), 400);
    error("model unknown", post(png(), "{\"model\":\"no-such-model\"}", null), 400);
    // too large: refused on the declared length, before the body is read
    String head = "POST /v1/ocr HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: multipart/form-data; boundary=x\r\n"
        + "Content-Length: 60000000\r\n\r\n";
    try (Socket s = new Socket("127.0.0.1", port))
    {
      s.setSoTimeout(5000);
      s.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
      String answer = readAnswer(s.getInputStream());
      check("too large: 413 that says the limits", answer.startsWith("HTTP/1.1 413") && answer.contains("50 MB")
          && answer.contains("50 million pixels"), answer);
    }
  }

  private static void engineTooLarge() throws Exception
  {
    HttpResponse<String> r = ready("{\"model\":\"ocr-huge\"}");
    error("too many pixels (the engine's 413 passes through)", r, 413);
    check("too many pixels: says the limit", r.body().contains("50000000"), r.body());
  }

  private static void loading() throws Exception
  {
    HttpResponse<String> r = post(png(), "{\"model\":\"ocr-lazy\"}", null);
    error("while the model loads", r, 503);
    check("while the model loads: Retry-After", r.headers().firstValue("Retry-After").isPresent(), r.headers().toString());
    Thread.sleep(3500);
    check("once loaded: 200", ready("{\"model\":\"ocr-lazy\"}").statusCode() == 200, "");
  }

  private static void twoAtOnce() throws Exception
  {
    ready("{\"model\":\"ocr-slow\"}"); // started (and its first reading done)
    CompletableFuture<HttpResponse<String>> slow = postAsync("{\"model\":\"ocr-slow\"}");
    Thread.sleep(300);
    long t0 = System.nanoTime();
    CompletableFuture<HttpResponse<String>> a = postAsync("{}");
    CompletableFuture<HttpResponse<String>> b = postAsync("{}");
    boolean both = a.get().statusCode() == 200 && b.get().statusCode() == 200;
    long ms = (System.nanoTime() - t0) / 1_000_000;
    check("two at once, beside a slow one, without waiting for it", both && ms < 2000, ms + " ms");
    slow.get();
  }

  private static void timeout() throws Exception
  {
    long t0 = System.nanoTime();
    HttpResponse<String> r = post(png(), "{\"model\":\"ocr-slow\"}", null);
    long ms = (System.nanoTime() - t0) / 1_000_000;
    error("longer than ocrTimeoutSeconds", r, 504);
    check("504 after about ocrTimeoutSeconds", ms >= 2500 && ms < 6000, ms + " ms");
    check("504 stops the engine's work too", waitFor("slow.log", "cancelled", 1), "");
  }

  private static void hangUp() throws Exception
  {
    byte[] body = multipart(png(), "{\"model\":\"ocr-slow\"}");
    String head = "POST /v1/ocr HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: multipart/form-data; boundary=" + BOUNDARY
        + "\r\nContent-Length: " + body.length + "\r\n\r\n";
    int before = count("slow.log", "cancelled");
    try (Socket s = new Socket("127.0.0.1", port))
    {
      OutputStream out = s.getOutputStream();
      out.write(head.getBytes(StandardCharsets.US_ASCII));
      out.write(body);
      out.flush();
      Thread.sleep(1000);
    } // the client goes
    check("a client hanging up stops the engine's work", waitFor("slow.log", "cancelled", before + 1), "");
  }

  // ---- helpers --------------------------------------------------------------------------------

  private static final String BOUNDARY = "hemera-ocr-test-boundary";

  private static String config(Path engine)
  {
    String python = "python3";
    for (String d : System.getenv().getOrDefault("PATH", "").split(":"))
      if (!d.isEmpty() && Files.isExecutable(Path.of(d, "python3")))
      {
        python = Path.of(d, "python3").toString();
        break;
      }
    List<Object> models = new ArrayList<>();
    for (String mode : List.of("fast", "slow", "lazy", "huge"))
      models.add(Json.map("name", "ocr-" + mode, "engine", "fake-ocr", "task", "ocr", "scripts", List.of("Latn"),
          "licence", "test", "keepAliveSeconds", 300,
          "args", List.of("--mode", mode, "--log", dir.resolve(mode + ".log").toString())));
    return Json.write(Json.map("port", port, "ocrModel", "ocr-fast", "ocrTimeoutSeconds", 3, "ocrLoadWaitSeconds", 1,
        "engines", Json.map("fake-ocr", Json.map("binaryPath", python, "argStyle", "custom", "healthPath", "/health", "gpu", false,
            "defaultArgs", List.of(engine.toString(), "--port", "{port}"))),
        "models", models));
  }

  private static Process startHemera() throws IOException
  {
    String java = ProcessHandle.current().info().command().orElse("java");
    ProcessBuilder pb = new ProcessBuilder(java, "--add-opens", "jdk.httpserver/sun.net.httpserver=ALL-UNNAMED",
        "-cp", System.getProperty("java.class.path"), "ing.aether.hemera.Hemera", "--home", dir.toString(),
        "--config", dir.resolve("hemera.json").toString(), "--port", String.valueOf(port));
    pb.redirectErrorStream(true);
    pb.redirectOutput(dir.resolve("hemera.log").toFile());
    return pb.start();
  }

  private static void waitUp() throws Exception
  {
    for (int i = 0; i < 100; i++)
    {
      try (Socket s = new Socket("127.0.0.1", port))
      {
        return;
      }
      catch (IOException e)
      {
        Thread.sleep(100);
      }
    }
    throw new IllegalStateException("hemera did not start; see " + dir.resolve("hemera.log"));
  }

  /** A POST that waits out the model's start: 503s are retried for a few seconds. */
  private static HttpResponse<String> ready(String options) throws Exception
  {
    HttpResponse<String> r = null;
    for (int i = 0; i < 40; i++)
    {
      r = post(png(), options, null);
      if (r.statusCode() != 503)
        return r;
      Thread.sleep(250);
    }
    return r;
  }

  private static HttpResponse<String> post(byte[] image, String options, String contentType) throws Exception
  {
    return HTTP.send(request(image, options), HttpResponse.BodyHandlers.ofString());
  }

  private static CompletableFuture<HttpResponse<String>> postAsync(String options) throws IOException
  {
    return HTTP.sendAsync(request(png(), options), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpRequest request(byte[] image, String options) throws IOException
  {
    return HttpRequest.newBuilder(URI.create(base + "/v1/ocr")).timeout(Duration.ofSeconds(30))
        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
        .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(image, options))).build();
  }

  private static byte[] multipart(byte[] image, String options) throws IOException
  {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (image != null)
    {
      out.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"p.png\"\r\n"
          + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
      out.write(image);
      out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
    if (options != null)
      out.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"options\"\r\n\r\n" + options + "\r\n")
          .getBytes(StandardCharsets.UTF_8));
    out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return out.toByteArray();
  }

  /** The PNG signature and a header: the fake engine reads no further. */
  private static byte[] png()
  {
    return new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 1, (byte) 0x90,
        0, 0, 1, 0x2c, 8, 2, 0, 0, 0 };
  }

  private static Map<String, Object> line0(HttpResponse<String> r)
  {
    Map<String, Object> m = Json.parseObj(r.body());
    return Json.obj(Json.arr(Json.obj(Json.arr(m.get("blocks")).get(0)).get("lines")).get(0));
  }

  private static void error(String what, HttpResponse<String> r, int status)
  {
    boolean shaped;
    try
    {
      shaped = Json.parseObj(r.body()).get("error") instanceof String;
    }
    catch (RuntimeException e)
    {
      shaped = false;
    }
    check(what + ": " + status + " with {\"error\": ...}", r.statusCode() == status && shaped, r.statusCode() + " " + r.body());
  }

  private static String readAnswer(InputStream in) throws IOException
  {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    try
    {
      int n;
      while ((n = in.read(buf)) > 0)
      {
        out.write(buf, 0, n);
        if (out.toString(StandardCharsets.UTF_8).contains("}"))
          break;
      }
    }
    catch (IOException ignored)
    {
    }
    return out.toString(StandardCharsets.UTF_8);
  }

  private static int count(String log, String word) throws IOException
  {
    Path p = dir.resolve(log);
    return Files.exists(p) ? (int) Files.readAllLines(p).stream().filter(word::equals).count() : 0;
  }

  private static boolean waitFor(String log, String word, int atLeast) throws Exception
  {
    for (int i = 0; i < 40; i++)
    {
      if (count(log, word) >= atLeast)
        return true;
      Thread.sleep(100);
    }
    return false;
  }

  private static boolean span(Object o, double s, double e)
  {
    List<Object> p = Json.arr(o);
    return p.size() == 2 && dbl(p.get(0)) == s && dbl(p.get(1)) == e;
  }

  private static boolean polygon(Object o, double... xy)
  {
    List<Object> pts = Json.arr(o);
    if (pts.size() != 4)
      return false;
    for (int i = 0; i < 4; i++)
    {
      List<Object> p = Json.arr(pts.get(i));
      if (Math.abs(dbl(p.get(0)) - xy[2 * i]) > 0.05 || Math.abs(dbl(p.get(1)) - xy[2 * i + 1]) > 0.05)
        return false;
    }
    return true;
  }

  private static long num(Object o)
  {
    return o instanceof Number n ? n.longValue() : Long.MIN_VALUE;
  }

  private static double dbl(Object o)
  {
    return o instanceof Number n ? n.doubleValue() : Double.NaN;
  }

  private static int freePort() throws IOException
  {
    try (ServerSocket s = new ServerSocket(0))
    {
      return s.getLocalPort();
    }
  }

  private static void check(String what, boolean ok, String detail)
  {
    checks++;
    if (ok)
      System.out.println("ok    " + what);
    else
    {
      failures++;
      System.out.println("FAIL  " + what + "\n      " + (detail.length() > 600 ? detail.substring(0, 600) + "..." : detail));
    }
  }
}
