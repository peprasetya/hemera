package ing.aether.hemera.http;

/** Exception carrying an HTTP status; the router turns it into a JSON error response. */
public class HttpError extends RuntimeException
{
  private static final long serialVersionUID = 1L;

  public final int status;

  public HttpError(int status, String message)
  {
    super(message);
    this.status = status;
  }
}
