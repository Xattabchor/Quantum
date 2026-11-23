#!/usr/bin/env python3
"""Generate the per-weapon QWG configs from sym 9 2's `weapons.txt`.

`weapons.txt` is the game's own weapon table and the authority the Java build
reads through `WeaponCreator`. This script translates the four entries into the
binary QWG3 records the Symbian renderer loads, so the port stops borrowing the
FN2000 tuning for the pistol, the Kalashnikov and the Dragunov.

Field mapping, following `WeaponCreator.createWeapon`:

  CAPACITY / DELAY / SHOTTIME / RELOADTIME / DAMAGE   direct
  KW, KH                                              muzzle anchor
  X_POS                                               custom position (also
                                                      sets the flag, exactly as
                                                      `wp.customPos = true`)
  ZOOM=a,b,c                                          hasZoom = (c == 1)
  PATRONBUY                                           patronBuy
  NEWFIREANIM                                         newFireAnimation
  TWOHANDS                                            twoHands
  SHOOT_LIGHT                                         inverted: absent means on

`attackIntensity` defaults to `SoftwareWeaponTuning`'s 1.0 (Q8 256) unless a
weapon sets MELEEANIM, which Java raises to 5.0. None of the four do.

Weapon 0 (`weapon.qtx`, FS2000) is not written here: its tuning already ships
byte-for-byte as `weapon-gameplay.qwg` and this script only adds the missing
three, so the bytes the walk profile was tuned around cannot drift.
"""

from __future__ import annotations

import argparse
import struct
from pathlib import Path

MAGIC = b"QWG3"
HEADER_BYTES = 30
ATTACK_INTENSITY_DEFAULT_Q8 = 256

# Bit order matches software_weapon_controller.cpp.
FLAG_TWO_HANDS = 1 << 0
FLAG_PATRON_BUY = 1 << 1
FLAG_CAN_SHOOT = 1 << 2
FLAG_NEW_FIRE_ANIMATION = 1 << 3
FLAG_MELEE_ANIMATION = 1 << 4
FLAG_LEFT_HAND = 1 << 5
FLAG_HAS_ZOOM = 1 << 6
FLAG_IGNORE_SIGHT = 1 << 7
FLAG_CUSTOM_POSITION = 1 << 8
FLAG_CENTRE_ALIGN = 1 << 9
FLAG_DISABLE_SHOOT_LIGHT = 1 << 10


def parse_weapons_txt(path: Path) -> dict[int, dict[str, str]]:
    """Parse the `[n]` / `KEY=value` table `weapons.txt` uses."""
    text = path.read_text(encoding="utf-8", errors="replace")
    entries: dict[int, dict[str, str]] = {}
    current: dict[str, str] | None = None
    for raw_line in text.splitlines():
        line = raw_line.strip().lstrip("\ufeff")
        if not line:
            continue
        if line.startswith("[") and line.endswith("]"):
            index = int(line[1:-1])
            current = {}
            entries[index] = current
            continue
        if current is None or "=" not in line:
            continue
        key, _, value = line.partition("=")
        current[key.strip().upper()] = value.strip()
    return entries


def int_field(entry: dict[str, str], key: str, default: int) -> int:
    value = entry.get(key)
    if value is None or value == "":
        return default
    try:
        return int(float(value))
    except ValueError:
        return default


def float_field(entry: dict[str, str], key: str, default: float) -> float:
    value = entry.get(key)
    if value is None or value == "":
        return default
    try:
        return float(value)
    except ValueError:
        return default


