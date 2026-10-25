#!/usr/bin/env python3
"""Prepare compact RGBA texture payloads for the E65 Level 41 smoke SIS.

The target reads QTX1 files directly; ImageMagick performs PNG/JPEG decoding
on the host so the Symbian app does not depend on image-codec plugins.
"""

from __future__ import annotations

import argparse
import struct
import subprocess
from pathlib import Path


TEXTURES = {
    "forest.qtx": "forestOrig.png",
    "map.qtx": "map-selo.png",
    "river.qtx": "river0.png",
    "sky.qtx": "skybox5-2.jpg",
}
MAX_WIDTH = 256
MAX_HEIGHT = 256


def run(*args: str) -> bytes:
    result = subprocess.run(args, check=True, stdout=subprocess.PIPE)
    return result.stdout


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--assets",
        type=Path,
        default=Path(__file__).parents[1] / "level41_device_test" / "assets" / "level41",
    )
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    for output_name, relative_input in TEXTURES.items():
        source = args.assets / relative_input
        dimensions = run("identify", "-format", "%w %h", str(source)).decode("ascii")
        width, height = (int(value) for value in dimensions.split())
        if not (0 < width <= MAX_WIDTH and 0 < height <= MAX_HEIGHT):
            raise SystemExit(f"unsupported texture size {width}x{height}: {source}")
        rgba = run("convert", str(source), "-alpha", "on", "-depth", "8", "rgba:-")
        if len(rgba) != width * height * 4:
            raise SystemExit(f"unexpected RGBA payload length for {source}: {len(rgba)}")
        output = args.output / output_name
        output.write_bytes(b"QTX1" + struct.pack("<HH", width, height) + rgba)
        print(f"{source}: {width}x{height}, {len(rgba)} RGBA bytes -> {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
