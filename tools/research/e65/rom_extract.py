#!/usr/bin/env python3
"""Bounds-checked extractor/inventory for Nokia E65 TRomImage dumps.

The parser is intentionally stdlib-only. It reads the TRomHeader fields used
by this E65 dump, follows TRomRootDirectoryList/TRomDir entries, recognizes
XIP E32 images by the ROM image header/code-address relationship, inventories
export address tables, and can extract selected logical ROM files.

For a TRomEntry containing an E32 XIP image, iAddressLin points at a 0x78-byte
TRomImageHeader and iSize counts the mapped code/data payload after that
header. Extracted .romimg files preserve the header and payload; they are not
ordinary relocatable .dll/.exe E32 files.
"""

from __future__ import annotations

import argparse
import csv
import fnmatch
import hashlib
import json
import struct
import sys
from collections import defaultdict
from pathlib import Path, PurePosixPath
from typing import Any, Iterable

ROM_BASE_OFFSET = 0x8C
ROM_SIZE_OFFSET = 0x90
ROOT_LIST_POINTER_OFFSET = 0x94
ROOT_LIST_HEADER_SIZE = 4
ROOT_LIST_ENTRY_SIZE = 8
TRomImageHeader_SIZE = 0x78
E32_UID_DLL = 0x10000079
E32_UID_EXE = 0x1000007A
ATTR_DIRECTORY = 0x10


class RomFormatError(Exception):
    """Raised when a ROM pointer or structure is outside the input dump."""


