#!/usr/bin/env python3
"""Extract and convert the Level 1 map/sky assets from Drive's sym9.zip.

The archive is a separate, selectable asset profile. It is staged without
modifying the existing Level 41 assets. The interactive Level 1 target consumes
the four configured river frames at 6 FPS; river.qtx remains for the separate
fixed-scene target.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import subprocess
from pathlib import Path
from zipfile import BadZipFile, ZipFile

EXPECTED_ARCHIVE_SHA256 = "eb81090003338a3333aa2d5b1c25c586cdabff0c446ed9664da132ca82e7b60b"

# Runtime filenames match the existing Level 41 loader. The source filenames
# are the exact paths named by sym9.zip's level1.txt configuration.
SOURCE_ASSETS = {
    "L3D_new.3d": "maps/kordos/kordos.3d",
    "lightmap.vla": "lightmap/kordos.vla",
    "skybox8.3d": "skies/sky.3d",
    "forestOrigy.png": "maps/kordos/forestOrigy.png",
    "map-selo1.png": "maps/kordos/map-selo1.png",
    "skuy.png": "skies/skuy.png",
    "skun.png": "skies/skun.png",
    **{f"river{i}.{ext}": f"maps/kordos/river{i}.{ext}"
       for i in range(4) for ext in ("bmp", "png")},
}

TEXTURES = {
    "forest.qtx": ("forestOrigy.png", (128, 64)),
    "map.qtx": ("map-selo1.png", (256, 256)),
    # Level 1's WTEX entry animates river0..river3 at 6 FPS. Keep river.qtx
    # as the legacy single-frame asset for the separate fixed-scene target.
    "river.qtx": ("river0.bmp", (32, 32)),
    "river0.qtx": ("river0.bmp", (32, 32)),
    "river1.qtx": ("river1.bmp", (32, 32)),
    "river2.qtx": ("river2.bmp", (32, 32)),
    "river3.qtx": ("river3.bmp", (32, 32)),
    # level1.txt explicitly selects the daytime sky texture.
    "sky.qtx": ("skuy.png", (256, 256)),
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def run(*args: str) -> bytes:
    result = subprocess.run(args, check=True, stdout=subprocess.PIPE)
    return result.stdout


def convert_texture(source: Path, expected_dimensions: tuple[int, int]) -> bytes:
    dimensions = run("identify", "-format", "%w %h", str(source)).decode("ascii")
    width, height = (int(value) for value in dimensions.split())
    if (width, height) != expected_dimensions:
        raise SystemExit(
            f"Unexpected dimensions for {source}: {width}x{height}; "
            f"expected {expected_dimensions[0]}x{expected_dimensions[1]}"
        )
    if not (0 < width <= 256 and 0 < height <= 256):
        raise SystemExit(f"Unsupported texture size {width}x{height}: {source}")
    rgba = run("convert", str(source), "-alpha", "on", "-depth", "8", "rgba:-")
    if len(rgba) != width * height * 4:
        raise SystemExit(f"Unexpected RGBA payload length for {source}: {len(rgba)}")
    return b"QTX1" + struct.pack("<HH", width, height) + rgba


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--force", action="store_true",
        help="replace files in the specified output profile after source checks",
    )
    args = parser.parse_args()
    archive_path = args.archive.resolve()
    output = args.output.resolve()
    if not archive_path.is_file():
        raise SystemExit(f"sym9 archive not found: {archive_path}")
    archive_bytes = archive_path.read_bytes()
    archive_hash = sha256(archive_bytes)
    if archive_hash != EXPECTED_ARCHIVE_SHA256:
        raise SystemExit(
            "sym9.zip SHA-256 changed; refusing to import an unreviewed archive: "
            f"expected={EXPECTED_ARCHIVE_SHA256} actual={archive_hash}"
        )
    if output.exists() and any(output.iterdir()) and not args.force:
        raise SystemExit(f"Refusing to overwrite non-empty asset profile: {output}")

    output.mkdir(parents=True, exist_ok=True)
    copied: dict[str, dict[str, object]] = {}
    try:
        with ZipFile(archive_path) as archive:
            members = set(archive.namelist())
            missing = sorted(set(SOURCE_ASSETS.values()) - members)
            if missing:
                raise SystemExit("sym9.zip is missing required entries: " + ", ".join(missing))
            for destination_name, member in SOURCE_ASSETS.items():
                payload = archive.read(member)
                destination = output / destination_name
                destination.write_bytes(payload)
                copied[destination_name] = {
                    "archive_member": member,
                    "size_bytes": len(payload),
                    "sha256": sha256(payload),
                }
    except BadZipFile as exc:
        raise SystemExit(f"Invalid sym9 ZIP archive: {archive_path}") from exc

    qtx_dir = output / "qtx"
    qtx_dir.mkdir(parents=True, exist_ok=True)
    generated: dict[str, dict[str, object]] = {}
    for output_name, (source_name, dimensions) in TEXTURES.items():
        payload = convert_texture(output / source_name, dimensions)
        destination = qtx_dir / output_name
        destination.write_bytes(payload)
        generated[output_name] = {
            "source": source_name,
            "width": dimensions[0],
            "height": dimensions[1],
            "size_bytes": len(payload),
            "sha256": sha256(payload),
        }

    manifest = {
        "source_archive": {
            "name": archive_path.name,
            "sha256": archive_hash,
            "selection": "Kordos Level 1 map textures, lightmap, model, and the configured daytime sky; unrelated maps, weapons, and game code were not imported",
        },
        "runtime_assets": copied,
        "qtx1_textures": generated,
        "compatibility": {
            "forest_texture": "sym9's 128x64 forestOrigy.png is supported in addition to the existing 256x128 profile",
            "sky_texture": "skuy.png, selected by sym9.zip/level1.txt",
            "river": "the interactive Level 1 target loads river0.qtx..river3.qtx at the configured 6 FPS; river.qtx remains for the separate fixed-scene target",
            "runtime_verification": "none; this manifest records source conversion and packaging inputs only",
        },
    }
    manifest_path = output / "sym9-assets-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Staged {len(copied)} sym9 source assets and {len(generated)} QTX1 textures in {output}")
    print(f"Manifest: {manifest_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
