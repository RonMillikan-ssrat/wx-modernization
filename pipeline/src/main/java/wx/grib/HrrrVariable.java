package wx.grib;

/**
 * The HRRR surface fields the station pipeline uses, identified the way GRIB2 identifies them:
 * discipline, parameter category and number (WMO Code Table 4.2) and the fixed surface (Table 4.5).
 * The {@code idxName} and {@code idxLevel} are how the same fields appear in NCEP's .idx inventory
 * files, which the fetch script uses to download only these messages.
 */
public enum HrrrVariable {
  TMP_2M(0, 0, 0, 103, 2.0, "TMP", "2 m above ground", "K"),
  DPT_2M(0, 0, 6, 103, 2.0, "DPT", "2 m above ground", "K"),
  UGRD_10M(0, 2, 2, 103, 10.0, "UGRD", "10 m above ground", "m s-1"),
  VGRD_10M(0, 2, 3, 103, 10.0, "VGRD", "10 m above ground", "m s-1"),
  PRES_SFC(0, 3, 0, 1, Double.NaN, "PRES", "surface", "Pa");

  public final int discipline;
  public final int category;
  public final int number;
  public final int surfaceType;
  public final double surfaceValue;
  public final String idxName;
  public final String idxLevel;
  public final String units;

  HrrrVariable(int discipline, int category, int number, int surfaceType, double surfaceValue,
               String idxName, String idxLevel, String units) {
    this.discipline = discipline;
    this.category = category;
    this.number = number;
    this.surfaceType = surfaceType;
    this.surfaceValue = surfaceValue;
    this.idxName = idxName;
    this.idxLevel = idxLevel;
    this.units = units;
  }

  static HrrrVariable match(int discipline, int category, int number, int surfaceType,
                            double surfaceValue) {
    for (HrrrVariable v : values()) {
      if (v.discipline == discipline && v.category == category && v.number == number
          && v.surfaceType == surfaceType
          && (Double.isNaN(v.surfaceValue) || Math.abs(v.surfaceValue - surfaceValue) < 1e-6)) {
        return v;
      }
    }
    return null;
  }
}
