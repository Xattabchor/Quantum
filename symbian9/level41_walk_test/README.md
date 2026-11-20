# Symbian Level 41 native-walk test package

This directory holds the separately versioned Level 41 walk test packages,
asset preparation, source-level regression tests, and package audits. The
v1.0.53 sym9 Level 1 candidate is a distinct asset profile; prior candidates,
including the v1.0.52 candidate and v1.0.43 MDA rollback, are retained unchanged.

## v1.0.53 sym9 Level 1 candidate

The candidate uses the selected Kordos Level 1 map, lightmap, forest/map/river
textures, sky mesh, and configured daytime sky texture from Drive's `sym9.zip`.
The asset source and each raw/QTX conversion hash are recorded under
`../level41_device_test/assets/sym9-level1/sym9-assets-manifest.json` and in the
package audit. The forest texture's original 128×64 dimensions are supported
without resampling. That v1.0.53 candidate loaded and packaged only river frame
0; river animation was not claimed for that version. Restoration-specific
weapon code/assets were not overlaid; the v1.0.43 rollback payloads for weapon,
audio, weather, and app registration are retained read-only as package sources.

- Candidate SISX:
  `output/v1.0.53-sym9-level1-assets/quantum_level41_walk_test-v1.0.53-sym9-level1-assets-e65-candidate.sisx`
- Machine-readable audit and provenance:
  `output/v1.0.53-sym9-level1-assets/quantum_level41_walk_test-v1.0.53-sym9-level1-assets-e65-candidate-audit.json`
- Build report:
  [`SYM9-LEVEL1-ASSET-BUILD-REPORT-v1.0.53.md`](SYM9-LEVEL1-ASSET-BUILD-REPORT-v1.0.53.md)
- Dedicated packager: `package_sym9_v1_0_53.py`

The package passed the local signed-package audit and a second extraction/byte
comparison of all runtime payloads, registration resources, and the patched
E32 round-trip. The local adapted SIS writer is not stock MakeSIS acceptance
evidence. The package uses a test-only, self-signed certificate; it is not a
production signature. No E65 installation or runtime test was performed, so
E65 compatibility, startup, rendering, movement, keypad, weather, audio, FPS,
and stability are not claimed. The report lists the remaining limitations.

## v1.0.54 Level 1 object integration candidate

v1.0.54 builds on the v1.0.53 environment profile and adds exactly the one
`[MESH]` and one `[SPRITE]` placement selected from `sym9.zip/level1.txt`.
The checked-in object manifest records their original coordinates and settings;
only `prop/md.3d`, `prop/mdy.png`, and `prop/skl.png` are sourced from
`prop.zip`. The runtime package contains the parsed model and its two converted
QTX1 textures; no other prop archive entries or placements are included.

- Candidate SISX:
  `output/v1.0.54-sym9-level1-objects/quantum_level41_walk_test-v1.0.54-sym9-level1-objects-e65-candidate.sisx`
- Machine-readable package and asset audit:
  `output/v1.0.54-sym9-level1-objects/quantum_level41_walk_test-v1.0.54-sym9-level1-objects-e65-candidate-audit.json`
- Build and validation notes:
  [`SYM9-LEVEL1-OBJECTS-BUILD-REPORT-v1.0.54.md`](SYM9-LEVEL1-OBJECTS-BUILD-REPORT-v1.0.54.md)
- Dedicated packager: `package_sym9_v1_0_54.py`

The host object integration regression and ARMv5T/EKA2 E65-profile cross-build
passed. The local adapted SIS writer extracted all 23 package records and each
was byte-compared with its staged payload, including the patched E32. The
candidate is locally test-signed; this is not stock MakeSIS acceptance. No E65
installation or visual/runtime test was performed, so device rendering and
Java pixel parity remain unverified.

## v1.0.55 Level 1 river-animation candidate

v1.0.55 carries the original `level1.txt` WTEX declaration into the native
Level 1 walk build: four converted river frames at **6 FPS**. The interactive
loader now loads `river0.qtx`–`river3.qtx`, attaches them to world material slot
4, and feeds the Java-style absolute-time animation selector with Unix-epoch
milliseconds. `river.qtx` remains in the separate fixed-scene target. The
v1.0.54 candidate and the v1.0.43 MDA rollback were not modified.

- Candidate SISX:
  `output/v1.0.55-sym9-level1-river-animation/quantum_level41_walk_test-v1.0.55-sym9-level1-river-animation-e65-candidate.sisx`
- Machine-readable package/asset audit:
  `output/v1.0.55-sym9-level1-river-animation/quantum_level41_walk_test-v1.0.55-sym9-level1-river-animation-e65-candidate-audit.json`
- Build report:
  [`SYM9-LEVEL1-RIVER-ANIMATION-BUILD-REPORT-v1.0.55.md`](SYM9-LEVEL1-RIVER-ANIMATION-BUILD-REPORT-v1.0.55.md)
- Dedicated packager: `package_sym9_v1_0_55.py`

The four-frame/six-FPS renderer regression, existing Level 1 object integration
regression, walk/weapon suite, ARMv5T/EKA2 cross-build, and 27-record adapted
SIS round-trip audit passed. The candidate is test-signed; this is not stock
MakeSIS acceptance. **No E65 install or runtime animation test was performed**;
device display, actual frame timing, and Java pixel parity remain unverified.
