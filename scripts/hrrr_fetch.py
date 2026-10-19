#!/usr/bin/env python3
"""Land HRRR surface fields: download only the GRIB2 messages the pipeline needs.

Each HRRR file comes with an inventory (.idx), one line per GRIB2 message:

    66:41208447:d=2026101412:TMP:2 m above ground:anl:
    ^  ^        ^            ^   ^                ^
    |  byte     reference    var level            forecast
    message no. offset       time

A message runs from its offset to the byte before the next message's offset, so the five fields
we need (about 5-10 MB) can be fetched with HTTP Range requests instead of the whole ~120 MB file.
The downloaded messages are concatenated into one valid GRIB2 file per hour.

Usage:
  hrrr_fetch.py --date 20261014 [--hours 0-23] [--fxx 0] [--out work/hrrr]
  hrrr_fetch.py --date 20261014 --list        # show matching inventory lines only

Base URL defaults to the NOAA HRRR public dataset on Google Cloud (anonymous HTTPS). Check it with
  curl -sI https://storage.googleapis.com/high-resolution-rapid-refresh/hrrr.20261014/conus/hrrr.t00z.wrfsfcf00.grib2.idx
Override with --base-url if the bucket or layout differs.
"""
import argparse
import hashlib
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE_URL = "https://storage.googleapis.com/high-resolution-rapid-refresh"

# (idx variable, idx level) -> matches wx.grib.HrrrVariable
FIELDS = [
    ("TMP", "2 m above ground"),
    ("DPT", "2 m above ground"),
    ("UGRD", "10 m above ground"),
    ("VGRD", "10 m above ground"),
    ("PRES", "surface"),
]


def parse_idx(text):
    """Returns [(msg_no, offset, ref, var, level, fcst)] in file order."""
    out = []
    for line in text.splitlines():
        if not line.strip():
            continue
        f = line.split(":")
        if len(f) < 6:
            raise ValueError(f"unexpected .idx line: {line!r}")
        out.append((int(f[0]), int(f[1]), f[2], f[3], f[4], f[5]))
    for a, b in zip(out, out[1:]):
        if b[1] <= a[1]:
            raise ValueError(f".idx offsets not increasing at message {b[0]}")
    return out


def select(entries, fcst):
    """One (start, end_inclusive_or_None, entry) per field; fails loudly on 0 or >1 matches."""
    chosen = []
    for var, level in FIELDS:
        hits = [k for k, e in enumerate(entries) if e[3] == var and e[4] == level and e[5] == fcst]
        if len(hits) != 1:
            near = [":".join(map(str, e)) for e in entries if e[3] == var]
            raise LookupError(f"{var}:{level}:{fcst} matched {len(hits)} lines; lines for {var}: {near}")
        k = hits[0]
        start = entries[k][1]
        end = entries[k + 1][1] - 1 if k + 1 < len(entries) else None
        chosen.append((start, end, entries[k]))
    return chosen


def http_get(url, byte_range=None, attempts=4):
    headers = {"User-Agent": "wx-modernization/hrrr_fetch"}
    if byte_range:
        headers["Range"] = "bytes=%d-%s" % (byte_range[0], "" if byte_range[1] is None else byte_range[1])
    for n in range(attempts):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as r:
                body = r.read()
                if byte_range and r.status != 206:
                    raise IOError(f"{url}: server ignored Range (HTTP {r.status})")
                return body
        except (urllib.error.URLError, IOError) as e:
            if isinstance(e, urllib.error.HTTPError) and e.code == 404:
                raise
            if n == attempts - 1:
                raise
            time.sleep(2 ** n)


def fetch_hour(base, date, hour, fxx, out_dir, list_only=False):
    name = f"hrrr.t{hour:02d}z.wrfsfcf{fxx:02d}.grib2"
    url = f"{base}/hrrr.{date}/conus/{name}"
    entries = parse_idx(http_get(url + ".idx").decode("ascii"))
    fcst = "anl" if fxx == 0 else f"{fxx} hour fcst"
    chosen = select(entries, fcst)
    if list_only:
        for start, end, e in chosen:
            print(f"  {name}: {':'.join(map(str, e))}  bytes {start}-{'' if end is None else end}")
        return None
    parts = []
    for start, end, e in chosen:
        body = http_get(url, (start, end))
        if body[:4] != b"GRIB" or body[-4:] != b"7777":
            raise IOError(f"{name} message {e[0]}: not a complete GRIB message ({len(body)} bytes)")
        parts.append(body)
    data = b"".join(parts)
    out = out_dir / f"hrrr.{date}.t{hour:02d}z.wrfsfcf{fxx:02d}.subset.grib2"
    out.write_bytes(data)
    return {"file": out.name, "source": url, "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
            "messages": [":".join(map(str, e)) for _, _, e in chosen]}


def hours_arg(s):
    if "-" in s:
        a, b = s.split("-")
        return list(range(int(a), int(b) + 1))
    return [int(x) for x in s.split(",")]


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--date", required=True, help="YYYYMMDD")
    ap.add_argument("--hours", default="0-23", type=hours_arg)
    ap.add_argument("--fxx", default=0, type=int, help="forecast hour (0 = analysis)")
    ap.add_argument("--out", default="work/hrrr")
    ap.add_argument("--base-url", default=BASE_URL)
    ap.add_argument("--list", action="store_true", help="only print the matching .idx lines")
    a = ap.parse_args(argv)

    out_dir = Path(a.out) / a.date
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest = []
    for h in a.hours:
        r = fetch_hour(a.base_url.rstrip("/"), a.date, h, a.fxx, out_dir, a.list)
        if r:
            manifest.append(r)
            print(f"{r['file']}: {r['bytes']:,} bytes, {len(r['messages'])} messages")
    if manifest:
        (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=1))
        total = sum(m["bytes"] for m in manifest)
        print(f"{len(manifest)} files, {total:,} bytes in {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
