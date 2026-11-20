#!/usr/bin/env python3
"""Stage only the [MESH] and [SPRITE] objects named by sym9.zip/level1.txt.

The native target reads preconverted QTX1 textures and the Java .3d mesh from
its private resource directory. This tool pins both Drive archives, verifies
the exact two Level 1 records and the three referenced prop files, and keeps
all unrelated prop.zip entries out of the runtime asset profile.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import subprocess
from pathlib import Path
from zipfile import BadZipFile, ZipFile

LEVEL_ARCHIVE_SHA256 = "eb81090003338a3333aa2d5b1c25c586cdabff0c446ed9664da132ca82e7b60b"
PROP_ARCHIVE_SHA256 = "bbfb8d745e7ba42b95288e38ec30b7bfb9e75a188b2b74b057b6f65217aa6ceb"

EXPECTED_RECORDS = {
    "MESH": {
        "POS": "-73825,-3626,-113803",
        "MODEL": "/prop/md.3d",
        "TEX": "/prop/mdy.png",
        "MODEL_SCALE": "2",
        "COLLISION_SCALE_X": "0",
        "COLLISION_SCALE_Y": "0",
        "PHYSICS": "0",
    },
    "SPRITE": {
        "POS": "-71400,-3368,-76270",
        "TEX": "/prop/skl.png",
        "SCALE": "34",
        "FOG": "NONE",
        "MODE": "NORMAL",
    },
}

PROP_MEMBERS = {
    "prop/md.3d": "prop/md.3d",
    "prop/mdy.png": "prop/mdy.png",
    "prop/skl.png": "prop/skl.png",
}
PROP_SHA256 = {
    "prop/md.3d": "8f078f7d719eed10801c3914db73b630662ebd5e1644a1f6ca2baf0bfa14c7fb",
    "prop/mdy.png": "e78362d882a6d78f8665fe1757856326d40ee312e7081f1fd36cc356a33edf70",
    "prop/skl.png": "6d766bae12c0f1ee296b5561417c9782106356645c08c73b2bdb504f6caa6f0a",
}
TEXTURES = {
    "prop/mdy.qtx": ("prop/mdy.png", (64, 64)),
    "prop/skl.qtx": ("prop/skl.png", (43, 64)),
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def run(*args: str) -> bytes:
    return subprocess.run(args, check=True, stdout=subprocess.PIPE).stdout


def parse_level_objects(text: str) -> tuple[dict[str, list[str]], dict[str, dict[str, str]]]:
    lines = text.splitlines()
    blocks: dict[str, list[list[str]]] = {"MESH": [], "SPRITE": []}
    index = 0
    while index < len(lines):
        header = lines[index].strip()
        if len(header) < 3 or header[0] != "[" or header[-1] != "]":
            index += 1
            continue
        kind = header[1:-1].strip()
        start = index
        index += 1
        while index < len(lines):
            candidate = lines[index].strip()
            if len(candidate) >= 3 and candidate[0] == "[" and candidate[-1] == "]":
                break
            index += 1
        if kind not in blocks:
            continue
        raw_lines = lines[start:index]
        while raw_lines and not raw_lines[-1].strip():
            raw_lines.pop()
        blocks[kind].append(raw_lines)

    records: dict[str, dict[str, str]] = {}
    for kind, occurrences in blocks.items():
        if len(occurrences) != 1:
            raise SystemExit(f"Expected exactly one [{kind}] record in level1.txt; found {len(occurrences)}")
        values: dict[str, str] = {}
        for line in occurrences[0][1:]:
            stripped = line.strip()
            if not stripped or stripped.startswith(("#", "//")):
                continue
            if "=" not in stripped:
                raise SystemExit(f"Malformed [{kind}] property line: {line!r}")
            key, value = stripped.split("=", 1)
            values[key.strip()] = value.strip()
        records[kind] = values
        if values != EXPECTED_RECORDS[kind]:
            raise SystemExit(
                f"[{kind}] in level1.txt differs from the reviewed Level 1 entry: "
                f"expected={EXPECTED_RECORDS[kind]!r} actual={values!r}"
            )
    return blocks, records


def convert_texture(source: Path, expected_dimensions: tuple[int, int]) -> bytes:
    dimensions = run("identify", "-format", "%w %h", str(source)).decode("ascii")
    width, height = (int(value) for value in dimensions.split())
    if (width, height) != expected_dimensions:
        raise SystemExit(
            f"Unexpected dimensions for {source}: {width}x{height}; "
            f"expected {expected_dimensions[0]}x{expected_dimensions[1]}"
        )
    rgba = run("convert", str(source), "-alpha", "on", "-depth", "8", "rgba:-")
    if len(rgba) != width * height * 4:
        raise SystemExit(f"Unexpected RGBA payload length for {source}: {len(rgba)}")
    return b"QTX1" + struct.pack("<HH", width, height) + rgba


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--level-archive", type=Path, required=True)
    parser.add_argument("--props-archive", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--force", action="store_true",
        help="replace only this tool's Level 1 object files after source checks",
    )
    args = parser.parse_args()

    level_archive_path = args.level_archive.resolve()
    props_archive_path = args.props_archive.resolve()
    output = args.output.resolve()
    for path, expected, label in (
        (level_archive_path, LEVEL_ARCHIVE_SHA256, "sym9.zip"),
        (props_archive_path, PROP_ARCHIVE_SHA256, "prop.zip"),
    ):
        if not path.is_file():
            raise SystemExit(f"{label} not found: {path}")
        actual = sha256(path.read_bytes())
        if actual != expected:
            raise SystemExit(
                f"{label} SHA-256 changed; refusing to import an unreviewed archive: "
                f"expected={expected} actual={actual}"
            )

    owned_paths = [output / "level1-objects.txt", output / "level1-objects-manifest.json"]
    owned_paths.extend(output / relative for relative in PROP_MEMBERS)
    owned_paths.extend(output / relative for relative in TEXTURES)
    if not args.force:
        existing = [str(path) for path in owned_paths if path.exists()]
        if existing:
            raise SystemExit(
                "Refusing to overwrite existing Level 1 object assets without --force: "
                + ", ".join(existing)
            )

    try:
        with ZipFile(level_archive_path) as archive:
            members = set(archive.namelist())
            if "level1.txt" not in members:
                raise SystemExit("sym9.zip is missing level1.txt")
            level1_bytes = archive.read("level1.txt")
            level1_text = level1_bytes.decode("cp1251")
            raw_blocks, records = parse_level_objects(level1_text)
            manifest_bytes = (
                ("\n\n".join("\n".join(raw_blocks[kind][0]) for kind in ("MESH", "SPRITE")) + "\n")
                .encode("ascii")
            )
        with ZipFile(props_archive_path) as archive:
            members = set(archive.namelist())
            missing = sorted(set(PROP_MEMBERS.values()) - members)
            if missing:
                raise SystemExit("prop.zip is missing referenced Level 1 assets: " + ", ".join(missing))
            prop_payloads = {destination: archive.read(member)
                             for destination, member in PROP_MEMBERS.items()}
    except (BadZipFile, UnicodeDecodeError) as exc:
        raise SystemExit(f"Invalid source archive or level1.txt encoding: {exc}") from exc

    for relative, payload in prop_payloads.items():
        actual = sha256(payload)
        if actual != PROP_SHA256[relative]:
            raise SystemExit(
                f"Referenced prop asset changed: {relative}; "
                f"expected={PROP_SHA256[relative]} actual={actual}"
            )

    output.mkdir(parents=True, exist_ok=True)
    (output / "level1-objects.txt").write_bytes(manifest_bytes)
    source_records: dict[str, dict[str, object]] = {}
    for relative, payload in prop_payloads.items():
        destination = output / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(payload)
        source_records[relative] = {
            "archive_member": PROP_MEMBERS[relative],
            "size_bytes": len(payload),
            "sha256": sha256(payload),
        }

    generated: dict[str, dict[str, object]] = {}
    for relative, (source_relative, dimensions) in TEXTURES.items():
        payload = convert_texture(output / source_relative, dimensions)
        destination = output / relative
        destination.write_bytes(payload)
        generated[relative] = {
            "source": source_relative,
            "width": dimensions[0],
            "height": dimensions[1],
            "size_bytes": len(payload),
            "sha256": sha256(payload),
        }

    manifest = {
        "source_archives": {
            "level_archive": {
                "name": level_archive_path.name,
                "drive_id": "1elEgbqUS3KhYfKuusXIwS1LaVF_o6bki",
                "size_bytes": level_archive_path.stat().st_size,
                "sha256": LEVEL_ARCHIVE_SHA256,
                "level1_txt_sha256": sha256(level1_bytes),
            },
            "props_archive": {
                "name": props_archive_path.name,
                "drive_id": "1zoSFEQQS7foZTrsDfb3pfpLv_RGCkeAp",
                "size_bytes": props_archive_path.stat().st_size,
                "sha256": PROP_ARCHIVE_SHA256,
                "selection": "only prop/md.3d, prop/mdy.png, and prop/skl.png, because these are the only prop.zip files referenced by level1.txt's single [MESH] and single [SPRITE] entries",
            },
        },
        "level_object_records": records,
        "runtime_manifest": {
            "file": "level1-objects.txt",
            "size_bytes": len(manifest_bytes),
            "sha256": sha256(manifest_bytes),
            "record_counts": {"MESH": 1, "SPRITE": 1},
        },
        "source_prop_assets": source_records,
        "qtx1_textures": generated,
        "scope": "No unreferenced weapon PNGs or other prop.zip entries are imported.",
    }
    manifest_path = output / "level1-objects-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Staged exactly one Level 1 mesh and one sprite in {output}")
    print(f"Manifest: {manifest_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
