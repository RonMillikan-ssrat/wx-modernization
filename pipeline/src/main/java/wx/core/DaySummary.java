package wx.core;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Final values for one station-day, computed exactly as paragraph 5000-WRITE-SUMMARY does.
 *
 * <p>All arithmetic is BigDecimal. COBOL decimal arithmetic is exact, and COMPUTE ... ROUNDED rounds
 * the exact quotient half away from zero, which is RoundingMode.HALF_UP. Using double here, or
 * Math.round (which rounds half toward positive infinity), would break parity on negative means.
 *
 * <p>Null temperature or precipitation fields mean "no valid observations" (printed as N/A).
 */
public record DaySummary(
    String station,
    String date,
    long nobs,
    long ntemp,
    BigDecimal tmin,
    BigDecimal tmax,
    BigDecimal tmean,
    BigDecimal prcp,
    BigDecimal wmax,
    BigDecimal pmean,
    long minPos,
    long maxPos)
    implements Serializable {

  private static final BigDecimal TEN = BigDecimal.TEN;

  public static DaySummary of(String station, String date, DayAccumulator a) {
    BigDecimal tmin = null, tmax = null, tmean = null, prcp = null;
    if (a.ntemp() > 0) {
      tmin = tenths(a.tmin());
      tmax = tenths(a.tmax());
      tmean = BigDecimal.valueOf(a.tsum())
          .divide(BigDecimal.valueOf(a.ntemp() * 10), 1, RoundingMode.HALF_UP);
    }
    if (a.nprcp() > 0) {
      prcp = tenths(a.prcpSum());
    }
    BigDecimal pmean = BigDecimal.valueOf(a.psum())
        .divide(BigDecimal.valueOf(a.npres() * 10), 1, RoundingMode.HALF_UP);
    return new DaySummary(station, date, a.nobs(), a.ntemp(), tmin, tmax, tmean, prcp,
        tenths(a.wmax()), pmean, a.minPos(), a.maxPos());
  }

  private static BigDecimal tenths(long v) {
    return BigDecimal.valueOf(v).divide(TEN, 1, RoundingMode.UNNECESSARY);
  }

  /**
   * The legacy fixed-width summary line (WS-SUM-LINE), trailing spaces removed as GnuCOBOL does for
   * LINE SEQUENTIAL output.
   */
  public String legacyLine() {
    String temps = ntemp == 0
        ? "   N/A    N/A    N/A"
        : signed(tmin, 6) + " " + signed(tmax, 6) + " " + signed(tmean, 6);
    String p = prcp == null ? "   N/A" : unsigned(prcp, 6);
    return Validator.rtrim(String.join(" ",
        station,
        date,
        zeroSuppressed(nobs, 3),
        zeroSuppressed(ntemp, 3),
        temps,
        p,
        unsigned(wmax, 5),
        unsigned(pmean, 6)));
  }

  /** PIC ZZ9. */
  static String zeroSuppressed(long v, int width) {
    return pad(Long.toString(v), width);
  }

  /** PIC ---9.9: floating minus sign, one decimal. */
  static String signed(BigDecimal v, int width) {
    String s = v.abs().setScale(1, RoundingMode.UNNECESSARY).toPlainString();
    return pad(v.signum() < 0 ? "-" + s : s, width);
  }

  /** PIC ZZZ9.9 / ZZ9.9: unsigned, one decimal. */
  static String unsigned(BigDecimal v, int width) {
    return pad(v.abs().setScale(1, RoundingMode.UNNECESSARY).toPlainString(), width);
  }

  private static String pad(String s, int width) {
    if (s.length() > width) {
      throw new IllegalArgumentException("value " + s + " overflows edited picture of width " + width);
    }
    return " ".repeat(width - s.length()) + s;
  }
}
