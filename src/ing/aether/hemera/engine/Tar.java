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

  /**
   * Extract a zip, honouring the Unix symlinks and permissions recorded in it. Those live only in
   * the central directory at the end of the file, which a streaming reader never sees — so a
   * release that ships libfoo.so.3 as a link used to come out as a 19-byte text file holding the
   * link's target, and the engine died with "file too short".
   */
  public static void extractZip(Path zipFile, Path dest) throws IOException
  {
    java.util.Map<String, Integer> modes = unixModes(zipFile);
    try (ZipInputStream zis = new ZipInputStream(new java.io.BufferedInputStream(Files.newInputStream(zipFile), 1 << 16)))
    {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null)
      {
        Path target = safeResolve(dest, entry.getName());
        int mode = modes.getOrDefault(entry.getName(), 0);
        if (entry.isDirectory())
        {
          Files.createDirectories(target);
        }
        else if ((mode & 0170000) == 0120000)
        {
          // a symlink's "content" is its target path
          String link = new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
          Path resolved = target.getParent().resolve(link).normalize();
          if (!resolved.startsWith(dest))
            throw new IOException("archive symlink escapes destination: " + entry.getName() + " -> " + link);
          Files.createDirectories(target.getParent());
          Files.deleteIfExists(target);
          Files.createSymbolicLink(target, Path.of(link));
        }
        else
        {
          Files.createDirectories(target.getParent());
          try (var os = Files.newOutputStream(target))
          {
            zis.transferTo(os);
          }
          if ((mode & 0111) != 0)
            target.toFile().setExecutable(true, false);
        }
        zis.closeEntry();
      }
    }
  }

  /**
   * Entry name -> Unix mode, read from the zip's central directory. Empty when the archive was
   * not written on Unix or is laid out in a way this does not read (zip64), in which case
   * everything is extracted as a plain file, as before.
   */
  private static java.util.Map<String, Integer> unixModes(Path zipFile) throws IOException
  {
    java.util.Map<String, Integer> modes = new java.util.HashMap<>();
    try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(zipFile))
    {
      long size = ch.size();
      int tailLen = (int) Math.min(size, 65557);
      java.nio.ByteBuffer tail = java.nio.ByteBuffer.allocate(tailLen).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      ch.read(tail, size - tailLen);
      int eocd = -1;
      for (int i = tailLen - 22; i >= 0; i--)
        if (tail.getInt(i) == 0x06054b50)
        {
          eocd = i;
          break;
        }
      if (eocd < 0)
        return modes;
      long cdSize = tail.getInt(eocd + 12) & 0xFFFFFFFFL;
      long cdOffset = tail.getInt(eocd + 16) & 0xFFFFFFFFL;
      if (cdOffset == 0xFFFFFFFFL || cdSize > Integer.MAX_VALUE || cdOffset + cdSize > size)
        return modes;
      java.nio.ByteBuffer cd = java.nio.ByteBuffer.allocate((int) cdSize).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      ch.read(cd, cdOffset);
      int pos = 0;
      while (pos + 46 <= cd.capacity() && cd.getInt(pos) == 0x02014b50)
      {
        int madeBy = cd.getShort(pos + 4) & 0xFFFF;
        int nameLen = cd.getShort(pos + 28) & 0xFFFF;
        int extraLen = cd.getShort(pos + 30) & 0xFFFF;
        int commentLen = cd.getShort(pos + 32) & 0xFFFF;
        int external = cd.getInt(pos + 38);
        byte[] name = new byte[nameLen];
        cd.position(pos + 46);
        cd.get(name);
        if (madeBy >> 8 == 3) // host system 3 = Unix: the high 16 bits are st_mode
          modes.put(new String(name, java.nio.charset.StandardCharsets.UTF_8), external >>> 16);
        pos += 46 + nameLen + extraLen + commentLen;
      }
    }
    return modes;
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
