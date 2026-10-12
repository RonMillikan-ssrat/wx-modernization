package wx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ValidatorTest {

  private static Validator.Reject check(String station, String date, String hour, int wdir, String qc) {
    byte[] rec = Recs.record(station, date, hour, 150, 100, 10100, wdir, 50, 0, qc);
    return Validator.validate(WxobsLayout.decode(rec, 0));
  }

  @Test
  void acceptsAGoodRecord() {
    assertNull(check("KIAH01", "20280227", "12", 90, " "));
  }

  @Test
  void validLeapDayIsAccepted() {
    assertNull(check("PAFA03", "20280229", "00", 90, " "));
  }

  @Test
  void invalidLeapDayIsR04() {
    assertEquals("R04", check("KIAH01", "20270229", "10", 90, " ").code());
  }

  @Test
  void hour24IsR01() {
    assertEquals("R01", check("KIAH01", "20280301", "24", 90, " ").code());
  }

  @Test
  void windDirection361IsR02ButExactly360IsAccepted() {
    assertEquals("R02", check("KHOU02", "20280301", "12", 361, " ").code());
    assertNull(check("KHOU02", "20280301", "12", 360, " "));
  }

  @Test
  void qcFlagXIsR03() {
    assertEquals("R03", check("72243A", "20280301", "13", 90, "X").code());
  }

  @Test
  void blankDateIsR05() {
    assertEquals("R05", check("PAFA03", "        ", "14", 90, " ").code());
  }

  @Test
  void checksRunInLegacyOrder() {
    // Bad date AND bad hour AND bad wind: COBOL's EVALUATE stops at the first match, R04.
    assertEquals("R04", check("KIAH01", "20271332", "99", 999, "X").code());
  }

  @Test
  void missingTemperatureIsNotAReject() {
    byte[] rec = Recs.record("KIAH01", "20280228", "03", -9999, -9999, 10100, 90, 50, 0, " ");
    assertNull(Validator.validate(WxobsLayout.decode(rec, 0)));
  }

  @Test
  void errorLineMatchesLegacyLayout() {
    assertEquals("PAFA03          14 R05 NON-NUMERIC ZONED FIELD",
        check("PAFA03", "        ", "14", 90, " ").errorLine());
    assertEquals("KHOU02 20280301 12 R02 WIND DIRECTION OUT OF RANGE",
        check("KHOU02", "20280301", "12", 361, " ").errorLine());
  }
}
