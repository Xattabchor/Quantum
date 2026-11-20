# Level 41: old-source baseline build and package report

## Result

The pre-audio Level 41 source from the supplied ZIP was extracted separately and cross-built for ARMv5T/EKA2. The latest available Level 41 assets from the current workspace build tree were copied into that isolated source tree, and four QTX1 textures were regenerated from those inputs.

A **signed SISX-format test candidate** has now also been assembled with the app registration resources and seven runtime assets. It passed the local writer's package/signature checks and its embedded E32 was extracted and byte-compared with the staged executable. **No E65 installation, launch, or walking test has been performed.** The package result is not a device-compatibility verdict.

### Fresh rebuild and Drive delivery

The prior generated `output/` artifacts were absent from the current workspace, so I rebuilt the same archived walk target with the recorded SDK, source ZIP, and latest available workspace asset directory. Host tests and the E65-profile cross-build were rerun. The former temporary signing key had already been deleted, so this rebuild uses a **new self-signed test certificate** and is not byte-for-byte the earlier SISX.

- **Direct Drive link:** [Download the rebuilt signed SISX](https://drive.google.com/file/d/1wZ0LdhKhv8s8g-W_cwED-8ozxX65bLG9/view?usp=drivesdk).
- Drive name: `quantum_level41_walk_test-old-source-v1.0.51-rebuild-2026-10-16.sisx`.
- Size: **904,344 bytes**; SHA-256: `a4b5922f2bb3083e140507d9aee038df4afcf5c788b2e53b33250df84cdebc1f`. The Drive copy was downloaded back and byte-compared with the local file.
- Package UID `0xE000A021`, executable UID3 `0xE000A020`, version `1.0.5`, ten embedded records. The embedded E32 matches the staged 2 MiB-heap executable byte-for-byte; MDA and MIDI imports are absent.
- Fresh signer: self-signed RSA-1024/SHA-1, valid 2000-01-01 through 2045-12-31; SHA-1 fingerprint `AF:4D:E2:93:B9:C1:AD:B0:3C:6B:04:37:A7:DB:11:64:67:34:61:A3`. The public certificate is included in the package inputs; the private key was removed after signing.
- Local adapted-writer validation passed. This does **not** establish acceptance by the stock SDK or E65 behavior. No E65 installation, launch, or walking test has been performed.

## Baseline and assets

- Source ZIP: `quantum-symbian-native-source-backup-2026-10-29-v1.0.51-q48-sidfix-no-assets.zip` — 291,753 bytes; SHA-256 `fcaf4a98d79486af00bf2520f79b19fd6e8d6748e4aa7bd5a76447b02b55d737`.
- Its `SOURCE-MANIFEST.txt` records a 2026-10-29 snapshot and excludes assets/build outputs. That date is later than the workspace date (2026-10-16), so the timestamp/name was **not** treated as proof that it predates sound. Source inspection is the evidence: the archived Level 41 target has no MDA/SNDDMA audio service or audio source in its CMake target, and its walk README explicitly says sounds are not ported.
- The walk project inside this archive declares package version **1.0.5**. The ZIP's **v1.0.51** label describes the separate Q48/SID-fix source snapshot; it is not the walk package version.
- Seven raw assets were copied byte-for-byte from `symbian9/level41_device_test/assets/level41/` into the isolated old source tree. Four QTX1 textures were generated using the archived `prepare_level41_device_assets.py`.
- This is the latest available workspace asset directory, **not independently verified as byte-identical to assets embedded in the latest compiled SIS/build**.

Raw asset SHA-256 values:

| Asset | SHA-256 |
|---|---|
| `L3D_new.3d` | `00cf889f495aa5b8fe6bc058c022ef16f49a8761c2d30b7d084fa43eec088ad9` |
| `forestOrig.png` | `3f1be7f30a510f7aa53e0212f17ffd4b49eac927eab8965808e0281d075b4d0a` |
| `lightmap.vla` | `611e4f67c28d1bd8de778340eef72ad96e59821cdb00c044adbdba65a78162f1` |
| `map-selo.png` | `5729a532df20b6da95da239a825bb93cd5758a2d91fa69f09d7a1213e5af6fbd` |
| `river0.png` | `83dcb4fa5f967def9cd02b072921ac2d2cd518c61a484c08647f76c1b27275ba` |
| `skybox5-2.jpg` | `51929d408121b67054f29ae45734856ff6f026ffc400c741cb5b4c8d04dbda1c` |
| `skybox8.3d` | `15213cafe7535b1f90276b7d7ad807192c9dfa164e6985d93b7687d00ef695c1` |

QTX1 SHA-256 values:

| Texture | SHA-256 |
|---|---|
| `forest.qtx` | `0b2963f5ff7371cb698be871ff37775d9cd82c42c3ad73bef7d89c274e98450e` |
| `map.qtx` | `319a39401f971387172807a6f9b8832ba50e45fe8a6e066c540fbad6d140b697` |
| `river.qtx` | `879f3ff189d631275a4747a0d5d82759ec391ec6ecdc6f173b11ec3ca7d4eb86` |
| `sky.qtx` | `c89af7f2a11d405ab00bf23ce613b6a1a2cc07bf71f404b6cd7f700a0db8788e` |

## Cross-build

- SDK: Quantum Symbian SDK 0.3.0. Its split archive parts recombined to SHA-256 `0c974247026ee0503a3d522a497963b73cfe2247075cffae3867c33ae09f7216`.
- The Drive GLIBC 2.38 runtime (`d281c56280563674bbb218abec67a9bfa1f0daa7cfddd50a1cea489c23a4f92b`) and host helpers (`2d67b15a47129158ec7f98bbf71fe74042c7134c1546de467f502e2664fc307c`) both matched the Drive `SHA256SUMS`. SDK host executables were run with the private loader/runtime under `/home/user/quantum-sdk-cache/`; resource compilation with SDK `rcomp` succeeded.
- Target: `quantum_level41_walk_test_e32`, ARMv5T / EKA2, with the archive's E65-only OMAP1710, EColor64K, and DSA ARGB gates enabled. SNDDMA was not enabled; the archived walk target has no audio integration.
- Raw output: `quantum_level41_walk_test.exe`, 174,200 bytes, SHA-256 `c4c04d30e8083648e3f5035961f075e35d5e8bcd0ec0ae6d9f14f13c11f290fe`.
- The SDK native inspector accepts the raw E32 and reports architecture `armv5t`, kernel `eka2`, UID3/secure ID `0xE000A020`.
- The walk manifest requests a 2 MiB maximum process heap. The raw converter output has a 1 MiB maximum. The separate package E32 was patched to heap bounds 4096..2,097,152 bytes; its header CRC passed. Patched E32 SHA-256: `5325a33861ff937e64d7b6539926f85945087d0adf3c51c1a566c8258792670b`.
- The unmodified SDK 0.3.0 native inspector/package builder rejects that 2 MiB heap layout. The patch is **not** represented as accepted by the stock validator; packaging used the scoped local-writer adaptation described below.

## Host checks

The archived source's `make test` passed with the latest raw assets staged separately:

- Core suite: **79,878 checks passed**.
- Asset parser: **2 `.3d` files**, 2 meshes, 2,753 vertices, 2,663 indexed polygons.
- Python weapon-config tests: **3 passed**; E32 heap and Q48 SID checks passed.

The ARM build completed with two existing `matrix.cpp:57–58` enum/floating-point warnings and no build errors.

## Walking difference to test on E65

The archived input path is event-stepped: `handleLevel41WalkKey()` calls `SoftwareWalkController::step()` once per `EEventKey`. The archived defaults are a 150-world-unit step per forward/back key event and a 10-degree turn per left/right event.

The current source instead tracks key-down/key-up state (with an `EEventKey` pulse fallback) and calls `SoftwareWalkController::update()` from the frame loop. The Java-reference controller adds `walkSpeed=150` to velocity per update, scales displacement by elapsed frame time relative to 50 ms, caps a frame's displacement at 0.8 of the 400-unit body radius, and applies grounded friction `/4`. Thus current `walkSpeed=150` is **not** a 150-unit key-event step. Turning remains event-stepped.

This is the clearest movement-path difference. **If the old version is confirmed to walk normally on the E65**, the next A/B fix candidate is to restore event-stepped forward/back movement in the current source while leaving the ordinary MDA path unchanged and SNDDMA off. The current source was not modified because no E65 result confirms that the old behavior is normal or isolates movement from the added audio path.

## Original SISX package and local validation (historical)

- Earlier candidate (not the rebuild above): **904,248 bytes**, SHA-256 `224e0bab6993c4cd38591fc5cae5489a77bc6da444ef23d2ac23a13bb6c5deed`. Its old signing key and exact SISX are no longer retained; the local path now contains the rebuilt candidate described above.
- Package UID `0xE000A021`; executable UID3 `0xE000A020`; package version `1.0.5`; application registration is present.
- The SISX contains ten records: the E32 executable, registration/localization resources, and seven runtime assets under `!:\private\e000a020\level41\`. Its embedded E32 was extracted and compared byte-for-byte with the staged 2 MiB-heap E32.
- The E32 import audit found **no MDA imports and no MIDI imports**. This is the archived pre-audio walk baseline, not an MDA/SNDDMA build.
- The unmodified SDK package API rejects arbitrary game-asset destinations and the stock E32 parser rejects the 2 MiB heap. To create this test package, the repository's scoped local SIS writer adaptation was built from `hpnkv/symbian-platform` v0.3.0 commit `b105f752f84d1b5640052f64c4a703017810d7b8` with the local `local_sis_writer_2m_heap.patch`. Its validator accepts only the bounded 1/2 MiB heap profile and safe app-private data paths. It re-inspected the signed package and its embedded file records; the extracted EXE round-trip matched. This is a local adapted validation path, **not** stock SDK acceptance.
- The historical candidate used a temporary self-signed RSA-1024/SHA-1 test certificate valid 2000-01-01 through 2045-12-31 (SHA-1 fingerprint `A5:3B:5A:8C:AC:EC:69:00:DE:98:9B:E2:D3:00:FE:FD:53:FF:99:D6`). The rebuilt candidate uses the fresh signer documented above. Each private key was kept outside the repository and removed after signing. The E65 may require an explicit trust/install decision for either self-signed test package.
- Machine-readable local package details: `symbian9/level41_walk_test/output/old-v1.0.51-build/package-inputs/OLD-SOURCE-SISX-AUDIT.json`.

**No E65 runtime test has been performed.** The rebuilt SISX is a locally verified test candidate only; installation, launch, and normal walking behavior remain unverified. No existing SISX, especially the MDA rollback artifact, was overwritten or relabelled.

## Rebuilt bundle

The rebuilt 24-entry package/build bundle is saved at:

`symbian9/level41_walk_test/output/old-v1.0.51-build/quantum-level41-walk-old-v1.0.51-package-build-bundle-rebuilt-2026-10-16.zip`

It includes the signed candidate, public certificate, audit, source backup, fresh and packaged E32 files, staged assets, host-test log, and build report/log. It excludes all signing private keys and SDK host runtimes.
