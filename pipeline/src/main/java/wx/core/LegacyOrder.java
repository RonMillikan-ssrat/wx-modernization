package wx.core;

import java.util.Arrays;
import java.util.Comparator;

/**
 * The mainframe's sort order: station then date, compared as EBCDIC bytes.
 *
 * <p>In EBCDIC letters (0xC1-0xE9) sort before digits (0xF0-0xF9); in ASCII and UTF-8 digits
 * (0x30-0x39) sort first. So "KIAH01" sorts before "72243A" on the mainframe and after it in
 * BigQuery, Java String.compareTo, or a Unix sort. Use this comparator wherever the business needs
 * output in the same order as the legacy report.
 */
public final class LegacyOrder {

  private LegacyOrder() {}

  public static final Comparator<DaySummary> SUMMARY =
      Comparator.<DaySummary, byte[]>comparing(s -> ebcdic(s.station()), Arrays::compareUnsigned)
          .thenComparing(s -> ebcdic(s.date()), Arrays::compareUnsigned);

  public static byte[] ebcdic(String s) {
    return s.getBytes(CopybookCodec.CP037);
  }
}
