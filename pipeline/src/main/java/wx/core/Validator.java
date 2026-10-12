package wx.core;

import java.io.Serializable;
import java.time.DateTimeException;
import java.time.LocalDate;

/** Mirrors paragraph 3100-VALIDATE in WXSUMM, including the order the checks are made in. */
public final class Validator {

  /** A rejected record and the reason, as written to the legacy error file. */
  public record Reject(String code, String text, String station, String date, String hour, long position)
      implements Serializable {

    /** Same layout as WS-ERR-LINE, trailing spaces removed (LINE SEQUENTIAL behaviour). */
    public String errorLine() {
      return rtrim(station + " " + date + " " + hour + " " + code + " " + text);
    }
  }

  private Validator() {}

  /** Returns null when the record is accepted. */
  public static Reject validate(ObsRecord o) {
    if (!isNumeric(o.date()) || !isNumeric(o.hour())) {
      return reject(o, "R05", "NON-NUMERIC ZONED FIELD");
    }
    if (!isValidYyyymmdd(o.date())) {
      return reject(o, "R04", "INVALID CALENDAR DATE");
    }
    if (Integer.parseInt(o.hour()) > 23) {
      return reject(o, "R01", "HOUR OUT OF RANGE");
    }
    if (o.wdir() > 360) {
      return reject(o, "R02", "WIND DIRECTION OUT OF RANGE");
    }
    if ("X".equals(o.qc())) {
      return reject(o, "R03", "REJECTED BY QC FLAG");
    }
    return null;
  }

  /** Records that cannot be decoded at all. Not in the legacy program, which had no such check. */
  public static Reject undecodable(long position, String reason) {
    return new Reject("R06", "UNDECODABLE: " + reason, "", "", "", position);
  }

  /** IS NUMERIC on an unsigned DISPLAY field: every character is 0-9. */
  static boolean isNumeric(String s) {
    if (s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  /** FUNCTION TEST-DATE-YYYYMMDD = 0: a real calendar date in years 1601-9999. */
  static boolean isValidYyyymmdd(String s) {
    int y = Integer.parseInt(s.substring(0, 4));
    int m = Integer.parseInt(s.substring(4, 6));
    int d = Integer.parseInt(s.substring(6, 8));
    if (y < 1601) {
      return false;
    }
    try {
      LocalDate.of(y, m, d);
      return true;
    } catch (DateTimeException e) {
      return false;
    }
  }

  private static Reject reject(ObsRecord o, String code, String text) {
    return new Reject(code, text, o.station(), o.date(), o.hour(), o.position());
  }

  static String rtrim(String s) {
    int end = s.length();
    while (end > 0 && s.charAt(end - 1) == ' ') {
      end--;
    }
    return s.substring(0, end);
  }
}
