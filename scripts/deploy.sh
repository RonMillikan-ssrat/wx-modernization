#!/usr/bin/env bash
# Phase 1 deployment: buckets, service accounts, image, data, Batch,
# Workflows, and Cloud Scheduler. Each step is safe to re-run.
#
# Usage:
#   export PROJECT_ID=my-project            # required
#   export REGION=us-central1               # optional
#   scripts/deploy.sh all                   # everything below, in order
#   scripts/deploy.sh <step>                # one step
#
# Steps: apis | iam | buckets | image | data | workflow | schedule
#        run-batch    submit one Batch job directly (no workflow)
#        run-workflow execute the workflow once and wait for the result
#        fetch        download outputs and run parity locally
#
# Image build: IMAGE_BUILD=docker (default, local Docker + push) or
#              IMAGE_BUILD=cloudbuild (gcloud builds submit).
set -euo pipefail
cd "$(dirname "$0")/.."

: "${PROJECT_ID:?set PROJECT_ID}"
REGION="${REGION:-us-central1}"
PREFIX="${PREFIX:-$PROJECT_ID-wx}"
RUN_DATE="${RUN_DATE:-sample}"
IMAGE_BUILD="${IMAGE_BUILD:-docker}"

LANDING_BUCKET="$PREFIX-landing"
OUTPUT_BUCKET="$PREFIX-output"
REFERENCE_BUCKET="$PREFIX-reference"
REPO=wx
IMAGE_URI="$REGION-docker.pkg.dev/$PROJECT_ID/$REPO/wxsumm:latest"
RUNNER_SA="wx-batch-runner@$PROJECT_ID.iam.gserviceaccount.com"
WORKFLOW_SA="wx-workflow@$PROJECT_ID.iam.gserviceaccount.com"
WORKFLOW=wx-nightly
SCHEDULE="${SCHEDULE:-45 2 * * *}"      # 02:45 daily
TIME_ZONE="${TIME_ZONE:-America/Chicago}"

export LANDING_BUCKET OUTPUT_BUCKET REFERENCE_BUCKET IMAGE_URI RUNNER_SA RUN_DATE

log() { echo "== $*"; }
gc() { gcloud --project "$PROJECT_ID" "$@"; }

step_apis() {
    log "enabling APIs"
    gc services enable storage.googleapis.com artifactregistry.googleapis.com \
        batch.googleapis.com compute.googleapis.com logging.googleapis.com \
        workflows.googleapis.com workflowexecutions.googleapis.com \
        cloudscheduler.googleapis.com cloudbuild.googleapis.com
}

ensure_sa() {
    local name=$1 desc=$2
    gc iam service-accounts describe "$name@$PROJECT_ID.iam.gserviceaccount.com" \
        >/dev/null 2>&1 || gc iam service-accounts create "$name" --display-name "$desc"
}

project_role() {
    gc projects add-iam-policy-binding "$PROJECT_ID" \
        --member "serviceAccount:$1" --role "$2" --condition None >/dev/null
}

step_iam() {
    log "service accounts and project roles"
    ensure_sa wx-batch-runner "WXSUMM Batch runner"
    ensure_sa wx-workflow "WXSUMM nightly workflow"
    # Batch VM identity: report status, write logs, pull the image
    project_role "$RUNNER_SA" roles/batch.agentReporter
    project_role "$RUNNER_SA" roles/logging.logWriter
    project_role "$RUNNER_SA" roles/artifactregistry.reader
    # Workflow identity: create Batch jobs that run as the runner SA
    project_role "$WORKFLOW_SA" roles/batch.jobsEditor
    project_role "$WORKFLOW_SA" roles/logging.logWriter
    project_role "$WORKFLOW_SA" roles/workflows.invoker      # used by Scheduler
    gc iam service-accounts add-iam-policy-binding "$RUNNER_SA" \
        --member "serviceAccount:$WORKFLOW_SA" \
        --role roles/iam.serviceAccountUser >/dev/null
}

step_buckets() {
    log "buckets (bucket-level IAM: read-only input and reference)"
    for b in "$LANDING_BUCKET" "$OUTPUT_BUCKET" "$REFERENCE_BUCKET"; do
        gc storage buckets describe "gs://$b" >/dev/null 2>&1 || \
            gc storage buckets create "gs://$b" --location "$REGION" \
                --uniform-bucket-level-access
    done
    gc storage buckets update "gs://$REFERENCE_BUCKET" --versioning
    gc storage buckets add-iam-policy-binding "gs://$LANDING_BUCKET" \
        --member "serviceAccount:$RUNNER_SA" --role roles/storage.objectViewer >/dev/null
    gc storage buckets add-iam-policy-binding "gs://$REFERENCE_BUCKET" \
        --member "serviceAccount:$RUNNER_SA" --role roles/storage.objectViewer >/dev/null
    gc storage buckets add-iam-policy-binding "gs://$OUTPUT_BUCKET" \
        --member "serviceAccount:$RUNNER_SA" --role roles/storage.objectUser >/dev/null
}