class E65Rom:
    def __init__(self, data: bytes, base_override: int | None = None):
        if len(data) < ROOT_LIST_POINTER_OFFSET + 4:
            raise RomFormatError("input is too small to contain the TRomHeader fields")
        self.data = data
        self.base = base_override if base_override is not None else self.u32(ROM_BASE_OFFSET)
        self.declared_size = self.u32(ROM_SIZE_OFFSET)
        self.root_list_address = self.u32(ROOT_LIST_POINTER_OFFSET)
        self.entries: list[dict[str, Any]] = []
        self.images: list[dict[str, Any]] = []
        self.roots: list[dict[str, int]] = []
        self.errors: list[str] = []
        self.warnings: list[str] = []
        self._visited_directories: set[int] = set()

        if not self.base:
            raise RomFormatError("TRomHeader.iRomBase is zero; pass --base explicitly")

    def u8(self, offset: int) -> int:
        self.require_range(offset, 1, "byte")
        return self.data[offset]

    def u16(self, offset: int) -> int:
        self.require_range(offset, 2, "uint16")
        return struct.unpack_from("<H", self.data, offset)[0]

    def u32(self, offset: int) -> int:
        self.require_range(offset, 4, "uint32")
        return struct.unpack_from("<I", self.data, offset)[0]

    def require_range(self, offset: int, size: int, label: str) -> None:
        if offset < 0 or size < 0 or offset + size > len(self.data):
            raise RomFormatError(
                f"{label} range [{offset:#x}, {offset + size:#x}) is outside "
                f"the {len(self.data):#x}-byte dump"
            )

    def rom_offset(self, address: int, size: int = 1, label: str = "ROM pointer") -> int:
        """Convert a ROM linear address to an input offset with strict bounds."""
        offset = address - self.base
        self.require_range(offset, size, label)
        return offset

    def try_rom_offset(self, address: int, size: int = 1) -> int | None:
        try:
            return self.rom_offset(address, size)
        except RomFormatError:
            return None

    def parse(self) -> None:
        root_list_offset = self.rom_offset(self.root_list_address, ROOT_LIST_HEADER_SIZE,
                                           "TRomRootDirectoryList")
        root_count = self.u32(root_list_offset)
        if root_count > 1024:
            raise RomFormatError(f"implausible root directory count: {root_count}")
        self.require_range(root_list_offset + ROOT_LIST_HEADER_SIZE,
                           root_count * ROOT_LIST_ENTRY_SIZE,
                           "TRomRootDirectoryList entries")

        for index in range(root_count):
            item = root_list_offset + ROOT_LIST_HEADER_SIZE + index * ROOT_LIST_ENTRY_SIZE
            variant, directory_address = struct.unpack_from("<II", self.data, item)
            self.roots.append({"variant": variant, "directory_address": directory_address})
            self._walk_directory(directory_address, "")

        self._parse_e32_headers()
        self._validate_file_ranges()

    def _walk_directory(self, address: int, parent: str) -> None:
        dir_offset = self.rom_offset(address, 4, "TRomDir pointer")
        if dir_offset in self._visited_directories:
            return
        self._visited_directories.add(dir_offset)

        directory_size = self.u32(dir_offset)
        entries_start = dir_offset + 4
        entries_end = entries_start + directory_size
        if entries_end > len(self.data):
            raise RomFormatError(
                f"directory {parent or '/'} at {address:#010x} spans beyond dump"
            )

        cursor = entries_start
        while cursor + 10 <= entries_end:
            item_size = self.u32(cursor)
            item_address = self.u32(cursor + 4)
            attributes = self.u8(cursor + 8)
            name_chars = self.u8(cursor + 9)
            name_bytes = name_chars * 2
            record_end = cursor + 10 + name_bytes
            if record_end > entries_end:
                raise RomFormatError(
                    f"truncated UTF-16 name in directory {parent or '/'} at {cursor:#x}"
                )
            name = self.data[cursor + 10:record_end].decode("utf-16le", errors="replace")
            if not name or name in (".", "..") or "/" in name or "\\" in name:
                raise RomFormatError(
                    f"invalid ROM directory name {name!r} at {cursor:#x}"
                )
            path = f"{parent}/{name}" if parent else f"/{name}"
            record: dict[str, Any] = {
                "path": path,
                "size": item_size,
                "address": item_address,
                "attributes": attributes,
                "is_directory": bool(attributes & ATTR_DIRECTORY),
                "directory_entry_offset": cursor,
            }
            self.entries.append(record)
            if record["is_directory"]:
                self._walk_directory(item_address, path)
            cursor = (record_end + 3) & ~3

        if cursor > entries_end:
            raise RomFormatError(f"directory alignment overran {parent or '/'}")
        if cursor != entries_end and any(self.data[cursor:entries_end]):
            # Some directory blocks can include padding; retain rather than
            # silently assume that non-zero trailing bytes are another record.
            self.warnings.append(
                f"non-zero trailing bytes in directory {parent or '/'} "
                f"at [{cursor:#x}, {entries_end:#x})"
            )

    def _parse_e32_headers(self) -> None:
        for item in self.entries:
            if item["is_directory"]:
                continue
            offset = self.try_rom_offset(item["address"], TRomImageHeader_SIZE)
            if offset is None:
                continue
            uid1, uid2, uid3 = struct.unpack_from("<III", self.data, offset)
            if uid1 not in (E32_UID_DLL, E32_UID_EXE):
                continue

            entry_point = self.u32(offset + 0x10)
            code_address = self.u32(offset + 0x14)
            data_address = self.u32(offset + 0x18)
            code_size = self.u32(offset + 0x1C)
            text_size = self.u32(offset + 0x20)
            data_size = self.u32(offset + 0x24)
            bss_size = self.u32(offset + 0x28)
            dll_ref_address = self.u32(offset + 0x38)
            export_count = self.u32(offset + 0x3C)
            export_address = self.u32(offset + 0x40)
            secure_id = self.u32(offset + 0x44)
            vendor_id = self.u32(offset + 0x48)
            caps0 = self.u32(offset + 0x4C)
            caps1 = self.u32(offset + 0x50)
            version_word = self.u32(offset + 0x54)
            image_flags = self.u32(offset + 0x58)
            process_priority = self.u32(offset + 0x5C)

            # The +0x78 relation distinguishes a TRomImageHeader from an E32
            # file that merely starts with the UID1/UID2/UID3 triplet.
            is_xip = code_address == item["address"] + TRomImageHeader_SIZE
            payload_ok = code_size + data_size <= item["size"]
            if not is_xip:
                # Keep possible non-XIP E32 entries visible as notes, but do
                # not interpret their ROM body as a normal XIP code segment.
                item["e32_uid1"] = uid1
                item["e32_header_candidate"] = True
                item["xip_header_match"] = False
                continue

            image: dict[str, Any] = {
                "path": item["path"],
                "rom_entry_address": item["address"],
                "rom_entry_offset": offset,
                "tr_entry_size": item["size"],
                "rom_image_header_size": TRomImageHeader_SIZE,
                "uid1": uid1,
                "uid2": uid2,
                "uid3": uid3,
                "entry_point": entry_point,
                "code_address": code_address,
                "data_address": data_address,
                "code_size": code_size,
                "text_size": text_size,
                "data_size": data_size,
                "bss_size": bss_size,
                "dll_ref_address": dll_ref_address,
                "export_count": export_count,
                "export_address": export_address,
                "secure_id": secure_id,
                "vendor_id": vendor_id,
                "capabilities_low": caps0,
                "capabilities_high": caps1,
                "module_version_word": version_word,
                "image_flags": image_flags,
                "process_priority": process_priority,
                "xip_header_match": True,
                "payload_size_matches_code_data": item["size"] == code_size + data_size,
                "payload_size_delta": item["size"] - code_size - data_size,
                "text_size_valid": text_size <= code_size,
                "payload_size_valid": payload_ok,
            }
            item["e32_uid1"] = uid1
            item["e32_header_candidate"] = True
            item["xip_header_match"] = True
            item["e32_index"] = len(self.images)
            self.images.append(image)

    def _validate_file_ranges(self) -> None:
        for item in self.entries:
            if item["is_directory"]:
                continue
            start = self.try_rom_offset(item["address"], 0)
            if start is None:
                item["range_valid"] = False
                item["range_error"] = "file address is outside the input dump"
                self.errors.append(f"{item['path']}: entry address outside dump")
                continue

            image = self.image_for_path(item["path"])
            if image is not None:
                extent_start = start
                extent_size = TRomImageHeader_SIZE + item["size"]
            else:
                extent_start = start
                extent_size = item["size"]
            if extent_start + extent_size > len(self.data):
                item["range_valid"] = False
                item["range_error"] = "file extent is outside the input dump"
                self.errors.append(
                    f"{item['path']}: range [{extent_start:#x}, "
                    f"{extent_start + extent_size:#x}) exceeds dump"
                )
            else:
                item["range_valid"] = True
                item["payload_offset"] = start + (TRomImageHeader_SIZE if image else 0)
                item["payload_end_offset"] = item["payload_offset"] + item["size"]
                if image is not None:
                    image["whole_image_end_offset"] = extent_start + extent_size
                    image["payload_offset"] = start + TRomImageHeader_SIZE
                    image["code_file_offset"] = start + TRomImageHeader_SIZE
                    image["code_rom_address"] = self.base + start + TRomImageHeader_SIZE
                    image["body_range_valid"] = image["payload_size_valid"] and image["text_size_valid"]

    def image_for_path(self, path: str) -> dict[str, Any] | None:
        for image in self.images:
            if image["path"] == path:
                return image
        return None

    def export_tables(self) -> list[dict[str, Any]]:
        tables: list[dict[str, Any]] = []
        for image in self.images:
            count = image["export_count"]
            address = image["export_address"]
            table: dict[str, Any] = {
                "path": image["path"],
                "export_count": count,
                "table_address": address,
                "entries": [],
            }
            if not count:
                tables.append(table)
                continue
            if count > 100000:
                table["error"] = f"implausible export count: {count}"
                tables.append(table)
                self.errors.append(f"{image['path']}: {table['error']}")
                continue
            table_offset = self.try_rom_offset(address, count * 4)
            if table_offset is None:
                table["error"] = "export address table is outside the input dump"
                tables.append(table)
                self.errors.append(f"{image['path']}: {table['error']}")
                continue
            for index in range(count):
                function_address = self.u32(table_offset + index * 4)
                aligned_address = function_address & ~1
                table["entries"].append({
                    "ordinal": index + 1,
                    "address": function_address,
                    "thumb_state_bit": function_address & 1,
                    "target_offset": self.try_rom_offset(aligned_address, 1),
                })
            tables.append(table)
        return tables

    def static_dll_reference_tables(self) -> list[dict[str, Any]]:
        """Read only the documented 16-bit flags/count + pointer-array prefix."""
        known_images: dict[int, list[str]] = defaultdict(list)
        for image in self.images:
            paths = known_images[image["rom_entry_address"]]
            if image["path"] not in paths:
                paths.append(image["path"])

        tables: list[dict[str, Any]] = []
        for image in self.images:
            address = image["dll_ref_address"]
            if not address:
                continue
            offset = self.try_rom_offset(address, 4)
            if offset is None:
                tables.append({"path": image["path"], "address": address,
                               "error": "DLL-reference table pointer outside dump"})
                continue
            flags, count = struct.unpack_from("<HH", self.data, offset)
            if count > 4096 or offset + 4 + count * 4 > len(self.data):
                tables.append({"path": image["path"], "address": address,
                               "flags": flags, "count": count,
                               "error": "invalid static DLL-reference count/range"})
                continue
            refs = []
            for index in range(count):
                ref_address = self.u32(offset + 4 + index * 4)
                refs.append({
                    "address": ref_address,
                    "header_paths": known_images.get(ref_address, []),
                    "rom_offset": self.try_rom_offset(ref_address, 1),
                })
            tables.append({"path": image["path"], "address": address,
                           "flags": flags, "count": count, "references": refs})
        return tables

    def summary(self) -> dict[str, Any]:
        files = [entry for entry in self.entries if not entry["is_directory"]]
        directories = [entry for entry in self.entries if entry["is_directory"]]
        e32_xip = len(self.images)
        max_end = 0
        last_path = None
        for item in files:
            if not item.get("range_valid"):
                continue
            end = item["payload_end_offset"]
            if item.get("xip_header_match"):
                end = item["address"] - self.base + TRomImageHeader_SIZE + item["size"]
            if end > max_end:
                max_end, last_path = end, item["path"]

        by_addr: dict[int, list[str]] = defaultdict(list)
        for item in files:
            by_addr[item["address"]].append(item["path"])
        aliases = [paths for paths in by_addr.values() if len(paths) > 1]

        return {
            "input_bytes": len(self.data),
            "input_sha256": hashlib.sha256(self.data).hexdigest(),
            "rom_base": self.base,
            "declared_rom_size": self.declared_size,
            "declared_rom_size_delta": self.declared_size - len(self.data),
            "root_list_address": self.root_list_address,
            "roots": self.roots,
            "record_count": len(self.entries),
            "directory_count": len(directories),
            "file_count": len(files),
            "xip_e32_image_count": e32_xip,
            "invalid_file_range_count": sum(not item.get("range_valid", False) for item in files),
            "nonzero_directory_tail_warnings": len(self.warnings),
            "duplicate_address_alias_groups": aliases,
            "last_logical_payload_end_offset": max_end,
            "last_logical_payload_path": last_path,
            "bytes_after_last_logical_payload": len(self.data) - max_end,
            "errors": self.errors,
            "warnings": self.warnings,
        }

    def object_bytes(self, item: dict[str, Any]) -> bytes:
        if item["is_directory"]:
            raise ValueError("cannot extract a directory as a file")
        start = self.rom_offset(item["address"], 0, "file entry")
        image = self.image_for_path(item["path"])
        if image is not None:
            total = TRomImageHeader_SIZE + item["size"]
        else:
            total = item["size"]
        self.require_range(start, total, f"file {item['path']}")
        return self.data[start:start + total]


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n",
                    encoding="utf-8")


