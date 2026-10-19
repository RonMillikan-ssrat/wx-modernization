package wx.grib;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** A point to sample: airport reference point, decimal degrees, longitude in [-180, 180). */
public record Station(String id, double lat, double lon) implements Serializable {

  /**
   * Houston-area airports plus Denver for contrast (high elevation, far from LoV). Coordinates are
   * the AirNav (KIAH, KHOU, KSGR) and SkyVector (KDEN) airport reference points.
   */
  public static final List<Station> DEFAULT = List.of(
      new Station("KIAH", 29.9844353, -95.3414425),
      new Station("KHOU", 29.6457998, -95.2772316),
      new Station("KSGR", 29.6222486, -95.6565342),
      new Station("KDEN", 39.8616667, -104.6731667));

  /** Parses "ID:lat:lon,ID:lat:lon". Empty or null gives {@link #DEFAULT}. */
  public static List<Station> parse(String spec) {
    if (spec == null || spec.isBlank()) {
      return DEFAULT;
    }
    List<Station> out = new ArrayList<>();
    for (String part : spec.split(",")) {
      String[] f = part.trim().split(":");
      if (f.length != 3) {
        throw new IllegalArgumentException("station '" + part + "' is not ID:lat:lon");
      }
      out.add(new Station(f[0], Double.parseDouble(f[1]), Double.parseDouble(f[2])));
    }
    return out;
  }
}
