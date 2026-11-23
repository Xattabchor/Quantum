#!/usr/bin/env python3
"""Pure-Python port of the scoped local SISX writer (stage 3).

The upstream stage-3 writer (`local_sis_writer/cpp/symbian/sis/sis.cc`) uses the
SDK 0.3.0 toolchain only for the E32 *converter*; the package container itself
is a plain serialisation of length-prefixed fields plus two CRC16s. This module
reproduces that serialisation exactly, so the v1.0.60 candidate can be produced
and re-inspected without the 326 MiB SDK download.

`build_files` mirrors `BuildFiles` including the local `data_files` extension;
`inspect_package` mirrors `InspectPackage` with its canonical re-serialisation;
`patch_e32_heap` performs the E32 heap edit (offset 60) plus header CRC refresh.

Running the module as a script prints an inventory of a package and, given
`--verify`, re-serialises it and requires a byte-for-byte match.
"""

from __future__ import annotations

import hashlib
import struct
import sys
from dataclasses import dataclass, field
from pathlib import Path

SIS_UID = 0x10201A7A
DATA_TARGET_PREFIX = "!:\\data\\"
APP_PRIVATE_TARGET_PREFIX = "!:\\private\\e000a020\\"
E32_FLAGS = 0x12000028
E32_CRC_INITIALIZER = 0xC90FDAA2
MAX_PAYLOAD = 16 * 1024 * 1024
MAX_PACKAGE = MAX_PAYLOAD + 64 * 1024
E32_HEADER_SIZE = 156
EXECUTABLE_PREFIX = "!:\\sys\\bin\\"


class SisError(Exception):
    """Raised when a package or image falls outside the supported profile."""


# --------------------------------------------------------------------------
# Primitive encoders (mirror sis.cc Word/WideWord/Half/Field/Array/...).
# --------------------------------------------------------------------------


def word(value: int) -> bytes:
    return struct.pack("<I", value & 0xFFFFFFFF)


def wide_word(value: int) -> bytes:
    return struct.pack("<Q", value)


def half(value: int) -> bytes:
    return struct.pack("<H", value & 0xFFFF)


def sis_field(field_type: int, payload: bytes, in_array: bool = False) -> bytes:
    data = b"" if in_array else word(field_type)
    data += word(len(payload)) + payload
    data += b"\0" * ((4 - len(payload) % 4) % 4)
    return data


def array(field_type: int, elements: bytes = b"") -> bytes:
    return sis_field(2, word(field_type) + elements)


def utf16(value: str) -> bytes:
    return b"".join(char.encode("ascii") + b"\0" for char in value)


def string(value: str) -> bytes:
    return sis_field(1, utf16(value))


def strings(value: str) -> bytes:
    return array(1, sis_field(1, utf16(value), True))


def compressed(data: bytes) -> bytes:
    return sis_field(3, word(0) + wide_word(len(data)) + data)


def sha1(data: bytes) -> bytes:
    return hashlib.sha1(data).digest()


# --------------------------------------------------------------------------
# Checksums (mirror analysis/checksum.h and e32.cc HeaderCrc).
# --------------------------------------------------------------------------


def crc16(data: bytes) -> int:
    crc = 0
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            crc = ((crc << 1) ^ (0x1021 if crc & 0x8000 else 0)) & 0xFFFF
    return crc


def uid_checksum(data: bytes) -> int:
    crc = [0, 0]
    for index in range(12):
        slot = index % 2
        crc[slot] ^= data[index] << 8
        for _ in range(8):
            crc[slot] = ((crc[slot] << 1) ^ (0x1021 if crc[slot] & 0x8000 else 0)) & 0xFFFF
    return (crc[1] << 16) | crc[0]


def e32_header_crc(data: bytes, header_size: int) -> int:
    """E32 header CRC: reflected 0xedb88320, initial zero, no complement."""
    crc = 0
    for index in range(header_size):
        byte = (
            (E32_CRC_INITIALIZER >> ((index - 20) * 8)) & 0xFF
            if 20 <= index < 24
            else data[index]
        )
        crc ^= byte
        for _ in range(8):
            crc = (crc >> 1) ^ (0xEDB88320 if crc & 1 else 0)
    return crc & 0xFFFFFFFF


# --------------------------------------------------------------------------
# E32 profile checks (mirror the parts of e32::InspectImage BuildFiles uses).
# --------------------------------------------------------------------------


@dataclass
class E32Info:
    uid3: int
    capabilities: int
    heap_min: int
    heap_max: int
    stack_min: int
    stack_max: int
    header_crc: int
    header_size: int
    code_size: int


