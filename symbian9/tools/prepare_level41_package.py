#!/usr/bin/env python3
"""Build and verify an unsigned native SISX with Level 41 smoke-test assets."""

from __future__ import annotations

import argparse
import hashlib
import json
import tomllib
from pathlib import Path

from symbian.native import require_native
from symbian.packaging.registration import compile_registration
from symbian.project.sdk import project_sdk


ASSET_TARGETS = {
    "L3D_new.3d": r"!:\private\e000a012\level41\L3D_new.3d",
    "lightmap.vla": r"!:\private\e000a012\level41\lightmap.vla",
    "skybox8.3d": r"!:\private\e000a012\level41\skybox8.3d",
    "forest.qtx": r"!:\private\e000a012\level41\forest.qtx",
    "map.qtx": r"!:\private\e000a012\level41\map.qtx",
    "river.qtx": r"!:\private\e000a012\level41\river.qtx",
    "sky.qtx": r"!:\private\e000a012\level41\sky.qtx",
}


def normalize_target(value: str) -> str:
    """Normalizes duplicate Windows separators for SIS metadata comparison."""
    return value.replace("/", "\\").replace("\\\\", "\\").casefold()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    project = args.project.resolve()
    artifact = args.artifact.resolve()
    output = args.output.resolve()
    manifest = tomllib.loads((project / "symbian.toml").read_text(encoding="utf-8"))
    package = manifest["package"]
    application = manifest["application"]
    executable_name = package["executable_name"]
    data = artifact.read_bytes()

    native = require_native()
    uid3 = native.inspect_e32(data).uid3
    expected_uid3 = manifest["project"]["uid3"]
    if uid3 != expected_uid3:
        raise SystemExit(
            f"E32 UID3 0x{uid3:08x} does not match manifest 0x{expected_uid3:08x}"
        )

    output.mkdir(parents=True, exist_ok=True)
    (output / executable_name).write_bytes(data)

    registration_files, icon_hashes = compile_registration(
        project,
        application,
        executable_name,
        uid3,
        sdk=project_sdk(project),
    )
    sis_files: list[tuple[str, bytes]] = []
    for target, content in registration_files:
        resource_name = target.replace("\\", "/").rsplit("/", 1)[-1]
        (output / resource_name).write_bytes(content)
        sis_files.append((target, content))

    for name, target in ASSET_TARGETS.items():
        # Compact QTX1 files are generated into the package directory by the
        # preceding host texture step; the remaining files are source assets.
        source = (
            output / name
            if name.endswith(".qtx")
            else project / "assets" / "level41" / name
        )
        if not source.is_file():
            raise SystemExit(f"missing staged Level 41 asset: {source}")
        destination = output / name
        if source != destination:
            destination.write_bytes(source.read_bytes())
        content = destination.read_bytes()
        sis_files.append((target, content))

    options = {
        "uid": package["uid"],
        "name": package["name"],
        "vendor": package["vendor"],
        "executable_name": executable_name,
        "version": package["version"],
    }
    unsigned = native.build_application_sis(data, sis_files, **options)
    if unsigned != native.build_application_sis(data, sis_files, **options):
        raise SystemExit("independent Level 41 SISX builds differ")

    info = native.inspect_sis(unsigned)
    if info.signed_package:
        raise SystemExit("native package writer unexpectedly signed the SISX")
    if not info.application_registered:
        raise SystemExit("Level 41 SISX has no application registration")
    if info.options.uid != package["uid"] or info.executable_uid != uid3:
        raise SystemExit("native SISX metadata does not match Level 41 UIDs")
    expected_targets = {
        normalize_target(target) for target, _ in sis_files
    }
    expected_targets.add(
        normalize_target(f"!:\\sys\\bin\\{executable_name}")
    )
    actual_targets = {normalize_target(file.target) for file in info.files}
    if not expected_targets.issubset(actual_targets):
        missing = sorted(expected_targets - actual_targets)
        raise SystemExit(f"native SISX is missing expected targets: {missing}")

    sisx_name = f"{Path(executable_name).stem}-unsigned.sisx"
    sisx_path = output / sisx_name
    sisx_path.write_bytes(unsigned)
    report = {
        "package_uid": f"0x{package['uid']:08X}",
        "executable_uid3": f"0x{uid3:08X}",
        "executable": executable_name,
        "executable_sha256": hashlib.sha256(data).hexdigest(),
        "unsigned_sisx": sisx_name,
        "unsigned_sisx_sha256": hashlib.sha256(unsigned).hexdigest(),
        "registration_icon_source_sha256": icon_hashes,
        "sis_metadata": {
            "name": info.options.name,
            "vendor": info.options.vendor,
            "version": info.options.version,
            "application_registered": info.application_registered,
            "signed_package": info.signed_package,
            "executable_target": info.target,
            "file_count": len(info.files),
            "files": [
                {
                    "target": file.target,
                    "size": file.size,
                    "sha1": file.sha1,
                    "capabilities": file.capabilities,
                }
                for file in info.files
            ],
        },
        "assets": [
            {"source": name, "target": target}
            for name, target in ASSET_TARGETS.items()
        ],
    }
    (output / "level41-package-inputs.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8"
    )
    print(
        f"Built {sisx_name} with {len(info.files)} files; "
        f"registered={info.application_registered}, signed={info.signed_package}"
    )
    print(f"Wrote {sisx_path} ({len(unsigned)} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
