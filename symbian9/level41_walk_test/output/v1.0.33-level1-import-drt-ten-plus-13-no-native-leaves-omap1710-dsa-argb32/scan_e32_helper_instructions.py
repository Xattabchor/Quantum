#!/usr/bin/env python3
"""Decode the retained DRT-address probe from the shipped v1.0.33 E32 image.

Requires Capstone 5.x. This intentionally checks the final E32 bytes, not the
ELF or generated assembly. It scans only the actual Thumb instruction body;
the following 12 32-bit GOT_PREL words are literal-pool data, not instructions.
"""
from __future__ import annotations

import hashlib
import importlib.metadata
import json
import sys
from pathlib import Path

import capstone
from capstone import CS_ARCH_ARM, CS_MODE_LITTLE_ENDIAN, CS_MODE_THUMB, Cs

EXPECTED_SHA256 = "0d23b4b408167a83870f1c2d577dd9d51a5f1155b51f7a461ea056e7e72fb2b4"
EXPECTED_FILE_SIZE = 190_916
EXPECTED_CODE_SIZE = 188_248
E32_MAGIC_OFFSET = 16
E32_HEADER_SIZE_OFFSET = 100
E32_MIN_HEADER_SIZE = 156
CODE_BASE = 0x8000
PROBE_THUMB_SYMBOL = 0x29E45  # STT_FUNC value; low bit marks Thumb state.
PROBE_VMA = PROBE_THUMB_SYMBOL & ~1
PROBE_FUNCTION_SIZE = 176
PROBE_INSTRUCTION_BODY_SIZE = 128  # ends after BX LR and one alignment NOP
GOT_PREL_LITERAL_COUNT = 12


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {Path(sys.argv[0]).name} PATH_TO_SHIPPED_E32", file=sys.stderr)
        return 2

    image_path = Path(sys.argv[1])
    image = image_path.read_bytes()
    digest = hashlib.sha256(image).hexdigest()
    if digest != EXPECTED_SHA256:
        raise SystemExit(f"unexpected E32 SHA-256: {digest}")
    if len(image) != EXPECTED_FILE_SIZE:
        raise SystemExit(f"unexpected E32 size: {len(image)}")
    if image[E32_MAGIC_OFFSET : E32_MAGIC_OFFSET + 4] != b"EPOC":
        raise SystemExit("missing EPOC image signature")

    header_size = int.from_bytes(
        image[E32_HEADER_SIZE_OFFSET : E32_HEADER_SIZE_OFFSET + 4], "little"
    )
    if header_size < E32_MIN_HEADER_SIZE or header_size > len(image):
        raise SystemExit(f"invalid E32 header size: {header_size}")
    if header_size != E32_MIN_HEADER_SIZE:
        raise SystemExit(f"unexpected v1.0.33 header size: {header_size}")

    code_file_offset = header_size
    if code_file_offset + EXPECTED_CODE_SIZE > len(image):
        raise SystemExit("E32 code image is truncated")
    probe_code_offset = PROBE_VMA - CODE_BASE
    function_file_offset = code_file_offset + probe_code_offset
    instruction_bytes = image[
        function_file_offset : function_file_offset + PROBE_INSTRUCTION_BODY_SIZE
    ]
    literal_bytes = image[
        function_file_offset + PROBE_INSTRUCTION_BODY_SIZE :
        function_file_offset + PROBE_FUNCTION_SIZE
    ]
    if len(instruction_bytes) != PROBE_INSTRUCTION_BODY_SIZE:
        raise SystemExit("truncated probe instruction body")
    if len(literal_bytes) != GOT_PREL_LITERAL_COUNT * 4:
        raise SystemExit("unexpected GOT_PREL literal-pool length")

    decoder = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)
    instructions = list(decoder.disasm(instruction_bytes, PROBE_VMA))
    decoded_size = sum(instruction.size for instruction in instructions)
    if decoded_size != PROBE_INSTRUCTION_BODY_SIZE:
        raise SystemExit(
            f"Capstone decoded {decoded_size} of {PROBE_INSTRUCTION_BODY_SIZE} bytes"
        )

    calls = [
        instruction
        for instruction in instructions
        if instruction.mnemonic.lower() in {"bl", "blx"}
    ]
    if calls:
        rendered = ", ".join(
            f"0x{instruction.address:08x}:{instruction.mnemonic} {instruction.op_str}"
            for instruction in calls
        )
        raise SystemExit(f"helper-call instruction(s) found in probe: {rendered}")
    if not any(
        instruction.mnemonic.lower() == "bx"
        and instruction.op_str.lower() == "lr"
        for instruction in instructions
    ):
        raise SystemExit("probe body has no BX LR return")

    counts: dict[str, int] = {}
    for instruction in instructions:
        name = instruction.mnemonic.lower()
        counts[name] = counts.get(name, 0) + 1
    if counts.get("str", 0) != GOT_PREL_LITERAL_COUNT:
        raise SystemExit(f"unexpected probe STR count: {counts.get('str', 0)}")

    report = {
        "e32_file": str(image_path),
        "e32_size_bytes": len(image),
        "e32_sha256": digest,
        "header_size": header_size,
        "code_base": f"0x{CODE_BASE:08x}",
        "code_size_bytes": EXPECTED_CODE_SIZE,
        "probe_thumb_symbol": f"0x{PROBE_THUMB_SYMBOL:08x}",
        "probe_vma": f"0x{PROBE_VMA:08x}",
        "probe_file_offset": f"0x{function_file_offset:08x}",
        "probe_function_size_bytes": PROBE_FUNCTION_SIZE,
        "decoded_instruction_body_bytes": decoded_size,
        "decoded_instruction_count": len(instructions),
        "instruction_mnemonic_counts": counts,
        "bl_count": 0,
        "blx_count": 0,
        "return_instruction": "BX LR",
        "trailing_literal_data_bytes": len(literal_bytes),
        "trailing_got_prel_literal_count": GOT_PREL_LITERAL_COUNT,
        "decoder": (
            f"Capstone package {importlib.metadata.version('capstone')} "
            f"(engine {capstone.__version__}) / ARM Thumb little-endian"
        ),
        "instructions": [
            {
                "address": f"0x{instruction.address:08x}",
                "bytes": instruction.bytes.hex(),
                "mnemonic": instruction.mnemonic,
                "operands": instruction.op_str,
            }
            for instruction in instructions
        ],
        "scope_note": (
            "Decoded from the exact shipped E32 image. The 48 bytes after the "
            "instruction body are 12 GOT_PREL literal words, not executable "
            "instructions. The separate section-aware E32 audit reports zero "
            "call relocations to the 12 added DRT helper imports."
        ),
    }
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
