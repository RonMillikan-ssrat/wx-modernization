package wx.core;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;

/** Builds raw 40-byte WXOBS records for tests, the same way data/gen_obs.py does. */
final class Recs {

  private Recs() {}

  static byte[] record(String station, String date, String hour, int temp, int dewpt, int press,
                       int wdir, int wspd, int prcp, String qc) {
    return record(station, date, hour, temp, dewpt, pack(press, null), wdir, wspd, prcp, qc);
  }

  static byte[] record(String station, String date, String hour, int temp, int dewpt, byte[] press,
                       int wdir, int wspd, int prcp, String qc) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(ebcdic(station, 6));
    out.writeBytes(ebcdic(date, 8));
    out.writeBytes(ebcdic(hour, 2));
    out.writeBytes(pack(temp, null));
    out.writeBytes(pack(dewpt, null));
    out.writeBytes(press);
    out.writeBytes(new byte[] {(byte) (wdir >>> 8), (byte) wdir});
    out.writeBytes(new byte[] {(byte) (wspd >>> 8), (byte) wspd});
    out.writeBytes(pack(prcp, null));
    out.writeBytes(ebcdic(qc, 1));
    out.writeBytes(ebcdic("", 7));
    byte[] rec = out.toByteArray();
    if (rec.length != WxobsLayout.LRECL) {
      throw new IllegalStateException("built " + rec.length + " bytes");
    }
    return rec;
  }

  static byte[] obs(String station, String date, int hour, int temp) {
    return record(station, date, String.format("%02d", hour), temp, temp - 20, 10130, 90, 50, 0, " ");
  }

  /** 5-digit COMP-3 (3 bytes). sign null = C for positive, D for negative. */
  static byte[] pack(int value, Character sign) {
    char s = sign != null ? sign : (value < 0 ? 'D' : 'C');
    return HexFormat.of().parseHex(String.format("%05d", Math.abs(value)) + s);
  }

  static byte[] ebcdic(String text, int width) {
    String padded = (text + " ".repeat(width)).substring(0, width);
    return padded.getBytes(CopybookCodec.CP037);
  }

  static byte[] concat(byte[]... recs) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] r : recs) {
      out.writeBytes(r);
    }
    return out.toByteArray();
  }
}
