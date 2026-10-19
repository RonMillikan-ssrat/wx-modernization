package wx.grib;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds GRIB2 messages in a byte array and reads their metadata from Sections 0, 1, 3, 4 and 5.
 *
 * <p>This is the GRIB2 counterpart of the WXOBS copybook in Phase 1: the WMO templates fix every
 * field's octet position, so each value is read at its documented offset. Only what the station
 * pipeline needs is supported: grid template 3.30 (Lambert conformal), product template 4.0
 * (analysis or forecast at a point in time), hourly forecast units. A message outside that set is
 * rejected with the reason, not guessed at.
 *
 * <p>Octet numbers below are those of WMO GRIB2 Section and Template tables (1-based in section).
 */
public final class Grib2Scanner {

  private Grib2Scanner() {}

  /** All messages in {@code data}, in file order. */
  public static List<Grib2Message> scan(byte[] data) {
    List<Grib2Message> out = new ArrayList<>();
    int pos = 0;
    while (pos <= data.length - 16) {
      if (!(data[pos] == 'G' && data[pos + 1] == 'R' && data[pos + 2] == 'I' && data[pos + 3] == 'B')) {
        pos++;  // tolerate padding between messages
        continue;
      }
      int edition = Octets.u1(data, pos, 8);
      if (edition != 2) {
        throw new IllegalArgumentException("GRIB edition " + edition + " at byte " + pos + "; only GRIB2 is supported");
      }
      long length = Octets.u8(data, pos, 9);
      if (pos + length > data.length) {
        throw new IllegalArgumentException("message at byte " + pos + " claims " + length
            + " bytes but only " + (data.length - pos) + " remain (truncated download?)");
      }
      out.add(parse(data, pos, (int) length));
      pos += (int) length;
    }
    return out;
  }

  static Grib2Message parse(byte[] b, int msg, int length) {
    int discipline = Octets.u1(b, msg, 7);
    LocalDateTime ref = null;
    LambertConformal grid = null;
    int category = -1, number = -1, surfaceType = -1, timeUnit = -1;
    double surfaceValue = Double.NaN;
    long forecastTime = 0;
    int drsTemplate = -1, binaryScale = 0, decimalScale = 0, bits = 0;
    float referenceValue = 0f;

    int s = msg + 16;  // first section after Section 0
    int end = msg + length - 4;
    while (s < end) {
      int secLen = (int) Octets.u4(b, s, 1);
      int secNum = Octets.u1(b, s, 5);
      switch (secNum) {
        case 1 -> ref = LocalDateTime.of(Octets.u2(b, s, 13), Octets.u1(b, s, 15),
            Octets.u1(b, s, 16), Octets.u1(b, s, 17), Octets.u1(b, s, 18), Octets.u1(b, s, 19));
        case 3 -> grid = lambert(b, s);
        case 4 -> {
          int template = Octets.u2(b, s, 8);
          if (template != 0) {
            throw new IllegalArgumentException("product template 4." + template + " not supported");
          }
          category = Octets.u1(b, s, 10);
          number = Octets.u1(b, s, 11);
          timeUnit = Octets.u1(b, s, 18);
          if (timeUnit != 1) {
            throw new IllegalArgumentException("forecast time unit " + timeUnit + " not supported (need hours)");
          }
          forecastTime = Octets.u4(b, s, 19);
          surfaceType = Octets.u1(b, s, 23);
          if (!Octets.allOnes(b, s, 24, 5)) {
            int scale = (byte) Octets.u1(b, s, 24);  // scale factor is a signed octet
            surfaceValue = Octets.s4(b, s, 25) / Math.pow(10, scale);
          }
        }
        case 5 -> {
          drsTemplate = Octets.u2(b, s, 10);
          referenceValue = Octets.ieee32(b, s, 12);
          binaryScale = Octets.s2(b, s, 16);
          decimalScale = Octets.s2(b, s, 18);
          bits = Octets.u1(b, s, 20);
        }
        default -> { }  // 2 (local use), 6 (bitmap), 7 (data): not needed for metadata
      }
      if (secLen <= 0) {
        throw new IllegalArgumentException("zero-length section " + secNum + " at byte " + s);
      }
      s += secLen;
    }
    if (ref == null || grid == null || category < 0 || drsTemplate < 0) {
      throw new IllegalArgumentException("message at byte " + msg + " is missing a required section");
    }
    return new Grib2Message(msg, length, discipline, ref, category, number, surfaceType,
        surfaceValue, forecastTime, timeUnit, grid, drsTemplate, referenceValue, binaryScale,
        decimalScale, bits);
  }

  /** Grid template 3.30. Octets 15 onward per WMO Template 3.30. */
  static LambertConformal lambert(byte[] b, int s) {
    int template = Octets.u2(b, s, 13);
    if (template != 30) {
      throw new IllegalArgumentException("grid template 3." + template + " not supported (need 3.30 Lambert)");
    }
    double radius = earthRadius(b, s);
    int nx = (int) Octets.u4(b, s, 31);
    int ny = (int) Octets.u4(b, s, 35);
    double la1 = Octets.s4(b, s, 39) * 1e-6;
    double lo1 = Octets.u4(b, s, 43) * 1e-6;
    int resComp = Octets.u1(b, s, 47);
    double lov = Octets.u4(b, s, 52) * 1e-6;
    double dx = Octets.u4(b, s, 56) * 1e-3;
    double dy = Octets.u4(b, s, 60) * 1e-3;
    int projCentre = Octets.u1(b, s, 64);
    int scan = Octets.u1(b, s, 65);
    double latin1 = Octets.s4(b, s, 66) * 1e-6;
    double latin2 = Octets.s4(b, s, 70) * 1e-6;
    if ((projCentre & 0x80) != 0) {
      throw new IllegalArgumentException("south-pole Lambert projection not supported");
    }
    if (scan != 0x40) {
      throw new IllegalArgumentException(String.format(
          "scanning mode 0x%02X not supported (need 0x40: +i, +j, i consecutive)", scan));
    }
    return new LambertConformal(nx, ny, la1, lo1, lov, latin1, latin2, dx, dy, radius,
        (resComp & 0x08) != 0);
  }

  /** Code Table 3.2, shape of the earth: the spherical cases. */
  static double earthRadius(byte[] b, int s) {
    int shape = Octets.u1(b, s, 15);
    return switch (shape) {
      case 0 -> 6_367_470.0;
      case 6 -> 6_371_229.0;
      case 8 -> 6_371_200.0;
      case 1 -> Octets.u4(b, s, 17) / Math.pow(10, Octets.u1(b, s, 16));
      default -> throw new IllegalArgumentException("earth shape " + shape
          + " (oblate spheroid) not supported; HRRR uses 6, a sphere of radius 6371229 m");
    };
  }
}
