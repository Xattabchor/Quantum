# sym9.zip Level 1 asset candidate — v1.0.53

## Result

A fresh ARMv5T/EKA2 E32 was built from the active working tree and packaged as a separately named v1.0.53 test candidate using the selected Kordos Level 1 assets from Drive's `sym9.zip`.

- SISX: `output/v1.0.53-sym9-level1-assets/quantum_level41_walk_test-v1.0.53-sym9-level1-assets-e65-candidate.sisx`
- Size: **1,419,128 bytes**
- SHA-256: `62421d74151a8e3d512e524a066e3c473faac2f48f23957c680053678b6b4d8f`
- Package version: **1.0.53**; UID `0xE000A021`; executable UID3 `0xE000A020`; 19 embedded records.
- Machine-readable package/provenance audit: `output/v1.0.53-sym9-level1-assets/quantum_level41_walk_test-v1.0.53-sym9-level1-assets-e65-candidate-audit.json`.
- Selected source assets and all eight generated QTX1 files: `../level41_device_test/assets/sym9-level1/`.
- The v1.0.52 candidate and MDA v1.0.43 rollback package were preserved unchanged.

## Imported assets

Source: Drive `sym9.zip`, ID `1elEgbqUS3KhYfKuusXIwS1LaVF_o6bki`, SHA-256 `eb81090003338a3333aa2d5b1c25c586cdabff0c446ed9664da132ca82e7b60b`.

The archive's `level1.txt` explicitly selects the Kordos world model and lightmap, `forestOrigy.png`, `map-selo1.png`, four river BMP frames, `skies/sky.3d`, and daytime `skies/skuy.png`. Fifteen raw selected files were extracted and byte-compared to their archive members. Unrelated maps, weapons, and source code were not overlaid.

| Packaged resource | sym9.zip source | SHA-256 |
| --- | --- | --- |
| `L3D_new.3d` | `maps/kordos/kordos.3d` | `00cf889f495aa5b8fe6bc058c022ef16f49a8761c2d30b7d084fa43eec088ad9` |
| `lightmap.vla` | `lightmap/kordos.vla` | `b62c6b46df7b60e50d3cced9e5fe4f73d32b21d45521273932c5a53e0bfad53e` |
| `skybox8.3d` | `skies/sky.3d` | `e7fe2edc8d7132808f3ea15ab55dc7663ba3263de25a007f7c576f58c8ad9e1f` |
| `forest.qtx` | `maps/kordos/forestOrigy.png` (128×64) | `29e1524bef7809d27aa93b7dab2b13d31c16e4b592fe10051f8b8a753059874c` |
| `map.qtx` | `maps/kordos/map-selo1.png` (256×256) | `c963a1dc3f644cfd3e5ddf2991bfbaf446e6580e97a816b2b2c5785d2df16286` |
| `river.qtx` | `maps/kordos/river0.bmp` (32×32) | `707a5e6fd572f4f9fc127ea325edc0df90292eb1f1dad925cd26dcf6a487a231` |
| `sky.qtx` | `skies/skuy.png` (256×256; configured daytime sky) | `40125dacbfa69d2b2fb45e9810056e417ca970239846615332d6f29a99a43aa7` |

The original `skun.png` and all BMP/PNG river frames remain available in the staged profile. QTX1 conversions for `river0`–`river3` were regenerated and hash-checked; the current executable embeds and loads only `river.qtx` (frame 0), so **river animation is not claimed**. The native loader now accepts the archive's exact 128×64 forest texture alongside the existing 256×128 profile; the archive image is not resampled.

## Build and local checks

- SDK 0.3.0 split archive recombined to SHA-256 `0c974247026ee0503a3d522a497963b73cfe2247075cffae3867c33ae09f7216`.
- Drive GLIBC 2.38 runtime and host-helper archives matched their Drive `SHA256SUMS`: `d281c56280563674bbb218abec67a9bfa1f0daa7cfddd50a1cea489c23a4f92b` and `2d67b15a47129158ec7f98bbf71fe74042c7134c1546de467f502e2664fc307c`.
- CMake 3.28.3, Ninja 1.13.2, Clang 23.1.2; ARMv5T/EKA2 with the E65 OMAP1710 ASM and DSA ARGB32-required build gates. Standard MDA remains enabled; SNDDMA and MIDI are off.
- The only native-source change for this asset switch was the forest texture dimension guard. The existing 3D mesh renderer, walk/sway path, weapon path, roof-aware rain path, and audio backend were otherwise unchanged.
- All four host regression configurations passed: SIS v1.0.33 baseline, baseline without sway, default turn-inertia A/B, and disabled-weapon-sway A/B. Existing compiler warnings remain in `matrix.cpp` and the local writer/Abseil build.
- The locally adapted SIS writer validated the signature/package records, 19-record payload list, MDA import ordinals `[35, 15]`, absence of MIDI imports, 2 MiB E32 heap/CRC, and byte-identical extracted E32 round-trip. A second extraction compared all 16 runtime payloads and both registration resources to the staged inputs.

## Limitations

**No E65 installation or runtime test was performed.** Startup, sky/map appearance, keypad delivery, walking, roof-edge rain, display mode, audio playback, FPS, and stability remain unverified. The local adapted writer is not stock MakeSIS acceptance evidence. The SISX is a locally signed test candidate, not a claim of device compatibility. Audio playback and the previously reported post-shot issue remain unresolved/unverified.
