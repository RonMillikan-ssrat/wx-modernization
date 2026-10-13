# wx-modernization: Phase 1 lift-and-shift

A legacy-style COBOL weather batch job (`WXSUMM`) running unchanged on GCP:
Cloud Scheduler triggers a Workflow, the Workflow runs a Cloud Batch job, and
the job runs the COBOL program in a container and then checks its output
byte for byte against a golden reference.

```
Cloud Scheduler ──▶ Workflows (wx-nightly) ──▶ Cloud Batch job
                                               ├─ STEP010  WXSUMM (GnuCOBOL)
                                               └─ STEP020  parity_check.py
  gs://…-landing/obs/<date>/obs.ebc ──▶ gs://…-output/<date>/{summary,errors}.txt
                                         compared to gs://…-reference/<date>/
```

## Quick start (local)

Requirements: GnuCOBOL 3.x or 4.x (`cobc`), Python 3.10+.

```bash
scripts/run_local.sh                    # compile, generate, run, cross-check, parity
scripts/run_local.sh --make-reference   # regenerate the golden reference
```

A passing run ends with `PARITY OK` twice: once for the independent Python
port against COBOL, once for COBOL against `reference/sample/`.

## Deploy to GCP

```bash
export PROJECT_ID=your-project REGION=us-central1
scripts/deploy.sh all            # APIs, IAM, buckets, image, data, workflow, schedule
scripts/deploy.sh run-workflow   # one execution now, waits for the result
scripts/deploy.sh fetch          # download outputs and re-check parity locally
```

`IMAGE_BUILD=cloudbuild scripts/deploy.sh image` builds with Cloud Build
instead of local Docker. `scripts/deploy.sh run-batch` submits the Batch job
directly, bypassing the workflow, which is useful for debugging mounts.

## Layout

| Path | Purpose |
| --- | --- |
| `cobol/WXOBS.cpy` | Record copybook, LRECL 40, with byte offsets |
| `cobol/WXSUMM.cbl` | Legacy program: EBCDIC decode, validation, control break |
| `data/gen_obs.py` | Deterministic synthetic EBCDIC input with planted traps |
| `scripts/wxsumm_ref.py` | Independent Python port; seed of the Phase 2 decoders |
| `scripts/parity_check.py` | SHA-256 compare, first differing line and column |
| `scripts/run_job.sh` | Container entrypoint: STEP010 + STEP020 with JCL-style RC handling |
| `scripts/run_local.sh` | Local end-to-end run |
| `scripts/deploy.sh` | GCP deployment, one idempotent step at a time |
| `batch/job.json.tmpl` | Batch job for direct submission (`run-batch`) |
| `workflows/nightly.yaml` | Nightly workflow using the Batch connector |
| `reference/sample/` | Golden outputs, committed |

## Design decisions worth explaining in an interview

- **DD-style file assignment.** `ASSIGN TO "OBSIN"` resolves through the
  `DD_OBSIN` environment variable at runtime, so the program is unchanged
  between local, container and GCP runs, just as JCL DD statements rebind
  datasets without recompiling.
- **Translate fields, never records.** Only DISPLAY fields are converted from
  EBCDIC. Converting the whole record (`CODE-SET`, `dd conv=ascii`, a text-mode
  FTP) silently corrupts COMP and COMP-3 bytes.
- **RC 4 is success.** The program sets RC 4 when it rejects records. Batch
  fails a task on any nonzero exit, so `run_job.sh` applies the JCL `COND`
  logic itself.
- **Local write, then copy.** Outputs are written to local disk and copied to
  the FUSE-mounted bucket, because Cloud Storage FUSE is not a POSIX disk.
- **Least privilege.** The runner can only read landing and reference, and
  write output. The workflow identity can create Batch jobs that run as the
  runner, nothing more.
- **No silent pass.** A missing reference fails the job (exit 3) instead of
  skipping the comparison.

## Planted traps (see `data/gen_obs.py`)

