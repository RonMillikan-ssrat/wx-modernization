package wx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DaySummaryTest {

  private static DaySummary summarize(int... temps) {
    DayAccumulator a = new DayAccumulator();
    for (int i = 0; i < temps.length; i++) {
      a.add(new ObsRecord(i, "PAFA03", "20280229", "00", temps[i], 0, 10130, 0, 0, 0, " "));
    }
    return DaySummary.of("PAFA03", "20280229", a);
  }

  @Test
  void roundingIsHalfAwayFromZeroLikeCobolRounded() {
    // -12 and -13 tenths: mean -1.25 C. COBOL ROUNDED gives -1.3.
    // Math.round(-12.5) would give -12 (-1.2), and banker's rounding also gives -1.2.
    assertEquals(new BigDecimal("-1.3"), summarize(-12, -13).tmean());
    assertEquals(new BigDecimal("1.3"), summarize(12, 13).tmean());
  }

  @Test
  void floatingMinusPicture() {
    assertEquals("  -5.3", DaySummary.signed(new BigDecimal("-5.3"), 6));
    assertEquals(" -21.8", DaySummary.signed(new BigDecimal("-21.8"), 6));
    assertEquals("   0.0", DaySummary.signed(new BigDecimal("0.0"), 6));
    assertEquals("  15.2", DaySummary.signed(new BigDecimal("15.2"), 6));
  }

  @Test
  void zeroSuppressedPicture() {
    assertEquals(" 24", DaySummary.zeroSuppressed(24, 3));
    assertEquals("  0", DaySummary.zeroSuppressed(0, 3));
  }

  @Test
  void noValidTemperaturesPrintsNA() {
    DaySummary s = summarize(ObsRecord.MISSING, ObsRecord.MISSING);
    assertEquals(0L, s.ntemp());
    assertEquals("PAFA03 20280229   2   0    N/A    N/A    N/A    0.0   0.0 1013.0", s.legacyLine());
  }

  @Test
  void legacyLineMatchesAKnownCobolLine() {
    // Built to reproduce the shape of a WXSUMM line; values chosen by hand.
    DayAccumulator a = new DayAccumulator();
    a.add(new ObsRecord(0, "KHOU02", "20280227", "00", 152, 0, 10116, 0, 245, 20, " "));
    a.add(new ObsRecord(1, "KHOU02", "20280227", "01", 229, 0, 10116, 0, 100, 0, " "));
    assertEquals("KHOU02 20280227   2   2   15.2   22.9   19.1    2.0  24.5 1011.6",
        DaySummary.of("KHOU02", "20280227", a).legacyLine());
  }
}
