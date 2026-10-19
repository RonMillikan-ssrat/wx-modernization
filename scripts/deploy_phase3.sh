#!/usr/bin/env bash
# Phase 3: real HRRR GRIB2 data -> station point series, Java (netCDF-Java) vs an eccodes oracle.
# Reuses the Phase 1/2 project, buckets, dataset and Dataflow worker service account.
#
# Usage (DATE defaults to yesterday UTC; HOURS to 0-23):
#   export PROJECT_ID=wx-modernization-0 REGION=us-central1 DATE=20261014
#   scripts/deploy_phase3.sh python      # venv with eccodes + numpy (work/venv-hrrr)
#   scripts/deploy_phase3.sh check       # is the HRRR bucket where we expect? (HTTP status)
#   scripts/deploy_phase3.sh list        # matching .idx lines for hour 0, nothing downloaded
#   scripts/deploy_phase3.sh fetch       # download the 5 fields per hour (byte ranges)
#   scripts/deploy_phase3.sh reference   # eccodes oracle -> work/hrrr/DATE/reference.csv
#   scripts/deploy_phase3.sh run-local   # Beam DirectRunner on local files + local parity (no GCP)
#   scripts/deploy_phase3.sh setup       # upload, bq tables, load reference into BigQuery
#   scripts/deploy_phase3.sh run-dataflow
#   scripts/deploy_phase3.sh parity      # SQL parity for the last run
#
# Single steps: upload | bq | bq-reference
#               run-direct  DirectRunner on this machine reading gs:// and writing BigQuery
#
# Each cloud run gets a run_id (RUN_ID=... to choose one), saved in work/phase3_run_id.
set -euo pipefail
cd "$(dirname "$0")/.."

DATE="${DATE:-$(date -u -d yesterday +%Y%m%d)}"
HOURS="${HOURS:-0-23}"
HRRR_BASE_URL="${HRRR_BASE_URL:-https://storage.googleapis.com/high-resolution-rapid-refresh}"

VENV=work/venv-hrrr
PY="$VENV/bin/python"
LOCAL_DIR="work/hrrr/$DATE"
LOCAL_OUT="work/hrrr/out/$DATE"
RUN_ID_FILE=work/phase3_run_id
MAIN=wx.pipeline.HrrrPointPipeline

log() { echo "== $*"; }

# Cloud settings are only needed by the cloud steps.
cloud_env() {
    : "${PROJECT_ID:?set PROJECT_ID}"
    REGION="${REGION:-us-central1}"
    PREFIX="${PREFIX:-$PROJECT_ID-wx}"
    LANDING_BUCKET="$PREFIX-landing"
    OUTPUT_BUCKET="$PREFIX-output"
    DATAFLOW_BUCKET="$PREFIX-dataflow"
    DATASET="$PROJECT_ID.wx"                 # for SQL
    BQ_DATASET="$PROJECT_ID:wx"              # for bq / BigQueryIO
    WORKER_SA="wx-dataflow-worker@$PROJECT_ID.iam.gserviceaccount.com"
    GCS_INPUT="gs://$LANDING_BUCKET/hrrr/$DATE"
}
gc() { gcloud --project "$PROJECT_ID" "$@"; }
bqp() { bq --project_id "$PROJECT_ID" --location "$REGION" "$@"; }

need_python() {
    [[ -x "$PY" ]] || { echo "no $VENV: run '$0 python' first"; exit 2; }
}

need_files() {
    compgen -G "$LOCAL_DIR/*.grib2" >/dev/null || {
        echo "no GRIB2 files in $LOCAL_DIR: run '$0 fetch' (DATE=$DATE)"; exit 2; }
}

new_run_id() {
    RUN_ID="${RUN_ID:-run-$(date -u +%Y%m%dt%H%M%Sz)}"
    mkdir -p work
    echo "$RUN_ID" > "$RUN_ID_FILE"
    log "run_id=$RUN_ID"
}

last_run_id() {
    RUN_ID="${RUN_ID:-$(cat "$RUN_ID_FILE" 2>/dev/null || true)}"
    [[ -n "$RUN_ID" ]] || { echo "no run_id: run a pipeline first or set RUN_ID"; exit 2; }
}

