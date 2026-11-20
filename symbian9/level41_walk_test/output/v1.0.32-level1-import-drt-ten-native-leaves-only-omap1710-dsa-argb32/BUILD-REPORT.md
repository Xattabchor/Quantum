# Build report: v1.0.32 native-leave/personality launch-only control

## Purpose and test boundary

v1.0.32 starts from the user-reported-working v1.0.28 ten-ordinal DRT baseline
and adds the native-leave/personality support required by the selected control.
Because native-leave support necessarily contributes additional runtime imports,
this is an import/personality launch control—not a test of one DLL's availability
in isolation. It is separate from the v1.0.31 MDA import-only control.

The launch probe's `quantumNativeLeavesImportProbeEnabled` gate is initialized
to `EFalse` and is not written. Its `TRAPD`/`User::Leave(KErrNotSupported)`
branch remains compiled behind that gate; the normal startup path does not
intentionally raise a leave. This candidate tests launch with the static
native-leave/personality import surface present, not leave propagation.

MDA and MIDI imports, MDA API calls, gameplay audio code, and playback are off.
The three audio files remain in the package unchanged from v1.0.28 only as
payload-consistency controls; no audio path is enabled or called. Scalar PDiv
ASM, Mode-9 MUL-order, and effects-ASM experiments are off.

## Candidate

- Package version: **1.0.32**; package UID `0xE000A021`; executable UID3/SID
  `0xE000A020`.
- SIS: `quantum_level41_walk_test-v1.0.32-level1-import-drt-ten-native-leaves-only-omap1710-dsa-argb32-armv5t.sis`.
- SIS size: **742,756 bytes**; SHA-256 `2091b0d15b7ff0319d7dec22bbe855e0f164120cf281612a581476b74be30140`; MD5
  `9bc60ad4e816aeaab4ff77e5b4993db2`.
- Unsigned SIS, built with GnuPoc MakeSIS 4.0.
- [Download the unsigned SIS from Google Drive](https://drive.google.com/file/d/12Yt2aCwT8pKXfwCG715Veup1AbJLO4qO/view?usp=drivesdk) (file ID `12Yt2aCwT8pKXfwCG715Veup1AbJLO4qO`); Drive size and MD5 match the local package.
- Nokia E65 result: **user-reported: “Feature not supported”**.

## Verified E32 import delta

The final E32 retains all ten v1.0.28 DRT imports and adds exactly thirteen
native-leave/personality DRT ordinals:

`123, 158, 159, 160, 180, 181, 183, 184, 204, 213, 217, 218, 220`.

The combined custom `drtaeabi.dll` proxy exports exactly the 23 distinct DRT
ordinals in the E32. The table has **25 import-slot references** because
ordinals 158 and 159 each appear twice. The two division ordinals 222/223 remain
absent. The EUser delta is exactly ordinals **2060, 2061, and 2082**, resolving
in the SDK proxy to `User::MarkCleanupStack`, `User::UnMarkCleanupStack`, and
`XLeaveException::GetReason`. Every other E32 DLL/ordinal block matches
v1.0.28. No `mediaclientaudio.dll` or MIDI-client import is present.

The original CMake rule would attach both the ten-ordinal DRT proxy and the SDK
`Symbian::CxxAbi` proxy when native leaves were enabled, producing a duplicate
proxy identity during E32 conversion. For this temporary control build, the
imported `SymbianCxxAbi` target and the legacy EKA2 runtime proxy edge were
redirected to the same combined custom proxy. The E32 converter then completed
successfully with the audited 23-ordinal DRT surface; the exact replayable
CMake/definition delta is in `native-leaves-source.patch`.

## Package and payload validation

- ARMv5T/EKA2, OMAP1710, EColor64K, DSA ARGB32, interactive Level 1 walk;
  heap **4096..2,097,152 bytes**.
- E32 size **193,636 bytes**, SHA-256 `085b0cbcd7ec10dd90955933ee14f8831dcd875b1b9afbcd1433463b722e4338`; UID3/SID
  `0xE000A020`, capabilities 0, header CRC valid.
- The SIS audit passed all **19 payloads**, UID/header CRC, controller/data
  checksums, package target ordering, application registration, embedded
  hashes, and staged-byte comparisons.
- All **18 non-E32 payloads** match v1.0.28 byte-for-byte, including the three
  retained audio files.
- The combined proxy is `2,788 bytes`, SHA-256 `4592ef95cd252e1140f453d096cac73b75199e699883d55ad8dcf148893d2c0a`;
  its exports exactly match the E32's unique DRT import set.
- Build configuration: Symbian SDK 0.3.0, Clang 23.1.2, CMake 3.30.5,
  legacy ARMv5T/EKA2 runtime; native leaves on; MDA/MIDI/gameplay audio off.
  The v1.0.28 core/host test suite was not rerun for this import/personality
  link/package-only variant.

## Device interpretation

The user reports that v1.0.32 displays the exact “Feature not supported”
popup on the Nokia E65; this candidate did not launch in that run. Compared with
the user-reported-working v1.0.28 baseline, the candidate adds native-leave /
personality support and its associated DRT/EUser imports. The result strengthens
the association with that combined change set, but does not identify a specific
ordinal or firmware export as the cause. The leave probe remains false-gated, so
this does not show that a leave was raised or propagated. v1.0.31's separate
MDA-import-only run worked, but that does not resolve this native-leave result.
No MIDI, gameplay audio, playback, codec, DSP, or performance conclusion is in
scope.

Build evidence: `cmake-configure.log`, `e32-build.log`, `makesis.log`,
`native-leaves-import-probe.cpp`, `native-leaves-source.patch`,
`drtaeabi-ten-native-leaves-proxy.def`, `drtaeabi-ten-native-leaves-proxy.dso`,
`e32-import-audit.json`, `sis-audit.json`, and `payload-manifest.json`.
