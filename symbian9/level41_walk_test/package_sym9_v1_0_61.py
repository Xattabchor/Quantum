#!/usr/bin/env python3
"""Package v1.0.61: v1.0.60 payloads with the 16-bit weapon build.

Two things changed in the executable, both owned by the weapon renderer:

* The weapon body is stored as RGB1555 and only the muzzle flash stays
  ARGB4444. Previously buildSixteen() refused 1555 for any sprite whose
  resized view reported alphaMixing, which is true of every bilinear resize,
  so the weapon body never got a 16-bit twin at all.
* weapon-sight-weapon is no longer loaded. The reticle needs `zoom`, which
  this sample never raises, so its 300x320 resize buffer (384,000 bytes)
  and its 16-bit twin (192,000 bytes) were allocated and never read.

Every other payload is byte-identical to v1.0.60, and the embedded E32 keeps
the 2,097,152 byte maximum heap that v1.0.60 introduced. The package is
serialised by `sis33_python_writer`, whose `--verify` path re-serialises and
requires a byte-for-byte match; `package_sym9_v1_0_61.py --verify` uses it to
prove this output round-trips.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

import sis33_python_writer as writer

PROJECT = Path(__file__).resolve().parent
ROOT = PROJECT.parent.parent
PREVIOUS = ROOT / "google_drive/quantum-level41-walk-v1.0.60-heap2m-unsigned.sis"
OUTPUT_DIR = PROJECT / "output/v1.0.61-sdk030-e65-weapon1555"
VERSION = (1, 0, 61)
PREVIOUS_SHA256 = "3e36effd7e50e04a2bbd61d0328734e7dd57cca81e91d9e76d7d0471762ad79a"
TARGET_HEAP_MAX = 0x200000
EXECUTABLE = (
    ROOT
    / "symbian9/build/e65-level41-walk-asm/level41_device_test/e32"
    / "quantum_level41_walk_test.exe"
)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def build(previous: bytes, executable: bytes, *, verify_previous: bool) -> tuple[bytes, dict]:
    if verify_previous and sha256(previous) != PREVIOUS_SHA256:
        raise SystemExit(
            "The v1.0.60 baseline does not match the shipped package hash: "
            f"{sha256(previous)}"
        )
    info = writer.inspect_package(previous)

    # Only the executable is replaced; every other payload is carried over.
    patched = writer.patch_e32_heap(executable, TARGET_HEAP_MAX)
    before = writer.inspect_e32(executable)
    after = writer.inspect_e32(patched)
    if before.heap_max not in (0x100000, TARGET_HEAP_MAX):
        raise SystemExit(f"unexpected built heap 0x{before.heap_max:x}")
    if after.heap_max != TARGET_HEAP_MAX:
        raise SystemExit(f"heap patch did not take: 0x{after.heap_max:x}")

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

    checked = writer.inspect_package(package)
    if checked.options.version != VERSION:
        raise SystemExit("packaged version did not take effect")
    if checked.executable != patched:
        raise SystemExit("packaged E32 differs from the built image")
    if checked.targets[1:] != info.targets[1:]:
        raise SystemExit("packaged payload targets changed")
    for old_target, old_payload, new_target, new_payload in zip(
        info.targets[1:], info.payloads[1:], checked.targets[1:], checked.payloads[1:]
    ):
        if old_target != new_target or old_payload != new_payload:
            raise SystemExit(f"payload changed: {new_target}")

    report = {
        "version": ".".join(str(part) for part in VERSION),
        "baseline": str(PREVIOUS.relative_to(ROOT)),
        "baseline_sha256": sha256(previous),
        "executable": str(EXECUTABLE.relative_to(ROOT)),
        "executable_sha256_build": sha256(executable),
        "executable_sha256_after_heap_patch": sha256(patched),
        "executable_size": len(patched),
        "heap": {
            "minimum": after.heap_min,
            "maximum_before": before.heap_max,
            "maximum_after": after.heap_max,
            "stack_minimum": after.stack_min,
            "stack_maximum": after.stack_max,
        },
        "files": len(checked.payloads),
        "package_sha256": sha256(package),
        "package_size": len(package),
        "payloads_unchanged_except_executable": True,
    }
    return package, report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true")
    parser.add_argument(
        "--verify",
        action="store_true",
        help="re-parse the built SIS and require a byte-for-byte re-serialisation",
    )
    args = parser.parse_args()

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    package, report = build(
        PREVIOUS.read_bytes(), EXECUTABLE.read_bytes(), verify_previous=True
    )
    name = "quantum-level41-walk-v1.0.61-weapon1555-unsigned.sis"
    output = OUTPUT_DIR / name
    if output.exists() and not args.force:
        raise SystemExit(f"refusing to overwrite {output} without --force")
    output.write_bytes(package)
    (OUTPUT_DIR / "v1.0.61-build-report.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8"
    )
    print(f"\nwrote {output} ({len(package)} bytes)")
    print(f"sha256 {report['package_sha256']}")
    print(f"files  {report['files']}")
    print(
        "heap   0x{minimum:x}..0x{maximum_after:x}".format(**report["heap"])
    )

    if args.verify:
        parsed = writer.inspect_package(output.read_bytes())
        rebuilt = writer.build_files(
            parsed.executable,
            [
                writer.ApplicationFile(target, payload)
                for target, payload in zip(parsed.targets[1:], parsed.payloads[1:])
                if not (
                    target.startswith(writer.DATA_TARGET_PREFIX)
                    or target.startswith(writer.APP_PRIVATE_TARGET_PREFIX)
                )
            ],
            [
                writer.ApplicationFile(target, payload)
                for target, payload in zip(parsed.targets[1:], parsed.payloads[1:])
                if target.startswith(writer.DATA_TARGET_PREFIX)
                or target.startswith(writer.APP_PRIVATE_TARGET_PREFIX)
            ],
            writer.PackageOptions(
                uid=parsed.options.uid,
                name=parsed.options.name,
                vendor=parsed.options.vendor,
                executable_name=parsed.options.executable_name,
                version=parsed.options.version,
            ),
        )
        if rebuilt != output.read_bytes():
            print("VERIFY FAILED: re-serialisation differs", file=sys.stderr)
            return 1
        print("verify: byte-for-byte re-serialisation matches")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
