#!/usr/bin/env bash
# Phase 2 on GCP: BigQuery + Pub/Sub sinks, Dataflow (direct launch, then Flex
# Template), and parity as SQL. Reuses the Phase 1 project, buckets and input.
#
# Usage:
#   export PROJECT_ID=wx-modernization-0 REGION=us-central1
#   scripts/deploy_phase2.sh setup          # apis iam bucket bq pubsub reference
#   scripts/deploy_phase2.sh run-dataflow   # Step 6: direct launch, waits for the job
#   scripts/deploy_phase2.sh parity         # Step 7: SQL parity for the last run
#   scripts/deploy_phase2.sh flex-build     # Step 8: package and register the template
#   scripts/deploy_phase2.sh flex-run       # Step 8: launch from the template, wait
#   scripts/deploy_phase2.sh parity         # parity again, for the flex run
#
# Single steps: apis | iam | bucket | bq | pubsub | reference
#               run-direct   pipeline on your machine (DirectRunner) writing to
#                            BigQuery and Pub/Sub: a cheap check of the sinks
#               dead-letter  pull and show messages from the dead-letter topic
#
# Each run gets a run_id (RUN_ID=... to choose one); the last one is saved in
# work/phase2_run_id so `parity` checks the run you just did.
set -euo pipefail
cd "$(dirname "$0")/.."

: "${PROJECT_ID:?set PROJECT_ID}"
REGION="${REGION:-us-central1}"
PREFIX="${PREFIX:-$PROJECT_ID-wx}"

LANDING_BUCKET="$PREFIX-landing"
OUTPUT_BUCKET="$PREFIX-output"
DATAFLOW_BUCKET="$PREFIX-dataflow"
DATASET_ID=wx
DATASET="$PROJECT_ID.$DATASET_ID"            # for SQL
BQ_DATASET="$PROJECT_ID:$DATASET_ID"         # for bq / BigQueryIO
TOPIC=wx-dead-letter
SUBSCRIPTION=wx-dead-letter-sub
WORKER_SA="wx-dataflow-worker@$PROJECT_ID.iam.gserviceaccount.com"
INPUT="gs://$LANDING_BUCKET/obs/${RUN_DATE:-sample}/obs.ebc"
REPO=wx
FLEX_IMAGE="$REGION-docker.pkg.dev/$PROJECT_ID/$REPO/wxsumm-flex:latest"
FLEX_SPEC="gs://$DATAFLOW_BUCKET/templates/wxsumm.json"
RUN_ID_FILE=work/phase2_run_id

log() { echo "== $*"; }
gc() { gcloud --project "$PROJECT_ID" "$@"; }

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

# The pipeline options shared by every launch method.
pipeline_args() {
    echo "--input=$INPUT" \
         "--output=gs://$OUTPUT_BUCKET/beam/$RUN_ID" \
         "--bqSummaryTable=$BQ_DATASET.summary" \
         "--bqErrorsTable=$BQ_DATASET.errors" \
         "--deadLetterTopic=projects/$PROJECT_ID/topics/$TOPIC" \
         "--runId=$RUN_ID"
}

step_apis() {
    log "enabling APIs"
    gc services enable dataflow.googleapis.com bigquery.googleapis.com \
        pubsub.googleapis.com storage.googleapis.com compute.googleapis.com \
        artifactregistry.googleapis.com cloudbuild.googleapis.com
}

project_role() {
    gc projects add-iam-policy-binding "$PROJECT_ID" \
        --member "serviceAccount:$WORKER_SA" --role "$1" --condition None >/dev/null
}

step_iam() {
    log "Dataflow worker service account and roles"
    gc iam service-accounts describe "$WORKER_SA" >/dev/null 2>&1 || \
        gc iam service-accounts create wx-dataflow-worker --display-name "WXSUMM Dataflow worker"
    project_role roles/dataflow.worker           # run as a Dataflow worker
    project_role roles/bigquery.jobUser          # run BigQuery load jobs
    # Project-wide for simplicity; least privilege would grant this on the wx dataset only.
    project_role roles/bigquery.dataEditor       # append to the wx tables
    project_role roles/artifactregistry.reader   # pull the Flex Template image
}

