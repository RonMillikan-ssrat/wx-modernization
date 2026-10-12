package wx.core;

/**
 * Field offsets for copybook WXOBS (LRECL 40). This class is the Java twin of cobol/WXOBS.cpy;
 * keep the two in step.
 *
 * <pre>
 * Offset Len  Field        Picture         Usage
 *   0     6   OBS-STATION  X(6)            DISPLAY
 *   6     8   OBS-DATE     9(8)            DISPLAY (zoned)
 *  14     2   OBS-HOUR     9(2)            DISPLAY (zoned)
 *  16     3   OBS-TEMP     S9(4)           COMP-3
 *  19     3   OBS-DEWPT    S9(4)           COMP-3
 *  22     3   OBS-PRESS    S9(5)           COMP-3
 *  25     2   OBS-WDIR     9(4)            COMP
 *  27     2   OBS-WSPD     9(4)            COMP
 *  29     3   OBS-PRCP     S9(5)           COMP-3
 *  32     1   OBS-QC       X               DISPLAY
 *  33     7   FILLER
 * </pre>
 */
public final class WxobsLayout {

  public static final int LRECL = 40;

  private WxobsLayout() {}

  /**
   * Decodes one record.
   *
   * @throws IllegalArgumentException if the record is the wrong length or a packed field is invalid
   */
  public static ObsRecord decode(byte[] rec, long position) {
    if (rec.length != LRECL) {
      throw new IllegalArgumentException("record " + position + " is " + rec.length + " bytes, expected " + LRECL);
    }
    return new ObsRecord(
        position,
        CopybookCodec.display(rec, 0, 6),
        CopybookCodec.display(rec, 6, 8),
        CopybookCodec.display(rec, 14, 2),
        (int) CopybookCodec.comp3(rec, 16, 3),
        (int) CopybookCodec.comp3(rec, 19, 3),
        (int) CopybookCodec.comp3(rec, 22, 3),
        CopybookCodec.compUnsignedBE(rec, 25, 2),
        CopybookCodec.compUnsignedBE(rec, 27, 2),
        (int) CopybookCodec.comp3(rec, 29, 3),
        CopybookCodec.display(rec, 32, 1));
  }
}