def inspect_e32(data: bytes, allow_2m_heap: bool = True) -> E32Info:
    if len(data) < E32_HEADER_SIZE:
        raise SisError("truncated E32 image")

    def read32(offset: int) -> int:
        return struct.unpack_from("<I", data, offset)[0]

    def read16(offset: int) -> int:
        return struct.unpack_from("<H", data, offset)[0]

    flags = read32(44)
    if flags != E32_FLAGS:
        raise SisError(f"unsupported E32 flags 0x{flags:08x}")
    header_size = read32(100)
    description_size = read16(152)
    if (
        read32(28) != 0
        or read16(122) not in (0x2001, 0x2002)
        or data[154] > 1
        or description_size != 0
        or data[154] != 0
    ):
        raise SisError("unsupported E32 image profile")
    if header_size != ((155 + description_size + 3) & ~3) or header_size > 8348:
        raise SisError("invalid E32 extended header size")
    if any(data[index] for index in range(155 + description_size, header_size)):
        raise SisError("nonzero E32 header padding")
    if (
        read32(0) != 0x1000007A
        or read32(4) != 0
        or read32(12) != uid_checksum(data)
        or read32(20) != e32_header_crc(data, header_size)
    ):
        raise SisError("invalid E32 identity/header checksum")
    for offset in (132, 140, 148):
        if read32(offset) != 0:
            raise SisError("unsupported E32 security header")
    if read32(136) & ~(1 << 13):
        raise SisError("unsupported E32 capability")
    heap_min, heap_max = read32(56), read32(60)
    stack_min, stack_max = read32(64), read32(68)
    if heap_min == 0 or heap_min > heap_max:
        raise SisError("invalid E32 heap bounds")
    allowed = (0x100000, 0x200000) if allow_2m_heap else (0x100000,)
    if heap_max not in allowed:
        raise SisError(f"E32 maximum heap 0x{heap_max:x} is outside the scoped profile")
    if stack_min == 0 or stack_min > stack_max:
        raise SisError("invalid E32 stack bounds")
    code_size = read32(108)
    if code_size > len(data):
        raise SisError("E32 code segment exceeds the image")
    return E32Info(
        uid3=read32(8),
        capabilities=read32(136),
        heap_min=heap_min,
        heap_max=heap_max,
        stack_min=stack_min,
        stack_max=stack_max,
        header_crc=read32(20),
        header_size=header_size,
        code_size=code_size,
    )


def patch_e32_heap(data: bytes, heap_max: int = 0x200000) -> bytes:
    """Set the E32 maximum heap (offset 60) and refresh the header CRC."""
    image = bytearray(data)
    header_size = struct.unpack_from("<I", image, 100)[0]
    old_crc = struct.unpack_from("<I", image, 20)[0]
    old_heap = struct.unpack_from("<I", image, 60)[0]
    struct.pack_into("<I", image, 60, heap_max)
    struct.pack_into("<I", image, 20, e32_header_crc(bytes(image), header_size))
    patched = bytes(image)
    changed = {index for index in range(len(patched)) if patched[index] != data[index]}
    if any(not (20 <= index < 24 or 60 <= index < 64) for index in changed):
        raise SisError(f"heap patch touched unexpected offsets: {sorted(changed)}")
    info = inspect_e32(patched, allow_2m_heap=True)
    if info.heap_max != heap_max:
        raise SisError("heap patch did not take effect")
    print(
        f"  heap 0x{old_heap:x} -> 0x{info.heap_max:x}; "
        f"crc 0x{old_crc:08x} -> 0x{info.header_crc:08x}; "
        f"touched bytes {sorted(changed)}"
    )
    return patched


# --------------------------------------------------------------------------
# Package serialisation (mirror BuildFiles).
# --------------------------------------------------------------------------


@dataclass
class PackageOptions:
    uid: int
    name: str
    vendor: str
    executable_name: str
    version: tuple[int, int, int]


@dataclass
class ApplicationFile:
    target: str
    data: bytes


def _check_data_targets(files: list[ApplicationFile]) -> None:
    previous = ""
    for data_file in files:
        target = data_file.target
        if target.startswith(DATA_TARGET_PREFIX):
            relative = target[len(DATA_TARGET_PREFIX):]
            if "\\" in relative:
                raise SisError("!:\\data\\ files must use a single-level filename")
        elif target.startswith(APP_PRIVATE_TARGET_PREFIX):
            relative = target[len(APP_PRIVATE_TARGET_PREFIX):]
        else:
            raise SisError(
                "Additional files must target !:\\data\\ or the app-private directory"
            )
        if not relative or len(target) > 192 or ".." in relative or "/" in relative:
            raise SisError("Additional file target must be a bounded safe relative path")
        segment = ""
        for char in relative:
            if char == "\\":
                if segment in ("", ".", ".."):
                    raise SisError("unsafe path component")
                segment = ""
                continue
            if not (char.isalnum() or char in "_-."):
                raise SisError(f"unsupported character {char!r} in additional file path")
            segment += char
        if segment in ("", ".", ".."):
            raise SisError("empty or unsafe final component")
        key = relative.casefold()
        if previous and key <= previous:
            raise SisError("Additional file targets must be unique and sorted")
        previous = key


