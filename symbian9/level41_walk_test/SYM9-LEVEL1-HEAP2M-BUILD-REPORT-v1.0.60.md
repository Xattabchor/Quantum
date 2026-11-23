# v1.0.60 — Level 41 walk build with a 2 MiB maximum heap

v1.0.59 is unchanged except for the embedded `E32` maximum-heap field. Every
payload (27 files: the executable, two resources, 13 QTX2 textures, three
object assets, six audio/config files) is byte-identical to v1.0.59.

## Heap patch

| Field | E32 offset | v1.0.59 | v1.0.60 |
| --- | --- | --- | --- |
| Minimum heap | 56 | 4,096 | 4,096 |
| **Maximum heap** | **60** | **1,048,576** | **2,097,152** |
| Header size | 52 | 432 | 432 |
| Stack minimum | 64 | 65,536 | 65,536 |
| Header CRC | 20 | `0x28a02261` | `0xece4b30f` |

Offsets 52/56/64 are intentionally untouched. `header CRC` is recomputed over
the 156-byte header with the Symbian reflected `0xedb88320` polynomial, initial
zero, no final complement, and the `0xc90fdaa2` initialiser substituted for the
CRC field itself — the same routine the pinned `e32.cc` uses.

The patched image contains exactly five changed bytes: offsets `20..23` (the
CRC) and `62` (the third byte of the heap word). Imports, UID3 `0xe000a020`,
capabilities and code are untouched, so the frozen import table still matches
v1.0.57/v1.0.58/v1.0.59.

The v1.0.28 reference image (`heap max 0x200000`, header CRC `0xdb7b20bd`) is
built the same way: recomputing its CRC over the shipped header reproduces the
stored value, which confirms the CRC-first patch order. Feeding the 1 MiB heap
back into that header yields `0x1f3fb1d3`, so v1.0.28 was never a 1 MiB image
whose CRC was left stale.

## Package

The candidate is serialised by `sis33_python_writer.py`, a byte-exact port of
the scoped stage-3 writer (`local_sis_writer/cpp/symbian/sis/sis.cc`) including
the `data_files` extension. The port was validated by round-tripping the shipped
v1.0.59 package: parsing it and re-serialising it from its own extracted
payloads reproduces all 1,203,236 bytes, so any difference in v1.0.60 is
attributable to the heap patch and the fields that cover it.

Compared with v1.0.59 the package differs in 30 bytes:

| Bytes | Meaning |
| --- | --- |
| `556..576` (20) | SHA-1 of the patched executable in its file descriptor |
| `5056..5060` (4) | E32 header CRC |
| `5098` (1) | E32 heap word, third byte `0x10` → `0x20` |
| `32`, `33` (2) | controller CRC16 (covers the descriptor) |
| `44`, `45` (2) | data CRC16 |
| `276` (1) | SIS version `1.0.59` → `1.0.60` |

## Verification performed

- `inspect_package()` on the produced bytes: parses, re-serialises, and
  requires a byte-for-byte match — passes.
- All 27 payload digests match v1.0.59 except the executable.
- Heap words read back as `432, 4096, 2097152, 65536`.
- Stored E32 header CRC equals the recomputed CRC.
- The stock SDK 0.3.0 `inspect_e32` accepts v1.0.59 and **rejects** v1.0.60,
  exactly as documented for the 2 MiB layout since v1.0.28. Acceptance rests on
  the scoped local-writer adaptation, not on stock SDK validation.
- The downloaded Drive copy is `cmp`-identical to the built file and its md5
  (`15a8168b787d3aab9270cfc33466daeb`) matches the Drive-reported checksum.

## Artifact

`quantum-level41-walk-v1.0.60-heap2m-unsigned.sis`

- size: 1,203,236 bytes (same as v1.0.59 — the patch is in place)
- md5: `15a8168b787d3aab9270cfc33466daeb`
- sha256: `3e36effd7e50e04a2bbd61d0328734e7dd57cca81e91d9e76d7d0471762ad79a`
- executable sha256: `8304f40f91372889fdbbbcd85e6a2360672b7123758d4ac864c135a96e134f63`
- Drive: `18DClDcKatZJFFn1AzXBH-1j6ccroMIBr`

## Note on the build path

The SDK toolchain archive (326 MiB, four parts in Drive) could not be fetched
this session: the sandbox has no TLS route to the archive hosts and the Drive
parts need the toolchain to be assembled. Because the change is confined to a
place-in field covered by a documented CRC, and because the writer's container
format is fully reproducible in Python (proved by the v1.0.59 round-trip), the
candidate was produced from the shipped v1.0.59 executable instead of a fresh
toolchain build. No source changed between v1.0.59 and v1.0.60.