def build_qwg3(entry: dict[str, str]) -> tuple[bytes, dict]:
    capacity = int_field(entry, "CAPACITY", 0)
    delay = int_field(entry, "DELAY", 0)
    shot_time = int_field(entry, "SHOTTIME", 0)
    reload_time = int_field(entry, "RELOADTIME", 0)
    damage = int_field(entry, "DAMAGE", 0)
    if capacity <= 0:
        raise SystemExit(f"weapon without CAPACITY: {entry}")

    flags = FLAG_CAN_SHOOT
    if int_field(entry, "TWOHANDS", 0) != 0:
        flags |= FLAG_TWO_HANDS
    if int_field(entry, "PATRONBUY", 1) != 0:
        flags |= FLAG_PATRON_BUY
    if int_field(entry, "NEWFIREANIM", 0) != 0:
        flags |= FLAG_NEW_FIRE_ANIMATION
    if entry.get("MELEEANIM") == "1":
        flags |= FLAG_MELEE_ANIMATION
    if int_field(entry, "LEFT_HAND", 0) != 0:
        flags |= FLAG_LEFT_HAND
    if int_field(entry, "IGNORE_SIGHT_IMAGE", 0) != 0:
        flags |= FLAG_IGNORE_SIGHT
    if int_field(entry, "CENTRE_ALIGHT", 0) != 0:
        flags |= FLAG_CENTRE_ALIGN
    # `wp.shootLight` defaults to true and is only cleared when the key is 0.
    if int_field(entry, "SHOOT_LIGHT", 1) == 0:
        flags |= FLAG_DISABLE_SHOOT_LIGHT
    # Java sets customPos whenever X_POS is present, whatever its value.
    has_custom_position = "X_POS" in entry
    if has_custom_position:
        flags |= FLAG_CUSTOM_POSITION

    zoom = entry.get("ZOOM")
    if zoom:
        parts = [part for part in zoom.replace(";", ",").split(",") if part.strip()]
        if len(parts) >= 3 and int_field({"Z": parts[2]}, "Z", 0) == 1:
            flags |= FLAG_HAS_ZOOM

    attack_intensity = (5 * 256 if flags & FLAG_MELEE_ANIMATION
                        else ATTACK_INTENSITY_DEFAULT_Q8)
    muzzle_x = float_field(entry, "KW", 0.0)
    muzzle_y = float_field(entry, "KH", 0.0)
    custom_x = float_field(entry, "X_POS", 1.0) if has_custom_position else 1.0

    payload = bytearray(MAGIC)
    payload += struct.pack("<hhhhHHH", capacity, delay, shot_time,
                           reload_time, damage, flags, attack_intensity)
    payload += struct.pack("<ff", muzzle_x, muzzle_y)
    payload += struct.pack("<f", custom_x)
    if len(payload) != HEADER_BYTES:
        raise SystemExit(f"unexpected QWG3 size {len(payload)}")

    record = {
        "name": entry.get("NAME", "").strip(),
        "capacity": capacity,
        "delay": delay,
        "shot_time": shot_time,
        "reload_time": reload_time,
        "damage": damage,
        "flags": flags,
        "attack_intensity_q8": attack_intensity,
        "muzzle_x": muzzle_x,
        "muzzle_y": muzzle_y,
        "custom_position": bool(flags & FLAG_CUSTOM_POSITION),
        "custom_position_x": custom_x,
        "has_zoom": bool(flags & FLAG_HAS_ZOOM),
    }
    return bytes(payload), record


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--weapons-txt", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()

    entries = parse_weapons_txt(args.weapons_txt)
    if not entries:
        raise SystemExit(f"no weapon entries in {args.weapons_txt}")
    args.output_dir.mkdir(parents=True, exist_ok=True)

    for index in sorted(entries):
        name = f"weapon{index}.qwg"
        output = args.output_dir / name
        if output.exists() and not args.force:
            raise SystemExit(f"refusing to overwrite {output} without --force")
        payload, record = build_qwg3(entries[index])
        output.write_bytes(payload)
        print(
            f"{name}: {record['name'] or '(unnamed)':<10} "
            f"cap={record['capacity']:<3} dmg={record['damage']:<3} "
            f"delay={record['delay']:<2} reload={record['reload_time']:<3} "
            f"muzzle=({record['muzzle_x']:.2f},{record['muzzle_y']:.2f}) "
            f"customPos={int(record['custom_position'])} "
            f"zoom={int(record['has_zoom'])} "
            f"flags=0x{record['flags']:04x}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