def build_files(
    executable: bytes,
    assets: list[ApplicationFile],
    data_files: list[ApplicationFile],
    options: PackageOptions,
    libraries: list[ApplicationFile] | None = None,
    allow_2m_heap: bool = True,
) -> bytes:
    libraries = libraries or []
    if not 0xE0000000 <= options.uid <= 0xEFFFFFFF:
        raise SisError("SIS application package requires an unprotected experimental UID")
    for text in (options.name, options.vendor):
        if any(not 32 <= ord(char) <= 126 for char in text):
            raise SisError("Non-ASCII SIS strings are unsupported")
    if len(executable) > MAX_PAYLOAD:
        raise SisError("SIS payload exceeds 16 MiB")
    image = inspect_e32(executable, allow_2m_heap=allow_2m_heap)
    _check_data_targets(data_files)

    files: list[tuple[str, bytes]] = [
        (EXECUTABLE_PREFIX + options.executable_name, executable)
    ]
    files += [(asset.target, asset.data) for asset in assets]
    files += [(data_file.target, data_file.data) for data_file in data_files]
    if sum(len(payload) for _, payload in files) > MAX_PAYLOAD:
        raise SisError("SIS payload exceeds 16 MiB")

    version_bytes = b"".join(word(part) for part in options.version)
    datetime_field = sis_field(8, sis_field(6, half(2004) + b"\0\1") + sis_field(7, b"\0\0\0"))
    info = sis_field(
        14,
        sis_field(9, word(options.uid))
        + string(options.vendor)
        + strings(options.name)
        + strings(options.vendor)
        + sis_field(4, version_bytes)
        + datetime_field
        + b"\0\0",
    )

    descriptions = b""
    payloads = b""
    for index, (target, payload) in enumerate(files):
        digest = sha1(payload)
        capabilities = (
            sis_field(41, word(image.capabilities)) if index == 0 and image.capabilities else b""
        )
        entry = (
            string(target)
            + string("")
            + capabilities
            + sis_field(25, word(1) + sis_field(37, digest))
            + word(1)
            + word(0)
            + wide_word(len(payload))
            + wide_word(len(payload))
            + word(index)
        )
        descriptions += sis_field(24, entry, True)
        payloads += sis_field(32, compressed(payload), True)

    block = sis_field(28, array(24, descriptions) + array(13) + array(26))
    controller = sis_field(
        13,
        info
        + sis_field(16, array(33))
        + sis_field(15, array(11, sis_field(11, word(1), True)))
        + sis_field(17, array(18) + array(18))
        + sis_field(19, array(20))
        + block
        + sis_field(40, word(0)),
    )
    controller_stream = compressed(controller)
    data_stream = sis_field(30, array(31, sis_field(31, array(32, payloads), True)))
    header = word(SIS_UID) + word(0) + word(options.uid) + word(0)
    header = header[:12] + word(uid_checksum(header)) + b""
    package = (
        header
        + sis_field(
            12,
            sis_field(34, half(crc16(controller_stream)))
            + sis_field(35, half(crc16(data_stream)))
            + controller_stream
            + data_stream,
        )
    )
    if len(package) > MAX_PACKAGE:
        raise SisError("SIS package exceeds size limit")
    return package


# --------------------------------------------------------------------------
# Reader (mirror Reader / Single / Elements / Ascii).
# --------------------------------------------------------------------------


