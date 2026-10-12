#!/usr/bin/env python3
"""Key-joined parity check for outputs that have no defined order.

Phase 1 compared files byte for byte. Phase 2 output comes from Beam (and later
BigQuery), where row order is not defined, so rows are joined on their key
(station + date, the first 15 characters of a summary line) and compared field
by field. Header and trailer lines of the legacy report are ignored for the
summary; error lines are compared as a multiset.

Exit codes: 0 = match, 1 = differences found, 2 = usage or I/O error.

Usage:
  keyed_parity.py CANDIDATE_SUMMARY REFERENCE_SUMMARY [CANDIDATE_ERRORS REFERENCE_ERRORS]
"""
import sys
from collections import Counter

FIELDS = ["station", "date", "nobs", "ntemp", "tmin", "tmax", "tmean",
          "prcp", "wmax", "pmean"]


def body_lines(path):
    with open(path, encoding="ascii") as f:
        lines = [l.rstrip("\n") for l in f]
    return [l for l in lines if l and not l.startswith(("STATN ", "*TOTAL"))]


def keyed(path):
    rows = {}
    for line in body_lines(path):
        key = line[:15]
        if key in rows:
            raise ValueError(f"{path}: duplicate key {key!r}")
        rows[key] = line
    return rows


def fields(line):
    parts = line.split()
    # The N/A form has the same number of whitespace-separated tokens as the numeric form.
    return dict(zip(FIELDS, parts))


def compare_summary(cand_path, ref_path):
    cand, ref = keyed(cand_path), keyed(ref_path)
    missing = sorted(ref.keys() - cand.keys())
    extra = sorted(cand.keys() - ref.keys())
    diffs = []
    for key in sorted(ref.keys() & cand.keys()):
        if cand[key] != ref[key]:
            fc, fr = fields(cand[key]), fields(ref[key])
            changed = [f"{n}: {fc.get(n)} != {fr.get(n)}" for n in FIELDS if fc.get(n) != fr.get(n)]
            diffs.append((key, changed or ["spacing differs"]))
    ok = not (missing or extra or diffs)
    status = "PASS" if ok else "FAIL"
    print(f"{status}  summary  {len(cand)} candidate rows, {len(ref)} reference rows, joined on station+date")
    for k in missing:
        print(f"  missing in candidate: {k}")
    for k in extra:
        print(f"  extra in candidate:   {k}")
    for k, changed in diffs:
        print(f"  {k}: " + "; ".join(changed))
    return ok


def compare_errors(cand_path, ref_path):
    cand, ref = Counter(body_lines(cand_path)), Counter(body_lines(ref_path))
    ok = cand == ref
    print(f"{'PASS' if ok else 'FAIL'}  errors   {sum(cand.values())} candidate, "
          f"{sum(ref.values())} reference, compared as a multiset")
    for line in sorted((ref - cand).elements()):
        print(f"  missing in candidate: {line}")
    for line in sorted((cand - ref).elements()):
        print(f"  extra in candidate:   {line}")
    return ok


def main(argv):
    if len(argv) not in (2, 4):
        print(__doc__, file=sys.stderr)
        return 2
    try:
        ok = compare_summary(argv[0], argv[1])
        if len(argv) == 4:
            ok &= compare_errors(argv[2], argv[3])
    except (OSError, ValueError) as e:
        print(f"ERROR {e}", file=sys.stderr)
        return 2
    print("KEYED PARITY OK" if ok else "KEYED PARITY FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