step_bucket() {
    log "staging/temp bucket and bucket-level access"
    gc storage buckets describe "gs://$DATAFLOW_BUCKET" >/dev/null 2>&1 || \
        gc storage buckets create "gs://$DATAFLOW_BUCKET" --location "$REGION" \
            --uniform-bucket-level-access
    gc storage buckets add-iam-policy-binding "gs://$DATAFLOW_BUCKET" \
        --member "serviceAccount:$WORKER_SA" --role roles/storage.objectAdmin >/dev/null
    gc storage buckets add-iam-policy-binding "gs://$LANDING_BUCKET" \
        --member "serviceAccount:$WORKER_SA" --role roles/storage.objectViewer >/dev/null
    gc storage buckets add-iam-policy-binding "gs://$OUTPUT_BUCKET" \
        --member "serviceAccount:$WORKER_SA" --role roles/storage.objectUser >/dev/null
    gc storage ls "$INPUT" >/dev/null || {
        echo "input $INPUT not found: run 'scripts/deploy.sh data' (Phase 1) first"; exit 2; }
}

make_table() {
    local name=$1 schema=$2; shift 2
    bq --project_id "$PROJECT_ID" show "$BQ_DATASET.$name" >/dev/null 2>&1 || \
        bq --project_id "$PROJECT_ID" mk --table "$@" "$BQ_DATASET.$name" "$schema"
}

step_bq() {
    log "BigQuery dataset $BQ_DATASET and tables"
    bq --project_id "$PROJECT_ID" show "$BQ_DATASET" >/dev/null 2>&1 || \
        bq --project_id "$PROJECT_ID" --location "$REGION" mk --dataset \
            --description "WXSUMM modernization exercise" "$BQ_DATASET"
    make_table summary pipeline/bq/summary_schema.json \
        --time_partitioning_field obs_date --time_partitioning_type DAY \
        --clustering_fields station \
        --description "Daily station summaries, one row per station-day per run"
    make_table errors pipeline/bq/errors_schema.json \
        --clustering_fields run_id,code \
        --description "Rejected records (dead letter), one row per record per run"
}

step_pubsub() {
    log "dead-letter topic and subscription"
    gc pubsub topics describe "$TOPIC" >/dev/null 2>&1 || gc pubsub topics create "$TOPIC"
    # Messages published before a subscription exists are not kept, so create it now.
    gc pubsub subscriptions describe "$SUBSCRIPTION" >/dev/null 2>&1 || \
        gc pubsub subscriptions create "$SUBSCRIPTION" --topic "$TOPIC" \
            --message-retention-duration 7d
    gc pubsub topics add-iam-policy-binding "$TOPIC" \
        --member "serviceAccount:$WORKER_SA" --role roles/pubsub.publisher >/dev/null
}

step_reference() {
    log "loading the WXSUMM reference report into BigQuery, one line per row"
    [[ -f reference/sample/summary.txt ]] || { echo "reference/sample missing: run scripts/run_local.sh"; exit 2; }
    for kind in summary errors; do
        bq --project_id "$PROJECT_ID" --location "$REGION" load --replace \
            --source_format CSV --field_delimiter tab --quote="" \
            "$BQ_DATASET.reference_${kind}_raw" "reference/sample/$kind.txt" \
            pipeline/bq/raw_line_schema.json
    done
}

step_run_direct() {
    new_run_id
    log "DirectRunner on this machine, writing to BigQuery and Pub/Sub"
    mvn -q -f pipeline/pom.xml compile exec:java -Dexec.args="$(pipeline_args) \
        --runner=DirectRunner --project=$PROJECT_ID \
        --tempLocation=gs://$DATAFLOW_BUCKET/temp"
}

step_run_dataflow() {
    new_run_id
    log "Dataflow, direct launch from this machine (blocks until the job finishes)"
    mvn -q -f pipeline/pom.xml compile exec:java -Dexec.args="$(pipeline_args) \
        --runner=DataflowRunner --project=$PROJECT_ID --region=$REGION \
        --jobName=wxsumm-$RUN_ID \
        --serviceAccount=$WORKER_SA \
        --tempLocation=gs://$DATAFLOW_BUCKET/temp \
        --stagingLocation=gs://$DATAFLOW_BUCKET/staging \
        --maxNumWorkers=1"
}

