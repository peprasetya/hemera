package ing.aether.hemera.engine;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import ing.aether.hemera.Log;
import ing.aether.hemera.config.Config;
import ing.aether.hemera.http.HttpError;
import ing.aether.hemera.json.Json;

/**
 * Lists GitHub releases and installs engine binaries into
 * binary/&lt;engine&gt;/&lt;version&gt;/&lt;variant&gt;/. Old versions are never
 * removed automatically — they are the rollback history.
 */
public final class EngineDownloader
{
  private static final HttpClient HC = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.ALWAYS)
      .connectTimeout(Duration.ofSeconds(20))
      .build();

  /** progress jobs keyed by "engine/version/asset" */
  private static final Map<String, Map<String, Object>> JOBS = new ConcurrentHashMap<>();

  public static List<Map<String, Object>> releases(String repo, int count) throws IOException, InterruptedException
  {
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create("https://api.github.com/repos/" + repo + "/releases?per_page=" + count))
        .header("User-Agent", "hemera")
        .header("Accept", "application/vnd.github+json")
        .timeout(Duration.ofSeconds(30))
        .GET().build();
    HttpResponse<String> resp = HC.send(req, HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200)
      throw new HttpError(502, "GitHub API returned " + resp.statusCode() + " for " + repo);
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object o : Json.arr(Json.parse(resp.body())))
    {
      Map<String, Object> rel = Json.obj(o);
      List<Map<String, Object>> assets = new ArrayList<>();
      for (Map<String, Object> a : Json.arrOfObj(rel.get("assets")))
        assets.add(Json.map(
            "name", Json.str(a, "name", ""),
            "size", Json.num(a, "size", 0),
            "url", Json.str(a, "browser_download_url", "")));
      out.add(Json.map(
          "tag", Json.str(rel, "tag_name", ""),
          "name", Json.str(rel, "name", ""),
          "published", Json.str(rel, "published_at", ""),
          "assets", assets));
    }
    return out;
  }

  /** Kick off an install in a background virtual thread; returns the job id. */
  public static String install(Config cfg, String engine, String tag, String assetName, String url)
  {
    String id = engine + "/" + tag + "/" + assetName;
    Map<String, Object> job = new ConcurrentHashMap<>();
    job.put("engine", engine);
    job.put("version", tag);
    job.put("asset", assetName);
    job.put("status", "starting");
    job.put("done", 0L);
    job.put("total", 0L);
    job.put("startedAt", Instant.now().toString());
    JOBS.put(id, job);
    Thread.ofVirtual().name("install-" + id).start(() -> {
      try
      {
        doInstall(cfg, engine, tag, assetName, url, job);
        job.put("status", "done");
        Log.info("engine", "Installed " + id);
      }
      catch (Exception e)
      {
        job.put("status", "error");
        job.put("error", String.valueOf(e.getMessage()));
        Log.error("engine", "Install failed for " + id, e);
      }
    });
    return id;
  }

  public static Map<String, Object> jobs()
  {
    return new LinkedHashMap<>(JOBS);
  }

  private static void doInstall(Config cfg, String engine, String tag, String assetName, String url,
      Map<String, Object> job) throws Exception
  {
    String variant = variantOf(engine, tag, assetName);
    Path destDir = cfg.home.resolve("binary").resolve(engine).resolve(tag).resolve(variant);
    Files.createDirectories(destDir);

    // download to a temp file first
    Path tmp = Files.createTempFile(destDir, ".download-", ".part");
    try
    {
      job.put("status", "downloading");
      HttpRequest req = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .header("User-Agent", "hemera")
          .timeout(Duration.ofMinutes(60))
          .GET().build();
      HttpResponse<InputStream> resp = HC.send(req, HttpResponse.BodyHandlers.ofInputStream());
      if (resp.statusCode() != 200)
        throw new IOException("download returned HTTP " + resp.statusCode());
      long total = resp.headers().firstValueAsLong("content-length").orElse(0);
      job.put("total", total);
      try (InputStream is = resp.body(); OutputStream os = Files.newOutputStream(tmp))
      {
        byte[] buf = new byte[1 << 16];
        long done = 0;
        int n;
        while ((n = is.read(buf)) > 0)
        {
          os.write(buf, 0, n);
          done += n;
          job.put("done", done);
        }
      }

      job.put("status", "extracting");
      String lower = assetName.toLowerCase();
      try (InputStream is = new BufferedInputStream(Files.newInputStream(tmp), 1 << 16))
      {
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz"))
          Tar.extractTar(new GZIPInputStream(is, 1 << 16), destDir);
        else if (lower.endsWith(".zip"))
          Tar.extractZip(is, destDir);
        else
          throw new IOException("unsupported archive format: " + assetName);
      }
    }
    finally
    {
      Files.deleteIfExists(tmp);
    }

    // locate the server binary inside the extracted tree
    String serverBin = Json.str(cfg.engine(engine), "serverBin", "llama-server");
    Path binDir = findBinDir(destDir, serverBin);
    if (binDir == null)
      throw new IOException("server binary '" + serverBin + "' not found in archive " + assetName);
    // zip archives carry no permission bits — make everything in the bin dir executable
    try (Stream<Path> files = Files.list(binDir))
    {
      files.filter(Files::isRegularFile).forEach(f -> f.toFile().setExecutable(true, false));
    }

    Map<String, Object> entry = Json.map(
        "version", tag,
        "variant", variant,
        "asset", assetName,
        "path", cfg.home.relativize(destDir).toString(),
        "binDir", cfg.home.relativize(binDir).toString(),
        "installedAt", Instant.now().toString());
    cfg.addInstalled(engine, entry);
  }

  private static Path findBinDir(Path root, String serverBin) throws IOException
  {
    try (Stream<Path> walk = Files.walk(root))
    {
      return walk.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().equals(serverBin))
          .map(Path::getParent)
          .findFirst().orElse(null);
    }
  }

  /**
   * llama-b10254-bin-ubuntu-rocm-7.2-x64.tar.gz -> ubuntu-rocm-7.2-x64
   * sd-master-ac45422-bin-Linux-Ubuntu-24.04-x86_64-vulkan.zip -> Linux-Ubuntu-24.04-x86_64-vulkan
   * Everything after "-bin-" rather than after the tag: stable-diffusion.cpp's assets carry only
   * the commit hash, not the full "master-897-ac45422" tag, and a variant that embedded the hash
   * would change every release and never match a model's variant filter again.
   */
  static String variantOf(String engine, String tag, String assetName)
  {
    Matcher m = Pattern.compile(".*?-bin-(.+?)\\.(tar\\.gz|tgz|zip)$").matcher(assetName);
    if (m.matches())
      return m.group(1);
    return assetName.replaceAll("\\.(tar\\.gz|tgz|zip)$", "");
  }
}
