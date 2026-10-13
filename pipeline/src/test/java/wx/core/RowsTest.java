package wx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RowsTest {

  private static DaySummary sample(int... temps) {
    DayAccumulator a = new DayAccumulator();
    for (int i = 0; i < temps.length; i++) {
      a.add(new ObsRecord(100 + i, "PAFA03", "20280229", "00", temps[i], 0, 10132, 0, 45, 3, " "));
    }
    return DaySummary.of("PAFA03", "20280229", a);
  }

  @Test
  void summaryRowUsesExactStringsForNumericAndIsoDate() {
    Map<String, Object> row = Rows.summary(sample(-218, -155), "run-1");
    assertEquals(List.of("run_id", "station", "obs_date", "nobs", "ntemp", "tmin", "tmax", "tmean",
        "prcp", "wmax", "pmean", "first_record", "last_record"), List.copyOf(row.keySet()));
    assertEquals("run-1", row.get("run_id"));
    assertEquals("2028-02-29", row.get("obs_date"));
    assertEquals(2L, row.get("nobs"));
    assertEquals("-21.8", row.get("tmin"));
    assertEquals("-15.5", row.get("tmax"));
    assertEquals("-18.7", row.get("tmean"));
    assertEquals("0.6", row.get("prcp"));
    assertEquals("4.5", row.get("wmax"));
    assertEquals("1013.2", row.get("pmean"));
    assertEquals(100L, row.get("first_record"));
    assertEquals(101L, row.get("last_record"));
  }

  @Test
  void noValidTemperaturesBecomeNullNotZero() {
    Map<String, Object> row = Rows.summary(sample(ObsRecord.MISSING), "r");
    assertNull(row.get("tmin"));
    assertNull(row.get("tmax"));
    assertNull(row.get("tmean"));
    assertEquals(0L, row.get("ntemp"));
  }

  @Test
  void errorRowCarriesCodeRecordAndLegacyLine() {
    byte[] rec = Recs.record("KHOU02", "20280301", "12", 150, 100, 10100, 361, 50, 0, " ");
    Validator.Reject r = Validator.validate(WxobsLayout.decode(rec, 42));
    Map<String, Object> row = Rows.error(r, "run-1");
    assertEquals(42L, row.get("record"));
    assertEquals("R02", row.get("code"));
    assertEquals("20280301", row.get("obs_date_raw"));
    assertEquals("KHOU02 20280301 12 R02 WIND DIRECTION OUT OF RANGE", row.get("error_line"));
  }

  @Test
  void jsonEscapesAndTypes() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("s", "a\"b\\c\nd");
    m.put("n", 42L);
    m.put("z", null);
    m.put("ctl", "\u0001");
    assertEquals("{\"s\":\"a\\\"b\\\\c\\nd\",\"n\":42,\"z\":null,\"ctl\":\"\\u0001\"}", Rows.json(m));
  }
}