step_python() {
    log "Python venv $VENV with eccodes (binary wheel, bundles the C library) and numpy"
    [[ -x "$PY" ]] || python3 -m venv "$VENV"
    "$PY" -m pip install -q --upgrade pip
    "$PY" -m pip install -q eccodes numpy
    "$PY" -c 'import eccodes; print("eccodes", eccodes.codes_get_api_version())'
}

step_check() {
    local url="$HRRR_BASE_URL/hrrr.$DATE/conus/hrrr.t00z.wrfsfcf00.grib2.idx"
    log "HEAD $url"
    local status
    status=$(curl -s -o /dev/null -w '%{http_code}' -I "$url")
    echo "HTTP $status"
    [[ "$status" == 200 ]] || {
        echo "not found: check the date (HRRR keeps recent days) or set HRRR_BASE_URL"; exit 1; }
}

step_list() {
    need_python
    "$PY" scripts/hrrr_fetch.py --date "$DATE" --hours 0 --list --base-url "$HRRR_BASE_URL"
}

step_fetch() {
    need_python
    log "fetching HRRR $DATE hours $HOURS into $LOCAL_DIR"
    "$PY" scripts/hrrr_fetch.py --date "$DATE" --hours "$HOURS" --out work/hrrr \
        --base-url "$HRRR_BASE_URL"
}