| Trap | Where | Expected result |
| --- | --- | --- |
| Negative COMP-3 | PAFA03 all days | Negative min, max, mean |
| -9999 sentinel | KIAH01 2028-02-28 h03–04 | NTP 22, excluded from stats |
| Whole day missing | KHOU02 2028-03-01 | `N/A` for temps and precip |
| F-signed packed decimal | KSGR-1 every 6th hour | Treated as positive |
| Byte order probe | 72243A 2028-02-27 h12, WDIR 258 | Accepted (0x0102 big-endian) |
| Leap days | 2028-02-29 valid; 2027-02-29 | Accepted; R04 |
| Range checks | hour 24; WDIR 361; QC `X`; blank date | R01; R02; R03; R05 |
| Collating sequence | `72243A` vs `KIAH01` | Input in EBCDIC order; `72243A` last |

Two runtime behaviours the Python port had to copy exactly: GnuCOBOL strips
trailing spaces from LINE SEQUENTIAL records, and `ROUNDED` rounds half away
from zero (Python `Decimal` `ROUND_HALF_UP`, not the built-in `round`).

## What has and hasn't been run

| Item | Status |
| --- | --- |
| COBOL compile and run (GnuCOBOL 3.1.2, Ubuntu 24.04) | Run, RC 4 as designed |
| Python port vs COBOL | Byte-identical |
| `run_job.sh` pass, parity-fail and missing-input paths | Run with simulated mounts |
| Shell scripts | `bash -n` and ShellCheck clean |
| Workflow YAML, rendered Batch JSON | Parse cleanly |
| Docker image build | Not run (no registry access where this was built) |
| Any GCP step | Not run |

Check on the first GCP run:

- Batch `mountOptions` takes gcsfuse CLI flags (`--implicit-dirs`). If the
  mount fails, Batch may log little; remove the option and test again.
- Container runnables list `container.volumes` explicitly to bind the host
  mounts. If Batch reports a duplicate mount, remove that list.
- On GnuCOBOL 4.0-early-dev, if `-std=default` is rejected, drop the flag.

Phase 1 has been run end to end on GCP: Cloud Batch job `SUCCEEDED` and
`deploy.sh fetch` reported `PARITY OK`.

---

# Phase 2: refactor to Apache Beam (Java)

The same logic as WXSUMM, in Java on Apache Beam 2.76.0, in `pipeline/`.

```bash
pipeline/run_local.sh      # unit tests, plain-Java parity, Beam DirectRunner runs
```

```
pipeline/
  src/main/java/wx/core/       business logic, no Beam dependency
    CopybookCodec.java           COMP-3, big-endian COMP, CP037 display decoders
    WxobsLayout.java             field offsets, the Java twin of WXOBS.cpy
    Validator.java               3100-VALIDATE: same checks, same order, same codes
    DayAccumulator.java          4000-ACCUMULATE, order- and partition-independent
    DaySummary.java              5000-WRITE-SUMMARY: BigDecimal, COBOL ROUNDED, edited pictures
    LegacyOrder.java             EBCDIC collating order
    SplitKeyDetector.java        tracks legacy control-break runs
    LegacyReport.java            single-process runner that writes the legacy report format
  src/main/java/wx/pipeline/
    WxSummaryPipeline.java       Beam wiring only
  src/test/java/wx/core/       34 JUnit 5 tests, including a golden-file test
```

| Check | Compares | Method |
| --- | --- | --- |
| Golden-file unit test | `LegacyReport` vs WXSUMM output | Byte for byte |
| `LegacyReport` run | Plain-Java core vs `reference/sample/` | `parity_check.py`, byte for byte |
| Beam DirectRunner | Pipeline output vs `reference/sample/` | `keyed_parity.py`, joined on station+date (Beam output has no order) |

## Behaviour differences from the legacy program

See the doc *Legacy vs Beam: Two Behavior Differences* for the full explanation.

- **Grouping.** The COBOL control break groups consecutive records; Beam groups
  every record with a key. On a file where a station-day appears in two separate
  runs, COBOL writes two partial lines and Beam writes one complete line. The
  pipeline counts legacy runs while it still reads each file in order and writes
  any split key to `split_keys.txt`. Demo: `gen_obs.py --late-records`.
