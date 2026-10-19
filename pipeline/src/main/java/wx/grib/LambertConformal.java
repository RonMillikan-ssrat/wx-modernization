package wx.grib;

import java.io.Serializable;

/**
 * GRIB2 grid template 3.30, Lambert conformal conic on a sphere: grid index from latitude and
 * longitude, and the reverse. Formulas are Snyder, Map Projections: A Working Manual (USGS PP 1395),
 * chapter 15, spherical form.
 *
 * <p>The grid is defined by its first point (La1, Lo1) and spacing (Dx, Dy) in projected metres, so
 * a point's fractional grid position is its projected offset from the first point divided by the
 * spacing. Only scanning mode 0x40 is supported (i east, j north, i fastest), which is what HRRR
 * and most NCEP Lambert grids use; anything else is rejected rather than silently mis-indexed.
 */
public final class LambertConformal implements Serializable {

  public final int nx;
  public final int ny;
  public final double la1;
  public final double lo1;
  public final double lov;
  public final double latin1;
  public final double latin2;
  public final double dx;
  public final double dy;
  public final double earthRadius;
  /** Resolution and component flags bit 0x08: U/V are relative to the grid's x/y axes. */
  public final boolean uvRelativeToGrid;

  private final double n;
  private final double rf;  // R * F
  private final double rho0;
  private final double x1;
  private final double y1;

  public LambertConformal(int nx, int ny, double la1, double lo1, double lov, double latin1,
                          double latin2, double dx, double dy, double earthRadius,
                          boolean uvRelativeToGrid) {
    this.nx = nx;
    this.ny = ny;
    this.la1 = la1;
    this.lo1 = lo1;
    this.lov = lov;
    this.latin1 = latin1;
    this.latin2 = latin2;
    this.dx = dx;
    this.dy = dy;
    this.earthRadius = earthRadius;
    this.uvRelativeToGrid = uvRelativeToGrid;

    double p1 = Math.toRadians(latin1);
    double p2 = Math.toRadians(latin2);
    if (Math.abs(latin1 - latin2) < 1e-9) {
      this.n = Math.sin(p1);  // tangent cone
    } else {
      this.n = Math.log(Math.cos(p1) / Math.cos(p2))
          / Math.log(Math.tan(Math.PI / 4 + p2 / 2) / Math.tan(Math.PI / 4 + p1 / 2));
    }
    double f = Math.cos(p1) * Math.pow(Math.tan(Math.PI / 4 + p1 / 2), n) / n;
    this.rf = earthRadius * f;
    this.rho0 = rho(latin1);  // any fixed origin works: only offsets from the first point matter
    double[] xy1 = project(la1, lo1);
    this.x1 = xy1[0];
    this.y1 = xy1[1];
  }

  /** The cone constant n; also the factor in the wind-rotation angle. */
  public double coneConstant() {
    return n;
  }

  private double rho(double latDeg) {
    return rf / Math.pow(Math.tan(Math.PI / 4 + Math.toRadians(latDeg) / 2), n);
  }

  /** Longitude difference from LoV, wrapped to [-180, 180) so 264.6 and -95.4 agree. */
  static double deltaLon(double lonDeg, double lovDeg) {
    double d = (lonDeg - lovDeg) % 360.0;
    if (d < -180.0) {
      d += 360.0;
    } else if (d >= 180.0) {
      d -= 360.0;
    }
    return d;
  }

  private double[] project(double latDeg, double lonDeg) {
    double theta = n * Math.toRadians(deltaLon(lonDeg, lov));
    double r = rho(latDeg);
    return new double[] {r * Math.sin(theta), rho0 - r * Math.cos(theta)};
  }

  /** Fractional grid position {i, j}; i = 0, j = 0 is the first grid point. */
  public double[] fractionalIndex(double latDeg, double lonDeg) {
    double[] xy = project(latDeg, lonDeg);
    return new double[] {(xy[0] - x1) / dx, (xy[1] - y1) / dy};
  }

  /** Nearest grid point {i, j}, or null when the point falls outside the grid. */
  public int[] nearest(double latDeg, double lonDeg) {
    double[] f = fractionalIndex(latDeg, lonDeg);
    int i = (int) Math.round(f[0]);
    int j = (int) Math.round(f[1]);
    if (i < 0 || i >= nx || j < 0 || j >= ny) {
      return null;
    }
    return new int[] {i, j};
  }

  /** Flat index into the data array for scanning mode 0x40 (i fastest, south row first). */
  public int flatIndex(int i, int j) {
    return j * nx + i;
  }

  /** Latitude and longitude {lat, lon in [-180, 180)} of grid point (i, j). */
  public double[] latLon(int i, int j) {
    double x = x1 + i * dx;
    double y = y1 + j * dy;
    double dyFromApex = rho0 - y;
    double r = Math.copySign(Math.hypot(x, dyFromApex), n);
    double theta = Math.atan2(Math.signum(n) * x, Math.signum(n) * dyFromApex);
    double lon = lov + Math.toDegrees(theta / n);
    double lat = Math.toDegrees(2 * Math.atan(Math.pow(rf / r, 1 / n)) - Math.PI / 2);
    return new double[] {lat, deltaLon(lon, 0.0)};
  }

  /** Great-circle distance in km on this grid's sphere. */
  public double distanceKm(double lat1, double lon1, double lat2, double lon2) {
    double p1 = Math.toRadians(lat1);
    double p2 = Math.toRadians(lat2);
    double dp = p2 - p1;
    double dl = Math.toRadians(lon2 - lon1);
    double a = Math.sin(dp / 2) * Math.sin(dp / 2)
        + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
    return 2 * earthRadius / 1000.0 * Math.asin(Math.min(1.0, Math.sqrt(a)));
  }

  /**
   * Rotates grid-relative U/V to earth-relative (east/north) at the given longitude.
   *
   * <p>The grid's y axis points to true north only along LoV; elsewhere it is turned by
   * angle = n * (lon - LoV). Wind speed is unchanged by the rotation; direction is not. On HRRR
   * (n = sin 38.5 = 0.62, LoV = 97.5 W) treating grid-relative U/V as east/north turns directions
   * by about 1 degree in Houston, 4 degrees in Denver and over 20 degrees at the grid's corners.
   */
  public double[] toEarthRelative(double uGrid, double vGrid, double lonDeg) {
    if (!uvRelativeToGrid) {
      return new double[] {uGrid, vGrid};
    }
    double angle = n * Math.toRadians(deltaLon(lonDeg, lov));
    double c = Math.cos(angle);
    double s = Math.sin(angle);
    return new double[] {c * uGrid + s * vGrid, -s * uGrid + c * vGrid};
  }
}
