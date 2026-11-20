# MDA OpenFile diagnostic — stage 3

This is a separate follow-up to the unchanged NewL-only and OpenDes stage-2
probes. It performs only these MDA operations, in order:

1. `CMdaAudioPlayerUtility::NewL`;
2. `OpenFileL("C:\\data\\mda_open_file_probe.wav")` for the WAV installed in
   this SISX;
3. waits up to 15 seconds for `MapcInitComplete` and shows the callback result,
   duration, an immediate leave, timeout, or worker panic.

The WAV contains 100 ms of silent, mono, 8 kHz, 16-bit PCM. **The probe never
calls `Play`, imports no MIDI DLL, and does not test audible output.**
`MapcPlayComplete` is present only as the callback required by the MDA interface;
there is no play request.

## E65 candidate

- `quantum_level41_mda_open_file_diag-v1.0.0-e65-candidate.sisx`
- Size: **116,060 bytes**
- SHA-256:
  `38301845f6068c1eeff9b9f0f7b6d300ec1d3095b3f38068f4c8cbc9023e1a51`
- Package UID `0xE000A02D`; executable UID3 `0xE000A02C`; version `1.0.0`.
- The package contains the app registration/localization assets and the WAV at
  `!:\data\mda_open_file_probe.wav`.
- The executable is ARMv5T/EKA2, has zero capabilities, and imports exactly
  `mediaclientaudio.dll` ordinals **35** (`NewL`) and **42** (`OpenFileL`).
  It has no MIDI imports.
- The self-signed certificate is RSA-1024/SHA-1 and valid from 2000-01-01
  through 2045-12-31, matching the previously E65-tested legacy signing
  profile. Its private key was temporary and was removed after packaging; the
  key is not present in the candidate or repository.

**Install to phone memory (C:)** so the install-drive placeholder `!:` for the
WAV matches the diagnostic's explicit `C:\\data\\...` path. Launch
**MDA OpenFile** from the app menu. The user has now reported the launch
message `Menu: Feature not supported`. This is a launch failure, not a positive
`OpenFileL` result; a local package audit is not a phone test.

### What the E65 result will mean

- `MapcInitComplete: success` means `NewL` returned and the E65 completed the
  `OpenFileL`/MDA initialization callback for the packaged silent WAV. The
  displayed duration should be about 100 ms.
- `OpenFileL leave code: ...` reports an immediate leave from the file-open
  call; the screen also identifies a `NewL` or timer-setup leave separately.
- `OpenFileL callback timed out` means no initialization callback arrived in
  15 seconds. A worker panic is reported separately.
- None of these outcomes tests decoding during playback or produces a claim
  about speaker/audio output.

Please report the exact screen text (or a photo) and whether installation and
launch succeeded. That device result is the remaining acceptance check.

## Local build and package audit

The E32 executable was built locally with Symbian SDK 0.3.0. The SIS writer
adaptation and command-line helper are in `local_sis_writer/`; their source is
based on upstream `symbian-platform` v0.3.0, commit
`b105f752f84d1b5640052f64c4a703017810d7b8`. The extension accepts only a
bounded single-level data filename under `!:\data\`, and the modified
canonical inspector re-parses the signed output and verifies its RSA/SHA-1
signature and embedded payloads. See `local_sis_writer/UPSTREAM.md`.

The host helper was compiled locally with g++ 12.2, SDK-bundled OpenSSL/zlib
headers, and the compatible system OpenSSL 3.0.20 and zlib 1.2.13 libraries.
The SDK prebuilt host archive was not linked because its C++ ABI requires a
newer host glibc; the helper instead compiles the pinned Abseil/E32/SIS source
needed by this target with the local host compiler.

The compatibility check is intentionally strict: with no WAV, the adapted C++
writer produced unsigned and signed outputs byte-for-byte identical to the
installed native SDK builder and signer. With the WAV present, the adapted
inspector verified the signed package, registration, four file records, and
embedded WAV size/SHA-1. The detailed machine-readable report is
`quantum_level41_mda_open_file_diag-v1.0.0-e65-candidate-audit.json`.

To rebuild the helper from the pinned local source checkouts:

```sh
export PYTHONPATH="$HOME/quantum-sdk-cache/host-python"
python3 local_sis_writer/build_local_sis_writer.py \
  --sdk-root "$HOME/quantum-sdk-cache/symbian-sdk" \
  --upstream-source "$HOME/quantum-sdk-cache/upstream-v030" \
  --abseil-source "$HOME/quantum-sdk-cache/abseil-cpp" \
  --cmake "$HOME/quantum-sdk-cache/host-python/bin/cmake" \
  --ninja "$HOME/quantum-sdk-cache/host-python/bin/ninja"
```

To package again, activate the local SDK virtual environment and provide a
certificate/key pair matching the E65-tested signing profile:

```sh
source ~/quantum-sdk-cache/venv/bin/activate
python package_candidate.py \
  --artifact ~/quantum-sdk-cache/level41-mda-open-file-build/quantum_level41_mda_open_file_diag.exe \
  --sis-writer ~/quantum-sdk-cache/stage3-sis-writer/build/stage3_sis_writer \
  --sdk-manifest ~/quantum-sdk-cache/symbian-sdk/sdk.json \
  --certificate /path/to/certificate.pem \
  --private-key /path/to/temporary-private-key.pem
```

The temporary signing key used for this candidate has been destroyed, so
re-signing will require a new matching key/certificate and will produce a
candidate with a different SHA-256. No GitHub Actions build was used.

## E65 result — launch failure on this static-import candidate

**User report:** `Menu: Feature not supported` when attempting to launch this
app; no screen appeared. This candidate draws its diagnostic screen before
starting the MDA worker, so this run did not obtain a `NewL`/`OpenFileL` result.
At the time, ordinal 42 was a possible explanation for the launch failure.

## Separate stage-3b follow-up — dynamic OpenFileL success

The separate [stage-3b dynamic-ordinal diagnostic](../level41_mda_open_file_stage3b/README.md)
keeps ordinal 42 out of the static import table and resolves it with
`RLibrary::Lookup(42)`. On the E65, the user reported
`MapcInitComplete: success` and `Duration: 100 ms`. That result confirms the
ordinal exists and the dynamically dispatched `OpenFileL` initialized the
packaged WAV successfully on this device. It rules out a missing export as the
cause on this E65, but does not establish why this older static-import
candidate failed to launch; the static import behavior or another candidate
difference remains unisolated. The stage-3 executable and SISX above remain
unchanged.
