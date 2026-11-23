#!/usr/bin/env python3
"""Package v1.0.62: v1.0.61 plus three more weapon bodies and the # switch.

What changed relative to v1.0.61:

* Three additional weapon sprites ship as `weapon1.qtx`, `weapon2.qtx` and
  `weapon3.qtx`. They are QTX2 with format RGB1555 (format id 1), the same
  layout `weapon.qtx` already uses, produced by
  `tools/convert_qtx1_to_qtx2.py` from the legacy QTX1 arsenal art.
* `#` cycles through the four bodies, wrapping from the last to the first.
  See `level41_weapon_selection.h`.
* The executable grows by the selection code; every other base payload is
  byte-identical to v1.0.61.

The embedded E32 keeps the 2,097,152 byte maximum heap. Only one weapon body
is resident at a time, so the extra art costs no heap: switching frees the
current sprite buffers before loading the next.

`--verify` re-parses the built SIS and requires a byte-for-byte
re-serialisation, the same proof the writer applies to v1.0.59 and later.
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
PREVIOUS = (
    ROOT
    / "symbian9/level41_walk_test/output/v1.0.61-sdk030-e65-weapon1555"
    / "quantum-level41-walk-v1.0.61-weapon1555-unsigned.sis"
)
OUTPUT_DIR = PROJECT / "output/v1.0.62-sdk030-e65-weapon-switch"
VERSION = (1, 0, 62)
PREVIOUS_SHA256 = "f642c23da8c6667bd413bbdbb537b44344058560a2d2c1fbeedf0796216f5c2c"
TARGET_HEAP_MAX = 0x200000
EXECUTABLE = (
    ROOT
    / "symbian9/build/e65-level41-walk-asm/level41_device_test/e32"
    / "quantum_level41_walk_test.exe"
)
WEAPON_DIR = ROOT / "symbian9/level41_device_test/assets/level41"

# Added by this release. Same device directory as the base weapon art.
NEW_WEAPONS = ("weapon1.qtx", "weapon2.qtx", "weapon3.qtx")
QTX2_FORMAT_1555 = 1


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def check_qtx2_1555(path: Path) -> dict:
    """Refuse to ship a weapon body that is not exactly what the device wants.

    The renderer reads a QTX2 header itself, so a wrong magic or a wrong format
    id fails silently at run time: the sprite simply never appears. Checking it
    at packaging time is the only place the mistake is cheap.
    """
    data = path.read_bytes()
    if len(data) < 16 or data[:4] != b"QTX2":
        raise SystemExit(f"{path.name} is not a QTX2 texture")
    format_id = data[4]
    flags = data[5]
    width = data[6] | (data[7] << 8)
    height = data[8] | (data[9] << 8)
    if format_id != QTX2_FORMAT_1555:
        raise SystemExit(
            f"{path.name} declares format {format_id}, expected RGB1555 (1)"
        )
    if not flags & 0x02:
        raise SystemExit(f"{path.name} stores no 8888 block for the resizer")
    if width <= 0 or height <= 0:
        raise SystemExit(f"{path.name} has invalid dimensions {width}x{height}")
    return {
        "file": path.name,
        "format": "RGB1555",
        "width": width,
        "height": height,
        "flags": flags,
        "size": len(data),
        "sha256": sha256(data),
    }


def build(previous: bytes, executable: bytes, new_weapons: list[Path], *,
          verify_previous: bool) -> tuple[bytes, dict]:
    if verify_previous and sha256(previous) != PREVIOUS_SHA256:
        raise SystemExit(
            "The v1.0.61 baseline does not match the shipped package hash: "
            f"{sha256(previous)}"
        )
    info = writer.inspect_package(previous)

    patched = writer.patch_e32_heap(executable, TARGET_HEAP_MAX)
    before = writer.inspect_e32(executable)
    after = writer.inspect_e32(patched)
    if before.heap_max not in (0x100000, TARGET_HEAP_MAX):
        raise SystemExit(f"unexpected built heap 0x{before.heap_max:x}")
    if after.heap_max != TARGET_HEAP_MAX:
        raise SystemExit(f"heap patch did not take: 0x{after.heap_max:x}")

    weapon_records = [check_qtx2_1555(path) for path in new_weapons]

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

    existing = {target for target in info.targets[1:]}
    added: list[writer.ApplicationFile] = []
    for path in new_weapons:
        target = f"{writer.APP_PRIVATE_TARGET_PREFIX}level41\\{path.name}"
        if target in existing:
            raise SystemExit(f"target already present in the base: {target}")
        added.append(writer.ApplicationFile(target, path.read_bytes()))

    # The writer sorts the additional section by the path *after* the target
    # prefix, not by the whole target string, so `level41\...` and `q41_*.wav`
    # interleave. Reproduce that exactly instead of sorting the raw targets.
    def relative_key(entry: writer.ApplicationFile) -> str:
        for prefix in (writer.APP_PRIVATE_TARGET_PREFIX,
                       writer.DATA_TARGET_PREFIX):
            if entry.target.startswith(prefix):
                return entry.target[len(prefix):].casefold()
        return entry.target.casefold()

    data_files.extend(added)
    data_files.sort(key=relative_key)

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
    if len(checked.payloads) != len(info.payloads) + len(new_weapons):
        raise SystemExit(
            f"expected {len(info.payloads) + len(new_weapons)} files, "
            f"packed {len(checked.payloads)}"
        )
    # Every base payload except the executable must survive untouched.
    base_by_target = dict(zip(info.targets[1:], info.payloads[1:]))
    new_by_target = dict(zip(checked.targets[1:], checked.payloads[1:]))
    for target, payload in base_by_target.items():
        if target not in new_by_target:
            raise SystemExit(f"base payload vanished: {target}")
        if new_by_target[target] != payload:
            raise SystemExit(f"base payload changed: {target}")

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
        "weapon_switch": {
            "key": "#",
            "count": 1 + len(new_weapons),
            "wrap": True,
            "base_body": "weapon.qtx",
            "added_bodies": [path.name for path in new_weapons],
        },
        "new_weapons": weapon_records,
        "files": len(checked.payloads),
        "package_sha256": sha256(package),
        "package_size": len(package),
        "base_payloads_unchanged": True,
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
    weapons = [WEAPON_DIR / name for name in NEW_WEAPONS]
    missing = [str(path) for path in weapons if not path.exists()]
    if missing:
        raise SystemExit("missing weapon art: " + ", ".join(missing))

    package, report = build(
        PREVIOUS.read_bytes(), EXECUTABLE.read_bytes(), weapons,
        verify_previous=True,
    )
    name = "quantum-level41-walk-v1.0.62-weapon-switch-unsigned.sis"
    output = OUTPUT_DIR / name
    if output.exists() and not args.force:
        raise SystemExit(f"refusing to overwrite {output} without --force")
    output.write_bytes(package)
    (OUTPUT_DIR / "v1.0.62-build-report.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8"
    )
    print(f"\nwrote {output} ({len(package)} bytes)")
    print(f"sha256 {report['package_sha256']}")
    print(f"files  {report['files']}")
    print("heap   0x{minimum:x}..0x{maximum_after:x}".format(**report["heap"]))
    print(f"switch # over {report['weapon_switch']['count']} bodies, wrapping")

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
