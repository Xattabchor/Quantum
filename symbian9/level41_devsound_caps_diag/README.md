# E65 DevSound capabilities / PCM-open diagnostic

This is a separate, zero-capability Symbian diagnostic for the E65. It queries
`CMMFDevSound::Capabilities()` and `Config()` through the public DevSound API,
then performs up to two public MDA output-stream **open-only** smoke tests. It
does not change the game's MDA path or the experimental SNDDMA software mixer.

## What was already known

Before this run, no E65 **runtime DevSound capability mask** was present in the
Drive materials checked. The user has now supplied a readable photo of this
probe's final E65 screen: `Capabilities().iRate = 0x0000067F`, PCM16 with mono
and stereo bits, current `Config()` 8 kHz PCM16 mono, and successful MDA opens
for 8 kHz mono and 11,025 Hz stereo. The full transcription and limits are in
[`RUNTIME_RESULTS.md`](RUNTIME_RESULTS.md).

The previous user-reported MDA results—`NewL` returned, silent in-memory/file
WAV initialization opened, and a 100 ms file duration was read—remain
initialization/file-open results only; `Play` was not called. The new probe
also writes no PCM and explicitly reports that audible output and routing were
not tested. The Drive archive `e65-caps-decision.zip` is an offline ROM/loader
capability experiment about synthetic LDD images, not an audio measurement.
The ROM-backed audio inventory remains in
[`tools/research/e65/audio-findings.md`](../../tools/research/e65/audio-findings.md).

## Probe sequence

1. In an isolated worker thread, call `CMMFDevSound::NewL()` and
   `InitializeL(observer, EMMFStatePlaying)`.
2. On `InitializeComplete(KErrNone)`, capture all four fields from both
   `Capabilities()` and `Config()`: rate mask, encoding mask, channel mask, and
   buffer size. The screen shows the raw masks and the advertised sample rates
   in Hz, preserving unknown bits in the masks.
3. Release the direct DevSound object. Load
   `mediaclientaudiostream.dll` through `RLibrary::Load`, then resolve the
   public `CMdaAudioOutputStream::NewL(observer, CMdaServer*)` export. The SDK
   export definition maps that overload to ordinal 3. The DLL is deliberately
   **not** a static E32 import: if it is absent on the E65, the DevSound query
   can still complete and the stream test will report the load/lookup error.
4. Only when the queried DevSound flags advertise signed PCM16 plus the exact
   requested rate and channel mode, call MDA `Open()` for **8,000 Hz mono** and
   then **11,025 Hz stereo**. Each asynchronous callback has a 15-second
   timeout. A skipped test means at least one of those three requested flags
   was not reported.

The second format was a candidate matching the current software-mixer
request; the supplied E65 screen now confirms that its MDA `Open` callback
succeeded, but not that it is the best or physical hardware rate. The 8 kHz
mono case is a basic smoke test, not an asserted optimal setting. The MDA
stream API defaults to PCM16 unless a different data type is set; the settings
package supplies the requested rate and channels.

## Safety and interpretation

- This uses public `CMMFDevSound` / MDA APIs only. No LDD/PDD, DSP bridge,
  register, physical-address, or direct-DMA path is used.
- The probe does not call `PlayInitL`, `PlayData`, `WriteL`, `SetConfigL`, a
  tone API, or a recording API; it sends no PCM payload and should produce no
  sound. MIDI remains off.
- `InitializeL` initializes DevSound in playback mode so the public query is
  made in the relevant mode. `MaoscOpenComplete: 0` means the requested MDA
  stream opened; it does **not** prove audio was played, heard, routed to the
  speaker/headset, or rendered at the requested physical DAC rate.
- `Capabilities()` is the mask reported by this E65 in this runtime/policy
  context. `Config()` is the current configuration returned after
  initialization. Neither is inferred from SDK constants or the ROM rate
  conversion table.
- Results are isolated from a worker panic/leave and displayed on the final
  screen. `KErrNone` is shown as `0`; negative error codes are preserved.

## E65 test procedure

The user has now run the signed candidate on the E65 and supplied a readable
final-screen photo; its values are recorded in [`RUNTIME_RESULTS.md`](RUNTIME_RESULTS.md).
For a rerun, capture/transcribe `InitializeComplete`, capability/config masks
and buffer sizes, stream DLL/ordinal status, and each open result or error
(including timeout/panic).

