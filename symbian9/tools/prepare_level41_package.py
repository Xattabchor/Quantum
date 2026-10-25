#!/usr/bin/env python3
"""Stage registration resources and a GnuPoc-compatible Level 41 .pkg file."""

from __future__ import annotations

import argparse
import hashlib
import json
import tomllib
from pathlib import Path

from symbian.native import require_native
from symbian.packaging.registration import compile_registration
from symbian.project.sdk import project_sdk


ASSET_NAMES = (
    "L3D_new.3d",
    "lightmap.vla",
    "skybox8.3d",
    "forest.qtx",
    "map.qtx",
    "river.qtx",
    "sky.qtx",
)


def pkg_escape_target(value: str) -> str:
    # MakeSIS .pkg strings use literal Windows separators, not C escapes.
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--asset-directory",
        type=Path,
        help="source directory for the shared Level 41 mesh and lightmap assets",
    )
    args = parser.parse_args()

    project = args.project.resolve()
    artifact = args.artifact.resolve()
    output = args.output.resolve()
    asset_directory = (
        args.asset_directory.resolve()
        if args.asset_directory is not None
        else project / "assets" / "level41"
    )
    manifest = tomllib.loads((project / "symbian.toml").read_text(encoding="utf-8"))
    project_info = manifest["project"]
    package = manifest["package"]
    application = manifest["application"]
    executable_name = package["executable_name"]
    data = artifact.read_bytes()
    uid3 = require_native().inspect_e32(data).uid3
    expected_uid3 = project_info["uid3"]
    if uid3 != expected_uid3:
        raise SystemExit(
            f"E32 UID3 0x{uid3:08x} does not match manifest 0x{expected_uid3:08x}"
        )
    private_asset_directory = f"!:\\private\\{expected_uid3:08x}\\level41\\"
    asset_targets = {
        name: private_asset_directory + name for name in ASSET_NAMES
    }

    output.mkdir(parents=True, exist_ok=True)
    executable = output / executable_name
    executable.write_bytes(data)

    resources, icon_hashes = compile_registration(
        project,
        application,
        executable_name,
        uid3,
        sdk=project_sdk(project),
    )
    resource_lines: list[str] = []
    for target, content in resources:
        resource_name = target.replace("\\", "/").rsplit("/", 1)[-1]
        (output / resource_name).write_bytes(content)
        resource_lines.append(
            f'"{resource_name}"-"{pkg_escape_target(target)}"'
        )

    for name, target in asset_targets.items():
        # Compact QTX1 files are generated into the package directory by the
        # preceding host texture step; the remaining files come from the shared
        # Level 41 asset directory (also used by the GDI-only package).
        source = output / name if name.endswith(".qtx") else asset_directory / name
        if not source.is_file():
            raise SystemExit(f"missing staged Level 41 asset: {source}")
        destination = output / name
        if source != destination:
            destination.write_bytes(source.read_bytes())
        resource_lines.append(
            f'"{name}"-"{pkg_escape_target(target)}"'
        )

    uid = package["uid"]
    version = package["version"]
    pkg = [
        "&EN",
        "",
        f'%{{"{package["vendor"]}"}}',
        f':"{package["vendor"]}"',
        "",
        f'#{{"{package["name"]}"}},(0x{uid:08X}),'
        f"{version[0]},{version[1]},{version[2]}",
        "",
        f'"{executable_name}"-"!:\\sys\\bin\\{executable_name}"',
        *resource_lines,
        "",
    ]
    project_name = project_info["name"]
    pkg_path = output / f"{project_name}.pkg"
    pkg_path.write_text("\n".join(pkg), encoding="ascii")

    report = {
        "package_uid": f"0x{uid:08X}",
        "executable_uid3": f"0x{uid3:08X}",
        "executable": executable_name,
        "executable_sha256": hashlib.sha256(data).hexdigest(),
        "registration_icon_source_sha256": icon_hashes,
        "files": [
            {"source": name, "target": target}
            for name, target in asset_targets.items()
        ],
    }
    (output / f"{project_name}-package-inputs.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Staged {executable_name}, {len(resources)} registration files and "
          f"{len(asset_targets)} Level 41 assets")
    print(f"Wrote {pkg_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
