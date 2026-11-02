# Build report: v1.0.28 ten-ordinal static DRT-import control

## Purpose

v1.0.27 established the E65 lookup result `DLL LOAD ERROR: 0`, `ORDINALS FOUND: 10/12`, mask `0x3FF`. The ten non-null lookups were ordinals 135, 139, 140, 141, 142, 147, 148, 155, 157, and 221; ordinals 222 (`__aeabi_idiv`) and 223 (`__aeabi_uidiv`) returned null on that run.

This v1.0.28 candidate tests the **static-import** path with only those ten previously resolved DRT ordinals. It retains the OMAP1710 DSA ARGB32 Level 1 renderer and package assets. Gameplay audio and MIDI remain disabled; audio files are retained only for payload consistency. It does not perform `RLibrary::Load`/`Lookup`, and no probe code explicitly calls a DRT helper.

## Candidate

- Package version: **1.0.28**; package UID `0xE000A021`; executable UID3/SID `0xE000A020`.
- SIS: `quantum_level41_walk_test-v1.0.28-level1-import-drt-ten-found-omap1710-dsa-argb32-armv5t.sis`.
- SIS size: **740,660 bytes**; SHA-256 `7cc5206d07cffc1768dcd52c7988410f08fcbef7a234cbd60a1b48880e1025bb`; MD5 `232b9054434eae764627761961be260c`.
- Unsigned SIS, built with local GnuPoc MakeSIS 4.0.
- [Download from Google Drive](https://drive.google.com/file/d/1mXfVd9lVIz8m7TzkFjRpqvhZYTjkB9U3/view?usp=drivesdk) (file ID `1mXfVd9lVIz8m7TzkFjRpqvhZYTjkB9U3`).
- Nokia E65 result: **pending**; no v1.0.28 device result has been reported.

## Static DRT import set

The E32 import audit verified that `drtaeabi.dll` contains **exactly** these ten imports:

`135 __aeabi_idivmod`, `139 __aeabi_ldivmod`, `140 __aeabi_llsl`, `141 __aeabi_llsr`, `142 __aeabi_lmul`, `147 __aeabi_memcpy4`, `148 __aeabi_memcpy8`, `155 __aeabi_uidivmod`, `157 __aeabi_uldivmod`, and `221 abort`.

Ordinals **222 and 223 are absent** from both the generated import proxy and final E32 import table. The proxy was generated from `symbian9/level41_device_test/drtaeabi_ten_ordinals.def` using the SDK `symbian-native proxy-sources` command, then assembled/linked with the SDK Clang and `ld.lld`. The audited proxy is `drtaeabi-ten-import-proxy.dso` (1,640 bytes; SHA-256 `1da4d1fa9ce115111f4b325a13d8267f5b101cf004b922a8c062ce88a0dbfca0`); its versioned target remains `drtaeabi.dll`.

To keep ordinary compiler division references linkable without importing the two E65-missing DRT ordinals, `__aeabi_idiv` and `__aeabi_uidiv` resolve to local implementations from the SDK's `libsymbian_guest_runtime_legacy_eka2.a` (`divsi3.S.obj` and `udivsi3.S.obj`). These are **not** static imports from `drtaeabi.dll`. This implementation substitution is part of the controlled build and is recorded for interpretation; it is not a call through a dynamically resolved DRT pointer.

Other E32 details: 189,852 bytes; ARMv5T/EKA2; UID3/SID `0xE000A020`; capabilities 0; heap 4,096..2,097,152 bytes; header CRC valid. The DLL set is `bitgdi.dll`, `drtaeabi.dll`, `efsrv.dll`, `estlib.dll`, `euser.dll`, `fbscli.dll`, `gdi.dll`, `libm.dll`, and `ws32.dll`. The EUser import set matches the working v1.0.22 baseline. There is no `mediaclientaudio.dll` or MIDI-client import.

The E32 inspector rejects the shipped 2 MiB heap limit, so import inspection used a disposable copy capped at 1 MiB; the packaged E32 retains its 2 MiB heap maximum. Exact import and proxy metadata are in `e32-import-audit.json` and `payload-manifest.json`.

## Build and package verification

- Restored Symbian SDK 0.3.0; ARMv5T/EKA2, OMAP1710, EColor64K, DSA ARGB32, interactive Level 1 target.
- `QUANTUM_LEVEL41_IMPORT_PROBE_DRT_TEN_ORDINALS=ON`; full-proxy and dynamic-lookup probes off; native-leave/personality and MDA probes off; gameplay audio and MIDI off.
- Scalar PDiv ASM, Mode-9 MUL-order, and effects ASM experiments remain off.
- Package preparation set the E32 process heap to 4,096..2,097,152 bytes and recomputed the header CRC.
- SIS audit passed all **19** payloads: UID CRC, controller/data checksums, package target order, application registration, embedded hashes, and staged-byte comparisons.
- All **18 non-E32 payloads** match v1.0.27 byte-for-byte, including the Level 1 world/weapon assets and the three retained audio files.
- `PATH=/home/user/quantum-sdk-cache/pyenv/bin:$PATH make -C symbian9 test` passed: both core suites (149,093 checks each), Python test suites (10 passed; 8 passed with 1 skipped), and asset, heap, SID, key-up, and presentation checks.
- Build evidence: `cmake-configure.log`, `e32-build.log`, `makesis.log`, `e32-import-audit.json`, `sis-audit.json`, and `payload-manifest.json`.

## Nokia E65 test and interpretation

Device test is pending. If v1.0.28 launches, that would be **evidence favoring** the two excluded ordinals as a factor in v1.0.25, but not proof: this candidate also resolves those division helpers locally and uses a custom ten-export proxy. If it still shows **“Feature not supported,”** that would suggest a broader static-import issue or another difference; it would not identify a particular offending import.

No playback or MIDI path is enabled. This build and its host-side audits make no claim about codec availability, direct DSP access, audio output, or general E65 compatibility.