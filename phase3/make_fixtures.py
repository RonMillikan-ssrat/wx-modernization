#!/usr/bin/env python3
"""Build GRIB2 test fixtures with eccodes, and record eccodes' own answers as the oracle.

Writes into phase3/fixtures/:
  small.grib2        5 messages (TMP/DPT 2 m, UGRD/VGRD 10 m, PRES sfc) on a 60 x 40
                     Lambert conformal grid around Houston, HRRR's projection parameters
                     and packing style, small enough to commit.
  small_expected.csv eccodes decode of small.grib2: per message metadata, and the
                     nearest grid point + value at each station.
  hrrr_nearest.csv   on the full HRRR CONUS grid (1799 x 1059): eccodes nearest grid
                     point for the stations and 400 seeded random points. Used to test
                     the Java Lambert projection without committing a 1.9M-point file.

Grid values are a function of (i, j) so that picking a neighbouring cell changes the value.
"""
import csv
import math
import random
import sys
from pathlib import Path

import eccodes as ec

OUT = Path(__file__).resolve().parent / "fixtures"

# HRRR CONUS (NOAA EMC HRRR grid spec): Lambert conformal, tangent cone at 38.5N
HRRR = dict(Nx=1799, Ny=1059, La1=21.138, Lo1=237.28, LoV=262.5, Latin=38.5, D=3000.0)
STATIONS = [  # AirNav / SkyVector airport reference points
    ("KIAH", 29.9844353, -95.3414425),
    ("KHOU", 29.6457998, -95.2772316),
    ("KSGR", 29.6222486, -95.6565342),
    ("KDEN", 39.8616667, -104.6731667),
]
# (shortName for info, discipline, category, number, surface type, scaled value, units)
FIELDS = [
    ("2t",  0, 0, 0, 103, 2,  "K"),
    ("2d",  0, 0, 6, 103, 2,  "K"),
    ("10u", 0, 2, 2, 103, 10, "m s-1"),
    ("10v", 0, 2, 3, 103, 10, "m s-1"),
    ("sp",  0, 3, 0, 1,   None, "Pa"),
]


def field_values(name, nx, ny):
    """Deterministic, smooth-but-cell-distinct values in realistic ranges."""
    vals = []
    for j in range(ny):
        for i in range(nx):
            a, b = math.sin(i * 0.37 + j * 0.11), math.cos(j * 0.29 - i * 0.07)
            if name == "2t":
                v = 295.0 + 8.0 * a + 3.0 * b + 0.013 * i - 0.021 * j
            elif name == "2d":
                v = 288.0 + 6.0 * a - 2.0 * b
            elif name == "10u":
                v = 4.0 * a + 2.5 * b - 1.0
            elif name == "10v":
                v = -3.0 * b + 1.5 * a + 0.5
            else:
                v = 101325.0 + 900.0 * a - 400.0 * b
            vals.append(v)
    return vals


def make_message(sample, name, disc, cat, num, sfc, lev, nx, ny, la1, lo1, packing):
    h = ec.codes_clone(sample)
    ec.codes_set(h, "discipline", disc)
    ec.codes_set(h, "centre", 7)                  # NCEP
    ec.codes_set(h, "dataDate", 20261014)
    ec.codes_set(h, "dataTime", 1200)
    ec.codes_set(h, "significanceOfReferenceTime", 1)
    ec.codes_set(h, "gridDefinitionTemplateNumber", 30)
    ec.codes_set(h, "shapeOfTheEarth", 6)          # sphere, R = 6371229 m
    ec.codes_set(h, "Nx", nx)
    ec.codes_set(h, "Ny", ny)
    ec.codes_set(h, "latitudeOfFirstGridPointInDegrees", la1)
    ec.codes_set(h, "longitudeOfFirstGridPointInDegrees", lo1)
    ec.codes_set(h, "LaDInDegrees", HRRR["Latin"])
    ec.codes_set(h, "LoVInDegrees", HRRR["LoV"])
    ec.codes_set(h, "DxInMetres", HRRR["D"])
    ec.codes_set(h, "DyInMetres", HRRR["D"])
    ec.codes_set(h, "Latin1InDegrees", HRRR["Latin"])
    ec.codes_set(h, "Latin2InDegrees", HRRR["Latin"])
    ec.codes_set(h, "latitudeOfSouthernPoleInDegrees", -90.0)
    ec.codes_set(h, "longitudeOfSouthernPoleInDegrees", 0.0)
    ec.codes_set(h, "iScansNegatively", 0)
    ec.codes_set(h, "jScansPositively", 1)
    ec.codes_set(h, "uvRelativeToGrid", 1)        # HRRR winds are grid-relative
    ec.codes_set(h, "productDefinitionTemplateNumber", 0)
    ec.codes_set(h, "parameterCategory", cat)
    ec.codes_set(h, "parameterNumber", num)
    ec.codes_set(h, "typeOfFirstFixedSurface", sfc)
    if lev is None:
        ec.codes_set_missing(h, "scaleFactorOfFirstFixedSurface")
        ec.codes_set_missing(h, "scaledValueOfFirstFixedSurface")
    else:
        ec.codes_set(h, "scaleFactorOfFirstFixedSurface", 0)
        ec.codes_set(h, "scaledValueOfFirstFixedSurface", lev)
    ec.codes_set(h, "typeOfSecondFixedSurface", 255)
    ec.codes_set(h, "forecastTime", 0)
    ec.codes_set(h, "packingType", packing)
    ec.codes_set(h, "decimalScaleFactor", 2 if name != "sp" else 0)
    ec.codes_set(h, "bitsPerValue", 16)
    ec.codes_set_values(h, field_values(name, nx, ny))
    return h


