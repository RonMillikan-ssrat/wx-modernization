#!/usr/bin/env python3
"""Generate synthetic EBCDIC hourly observations matching copybook WXOBS.

Output is a fixed-length (LRECL 40) file with no record delimiters, the way
a mainframe sequential dataset arrives after a binary FTP or Connect:Direct
transfer. Text and zoned fields are CP037; COMP fields are big-endian;
COMP-3 fields are packed decimal with C/D/F sign nibbles.

The data is deterministic (fixed seed) so the reference output is stable,
and it deliberately plants the traps listed in the exercise doc:

  * negative temperatures and a -9999 missing sentinel (incl. a whole
    station-day with no valid temperature -> N/A in the summary)
  * F-signed (unsigned-style) packed decimal in the pressure field
  * wind direction 258 (0x0102) to expose byte-order mistakes
  * a valid leap day (2028-02-29) and an invalid one (2027-02-29) -> R04
  * hour 24 -> R01, wind direction 361 -> R02, QC flag X -> R03
  * a record with a blank (EBCDIC spaces) date -> R05
  * station IDs that sort differently in EBCDIC and ASCII ("72243A" vs
    "KIAH01"); records are ordered in EBCDIC collating sequence

Usage:
  python3 gen_obs.py OUTFILE [--seed N]
"""
import argparse
import random
import struct
import sys
from datetime import date, timedelta

LRECL = 40
CODEPAGE = "cp037"

STATIONS = {
    # id      base temp (tenths C), diurnal range (tenths C)
    "KIAH01": (180, 90),
    "KHOU02": (190, 80),
    "KSGR-1": (175, 95),
    "72243A": (170, 85),
    "PAFA03": (-185, 60),   # Fairbanks: exercises negative COMP-3
}
START = date(2028, 2, 27)
NDAYS = 4                   # 27, 28, 29 (leap day), Mar 1


def pack(value: int, ndigits: int, sign: str | None = None) -> bytes:
    """Encode an integer as COMP-3. ndigits must be odd (fills whole bytes)."""
    if ndigits % 2 == 0:
        raise ValueError("ndigits must be odd for whole-byte packing")
    if sign is None:
        sign = "D" if value < 0 else "C"
    digits = f"{abs(value):0{ndigits}d}"
    if len(digits) > ndigits:
        raise ValueError(f"{value} does not fit in {ndigits} digits")
    return bytes.fromhex(digits + sign)


def ebcdic(text: str, width: int) -> bytes:
    raw = text.ljust(width)[:width].encode(CODEPAGE)
    assert len(raw) == width
    return raw


def record(station, ymd, hour, temp, dewpt, press, wdir, wspd, prcp,
           qc=" ", press_sign=None, ascii_date=False) -> bytes:
    date_bytes = ymd.encode("ascii") if ascii_date else ebcdic(ymd, 8)
    rec = (
        ebcdic(station, 6)
        + date_bytes
        + ebcdic(f"{hour:02d}", 2)
        + pack(temp, 5)                      # S9(4) COMP-3 -> 3 bytes
        + pack(dewpt, 5)                     # S9(4) COMP-3 -> 3 bytes
        + pack(press, 5, press_sign)         # S9(5) COMP-3 -> 3 bytes
        + struct.pack(">H", wdir)            # 9(4) COMP big-endian
        + struct.pack(">H", wspd)            # 9(4) COMP big-endian
        + pack(prcp, 5)                      # S9(5) COMP-3 -> 3 bytes
        + ebcdic(qc, 1)
        + ebcdic("", 7)                      # FILLER
    )
    assert len(rec) == LRECL, len(rec)
    return rec


def build(seed: int) -> tuple[list[bytes], list[str]]:
    rng = random.Random(seed)
    notes: list[str] = []
    # EBCDIC collating order: letters (C1-E9) sort before digits (F0-F9)
    order = sorted(STATIONS, key=lambda s: s.encode(CODEPAGE))
    notes.append("station order (EBCDIC): " + ", ".join(order))

    records: list[bytes] = []
    for stn in order:
        base, rng_t = STATIONS[stn]
        for d in range(NDAYS):
            day = START + timedelta(days=d)
            ymd = day.strftime("%Y%m%d")
            for hour in range(24):
                # diurnal curve: coldest ~05h, warmest ~15h
                phase = (hour - 5) % 24
                shape = phase / 10 if phase <= 10 else (24 - phase) / 14
                temp = base - rng_t // 2 + int(rng_t * shape) + rng.randint(-8, 8)
                dewpt = temp - rng.randint(5, 60)
                press = 10130 + rng.randint(-120, 120)
                wdir = rng.randint(0, 360)
                wspd = rng.randint(0, 250)
                prcp = rng.choice([0] * 8 + [2, 5, 13])
                press_sign = None
                qc = " "

                # --- planted traps -----------------------------------
                if stn == "KIAH01" and ymd == "20280228" and hour in (3, 4):
                    temp = -9999
                    if hour == 3:
                        notes.append("KIAH01 20280228 h03-04: TEMP -9999 sentinel")
                if stn == "KHOU02" and ymd == "20280301":
                    temp = -9999                       # whole day missing
                    prcp = -9999
                    if hour == 0:
                        notes.append("KHOU02 20280301: all TEMP and PRCP missing -> N/A")
                if stn == "KSGR-1" and hour % 6 == 0:
                    press_sign = "F"                   # unsigned-style packed
                    if ymd == "20280227" and hour == 0:
                        notes.append("KSGR-1 every 6th hour: PRESS packed with F sign")
                if stn == "72243A" and ymd == "20280227" and hour == 12:
                    wdir = 258                         # 0x0102 byte-order probe
                    notes.append("72243A 20280227 h12: WDIR 258 (0x0102)")
                if stn == "PAFA03" and ymd == "20280229" and hour == 0:
                    notes.append("PAFA03 20280229: valid leap day, negative temps")

                records.append(record(stn, ymd, hour, temp, dewpt, press,
                                      wdir, wspd, prcp, qc, press_sign))

        # rejects appended at the end of each station's run
        if stn == "KIAH01":
            records.append(record(stn, "20270229", 10, 150, 100, 10100, 90, 50, 0))
            notes.append("KIAH01 20270229: invalid leap day -> R04")
            records.append(record(stn, "20280301", 24, 150, 100, 10100, 90, 50, 0))
            notes.append("KIAH01 20280301 h24: hour out of range -> R01")
        if stn == "KHOU02":
            records.append(record(stn, "20280301", 12, 150, 100, 10100, 361, 50, 0))
            notes.append("KHOU02 20280301 h12: WDIR 361 -> R02")
        if stn == "72243A":
            records.append(record(stn, "20280301", 13, 150, 100, 10100, 90, 50, 0,
                                  qc="X"))
            notes.append("72243A 20280301 h13: QC flag X -> R03")
        if stn == "PAFA03":
            records.append(record(stn, "        ", 14, -150, -200, 10100, 90, 50, 0))
            notes.append("PAFA03 (blank date) h14: non-numeric zoned field -> R05")
    return records, notes


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("outfile")
    ap.add_argument("--seed", type=int, default=20261012)
    args = ap.parse_args()

    records, notes = build(args.seed)
    with open(args.outfile, "wb") as f:
        for r in records:
            f.write(r)
    print(f"wrote {len(records)} records x {LRECL} bytes = "
          f"{len(records) * LRECL} bytes to {args.outfile}")
    for n in notes:
        print("  trap:", n)
    return 0


if __name__ == "__main__":
    sys.exit(main())
