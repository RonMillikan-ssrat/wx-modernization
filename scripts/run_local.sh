#!/usr/bin/env bash
# Local end-to-end run: compile, generate, run COBOL, cross-check, parity.
#
#   scripts/run_local.sh                 run and compare against reference/
#   scripts/run_local.sh --make-reference  (re)create reference/ from this run
#
# Steps:
#   1. compile WXSUMM with GnuCOBOL
#   2. generate deterministic synthetic EBCDIC input (data/gen_obs.py)
#   3. run WXSUMM with DD_* variables, like JCL DD statements
#   4. run the independent Python port and require byte-identical output
#   5. compare against the golden reference outputs
set -euo pipefail
cd "$(dirname "$0")/.."

RUN_DATE=sample
WORK=work/$RUN_DATE
REF=reference/$RUN_DATE
mkdir -p build "$WORK/cobol" "$WORK/python"

echo "== 1. compile"
cobc -x -std=default -I cobol -o build/wxsumm cobol/WXSUMM.cbl

echo "== 2. generate input"
python3 data/gen_obs.py "$WORK/obs.ebc"

echo "== 3. run WXSUMM"
set +e
DD_OBSIN="$WORK/obs.ebc" \
DD_SUMOUT="$WORK/cobol/summary.txt" \
DD_ERROUT="$WORK/cobol/errors.txt" \
    ./build/wxsumm
rc=$?
set -e
echo "WXSUMM RC=$rc"
(( rc <= 4 )) || { echo "WXSUMM failed"; exit "$rc"; }

echo "== 4. Python port cross-check"
python3 scripts/wxsumm_ref.py "$WORK/obs.ebc" \
    "$WORK/python/summary.txt" "$WORK/python/errors.txt" || true
python3 scripts/parity_check.py \
    "$WORK/python/summary.txt" "$WORK/cobol/summary.txt" \
    "$WORK/python/errors.txt"  "$WORK/cobol/errors.txt"

if [[ "${1:-}" == "--make-reference" || ! -f "$REF/summary.txt" ]]; then
    echo "== 5. writing reference to $REF"
    mkdir -p "$REF"
    cp "$WORK/cobol/summary.txt" "$WORK/cobol/errors.txt" "$REF/"
else
    echo "== 5. parity against $REF"
    python3 scripts/parity_check.py \
        "$WORK/cobol/summary.txt" "$REF/summary.txt" \
        "$WORK/cobol/errors.txt"  "$REF/errors.txt"
fi