# Substitutes ${DATASET} and ${RUN_ID}, and drops whole-line "--" comments: bq treats a query
# argument that starts with "--" as one of its own flags.
render_sql() {
    DATASET="$DATASET" RUN_ID="$RUN_ID" python3 -c '
import os, sys
text = os.path.expandvars(open(sys.argv[1]).read())
sys.stdout.write("\n".join(l for l in text.splitlines() if not l.lstrip().startswith("--")))
' "$1"
}

step_parity() {
    last_run_id
    log "row counts for $RUN_ID"
    bq --project_id "$PROJECT_ID" --location "$REGION" query --use_legacy_sql=false \
        --format pretty "$(render_sql pipeline/bq/counts.sql)"
    log "SQL parity vs the WXSUMM reference, joined on station + obs_date"
    local diffs
    diffs=$(bq --project_id "$PROJECT_ID" --location "$REGION" query --use_legacy_sql=false \
        --format csv --max_rows 1000 "$(render_sql pipeline/bq/parity.sql)")
    # CSV output: a header line, then one line per difference.
    local n
    n=$(( $(printf '%s\n' "$diffs" | grep -c .) - 1 ))
    if (( n <= 0 )); then
        echo "BIGQUERY PARITY OK ($RUN_ID)"
    else
        printf '%s\n' "$diffs"
        echo "BIGQUERY PARITY FAILED: $n difference(s) for $RUN_ID"
        exit 1
    fi
}

step_dead_letter() {
    log "dead-letter messages (pulled and acknowledged)"
    gc pubsub subscriptions pull "$SUBSCRIPTION" --limit 20 --auto-ack \
        --format 'value(message.data)'
}

step_flex_build() {
    log "building the bundled jar"
    mvn -q -f pipeline/pom.xml -Pflex -DskipTests package
    gc artifacts repositories describe "$REPO" --location "$REGION" >/dev/null 2>&1 || \
        gc artifacts repositories create "$REPO" --location "$REGION" --repository-format docker
    log "building the Flex Template image (Cloud Build) and spec $FLEX_SPEC"
    gc dataflow flex-template build "$FLEX_SPEC" \
        --image-gcr-path "$FLEX_IMAGE" \
        --sdk-language JAVA \
        --flex-template-base-image JAVA21 \
        --metadata-file pipeline/flex/metadata.json \
        --jar pipeline/target/wx-summary-pipeline-bundled.jar \
        --env FLEX_TEMPLATE_JAVA_MAIN_CLASS=wx.pipeline.WxSummaryPipeline
}

step_flex_run() {
    new_run_id
    local params
    params=$(pipeline_args | sed 's/--//g; s/ /,/g')
    log "launching from the Flex Template"
    local job_id
    job_id=$(gc dataflow flex-template run "wxsumm-flex-$RUN_ID" \
        --region "$REGION" \
        --template-file-gcs-location "$FLEX_SPEC" \
        --service-account-email "$WORKER_SA" \
        --staging-location "gs://$DATAFLOW_BUCKET/staging" \
        --temp-location "gs://$DATAFLOW_BUCKET/temp" \
        --max-workers 1 \
        --parameters "$params" \
        --format 'value(job.id)')
    log "job $job_id submitted; waiting (the launcher VM adds a few minutes)"
    local state
    while :; do
        state=$(gc dataflow jobs describe "$job_id" --region "$REGION" --format 'value(currentState)')
        echo "$(date +%T) $state"
        case "$state" in
            JOB_STATE_DONE) break ;;
            JOB_STATE_FAILED|JOB_STATE_CANCELLED)
                echo "job ended $state; see: gcloud dataflow jobs show $job_id --region $REGION"; exit 1 ;;
        esac
        sleep 30
    done
}

case "${1:-}" in
    apis) step_apis ;;
    iam) step_iam ;;
    bucket) step_bucket ;;
    bq) step_bq ;;
    pubsub) step_pubsub ;;
    reference) step_reference ;;
    setup) step_apis; step_iam; step_bucket; step_bq; step_pubsub; step_reference ;;
    run-direct) step_run_direct ;;
    run-dataflow) step_run_dataflow ;;
    parity) step_parity ;;
    dead-letter) step_dead_letter ;;
    flex-build) step_flex_build ;;
    flex-run) step_flex_run ;;
    *) sed -n '2,21p' "$0"; exit 2 ;;
esac
