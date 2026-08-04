package ing.aether.hemera.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads just enough ELF to answer "which shared libraries does this file need, and
 * where does it look for them itself?" — the DT_NEEDED, DT_RUNPATH and DT_RPATH
 * entries of the dynamic section. Only the headers are read; the file is never loaded.
 */
public final class Elf
{
  /** Empty when the file is not a readable 64-bit little-endian ELF. */
  public record Info(List<String> needed, List<String> runPaths)
  {
    public static final Info EMPTY = new Info(List.of(), List.of());

    public boolean isEmpty()
    {
      return needed.isEmpty() && runPaths.isEmpty();
    }
  }

  private static final int PT_LOAD = 1, PT_DYNAMIC = 2;
  private static final long DT_NULL = 0, DT_NEEDED = 1, DT_STRTAB = 5, DT_STRSZ = 10,
      DT_RPATH = 15, DT_RUNPATH = 29;

  private Elf()
  {
  }

  public static Info read(Path file)
  {
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ))
    {
      ByteBuffer ident = get(ch, 0, 64);
      if (ident.limit() < 64)
        return Info.EMPTY;
      if (ident.get(0) != 0x7F || ident.get(1) != 'E' || ident.get(2) != 'L' || ident.get(3) != 'F')
        return Info.EMPTY;
      if (ident.get(4) != 2 || ident.get(5) != 1)
        return Info.EMPTY; // only 64-bit little-endian

      long phoff = ident.getLong(0x20);
      int phentsize = ident.getShort(0x36) & 0xFFFF;
      int phnum = ident.getShort(0x38) & 0xFFFF;
      if (phoff <= 0 || phentsize < 56 || phnum == 0 || phnum > 4096)
        return Info.EMPTY;

      // Collect PT_LOAD segments (to translate virtual addresses) and PT_DYNAMIC.
      List<long[]> loads = new ArrayList<>(); // {vaddr, filesz, offset}
      long dynOffset = -1, dynSize = 0;
      ByteBuffer phdrs = get(ch, phoff, phentsize * phnum);
      for (int i = 0; i < phnum; i++)
      {
        int base = i * phentsize;
        if (base + 56 > phdrs.limit())
          break;
        int type = phdrs.getInt(base);
        long offset = phdrs.getLong(base + 0x08);
        long vaddr = phdrs.getLong(base + 0x10);
        long filesz = phdrs.getLong(base + 0x20);
        if (type == PT_LOAD)
          loads.add(new long[] { vaddr, filesz, offset });
        else if (type == PT_DYNAMIC)
        {
          dynOffset = offset;
          dynSize = filesz;
        }
      }
      if (dynOffset < 0 || dynSize <= 0 || dynSize > (1 << 22))
        return Info.EMPTY;

      // Walk the dynamic section: gather string-table location and the tags we care about.
      ByteBuffer dyn = get(ch, dynOffset, (int) dynSize);
      long strtabVaddr = -1, strsz = 0;
      List<Long> neededOffsets = new ArrayList<>();
      List<Long> pathOffsets = new ArrayList<>();
      for (int off = 0; off + 16 <= dyn.limit(); off += 16)
      {
        long tag = dyn.getLong(off);
        long val = dyn.getLong(off + 8);
        if (tag == DT_NULL)
          break;
        if (tag == DT_NEEDED)
          neededOffsets.add(val);
        else if (tag == DT_STRTAB)
          strtabVaddr = val;
        else if (tag == DT_STRSZ)
          strsz = val;
        else if (tag == DT_RUNPATH || tag == DT_RPATH)
          pathOffsets.add(val);
      }
      if (strtabVaddr < 0 || strsz <= 0 || strsz > (1 << 24))
        return Info.EMPTY;

      long strtabOffset = toFileOffset(loads, strtabVaddr);
      if (strtabOffset < 0)
        return Info.EMPTY;
      byte[] strtab = new byte[(int) strsz];
      ByteBuffer sb = get(ch, strtabOffset, (int) strsz);
      sb.get(strtab, 0, Math.min(strtab.length, sb.limit()));

      Set<String> needed = new LinkedHashSet<>();
      for (long o : neededOffsets)
      {
        String s = cstr(strtab, o);
        if (!s.isEmpty())
          needed.add(s);
      }
      // RPATH/RUNPATH are colon-separated lists; $ORIGIN is relative to the file itself.
      Path origin = file.toAbsolutePath().getParent();
      Set<String> runPaths = new LinkedHashSet<>();
      for (long o : pathOffsets)
        for (String entry : cstr(strtab, o).split(":"))
        {
          String e = entry.trim();
          if (e.isEmpty())
            continue;
          e = e.replace("${ORIGIN}", origin.toString()).replace("$ORIGIN", origin.toString());
          runPaths.add(e);
        }
      return new Info(List.copyOf(needed), List.copyOf(runPaths));
    }
    catch (IOException | RuntimeException e)
    {
      return Info.EMPTY;
    }
  }

  /** True when the file starts with the ELF magic — cheap pre-filter before a full read. */
  public static boolean isElf(Path file)
  {
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ))
    {
      ByteBuffer b = get(ch, 0, 4);
      return b.limit() == 4 && b.get(0) == 0x7F && b.get(1) == 'E' && b.get(2) == 'L' && b.get(3) == 'F';
    }
    catch (IOException e)
    {
      return false;
    }
  }

  private static long toFileOffset(List<long[]> loads, long vaddr)
  {
    for (long[] l : loads)
      if (vaddr >= l[0] && vaddr < l[0] + l[1])
        return vaddr - l[0] + l[2];
    return -1;
  }

  private static String cstr(byte[] buf, long offset)
  {
    if (offset < 0 || offset >= buf.length)
      return "";
    int end = (int) offset;
    while (end < buf.length && buf[end] != 0)
      end++;
    return new String(buf, (int) offset, end - (int) offset, StandardCharsets.UTF_8);
  }

  private static ByteBuffer get(FileChannel ch, long position, int size) throws IOException
  {
    ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    ch.read(buf, position);
    buf.flip();
    ByteBuffer full = ByteBuffer.allocate(Math.max(size, buf.limit())).order(ByteOrder.LITTLE_ENDIAN);
    full.put(buf);
    full.position(0);
    full.limit(buf.limit());
    return full;
  }

  /** Convenience: does this directory contain a file with the given SONAME? */
  public static boolean has(Path dir, String soname)
  {
    return Files.isRegularFile(dir.resolve(soname));
  }
}
