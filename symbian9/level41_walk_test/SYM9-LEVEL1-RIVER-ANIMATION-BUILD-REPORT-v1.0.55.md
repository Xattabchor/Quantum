# Level 1 river texture animation — v1.0.55 build report

## Result

The Level 1 native walk candidate now uses the four-frame river animation
configured in `sym9.zip/level1.txt`:

```text
6:/maps/kordos/river0.bmp:/maps/kordos/river1.bmp:/maps/kordos/river2.bmp:/maps/kordos/river3.bmp
```

The interactive loader reads `river0.qtx` through `river3.qtx` into four
separate 32×32 texture buffers. It attaches those frames to world material slot
4 at **6 FPS**, using the existing Java-style absolute-time frame selector and
per-frame mip/alpha handling. The render clock is converted from Symbian's
0000-based `TTime` to Java's Unix-epoch milliseconds. The separate fixed-scene
profile still loads `river.qtx` as a single static texture.

No placements were added or changed. The Level 1 scene still contains exactly
the configured one `[MESH]` and one `[SPRITE]`; no extra `prop.zip` assets were
added. The v1.0.54 candidate and v1.0.43 MDA rollback remain unchanged.

## River-frame inputs

| Runtime file | Source | SHA-256 |
| --- | --- | --- |
| `river0.qtx` | `maps/kordos/river0.bmp` | `707a5e6fd572f4f9fc127ea325edc0df90292eb1f1dad925cd26dcf6a487a231` |
| `river1.qtx` | `maps/kordos/river1.bmp` | `e11c978dfddc207e5668a62b4a7ee8a134c45afe7cce3f90b0b783cebc8c4b2e` |
| `river2.qtx` | `maps/kordos/river2.bmp` | `5b1fe47d0d81cdef19a46fa1007021f3c99de96c679614f91ef8ab9f4dcb73ec` |
| `river3.qtx` | `maps/kordos/river3.bmp` | `4a7ab3486209ce3fb6f3a27affdcc6c0adff739aa0d130041fdcd4eec0831b28` |

The legacy `river.qtx` is retained in the package and still maps to frame 0.
All four frame conversions were regenerated from the pinned `sym9.zip` and
byte/hash-checked by the packager.

## Validation and build

- `sh symbian9/host_tests/run_world_objects_rendering_regression_test.sh` —
  passed. Includes four frames at 6 FPS, checking frame boundaries at 0, 166,
  167, 333, 334, 500, 665, and 666 ms, in addition to the existing animation,
  mip, alpha, and shared-painter regressions.
- `sh symbian9/host_tests/run_level41_walk_weapon_regression_test.sh` — passed,
  including the walk/weapon and production Level 1 object integration suites.
- Fresh ARMv5T/EKA2 E65-profile cross-build of
  `quantum_level41_walk_test_e32` — passed with Symbian SDK 0.3.0, CMake 3.28.3,
  Ninja 1.13.2, and Clang 23.1.2. OMAP1710 assembly optimizations and the DSA
  ARGB32 compile gate were enabled; the ordinary MDA path was retained; SNDDMA
  and MIDI were disabled.
- Raw E32: `symbian9/build/e65-level41-walk-asm/level41_device_test/e32/quantum_level41_walk_test.exe`
  — 209,268 bytes, SHA-256
  `211af50c14ddb6ae6ea99c532858bfc0d7008a786efbb1a81519d294c63d04bb`.
- The local adapted SIS writer patched the candidate heap maximum from 1 MiB to
  2 MiB, signed the package, extracted it, and byte-compared every payload.
  The round trip passed: 27 embedded records, 24 runtime data files, no extras.
  Static MDA imports are `[35, 15]`; MIDI imports are absent.

## Candidate package

- SISX: `output/v1.0.55-sym9-level1-river-animation/quantum_level41_walk_test-v1.0.55-sym9-level1-river-animation-e65-candidate.sisx`
- Size: **1,489,784 bytes**
- SHA-256: `11d0d5406d4567af6bfc921be5d6e62aa174b65451ea770c0f7d6b06a4b45992`
- Version `1.0.55`; package UID `0xE000A021`; executable UID3 `0xE000A020`.
- Machine-readable audit:
  `output/v1.0.55-sym9-level1-river-animation/quantum_level41_walk_test-v1.0.55-sym9-level1-river-animation-e65-candidate-audit.json`
- Repacked and test-signed with a fresh temporary self-signed RSA-1024/SHA-1
  certificate because the prior private key had been deleted. The new private
  key was deleted after successful signing; only the public certificate is
  retained with the local build output.

## Limitations

**No E65 installation, launch, or runtime visual test was performed.** The
cross-build and host regression do not establish that the river animates on the
phone, that its observed timing matches Java in device runtime, or that pixels
are identical. The adapted local SIS writer's audit is not stock MakeSIS
acceptance evidence.
