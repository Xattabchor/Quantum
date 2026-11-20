#!/usr/bin/env python3
"""Run a small ROM-backed E65 audio helper in Unicorn.

This is a focused ARM/Thumb code test, not a Symbian OS boot or hardware
emulation. It maps the supplied flat ROM dump at its TRomHeader iRomBase,
executes AudioServer.dll's MMF rate-code-to-Hz routine, and follows its real
ROM import veneer into AudioStream.dll's ordinal-111 field getter. The audio
object, stack, and return sentinel are synthetic RAM supplied by this harness.
"""
from __future__ import annotations

import argparse
import hashlib
import struct
import sys
from pathlib import Path
from typing import Any

from rom_extract import E65Rom, RomFormatError

try:
    from unicorn import (
        UC_ARCH_ARM,
        UC_HOOK_CODE,
        UC_HOOK_MEM_INVALID,
        UC_MODE_ARM,
        UC_MODE_LITTLE_ENDIAN,
        UC_PROT_ALL,
        Uc,
        UcError,
    )
    from unicorn.arm_const import (
        UC_ARM_REG_LR,
        UC_ARM_REG_PC,
        UC_ARM_REG_R0,
        UC_ARM_REG_R10,
        UC_ARM_REG_SP,
    )
except ImportError as error:  # pragma: no cover - depends on optional host tool
    raise SystemExit(
        "Unicorn Engine is required. Install it with: python3 -m pip install unicorn"
    ) from error

DEFAULT_ROM = Path("google_drive/romdumpplus_090549.dmp")
EXPECTED_ROM_BASE = 0xF8000000
AUDIO_HELPER_CODE_OFFSET = 0x705A
AUDIO_IMPORT_VENEER_CODE_OFFSET = 0x9BE4
AUDIOSTREAM_RATE_GETTER_ORDINAL = 111
AUDIO_OBJECT_RATE_FIELD_OFFSET = 0x38

ROM_BOOT_RAM_BASE = 0x80000000
ROM_BOOT_RAM_SIZE = 0x01000000
RATE_TEST_RAM_BASE = 0x10000000
RATE_TEST_RAM_SIZE = 0x00010000

RATE_CASES = (
    (0x00000001, 8_000),
    (0x00000002, 11_025),
    (0x00000004, 16_000),
    (0x00000008, 22_050),
    (0x00000010, 32_000),
    (0x00000020, 44_100),
    (0x00000040, 48_000),
    (0x00000080, 88_200),
    (0x00000100, 96_000),
    (0x00000200, 12_000),
    (0x00000400, 24_000),
    (0x00000800, 64_000),
    (0x00000000, 0),
    (0x00000003, 0),
    (0x0000067F, 0),
)


def image_by_path(rom: E65Rom, path: str) -> dict[str, Any]:
    for image in rom.images:
        if image["path"].lower() == path.lower():
            return image
    raise RomFormatError(f"required ROM image is missing: {path}")


def load_flat_rom(rom: E65Rom) -> Uc:
    if rom.base != EXPECTED_ROM_BASE:
        raise RomFormatError(
            f"expected E65 ROM base {EXPECTED_ROM_BASE:#010x}, got {rom.base:#010x}"
        )
    if len(rom.data) % 0x1000:
        raise RomFormatError("dump length is not page-aligned for Unicorn mapping")
    uc = Uc(UC_ARCH_ARM, UC_MODE_ARM | UC_MODE_LITTLE_ENDIAN)
    uc.mem_map(rom.base, len(rom.data), UC_PROT_ALL)
    uc.mem_write(rom.base, rom.data)
    return uc


def audio_rate_converter_test(rom: E65Rom) -> tuple[int, int, int]:
    """Execute the ROM converter and its imported getter for all rate flags."""
    audio_server = image_by_path(rom, "/Sys/Bin/AudioServer.dll")
    audio_stream = image_by_path(rom, "/Sys/Bin/AudioStream.dll")
    helper = audio_server["code_rom_address"] + AUDIO_HELPER_CODE_OFFSET
    veneer = audio_server["code_rom_address"] + AUDIO_IMPORT_VENEER_CODE_OFFSET

    exports = next(
        table for table in rom.export_tables()
        if table["path"].lower() == "/sys/bin/audiostream.dll"
    )
    getter = exports["entries"][AUDIOSTREAM_RATE_GETTER_ORDINAL - 1]["address"]
    veneer_offset = veneer - rom.base
    veneer_target = struct.unpack_from("<I", rom.data, veneer_offset + 4)[0]
    if (veneer_target & ~1) != (getter & ~1):
        raise RomFormatError(
            "AudioServer import veneer no longer targets AudioStream ordinal "
            f"{AUDIOSTREAM_RATE_GETTER_ORDINAL}: {veneer_target:#010x} vs {getter:#010x}"
        )

    uc = load_flat_rom(rom)
    uc.mem_map(RATE_TEST_RAM_BASE, RATE_TEST_RAM_SIZE, UC_PROT_ALL)
    object_address = RATE_TEST_RAM_BASE + 0x1000
    stack_top = RATE_TEST_RAM_BASE + 0xD000
    return_sentinel = RATE_TEST_RAM_BASE + 0xF000
    reached: set[int] = set()

    def code_hook(machine: Uc, address: int, _size: int, _user_data: Any) -> None:
        if address in (veneer, getter & ~1):
            reached.add(address)
        if address == return_sentinel:
            machine.emu_stop()

    uc.hook_add(UC_HOOK_CODE, code_hook)
    cases_run = 0
    for rate_flag, expected_hz in RATE_CASES:
        uc.mem_write(object_address, b"\x00" * 0x40)
        uc.mem_write(
            object_address + AUDIO_OBJECT_RATE_FIELD_OFFSET,
            struct.pack("<I", rate_flag),
        )
        uc.reg_write(UC_ARM_REG_SP, stack_top)
        uc.reg_write(UC_ARM_REG_LR, return_sentinel | 1)
        uc.reg_write(UC_ARM_REG_R0, object_address)
        reached.clear()
        uc.emu_start(helper | 1, return_sentinel, count=4096)
        actual_hz = uc.reg_read(UC_ARM_REG_R0) & 0xFFFFFFFF
        if actual_hz != expected_hz:
            raise AssertionError(
                f"ROM conversion for {rate_flag:#06x}: {actual_hz} != {expected_hz} Hz"
            )
        if veneer not in reached or (getter & ~1) not in reached:
            raise AssertionError(
                f"{rate_flag:#06x}: execution did not reach both real ROM import targets"
            )
        cases_run += 1

    return helper, getter, cases_run


