package wx.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single-process run of the Phase 2 logic that writes the legacy report format.
 *
 * <p>It groups exactly as the Beam pipeline does (every record with a key, wherever it sits), then
 * sorts the rows in EBCDIC order and adds the legacy header and trailer. On input with no split keys
 * the result must be byte-identical to WXSUMM's output, which makes this a fast oracle check for the
 * core logic with no Beam or cloud dependency.
 *
 * <p>Usage: {@code LegacyReport OBSIN SUMOUT ERROUT}
 */
public final class LegacyReport {

  public static final String HEADER =
      "STATN  DATE     NOB NTP   TMIN   TMAX  TMEAN   PRCP  WMAX  PMEAN";

  /** Everything one run produces. */
  public record Result(List<String> summaryLines, List<String> errorLines, List<String> splitWarnings,
                       long read, long accepted, long rejected) {}

  private LegacyReport() {}

  public static Result run(byte[] data) {
    Map<String, DayAccumulator> groups = new LinkedHashMap<>();
    Map<String, String[]> keyParts = new LinkedHashMap<>();
    List<String> errors = new ArrayList<>();
    SplitKeyDetector tracker = new SplitKeyDetector();
    Map<String, Long> runsPerKey = new LinkedHashMap<>();
    long read = 0, accepted = 0, rejected = 0;

    int whole = data.length - data.length % WxobsLayout.LRECL;
    for (int off = 0; off < whole; off += WxobsLayout.LRECL) {
      long pos = off / WxobsLayout.LRECL;
      read++;
      byte[] rec = java.util.Arrays.copyOfRange(data, off, off + WxobsLayout.LRECL);
      Validator.Reject reject;
      ObsRecord o = null;
      try {
        o = WxobsLayout.decode(rec, pos);
        reject = Validator.validate(o);
      } catch (IllegalArgumentException e) {
        reject = Validator.undecodable(pos, e.getMessage());
      }
      if (reject != null) {
        rejected++;
        errors.add(reject.errorLine());
        continue;
      }
      accepted++;
      if (tracker.startsNewRun(o.key())) {
        runsPerKey.merge(o.key(), 1L, Long::sum);
      }
      groups.computeIfAbsent(o.key(), k -> new DayAccumulator()).add(o);
      keyParts.putIfAbsent(o.key(), new String[] {o.station(), o.date()});
    }

    List<DaySummary> rows = new ArrayList<>();
    groups.forEach((key, acc) -> {
      String[] kp = keyParts.get(key);
      rows.add(DaySummary.of(kp[0], kp[1], acc));
    });
    rows.sort(LegacyOrder.SUMMARY);

    List<String> lines = new ArrayList<>();
    lines.add(HEADER);
    rows.forEach(r -> lines.add(r.legacyLine()));
    lines.add(trailer(read, accepted, rejected, rows.size()));
    return new Result(lines, errors, SplitKeyDetector.warnings(runsPerKey), read, accepted, rejected);
  }

  public static String trailer(long read, long accepted, long rejected, long groups) {
    return String.format("*TOTAL READ=%07d ACCEPTED=%07d REJECTED=%07d GROUPS=%07d",
        read, accepted, rejected, groups);
  }

  public static void main(String[] args) throws IOException {
    if (args.length != 3) {
      System.err.println("usage: LegacyReport OBSIN SUMOUT ERROUT");
      System.exit(2);
    }
    Result r = run(Files.readAllBytes(Path.of(args[0])));
    write(Path.of(args[1]), r.summaryLines());
    write(Path.of(args[2]), r.errorLines());
    System.out.printf("LegacyReport: READ=%d ACCEPTED=%d REJECTED=%d GROUPS=%d%n",
        r.read(), r.accepted(), r.rejected(), r.summaryLines().size() - 2);
    r.splitWarnings().forEach(w -> System.out.println("WARNING " + w));
  }

  private static void write(Path p, List<String> lines) throws IOException {
    StringBuilder sb = new StringBuilder();
    lines.forEach(l -> sb.append(l).append('\n'));
    Files.writeString(p, sb.toString(), StandardCharsets.US_ASCII);
  }
}
