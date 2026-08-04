package ing.aether.hemera.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import ing.aether.hemera.Log;

/**
 * Minimal tar (ustar + GNU longname) and zip extraction — pure JDK.
 * Enough for GitHub release archives; preserves the executable bit from tar modes.
 */
public final class Tar
{
  public static void extractTar(InputStream in, Path dest) throws IOException
  {
    byte[] header = new byte[512];
    String pendingLongName = null;
    while (true)
    {
      if (!readBlock(in, header))
        return;
      if (isZero(header))
        return;
      String name = pendingLongName != null ? pendingLongName : str(header, 0, 100);
      pendingLongName = null;
      String prefix = str(header, 345, 155);
      if (!prefix.isEmpty() && !name.startsWith("/"))
        name = prefix + "/" + name;
      long size = octal(header, 124, 12);
      int mode = (int) octal(header, 100, 8);
      char type = (char) header[156];
      String linkName = str(header, 157, 100);

      switch (type)
      {
        case 'L': // GNU long name: content is the real name of the next entry
        {
          byte[] nb = readContent(in, size);
          pendingLongName = new String(nb, StandardCharsets.UTF_8).trim().replace("\0", "");
          break;
        }
        case 'x': case 'g': case 'K': // pax/global headers, long link target: skip
          skipContent(in, size);
          break;
        case '5': // directory
          Files.createDirectories(safeResolve(dest, name));
          skipContent(in, size);
          break;
        case '2': // symlink
        {
          Path target = safeResolve(dest, name);
          Files.createDirectories(target.getParent());
          try
          {
            Files.deleteIfExists(target);
            Files.createSymbolicLink(target, Path.of(linkName));
          }
          catch (Exception e)
          {
            Log.debug("tar", "symlink " + name + " -> " + linkName + " failed: " + e);
          }
          skipContent(in, size);
          break;
        }
        case '0': case '\0': case '7': // regular file
        {
          Path target = safeResolve(dest, name);
          Files.createDirectories(target.getParent());
          try (var os = Files.newOutputStream(target))
          {
            copy(in, os, size);
          }
          skipPadding(in, size);
          if ((mode & 0111) != 0)
            target.toFile().setExecutable(true, false);
          break;
        }
        default:
          skipContent(in, size);
      }
    }
  }

  public static void extractZip(InputStream in, Path dest) throws IOException
  {
    try (ZipInputStream zis = new ZipInputStream(in))
    {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null)
      {
        Path target = safeResolve(dest, entry.getName());
        if (entry.isDirectory())
        {
          Files.createDirectories(target);
        }
        else
        {
          Files.createDirectories(target.getParent());
          try (var os = Files.newOutputStream(target))
          {
            zis.transferTo(os);
          }
        }
        zis.closeEntry();
      }
    }
  }

  // ---- helpers ------------------------------------------------------------

  private static Path safeResolve(Path dest, String name) throws IOException
  {
    Path p = dest.resolve(name).normalize();
    if (!p.startsWith(dest))
      throw new IOException("archive entry escapes destination: " + name);
    return p;
  }

  private static boolean readBlock(InputStream in, byte[] block) throws IOException
  {
    int n = in.readNBytes(block, 0, 512);
    return n == 512;
  }

  private static boolean isZero(byte[] block)
  {
    for (byte b : block)
      if (b != 0)
        return false;
    return true;
  }

  private static String str(byte[] b, int off, int len)
  {
    int end = off;
    while (end < off + len && b[end] != 0)
      end++;
    return new String(b, off, end - off, StandardCharsets.UTF_8);
  }

  private static long octal(byte[] b, int off, int len)
  {
    long v = 0;
    for (int i = off; i < off + len; i++)
    {
      byte c = b[i];
      if (c == 0 || c == ' ')
        continue;
      if (c < '0' || c > '7')
        break;
      v = v * 8 + (c - '0');
    }
    return v;
  }

  private static byte[] readContent(InputStream in, long size) throws IOException
  {
    byte[] b = in.readNBytes((int) Math.min(size, 1 << 20));
    skipPadding(in, size);
    return b;
  }

  private static void copy(InputStream in, java.io.OutputStream os, long size) throws IOException
  {
    byte[] buf = new byte[1 << 16];
    long left = size;
    while (left > 0)
    {
      int n = in.read(buf, 0, (int) Math.min(buf.length, left));
      if (n < 0)
        throw new IOException("truncated tar");
      os.write(buf, 0, n);
      left -= n;
    }
  }

  private static void skipContent(InputStream in, long size) throws IOException
  {
    skipExactly(in, size);
    skipPadding(in, size);
  }

  private static void skipPadding(InputStream in, long size) throws IOException
  {
    long pad = (512 - size % 512) % 512;
    skipExactly(in, pad);
  }

  private static void skipExactly(InputStream in, long n) throws IOException
  {
    long left = n;
    while (left > 0)
    {
      long s = in.skip(left);
      if (s <= 0)
      {
        if (in.read() < 0)
          throw new IOException("truncated tar");
        s = 1;
      }
      left -= s;
    }
  }
}
