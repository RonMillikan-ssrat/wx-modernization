#!/usr/bin/env bash
# Container entrypoint: the GCP equivalent of a two-step JCL job.
#
#   STEP010  EXEC PGM=WXSUMM      (DD_OBSIN / DD_SUMOUT / DD_ERROUT)
#   STEP020  parity check against the reference, only if STEP010 RC <= 4
#
# Cloud Batch treats any nonzero exit as a failed task, so this script
# applies the JCL COND logic itself: RC 0 and 4 are success (4 = some
# records rejected), anything higher fails the job.
#
# Environment (set by the Batch job):
#   RUN_DATE    partition to process (default: sample)
#   IN_ROOT     mounted landing bucket   (default /mnt/disks/landing)
#   OUT_ROOT    mounted output bucket    (default /mnt/disks/output)
#   REF_ROOT    mounted reference bucket (default /mnt/disks/reference)
#   SKIP_PARITY set to 1 to skip STEP020 (e.g. when creating a reference)
set -uo pipefail

RUN_DATE="${RUN_DATE:-sample}"
IN_ROOT="${IN_ROOT:-/mnt/disks/landing}"
OUT_ROOT="${OUT_ROOT:-/mnt/disks/output}"
REF_ROOT="${REF_ROOT:-/mnt/disks/reference}"
APP_DIR="${APP_DIR:-/opt/wx}"

IN_FILE="$IN_ROOT/obs/$RUN_DATE/obs.ebc"
OUT_DIR="$OUT_ROOT/$RUN_DATE"
REF_DIR="$REF_ROOT/$RUN_DATE"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) run_job: $*"; }

log "RUN_DATE=$RUN_DATE input=$IN_FILE"
if [[ ! -f "$IN_FILE" ]]; then
    log "input not found: $IN_FILE"
    exit 16
fi

# --- STEP010: run the unchanged COBOL program -------------------------------
# Write to local disk first and copy afterwards: Cloud Storage FUSE does not
# behave like a POSIX disk for partial or random writes.
export DD_OBSIN="$IN_FILE"
export DD_SUMOUT="$WORK/summary.txt"
export DD_ERROUT="$WORK/errors.txt"
"$APP_DIR/bin/wxsumm"
rc=$?
log "STEP010 WXSUMM RC=$rc"
if (( rc > 4 )); then
    log "STEP010 failed (RC > 4); outputs not published"
    exit "$rc"
fi

mkdir -p "$OUT_DIR"
cp "$WORK/summary.txt" "$WORK/errors.txt" "$OUT_DIR/"
log "published outputs to $OUT_DIR"

# --- STEP020: parity check --------------------------------------------------
parity="skipped"
prc=0
if [[ "${SKIP_PARITY:-0}" != "1" ]]; then
    if [[ ! -f "$REF_DIR/summary.txt" ]]; then
        log "no reference at $REF_DIR; failing so a missing golden file is never silent"
        prc=3
        parity="no_reference"
    else
        python3 "$APP_DIR/scripts/parity_check.py" \
            "$WORK/summary.txt" "$REF_DIR/summary.txt" \
            "$WORK/errors.txt"  "$REF_DIR/errors.txt"
        prc=$?
        parity=$([[ $prc -eq 0 ]] && echo pass || echo fail)
    fi
    log "STEP020 parity=$parity RC=$prc"
fi

trailer="$(grep '^\*TOTAL' "$WORK/summary.txt" || true)"
cat > "$WORK/run_status.json" <<EOF
{"run_date": "$RUN_DATE", "wxsumm_rc": $rc, "parity": "$parity",
 "trailer": "$trailer", "finished_utc": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"}
EOF
cp "$WORK/run_status.json" "$OUT_DIR/"

exit "$prc"