- **Order.** Beam and BigQuery output is unordered and sorts by UTF-8 rules.
  `LegacyOrder` reproduces EBCDIC order when the legacy order is required.
- **Extra dead-letter codes.** `R06` marks records that cannot be decoded (bad
  packed nibble, or trailing bytes when the file size is not a multiple of 40).
  WXSUMM had no such check.

## What has and hasn't been run (Phase 2, steps 1-4)

| Item | Status |
| --- | --- |
| `pipeline/run_local.sh` on Ubuntu, Java 21, Maven 3.9.12 | `PHASE 2 LOCAL OK` |
| Unit tests under JUnit 5 | Pass |
| Mutation check: banker's rounding, little-endian COMP, F sign rejected | Each one fails the suite |
| `LegacyReport` vs WXSUMM reference | Byte-identical |
| Beam DirectRunner vs WXSUMM reference | Keyed parity on 20 rows and 5 rejects |
| Late-records demo: COBOL 21 groups vs Beam 20 groups and 1 split-key warning | Run |

---

# Phase 2 on GCP: BigQuery, Pub/Sub, Dataflow, Flex Template

```
                      ┌─▶ BigQuery wx.summary   (partitioned by obs_date, clustered by station)
gs://…-landing ─▶ Dataflow ─┼─▶ BigQuery wx.errors    (rejects with legacy reason codes)
  obs.ebc        WxSummary  ├─▶ Pub/Sub wx-dead-letter (one JSON message per reject)
                 Pipeline   └─▶ gs://…-output/beam/<run_id>/ (summary, errors, split_keys .txt)

BigQuery: wx.reference_summary_raw + wx.reference_errors_raw (WXSUMM report, one line per row)
          parity.sql parses them by column position and joins on station + obs_date
```

```bash
export PROJECT_ID=wx-modernization-0 REGION=us-central1
gcloud auth application-default login       # the pipeline launcher uses these credentials
scripts/deploy_phase2.sh setup              # APIs, worker SA, buckets, dataset, topic, reference
scripts/deploy_phase2.sh run-dataflow       # step 6: direct launch; blocks until the job ends
scripts/deploy_phase2.sh parity             # step 7: expect BIGQUERY PARITY OK
scripts/deploy_phase2.sh dead-letter        # the 5 rejects as JSON
scripts/deploy_phase2.sh flex-build         # step 8: shaded jar, image, template spec
scripts/deploy_phase2.sh flex-run           # launch from the template and wait
scripts/deploy_phase2.sh parity             # parity for the flex run
```

| Design choice | Why |
| --- | --- |
| `NUMERIC` columns, written as strings | Exact decimals, as in COBOL; `FLOAT64` would add binary rounding |
| `NULL` for no valid observations | The BigQuery form of the legacy `N/A`; `IS DISTINCT FROM` compares it safely |
| `run_id` on every row, `WRITE_APPEND` | Reruns never overwrite or mix with earlier runs; parity checks one run |
| Tables created by the deploy script, pipeline uses `CREATE_NEVER` | The schema lives in one place (`pipeline/bq/*.json`), not in code |
| Reference report loaded unchanged, parsed in SQL by position | Fixed-width parsing in SQL, the same layout a copybook describes |
| Every sink optional | The same jar runs locally with text output only |

| Item | Status |
| --- | --- |
| `Rows` (BigQuery row and JSON mapping) unit tests | Pass (JUnit stand-in; `mvn test` runs them for real) |
| `parity.sql` logic | Checked with a DuckDB translation: catches changed values, N/A vs 0, missing rows, duplicated errors; ignores other runs. BigQuery syntax is checked by the first real run |
| Fixed-width offsets in `parity.sql` | Checked against all 20 reference lines |
| `deploy_phase2.sh` | `bash -n` and ShellCheck clean; not yet run against GCP |
| Beam sinks, Dataflow, Flex Template | Not yet compiled or run (no Maven access where written) |
