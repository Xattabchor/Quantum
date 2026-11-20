# Build report: v1.0.31 MDA import-only control on the known-good DRT baseline

## Purpose and test boundary

v1.0.31 starts from the v1.0.28 launch configuration and adds only the static
`mediaclientaudio.dll` import block. Its purpose is to test whether adding that
MDA import surface changes E65 launch behavior without enabling gameplay audio,
MIDI, or media playback.

The MDA references are retained in a Symbian-only function behind the default
`EFalse` runtime gate `quantumMdaImportProbeEnabled`. The ordinary startup path
only checks that gate: it does not create `CMdaAudioPlayerUtility`, open a file,
call `Play()`, or use an assumed audio/weapon path. The gate is not written by
the probe. Audio payloads remain unchanged from v1.0.28 solely as package
consistency controls.

This is a **static import/launch control**, not a sound, codec, or playback test.
A successful host build or E32 audit cannot show that the E65 has or accepts
these imports.

## Candidate and download

- Package version: **1.0.31**; package UID `0xE000A021`; executable UID3/SID
  `0xE000A020`.
- SIS: `quantum_level41_walk_test-v1.0.31-level1-import-drt-ten-mda-only-omap1710-dsa-argb32-armv5t.sis`.
- SIS size: **740,924 bytes**; SHA-256 `062dda9fe06d19d3f2311b66ec272627b6c9ca4c0fb4f67a82ccc22433818a4b`; MD5
  `bd1274fefef3e8f10e68a3bd8023c6a3`.
- Unsigned SIS, built with GnuPoc MakeSIS 4.0.
- [Download from Google Drive](https://drive.google.com/file/d/1bc9NUYZ73_TQke7UljWBdqAkg_M19pOv/view?usp=drivesdk) (file ID `1bc9NUYZ73_TQke7UljWBdqAkg_M19pOv`); Drive MD5 matches the local SIS.
- Nokia E65 result: **user reports v1.0.31 works**.

## Verified E32 imports

The final E32 imports exactly v1.0.28's other DLL/ordinal blocks, plus
`mediaclientaudio.dll` ordinals **15, 35, 36, and 42**. In the SDK import proxy
these map to `CMdaAudioPlayerUtility::SetPosition`, `NewL`, `Close`, and
`OpenFileL`, respectively. The E32 contains no `midiclient.dll` import and no
DRT ordinals 222/223. The `drtaeabi.dll` block remains exactly the known-good
ten imports `135, 139, 140, 141, 142, 147, 148, 155, 157, 221`.

All other E32 import blocks—including EUser—match v1.0.28; EUser also matches
the v1.0.22 baseline. The DRT ten-export proxy SHA-256 remains
`1da4d1fa9ce115111f4b325a13d8267f5b101cf004b922a8c062ce88a0dbfca0`. Full ordinal data and SDK symbol mapping are in
`e32-import-audit.json`.

## Package and payload validation

- ARMv5T/EKA2, OMAP1710, EColor64K, DSA ARGB32, interactive Level 1 walk
  target; heap **4096..2,097,152 bytes**.
- Gameplay audio, MIDI, native-leave/personality support, scalar PDiv ASM, and
  Mode-9 MUL-order experiments are disabled.
- The E32 is **190,564 bytes**, SHA-256 `c82ee4ea5d4b9bdf3dbf8c445b776d3355f4d59b02c45242e5027c4cacf5d57f`; its DRT
  imports are unchanged from v1.0.28.
- SIS audit passed all **19 payloads**, UID/checksum/header checks, application
  registration, package target ordering, embedded hashes, and staged-byte
  comparisons.
- All **18 non-E32 payloads** match v1.0.28 byte-for-byte, including the
  existing three audio payloads.
- The EUser import set is unchanged from v1.0.28 and v1.0.22. The only
  additional imported DLL is `mediaclientaudio.dll` with the four ordinals
  above.

Build configuration: Symbian SDK 0.3.0; Clang 23.1.2; CMake 3.30.5; legacy
ARMv5T/EKA2 runtime; ten-ordinal DRT proxy on; MDA import probe on; gameplay
audio/MIDI/native leaves off. Core host tests were not rerun for this import-only
link/package variant; the v1.0.28 core/payload baseline was previously tested.

## Device interpretation

The user reports that v1.0.31 works on the Nokia E65. Since the only DLL
import delta from the user-reported-working v1.0.28 baseline is the four-entry
MDA block, this run did not reproduce the popup with these imports in this
configuration. That weighs against the four static MDA imports alone causing
launch failure here, but does not explain v1.0.24's different native-leave/DRT
combination. Gameplay audio and MIDI remain disabled; MDA utility initialization,
file opening, codec support, DSP access, and playback were not tested.

Build evidence: `cmake-configure.log`, `e32-build.log`, `makesis.log`,
`mda-import-probe.cpp`, `mda-import-probe-source.patch`, `e32-import-audit.json`,
`sis-audit.json`, and `payload-manifest.json`.
