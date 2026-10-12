package wx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.Charset;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class CopybookCodecTest {

  private static byte[] hex(String h) {
    return HexFormat.of().parseHex(h);
  }

  @Test
  void comp3PositiveNegativeZero() {
    assertEquals(215L, CopybookCodec.comp3(hex("00215C"), 0, 3));
    assertEquals(-185L, CopybookCodec.comp3(hex("00185D"), 0, 3));
    assertEquals(0L, CopybookCodec.comp3(hex("00000C"), 0, 3));
    assertEquals(-9999L, CopybookCodec.comp3(hex("09999D"), 0, 3));
  }

  @Test
  void comp3UnsignedFSignIsPositive() {
    // Trap: KSGR-1 pressure is packed with an F sign nibble every 6th hour.
    assertEquals(10130L, CopybookCodec.comp3(hex("10130F"), 0, 3));
  }

  @Test
  void comp3AlternateSignNibbles() {
    assertEquals(-42L, CopybookCodec.comp3(hex("00042B"), 0, 3));
    assertEquals(42L, CopybookCodec.comp3(hex("00042A"), 0, 3));
    assertEquals(42L, CopybookCodec.comp3(hex("00042E"), 0, 3));
  }

  @Test
  void comp3RejectsBadNibbles() {
    assertThrows(IllegalArgumentException.class, () -> CopybookCodec.comp3(hex("00A15C"), 0, 3));
    assertThrows(IllegalArgumentException.class, () -> CopybookCodec.comp3(hex("002159"), 0, 3));
    // A field that was wrongly translated as text: EBCDIC "  " (0x40) becomes ASCII 0x20.
    assertThrows(IllegalArgumentException.class, () -> CopybookCodec.comp3(hex("404040"), 0, 3));
  }

  @Test
  void compIsBigEndian() {
    // Trap: wind direction 258 = 0x0102. Read little-endian it would be 0x0201 = 513 (invalid).
    assertEquals(258, CopybookCodec.compUnsignedBE(hex("0102"), 0, 2));
    assertEquals(65535, CopybookCodec.compUnsignedBE(hex("FFFF"), 0, 2));
  }

  @Test
  void displayDecodesCp037() {
    assertEquals("72243A", CopybookCodec.display(hex("F7F2F2F4F3C1"), 0, 6));
    assertEquals("KSGR-1", CopybookCodec.display(hex("D2E2C7D960F1"), 0, 6));
    assertEquals("  ", CopybookCodec.display(hex("4040"), 0, 2));
  }

  @Test
  void codePagesDisagreeOnBrackets() {
    // Trap: CP037 and CP1047 differ. 0xBA is '[' in CP037 but not in CP1047.
    byte[] b = hex("BA");
    assertEquals("[", new String(b, CopybookCodec.CP037));
    assertNotEquals("[", new String(b, Charset.forName("IBM1047")));
  }

  @Test
  void decodesOffsetsWithinARecord() {
    byte[] rec = Recs.record("KIAH01", "20280229", "05", -12, -40, 10132, 258, 145, 13, " ");
    ObsRecord o = WxobsLayout.decode(rec, 7);
    assertEquals("KIAH01", o.station());
    assertEquals("20280229", o.date());
    assertEquals("05", o.hour());
    assertEquals(-12, o.temp());
    assertEquals(-40, o.dewpt());
    assertEquals(10132, o.press());
    assertEquals(258, o.wdir());
    assertEquals(145, o.wspd());
    assertEquals(13, o.prcp());
    assertEquals(" ", o.qc());
    assertEquals(7L, o.position());
  }

  @Test
  void wrongLengthRecordIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> WxobsLayout.decode(new byte[39], 0));
  }
}
