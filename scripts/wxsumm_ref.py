#!/usr/bin/env python3
"""Independent Python port of WXSUMM (Phase 2 preview).

Re-implements the COBOL program's decoding, validation, control break and
edited-picture formatting, so its output can be parity-checked byte for
byte against the COBOL output. Each copybook field type has its own
decoder function: these are the functions to unit test and then carry into
the Beam pipeline in Phase 2.

Rules that must match COBOL exactly:
  * only DISPLAY fields are translated from EBCDIC; COMP/COMP-3 stay binary
  * COMP-3 sign nibble: D or B negative; C, F, A, E positive
  * COMP is big-endian
  * ROUNDED = round half away from zero (Decimal ROUND_HALF_UP)
  * groups are consecutive station/date runs in input order; the file is
    NOT re-sorted (EBCDIC and ASCII collating sequences differ)
  * LINE SEQUENTIAL output: GnuCOBOL strips trailing spaces from each line

Usage:
  wxsumm_ref.py OBSIN SUMOUT ERROUT
"""
import struct
import sys
from dataclasses import dataclass
from datetime import date
from decimal import Decimal, ROUND_HALF_UP

LRECL = 40
MISSING = -9999
TENTH = Decimal("0.1")


# --- field decoders (one per COBOL usage) ------------------------------------
def decode_display(raw: bytes) -> str:
    """PIC X / PIC 9 DISPLAY in EBCDIC CP037."""
    return raw.decode("cp037")


def decode_comp3(raw: bytes) -> int:
    """PIC S9(n) COMP-3: two digits per byte, sign in the low nibble of the last byte."""
    h = raw.hex().upper()
    digits, sign = h[:-1], h[-1]
    if not digits.isdigit() or sign not in "ABCDEF":
        raise ValueError(f"invalid packed decimal {h}")
    value = int(digits)
    return -value if sign in "BD" else value


def decode_comp_be(raw: bytes) -> int:
    """PIC 9(4) COMP, big-endian unsigned halfword."""
    return struct.unpack(">H", raw)[0]


@dataclass
class Obs:
    station: str
    date: str
    hour: str
    temp: int
    dewpt: int
    press: int
    wdir: int
    wspd: int
    prcp: int
    qc: str


def decode_record(rec: bytes) -> Obs:
    return Obs(
        station=decode_display(rec[0:6]),
        date=decode_display(rec[6:14]),
        hour=decode_display(rec[14:16]),
        temp=decode_comp3(rec[16:19]),
        dewpt=decode_comp3(rec[19:22]),
        press=decode_comp3(rec[22:25]),
        wdir=decode_comp_be(rec[25:27]),
        wspd=decode_comp_be(rec[27:29]),
        prcp=decode_comp3(rec[29:32]),
        qc=decode_display(rec[32:33]),
    )


# --- validation (mirrors 3100-VALIDATE, same order) --------------------------
def valid_yyyymmdd(s: str) -> bool:
    try:
        y, m, d = int(s[0:4]), int(s[4:6]), int(s[6:8])
        return 1601 <= y and date(y, m, d) is not None
    except ValueError:
        return False


def validate(o: Obs) -> tuple[str, str] | None:
    if not (o.date.isdigit() and o.hour.isdigit()):
        return "R05", "NON-NUMERIC ZONED FIELD"
    if not valid_yyyymmdd(o.date):
        return "R04", "INVALID CALENDAR DATE"
    if int(o.hour) > 23:
        return "R01", "HOUR OUT OF RANGE"
    if o.wdir > 360:
        return "R02", "WIND DIRECTION OUT OF RANGE"
    if o.qc == "X":
        return "R03", "REJECTED BY QC FLAG"
    return None


# --- edited pictures ---------------------------------------------------------
def pic_z(value: int, width: int) -> str:
    """ZZ9 style: zero-suppressed unsigned integer."""
    return f"{value:>{width}d}"


