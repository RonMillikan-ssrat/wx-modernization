package wx.grib;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Metadata for one GRIB2 message, read directly from its sections. The data values are unpacked
 * elsewhere (netCDF-Java in the pipeline); everything needed to interpret them is here.
 *
 * @param offset            byte offset of "GRIB" in the file
 * @param length            total message length (Section 0)
 * @param discipline        Section 0 octet 7 (0 = meteorological)
 * @param referenceTime     Section 1 reference time, UTC
 * @param category          Section 4 parameter category
 * @param number            Section 4 parameter number
 * @param surfaceType       Section 4 type of first fixed surface (103 = height above ground, 1 = surface)
 * @param surfaceValue      first fixed surface value in its units (metres for 103), NaN if missing
 * @param forecastTime      forecast time in {@code timeUnit} units
 * @param timeUnit          Section 4 indicator of unit of time range (1 = hour)
 * @param grid              Lambert conformal grid (template 3.30)
 * @param drsTemplate       Section 5 data representation template (0 simple, 3 complex + spatial differencing)
 * @param referenceValue    Section 5 R
 * @param binaryScale       Section 5 E
 * @param decimalScale      Section 5 D
 * @param bitsPerValue      Section 5 number of bits
 */
public record Grib2Message(
    long offset,
    long length,
    int discipline,
    LocalDateTime referenceTime,
    int category,
    int number,
    int surfaceType,
    double surfaceValue,
    long forecastTime,
    int timeUnit,
    LambertConformal grid,
    int drsTemplate,
    float referenceValue,
    int binaryScale,
    int decimalScale,
    int bitsPerValue)
    implements Serializable {

  /** Valid time = reference time + forecast time (hours only; other units are rejected on parse). */
  public LocalDateTime validTime() {
    return referenceTime.plusHours(forecastTime);
  }

  /**
   * Size of one packing step: values are stored as integers X with Y = (R + X * 2^E) / 10^D, so
   * two values that differ by less than this were indistinguishable in the file. Parity between
   * two decoders is judged against this, not against an arbitrary epsilon.
   */
  public double packingStep() {
    return Math.scalb(1.0, binaryScale) / Math.pow(10, decimalScale);
  }

  /** The variable this message holds, or null if the pipeline does not use it. */
  public HrrrVariable variable() {
    return HrrrVariable.match(discipline, category, number, surfaceType, surfaceValue);
  }
}
