#!/usr/bin/env python3
"""Package v1.0.60: v1.0.59 payloads with a 2 MiB E32 maximum heap.

v1.0.59 shipped the QTX2 build with the converted 1 MiB E32 heap ceiling. This
candidate keeps every payload byte-identical and raises only the embedded E32
maximum heap to 2,097,152 bytes (offset 60), refreshing the header CRC at
offset 20 the way the v1.0.28 reference image does.

The package is serialised by `sis33_python_writer`, a byte-exact Python port of
the scoped stage-3 writer. Its `--verify` path re-serialises the result and
requires a byte-for-byte match, which is how this script proves its own output:
v1.0.59 round-trips through it unchanged, so any difference in v1.0.60 is
exactly the E32 heap patch and the offsets it covers.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import sis33_python_writer as writer

PROJECT = Path(__file__).resolve().parent
ROOT = PROJECT.parent.parent
PREVIOUS = ROOT / "google_drive/quantum-level41-walk-v1.0.59-unsigned.sis"
OUTPUT_DIR = PROJECT / "output/v1.0.60-sdk030-e65-heap2m"
VERSION = (1, 0, 60)
TARGET_HEAP_MAX = 0x200000
PREVIOUS_SHA256 = "a7f178b423e3d7b2b4c760cab116c68db04b63ce9d516be248ccd64332806582"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def build(previous: bytes) -> tuple[bytes, dict]:
    if sha256(previous) != PREVIOUS_SHA256:
        raise SystemExit(
            "The v1.0.59 baseline does not match the shipped package hash: "
            f"{sha256(previous)}"
        )
    info = writer.inspect_package(previous)
    before = writer.inspect_e32(info.executable)
    if before.heap_max != 0x100000:
        raise SystemExit(f"v1.0.59 already has heap 0x{before.heap_max:x}")
    print(f"baseline: {info.options.name!r} v{'.'.join(map(str, info.options.version))} "
          f"uid 0x{info.options.uid:08x}, {len(info.payloads)} files")
    print("patching the embedded E32 heap ceiling:")
    patched = writer.patch_e32_heap(info.executable, TARGET_HEAP_MAX)
    after = writer.inspect_e32(patched)

    assets: list[writer.ApplicationFile] = []
    data_files: list[writer.ApplicationFile] = []
    for target, payload in zip(info.targets[1:], info.payloads[1:]):
        entry = writer.ApplicationFile(target, payload)
        if target.startswith(writer.DATA_TARGET_PREFIX) or target.startswith(
            writer.APP_PRIVATE_TARGET_PREFIX
        ):
            data_files.append(entry)
        else:
            assets.append(entry)
    options = writer.PackageOptions(
        uid=info.options.uid,
        name=info.options.name,
        vendor=info.options.vendor,
        executable_name=info.options.executable_name,
        version=VERSION,
    )
    package = writer.build_files(patched, assets, data_files, options)

    # Prove the output the same way the writer does: parse and re-serialise.
    checked = writer.inspect_package(package)
    if checked.options.version != VERSION:
        raise SystemExit("packaged version did not take effect")
    if checked.executable != patched:
        raise SystemExit("packaged E32 differs from the patched image")
    if checked.targets[1:] != info.targets[1:]:
        raise SystemExit("packaged payload targets changed")
    for old_target, old_payload, new_target, new_payload in zip(
        info.targets[1:], info.payloads[1:], checked.targets[1:], checked.payloads[1:]
    ):
        if old_target != new_target or old_payload != new_payload:
            raise SystemExit(f"payload changed: {new_target}")
    checked_before = writer.inspect_e32(checked.executable)
    report = {
        "version": ".".join(str(part) for part in VERSION),
        "baseline": str(PREVIOUS.relative_to(ROOT)),
        "baseline_sha256": sha256(previous),
        "executable_sha256_before": sha256(info.executable),
        "executable_sha256_after": sha256(patched),
        "header_crc_before": f"0x{before.header_crc:08x}",
        "header_crc_after": f"0x{after.header_crc:08x}",
        "heap": {
            "minimum": after.heap_min,
            "maximum_before": before.heap_max,
            "maximum_after": checked_before.heap_max,
            "stack_minimum": after.stack_min,
            "stack_maximum": after.stack_max,
        },
        "files": len(checked.payloads),
        "package_sha256": sha256(package),
        "package_size": len(package),
        "payloads_unchanged": True,
    }
    return package, report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    package, report = build(PREVIOUS.read_bytes())
    name = "quantum-level41-walk-v1.0.60-heap2m-unsigned.sis"
    output = OUTPUT_DIR / name
    if output.exists() and not args.force:
        raise SystemExit(f"refusing to overwrite {output} without --force")
    output.write_bytes(package)
    (OUTPUT_DIR / "v1.0.60-build-report.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8"
    )
    print(f"\nwrote {output} ({len(package)} bytes)")
    print(f"sha256 {report['package_sha256']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