def boot_vector_smoke(rom: E65Rom) -> dict[str, Any]:
    """Try the reset vector with a synthetic RAM/SL setup; stop on first fault."""
    uc = load_flat_rom(rom)
    uc.mem_map(ROM_BOOT_RAM_BASE, ROM_BOOT_RAM_SIZE, UC_PROT_ALL)
    # The real boot chain supplies this register/context. This placeholder is
    # deliberately explicit: it is only enough to inspect the early ROM path.
    uc.reg_write(UC_ARM_REG_R10, ROM_BOOT_RAM_BASE + 0x10000)
    uc.reg_write(UC_ARM_REG_SP, ROM_BOOT_RAM_BASE + 0x8000)
    uc.reg_write(UC_ARM_REG_LR, rom.base + 0x40)
    fault: dict[str, Any] = {}

    def invalid_memory_hook(
        machine: Uc,
        access: int,
        address: int,
        size: int,
        value: int,
        _user_data: Any,
    ) -> bool:
        fault.update({
            "access_type": access,
            "address": address,
            "size": size,
            "value": value,
            "pc": machine.reg_read(UC_ARM_REG_PC),
        })
        return False

    uc.hook_add(UC_HOOK_MEM_INVALID, invalid_memory_hook)
    error_text = ""
    try:
        uc.emu_start(rom.base, rom.base + len(rom.data), count=2000)
    except UcError as error:
        error_text = str(error)
    return {
        "instructions_limit": 2000,
        "pc_after_stop": uc.reg_read(UC_ARM_REG_PC),
        "fault": fault,
        "emulator_error": error_text,
        "boot_completed": False,
    }


def format_hex(value: int) -> str:
    return f"0x{value:08X}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", type=Path, default=DEFAULT_ROM,
                        help="flat E65 TRomImage dump")
    parser.add_argument("--skip-boot-smoke", action="store_true",
                        help="run only the ROM-backed audio-rate helper")
    args = parser.parse_args()

    try:
        data = args.rom.read_bytes()
        rom = E65Rom(data)
        rom.parse()
        if rom.errors:
            raise RomFormatError("ROM tree has parser errors: " + "; ".join(rom.errors[:5]))
        if rom.summary()["invalid_file_range_count"]:
            raise RomFormatError("ROM tree contains file ranges outside the dump")

        helper, getter, cases_run = audio_rate_converter_test(rom)
        print(f"ROM: {args.rom} ({len(data):,} bytes, SHA-256 {hashlib.sha256(data).hexdigest()})")
        print(f"Mapping: ARM little-endian; TRomHeader base {format_hex(rom.base)}; "
              f"dump length {len(data):#x} (declared {rom.declared_size:#x})")
        print(f"Executed ROM AudioServer.dll rate helper at {format_hex(helper)} (Thumb)")
        print(f"  real AudioStream.dll export #{AUDIOSTREAM_RATE_GETTER_ORDINAL} "
              f"getter {format_hex(getter & ~1)} (Thumb), reached via ROM import veneer")
        print(f"  {cases_run} cases passed: all 12 single MMF rate flags plus zero, "
              "a multi-bit value, and the E65 mask 0x0000067F")
        print("  result is ROM rate-code translation only; it does not identify an E65 DAC clock or route")

        if not args.skip_boot_smoke:
            boot = boot_vector_smoke(rom)
            fault = boot["fault"]
            print("Boot-vector smoke (synthetic SL/stack, no OS/device model):")
            if fault:
                print(f"  stopped on unmapped access {fault['access_type']} at "
                      f"{format_hex(fault['address'])}, PC {format_hex(fault['pc'])}, "
                      f"size {fault['size']} ({boot['emulator_error']})")
            else:
                print(f"  stopped at PC {format_hex(boot['pc_after_stop'])} "
                      "without completing boot")
            print("  full E65 boot/audio-server session is not simulated")
        return 0
    except (OSError, RomFormatError, UcError, ValueError, struct.error, AssertionError) as error:
        print(f"e65-unicorn-audio: error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
