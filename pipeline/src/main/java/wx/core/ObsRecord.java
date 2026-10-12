package wx.core;

import java.io.Serializable;

/**
 * One decoded WXOBS observation.
 *
 * <p>{@code position} is the zero-based record number in the input file. COBOL never needed it
 * because it read the file in order; Beam does not preserve order, so it is carried explicitly so
 * results and rejects can be traced back to the input record.
 */
public record ObsRecord(
    long position,
    String station,
    String date,
    String hour,
    int temp,
    int dewpt,
    int press,
    int wdir,
    int wspd,
    int prcp,
    String qc)
    implements Serializable {

  /** Missing-value sentinel used by TEMP, DEWPT and PRCP. */
  public static final int MISSING = -9999;

  /** Grouping key: one summary row per station and day. */
  public String key() {
    return station + "|" + date;
  }
}
