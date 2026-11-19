import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import ing.aether.hemera.json.Json;

/**
 * /v1/motion/generations, against a real hemera and a fake motion engine (fake_motion_engine.py):
 * hemera picks the model, starts its engine, passes requests and answers through unchanged, keeps
 * the engine from being stopped while it makes a clip, and never logs the prompt. Run it with
 * tests/motion/run.sh.
 */
public class MotionApiTest
{
  private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
      .connectTimeout(Duration.ofSeconds(5)).build();
  private static final String PROMPT = "a zebra-crossing pirouette 4711";
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
      send("POST", "/hemera/api/loglevel", "{\"level\":\"trace\"}"); // the prompt must stay out of even a trace
      listed();
      notMotion();
      passThrough();
      errors();
      loading();
      busy();
      stopped();
      String log = Files.readString(dir.resolve("hemera.log"));
      check("the prompt is not in hemera's log, even at trace level", !log.contains(PROMPT) && !log.contains("pirouette"),
          "see " + dir.resolve("hemera.log"));
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

  private static void listed() throws Exception
  {
    HttpResponse<String> r = send("GET", "/v1/models", null);
    Map<String, Object> found = null;
    for (Object o : Json.arr(Json.parseObj(r.body()).get("data")))
      if ("motion-fast".equals(Json.obj(o).get("id")))
        found = Json.obj(Json.obj(o).get("hemera"));
    check("/v1/models: a motion model is kind motion, at /v1/motion/generations", found != null
        && "motion".equals(found.get("kind")) && "/v1/motion/generations".equals(found.get("endpoint")), r.body());
  }

