package wx.core;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps results to BigQuery rows and dead-letter messages, with no BigQuery or Pub/Sub dependency so
 * it can be unit-tested. The pipeline copies each map into a TableRow.
 *
 * <p>Value types follow Beam's BigQueryIO conventions: INT64 as Long, NUMERIC and DATE as strings
 * ("1013.2", "2028-02-27"). NUMERIC keeps the values exact; FLOAT64 would reintroduce the binary
 * rounding that COBOL never had. A null NUMERIC is the BigQuery form of the legacy "N/A".
 *
 * <p>Every row carries {@code run_id}, so a rerun appends a new run instead of silently mixing
 * with an old one, and parity can be checked one run at a time.
 */
public final class Rows {

  private Rows() {}

  /** One row of {@code summary}; columns match pipeline/bq/summary_schema.json. */
  public static Map<String, Object> summary(DaySummary s, String runId) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("run_id", runId);
    row.put("station", s.station());
    row.put("obs_date", isoDate(s.date()));
    row.put("nobs", s.nobs());
    row.put("ntemp", s.ntemp());
    row.put("tmin", numeric(s.tmin()));
    row.put("tmax", numeric(s.tmax()));
    row.put("tmean", numeric(s.tmean()));
    row.put("prcp", numeric(s.prcp()));
    row.put("wmax", numeric(s.wmax()));
    row.put("pmean", numeric(s.pmean()));
    row.put("first_record", s.minPos());
    row.put("last_record", s.maxPos());
    return row;
  }

  /** One row of {@code errors}; columns match pipeline/bq/errors_schema.json. */
  public static Map<String, Object> error(Validator.Reject r, String runId) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("run_id", runId);
    row.put("record", r.position());
    row.put("code", r.code());
    row.put("reason", r.text());
    row.put("station", r.station());
    row.put("obs_date_raw", r.date());
    row.put("hour", r.hour());
    row.put("error_line", r.errorLine());
    return row;
  }

  /** YYYYMMDD to ISO YYYY-MM-DD. Only called for accepted records, whose date is already valid. */
  static String isoDate(String yyyymmdd) {
    return yyyymmdd.substring(0, 4) + "-" + yyyymmdd.substring(4, 6) + "-" + yyyymmdd.substring(6, 8);
  }

  private static String numeric(BigDecimal v) {
    return v == null ? null : v.toPlainString();
  }

  /** Minimal JSON object writer for the dead-letter message: strings, numbers, booleans, null. */
  public static String json(Map<String, Object> map) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Object> e : map.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      quote(sb, e.getKey());
      sb.append(':');
      Object v = e.getValue();
      if (v == null) {
        sb.append("null");
      } else if (v instanceof Number || v instanceof Boolean) {
        sb.append(v);
      } else {
        quote(sb, v.toString());
      }
    }
    return sb.append('}').toString();
  }

  private static void quote(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }
}
