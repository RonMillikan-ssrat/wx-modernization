package wx.grib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * netCDF-Java against eccodes on the same file. Both decode the same packed integers, so the only
 * expected difference is float rounding, far below half a packing step. That half step is the
 * tolerance: it is derived from the file (R, E, D), not picked by hand.
 */
class NetcdfUnpackerTest {

  @Test
  void valuesAtStationsMatchEccodes() throws Exception {
    byte[] data = Fixtures.smallGrib();
    List<Grib2Message> msgs = Grib2Scanner.scan(data);
    try (NetcdfUnpacker u = new NetcdfUnpacker(data, msgs)) {
      for (Map<String, String> r : Fixtures.csv("small_expected.csv")) {
        Grib2Message m = msgs.get(Integer.parseInt(r.get("msg")) - 1);
        float[] v = u.unpack(m);
        double tol = m.packingStep() / 2;
        assertEquals(Double.parseDouble(r.get("value")), v[Integer.parseInt(r.get("index"))], tol,
            r.get("station") + " msg " + r.get("msg"));
      }
    }
  }

  @Test
  void wholeFieldStatisticsMatchEccodes() throws Exception {
    byte[] data = Fixtures.smallGrib();
    List<Grib2Message> msgs = Grib2Scanner.scan(data);
    Map<String, Map<String, String>> perMsg = new HashMap<>();
    for (Map<String, String> r : Fixtures.csv("small_expected.csv")) {
      perMsg.putIfAbsent(r.get("msg"), r);
    }
    try (NetcdfUnpacker u = new NetcdfUnpacker(data, msgs)) {
      for (int k = 0; k < msgs.size(); k++) {
        Grib2Message m = msgs.get(k);
        Map<String, String> e = perMsg.get(String.valueOf(k + 1));
        float[] v = u.unpack(m);
        assertEquals(m.grid().nx * m.grid().ny, v.length);
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
        for (float x : v) {
          min = Math.min(min, x);
          max = Math.max(max, x);
          sum += x;
        }
        double tol = m.packingStep() / 2;
        assertEquals(Double.parseDouble(e.get("vmin")), min, tol, "min msg " + (k + 1));
        assertEquals(Double.parseDouble(e.get("vmax")), max, tol, "max msg " + (k + 1));
        // the sum of 2400 values may drift by up to one half-step per value
        assertEquals(Double.parseDouble(e.get("vsum")), sum, tol * v.length, "sum msg " + (k + 1));
      }
    }
  }

  @Test
  void endToEndSamplingWithRealUnpacking() throws Exception {
    byte[] data = Fixtures.smallGrib();
    List<Grib2Message> msgs = Grib2Scanner.scan(data);
    try (NetcdfUnpacker u = new NetcdfUnpacker(data, msgs)) {
      List<StationPoints.Row> rows = StationPoints.extract(msgs, u::unpack, Station.DEFAULT);
      assertEquals(3, rows.size(), "three Houston stations; KDEN is off the small grid");
      for (StationPoints.Row row : rows) {
        assertTrue(row.t2mC() > 0 && row.t2mC() < 40, row.station() + " t2m " + row.t2mC());
        assertTrue(row.psfcHpa() > 980 && row.psfcHpa() < 1040, row.station() + " psfc " + row.psfcHpa());
      }
    }
  }
}