class Reader:
    def __init__(self, data: bytes) -> None:
        self.data = data
        self.position = 0

    def done(self) -> bool:
        return self.position == len(self.data)

    def word_value(self) -> int:
        """Read one raw 32-bit word (C++ Reader::WordValue)."""
        if self.position + 4 > len(self.data):
            raise SisError("Truncated SIS integer")
        value = struct.unpack_from("<I", self.data, self.position)[0]
        self.position += 4
        return value

    def take(self, expected: int | None = None, in_array: bool = False) -> bytes:
        """Return the field payload; `take_raw` returns it with its header."""
        payload, _ = self.take_raw(expected, in_array)
        return payload

    def take_raw(
        self, expected: int | None = None, in_array: bool = False
    ) -> tuple[bytes, bytes]:
        start = self.position
        if not in_array:
            found = struct.unpack_from("<I", self.data, self.position)[0]
            if expected is not None and found != expected:
                raise SisError(f"expected SIS field {expected}, found {found}")
            self.position += 4
        length = struct.unpack_from("<I", self.data, self.position)[0]
        if length & 0x80000000:
            raise SisError("Large SIS fields are unsupported")
        payload = self.data[self.position + 4: self.position + 4 + length]
        if len(payload) != length:
            raise SisError("Truncated SIS field")
        self.position += 4 + ((length + 3) & ~3)
        if self.position > len(self.data):
            raise SisError("Truncated SIS field padding")
        return payload, self.data[start: self.position]

    def finish(self) -> None:
        if self.position != len(self.data):
            raise SisError("unexpected trailing SIS field data")


def single(data: bytes, expected: int) -> bytes:
    reader = Reader(data)
    if reader.word_value() != expected:
        raise SisError("Unsupported SIS array element type")
    element = reader.take(expected, in_array=True)
    reader.finish()
    return element


def elements(data: bytes, expected: int, maximum: int = 41) -> list[bytes]:
    reader = Reader(data)
    if reader.word_value() != expected:
        raise SisError("Unsupported SIS array element type")
    items: list[bytes] = []
    while not reader.done():
        if len(items) == maximum:
            raise SisError("Too many SIS array elements")
        items.append(reader.take(expected, in_array=True))
    reader.finish()
    return items


def ascii_text(data: bytes) -> str:
    if len(data) % 2:
        raise SisError("Odd SIS UTF-16 string length")
    characters = []
    for index in range(0, len(data), 2):
        low, high = data[index], data[index + 1]
        if high != 0 or not 32 <= low <= 126:
            raise SisError("Non-ASCII SIS strings are unsupported")
        characters.append(chr(low))
    return "".join(characters)


def uncompressed(data: bytes) -> bytes:
    if len(data) < 12:
        raise SisError("Truncated SIS compressed field")
    if struct.unpack_from("<I", data, 0)[0] != 0:
        raise SisError("Compressed SIS streams are unsupported")
    if struct.unpack_from("<I", data, 8)[0] != 0:
        raise SisError("SIS uncompressed length mismatch")
    if struct.unpack_from("<I", data, 4)[0] != len(data) - 12:
        raise SisError("SIS uncompressed length mismatch")
    return data[12:]


# --------------------------------------------------------------------------
# Package inspection (mirror InspectPackage, unsigned canonical path).
# --------------------------------------------------------------------------


@dataclass
class PackageInfo:
    options: PackageOptions
    targets: list[str]
    payloads: list[bytes] = field(default_factory=list)

    @property
    def executable(self) -> bytes:
        return self.payloads[0]


