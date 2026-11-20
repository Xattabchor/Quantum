# SIS v1.0.33 native walk/sway reconstruction — v1.0.52 rebuild report

**Result:** a fresh ARMv5T/EKA2 E32 was cross-built and packaged as a separately named, locally signed SISX candidate. The host regressions, local signature/package audit, 19-record payload audit, E32 heap/CRC validation, and exact embedded-E32 round trip passed. **No E65 installation or runtime test was performed.**

## Candidate and audit artifacts

- Candidate: `output/v1.0.52-sis33-native-walk150-jump0/quantum_level41_walk_test-v1.0.52-sis33-native-walk150-jump0-e65-candidate.sisx`
- Package version: **1.0.52**. This is a new sequence label, not a behavioral reference; it does not replace the previously uploaded v1.0.49 candidate or any historical SISX.
- SISX size: **1,517,276 bytes**
- SISX SHA-256: `8fb3a585c6f0e251ea0c65ae2609384281893170f5b7df8f3b48b248145eb4d1`
- Package UID / executable UID3: `0xE000A021` / `0xE000A020`; application registration is present; **19 records** were embedded.
- Machine-readable audit/provenance: `output/v1.0.52-sis33-native-walk150-jump0/quantum_level41_walk_test-v1.0.52-sis33-native-walk150-jump0-e65-candidate-audit.json`.
- Companion source-and-assets bundle: `output/v1.0.52-sis33-native-walk150-jump0/quantum_level41_walk_test-v1.0.52-sis33-native-walk150-jump0-source-and-assets.zip`.

## Walk and jump behavior

- The sole behavioral walk/sway baseline is the v1.0.33 SIS/E32 (SIS SHA-256 `002e698f84393f7304108716daa755904da8c33bacc7967d1c481293712ade8a`) and the native observations in `SIS33-WALK-SWAY-NATIVE-TRACE.md`. No v1.0.34+ behavior is used as the native specification; Java is only a cross-check.
- `walkSpeed=150` is restored as the **per-update acceleration scalar** observed in v1.0.33. It is not a 150-unit key-event step; horizontal displacement is scaled by clamped elapsed time / 50 ms. Native turning uses `10 × elapsedMs / 50`; turn inertia is off in this build.
- Button **0** accepts either the character code `'0'` or Symbian `EStdKeyNkp0` scan code `0x92`. It creates a one-frame pulse and only launches while grounded. Jump impulse 150, velocity scale 1.2, and gravity −20 per 50 ms come from the Java cross-check; **native v1.0.33 jump-field equivalence is not claimed**.
- Host regression covers button mapping, launch and pulse behavior, gravity/landing, grounded friction, and the collision ceiling stop. This is source-level coverage, not E65 keypad or runtime verification.
- The native weapon sway remains a partial source-level reconstruction. Full object/field mapping, state equivalence, and pixel parity are not established; the rejected v1.0.47 weapon animation is not selected.

## Build and test details

- SDK 0.3.0; CMake 3.28.3; Ninja 1.13.2; ARMv5T/EKA2, OMAP1710; DSA ARGB32 required at compile time.
- CMake preset: `e65-level41-walk-asm`. Standard Symbian MDA remains enabled; SNDDMA adapter/audio and MIDI are off. Roof-aware rain, the existing 3D mesh renderer, and the requested OMAP1710 assembly paths are retained.
- Fresh raw E32: **184,248 bytes**, SHA-256 `f1be4f4488f7fb85be108db0e2f6ff9f19a0e7144c8b4fbb802b43cb6e5a6497`.
- Packaging's scoped E32 profile changed the maximum heap from 1 MiB to 2 MiB and recalculated the header CRC. Patched/embedded E32 SHA-256: `91d0a9008fce322ed2f5332f76a6688166c1d95aa166b2dc8fa85511955df64d`; the signed-package extraction matched these bytes exactly.
- Host suites passed under AddressSanitizer and UndefinedBehaviorSanitizer in all four modes: `--sis33-baseline`, `--sis33-baseline-no-sway`, default turn-inertia, and `--disable-weapon-sway`. Compiler output includes existing deprecated enum/floating-point arithmetic warnings in `matrix.cpp`; no test failures remain.
- The local adapted stage-3 SIS writer validated the signature, package records, payload hashes, E32 header/heap/CRC, and exact embedded-E32 round trip. This is **not** stock MakeSIS acceptance or E65 installation evidence. The temporary test-signing private key was deleted after successful signing.

## Payload provenance and limitations

- Seven Level 41 environment assets were selected from Drive's `quantum last.zip` (ID `1lUuQ-BUSA0ABKCjWoCLELBVagUBGYJkr`, SHA-256 `90683d18db5e281647835506942ab44784e744b35f695ccd496d4cb7e6e544a7`). They were byte-compared with the same seven records in the hash-verified pre-audio v1.0.51 SISX (SHA-256 `a4b5922f2bb3083e140507d9aee038df4afcf5c788b2e53b33250df84cdebc1f`). This does **not** establish equality with any latest compiled SIS; no such claim is made. No bulk code overlay was applied from the archive.
- Weather, weapon-art/gameplay, audio, and registration resources were read-only extracted from the separate v1.0.43 MDA rollback SISX (SHA-256 `c01410a5fc9883b4fb95c1438a83b12dab60a70d08455648d3ebc571b1a9d3da`). That historical rollback SISX remains unchanged.
- **E65 runtime status: not tested.** Installation, launch, walking speed, button delivery, jump feel, display mode, roof-edge rain appearance, audio playback, FPS, and stability remain unverified. The previously reported MDA post-shot crash remains unresolved; this candidate does not claim to fix it.
- The local SIS writer uses a scoped 2 MiB E32 parser compatibility patch. The adapted writer is not stock SDK MakeSIS, and no hardware/device compatibility claim follows from the local audit.