def main():
    OUT.mkdir(exist_ok=True)
    random.seed(20261015)
    # Samples are embedded in the eccodes wheel (MEMFS), so load by name, not by path.
    sample = ec.codes_grib_new_from_samples("GRIB2")

    # --- small committed fixture -------------------------------------------------
    nx, ny = 60, 40
    la1, lo1 = 29.20, 264.20            # south-west corner near Houston (lon in 0..360)
    small = OUT / "small.grib2"
    with open(small, "wb") as f:
        for name, disc, cat, num, sfc, lev, _ in FIELDS:
            h = make_message(sample, name, disc, cat, num, sfc, lev, nx, ny, la1, lo1,
                             "grid_complex_spatial_differencing")
            ec.codes_write(h, f)
            ec.codes_release(h)

    rows = []
    with open(small, "rb") as f:
        msg = 0
        while (h := ec.codes_grib_new_from_file(f)) is not None:
            msg += 1
            meta = {k: ec.codes_get(h, k) for k in (
                "discipline", "parameterCategory", "parameterNumber",
                "typeOfFirstFixedSurface", "Nx", "Ny", "dataDate", "dataTime",
                "forecastTime", "dataRepresentationTemplateNumber",
                "binaryScaleFactor", "decimalScaleFactor", "bitsPerValue")}
            meta["referenceValue"] = repr(ec.codes_get_double(h, "referenceValue"))
            vals = ec.codes_get_values(h)
            meta["vmin"], meta["vmax"] = repr(float(vals.min())), repr(float(vals.max()))
            meta["vsum"] = repr(float(vals.sum()))
            meta["packing_step"] = repr(2.0 ** meta["binaryScaleFactor"] / 10 ** meta["decimalScaleFactor"])
            meta["level"] = ec.codes_get(h, "level")
            for st, lat, lon in STATIONS[:3]:      # KDEN is off this small grid
                n = ec.codes_grib_find_nearest(h, lat, lon)[0]
                rows.append(dict(msg=msg, station=st, lat=lat, lon=lon,
                                 index=n["index"], i=n["index"] % nx, j=n["index"] // nx,
                                 grid_lat=repr(n["lat"]), grid_lon=repr(n["lon"]),
                                 distance_km=repr(n["distance"]), value=repr(n["value"]),
                                 **meta))
            ec.codes_release(h)
    with open(OUT / "small_expected.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    print(f"small.grib2: {small.stat().st_size} bytes, {msg} messages; {len(rows)} expected rows")

    # --- full HRRR grid: nearest-point oracle (grid not committed) ---------------
    # eccodes computes the latitude/longitude of every grid point from the message's
    # projection keys; nearest neighbour is then a great-circle search over those points.
    # (codes_grib_find_nearest gives the same answer but is too slow for 1.9M points.)
    import numpy as np
    h = make_message(sample, "2t", *FIELDS[0][1:6], HRRR["Nx"], HRRR["Ny"],
                     HRRR["La1"], HRRR["Lo1"], "grid_simple")
    glat = np.radians(ec.codes_get_array(h, "latitudes"))
    glon = np.radians(ec.codes_get_array(h, "longitudes"))
    gxyz = np.stack([np.cos(glat) * np.cos(glon), np.cos(glat) * np.sin(glon), np.sin(glat)], 1)
    R_KM = 6371.229
    pts = [(s_, la, lo) for s_, la, lo in STATIONS]
    while len(pts) < len(STATIONS) + 400:
        la, lo = random.uniform(24.0, 49.0), random.uniform(-122.0, -72.0)
        pts.append((f"R{len(pts):03d}", la, lo))
    out = []
    for name, la, lo in pts:
        p_ = np.array([math.cos(math.radians(la)) * math.cos(math.radians(lo)),
                       math.cos(math.radians(la)) * math.sin(math.radians(lo)),
                       math.sin(math.radians(la))])
        d2 = ((gxyz - p_) ** 2).sum(1)
        k = int(d2.argmin())
        second = float(np.partition(d2, 1)[1])
        dist = 2 * R_KM * math.asin(min(1.0, math.sqrt(float(d2[k])) / 2))
        if dist > 3.0:                   # outside the grid
            continue
        # skip near-ties (two cells within 1 m of equally close): either answer is right
        if 2 * R_KM * 1000 * (math.sqrt(second) - math.sqrt(float(d2[k]))) / 2 < 1.0:
            continue
        out.append(dict(name=name, lat=repr(la), lon=repr(lo), index=k,
                        i=k % HRRR["Nx"], j=k // HRRR["Nx"],
                        grid_lat=repr(math.degrees(float(glat[k]))),
                        grid_lon=repr(math.degrees(float(glon[k]))),
                        distance_km=repr(dist)))
    lats, lons = ec.codes_get_array(h, "latitudes"), ec.codes_get_array(h, "longitudes")
    for k, label in [(0, "corner_sw"), (HRRR["Nx"] - 1, "corner_se"),
                     (HRRR["Nx"] * (HRRR["Ny"] - 1), "corner_nw"),
                     (HRRR["Nx"] * HRRR["Ny"] - 1, "corner_ne")]:
        out.append(dict(name=label, lat=repr(float(lats[k])), lon=repr(float(lons[k])),
                        index=k, i=k % HRRR["Nx"], j=k // HRRR["Nx"],
                        grid_lat=repr(float(lats[k])), grid_lon=repr(float(lons[k])),
                        distance_km="0.0"))
    ec.codes_release(h)
    with open(OUT / "hrrr_nearest.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(out[0]))
        w.writeheader()
        w.writerows(out)
    print(f"hrrr_nearest.csv: {len(out)} points (inside the grid) incl. 4 corners")
    ec.codes_release(sample)


if __name__ == "__main__":
    sys.exit(main())