For a missing `mediaclientaudiostream.dll`, the DevSound query remains useful;
the stream test will be marked unavailable. Do not report an open success as
audible playback. The current E65 run establishes the reported capability mask
and two open-only successes, but not audible output, the selected route, or the
physical DAC rate.

## Built candidate and audit

The signed test installer is in Drive as
[quantum_level41_devsound_caps_diag-v1.0.0-e65-candidate.sisx](https://drive.google.com/file/d/13aOmV-bVB2-YpaBHHt_MX0H3TKjzvAcK/view?usp=drivesdk);
its SHA-256 is recorded in [`AUDIT.json`](AUDIT.json). The audit verifies the E32 UID, both zero
capability words, ARMv5T/EKA2 format, imports, package UIDs, signing pair,
registration resources, and packaged files. The static DLL set is limited to
`estlib.dll`, `euser.dll`, `gdi.dll`, `mmfdevsound.dll`, and `ws32.dll`; the
optional stream DLL is loaded only at runtime. The RSA-1024/SHA-1 self-signed
key was temporary and removed after signing. This is a test-signed candidate,
not an official trusted release.

The build completed with the restored Symbian SDK 0.3.0, GLIBC 2.38 host
runtime, Clang 23.1.2, and the local SIS writer built from pinned upstream
sources. A package-source audit, Python syntax check, SISX registration/signing
and embedded-file audit passed. The two existing offline ROM-parser unit tests
also pass. A user-supplied E65 screen photo now confirms runtime initialization
and the two open-only results in [`RUNTIME_RESULTS.md`](RUNTIME_RESULTS.md).
No audio payload was played, and no audible output or route was tested.

## Rebuild in this workspace

The restored SDK, host runtime, Python CMake/Ninja wrappers, and pinned source
checkouts lived under the ignored `google_drive/` directory and are not part of
this Git tree; restore those inputs from the connected Drive before rebuilding
if that directory is absent. The following commands describe the build setup
used for this candidate.

This sandbox's restored SDK host binaries were patched to use the short ELF
interpreter path `/tmp/ld.so`. Recreate the symlink before invoking them:

```sh
SDK="$PWD/google_drive/restored-sdk"
HOST_GLIBC="$SDK/host-glibc-2.38/host-glibc-2.38/lib"
HOST_PYTHON="$PWD/google_drive/host-python"
ln -sfn "$HOST_GLIBC/ld-linux-x86-64.so.2" /tmp/ld.so
export LD_LIBRARY_PATH="$HOST_GLIBC:$SDK/libexec/host:/usr/lib/x86_64-linux-gnu:/lib/x86_64-linux-gnu"
export PYTHONPATH="$HOST_PYTHON"

"$SDK/bin/cmake" --fresh \
  -S symbian9/level41_devsound_caps_diag \
  -B google_drive/e65-devsound-caps-build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$SDK/cmake/symbian-arm.cmake" \
  -DSYMBIAN_SDK_PREFIX="$SDK" -DSYMBIAN_TARGET_ARCH=armv5t \
  -DSYMBIAN_RUNTIME_LEGACY_EKA2=ON \
  -DCMAKE_MAKE_PROGRAM="$SDK/bin/ninja" -DCMAKE_BUILD_TYPE=Release
"$SDK/bin/cmake" --build google_drive/e65-devsound-caps-build \
  --target quantum_level41_devsound_caps_diag_e32 -j2
```

The local SIS writer can be rebuilt with the pinned source checkouts in this
workspace (`upstream-v030` commit `b105f752f84d1b5640052f64c4a703017810d7b8`,
Abseil commit `5650e9cf76d3be4318d5fa3af38ee483ddfd5e4a`):

```sh
python3 symbian9/level41_mda_open_file_diag/local_sis_writer/build_local_sis_writer.py \
  --sdk-root "$SDK" \
  --upstream-source "$PWD/google_drive/upstream-v030" \
  --abseil-source "$PWD/google_drive/abseil-cpp" \
  --build-dir "$PWD/google_drive/e65-devsound-sis-writer-build" \
  --output "$PWD/google_drive/e65-devsound-sis-writer-build/stage3_sis_writer" \
  --cmake "$HOST_PYTHON/bin/cmake" \
  --ninja "$HOST_PYTHON/bin/ninja"
```

To repackage, generate a temporary matching RSA-1024/SHA-1 self-signed
certificate/key, run `package_candidate.py --help`, and pass
`--remove-private-key-after-signing`. Never retain the private key in Git or
embed it in a SISX. Keep normal MDA as the game's primary audio path and SNDDMA
as a separate experiment until audible playback and route behavior are tested.