def pic_dec(value: Decimal, width: int, signed: bool) -> str:
    """---9.9 (floating minus) or ZZZ9.9 (unsigned)."""
    text = f"{abs(value):.1f}"
    if signed and value < 0:
        text = "-" + text
    return text.rjust(width)


def cobol_round(num: Decimal, den: Decimal) -> Decimal:
    return (num / den).quantize(TENTH, rounding=ROUND_HALF_UP)


# --- control break -----------------------------------------------------------
class Group:
    def __init__(self, station: str, ymd: str):
        self.station, self.date = station, ymd
        self.nobs = self.ntemp = self.npres = self.nprcp = 0
        self.tsum = self.psum = self.prcp_sum = self.wmax = 0
        self.tmin = self.tmax = 0

    def add(self, o: Obs) -> None:
        self.nobs += 1
        if o.temp != MISSING:
            self.ntemp += 1
            self.tsum += o.temp
            if self.ntemp == 1 or o.temp < self.tmin:
                self.tmin = o.temp
            if self.ntemp == 1 or o.temp > self.tmax:
                self.tmax = o.temp
        self.npres += 1
        self.psum += o.press
        if o.prcp != MISSING:
            self.nprcp += 1
            self.prcp_sum += o.prcp
        self.wmax = max(self.wmax, o.wspd)

    def line(self) -> str:
        if self.ntemp == 0:
            temps = "   N/A    N/A    N/A"
        else:
            temps = " ".join([
                pic_dec(Decimal(self.tmin) / 10, 6, True),
                pic_dec(Decimal(self.tmax) / 10, 6, True),
                pic_dec(cobol_round(Decimal(self.tsum), Decimal(self.ntemp * 10)), 6, True),
            ])
        prcp = "   N/A" if self.nprcp == 0 else pic_dec(Decimal(self.prcp_sum) / 10, 6, False)
        return " ".join([
            self.station,
            self.date,
            pic_z(self.nobs, 3),
            pic_z(self.ntemp, 3),
            temps,
            prcp,
            pic_dec(Decimal(self.wmax) / 10, 5, False),
            pic_dec(cobol_round(Decimal(self.psum), Decimal(self.npres * 10)), 6, False),
        ])


HEADER = ("STATN  DATE     NOB NTP   TMIN   TMAX  T"
          "MEAN   PRCP  WMAX  PMEAN                ")


def run(obs_path: str, sum_path: str, err_path: str) -> int:
    with open(obs_path, "rb") as f:
        data = f.read()
    if len(data) % LRECL:
        print(f"wxsumm_ref: file size {len(data)} is not a multiple of LRECL {LRECL}",
              file=sys.stderr)

    sum_lines = [HEADER]
    err_lines = []
    group = None
    nread = ngood = nrej = ngroups = 0

    for off in range(0, len(data) - len(data) % LRECL, LRECL):
        nread += 1
        o = decode_record(data[off:off + LRECL])
        reason = validate(o)
        if reason:
            nrej += 1
            code, text = reason
            err_lines.append(f"{o.station} {o.date} {o.hour} {code} {text}")
            continue
        ngood += 1
        if group is None or (o.station, o.date) != (group.station, group.date):
            if group is not None:
                sum_lines.append(group.line())
                ngroups += 1
            group = Group(o.station, o.date)
        group.add(o)

    if group is not None:
        sum_lines.append(group.line())
        ngroups += 1
    sum_lines.append(f"*TOTAL READ={nread:07d} ACCEPTED={ngood:07d} "
                     f"REJECTED={nrej:07d} GROUPS={ngroups:07d}")

    for path, lines in ((sum_path, sum_lines), (err_path, err_lines)):
        with open(path, "w", encoding="ascii", newline="\n") as f:
            for line in lines:
                f.write(line.rstrip(" ") + "\n")

    print(f"wxsumm_ref: READ={nread} ACCEPTED={ngood} REJECTED={nrej} GROUPS={ngroups}")
    return 4 if nrej else 0


if __name__ == "__main__":
    if len(sys.argv) != 4:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(run(*sys.argv[1:]))
