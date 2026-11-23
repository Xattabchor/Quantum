#!/usr/bin/env python3
"""Convert QTX1 (raw RGBA8888) assets to QTX2 (pre-quantised 16-bit).

QTX1 carries four bytes per texel that the renderer immediately shrinks to
two, and it leaves the choice of 565/1555/4444 to start-up. QTX2 fixes both:
the format is chosen here, per asset, from the image's own alpha content, and
the device only copies the pixels.

Format rules, in order:
  * any texel with 0 < alpha < 255   -> ARGB4444  (real translucency)
  * any fully transparent texel      -> RGB1555   (1-bit zero key)
  * otherwise                        -> RGB565    (fully opaque)

A `--format` override exists for the cases where the rule is right but not
obvious; see FORMAT_OVERRIDES for the ones this project actually uses.

Weapon sprites store the 8888 block *instead of* the 16-bit one, because the
renderer resizes them bilinearly at runtime and resizing already-quantised
texels would quantise twice. Their `format` field then declares which 16-bit
format the resized result must become, so the choice is still made here.
Everything else stores only the 16-bit block.

This mirrors software_qtx2.cpp field for field; symbian9/host_tests/
qtx2_conversion_test.cpp decodes these files with the C++ reader and asserts
agreement, so the two implementations cannot drift silently.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
from pathlib import Path

MAGIC = b"QTX2"
HEADER_SIZE = 16
FORMAT_565 = 0
FORMAT_1555 = 1
FORMAT_4444 = 2
FORMAT_NAMES = {FORMAT_565: "RGB565", FORMAT_1555: "RGB1555", FORMAT_4444: "ARGB4444"}
FLAG_HAS_16 = 1
FLAG_HAS_RGB8888 = 2

# Assets whose renderer path resizes the image at runtime (Java ImageResize).
# They carry the 8888 block; converting them before the resize would throw
# away the interpolated alpha the resize creates.
RETAINS_RGB8888 = ("weapon.qtx", "weapon-fire.qtx", "weapon-sight-weapon.qtx",
                   "weapon-sight.qtx")

# Explicit choices where the automatic rule would still be correct but the
# reason deserves recording. Every entry is verified against the pixels.
FORMAT_OVERRIDES = {
    # sky.qtx has no transparent and no partial texels at all: pure 565.
    "sky.qtx": FORMAT_565,
    # The weapon body keeps the keyed 1555 look it has always had. Its 148
    # part-transparent texels are bilinear fringes introduced by the runtime
    # resize, not authored translucency; the source is a hard 0/255 cut, so
    # 1555 reproduces it exactly.
    "weapon.qtx": FORMAT_1555,
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def align_to_two(value: int) -> int:
    return (value + 1) & ~1


def read_qtx1(path: Path) -> tuple[int, int, bytes]:
    data = path.read_bytes()
    if len(data) < 8 or data[:4] != b"QTX1":
        raise SystemExit(f"{path} is not a QTX1 texture")
    width, height = struct.unpack("<HH", data[4:8])
    if width <= 0 or height <= 0:
        raise SystemExit(f"{path} has invalid dimensions {width}x{height}")
    rgba = data[8:]
    if len(rgba) != width * height * 4:
        raise SystemExit(
            f"{path} payload is {len(rgba)} bytes, expected {width * height * 4}"
        )
    return width, height, rgba


def argb_dwords(rgba: bytes) -> bytes:
    """Store the 8888 block as 0xAARRGGBB dwords, not as RGBA bytes.

    QTX1 holds bytes in R,G,B,A order, so reinterpreting them as a dword
    yields A<<24 | B<<16 | G<<8 | R: red and blue come out swapped. The QTX1
    loader compensated by rebuilding every pixel; QTX2 stores the dword that
    the renderer actually wants, so start-up copies instead of converting.
    """
    out = bytearray(len(rgba))
    for index in range(0, len(rgba), 4):
        r, g, b, a = rgba[index:index + 4]
        struct.pack_into("<I", out, index, (a << 24) | (r << 16) | (g << 8) | b)
    return bytes(out)


def classify(rgba: bytes) -> tuple[int, int, int, int]:
    """Return (zero, partial, opaque, suggested format).

    Partial alpha suggests 1555, never 4444. The renderer's fast path rejects
    ARGB4444 outright (validTexture16 returns false for is4444): 4444 is a
    sprite-alpha format that only the drawmode-4 kernel understands, so a 4444
    world texture silently falls back to the 32-bit reference kernel and the
    whole point of pre-quantising is lost. 1555 keeps the 1-bit zero key that
    the engine's own rgb1555From8888() produces, and drawmode 4 still requests
    4444 explicitly through FORMAT_OVERRIDES where a gradient really needs it.
    """
    zero = partial = opaque = 0
    for alpha in rgba[3::4]:
        if alpha == 0:
            zero += 1
        elif alpha < 255:
            partial += 1
        else:
            opaque += 1
    if partial or zero:
        suggested = FORMAT_1555
    else:
        suggested = FORMAT_565
    return zero, partial, opaque, suggested


def to_565(r: int, g: int, b: int) -> int:
    return ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)


def to_1555(r: int, g: int, b: int, a: int) -> int:
    """Mirrors rgb1555From8888(): alpha below 128 collapses to the zero key."""
    if a < 128:
        return 0
    return 0x8000 | ((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3)


def to_4444(r: int, g: int, b: int, a: int) -> int:
    """Mirrors argb4444From8888(): high nibble of each channel."""
    return ((a & 0xF0) << 8) | ((r & 0xF0) << 4) | (g & 0xF0) | (b >> 4)


def convert_pixels(rgba: bytes, fmt: int) -> bytes:
    out = bytearray(len(rgba) // 2)
    if fmt == FORMAT_565:
        for index in range(0, len(rgba), 4):
            value = to_565(rgba[index], rgba[index + 1], rgba[index + 2])
            out[index // 2: index // 2 + 2] = struct.pack("<H", value)
    elif fmt == FORMAT_1555:
        for index in range(0, len(rgba), 4):
            value = to_1555(rgba[index], rgba[index + 1],
                            rgba[index + 2], rgba[index + 3])
            out[index // 2: index // 2 + 2] = struct.pack("<H", value)
    elif fmt == FORMAT_4444:
        for index in range(0, len(rgba), 4):
            value = to_4444(rgba[index], rgba[index + 1],
                            rgba[index + 2], rgba[index + 3])
            out[index // 2: index // 2 + 2] = struct.pack("<H", value)
    else:
        raise SystemExit(f"unknown format {fmt}")
    return bytes(out)


def convert(source: Path, fmt: int | None, keep_rgb: bool) -> tuple[bytes, dict]:
    width, height, rgba = read_qtx1(source)
    zero, partial, opaque, suggested = classify(rgba)
    chosen = suggested if fmt is None else fmt

    # Guard the two ways a format choice can be dishonest.
    #
    # When the 8888 block is stored, the source pixels are kept verbatim:
    # `format` is then only a declaration of what the runtime resize must
    # convert to, so a coarser declaration costs no build-time data. When the
    # 16-bit block is stored, the conversion is itself the storage, and a
    # format that cannot represent the image would destroy pixels here.
    if not keep_rgb:
        # RGB1555 is allowed to erase partial alpha: the renderer's own
        # rgb1555From8888() does exactly this (alpha < 128 collapses to the
        # zero key) for every 16-bit world texture, so storing 1555 moves that
        # step from start-up into the asset and changes nothing visible except
        # where it is already invisible. The count is recorded per file so the
        # loss stays measured instead of implied.
        if chosen == FORMAT_565 and (zero or partial):
            raise SystemExit(
                f"{source.name}: refused RGB565, {zero + partial} texels are "
                "not opaque"
            )

    # 16 bytes, so both pixel blocks start 4-byte aligned (ARMv5 faults on a
    # misaligned dword load, it does not merely return garbage).
    header = MAGIC + bytes([chosen, FLAG_HAS_RGB8888 if keep_rgb else FLAG_HAS_16]) \
        + struct.pack("<HH", width, height) + b"\x00" * 6
    if keep_rgb:
        body = header + argb_dwords(rgba)
    else:
        body = header + convert_pixels(rgba, chosen)

    record = {
        "source": source.name,
        "width": width,
        "height": height,
        "format": FORMAT_NAMES[chosen],
        "format_id": chosen,
        "auto_suggested": FORMAT_NAMES[suggested],
        "overridden": fmt is not None and fmt != suggested,
        "stored_block": "rgb8888" if keep_rgb else "16-bit",
        "declared_lossy": bool(
            keep_rgb and ((chosen == FORMAT_1555 and partial)
                          or (chosen == FORMAT_565 and (zero or partial)))
        ),
        "alpha_zero": zero,
        "alpha_partial": partial,
        "texels_erased_by_1555": (
            partial if (not keep_rgb and chosen == FORMAT_1555) else 0
        ),
        "alpha_opaque": opaque,
        "source_size_bytes": len(rgba) + 8,
        "size_bytes": len(body),
        "sha256": sha256(body),
    }
    return body, record


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, required=True,
                        help="directory holding QTX1 files")
    parser.add_argument("--output-dir", type=Path, required=True,
                        help="directory to write QTX2 files into")
    parser.add_argument("--manifest", type=Path,
                        help="optional JSON manifest path")
    parser.add_argument("--format", default=None,
                        help="force one format for every input: 565, 1555 or 4444")
    parser.add_argument("--keep-rgb", action="store_true",
                        help="store the 8888 block for every texture, not just weapons")
    parser.add_argument("--dry-run", action="store_true",
                        help="report the chosen formats without writing files")
    args = parser.parse_args()

    forced = None
    if args.format:
        lookup = {name.lower(): value for value, name in FORMAT_NAMES.items()}
        lookup["551"] = FORMAT_1555
        if args.format.lower() not in lookup:
            raise SystemExit(f"unknown --format {args.format}; use 565, 1555 or 4444")
        forced = lookup[args.format.lower()]

    sources = sorted(args.input_dir.glob("*.qtx"))
    if not sources:
        raise SystemExit(f"no .qtx files in {args.input_dir}")

    if not args.dry_run:
        args.output_dir.mkdir(parents=True, exist_ok=True)

    records = {}
    for source in sources:
        keep_rgb = args.keep_rgb or source.name in RETAINS_RGB8888
        override = forced if forced is not None else FORMAT_OVERRIDES.get(source.name)
        payload, record = convert(source, override, keep_rgb)
        records[source.name] = record
        if args.dry_run:
            print(f"{source.name:26s} {record['width']:3d}x{record['height']:<3d} "
                  f"{record['format']:8s} {record['source_size_bytes']:7d} -> "
                  f"{record['size_bytes']:7d} bytes")
        else:
            (args.output_dir / source.name).write_bytes(payload)
            print(f"{source.name:26s} -> {record['format']}")

    if args.manifest and not args.dry_run:
        total_source = sum(item["source_size_bytes"] for item in records.values())
        total_output = sum(item["size_bytes"] for item in records.values())
        manifest = {
            "format": "QTX2",
            "description": (
                "Pre-quantised 16-bit textures. The format is chosen per asset "
                "from its alpha content, so start-up only copies pixels."
            ),
            "totals": {
                "source_bytes": total_source,
                "output_bytes": total_output,
                "saved_bytes": total_source - total_output,
                "saved_percent": round(
                    100.0 * (total_source - total_output) / total_source, 1
                ) if total_source else 0.0,
            },
            "textures": records,
        }
        args.manifest.write_text(json.dumps(manifest, indent=2) + "\n",
                                 encoding="utf-8")
        print(f"Manifest: {args.manifest}")
        print(f"Total: {total_source} -> {total_output} bytes "
              f"({manifest['totals']['saved_percent']}% smaller)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
