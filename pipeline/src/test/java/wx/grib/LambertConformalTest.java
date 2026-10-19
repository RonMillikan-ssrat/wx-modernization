package wx.grib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LambertConformalTest {

  /** eccodes computed every HRRR grid point's coordinates; we must land on the same cell. */
  @Test
  void nearestCellMatchesEccodesAcrossTheHrrrGrid() throws Exception {
    List<Map<String, String>> rows = Fixtures.csv("hrrr_nearest.csv");
    int checked = 0;
    for (Map<String, String> r : rows) {
      int[] ij = Fixtures.HRRR.nearest(Double.parseDouble(r.get("lat")), Double.parseDouble(r.get("lon")));
      String where = r.get("name") + " " + r.get("lat") + "," + r.get("lon");
      assertTrue(ij != null, "inside grid: " + where);
      assertEquals(Integer.parseInt(r.get("i")), ij[0], "i for " + where);
      assertEquals(Integer.parseInt(r.get("j")), ij[1], "j for " + where);
      assertEquals(Integer.parseInt(r.get("index")), Fixtures.HRRR.flatIndex(ij[0], ij[1]), "index for " + where);
      checked++;
    }
    assertTrue(checked > 350, "checked " + checked + " points");
  }

  /** Inverse projection: grid point coordinates agree with eccodes to well under a metre. */
  @Test
  void gridPointCoordinatesMatchEccodes() throws Exception {
    for (Map<String, String> r : Fixtures.csv("hrrr_nearest.csv")) {
      double[] ll = Fixtures.HRRR.latLon(Integer.parseInt(r.get("i")), Integer.parseInt(r.get("j")));
      double elat = Double.parseDouble(r.get("grid_lat"));
      double elon = Double.parseDouble(r.get("grid_lon"));
      assertEquals(elat, ll[0], 1e-6, "lat for " + r.get("name"));
      assertEquals(0.0, Fixtures.lonDiff(ll[1], elon), 1e-6, "lon for " + r.get("name"));
    }
  }

  @Test
  void stationsLandOnTheExpectedHrrrCells() throws Exception {
    Map<String, int[]> expected = Map.of(
        "KIAH", new int[] {969, 213}, "KHOU", new int[] {972, 201},
        "KSGR", new int[] {959, 199}, "KDEN", new int[] {695, 588});
    for (Map<String, String> r : Fixtures.csv("hrrr_nearest.csv")) {
      int[] want = expected.get(r.get("name"));
      if (want != null) {
        int[] got = Fixtures.HRRR.nearest(Double.parseDouble(r.get("lat")), Double.parseDouble(r.get("lon")));
        assertEquals(want[0], got[0], r.get("name"));
        assertEquals(want[1], got[1], r.get("name"));
      }
    }
  }

  @Test
  void pointsOutsideTheGridReturnNull() {
    assertEquals(null, Fixtures.HRRR.nearest(64.8, -147.7));   // Fairbanks: not on CONUS HRRR
    assertEquals(null, Fixtures.HRRR.nearest(19.7, -155.1));   // Hawaii
  }

  @Test
  void smallGridNearestMatchesEccodesFindNearest() throws Exception {
    LambertConformal g = Grib2Scanner.scan(Fixtures.smallGrib()).get(0).grid();
    for (Map<String, String> r : Fixtures.csv("small_expected.csv")) {
      int[] ij = g.nearest(Double.parseDouble(r.get("lat")), Double.parseDouble(r.get("lon")));
      assertEquals(Integer.parseInt(r.get("index")), g.flatIndex(ij[0], ij[1]), r.get("station"));
      assertEquals(Double.parseDouble(r.get("distance_km")),
          g.distanceKm(Double.parseDouble(r.get("lat")), Double.parseDouble(r.get("lon")),
              g.latLon(ij[0], ij[1])[0], g.latLon(ij[0], ij[1])[1]), 0.01, r.get("station"));
    }
  }

  /**
   * Wind rotation checked against geometry, not against the formula: the grid's +j axis at a point
   * is the direction to the next grid point north in j. A wind blowing along +j (u=0, v=1 grid-
   * relative) must come out pointing along that bearing after rotation.
   */
  @Test
  void rotationTurnsGridNorthOntoItsTrueBearing() {
    LambertConformal g = Fixtures.HRRR;
    int[][] cells = {{969, 213}, {695, 588}, {10, 1000}, {1790, 1000}, {899, 500}};
    for (int[] c : cells) {
      double[] a = g.latLon(c[0], c[1]);
      double[] b = g.latLon(c[0], c[1] + 1);
      double bearing = bearing(a[0], a[1], b[0], b[1]);
      double[] uv = g.toEarthRelative(0.0, 1.0, a[1]);
      double towards = Math.toDegrees(Math.atan2(uv[0], uv[1]));
      assertEquals(0.0, angleDiff(bearing, towards), 0.01, "cell " + c[0] + "," + c[1]);
      assertEquals(1.0, Math.hypot(uv[0], uv[1]), 1e-12, "speed is unchanged");
    }
  }

  @Test
  void noRotationAlongTheCentralMeridian() {
    double[] uv = Fixtures.HRRR.toEarthRelative(3.0, 4.0, -97.5);
    assertEquals(3.0, uv[0], 1e-12);
    assertEquals(4.0, uv[1], 1e-12);
  }

  @Test
  void rotationAtTheGridCornersIsLarge() {
    double[] corner = Fixtures.HRRR.latLon(1798, 1058);
    double[] uv = Fixtures.HRRR.toEarthRelative(0.0, 1.0, corner[1]);
    double turned = Math.abs(Math.toDegrees(Math.atan2(uv[0], uv[1])));
    assertTrue(turned > 20.0, "north-east corner turned by " + turned + " degrees");
  }

  private static double bearing(double lat1, double lon1, double lat2, double lon2) {
    double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lon2 - lon1);
    return Math.toDegrees(Math.atan2(Math.sin(dl) * Math.cos(p2),
        Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl)));
  }

  private static double angleDiff(double a, double b) {
    double d = (a - b) % 360.0;
    return d > 180 ? d - 360 : d < -180 ? d + 360 : d;
  }
}
