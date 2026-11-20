# Build report: v1.0.29 — ordinal-222 launch-only control

## Purpose

This is the first of two separate controls following the v1.0.28 ten-ordinal DRT-import probe. It adds **only ordinal 222 (`__aeabi_idiv`)** to the ten previously resolved static imports. Ordinal 223 is not included. No dynamic lookup or explicit helper call is added.

## Package

- Version **1.0.29**; package UID `0xE000A021`; executable UID3/SID `0xE000A020`.
- SIS: `quantum_level41_walk_test-v1.0.29-level1-import-drt-idiv-222-omap1710-dsa-argb32-armv5t.sis`
- Size: **740,620 bytes**; SHA-256 `4d71a0890ab00ff0e9820d553c223954f1ba42ea4dbd1c9e63878eaf86f09978`; MD5 `0042da2ddda2c714c115065b2478eaa8`.
- [Download from Google Drive](https://drive.google.com/file/d/1CscxgVwC4UbsIPE1kdESASDBT4SuPi48/view?usp=drivesdk); Drive MD5 verified against local SIS.
- Unsigned. E32 image: 189,876 bytes; ARMv5T/EKA2; capabilities 0; heap 4,096..2,097,152; header CRC valid.
- Nokia E65 result: **user-reported popup: “Feature not supported”.** The report records the popup text; the E32/SIS audit does not diagnose the device loader.

## Exact import isolation

The final E32 and custom `drtaeabi.dll` import proxy were inspected before packaging. The E32 imports exactly:

`135, 139, 140, 141, 142, 147, 148, 155, 157, 221, 222`

The proxy maps ordinal 222 to `__aeabi_idiv` and exports exactly these eleven ordinals. Every other DLL import block matches v1.0.28; the EUser import set matches v1.0.22. No MDA/audio-client or MIDI-client imports are present.

Division references are isolated as intended: `__aeabi_idiv` stays unresolved in the linked ELF and is supplied by static DRT import ordinal 222; `__aeabi_uidiv` is defined locally by the filtered SDK legacy EKA2 runtime. The temporary runtime archive omits `divsi3.S.obj` but retains the unsigned-division implementation. The linked ELF symbol table confirms the imported/local split.

## Scope and package audit

The SIS audit passed all **19 payloads**: UID CRC, controller/data checksums, package target order, application registration, embedded payload hashes, and staged-byte comparisons. All **18 non-E32 payloads** match v1.0.28 byte-for-byte, including the Level 1 world/weapon resources and all three retained audio files. Gameplay audio and MIDI remain disabled; the audio files are retained only for payload consistency.

The E32 imports and custom proxy agree exactly. This verifies static package metadata and SIS integrity, not E65 ROM availability, loader acceptance, helper execution, gameplay, codec support, DSP access, audio playback, performance, or general compatibility. No performance claim is made.

## Build configuration

Symbian SDK 0.3.0; ARMv5T/EKA2; OMAP1710; EColor64K; DSA ARGB32; interactive Level 1 walk target. The ten-ordinal import option is on with a custom proxy adding only ordinal 222. Dynamic lookup, native-leave/personality, MDA, gameplay audio, and MIDI are off. Scalar PDiv ASM, Mode-9 MUL-order, and effects-ASM experiments remain off. The E32 heap maximum was set to 2 MiB before packaging and its header CRC recomputed.

C++ sources are unchanged from the v1.0.28 source snapshot; host tests were not rerun for this linker/package-only variant.

Build evidence: `cmake-configure.log`, `e32-build.log`, `makesis.log`, `drtaeabi-ordinal-222-import-proxy.dso`, `drtaeabi-ordinal-222-import-proxy.def`, `e32-import-audit.json`, `sis-audit.json`, and `payload-manifest.json`.
