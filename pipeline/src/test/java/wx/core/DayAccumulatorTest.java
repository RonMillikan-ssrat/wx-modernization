package wx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DayAccumulatorTest {

  private static List<ObsRecord> day(int n, long seed) {
    Random r = new Random(seed);
    List<ObsRecord> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      int temp = i % 7 == 3 ? ObsRecord.MISSING : -250 + r.nextInt(500);
      int prcp = i % 5 == 2 ? ObsRecord.MISSING : r.nextInt(20);
      out.add(new ObsRecord(i, "KIAH01", "20280227", String.format("%02d", i % 24),
          temp, temp - 30, 9950 + r.nextInt(400), r.nextInt(361), r.nextInt(400), prcp, " "));
    }
    return out;
  }

  private static DayAccumulator sequential(List<ObsRecord> recs) {
    DayAccumulator a = new DayAccumulator();
    recs.forEach(a::add);
    return a;
  }

  @Test
  void missingSentinelIsExcludedFromTemperatureAndPrecip() {
    DayAccumulator a = new DayAccumulator();
    a.add(new ObsRecord(0, "S", "D", "00", ObsRecord.MISSING, 0, 10000, 0, 10, ObsRecord.MISSING, " "));
    a.add(new ObsRecord(1, "S", "D", "01", -50, 0, 10010, 0, 30, 4, " "));
    assertEquals(2L, a.nobs());
    assertEquals(1L, a.ntemp());
    assertEquals(-50, a.tmin());
    assertEquals(-50, a.tmax());
    assertEquals(1L, a.nprcp());
    assertEquals(4L, a.prcpSum());
    assertEquals(30, a.wmax());
  }

  /**
   * The property Beam relies on: any arrival order and any split into partial accumulators,
   * merged in any order, gives the same totals as one sequential pass.
   */
  @Test
  void orderAndPartitioningDoNotChangeTheResult() {
    List<ObsRecord> recs = day(24, 42);
    DayAccumulator expected = sequential(recs);
    Random r = new Random(7);
    for (int trial = 0; trial < 200; trial++) {
      List<ObsRecord> shuffled = new ArrayList<>(recs);
      Collections.shuffle(shuffled, r);
      int parts = 1 + r.nextInt(6);
      List<DayAccumulator> partials = new ArrayList<>();
      for (int p = 0; p < parts; p++) {
        partials.add(new DayAccumulator());
      }
      for (ObsRecord o : shuffled) {
        partials.get(r.nextInt(parts)).add(o);
      }
      Collections.shuffle(partials, r);
      DayAccumulator merged = new DayAccumulator();
      partials.forEach(merged::merge);
      assertTrue(expected.sameTotals(merged), "trial " + trial + " with " + parts + " partitions");
    }
  }

  @Test
  void mergingAnEmptyPartialChangesNothing() {
    DayAccumulator a = sequential(day(10, 1));
    DayAccumulator b = sequential(day(10, 1)).merge(new DayAccumulator());
    DayAccumulator c = new DayAccumulator().merge(sequential(day(10, 1)));
    assertTrue(a.sameTotals(b));
    assertTrue(a.sameTotals(c));
  }

  @Test
  void allMissingPartialDoesNotPolluteMinMax() {
    DayAccumulator missingOnly = new DayAccumulator()
        .add(new ObsRecord(0, "S", "D", "00", ObsRecord.MISSING, 0, 10000, 0, 0, 0, " "));
    DayAccumulator real = new DayAccumulator()
        .add(new ObsRecord(1, "S", "D", "01", 75, 0, 10000, 0, 0, 0, " "));
    DayAccumulator merged = new DayAccumulator().merge(missingOnly).merge(real);
    assertEquals(75, merged.tmin());
    assertEquals(75, merged.tmax());
  }
}
