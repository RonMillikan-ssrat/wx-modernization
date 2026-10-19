package wx.grib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Grib2ScannerTest {

  @Test
  void signAndMagnitudeIsNotTwosComplement() {
    byte[] b = {(byte) 0x80, 0x00, 0x00, 0x01, (byte) 0x80, 0x0B};
    assertEquals(-1L, Octets.s4(b, 0, 1));
    assertEquals(-11, Octets.s2(b, 0, 5));
    // Read as two's complement, the same bytes would be a large negative number.
    assertEquals(-2147483647, (int) Octets.u4(b, 0, 1));
  }

  @Test
  void metadataMatchesEccodes() throws Exception {
    List<Grib2Message> msgs = Grib2Scanner.scan(Fixtures.smallGrib());
    assertEquals(5, msgs.size());
    Map<String, Map<String, String>> firstRowPerMsg = new LinkedHashMap<>();
    for (Map<String, String> r : Fixtures.csv("small_expected.csv")) {
      firstRowPerMsg.putIfAbsent(r.get("msg"), r);
    }
    for (int k = 0; k < msgs.size(); k++) {
      Grib2Message m = msgs.get(k);
      Map<String, String> e = firstRowPerMsg.get(String.valueOf(k + 1));
      assertEquals(Integer.parseInt(e.get("discipline")), m.discipline(), "discipline msg " + (k + 1));
      assertEquals(Integer.parseInt(e.get("parameterCategory")), m.category(), "category msg " + (k + 1));
      assertEquals(Integer.parseInt(e.get("parameterNumber")), m.number(), "number msg " + (k + 1));
      assertEquals(Integer.parseInt(e.get("dataRepresentationTemplateNumber")), m.drsTemplate());
      assertEquals(Integer.parseInt(e.get("binaryScaleFactor")), m.binaryScale(), "E msg " + (k + 1));
      assertEquals(Integer.parseInt(e.get("decimalScaleFactor")), m.decimalScale(), "D msg " + (k + 1));
      assertEquals(Integer.parseInt(e.get("bitsPerValue")), m.bitsPerValue(), "bits msg " + (k + 1));
      assertEquals((float) Double.parseDouble(e.get("referenceValue")), m.referenceValue(), 0f, "R msg " + (k + 1));
      assertEquals(Integer.parseInt(e.get("Nx")), m.grid().nx);
      assertEquals(Integer.parseInt(e.get("Ny")), m.grid().ny);
      assertEquals(LocalDateTime.of(2026, 10, 14, 12, 0), m.referenceTime());
      assertEquals(Long.parseLong(e.get("forecastTime")), m.forecastTime());
    }
  }

  @Test
  void identifiesTheFiveHrrrVariables() throws Exception {
    List<HrrrVariable> vars = Grib2Scanner.scan(Fixtures.smallGrib()).stream()
        .map(Grib2Message::variable).toList();
    assertEquals(List.of(HrrrVariable.TMP_2M, HrrrVariable.DPT_2M, HrrrVariable.UGRD_10M,
        HrrrVariable.VGRD_10M, HrrrVariable.PRES_SFC), vars);
  }

  @Test
  void readsGridFlagsAndEarth() throws Exception {
    LambertConformal g = Grib2Scanner.scan(Fixtures.smallGrib()).get(0).grid();
    assertEquals(6_371_229.0, g.earthRadius, 0.0);
    assertTrue(g.uvRelativeToGrid, "HRRR-style winds are grid-relative");
    assertEquals(262.5, g.lov, 1e-9);
    assertEquals(38.5, g.latin1, 1e-9);
    assertEquals(3000.0, g.dx, 1e-9);
  }

  @Test
  void packingStepComesFromScaleFactors() throws Exception {
    Grib2Message t = Grib2Scanner.scan(Fixtures.smallGrib()).get(0);
    assertEquals(Math.pow(2, t.binaryScale()) / Math.pow(10, t.decimalScale()), t.packingStep(), 0.0);
  }

  @Test
  void truncatedFileIsRejectedWithAReason() throws Exception {
    byte[] all = Fixtures.smallGrib();
    byte[] cut = Arrays.copyOf(all, all.length - 100);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Grib2Scanner.scan(cut));
    assertTrue(e.getMessage().contains("truncated"), e.getMessage());
  }

  @Test
  void messagesAreContiguousAndCoverTheFile() throws Exception {
    byte[] all = Fixtures.smallGrib();
    List<Grib2Message> msgs = Grib2Scanner.scan(all);
    long expected = 0;
    for (Grib2Message m : msgs) {
      assertEquals(expected, m.offset());
      expected += m.length();
    }
    assertEquals(all.length, expected);
  }
}