def is_audio_candidate(item: dict[str, Any]) -> bool:
    path = item["path"].lower()
    audio_tokens = (
        "audio", "sound", "devsound", "mmf", "dsp", "adsp", "ehwa",
        "mp3", "aac", "amr", "pcm", "tone", "mcbsp", "dma", "voice",
    )
    return (any(token in path for token in audio_tokens)
            or path.endswith(".dof")
            or path.endswith(".dll55l"))


def safe_extract_path(output_root: Path, rom_path: str) -> Path:
    parts = PurePosixPath(rom_path).parts
    if not parts or parts[0] != "/" or any(part in (".", "..") for part in parts[1:]):
        raise ValueError(f"refusing unsafe ROM path {rom_path!r}")
    return output_root.joinpath(*parts[1:])


def select_files(rom: E65Rom, exact: Iterable[str], patterns: Iterable[str],
                 audio_only: bool, extract_all: bool) -> list[dict[str, Any]]:
    wanted_exact = {path.lower() for path in exact}
    wanted_patterns = [pattern.lower() for pattern in patterns]
    selected = []
    for item in rom.entries:
        if item["is_directory"]:
            continue
        path_lower = item["path"].lower()
        if extract_all or (audio_only and is_audio_candidate(item)):
            selected.append(item)
        elif path_lower in wanted_exact or any(fnmatch.fnmatch(path_lower, pattern)
                                               for pattern in wanted_patterns):
            selected.append(item)
    # Preserve ROM directory order while avoiding duplicate selection.
    return list({item["path"]: item for item in selected}.values())


