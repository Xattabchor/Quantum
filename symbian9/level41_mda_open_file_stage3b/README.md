# MDA OpenFile dynamic-ordinal diagnostic — stage 3b

Stage 3b is a **separate** diagnostic after the stage-3 static-import candidate
reported `Menu: Feature not supported` and no app screen. It preserves the
verified NewL-only and OpenDes stage-2 probes and does not modify the stage-3
candidate.

The worker performs only this sequence:

1. `CMdaAudioPlayerUtility::NewL` (the one static MDA import retained is
   `mediaclientaudio.dll` ordinal 35);
2. `RLibrary::Load("mediaclientaudio.dll")`, then a runtime
   `RLibrary::Lookup(42)`;
3. if ordinal 42 is absent, show that result and **do not call OpenFileL**;
4. if present, call only the dynamically resolved `OpenFileL` entry point on
   `C:\\data\\mda_open_file_probe.wav`, then show the immediate leave,
   `MapcInitComplete` callback/duration, worker panic, or 15-second timeout.

The E32 import table contains no static ordinal 42. The candidate has no MIDI
imports, makes no `Play` request, and does not test audible output. The WAV in
the package is silent, mono, 8 kHz, 16-bit PCM, 100 ms long.

## Candidate download

- File: `quantum_level41_mda_open_file_stage3b-v1.0.0-e65-candidate.sisx`
- **Direct download:**
  <https://drive.google.com/uc?id=1JiaxS0tybOnZ8w36iMwv0DeaZsWA_lx6&export=download>