step_image() {
    log "image -> $IMAGE_URI"
    gc artifacts repositories describe "$REPO" --location "$REGION" >/dev/null 2>&1 || \
        gc artifacts repositories create "$REPO" --location "$REGION" \
            --repository-format docker
    if [[ "$IMAGE_BUILD" == "cloudbuild" ]]; then
        gc builds submit --region "$REGION" --tag "$IMAGE_URI" .
    else
        gcloud auth configure-docker "$REGION-docker.pkg.dev" --quiet
        docker build -t "$IMAGE_URI" .
        docker push "$IMAGE_URI"
    fi
}

step_data() {
    log "uploading input and reference for RUN_DATE=$RUN_DATE"
    [[ -f "work/$RUN_DATE/obs.ebc" ]] || scripts/run_local.sh
    gc storage cp "work/$RUN_DATE/obs.ebc" "gs://$LANDING_BUCKET/obs/$RUN_DATE/obs.ebc"
    gc storage cp "reference/$RUN_DATE/summary.txt" "reference/$RUN_DATE/errors.txt" \
        "gs://$REFERENCE_BUCKET/$RUN_DATE/"
}

step_workflow() {
    log "deploying workflow $WORKFLOW"
    gc workflows deploy "$WORKFLOW" --location "$REGION" \
        --source workflows/nightly.yaml \
        --service-account "$WORKFLOW_SA" \
        --set-env-vars "REGION=$REGION,IMAGE_URI=$IMAGE_URI,RUNNER_SA=$RUNNER_SA,LANDING_BUCKET=$LANDING_BUCKET,OUTPUT_BUCKET=$OUTPUT_BUCKET,REFERENCE_BUCKET=$REFERENCE_BUCKET"
}

step_schedule() {
    log "scheduler job ($SCHEDULE $TIME_ZONE)"
    local uri="https://workflowexecutions.googleapis.com/v1/projects/$PROJECT_ID/locations/$REGION/workflows/$WORKFLOW/executions"
    local body="{\"argument\": \"{\\\"runDate\\\": \\\"$RUN_DATE\\\"}\"}"
    local verb=create
    gc scheduler jobs describe wx-nightly --location "$REGION" >/dev/null 2>&1 && verb=update
    gc scheduler jobs "$verb" http wx-nightly --location "$REGION" \
        --schedule "$SCHEDULE" --time-zone "$TIME_ZONE" \
        --uri "$uri" --http-method POST --message-body "$body" \
        --oauth-service-account-email "$WORKFLOW_SA"
}

step_run_batch() {
    log "submitting a Batch job directly"
    mkdir -p build
    # expand ${VAR} placeholders (same as envsubst, without needing gettext)
    python3 -c 'import os,sys; sys.stdout.write(os.path.expandvars(sys.stdin.read()))' \
        < batch/job.json.tmpl > build/job.json
    gc batch jobs submit "wxsumm-manual-$(date +%s)" --location "$REGION" \
        --config build/job.json
    echo "watch: gcloud batch jobs list --location $REGION --project $PROJECT_ID"
}

step_run_workflow() {
    log "executing $WORKFLOW (waits for completion)"
    gc workflows run "$WORKFLOW" --location "$REGION" \
        --data "{\"runDate\": \"$RUN_DATE\"}"
}

step_fetch() {
    log "fetching outputs and checking parity locally"
    mkdir -p "work/gcp/$RUN_DATE"
    gc storage cp "gs://$OUTPUT_BUCKET/$RUN_DATE/*" "work/gcp/$RUN_DATE/"
    cat "work/gcp/$RUN_DATE/run_status.json"
    python3 scripts/parity_check.py \
        "work/gcp/$RUN_DATE/summary.txt" "reference/$RUN_DATE/summary.txt" \
        "work/gcp/$RUN_DATE/errors.txt"  "reference/$RUN_DATE/errors.txt"
}

case "${1:-}" in
    apis) step_apis ;;
    iam) step_iam ;;
    buckets) step_buckets ;;
    image) step_image ;;
    data) step_data ;;
    workflow) step_workflow ;;
    schedule) step_schedule ;;
    run-batch) step_run_batch ;;
    run-workflow) step_run_workflow ;;
    fetch) step_fetch ;;
    all) step_apis; step_iam; step_buckets; step_image; step_data
         step_workflow; step_schedule ;;
    *) sed -n '2,18p' "$0"; exit 2 ;;
esac
