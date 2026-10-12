package wx.core;

import java.io.Serializable;

/**
 * Running totals for one station-day: the Beam replacement for WS-ACCUM in WXSUMM.
 *
 * <p>Beam may add records in any order and on any worker, then merge partial accumulators in any
 * grouping. So {@link #add} and {@link #merge} must be commutative and associative: the result
 * must not depend on order or partitioning. Counts, sums, min and max all qualify. Logic such as
 * "first record of the day" or "carry the last value forward" would not, and could not live here.
 *
 * <p>{@code minPos} and {@code maxPos} are the file positions of the first and last accepted record
 * for the key, kept so any summary row can be traced back to the input records it came from.
 */
public final class DayAccumulator implements Serializable {

  long nobs;
  long ntemp;
  long tsum;
  int tmin;
  int tmax;
  long npres;
  long psum;
  long nprcp;
  long prcpSum;
  int wmax;
  long minPos = Long.MAX_VALUE;
  long maxPos = Long.MIN_VALUE;

  /** Mirrors paragraph 4000-ACCUMULATE. */
  public DayAccumulator add(ObsRecord o) {
    nobs++;
    if (o.temp() != ObsRecord.MISSING) {
      tmin = ntemp == 0 ? o.temp() : Math.min(tmin, o.temp());
      tmax = ntemp == 0 ? o.temp() : Math.max(tmax, o.temp());
      ntemp++;
      tsum += o.temp();
    }
    npres++;
    psum += o.press();
    if (o.prcp() != ObsRecord.MISSING) {
      nprcp++;
      prcpSum += o.prcp();
    }
    wmax = Math.max(wmax, o.wspd());
    minPos = Math.min(minPos, o.position());
    maxPos = Math.max(maxPos, o.position());
    return this;
  }

  /** Combines another partial result into this one. No COBOL equivalent: it never split the work. */
  public DayAccumulator merge(DayAccumulator other) {
    if (other.ntemp > 0) {
      tmin = ntemp == 0 ? other.tmin : Math.min(tmin, other.tmin);
      tmax = ntemp == 0 ? other.tmax : Math.max(tmax, other.tmax);
    }
    nobs += other.nobs;
    ntemp += other.ntemp;
    tsum += other.tsum;
    npres += other.npres;
    psum += other.psum;
    nprcp += other.nprcp;
    prcpSum += other.prcpSum;
    wmax = Math.max(wmax, other.wmax);
    minPos = Math.min(minPos, other.minPos);
    maxPos = Math.max(maxPos, other.maxPos);
    return this;
  }

  public long nobs() { return nobs; }
  public long ntemp() { return ntemp; }
  public long tsum() { return tsum; }
  public int tmin() { return tmin; }
  public int tmax() { return tmax; }
  public long npres() { return npres; }
  public long psum() { return psum; }
  public long nprcp() { return nprcp; }
  public long prcpSum() { return prcpSum; }
  public int wmax() { return wmax; }
  public long minPos() { return minPos; }
  public long maxPos() { return maxPos; }

  /** Value equality on the summary fields, used by tests to compare different merge orders. */
  public boolean sameTotals(DayAccumulator o) {
    return nobs == o.nobs && ntemp == o.ntemp && tsum == o.tsum
        && (ntemp == 0 || (tmin == o.tmin && tmax == o.tmax))
        && npres == o.npres && psum == o.psum && nprcp == o.nprcp
        && prcpSum == o.prcpSum && wmax == o.wmax
        && minPos == o.minPos && maxPos == o.maxPos;
  }
}
