package ing.aether.hemera.api;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Small helpers for 16-bit mono audio: rate conversion, telephone codecs, WAV in and out. */
final class Pcm
{
  private Pcm()
  {
  }

  /**
   * Any rate to any rate, chunk by chunk without seams. Going down, each output sample averages
   * the input it stands for, which keeps most of the aliasing out; going up, it interpolates.
   */
  static final class Resampler
  {
    private int from;
    private final int to;
    private double pos;           // position of the next output sample, in input samples
    private short last;

    Resampler(int from, int to)
    {
      this.from = from;
      this.to = to;
    }

    void setRate(int r)
    {
      from = r;
      pos = 0;
    }

    short[] process(short[] in)
    {
      if (from == to)
        return in;
      double step = (double) from / to;
      short[] out = new short[(int) Math.ceil((in.length - pos) / step) + 1];
      int n = 0;
      while (pos < in.length)
      {
        double v;
        if (step > 1)
        {
          int lo = (int) Math.floor(pos);
          int hi = Math.min(in.length, (int) Math.floor(pos + step));
          double sum = 0;
          int count = 0;
          for (int i = Math.max(lo, 0); i < hi; i++, count++)
            sum += in[i];
          v = count == 0 ? (lo < 0 ? last : in[Math.max(0, lo)]) : sum / count;
        }
        else
        {
          int i = (int) Math.floor(pos);
          double frac = pos - i;
          double a = i < 0 ? last : in[i];
          double b = i + 1 < in.length ? in[i + 1] : a;
          v = a + (b - a) * frac;
        }
        out[n++] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
        pos += step;
      }
      pos -= in.length;
      if (in.length > 0)
        last = in[in.length - 1];
      return Arrays.copyOf(out, n);
    }
  }

  static short[] fromLittleEndian(byte[] raw)
  {
    short[] s = new short[raw.length / 2];
    for (int i = 0; i < s.length; i++)
      s[i] = (short) (raw[2 * i] & 0xff | raw[2 * i + 1] << 8);
    return s;
  }

  static byte[] toLittleEndian(short[] s)
  {
    byte[] b = new byte[s.length * 2];
    for (int i = 0; i < s.length; i++)
    {
      b[2 * i] = (byte) s[i];
      b[2 * i + 1] = (byte) (s[i] >> 8);
    }
    return b;
  }

  /** ITU G.711 μ-law, as North American and Japanese telephone lines carry it. */
  static short ulaw(byte b)
  {
    int u = ~b & 0xff;
    int t = ((u & 0x0f) << 3) + 0x84;
    t <<= (u & 0x70) >> 4;
    return (short) ((u & 0x80) != 0 ? 0x84 - t : t - 0x84);
  }

  /** ITU G.711 A-law, as most other telephone lines carry it. */
  static short alaw(byte b)
  {
    int a = (b ^ 0x55) & 0xff;
    int t = (a & 0x0f) << 4;
    int seg = (a & 0x70) >> 4;
    t = seg == 0 ? t + 8 : seg == 1 ? t + 0x108 : (t + 0x108) << (seg - 1);
    return (short) ((a & 0x80) != 0 ? t : -t);
  }

  /** A mono 16-bit WAV file around raw samples. */
  static byte[] wav(short[] s, int rate)
  {
    int data = s.length * 2;
    ByteBuffer b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN);
    b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + data).put("WAVE".getBytes(StandardCharsets.US_ASCII));
    b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
        .putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
    b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(data);
    for (short v : s)
      b.putShort(v);
    return b.array();
  }

  /** The sample rate in a WAV header, or 0 when this is not a WAV file. */
  static int wavRate(byte[] wav)
  {
    if (wav.length < 28 || !new String(wav, 0, 4, StandardCharsets.US_ASCII).equals("RIFF"))
      return 0;
    return ByteBuffer.wrap(wav, 24, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
  }
}
