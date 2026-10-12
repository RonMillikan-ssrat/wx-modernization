package wx.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Finds keys the legacy control break would have reported more than once.
 *
 * <p>WXSUMM starts a new group whenever the key of an accepted record differs from the key of the
 * previous accepted record (rejected records are skipped and do not close a group). A key that
 * appears in N separate runs therefore produces N partial summary lines in the legacy output, while
 * Beam's grouping produces one complete line. This class reproduces that run tracking exactly, so
 * the difference is reported instead of passing silently.
 *
 * <p>It must see accepted records in file order. The pipeline guarantees that by reading each file
 * sequentially in a single step, so the tracker runs there, before the data is shuffled.
 */
public final class SplitKeyDetector {

  private String previousKey;

  /** Feed accepted records in file order. Returns true when this record starts a new legacy group. */
  public boolean startsNewRun(String key) {
    boolean newRun = !key.equals(previousKey);
    previousKey = key;
    return newRun;
  }

  /** One warning per key that appears in more than one run. */
  public static List<String> warnings(Map<String, Long> runsPerKey) {
    List<String> out = new ArrayList<>();
    new TreeMap<>(runsPerKey).forEach((key, runs) -> {
      if (runs > 1) {
        out.add(warning(key, runs));
      }
    });
    return out;
  }

  public static String warning(String key, long runs) {
    return String.format(
        "SPLIT KEY: %s appears in %d separate runs in the input; the legacy control break "
            + "would write %d partial groups, this pipeline writes 1", key, runs, runs);
  }
}