  private static void notMotion() throws Exception
  {
    HttpResponse<String> r = send("POST", "/v1/chat/completions", "{\"model\":\"motion-fast\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
    check("chat with a motion model: 400 pointing to /v1/motion/generations", r.statusCode() == 400
        && r.body().contains("/v1/motion/generations"), r.statusCode() + " " + r.body());
    r = send("POST", "/v1/motion/generations", "{\"model\":\"chat\",\"prompt\":\"waves\"}");
    check("a text model asked for motion: 400", r.statusCode() == 400 && r.body().contains("does not make motion"), r.body());
    r = send("POST", "/v1/motion/generations", "{\"model\":\"nobody\",\"prompt\":\"waves\"}");
    check("an unknown model: 404", r.statusCode() == 404, r.statusCode() + " " + r.body());
    r = send("POST", "/v1/motion/generations", "{not json");
    check("a body that is not JSON: 400", r.statusCode() == 400, r.statusCode() + " " + r.body());
  }

  private static void passThrough() throws Exception
  {
    String body = "{\"model\":\"motion-fast\",\"prompt\":\"" + PROMPT + "\",\"duration\":3,\"fps\":30,\"seed\":5,\"loop\":false}";
    HttpResponse<String> r = ready(body);
    Map<String, Object> m = Json.parseObj(r.body());
    String id = String.valueOf(m.get("id"));
    check("POST: 200 with the engine's id and status", r.statusCode() == 200 && id.startsWith("motion_")
        && m.get("status") != null, r.body());
    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
    check("POST: the body reached the engine unchanged", Files.readString(dir.resolve("fast.log")).contains("body-sha256 " + sha),
        Files.readString(dir.resolve("fast.log")));
    r = send("GET", "/v1/motion/generations/" + id, null);
    check("GET {id}: the engine's status passes through", r.statusCode() == 200
        && id.equals(Json.str(Json.parseObj(r.body()), "id", "")), r.body());
    HttpResponse<String> early = send("GET", "/v1/motion/generations/" + id + "/content?format=glb", null);
    check("GET content before it is made: the engine's 409", early.statusCode() == 409, early.statusCode() + " " + early.body());
    Thread.sleep(800);
    HttpResponse<byte[]> glb = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/v1/motion/generations/" + id
        + "/content?format=glb")).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    check("GET content?format=glb: the bytes, type and file name pass through", glb.statusCode() == 200
        && new String(glb.body(), 0, 4, StandardCharsets.ISO_8859_1).equals("glTF")
        && "model/gltf-binary".equals(glb.headers().firstValue("Content-Type").orElse(""))
        && glb.headers().firstValue("Content-Disposition").orElse("").contains(id + ".glb"), glb.statusCode() + " " + glb.headers());
    r = send("GET", "/v1/motion/generations/" + id + "/content?format=fbx", null);
    check("an unknown format: the engine's 400 passes through", r.statusCode() == 400 && r.body().contains("json, glb or bvh"),
        r.body());
    r = send("GET", "/v1/motion/generations", null);
    check("GET the list: the engine's clips", r.statusCode() == 200 && r.body().contains(id), r.body());
    r = send("POST", "/v1/motion/generations", "{\"prompt\":\"sits down\"}");
    check("POST without a model: the configured motionModel", r.statusCode() == 200
        && Files.readString(dir.resolve("fast.log")).split("POST ").length == 3, r.statusCode() + " " + r.body());
    r = send("DELETE", "/v1/motion/generations/" + id, null);
    check("DELETE: the engine drops it", r.statusCode() == 200 && r.body().contains("\"deleted\""), r.body());
    r = send("GET", "/v1/motion/generations/" + id, null);
    check("GET after DELETE: 404", r.statusCode() == 404, r.statusCode() + " " + r.body());
  }

  private static void errors() throws Exception
  {
    HttpResponse<String> r = send("POST", "/v1/motion/generations", "{\"model\":\"motion-fast\"}");
    check("POST without a prompt: the engine's 400 and message pass through", r.statusCode() == 400
        && Json.str(Json.parseObj(r.body()), "error", "").contains("'prompt' is missing"), r.body());
    r = send("GET", "/v1/motion/generations/motion_nothere", null);
    check("an unknown id: 404", r.statusCode() == 404, r.statusCode() + " " + r.body());
    r = send("POST", "/v1/motion/generations", "{\"model\":\"motion-fast\",\"prompt\":\"" + "x".repeat(70_000) + "\"}");
    check("a body over 64 KB: 413", r.statusCode() == 413, r.statusCode() + "");
  }

  private static void loading() throws Exception
  {
    HttpResponse<String> r = send("POST", "/v1/motion/generations", "{\"model\":\"motion-lazy\",\"prompt\":\"waves\"}");
    check("while the engine starts: 503 with Retry-After", r.statusCode() == 503
        && r.headers().firstValue("Retry-After").isPresent(), r.statusCode() + " " + r.body());
    r = ready("{\"model\":\"motion-lazy\",\"prompt\":\"waves\"}");
    check("once it is up: 200", r.statusCode() == 200, r.statusCode() + " " + r.body());
  }

  private static void busy() throws Exception
  {
    HttpResponse<String> r = ready("{\"model\":\"motion-slow\",\"prompt\":\"a slow twirl\"}");
    String id = Json.str(Json.parseObj(r.body()), "id", "");
    Thread.sleep(17_000); // the reaper looks every 15 s; this engine's keep-alive is 1 s
    r = send("GET", "/v1/motion/generations/" + id, null);
    check("a clip being made keeps its engine from being stopped by the keep-alive clock", r.statusCode() == 200,
        r.statusCode() + " " + r.body());
  }

  private static void stopped() throws Exception
  {
    HttpResponse<String> r = ready("{\"model\":\"motion-fast\",\"prompt\":\"waves\"}");
    String id = Json.str(Json.parseObj(r.body()), "id", "");
    r = send("POST", "/hemera/api/models/stop", "{\"name\":\"motion-fast\"}");
    check("POST /hemera/api/models/stop stops the motion model", r.statusCode() == 200 && r.body().contains("true"), r.body());
    r = send("GET", "/v1/motion/generations/" + id, null);
    check("after a stop its clips are gone: 404", r.statusCode() == 404, r.statusCode() + " " + r.body());
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private static String config(Path engine)
  {
    String python = "python3";
    for (String d : System.getenv().getOrDefault("PATH", "").split(":"))
      if (!d.isEmpty() && Files.isExecutable(Path.of(d, "python3")))
      {
        python = Path.of(d, "python3").toString();
        break;
      }
    List<Object> models = List.of(
        Json.map("name", "motion-fast", "engine", "fake-motion", "task", "motion", "keepAliveSeconds", 300,
            "args", List.of("--job-seconds", "0.5", "--log", dir.resolve("fast.log").toString())),
        Json.map("name", "motion-lazy", "engine", "fake-motion", "task", "motion", "keepAliveSeconds", 300,
            "args", List.of("--start-seconds", "3")),
        Json.map("name", "motion-slow", "engine", "fake-motion", "task", "motion", "keepAliveSeconds", 1,
            "args", List.of("--job-seconds", "30")),
        Json.map("name", "chat", "engine", "llama.cpp", "file", "/nonexistent/chat.gguf"));
    return Json.write(Json.map("port", port, "motionModel", "motion-fast", "motionLoadWaitSeconds", 1,
        "engines", Json.map("fake-motion", Json.map("binaryPath", python, "argStyle", "custom", "healthPath", "/health",
            "gpu", false, "defaultArgs", List.of(engine.toString(), "--port", "{port}"))),
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

  /** A POST that waits out the engine's start: 503s are retried for a while. */
  private static HttpResponse<String> ready(String body) throws Exception
  {
    HttpResponse<String> r = null;
    for (int i = 0; i < 60; i++)
    {
      r = send("POST", "/v1/motion/generations", body);
      if (r.statusCode() != 503)
        return r;
      Thread.sleep(250);
    }
    return r;
  }

  private static HttpResponse<String> send(String method, String path, String body) throws Exception
  {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30));
    b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (body != null)
      b.header("Content-Type", "application/json");
    return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
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
      System.out.println("FAIL  " + what + "\n      " + (detail.length() > 600 ? detail.substring(0, 600) + "…" : detail));
    }
  }
}
