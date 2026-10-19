package wx.grib;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import ucar.nc2.grib.grib2.Grib2Record;
import ucar.nc2.grib.grib2.Grib2RecordScanner;
import ucar.unidata.io.RandomAccessFile;

/**
 * Unpacks GRIB2 data sections with netCDF-Java (Unidata), matched one-to-one with the messages
 * {@link Grib2Scanner} found. Unpacking complex packing with spatial differencing (template 5.3,
 * what HRRR uses) is exactly the kind of code not to write yourself, so this is the one place the
 * pipeline depends on a GRIB library.
 *
 * <p>netCDF-Java reads through its own RandomAccessFile, so the bytes are written to a temporary
 * file first. Use with try-with-resources so the file is closed and deleted.
 */
public final class NetcdfUnpacker implements AutoCloseable {

  private final Path tmp;
  private final RandomAccessFile raf;
  private final Map<Grib2Message, Grib2Record> records = new IdentityHashMap<>();

  public NetcdfUnpacker(byte[] data, List<Grib2Message> messages) throws IOException {
    this.tmp = Files.createTempFile("wx-grib-", ".grib2");
    Files.write(tmp, data);
    this.raf = new RandomAccessFile(tmp.toString(), "r");
    List<Grib2Record> found = new ArrayList<>();
    Grib2RecordScanner scanner = new Grib2RecordScanner(raf);
    while (scanner.hasNext()) {
      found.add(scanner.next());
    }
    if (found.size() != messages.size()) {
      close();
      throw new IOException("netCDF-Java found " + found.size() + " GRIB2 records, the scanner found "
          + messages.size() + "; refusing to pair them up");
    }
    for (int k = 0; k < found.size(); k++) {
      records.put(messages.get(k), found.get(k));
    }
  }

  /** All grid values of {@code m}, scanning order 0x40 (i fastest, south row first), NaN if masked. */
  public float[] unpack(Grib2Message m) {
    Grib2Record r = records.get(m);
    if (r == null) {
      throw new IllegalArgumentException("message at byte " + m.offset() + " was not in this file");
    }
    try {
      return r.readData(raf);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void close() throws IOException {
    try {
      raf.close();
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
