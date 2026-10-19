#!/usr/bin/env python3
"""Oracle for Phase 3: decode the same GRIB2 files with eccodes and sample the stations.

This plays the part COBOL played in Phases 1 and 2. It shares no code with the Java pipeline:
eccodes unpacks the data and computes every grid point's latitude/longitude from the projection
keys; the nearest grid point is found by great-circle distance over those coordinates.

Writes one CSV row per file x station x variable:
  station, valid_time, variable, grid_index, i, j, grid_lat, grid_lon, value, packing_step

packing_step = 2^E / 10^D from the message: two decoders reading the same packed integers should
agree to within half of it, and the parity SQL uses exactly that tolerance.

Usage:
  hrrr_reference.py work/hrrr/20261014/*.grib2 > work/hrrr/20261014/reference.csv
Requires: pip install eccodes numpy
"""
import csv
import math
import sys
from datetime import datetime, timedelta

import eccodes as ec
import numpy as np

# (discipline, category, number, typeOfFirstFixedSurface, level) -> variable name used in BigQuery
VARIABLES = {
    (0, 0, 0, 103, 2): "tmp_k",
    (0, 0, 6, 103, 2): "dpt_k",
    (0, 2, 2, 103, 10): "ugrd_grid",
    (0, 2, 3, 103, 10): "vgrd_grid",
    (0, 3, 0, 1, None): "pres_pa",
}
STATIONS = [  # must match wx.grib.Station.DEFAULT
    ("KIAH", 29.9844353, -95.3414425),
    ("KHOU", 29.6457998, -95.2772316),
    ("KSGR", 29.6222486, -95.6565342),
    ("KDEN", 39.8616667, -104.6731667),
]
R_KM = 6371.229
_geometry_cache = {}


def geometry(h):
    """Unit vectors of every grid point, cached per grid (keyed by eccodes' grid-section md5)."""
    key = ec.codes_get(h, "md5GridSection")
    if key not in _geometry_cache:
        lat = np.radians(ec.codes_get_array(h, "latitudes"))
        lon = np.radians(ec.codes_get_array(h, "longitudes"))
        xyz = np.stack([np.cos(lat) * np.cos(lon), np.cos(lat) * np.sin(lon), np.sin(lat)], 1)
        _geometry_cache[key] = (xyz, np.degrees(lat), np.degrees(lon))
    return _geometry_cache[key]


def nearest(xyz, lat, lon):
    p = np.array([math.cos(math.radians(lat)) * math.cos(math.radians(lon)),
                  math.cos(math.radians(lat)) * math.sin(math.radians(lon)),
                  math.sin(math.radians(lat))])
    d2 = ((xyz - p) ** 2).sum(1)
    k = int(d2.argmin())
    return k, 2 * R_KM * math.asin(min(1.0, math.sqrt(float(d2[k])) / 2))


def main(paths):
    w = csv.writer(sys.stdout)
    w.writerow(["station", "valid_time", "variable", "grid_index", "i", "j",
                "grid_lat", "grid_lon", "distance_km", "value", "packing_step", "source_file"])
    for path in paths:
        name = path.rsplit("/", 1)[-1]
        with open(path, "rb") as f:
            while (h := ec.codes_grib_new_from_file(f)) is not None:
                try:
                    # Code-table keys come back as abbreviations ('sfc') unless asked for as int.
                    sfc = ec.codes_get(h, "typeOfFirstFixedSurface", ktype=int)
                    level = None if sfc == 1 else ec.codes_get(h, "level", ktype=int)
                    key = (ec.codes_get(h, "discipline", ktype=int), ec.codes_get(h, "parameterCategory", ktype=int),
                           ec.codes_get(h, "parameterNumber", ktype=int), sfc, level)
                    var = VARIABLES.get(key)
                    if var is None:
                        continue
                    if ec.codes_get(h, "stepUnits", ktype=int) != 1:
                        raise ValueError(f"{name}: forecast step not in hours")
                    ref = datetime.strptime("%08d%04d" % (ec.codes_get(h, "dataDate"), ec.codes_get(h, "dataTime")), "%Y%m%d%H%M")
                    valid = ref + timedelta(hours=ec.codes_get(h, "forecastTime"))
                    nx = ec.codes_get(h, "Nx")
                    xyz, glat, glon = geometry(h)
                    values = ec.codes_get_values(h)
                    step = 2.0 ** ec.codes_get(h, "binaryScaleFactor") / 10 ** ec.codes_get(h, "decimalScaleFactor")
                    for st, lat, lon in STATIONS:
                        k, dist = nearest(xyz, lat, lon)
                        if dist > 3.0:      # off the grid: eccodes would snap to an edge cell
                            continue
                        gl = ((float(glon[k]) + 180.0) % 360.0) - 180.0
                        w.writerow([st, valid.strftime("%Y-%m-%d %H:%M:%S"), var, k, k % nx, k // nx,
                                    repr(float(glat[k])), repr(gl), repr(dist),
                                    repr(float(values[k])), repr(step), name])
                finally:
                    ec.codes_release(h)
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1:]))
