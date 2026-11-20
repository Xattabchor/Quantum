#!/usr/bin/env python3
"""Emulate the OMAP1710 scalar-divide, weapon-row and rain-streak kernels.

Requires keystone-engine and unicorn. This checks ARM instruction behavior
and pixel parity on host; it is not a substitute for an E65 timing/device test.
"""
from __future__ import annotations

import random
import re
import struct
from pathlib import Path

from keystone import KS_ARCH_ARM, KS_MODE_ARM, Ks
from unicorn import UC_ARCH_ARM, UC_MODE_ARM, UC_MODE_LITTLE_ENDIAN, Uc
from unicorn.arm_const import UC_ARM_REG_LR, UC_ARM_REG_R0, UC_ARM_REG_R1
from unicorn.arm_const import UC_ARM_REG_R2, UC_ARM_REG_R3, UC_ARM_REG_SP

ROOT = Path(__file__).resolve().parents[1]
DSP_ASM = ROOT / "src/core/omap1710_dsp.S"
EFFECTS_ASM = ROOT / "src/core/omap1710_effects.S"
CODE_BASE = 0x10000
STOP_PC = 0x1FFF0
DATA_BASE = 0x22000
STACK_BASE = 0x50000


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def function_body(path: Path, name: str) -> str:
    text = path.read_text(encoding="utf-8")
    start = text.index(name + ":")
    end = text.index(".size " + name, start)
    body = re.sub(r"/\*.*?\*/", "", text[start:end], flags=re.S)
    lines = []
    for raw_line in body.splitlines()[1:]:
        line = raw_line.split("@", 1)[0].strip()
        if not line:
            continue
        if line.startswith(".") and not line.startswith(".L"):
            continue
        lines.append(line)
    return "\n".join(lines)


def arm_kernel(path: Path, name: str) -> tuple[Uc, int]:
    instructions = function_body(path, name)
    encoded, _ = Ks(KS_ARCH_ARM, KS_MODE_ARM).asm(instructions, CODE_BASE)
    require(encoded is not None, f"could not assemble {name}")
    uc = Uc(UC_ARCH_ARM, UC_MODE_ARM | UC_MODE_LITTLE_ENDIAN)
    uc.mem_map(CODE_BASE, 0x10000)
    uc.mem_map(DATA_BASE, 0x20000)
    uc.mem_map(STACK_BASE, 0x10000)
    uc.mem_write(CODE_BASE, bytes(encoded))
    return uc, CODE_BASE


def call(uc: Uc, address: int, r0: int, r1: int = 0,
         r2: int = 0, r3: int = 0) -> int:
    uc.reg_write(UC_ARM_REG_SP, STACK_BASE + 0x8000)
    uc.reg_write(UC_ARM_REG_LR, STOP_PC)
    uc.reg_write(UC_ARM_REG_R0, r0 & 0xFFFFFFFF)
    uc.reg_write(UC_ARM_REG_R1, r1 & 0xFFFFFFFF)
    uc.reg_write(UC_ARM_REG_R2, r2 & 0xFFFFFFFF)
    uc.reg_write(UC_ARM_REG_R3, r3 & 0xFFFFFFFF)
    uc.emu_start(address, STOP_PC, count=200000)
    return uc.reg_read(UC_ARM_REG_R0) & 0xFFFFFFFF


def test_scalar_pdiv() -> int:
    uc, address = arm_kernel(DSP_ASM, "quantum_omap1710_udiv_u32")
    rng = random.Random(0xE65D1D1)
    cases = [
        (0, 1), (0xFFFFFFFF, 1), (0xFFFFFFFF, 0xFFFFFFFF),
        (0x80000000, 0x7FFFFFFF), (0x7FFFFFFF, 0x80000000),
        (123456789, 12345), (0, 0), (1, 0),
    ]
    cases.extend((rng.getrandbits(32), rng.getrandbits(32))
                 for _ in range(5000))
    for dividend, divisor in cases:
        expected = dividend // divisor if divisor else 0
        actual = call(uc, address, dividend, divisor)
        require(actual == expected,
                f"PDiv mismatch: {dividend:#x}/{divisor:#x}: "
                f"{actual:#x} != {expected:#x}")
    return len(cases)


def rounded_divide_255(value: int) -> int:
    value += 128
    return (value + (value >> 8)) >> 8


def blend_pixel(destination: int, source: int) -> int:
    alpha = (source >> 24) & 0xFF
    if alpha == 0:
        return destination & 0x00FFFFFF
    source_rgb = source & 0x00FFFFFF
    if alpha == 255:
        return source_rgb
    inverse = 255 - alpha
    out = []
    for shift in (16, 8, 0):
        s = (source_rgb >> shift) & 0xFF
        d = (destination >> shift) & 0xFF
        out.append(rounded_divide_255(s * alpha + d * inverse))
    return (out[0] << 16) | (out[1] << 8) | out[2]


