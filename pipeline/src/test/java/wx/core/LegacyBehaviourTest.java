package wx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ordering, split keys, and the end-to-end golden-file check against WXSUMM's own output. */
class LegacyBehaviourTest {

  private static final Path GOLDEN = Path.of("src/test/resources/golden");

  private static DaySummary row(String station) {
    DayAccumulator a = new DayAccumulator()
        .add(new ObsRecord(0, station, "20280227", "00", 100, 0, 10130, 0, 0, 0, " "));
    return DaySummary.of(station, "20280227", a);
  }

  @Test
  void ebcdicOrderPutsLettersBeforeDigits() {
    List<DaySummary> rows = new ArrayList<>(List.of(row("72243A"), row("KIAH01"), row("KHOU02")));
    rows.sort(LegacyOrder.SUMMARY);
    assertEquals(List.of("KHOU02", "KIAH01", "72243A"), rows.stream().map(DaySummary::station).toList());
  }

  @Test
  void asciiOrderIsTheOpposite() {
    List<String> ids = new ArrayList<>(List.of("KIAH01", "72243A"));
    ids.sort(String::compareTo);
    assertEquals(List.of("72243A", "KIAH01"), ids);
  }

  @Test
  void trackerCountsRunsLikeTheControlBreak() {
    SplitKeyDetector t = new SplitKeyDetector();
    assertTrue(t.startsNewRun("A"));
    assertTrue(!t.startsNewRun("A"));
    assertTrue(t.startsNewRun("B"));
    assertTrue(t.startsNewRun("A"));
    assertTrue(SplitKeyDetector.warnings(java.util.Map.of("A", 1L, "B", 1L)).isEmpty());
    assertEquals(1, SplitKeyDetector.warnings(java.util.Map.of("A", 2L, "B", 1L)).size());
  }

  @Test
  void appendedLateRecordsAreFlagged() {
    // The worked example: KIAH01 hours 00-11, KHOU02 all day, then KIAH01 hours 12-23.
    List<byte[]> recs = new ArrayList<>();
    for (int h = 0; h < 12; h++) recs.add(Recs.obs("KIAH01", "20280227", h, 150));
    for (int h = 0; h < 24; h++) recs.add(Recs.obs("KHOU02", "20280227", h, 160));
    for (int h = 12; h < 24; h++) recs.add(Recs.obs("KIAH01", "20280227", h, 170));
    LegacyReport.Result r = LegacyReport.run(Recs.concat(recs.toArray(new byte[0][])));

    // Beam-style grouping: two rows, KIAH01 with all 24 observations.
    assertEquals(4, r.summaryLines().size(), "header + 2 rows + trailer");
    assertTrue(r.summaryLines().get(2).startsWith("KIAH01 20280227  24  24"));
    // ...and the difference from the legacy program is reported, not silent.
    // Only KIAH01 is split; KHOU02 sits inside KIAH01's span but is itself contiguous.
    assertEquals(List.of(SplitKeyDetector.warning("KIAH01|20280227", 2)), r.splitWarnings());
  }

  @Test
  void rejectedRecordBetweenRunsDoesNotSplitAKey() {
    // COBOL skips rejects without closing the group, so this is still one legacy group.
    byte[] data = Recs.concat(
        Recs.obs("KIAH01", "20280227", 0, 150),
        Recs.record("KHOU02", "20280227", "24", 150, 100, 10100, 90, 50, 0, " "),
        Recs.obs("KIAH01", "20280227", 1, 150));
    LegacyReport.Result r = LegacyReport.run(data);
    assertEquals(1L, r.rejected());
    assertTrue(r.splitWarnings().isEmpty());
  }

  @Test
  void goldenFileMatchesCobolByteForByte() throws IOException {
    LegacyReport.Result r = LegacyReport.run(Files.readAllBytes(GOLDEN.resolve("obs.ebc")));
    assertEquals(Files.readString(GOLDEN.resolve("summary.txt"), StandardCharsets.US_ASCII),
        String.join("\n", r.summaryLines()) + "\n");
    assertEquals(Files.readString(GOLDEN.resolve("errors.txt"), StandardCharsets.US_ASCII),
        String.join("\n", r.errorLines()) + "\n");
    assertTrue(r.splitWarnings().isEmpty());
  }
}
