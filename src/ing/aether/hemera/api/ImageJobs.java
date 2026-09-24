package ing.aether.hemera.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import ing.aether.hemera.Log;
import ing.aether.hemera.engine.InstanceManager;
import ing.aether.hemera.http.HttpError;
import ing.aether.hemera.json.Json;

/**
 * Makes image requests cancellable.
 *
 * stable-diffusion.cpp cannot interrupt a generation over HTTP — sd-server answers a cancel of a
 * running job with 409 "cannot be interrupted yet", and its OpenAI-style routes do not even
 * create a job to cancel. The only thing that actually frees the GPU mid-image is stopping the
 * sd-server process, which hemera owns. That is cheap: sd.cpp reads its weights lazily at the
 * first generation, from page cache after the first time.
 *
 * Killing a process would also kill every request queued inside it, so requests are queued here
 * instead, one per model at a time. Only the running request is ever inside sd-server, which
 * makes cancelling exact: a queued request is simply dropped, and a restart takes down nothing
 * but the job that asked for it.
 */
final class ImageJobs
{
  static final class Job
  {
    final String id;
    final String model;
    final String kind;
    final long createdAt = System.currentTimeMillis();
    private long startedAt;
    private boolean running;
    private boolean cancelled;

    Job(String id, String model, String kind)
    {
      this.id = id;
      this.model = model;
      this.kind = kind;
    }

    synchronized boolean isCancelled()
    {
      return cancelled;
    }

    synchronized boolean isRunning()
    {
      return running;
    }
  }

  /** The body of a job, run once it reaches the front of its model's queue. */
  interface Work
  {
    Map<String, Object> run(Job job) throws Exception;
  }

  private final InstanceManager im;
  private final Map<String, Job> jobs = new ConcurrentHashMap<>();
  private final Map<String, Semaphore> gates = new ConcurrentHashMap<>();

  ImageJobs(InstanceManager im)
  {
    this.im = im;
  }

  Job create(String requestedId, String model, String kind)
  {
    String id = requestedId == null || requestedId.isBlank()
        ? "img_" + Long.toHexString(System.nanoTime()) + Integer.toHexString((int) (Math.random() * 0xFFFF))
        : requestedId.trim();
    if (id.length() > 128 || !id.matches("[A-Za-z0-9_.:-]+"))
      throw new HttpError(400, "request id must be 1-128 characters of A-Z a-z 0-9 _ . : -");
    Job job = new Job(id, model, kind);
    if (jobs.putIfAbsent(id, job) != null)
      throw new HttpError(409, "an image request with id '" + id + "' is already in progress");
    return job;
  }

  /** Wait for the model's turn, then run. Throws a "cancelled" HttpError if cancelled first. */
  Map<String, Object> run(Job job, Work work) throws Exception
  {
    Semaphore gate = gates.computeIfAbsent(job.model, k -> new Semaphore(1, true));
    boolean acquired = false;
    try
    {
      // Polled rather than blocking, so a job cancelled while it is still queued leaves at once.
      while (!(acquired = gate.tryAcquire(500, TimeUnit.MILLISECONDS)))
        if (job.isCancelled())
          throw cancelledError(job);
      synchronized (job)
      {
        if (job.cancelled)
          throw cancelledError(job);
        job.running = true;
        job.startedAt = System.currentTimeMillis();
      }
      try
      {
        return work.run(job);
      }
      catch (IOException | HttpError e)
      {
        // Stopping the engine makes the in-flight call fail on a dead connection; report it as
        // the cancellation it was, not as an engine failure.
        if (job.isCancelled())
          throw cancelledError(job);
        throw e;
      }
    }
    finally
    {
      synchronized (job)
      {
        job.running = false;
      }
      if (acquired)
        gate.release();
      jobs.remove(job.id, job);
    }
  }

  /** Cancel one job. A queued job just leaves the queue; a running one stops its engine. */
  void cancel(Job job, String why)
  {
    boolean stopEngine;
    synchronized (job)
    {
      if (job.cancelled)
        return;
      job.cancelled = true;
      stopEngine = job.running;
    }
    if (stopEngine)
    {
      Log.info("images", "Cancelling '" + job.id + "' on '" + job.model + "' (" + why + ") — stopping its"
          + " engine, since sd-server cannot interrupt a generation; it restarts on the next request");
      im.kill(job.model);
    }
    else
      Log.info("images", "Cancelling queued '" + job.id + "' on '" + job.model + "' (" + why + ")");
  }

  /**
   * Call between steps of a job's work: once the engine is up, and before sending it the
   * request. Without it, a cancel that lands while the engine is still starting finds nothing to
   * stop, and the job would go on to generate anyway.
   */
  static void checkpoint(Job job)
  {
    if (job.isCancelled())
      throw cancelledError(job);
  }

  /** Drop a job that never got as far as run(), e.g. because its response could not start. */
  void forget(Job job)
  {
    jobs.remove(job.id, job);
  }

  Job find(String id)
  {
    return id == null ? null : jobs.get(id);
  }

  /** The job currently generating on a model, if any. */
  Job running(String model)
  {
    for (Job j : jobs.values())
      if (j.model.equals(model) && j.isRunning())
        return j;
    return null;
  }

  List<Object> list()
  {
    long now = System.currentTimeMillis();
    List<Job> sorted = new ArrayList<>(jobs.values());
    sorted.sort((a, b) -> Long.compare(a.createdAt, b.createdAt));
    List<Object> out = new ArrayList<>();
    for (Job j : sorted)
    {
      boolean running;
      long started;
      synchronized (j)
      {
        running = j.running;
        started = j.startedAt;
      }
      out.add(Json.map(
          "id", j.id,
          "model", j.model,
          "kind", j.kind,
          "state", running ? "running" : "queued",
          "elapsedSeconds", (now - (running ? started : j.createdAt)) / 1000));
    }
    return out;
  }

  private static HttpError cancelledError(Job job)
  {
    return new HttpError(409, "image request '" + job.id + "' was cancelled");
  }
}