def parse_package(data: bytes) -> PackageInfo:
    """Parse a canonical unsigned package without re-serialising it."""
    if len(data) < 16 or struct.unpack_from("<I", data, 0)[0] != SIS_UID:
        raise SisError("Requires the supported SISX UID profile")
    if struct.unpack_from("<I", data, 4)[0] != 0:
        raise SisError("Requires the supported SISX UID profile")
    if struct.unpack_from("<I", data, 12)[0] != uid_checksum(data):
        raise SisError("SIS UID checksum mismatch")

    root = Reader(data[16:])
    contents = root.take(12)
    root.finish()
    content_reader = Reader(contents)
    controller_crc = content_reader.take(34)
    data_crc = content_reader.take(35)
    controller_stream_field, controller_raw = content_reader.take_raw(3)
    data_stream_field, data_raw = content_reader.take_raw(30)
    content_reader.finish()
    if crc16(controller_raw) != struct.unpack("<H", controller_crc)[0]:
        raise SisError("SIS CRC16 mismatch")
    if crc16(data_raw) != struct.unpack("<H", data_crc)[0]:
        raise SisError("SIS CRC16 mismatch")
    controller_bytes = uncompressed(controller_stream_field)

    controller_root = Reader(controller_bytes)
    controller = controller_root.take(13)
    controller_root.finish()

    controller_reader = Reader(controller)
    info = controller_reader.take(14)
    info_reader = Reader(info)
    package_uid = struct.unpack_from("<I", info_reader.take(9), 0)[0]
    vendor_text = ascii_text(info_reader.take(1))
    name_element = single(info_reader.take(2), 1)
    name_text = ascii_text(name_element)
    localized_vendor = single(info_reader.take(2), 1)
    ascii_text(localized_vendor)
    version_payload = info_reader.take(4)
    version_reader = Reader(version_payload)
    parts = [version_reader.word_value() for _ in range(3)]
    version_reader.finish()
    version = (parts[0], parts[1], parts[2])
    datetime_reader = Reader(info_reader.take(8))
    date_payload = datetime_reader.take(6)
    if len(date_payload) != 4:
        raise SisError("SIS datetime date must be one word")
    datetime_reader.take(7)
    datetime_reader.finish()
    # The canonical info block ends with two literal zero bytes (no type word),
    # which the C++ inspector leaves unread; require them to be padding only.
    if info_reader.data[info_reader.position:] != b"\0\0":
        raise SisError("unexpected trailing bytes in the SIS info block")
    controller_reader.take(16)
    controller_reader.take(15)
    controller_reader.take(17)
    controller_reader.take(19)
    block = controller_reader.take(28)
    data_index = controller_reader.take(40)
    if len(data_index) != 4:
        raise SisError("SIS data index must be one word")
    controller_reader.finish()
    block_reader = Reader(block)
    descriptions = elements(block_reader.take(2), 24, 41)
    block_reader.take(2)  # Array(13)
    block_reader.take(2)  # Array(26)
    block_reader.finish()

    targets: list[str] = []
    expected_digests: list[bytes] = []
    for description in descriptions:
        entry = Reader(description)
        targets.append(ascii_text(entry.take(1)))
        entry.take(1)
        if struct.unpack_from("<I", entry.data, entry.position)[0] == 41:
            entry.take(41)
        hash_reader = Reader(entry.take(25))
        if hash_reader.word_value() != 1:
            raise SisError("Unsupported SIS hash algorithm")
        expected_digests.append(hash_reader.take(37))
        hash_reader.finish()
        for _ in range(2):  # Word(1) then Word(0)
            entry.word_value()
        for _ in range(4):  # two wide words: packed size and installed size
            entry.word_value()
        entry.word_value()  # file index
        entry.finish()
    if not targets[0].startswith(EXECUTABLE_PREFIX):
        raise SisError("Unsupported SIS install destination")

    data_reader = Reader(data_stream_field)
    unit = single(data_reader.take(2), 31)
    data_reader.finish()
    unit_reader = Reader(unit)
    file_data = elements(unit_reader.take(2), 32, 41)
    unit_reader.finish()
    if len(file_data) != len(targets):
        raise SisError("SIS file description/data count mismatch")
    payloads: list[bytes] = []
    for index, element in enumerate(file_data):
        payload_reader = Reader(element)
        payload = uncompressed(payload_reader.take(3))
        payload_reader.finish()
        if sha1(payload) != expected_digests[index]:
            raise SisError(f"SIS file SHA-1 mismatch for {targets[index]}")
        payloads.append(payload)

    return PackageInfo(
        options=PackageOptions(
            uid=package_uid,
            name=name_text,
            vendor=vendor_text,
            executable_name=targets[0][len(EXECUTABLE_PREFIX):],
            version=version,
        ),
        targets=targets,
        payloads=payloads,
    )


def inspect_package(data: bytes) -> PackageInfo:
    """Parse, then require the canonical re-serialisation to match byte-for-byte."""
    info = parse_package(data)
    assets = [
        ApplicationFile(target, payload)
        for target, payload in zip(info.targets[1:], info.payloads[1:])
    ]
    data_files = [
        asset
        for asset in assets
        if asset.target.startswith(DATA_TARGET_PREFIX)
        or asset.target.startswith(APP_PRIVATE_TARGET_PREFIX)
    ]
    assets = [asset for asset in assets if asset not in data_files]
    canonical = build_files(
        info.executable, assets, data_files, info.options, allow_2m_heap=True
    )
    if canonical != data:
        raise SisError(
            "SIS fields are outside the canonical unsigned application package profile"
        )
    return info


def main(argv: list[str]) -> int:
    verify = "--verify" in argv
    paths = [argument for argument in argv[1:] if not argument.startswith("--")]
    if len(paths) != 1:
        raise SystemExit("usage: sis33_python_writer.py [--verify] <package.sis>")
    package = Path(paths[0]).read_bytes()
    info = inspect_package(package) if verify else parse_package(package)
    print(f"package uid 0x{info.options.uid:08x} {info.options.name!r} "
          f"v{'.'.join(str(part) for part in info.options.version)} "
          f"({info.options.vendor})")
    for target, payload in zip(info.targets, info.payloads):
        print(f"{len(payload):>9}  {hashlib.sha1(payload).hexdigest()}  {target}")
    if verify:
        print("canonical re-serialisation matches byte-for-byte")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