step_reference() {
    need_python
    need_files
    log "eccodes oracle -> $LOCAL_DIR/reference.csv"
    "$PY" scripts/hrrr_reference.py "$LOCAL_DIR"/*.grib2 > "$LOCAL_DIR/reference.csv.tmp"
    mv "$LOCAL_DIR/reference.csv.tmp" "$LOCAL_DIR/reference.csv"
    echo "$(( $(wc -l < "$LOCAL_DIR/reference.csv") - 1 )) reference values"
}

step_run_local() {
    need_files
    [[ -f "$LOCAL_DIR/reference.csv" ]] || step_reference
    log "HrrrPointPipeline on the DirectRunner, local files only"
    rm -rf "$LOCAL_OUT"
    mvn -q -f pipeline/pom.xml compile exec:java -Dexec.mainClass="$MAIN" \
        -Dexec.args="--input=$PWD/$LOCAL_DIR/*.grib2 --output=$PWD/$LOCAL_OUT --runner=DirectRunner"
    log "local parity: $LOCAL_OUT/points.csv vs reference.csv"
    python3 scripts/hrrr_points_parity.py "$LOCAL_OUT/points.csv" "$LOCAL_DIR/reference.csv"
}

step_upload() {
    cloud_env
    need_files
    log "uploading GRIB2 subsets to $GCS_INPUT/"
    gc storage cp "$LOCAL_DIR"/*.grib2 "$LOCAL_DIR/manifest.json" "$GCS_INPUT/"
}

make_table() {
    local name=$1 schema=$2; shift 2
    bq --project_id "$PROJECT_ID" show "$BQ_DATASET.$name" >/dev/null 2>&1 || \
        bq --project_id "$PROJECT_ID" mk --table "$@" "$BQ_DATASET.$name" "$schema"
}

step_bq() {
    cloud_env
    log "BigQuery tables hrrr_points and hrrr_reference in $BQ_DATASET"
    bq --project_id "$PROJECT_ID" show "$BQ_DATASET" >/dev/null 2>&1 || {
        echo "dataset $BQ_DATASET missing: run 'scripts/deploy_phase2.sh bq' first"; exit 2; }
    make_table hrrr_points pipeline/bq/hrrr_points_schema.json \
        --time_partitioning_field valid_time --time_partitioning_type DAY \
        --clustering_fields station \
        --description "HRRR station point series, one row per station-hour per run"
    make_table hrrr_reference pipeline/bq/hrrr_reference_schema.json \
        --time_partitioning_field valid_time --time_partitioning_type DAY \
        --clustering_fields station,variable \
        --description "eccodes decode of the same files: the Phase 3 oracle"
}

step_bq_reference() {
    cloud_env
    [[ -f "$LOCAL_DIR/reference.csv" ]] || { echo "no reference.csv: run '$0 reference'"; exit 2; }
    # Replace only this date's partition so other days already loaded stay put.
    log "loading reference.csv into hrrr_reference\$$DATE"
    bqp load --replace --source_format CSV --skip_leading_rows 1 \
        "$BQ_DATASET.hrrr_reference\$$DATE" "$LOCAL_DIR/reference.csv" \
        pipeline/bq/hrrr_reference_schema.json
}

pipeline_args() {
    echo "--input=$GCS_INPUT/*.grib2" \
         "--output=gs://$OUTPUT_BUCKET/hrrr/$RUN_ID" \
         "--bqPointsTable=$BQ_DATASET.hrrr_points" \
         "--runId=$RUN_ID"
}

step_run_direct() {
    cloud_env
    new_run_id
    log "DirectRunner on this machine, reading $GCS_INPUT, writing BigQuery"
    mvn -q -f pipeline/pom.xml compile exec:java -Dexec.mainClass="$MAIN" \
        -Dexec.args="$(pipeline_args) --runner=DirectRunner --project=$PROJECT_ID \
        --tempLocation=gs://$DATAFLOW_BUCKET/temp"
}

step_run_dataflow() {
    cloud_env
    new_run_id
    log "Dataflow, direct launch (blocks until the job finishes)"
    mvn -q -f pipeline/pom.xml compile exec:java -Dexec.mainClass="$MAIN" \
        -Dexec.args="$(pipeline_args) \
        --runner=DataflowRunner --project=$PROJECT_ID --region=$REGION \
        --jobName=hrrr-points-$RUN_ID \
        --serviceAccount=$WORKER_SA \
        --tempLocation=gs://$DATAFLOW_BUCKET/temp \
        --stagingLocation=gs://$DATAFLOW_BUCKET/staging \
        --maxNumWorkers=2"
}

# Substitutes ${DATASET} and ${RUN_ID}, and drops whole-line "--" comments: bq treats a query
# argument that starts with "--" as one of its own flags. (Same as deploy_phase2.sh.)
render_sql() {
    DATASET="$DATASET" RUN_ID="$RUN_ID" python3 -c '
import os, sys
text = os.path.expandvars(open(sys.argv[1]).read())
sys.stdout.write("\n".join(l for l in text.splitlines() if not l.lstrip().startswith("--")))
' "$1"
}

step_parity() {
    cloud_env
    last_run_id
    log "counts for $RUN_ID"
    bqp query --use_legacy_sql=false --format pretty "$(render_sql pipeline/bq/hrrr_counts.sql)"
    log "SQL parity vs the eccodes reference: cell exact, value within packing_step/2"
    local diffs n
    if ! diffs=$(bqp query --use_legacy_sql=false --format csv --max_rows 1000 \
            "$(render_sql pipeline/bq/hrrr_parity.sql)" 2>&1); then
        printf '%s\n' "$diffs"
        echo "parity query failed"
        exit 2
    fi
    n=$(printf '%s\n' "$diffs" | grep -c '[^[:space:]]' || true)
    n=$(( n > 0 ? n - 1 : 0 ))
    if (( n == 0 )); then
        echo "BIGQUERY HRRR PARITY OK ($RUN_ID)"
    else
        printf '%s\n' "$diffs"
        echo "BIGQUERY HRRR PARITY FAILED: $n difference(s) for $RUN_ID"
        exit 1
    fi
}

log "DATE=$DATE"
case "${1:-}" in
    python) step_python ;;
    check) step_check ;;
    list) step_list ;;
    fetch) step_fetch ;;
    reference) step_reference ;;
    run-local) step_run_local ;;
    upload) step_upload ;;
    bq) step_bq ;;
    bq-reference) step_bq_reference ;;
    setup) step_upload; step_bq; step_bq_reference ;;
    run-direct) step_run_direct ;;
    run-dataflow) step_run_dataflow ;;
    parity) step_parity ;;
    *) sed -n '2,21p' "$0"; exit 2 ;;
esac