- Drive file ID: `1JiaxS0tybOnZ8w36iMwv0DeaZsWA_lx6` (uploaded beside the
  stage-2 candidate; the file is not public and requires the connected Drive
  account's access).
- Size: **51,152 bytes**
- SHA-256: `8e12ed10263d8708d27ede7d5e0281fd08bf8f36ead135a3e8de2f67ef0a836e`
- MD5: `d79b7e423943a660569acc5b8fe0e21a` (matches Drive)
- Package UID `0xE000A02F`; executable UID3 `0xE000A02E`; version `1.0.0`.
- The package contains the executable, registration/localization resources,
  and the WAV at `!:\\data\\mda_open_file_probe.wav`.
- The executable is ARMv5T/EKA2 with zero capabilities. Local E32 inspection
  found only MDA ordinal 35 statically imported; ordinal 42 is runtime-only.
  There are no MIDI imports.
- The self-signed certificate matches the previously E65-tested RSA-1024,
  SHA-1 profile, valid 2000-01-01 through 2045-12-31. The temporary private
  signing key was removed immediately after the signed package passed local
  inspection; it is not embedded in the SISX.

Install to phone memory (C:) so the package's `!:` WAV destination matches the
explicit `C:\\data\\...` path. Launch **MDA OpenFile 3B** from the app menu.

## E65 result — dynamic OpenFileL succeeded

**Normalized user-reported screen:** `MapcInitComplete: success`, `Duration: 100 ms`,
`Packaged 100 ms PCM WAV`, `C:\\data\\mda_open_file_probe.wav`, and
`No Play; MIDI / other audio paths OFF`.

This confirms that stage 3b launched, dynamically resolved ordinal 42, called
`OpenFileL` on the packaged WAV, and received a successful MDA initialization
callback with the expected 100 ms duration. The result verifies file-open/
initialization on this E65 only; it does **not** test playback or audible output.

### Interpreting the E65 screen

- `OpenFileL ordinal 42 not found` means `NewL` returned and the DLL loaded, but
  the export lookup failed; the file-open call was skipped.
- `MDA DLL load leave: ...` or `NewL leave code: ...` identifies an earlier
  failure, before ordinal 42 can be tested.
- `MapcInitComplete: success` means the dynamic call returned and MDA completed
  initialization for the packaged WAV. The displayed duration should be about
  100 ms.
- `OpenFileL leave code: ...` reports a synchronous leave from the dynamically
  resolved call. `OpenFileL callback timed out` means no initialization
  callback arrived within 15 seconds; a worker panic is reported separately.
- None of these results tests decoding during playback or speaker output.

The E65 screen report is now recorded above. This device result complements,
but is distinct from, the local import-table and SIS signature audit.

## Local build and audit

The detailed signed-package, file, resource, certificate, and E32-import report
is `quantum_level41_mda_open_file_stage3b-v1.0.0-e65-candidate-audit.json`;
the human-readable summary is `AUDIT.md`.

The executable was cross-built locally with Symbian SDK 0.3.0 and Clang 23.1.2
for ARMv5T/EKA2. In this host, the SDK-bundled host binaries require newer
GLIBC/GLIBCXX symbols than the system provides. The SDK Clang and
`symbian-native` converter were run with the system `libstdc++` preloaded; the
host CMake 3.31.6 and Ninja 1.11.1.1 wheels were used for orchestration. The
Drive host-helper archive was downloaded and extracted. For resource files,
`rcomp` was rebuilt from the pinned EPL-1.0 `SymbianRevive/symbian-build`
source and the SDK's `uidcrc` companion from the pinned `symbian-platform`
source, avoiding the incompatible prebuilt `rcomp` binary.

The local SIS writer uses the audited v0.3.0 `symbian-platform` SIS/E32 source
and pinned Abseil, compiled with host g++ 12.2. It re-parses the signed SISX,
checks its RSA/SHA-1 signature and registration, and reports the E32 imports
from the executable bytes itself. No GitHub Actions build was used.

### Rebuild commands (workspace/cache layout used for this candidate)

```sh
export PYTHONPATH="$HOME/quantum-sdk-cache/host-python"
export LD_PRELOAD=/usr/lib/x86_64-linux-gnu/libstdc++.so.6
SDK="$HOME/quantum-sdk-cache/symbian-sdk"
CMAKE="$HOME/quantum-sdk-cache/host-python/bin/cmake"
NINJA="$HOME/quantum-sdk-cache/host-python/bin/ninja"

"$CMAKE" --fresh \
  -S symbian9/level41_mda_open_file_stage3b \
  -B "$HOME/quantum-sdk-cache/stage3b-build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$SDK/cmake/symbian-arm.cmake" \
  -DSYMBIAN_SDK_PREFIX="$SDK" -DSYMBIAN_TARGET_ARCH=armv5t \
  -DSYMBIAN_RUNTIME_LEGACY_EKA2=ON \
  -DCMAKE_MAKE_PROGRAM="$NINJA" -DCMAKE_BUILD_TYPE=Release
"$CMAKE" --build "$HOME/quantum-sdk-cache/stage3b-build" \
  --target quantum_level41_mda_open_file_stage3b_e32 -j2
```

Build host `rcomp`/`uidcrc` from the pinned source checkouts with:

```sh
python3 symbian9/level41_mda_open_file_stage3b/build_host_rcomp.py
```

The local SIS helper's `build_local_sis_writer.py` accepts `--cmake` and
`--ninja` overrides when the SDK's bundled host versions cannot run. Package
with a **new temporary matching key/certificate** (the stage-3b key was
removed) using:

```sh
SDK="$HOME/quantum-sdk-cache/symbian-sdk"
python3 symbian9/level41_mda_open_file_stage3b/package_candidate.py \
  --sdk-root "$SDK" \
  --artifact "$HOME/quantum-sdk-cache/stage3b-build/e32/quantum_level41_mda_open_file_stage3b.exe" \
  --sis-writer "$HOME/quantum-sdk-cache/stage3-sis-writer/build/stage3_sis_writer" \
  --rcomp "$HOME/quantum-sdk-cache/host-tools/bin/rcomp" \
  --certificate /path/to/certificate.pem \
  --private-key /path/to/temporary-private-key.pem \
  --remove-private-key-after-signing
```

`package_candidate.py` rejects a static `OpenFileL` call or import, requires the
sole static MDA import to be ordinal 35, verifies the silent WAV and signed
package, and deletes the provided key only when the explicit removal flag is
set.