def inventory_row(item: dict[str, Any]) -> dict[str, Any]:
    return {
        "path": item["path"],
        "size": item["size"],
        "address": f"0x{item['address']:08x}",
        "attributes": f"0x{item['attributes']:02x}",
        "is_directory": item["is_directory"],
        "range_valid": item.get("range_valid", ""),
        "e32_uid1": f"0x{item['e32_uid1']:08x}" if "e32_uid1" in item else "",
        "xip_header_match": item.get("xip_header_match", ""),
    }


def run(args: argparse.Namespace) -> int:
    input_path = Path(args.rom)
    data = input_path.read_bytes()
    rom = E65Rom(data, args.base)
    rom.parse()
    exports = rom.export_tables()
    dll_refs = rom.static_dll_reference_tables()
    summary = rom.summary()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    write_json(out_dir / "summary.json", summary)
    write_json(out_dir / "inventory.json", rom.entries)
    write_json(out_dir / "e32-images.json", rom.images)
    write_json(out_dir / "e32-exports.json", exports)
    write_json(out_dir / "e32-static-dll-refs.json", dll_refs)

    with (out_dir / "inventory.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(inventory_row(rom.entries[0]).keys()))
        writer.writeheader()
        writer.writerows(inventory_row(item) for item in rom.entries)

    selected = select_files(rom, args.extract, args.extract_glob,
                            args.extract_audio, args.extract_all)
    extracted = []
    for item in selected:
        destination = safe_extract_path(out_dir / "files", item["path"])
        destination.parent.mkdir(parents=True, exist_ok=True)
        blob = rom.object_bytes(item)
        destination.write_bytes(blob)
        extracted.append({"rom_path": item["path"], "output": str(destination),
                          "bytes": len(blob), "sha256": hashlib.sha256(blob).hexdigest(),
                          "representation": "TRomImageHeader+XIP payload"
                          if rom.image_for_path(item["path"]) is not None
                          else "raw ROM file bytes"})
    write_json(out_dir / "extracted-files.json", extracted)

    print(json.dumps(summary, indent=2, ensure_ascii=False))
    print(f"\nWrote inventory, E32 headers/exports and extraction manifest to {out_dir}")
    print(f"Extracted {len(extracted)} logical files")
    return 1 if rom.errors else 0


def make_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rom", default="google_drive/romdumpplus_090549.dmp",
                        help="path to the ROM dump")
    parser.add_argument("--out", default="google_drive/e65-rom-analysis",
                        help="output directory (default is under ignored google_drive/)")
    parser.add_argument("--base", type=lambda value: int(value, 0), default=None,
                        help="override the ROM base address (normally read at header+0x8c)")
    parser.add_argument("--extract", action="append", default=[], metavar="ROM_PATH",
                        help="extract an exact ROM path; repeatable")
    parser.add_argument("--extract-glob", action="append", default=[], metavar="GLOB",
                        help="extract paths matching a case-insensitive glob; repeatable")
    parser.add_argument("--extract-audio", action="store_true",
                        help="extract audio/DSP-named modules, DSP resources and .dof/.dll55L files")
    parser.add_argument("--extract-all", action="store_true",
                        help="extract every non-directory logical ROM file")
    return parser


def main() -> int:
    args = make_parser().parse_args()
    try:
        return run(args)
    except (OSError, RomFormatError, ValueError, struct.error) as error:
        print(f"e65-rom-extract: error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
