#!/usr/bin/env python3
"""Small synthetic tests for the E65 ROM extractor (no ROM dump required)."""

from __future__ import annotations

import struct
import unittest

from rom_extract import E65Rom, RomFormatError, TRomImageHeader_SIZE


BASE = 0x10000000
DUMP_SIZE = 0x500


def put_u32(data: bytearray, offset: int, value: int) -> None:
    struct.pack_into("<I", data, offset, value)


def add_dir_entry(data: bytearray, offset: int, size: int, address: int,
                  attributes: int, name: str) -> int:
    encoded = name.encode("utf-16le")
    data[offset:offset + 4] = struct.pack("<I", size)
    data[offset + 4:offset + 8] = struct.pack("<I", address)
    data[offset + 8] = attributes
    data[offset + 9] = len(encoded) // 2
    data[offset + 10:offset + 10 + len(encoded)] = encoded
    return (offset + 10 + len(encoded) + 3) & ~3


def sample_rom() -> bytes:
    data = bytearray(DUMP_SIZE)
    put_u32(data, 0x8C, BASE)
    put_u32(data, 0x90, DUMP_SIZE)
    put_u32(data, 0x94, BASE + 0x100)

    # One root variant, whose directory contains a raw file, an E32 XIP DLL,
    # and a nested directory with a second raw file.
    put_u32(data, 0x100, 1)
    struct.pack_into("<II", data, 0x104, 0x09080004, BASE + 0x120)

    cursor = 0x124
    cursor = add_dir_entry(data, cursor, 3, BASE + 0x240, 0x01, "raw")
    cursor = add_dir_entry(data, cursor, 4, BASE + 0x280, 0x01, "test.dll")
    cursor = add_dir_entry(data, cursor, 0, BASE + 0x180, 0x10, "sub")
    put_u32(data, 0x120, cursor - 0x124)

    sub_end = add_dir_entry(data, 0x184, 2, BASE + 0x248, 0x01, "x")
    put_u32(data, 0x180, sub_end - 0x184)
    data[0x240:0x243] = b"raw"
    data[0x248:0x24A] = b"ok"

    # A minimal mapped ROM image: 0x78-byte TRomImageHeader plus four bytes
    # of code. The export directory is empty in this test fixture.
    hdr = 0x280
    put_u32(data, hdr + 0x00, 0x10000079)
    put_u32(data, hdr + 0x04, 0x1000008D)
    put_u32(data, hdr + 0x08, 0x12345678)
    put_u32(data, hdr + 0x10, BASE + hdr + TRomImageHeader_SIZE)
    put_u32(data, hdr + 0x14, BASE + hdr + TRomImageHeader_SIZE)
    put_u32(data, hdr + 0x1C, 4)
    put_u32(data, hdr + 0x20, 4)
    put_u32(data, hdr + 0x3C, 0)
    data[hdr + TRomImageHeader_SIZE:hdr + TRomImageHeader_SIZE + 4] = b"CODE"
    return bytes(data)


class RomExtractorTests(unittest.TestCase):
    def test_walks_nested_directories_and_extracts_xip_header(self) -> None:
        rom = E65Rom(sample_rom())
        rom.parse()
        paths = [item["path"] for item in rom.entries]
        self.assertEqual(paths, ["/raw", "/test.dll", "/sub", "/sub/x"])
        self.assertEqual(len(rom.images), 1)
        self.assertEqual(rom.images[0]["path"], "/test.dll")
        self.assertEqual(rom.images[0]["payload_size_delta"], 0)
        image_entry = next(item for item in rom.entries if item["path"] == "/test.dll")
        blob = rom.object_bytes(image_entry)
        self.assertEqual(len(blob), TRomImageHeader_SIZE + 4)
        self.assertEqual(blob[-4:], b"CODE")
        self.assertEqual(rom.summary()["invalid_file_range_count"], 0)

    def test_rejects_out_of_bounds_root_directory(self) -> None:
        data = bytearray(sample_rom())
        struct.pack_into("<II", data, 0x104, 0x09080004, BASE + DUMP_SIZE + 8)
        rom = E65Rom(bytes(data))
        with self.assertRaises(RomFormatError):
            rom.parse()


if __name__ == "__main__":
    unittest.main()
