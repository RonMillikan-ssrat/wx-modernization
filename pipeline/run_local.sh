#!/usr/bin/env bash
# Phase 2 local run: unit tests, then the Beam pipeline on the DirectRunner.
#
#   pipeline/run_local.sh
#
# Steps:
#   1. unit tests (decoders, validator, order-independent accumulator,
#      legacy formatting, golden file vs WXSUMM output)
#   2. LegacyReport: plain-Java core vs WXSUMM reference, byte for byte
#   3. Beam pipeline on the sample file; keyed parity vs the reference,
#      and no split keys expected
#   4. Beam pipeline on the late-records file; keyed parity still holds and
#      exactly one split key is reported
#
# Requires: Java 17+, Maven, Python 3, and scripts/run_local.sh run once
# (Phase 1) so reference/sample/ exists.
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
WORK="$ROOT/work"
REF="$ROOT/reference/sample"
mkdir -p "$WORK/sample"

python3 "$ROOT/data/gen_obs.py" "$WORK/sample/obs.ebc" >/dev/null
python3 "$ROOT/data/gen_obs.py" "$WORK/sample/obs_late.ebc" --late-records >/dev/null

echo "== 1. unit tests"
mvn -q test
echo "unit tests passed"

echo "== 2. LegacyReport (plain Java) vs WXSUMM reference"
mvn -q compile exec:java -Dexec.mainClass=wx.core.LegacyReport \
    -Dexec.args="$WORK/sample/obs.ebc $WORK/sample/java_summary.txt $WORK/sample/java_errors.txt"
python3 "$ROOT/scripts/parity_check.py" \
    "$WORK/sample/java_summary.txt" "$REF/summary.txt" \
    "$WORK/sample/java_errors.txt"  "$REF/errors.txt"

run_beam() {
    local input=$1 out=$2
    rm -rf "$out"
    mvn -q compile exec:java -Dexec.args="--input=$input --output=$out --runner=DirectRunner"
}

echo "== 3. Beam DirectRunner on the sample file"
run_beam "$WORK/sample/obs.ebc" "$WORK/beam/sample"
python3 "$ROOT/scripts/keyed_parity.py" \
    "$WORK/beam/sample/summary.txt" "$REF/summary.txt" \
    "$WORK/beam/sample/errors.txt"  "$REF/errors.txt"
if [[ -s "$WORK/beam/sample/split_keys.txt" ]]; then
    echo "UNEXPECTED split keys:"; cat "$WORK/beam/sample/split_keys.txt"; exit 1
fi
echo "no split keys (as expected)"

echo "== 4. Beam DirectRunner on the late-records file (split-key demo)"
run_beam "$WORK/sample/obs_late.ebc" "$WORK/beam/late"
python3 "$ROOT/scripts/keyed_parity.py" \
    "$WORK/beam/late/summary.txt" "$REF/summary.txt"
echo "split keys reported:"
cat "$WORK/beam/late/split_keys.txt"
[[ $(wc -l < "$WORK/beam/late/split_keys.txt") -eq 1 ]] || { echo "expected exactly 1 split key"; exit 1; }
echo "PHASE 2 LOCAL OK"
