package wx.grib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the eccodes-generated fixtures in src/test/resources/grib (see phase3/make_fixtures.py). */
final class Fixtures {

  static final Path DIR = Path.of("src/test/resources/grib");

  /** The HRRR CONUS grid exactly as eccodes wrote it for hrrr_nearest.csv. */
  static final LambertConformal HRRR = new LambertConformal(
      1799, 1059, 21.138, 237.28, 262.5, 38.5, 38.5, 3000.0, 3000.0, 6_371_229.0, true);

  private Fixtures() {}

  static byte[] smallGrib() throws IOException {
    return Files.readAllBytes(DIR.resolve("small.grib2"));
  }

  /** CSV with a header row and no quoted fields (the fixtures contain none). */
  static List<Map<String, String>> csv(String name) throws IOException {
    List<String> lines = Files.readAllLines(DIR.resolve(name));
    String[] head = lines.get(0).split(",", -1);
    List<Map<String, String>> rows = new ArrayList<>();
    for (String line : lines.subList(1, lines.size())) {
      if (line.isBlank()) {
        continue;
      }
      String[] f = line.split(",", -1);
      Map<String, String> row = new LinkedHashMap<>();
      for (int k = 0; k < head.length; k++) {
        row.put(head[k], f[k]);
      }
      rows.add(row);
    }
    return rows;
  }

  /** Longitude difference wrapped to [-180, 180). */
  static double lonDiff(double a, double b) {
    return LambertConformal.deltaLon(a, b);
  }
}
