#!/usr/bin/env python3
"""Sign the native Level 41 SISX and verify its package metadata."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from symbian.native import require_native


def _files(info) -> list[tuple[str, int, str, int]]:
    return sorted(
        (item.target, item.size, item.sha1, item.capabilities)
        for item in info.files
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--certificate", type=Path, required=True)
    parser.add_argument("--key", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    unsigned = args.input.read_bytes()
    certificate = args.certificate.read_bytes()
    private_key = args.key.read_bytes()
    native = require_native()
    signed = native.sign_sis(unsigned, certificate, private_key)
    before = native.inspect_sis(unsigned)
    after = native.inspect_sis(signed)

    report_path = args.input.parent / "level41-package-inputs.json"
    report = json.loads(report_path.read_text(encoding="utf-8"))
    expected_uid = int(report["package_uid"], 16)
    expected_uid3 = int(report["executable_uid3"], 16)
    if not before.application_registered or before.signed_package:
        raise SystemExit("input SISX is not an unsigned registered application")
    if (
        not after.application_registered
        or not after.signed_package
        or after.options.uid != expected_uid
        or after.executable_uid != expected_uid3
    ):
        raise SystemExit("signed SISX metadata failed Level 41 verification")
    if _files(before) != _files(after):
        raise SystemExit("signing changed the embedded Level 41 file list")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(signed)
    print(
        f"Signed and verified {args.output.name}: {len(after.files)} files, "
        f"package UID 0x{after.options.uid:08X}, "
        f"executable UID3 0x{after.executable_uid:08X}, "
        f"{len(signed)} bytes, SHA-256 {hashlib.sha256(signed).hexdigest()}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
