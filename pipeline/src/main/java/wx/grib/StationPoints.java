package wx.grib;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Samples GRIB2 fields at stations: nearest grid point, raw values, and derived quantities.
 *
 * <p>Unpacking the data section is delegated to {@code unpack}, so this class has no dependency on
 * a GRIB library. The pipeline passes netCDF-Java's decoder; tests pass arrays with known values.
 */
public final class StationPoints {

  private StationPoints() {}

  /** One station at one valid time. Raw values are in the file's units; null when absent. */
  public record Row(
      String station,
      LocalDateTime validTime,
      LocalDateTime referenceTime,
      long forecastHour,
      int i,
      int j,
      double gridLat,
      double gridLon,
      double distanceKm,
      Map<HrrrVariable, Double> raw,
      Double t2mC,
      Double td2mC,
      Double windSpeed,
      Double windDirFrom,
      Double psfcHpa)
      implements Serializable {}

  /**
   * Samples every message that holds a known variable, groups by valid time, and returns one row
   * per station per valid time. All messages must share one grid; a file that mixes grids is an
   * error, not something to sample silently with the wrong geometry.
   */
  public static List<Row> extract(List<Grib2Message> messages, Function<Grib2Message, float[]> unpack,
                                  List<Station> stations) {
    Map<LocalDateTime, Map<HrrrVariable, Grib2Message>> byTime = new LinkedHashMap<>();
    Map<Grib2Message, float[]> values = new LinkedHashMap<>();
    LambertConformal grid = null;
    for (Grib2Message m : messages) {
      HrrrVariable v = m.variable();
      if (v == null) {
        continue;  // a field this pipeline does not use
      }
      if (grid == null) {
        grid = m.grid();
      } else if (!sameGrid(grid, m.grid())) {
        throw new IllegalArgumentException("message at byte " + m.offset() + " is on a different grid");
      }
      Grib2Message prev = byTime.computeIfAbsent(m.validTime(), t -> new EnumMap<>(HrrrVariable.class)).put(v, m);
      if (prev != null) {
        throw new IllegalArgumentException("two " + v + " messages for " + m.validTime());
      }
      float[] data = unpack.apply(m);
      if (data.length != m.grid().nx * m.grid().ny) {
        throw new IllegalArgumentException(v + ": unpacked " + data.length + " values, grid has "
            + (m.grid().nx * m.grid().ny));
      }
      values.put(m, data);
    }
    if (grid == null) {
      return List.of();
    }

    List<Row> rows = new ArrayList<>();
    for (Map.Entry<LocalDateTime, Map<HrrrVariable, Grib2Message>> t : byTime.entrySet()) {
      Grib2Message any = t.getValue().values().iterator().next();
      for (Station st : stations) {
        int[] ij = grid.nearest(st.lat(), st.lon());
        if (ij == null) {
          continue;  // station is outside this grid (e.g. Fairbanks on CONUS HRRR)
        }
        int k = grid.flatIndex(ij[0], ij[1]);
        double[] ll = grid.latLon(ij[0], ij[1]);
        Map<HrrrVariable, Double> raw = new EnumMap<>(HrrrVariable.class);
        for (Map.Entry<HrrrVariable, Grib2Message> e : t.getValue().entrySet()) {
          float x = values.get(e.getValue())[k];
          raw.put(e.getKey(), Float.isNaN(x) ? null : (double) x);  // NaN = masked by bitmap
        }
        Double u = raw.get(HrrrVariable.UGRD_10M);
        Double v = raw.get(HrrrVariable.VGRD_10M);
        Double speed = null;
        Double dirFrom = null;
        if (u != null && v != null) {
          // rotate at the grid point's longitude: that is where the vector was defined
          double[] uv = grid.toEarthRelative(u, v, ll[1]);
          speed = Math.hypot(uv[0], uv[1]);
          dirFrom = directionFrom(uv[0], uv[1]);
        }
        rows.add(new Row(st.id(), t.getKey(), any.referenceTime(), any.forecastTime(),
            ij[0], ij[1], ll[0], ll[1], grid.distanceKm(st.lat(), st.lon(), ll[0], ll[1]),
            raw, kelvinToC(raw.get(HrrrVariable.TMP_2M)), kelvinToC(raw.get(HrrrVariable.DPT_2M)),
            speed, dirFrom, raw.get(HrrrVariable.PRES_SFC) == null ? null : raw.get(HrrrVariable.PRES_SFC) / 100.0));
      }
    }
    return rows;
  }

  /** Meteorological direction the wind blows FROM, degrees clockwise from north, [0, 360). */
  public static Double directionFrom(double uEast, double vNorth) {
    if (uEast == 0.0 && vNorth == 0.0) {
      return null;  // calm: direction undefined
    }
    double d = Math.toDegrees(Math.atan2(-uEast, -vNorth));
    return d < 0 ? d + 360.0 : d;
  }

  static Double kelvinToC(Double k) {
    return k == null ? null : k - 273.15;
  }

  static boolean sameGrid(LambertConformal a, LambertConformal b) {
    return a.nx == b.nx && a.ny == b.ny && a.la1 == b.la1 && a.lo1 == b.lo1 && a.lov == b.lov
        && a.latin1 == b.latin1 && a.latin2 == b.latin2 && a.dx == b.dx && a.dy == b.dy
        && a.earthRadius == b.earthRadius && a.uvRelativeToGrid == b.uvRelativeToGrid;
  }
}
