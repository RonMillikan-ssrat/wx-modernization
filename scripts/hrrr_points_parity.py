#!/usr/bin/env python3
"""Local Phase 3 parity: the pipeline's points.csv against the eccodes reference.csv.

Same rules as pipeline/bq/hrrr_parity.sql, so a local pass predicts the BigQuery result:
  - every (station, valid_time, variable) must be in both files
  - the grid cell (i, j) must match exactly
  - values must agree within packing_step / 2 (2^E / 10^D of the message)

Usage:
  hrrr_points_parity.py work/hrrr/out/points.csv work/hrrr/20261014/reference.csv
Exit status 0 = parity, 1 = differences (listed), 2 = bad input.
"""
import csv
import sys

VARIABLES = ["tmp_k", "dpt_k", "ugrd_grid", "vgrd_grid", "pres_pa"]


def load_points(path):
    """points.csv is one row per station-hour; pivot it to one row per variable."""
    out = {}
    with open(path, newline="") as f:
        for r in csv.DictReader(f):
            for var in VARIABLES:
                key = (r["station"], r["valid_time"], var)
                if key in out:
                    raise ValueError(f"{path}: duplicate {key}")
                v = r[var]
                out[key] = (int(r["i"]), int(r["j"]), float(v) if v != "" else None)
    return out


def load_reference(path):
    out = {}
    with open(path, newline="") as f:
        for r in csv.DictReader(f):
            key = (r["station"], r["valid_time"], r["variable"])
            if key in out:
                raise ValueError(f"{path}: duplicate {key}")
            v = r["value"]
            out[key] = (int(r["i"]), int(r["j"]), float(v) if v not in ("", "nan") else None,
                        float(r["packing_step"]))
    return out


def compare(points, reference):
    issues, worst = [], 0.0
    for key in sorted(set(points) | set(reference)):
        if key not in points:
            issues.append((key, "missing from pipeline"))
            continue
        if key not in reference:
            issues.append((key, "extra in pipeline"))
            continue
        pi, pj, pv = points[key]
        ri, rj, rv, step = reference[key]
        if (pi, pj) != (ri, rj):
            issues.append((key, f"grid cell differs: reference ({ri},{rj}), pipeline ({pi},{pj})"))
        elif (pv is None) != (rv is None):
            issues.append((key, f"null mismatch: reference {rv}, pipeline {pv}"))
        elif pv is not None:
            d = abs(pv - rv)
            worst = max(worst, d / step)
            if d > step / 2:
                issues.append((key, f"value differs by {d:g} ({d / step:.2f} packing steps): "
                                    f"reference {rv!r}, pipeline {pv!r}"))
    return issues, worst


def main(argv):
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    try:
        points, reference = load_points(argv[1]), load_reference(argv[2])
    except (OSError, KeyError, ValueError) as e:
        print(f"bad input: {e}", file=sys.stderr)
        return 2
    if not reference:
        print("reference is empty: no stations on the grid?", file=sys.stderr)
        return 2
    issues, worst = compare(points, reference)
    stations = {k[0] for k in reference}
    hours = {k[1] for k in reference}
    print(f"{len(reference)} reference values, {len(points)} pipeline values; "
          f"{len(stations)} stations x {len(hours)} valid times; "
          f"largest difference {worst:.3f} packing steps")
    for (st, vt, var), msg in issues:
        print(f"  {st} {vt} {var}: {msg}")
    if issues:
        print(f"HRRR PARITY FAILED: {len(issues)} difference(s)")
        return 1
    print("HRRR PARITY OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
