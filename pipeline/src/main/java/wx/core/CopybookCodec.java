package wx.core;

import java.nio.charset.Charset;

/**
 * Decoders for the three COBOL USAGE types in copybook WXOBS.
 *
 * <p>Each decoder works on a slice of the raw record. Never convert the whole record from EBCDIC
 * first: COMP and COMP-3 bytes are binary, and a character translation corrupts them.
 */
public final class CopybookCodec {

  /** EBCDIC code page 037 (US/Canada). CP1047 differs on [ ] ^ and a few others. */
  public static final Charset CP037 = Charset.forName("IBM037");

  private CopybookCodec() {}

  /**
   * PIC X / PIC 9 USAGE DISPLAY, EBCDIC text.
   *
   * <p>WXSUMM translates only digits, A-Z, space and hyphen and leaves other bytes untouched; the
   * JDK decodes every byte. The two agree for every byte this file is allowed to contain.
   */
  public static String display(byte[] rec, int off, int len) {
    return new String(rec, off, len, CP037);
  }

  /**
   * PIC S9(n) COMP-3: packed decimal, two digits per byte, sign in the low nibble of the last byte.
   * Sign nibbles B and D are negative; A, C, E and F are positive (F = unsigned).
   *
   * @throws IllegalArgumentException on a digit nibble above 9 or an invalid sign nibble
   */
  public static long comp3(byte[] rec, int off, int len) {
    long value = 0;
    for (int i = 0; i < len; i++) {
      int b = rec[off + i] & 0xFF;
      int hi = b >>> 4;
      int lo = b & 0x0F;
      value = value * 10 + digit(hi, rec, off, len);
      if (i < len - 1) {
        value = value * 10 + digit(lo, rec, off, len);
      } else {
        switch (lo) {
          case 0xB, 0xD -> value = -value;
          case 0xA, 0xC, 0xE, 0xF -> { }
          default -> throw new IllegalArgumentException("invalid COMP-3 sign nibble in " + hex(rec, off, len));
        }
      }
    }
    return value;
  }

  /** PIC 9(4) COMP: unsigned binary halfword, big-endian (mainframe and GnuCOBOL default order). */
  public static int compUnsignedBE(byte[] rec, int off, int len) {
    if (len != 2) {
      throw new IllegalArgumentException("only 2-byte COMP fields are used in WXOBS");
    }
    return ((rec[off] & 0xFF) << 8) | (rec[off + 1] & 0xFF);
  }

  private static int digit(int nibble, byte[] rec, int off, int len) {
    if (nibble > 9) {
      throw new IllegalArgumentException("invalid COMP-3 digit nibble in " + hex(rec, off, len));
    }
    return nibble;
  }

  static String hex(byte[] rec, int off, int len) {
    StringBuilder sb = new StringBuilder(len * 2);
    for (int i = 0; i < len; i++) {
      sb.append(String.format("%02X", rec[off + i] & 0xFF));
    }
    return sb.toString();
  }
}
