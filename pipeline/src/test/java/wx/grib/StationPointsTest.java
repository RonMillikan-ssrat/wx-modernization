package wx.grib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Sampling logic with a stand-in unpacker: each array holds NaN everywhere except the cells eccodes
 * reported for the stations, which hold eccodes' values. Picking any other cell yields null.
 */
class StationPointsTest {

  private static final List<Station> HOUSTON = Station.DEFAULT.subList(0, 3);

  private static Map<Integer, float[]> eccodesArrays() throws Exception {
    Map<Integer, float[]> arrays = new HashMap<>();
    for (Map<String, String> r : Fixtures.csv("small_expected.csv")) {
      int msg = Integer.parseInt(r.get("msg"));
      float[] a = arrays.computeIfAbsent(msg, k -> {
        float[] f = new float[60 * 40];
        Arrays.fill(f, Float.NaN);
        return f;
      });
      a[Integer.parseInt(r.get("index"))] = (float) Double.parseDouble(r.get("value"));
    }
    return arrays;
  }

  private static List<StationPoints.Row> rows() throws Exception {
    List<Grib2Message> msgs = Grib2Scanner.scan(Fixtures.smallGrib());
    Map<Integer, float[]> arrays = eccodesArrays();
    return StationPoints.extract(msgs, m -> arrays.get(msgs.indexOf(m) + 1), HOUSTON);
  }

  @Test
  void picksEccodesCellAndValueForEveryStationAndField() throws Exception {
    List<StationPoints.Row> rows = rows();
    assertEquals(3, rows.size(), "one valid time x three stations");
    Map<String, Map<String, String>> tmp = new HashMap<>();
    for (Map<String, String> r : Fixtures.csv("small_expected.csv")) {
      if (r.get("msg").equals("1")) {
        tmp.put(r.get("station"), r);
      }
    }
    for (StationPoints.Row row : rows) {
      Map<String, String> e = tmp.get(row.station());
      assertEquals(Integer.parseInt(e.get("i")), row.i(), row.station());
      assertEquals(Integer.parseInt(e.get("j")), row.j(), row.station());
      for (HrrrVariable v : HrrrVariable.values()) {
        assertTrue(row.raw().get(v) != null, row.station() + " " + v + " sampled the wrong cell");
      }
      assertEquals(Double.parseDouble(e.get("value")), row.raw().get(HrrrVariable.TMP_2M), 1e-4);
      assertEquals(Double.parseDouble(e.get("value")) - 273.15, row.t2mC(), 1e-4);
      assertEquals(Double.parseDouble(e.get("distance_km")), row.distanceKm(), 0.01);
    }
  }

  @Test
  void derivedWindIsEarthRelative() throws Exception {
    for (StationPoints.Row row : rows()) {
      double u = row.raw().get(HrrrVariable.UGRD_10M);
      double v = row.raw().get(HrrrVariable.VGRD_10M);
      assertEquals(Math.hypot(u, v), row.windSpeed(), 1e-9, "rotation preserves speed");
      double unrotated = StationPoints.directionFrom(u, v);
      double diff = Math.abs(((row.windDirFrom() - unrotated) % 360 + 540) % 360 - 180);
      assertTrue(diff > 0.5 && diff < 3.0,
          row.station() + ": Houston is ~2 degrees east of LoV, rotation should be ~1-2 degrees, was " + diff);
    }
  }

  @Test
  void directionConventionIsWhereTheWindComesFrom() {
    assertEquals(0.0, StationPoints.directionFrom(0, -5), 1e-9);    // blowing south: from the north
    assertEquals(270.0, StationPoints.directionFrom(5, 0), 1e-9);   // blowing east: from the west
    assertEquals(90.0, StationPoints.directionFrom(-5, 0), 1e-9);
    assertNull(StationPoints.directionFrom(0, 0));
  }

  @Test
  void stationsOffTheGridAreSkipped() throws Exception {
    List<Grib2Message> msgs = Grib2Scanner.scan(Fixtures.smallGrib());
    Map<Integer, float[]> arrays = eccodesArrays();
    List<StationPoints.Row> rows = StationPoints.extract(msgs, m -> arrays.get(msgs.indexOf(m) + 1),
        List.of(new Station("KDEN", 39.86, -104.67)));   // Denver is not on the small Houston grid
    assertTrue(rows.isEmpty());
  }

  @Test
  void wrongLengthUnpackIsRejected() throws Exception {
    List<Grib2Message> msgs = Grib2Scanner.scan(Fixtures.smallGrib());
    assertThrows(IllegalArgumentException.class,
        () -> StationPoints.extract(msgs, m -> new float[10], HOUSTON));
  }

  @Test
  void parsesStationSpec() {
    List<Station> s = Station.parse("KABC:30.5:-97.25,KXYZ:31:-96");
    assertEquals(2, s.size());
    assertEquals(-97.25, s.get(0).lon(), 0.0);
    assertEquals(Station.DEFAULT, Station.parse(""));
  }
}
