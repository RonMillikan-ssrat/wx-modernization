#!/usr/bin/env python3
"""Byte-for-byte parity check between a candidate output and a reference.

Exit codes: 0 = identical, 1 = different, 2 = usage or I/O error.

On a mismatch it reports both SHA-256 digests, the sizes, the first
differing byte offset, and (for text files) the first differing line with
a caret under the first differing column. That is usually enough to tell
a code-page, rounding, or record-order problem apart at a glance.

Usage:
  parity_check.py CANDIDATE REFERENCE [CANDIDATE REFERENCE ...]
"""
import hashlib
import sys


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def first_diff(a: bytes, b: bytes) -> int:
    for i, (x, y) in enumerate(zip(a, b)):
        if x != y:
            return i
    return min(len(a), len(b))


def describe_text_diff(a: bytes, b: bytes, offset: int) -> list[str]:
    line_no = a.count(b"\n", 0, offset) + 1
    start = a.rfind(b"\n", 0, offset) + 1
    col = offset - start
    a_line = a[start:].split(b"\n", 1)[0].decode("latin-1")
    b_line = b[start:].split(b"\n", 1)[0].decode("latin-1")
    return [
        f"  first difference at line {line_no}, column {col + 1}",
        f"  candidate: {a_line}",
        f"  reference: {b_line}",
        f"             {' ' * col}^",
    ]


def compare(cand_path: str, ref_path: str) -> bool:
    with open(cand_path, "rb") as f:
        cand = f.read()
    with open(ref_path, "rb") as f:
        ref = f.read()
    hc, hr = sha256(cand), sha256(ref)
    if hc == hr:
        print(f"PASS  {cand_path} == {ref_path}  sha256={hc[:16]}  "
              f"{len(cand)} bytes")
        return True
    off = first_diff(cand, ref)
    print(f"FAIL  {cand_path} != {ref_path}")
    print(f"  candidate sha256={hc}  {len(cand)} bytes")
    print(f"  reference sha256={hr}  {len(ref)} bytes")
    print(f"  first differing byte offset: {off} "
          f"(0x{cand[off:off+1].hex() or '--'} vs 0x{ref[off:off+1].hex() or '--'})")
    is_text = b"\x00" not in cand[:4096] and b"\x00" not in ref[:4096]
    if is_text and off < min(len(cand), len(ref)):
        for line in describe_text_diff(cand, ref, off):
            print(line)
    elif off == min(len(cand), len(ref)):
        print("  one file is a prefix of the other (truncated or extra data)")
    return False


def main(argv: list[str]) -> int:
    if len(argv) < 2 or len(argv) % 2:
        print(__doc__, file=sys.stderr)
        return 2
    ok = True
    try:
        for cand, ref in zip(argv[0::2], argv[1::2]):
            ok &= compare(cand, ref)
    except OSError as e:
        print(f"ERROR {e}", file=sys.stderr)
        return 2
    print("PARITY OK" if ok else "PARITY FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
