#!/usr/bin/env python3
"""Stage a minimal WindowServer launch-probe E32 app for SIS packaging."""

from __future__ import annotations

import argparse
import hashlib
import json
import tomllib
from pathlib import Path

from symbian.native import require_native
from symbian.packaging.registration import compile_registration
from symbian.project.sdk import project_sdk


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
        resource_lines.append(f'"{resource_name}"-"{target}"')

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
    pkg_path = output / "quantum_level41_launch_probe.pkg"
    pkg_path.write_text("\n".join(pkg), encoding="ascii")

    report = {
        "package_uid": f"0x{uid:08X}",
        "executable_uid3": f"0x{uid3:08X}",
        "executable": executable_name,
        "executable_sha256": hashlib.sha256(data).hexdigest(),
        "registration_icon_source_sha256": icon_hashes,
    }
    (output / "level41-launch-probe-inputs.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Staged {executable_name} and {len(resources)} registration files")
    print(f"Wrote {pkg_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
