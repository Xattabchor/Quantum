# Level 1 mesh and sprite integration — v1.0.54 build report

## Result

The interactive Level 41 native scene now loads and renders exactly the single
`[MESH]` and single `[SPRITE]` placement in `sym9.zip/level1.txt`. The source
record values were retained; no additional prop placements or unreferenced
`prop.zip` entries were added.

### Imported Level 1 records

| Record | Preserved settings |
| --- | --- |
| `[MESH]` | `POS=-73825,-3626,-113803`; `MODEL=/prop/md.3d`; `TEX=/prop/mdy.png`; `MODEL_SCALE=2`; `COLLISION_SCALE_X=0`; `COLLISION_SCALE_Y=0`; `PHYSICS=0` |
| `[SPRITE]` | `POS=-71400,-3368,-76270`; `TEX=/prop/skl.png`; `SCALE=34`; `FOG=NONE`; `MODE=NORMAL` |

Only these prop source assets were selected from `prop.zip`:

- `prop/md.3d` — SHA-256 `8f078f7d719eed10801c3914db73b630662ebd5e1644a1f6ca2baf0bfa14c7fb`
- `prop/mdy.png` — SHA-256 `e78362d882a6d78f8665fe1757856326d40ee312e7081f1fd36cc356a33edf70` (64×64)
- `prop/skl.png` — SHA-256 `6d766bae12c0f1ee296b5561417c9782106356645c08c73b2bdb504f6caa6f0a` (43×64)

The runtime private-asset profile contains the parsed `prop/md.3d` plus QTX1
textures converted from the two listed PNGs. It contains no other prop archive
entries. The exact runtime records and provenance are in
[`../level41_device_test/assets/sym9-level1/level1-objects-manifest.json`](../level41_device_test/assets/sym9-level1/level1-objects-manifest.json).

## Tests and build

- `sh symbian9/host_tests/run_level41_walk_weapon_regression_test.sh` passed. This includes the walk/weapon regressions, general world-object renderer tests, and the production Level 1 object regression. The production test verified the exact manifest, parsed the real MD mesh and both QTX1 textures, checked the one-mesh/one-sprite counts and settings, and submitted them through the combined painter queue.
- Fresh ARMv5T/EKA2 cross-build passed for `quantum_level41_walk_test_e32` using SDK 0.3.0, CMake 3.28.3, Ninja 1.13.2, and Clang 23.1.2.
- E65 profile: OMAP1710 assembly optimizations and DSA ARGB32 compile gate on; standard MDA audio backend retained; SNDDMA and MIDI off. MDA imports are `[35, 15]`; MIDI imports are absent.
- Raw build E32: `symbian9/build/e65-level41-walk-asm/level41_device_test/e32/quantum_level41_walk_test.exe`, 207,396 bytes, SHA-256 `9e09942b0aceb955b794689bfaac6f6bcdf6149c42dcebcc6f98c9590e6bd6fe`.
- Packaged E32 has a locally applied 2 MiB maximum heap (minimum 4,096 bytes); its E32 header CRC and extraction round-trip passed. Packaged E32 SHA-256: `7207484e85ec7f692390adc77e71051142867f9dcecff23c2cf36cb42f207e3a`.
- The compiler emitted the existing deprecated enum/floating-point arithmetic warnings in `matrix.cpp:57–58`. The local SIS-writer/Abseil build emitted existing GCC `-Wrestrict` and missing-field-initializer warnings.

## Candidate package

- SISX: `output/v1.0.54-sym9-level1-objects/quantum_level41_walk_test-v1.0.54-sym9-level1-objects-e65-candidate.sisx`
- Size: 1,470,756 bytes; SHA-256 `b7d728666f2c36fc14714680016a9125138ae0e348c78fd43960114bb7b278f5`
- Package version 1.0.54; package UID `0xE000A021`; executable UID3 `0xE000A020`; 23 embedded records.
- The local adapted SIS writer extracted all 23 records and byte-compared every staged runtime file, registration resource, localization resource, and E32. The exact file set matched; no extra files were embedded.
- Audit: `output/v1.0.54-sym9-level1-objects/quantum_level41_walk_test-v1.0.54-sym9-level1-objects-e65-candidate-audit.json`.
- Test-only self-signed RSA-1024/SHA-1 certificate; public copy is `output/v1.0.54-sym9-level1-objects/sym9-v1.0.54-test-certificate.pem`. The temporary private key was deleted after signing and is not embedded or included in the workspace.
- The v1.0.53 candidate and the v1.0.43 MDA rollback SISX were not modified. The v1.0.43 package remained a read-only source for the established weather, weapon, audio, and registration payloads.

## Limitations

**No E65 installation, launch, or runtime test was performed.** Device visibility,
placement, `MODE=NORMAL` compositing, alpha behavior, frame timing, and Java
pixel parity remain unverified. The local adapted SIS writer's audit is not
stock MakeSIS acceptance evidence. Host regressions and a successful ARM E32
build establish neither E65 compatibility nor runtime behavior.
