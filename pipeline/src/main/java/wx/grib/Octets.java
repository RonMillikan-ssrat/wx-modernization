package wx.grib;

/**
 * Reads GRIB2 fixed-width fields. GRIB2 octet numbers are 1-based within each section, the way
 * the WMO tables print them, so callers pass the section start and the octet number exactly as in
 * the template tables and no off-by-one arithmetic leaks into the parsers.
 *
 * <p>GRIB2 trap, the equivalent of COMP-3's sign nibble: signed integers are sign-and-magnitude,
 * not two's complement. The top bit is the sign and the remaining bits the magnitude, so 0x80000001
 * is -1, not -2147483647. Reading one as a Java int gives nonsense latitudes south of the equator
 * and wrong negative scale factors.
 */
final class Octets {

  private Octets() {}

  static int u1(byte[] b, int sectionStart, int octet) {
    return b[sectionStart + octet - 1] & 0xFF;
  }

  static int u2(byte[] b, int sectionStart, int octet) {
    return (u1(b, sectionStart, octet) << 8) | u1(b, sectionStart, octet + 1);
  }

  static long u4(byte[] b, int sectionStart, int octet) {
    return ((long) u2(b, sectionStart, octet) << 16) | u2(b, sectionStart, octet + 2);
  }

  static long u8(byte[] b, int sectionStart, int octet) {
    return (u4(b, sectionStart, octet) << 32) | u4(b, sectionStart, octet + 4);
  }

  /** 2-octet sign-and-magnitude integer. */
  static int s2(byte[] b, int sectionStart, int octet) {
    int raw = u2(b, sectionStart, octet);
    int magnitude = raw & 0x7FFF;
    return (raw & 0x8000) != 0 ? -magnitude : magnitude;
  }

  /** 4-octet sign-and-magnitude integer. */
  static long s4(byte[] b, int sectionStart, int octet) {
    long raw = u4(b, sectionStart, octet);
    long magnitude = raw & 0x7FFF_FFFFL;
    return (raw & 0x8000_0000L) != 0 ? -magnitude : magnitude;
  }

  /** 4-octet IEEE 754 single precision, big-endian (Section 5 reference value). */
  static float ieee32(byte[] b, int sectionStart, int octet) {
    return Float.intBitsToFloat((int) u4(b, sectionStart, octet));
  }

  /** True when every octet of the field is 0xFF, GRIB's "missing" marker. */
  static boolean allOnes(byte[] b, int sectionStart, int octet, int length) {
    for (int k = 0; k < length; k++) {
      if (u1(b, sectionStart, octet + k) != 0xFF) {
        return false;
      }
    }
    return true;
  }
}
