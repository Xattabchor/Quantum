# MDA OpenDes diagnostic — stage 2

The user-reported v1.0.2 stage-1 result was `NewL returned; player deleted`.
This separate follow-up retains `NewL` and advances exactly one step: the worker
thread calls `CMdaAudioPlayerUtility::OpenDesL` on a generated 100 ms, 8 kHz,
mono, 16-bit silent PCM WAV held in memory. It waits up to 15 seconds for
`MapcInitComplete`, then displays its error and duration in milliseconds. A
leave from `NewL` or `OpenDesL` is reported through `RunError`; worker panics
are captured through `RThread::Logon`.

No file is opened or read; `OpenFileL`, `Play`, MIDI, and other audio APIs are
not used. The descriptor contains silence. Success tests MDA source parsing
and initialization only; it does not establish audible playback or speaker
output.

## Candidate and local validation

- SISX: `quantum_level41_mda_open_des_diag-v1.0.1-e65-candidate.sisx`
- [Download the corrected E65 candidate from Drive](https://drive.google.com/file/d/1fiCpfxd5eJG8biXEEKgpVf1kw4TCiDql/view?usp=drivesdk) (file ID `1fiCpfxd5eJG8biXEEKgpVf1kw4TCiDql`).
- Size: **48,964 bytes**; SHA-256:
  `de874d14c7df9a67371598a7aeab54bb224be9d0f5bef00e6495f6b4873e166b`.
- UID3 `0xE000A02A`, package UID `0xE000A02B`; separate from the NewL-only
  probe and the game.
- Signed with a temporary self-signed legacy certificate matching the
  previously E65-tested profile: 1024-bit RSA/SHA-1, valid from 2000-01-01
  through 2045-12-31. Its private key and identity store were removed after
  signing.
- The earlier v1.0.0 candidate was rejected during E65 installation. Its
  certificate was not valid before 2026-11-09, later than today's date; this
  is the likely cause. The obsolete file (49,300 bytes; SHA-256
  `a68b54a93065de46a4f239897376c01d12cd0aecd3281e71c3d540327f969310`) is in
  Drive trash. Do not install v1.0.0.
- E32 audit: ARMv5T/EKA2, zero capabilities; the only
  `mediaclientaudio.dll` imports are ordinals **35** (`NewL`) and **38**
  (`OpenDesL`). `OpenFileL` (ordinal 42) and MIDI are not imported.
- Built locally using SDK 0.3.0 from Drive, Clang 23.1.2, and CMake 3.28.3.
  Local SIS inspection and signature checks passed; phone installation and
  runtime remain unverified.

## E65 result — v1.0.1

**User-reported result:** `MapcInitComplete: success`, `Duration: 100 ms`,
`100 ms PCM WAV from RAM`. The v1.0.1 candidate installed and ran, and the E65
completed `NewL` plus `OpenDesL` initialization for the 100 ms in-memory PCM WAV.
This confirms the tested MDA descriptor-open path on this E65. The probe still
did not read a filesystem audio file, call `Play`, or produce audible output.

The follow-on file-path stage was built separately as stage 3, with a static
`OpenFileL` import, but the E65 reported `Menu: Feature not supported` before
any app screen or MDA result appeared. Its separate stage-3b follow-up keeps
ordinal 42 out of the static imports, looks it up dynamically, and calls only
`OpenFileL` if present. The user reports `MapcInitComplete: success` and
`Duration: 100 ms` on the E65, confirming the dynamic file-open initialization;
see [`level41_mda_open_file_stage3b`](../level41_mda_open_file_stage3b/README.md).
Why the prior static-import candidate failed to launch remains unisolated. The
later candidates remain separate from this verified stage-2 probe, whose binary
and user-reported success are unchanged.