def test_weapon_rows() -> int:
    uc, address = arm_kernel(EFFECTS_ASM, "quantum_omap1710_blend_argb_row")
    rng = random.Random(0xA65B1E4D)
    cases = 0
    for count in list(range(1, 66)) + [127, 240, 512]:
        source = [rng.getrandbits(32) for _ in range(count)]
        # Ensure the two alpha fast paths are exercised for every row length.
        if count > 0:
            source[0] &= 0x00FFFFFF
        if count > 1:
            source[1] = (source[1] & 0x00FFFFFF) | 0xFF000000
        initial = [rng.getrandbits(24) for _ in range(count)]
        for mirrored in (False, True):
            uc.mem_write(DATA_BASE, struct.pack("<" + "I" * count, *initial))
            src_addr = DATA_BASE + 0x4000
            uc.mem_write(src_addr, struct.pack("<" + "I" * count, *source))
            first_source = src_addr + (count - 1) * 4 if mirrored else src_addr
            step = -1 if mirrored else 1
            call(uc, address, DATA_BASE, first_source, count, step)
            actual = list(struct.unpack(
                "<" + "I" * count,
                uc.mem_read(DATA_BASE, count * 4)))
            ordered_source = list(reversed(source)) if mirrored else source
            expected = [blend_pixel(d, s)
                        for d, s in zip(initial, ordered_source)]
            require(actual == expected,
                    f"weapon row differs for count={count}, mirrored={mirrored}")
            cases += count
    return cases


def rain_reference(width: int, height: int, stride: int, x: int, y: int,
                   size: int, color: int) -> list[int]:
    output = [0] * (stride * height)
    dx = size // 4
    dy = size
    error = dy // 2
    current_x = x
    current_y = y
    remaining = dy
    while True:
        if 0 <= current_x < width and 0 <= current_y < height:
            output[current_y * stride + current_x] = color
        if remaining == 0:
            break
        error += dx
        if dy != 0 and error >= dy:
            error -= dy
            current_x -= 1
        current_y += 1
        remaining -= 1
    return output


def test_rain_streaks() -> int:
    uc, address = arm_kernel(EFFECTS_ASM, "quantum_omap1710_rain_streak")
    rng = random.Random(0xA11CE65)
    width, height, stride = 48, 40, 56
    dest_addr = DATA_BASE
    args_addr = DATA_BASE + 0x8000
    cases = []
    for size in range(0, 65):
        cases.append((rng.randint(-12, width + 12),
                      rng.randint(-12, height + 12), size))
    cases.extend((rng.randint(-64, width + 64),
                  rng.randint(-64, height + 64), rng.randint(0, 128))
                 for _ in range(700))

    for x, y, size in cases:
        dx, dy = size // 4, size
        color = rng.randrange(0x1000000)
        uc.mem_write(dest_addr, b"\0" * (stride * height * 4))
        args = struct.pack("<9I", dest_addr, stride, width, height,
                           x & 0xFFFFFFFF, y & 0xFFFFFFFF, dx, dy, color)
        uc.mem_write(args_addr, args)
        call(uc, address, args_addr)
        actual = list(struct.unpack(
            "<" + "I" * (stride * height),
            uc.mem_read(dest_addr, stride * height * 4)))
        expected = rain_reference(width, height, stride, x, y, size, color)
        require(actual == expected,
                f"rain streak mismatch at ({x},{y}), size={size}")
    return len(cases)


def test_mode9_mul_order_is_selected_in_profile_source() -> None:
    source = DSP_ASM.read_text(encoding="utf-8")
    require("#ifdef QUANTUM_LEVEL41_OMAP1710_MODE9_MUL_ORDER_EXPERIMENT" in source,
            "mode-9 MUL-order assembly gate is missing")
    require("mul r0, r0, r3" in source and "mul r12, r12, r3" in source,
            "mode-9 optimized Rs operand order is missing")
    require("mul r0, r3, r0" in source and "mul r12, r3, r12" in source,
            "portable/reference operand order should remain available")


def main() -> None:
    pdiv = test_scalar_pdiv()
    weapon = test_weapon_rows()
    rain = test_rain_streaks()
    test_mode9_mul_order_is_selected_in_profile_source()
    print(f"OMAP1710 assembly emulator parity passed: "
          f"PDiv {pdiv} cases, weapon {weapon} pixels, rain {rain} streaks; "
          "Mode-9 MUL-order gate present.")


if __name__ == "__main__":
    main()
