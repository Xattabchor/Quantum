# E65 launch/import diagnostic matrix

## Goal

Compare launch-only import variants for the already-reported **“Feature not supported”** popup and isolate selected audio-library imports. v1.0.22 is the no-audio/no-DRT baseline; v1.0.23 adds native-leaves/DRT; v1.0.24 adds MDA imports; v1.0.25 links the complete DRT import proxy without native-leave/personality scaffolding; and v1.0.26 dynamically loads `drtaeabi.dll` and looks up v1.0.25's 12 helper ordinals. **v1.0.27 is a readability-only revision** of that lookup probe. v1.0.28 statically imports only the ten ordinals reported non-null by the v1.0.27 E65 lookup. v1.0.29 and v1.0.30 are separate controls: each adds only ordinal 222 or only ordinal 223, respectively, while the other division helper remains local. v1.0.31 adds only the four MDA imports to v1.0.28's known-good ten-ordinal DRT baseline. No candidate enables gameplay playback or MIDI.

## Packages and Nokia E65 results

| Version | Variant | Import/runtime delta | SIS bytes | Nokia E65 result |
|---|---|---|---:|---|
| 1.0.22 | No-audio/no-DRT baseline | No static `drtaeabi.dll` or `mediaclientaudio.dll` | 741,360 | **User-reported: works as expected** |
| 1.0.23 | Native-leaves/DRT-only probe | `drtaeabi.dll` plus native-leave/personality scaffolding and additional EUser imports | 743,624 | **User-reported: “Feature not supported”** |
| 1.0.24 | Native-leaves/DRT + MDA import probe | v1.0.23 path plus `mediaclientaudio.dll` ordinals [15, 35, 36, 42] | 744,476 | **User-reported: “Feature not supported”** |
| 1.0.25 | DRT-proxy/no-native-leaves probe | Static `drtaeabi.dll` import with 12 ARM EABI/helper ordinals; no native-leave/personality objects or EUser import delta | 740,368 | **User-reported: “Feature not supported”** |
| 1.0.26 | Dynamic DRT-helper lookup probe | No static DRT import; calls `RLibrary::Load`/`Lookup` for those 12 ordinals; adds EUser [1308, 1311, 1838] | 741,840 | **User-reported: works; DRT labels unreadable, exact values unknown** |
| 1.0.27 | Readability-only revision of v1.0.26 | Same Load/Lookup sequence and static imports; dedicated panel replaces older status text | 739,848 | **User-reported: load error 0; 10/12; mask 3FF (ordinals 222 and 223 missing)** |
| 1.0.28 | Ten-ordinal static DRT-import control | Static `drtaeabi.dll` imports only 135, 139, 140, 141, 142, 147, 148, 155, 157, 221; excludes 222/223 | 740,660 | **User-reported: launched on Nokia E65** |
| 1.0.29 | Single ordinal-222 static-import control | v1.0.28 ten ordinals + `222 __aeabi_idiv`; `__aeabi_uidiv` local | 740,620 | **User-reported: “Feature not supported”** |
| 1.0.30 | Single ordinal-223 static-import control | v1.0.28 ten ordinals + `223 __aeabi_uidiv`; `__aeabi_idiv` local | 740,840 | **User-reported: “Feature not supported”** |
| 1.0.31 | MDA import-only on v1.0.28 | v1.0.28 ten DRT ordinals + `mediaclientaudio.dll` [15, 35, 36, 42]; no native leaves; MDA API gate false | 740,924 | **Not yet tested on E65** |

Downloads: [v1.0.22 baseline](https://drive.google.com/file/d/12LFx5fGrUhy28c3pTkKDs9PzXFt_yKUm/view?usp=drivesdk) · [v1.0.23 DRT-only](https://drive.google.com/file/d/1VtqRmMdDG1VVWJQVQ5gNzS9v1Gk3uJT4/view?usp=drivesdk) · [v1.0.24 DRT+MDA](https://drive.google.com/file/d/1rhJzecHwYvSNZSXj6w3Wbw6OraD8ccnj/view?usp=drivesdk) · [v1.0.25 DRT proxy/no native leaves](https://drive.google.com/file/d/13hWfbu7-Qj8k9hLmj7r1ssiXqgnl66ZI/view?usp=drivesdk) · [v1.0.26 dynamic DRT lookup](https://drive.google.com/file/d/1kipGIy5t-MbSuHL1mpR1fDP9fPlzmVmx/view?usp=drivesdk) · [v1.0.27 readable DRT panel](https://drive.google.com/file/d/1WPgZE_fNdN5qCMj_vwVKXlV1LEFgGYVG/view?usp=drivesdk) · [v1.0.28 ten-ordinal static import control](https://drive.google.com/file/d/1mXfVd9lVIz8m7TzkFjRpqvhZYTjkB9U3/view?usp=drivesdk) · [v1.0.29 ordinal-222 control](https://drive.google.com/file/d/1CscxgVwC4UbsIPE1kdESASDBT4SuPi48/view?usp=drivesdk) · [v1.0.30 ordinal-223 control](https://drive.google.com/file/d/1IZnjvqYVI_gCkVt9gMp1YUCibMmh-i0V/view?usp=drivesdk) · [v1.0.31 MDA import-only control](https://drive.google.com/file/d/1bc9NUYZ73_TQke7UljWBdqAkg_M19pOv/view?usp=drivesdk)

All ten packages use ARMv5T/EKA2, OMAP1710, DSA ARGB32, the same package UID, and the same Level 1 payload set. All 18 non-E32 payloads are byte-identical across v1.0.22–v1.0.31, including the three retained audio files. The no-audio v1.0.22 E32 is byte-identical to v1.0.20. v1.0.27's E32 static imports are identical to v1.0.26; v1.0.28, v1.0.29, and v1.0.30 change only the executable import/runtime control and package version; v1.0.31 adds the MDA import-only control. Every non-E32 payload remains byte-identical.

## What the device reports establish so far

The user reports that **v1.0.22 works as expected**, while **v1.0.23, v1.0.24, and v1.0.25 show “Feature not supported.”** For v1.0.23 and v1.0.24, the popup appears before the app screen; v1.0.25 reproduces the same popup. The v1.0.24 MDA imports are not required to reproduce the symptom. v1.0.25 also reproduces it without native-leave/personality support or the extra EUser import set seen in v1.0.23. The user reports that **v1.0.26 works**, but its mixed diagnostic labels were unreadable, leaving that run's `Load()`/lookup outcome unknown.

For v1.0.27, the user reports `DLL LOAD ERROR: 0`, `ORDINALS FOUND: 10/12`, and `ORDINAL MASK: 3FF`. The zero error means `RLibrary::Load("drtaeabi.dll")` succeeded on that run. Bits 0–9 are set; bits 10–11 are clear, corresponding to ordinals 222 (`__aeabi_idiv`) and 223 (`__aeabi_uidiv`). Thus the other ten lookups returned non-null, and these two returned null. This establishes the reported lookup results on that E65 run only; no helper was invoked.

The earlier contrast still associates the popup with the static DRT-proxy/import configurations, but it does **not** prove the exact cause, explain the missing two lookups, or show that v1.0.25's static import fixups are accepted. v1.0.26 adds three EUser imports and continues into the app if `RLibrary::Load()` fails; its successful app run alone did not establish loader success.

## Fourth probe: DRT proxy without native leaves

v1.0.25 links the SDK `Symbian::CxxAbi` (`drtaeabi.dso`) while leaving native leaves, C++ exceptions/RTTI, the MDA probe, gameplay audio, and MIDI disabled. Its E32 contains these 12 `drtaeabi.dll` imports:

`135 __aeabi_idivmod`, `139 __aeabi_ldivmod`, `140 __aeabi_llsl`, `141 __aeabi_llsr`, `142 __aeabi_lmul`, `147 __aeabi_memcpy4`, `148 __aeabi_memcpy8`, `155 __aeabi_uidivmod`, `157 __aeabi_uldivmod`, `221 abort`, `222 __aeabi_idiv`, and `223 __aeabi_uidiv`.

The EUser ordinal set matches v1.0.22 exactly. The native-leave probe source and SDK personality objects are not linked; the DRT exception/unwind ordinals seen in v1.0.23 are absent. The linker retains ordinary ARM EABI/runtime imports required by this image, so v1.0.25 is a **12-import DRT control**, not a one-symbol DLL-presence toggle.

### v1.0.25 E65 result and interpretation

The user reports that v1.0.25 shows **“Feature not supported.”** This confirms the symptom also occurs with the 12-import DRT-proxy configuration, even without native-leave/personality objects or the extra EUser imports from v1.0.23. The DRT import/runtime path remains the strongest common lead. The result does not prove the DLL itself is missing, distinguish among the 12 ordinals, or isolate the exact cause.

## Fifth probe: dynamic DRT helper lookup (v1.0.26)

v1.0.26 uses the default-off `QUANTUM_LEVEL41_IMPORT_PROBE_DRT_LOOKUP` build option. After the startup window is drawn and before the scene is loaded, it calls `RLibrary::Load("drtaeabi.dll")`, performs `RLibrary::Lookup()` for the 12 ordinals listed above, and calls `RLibrary::Close()` if loading succeeds. It does not invoke any resolved helper. The original status string was `DRT LOAD <error> FOUND <count>/12 MASK <hex>`; the user reports that it was mixed with older diagnostic lines and unreadable.

The v1.0.26 E32 has **no static `drtaeabi.dll` import**. Compared with working v1.0.22, the EUser import table adds only 1308 (`RLibrary::Load(const TDesC&, const TDesC&)`), 1311 (`RLibrary::Close()`), and 1838 (`RLibrary::Lookup(TInt) const`); no EUser ordinal is removed. MDA/MIDI imports, native-leave/personality objects, and gameplay audio are off. The OMAP1710 DSA ARGB32 renderer/presenter is retained, and every non-E32 payload matches v1.0.25.

**User-reported E65 result:** v1.0.26 works. Because its overlay is unreadable and the app continues when `RLibrary::Load()` returns an error, the device run confirms app launch only—not whether the dynamic load or any lookup succeeded.

## v1.0.27 readability-only revision

v1.0.27 keeps the v1.0.26 probe call sequence, E32 import table, scene/render path, DSA ARGB32 configuration, and payloads. Its GDI and DSA ARGB bitmap overlays now suppress the previous diagnostic text in the probe build and draw a dedicated dark panel with these separate lines:

```text
DRT HELPER LOOKUP
DLL LOAD ERROR: <error>
ORDINALS FOUND: <count>/12
ORDINAL MASK: <hex>
```

The walk-control and exit hints remain at the bottom. All 18 non-E32 payloads match v1.0.26 byte-for-byte; the E32 is smaller because the older overlay text/display path is replaced. The E32 import sets are unchanged. The v1.0.27 SIS passed the local audit of all 19 payloads and was uploaded to Drive; see its `BUILD-REPORT.md`, `payload-manifest.json`, and `sis-audit.json` in the version directory.

The device readout is now available: load error `0`, 10/12 found, mask `0x3FF`; the two misses are ordinals 222 and 223. It does **not** prove that calling the helpers works, that v1.0.25's static import fixups are accepted, or why v1.0.25 showed the popup. The null lookups do not by themselves show whether those exports are absent from other firmware/DLL versions or explain the earlier static-import failure.

The user also reports that both v1.0.29 and v1.0.30 show the exact “Feature not supported” popup. Each candidate adds only one of the two missing lookup ordinals to the working v1.0.28 import set. This is consistent with either ordinal-specific static import path being a problem, but the builds also differ in how the division helper is resolved locally; the report does not establish the loader's exact failure point.

v1.0.31 is a new MDA import-only control built on the user-reported launched v1.0.28 baseline. Its E32 keeps the exact same ten DRT ordinals and adds only `mediaclientaudio.dll` ordinals `[15, 35, 36, 42]` (`SetPosition`, `NewL`, `Close`, `OpenFileL` in the SDK proxy definition). No other DLL import block changes. Native-leave/personality support, gameplay audio and MIDI are off. MDA references are behind the default-false volatile gate; the normal path does not create a player, open a file, or call `Play()`, and no guessed audio resource path is used. The SIS passed the local 19-payload audit; all 18 non-E32 payloads match v1.0.28. **E65 result pending.** This is an import/launch test only, not playback or codec evidence.

## Sixth import control: static ten-ordinal DRT proxy (v1.0.28)

v1.0.28 builds a custom import proxy for `drtaeabi.dll` from only the ten exports reported non-null by the v1.0.27 E65 lookup: ordinals **135, 139, 140, 141, 142, 147, 148, 155, 157, and 221**. The generated proxy and final E32 were independently inspected; the E32 contains exactly that DRT ordinal set and excludes **222 (`__aeabi_idiv`) and 223 (`__aeabi_uidiv`)**. No static MDA/MIDI import is present. The EUser import set remains identical to the working v1.0.22 baseline. Gameplay audio and MIDI are disabled, and the E65 OMAP1710 DSA ARGB32/Level 1 package path is preserved.

The SDK legacy EKA2 runtime supplies local `__aeabi_idiv` and `__aeabi_uidiv` implementations so the program remains linkable without importing those two DRT ordinals. The probe adds no direct call into a DRT helper and performs no dynamic lookup; ordinary compiler/runtime references may still use the imported runtime services. This local resolution is a build difference that must be considered when interpreting the result.

The unsigned SIS is **740,660 bytes**, SHA-256 `7cc5206d07cffc1768dcd52c7988410f08fcbef7a234cbd60a1b48880e1025bb`, MD5 `232b9054434eae764627761961be260c`. It was uploaded to Drive and passed the 19-payload SIS audit; all 18 non-E32 payloads match v1.0.27 byte-for-byte. The user reports that it launched on the Nokia E65. This favors, but does not prove, the missing 222/223 imports as a factor in v1.0.25: v1.0.28 also supplies those division helpers locally and uses a custom ten-export proxy. The result confirms launch for this candidate only; it does not establish helper execution or playback behavior.

## Seventh import control: ordinal 222 only (v1.0.29)

v1.0.29 starts from v1.0.28's ten static DRT imports and adds **only ordinal 222 (`__aeabi_idiv`)**. Ordinal 223 (`__aeabi_uidiv`) stays local to the filtered SDK legacy EKA2 runtime. The final `drtaeabi.dll` table is exactly `135, 139, 140, 141, 142, 147, 148, 155, 157, 221, 222`; its custom proxy exports those same eleven ordinals. The linked ELF has a local `__aeabi_uidiv` definition and an unresolved `__aeabi_idiv` reference routed through the proxy. All other DLL import blocks match v1.0.28, and the EUser imports match v1.0.22.

The unsigned SIS is **740,620 bytes**, SHA-256 `4d71a0890ab00ff0e9820d553c223954f1ba42ea4dbd1c9e63878eaf86f09978`, MD5 `0042da2ddda2c714c115065b2478eaa8`. It passed the audit of all 19 payloads; all 18 non-E32 payloads match v1.0.28 byte-for-byte. Audio/MIDI remain disabled, and the retained audio files are only package payloads. **User-reported Nokia E65 result: “Feature not supported”.**

## Eighth import control: ordinal 223 only (v1.0.30)

v1.0.30 separately starts from the same ten static DRT imports and adds **only ordinal 223 (`__aeabi_uidiv`)**. Ordinal 222 (`__aeabi_idiv`) stays local to the SDK legacy EKA2 runtime. The final `drtaeabi.dll` table is exactly `135, 139, 140, 141, 142, 147, 148, 155, 157, 221, 223`; its custom proxy exports those same eleven ordinals. The linked ELF has a local `__aeabi_idiv` definition and an unresolved `__aeabi_uidiv` reference routed through the proxy. To preserve the idiv implementation's internal `__udivsi3` dependency without satisfying public uidiv references locally, the temporary filtered archive keeps that implementation but renames its public `__aeabi_uidiv` alias. All other DLL import blocks match v1.0.28; the EUser imports match v1.0.22.

The unsigned SIS is **740,840 bytes**, SHA-256 `9b7c2d7c2957efbec0d93878e55e75753810e56aea4fc6cf40249fbe80223647`, MD5 `5aa50f273fcc053425603b44c53b93c8`. It passed the audit of all 19 payloads; all 18 non-E32 payloads match v1.0.28 byte-for-byte. Audio/MIDI remain disabled, and the retained audio files are only package payloads. **User-reported Nokia E65 result: “Feature not supported”.**

These are two separate launch-only tests: neither package combines ordinals 222 and 223, performs a dynamic lookup, or explicitly invokes a DRT helper. Static imports and SIS audits do not predict whether the Nokia E65 loader will accept the additional ordinal.

## Interpretation limits and scope

- v1.0.23 combines native-leave/personality support with its associated `drtaeabi.dll`/EUser import surface.
- v1.0.24 adds `mediaclientaudio.dll` ordinals 15, 35, 36, and 42 (`SetPosition`, `NewL`, `Close`, `OpenFileL`) on top of that path. Those references are behind an `EFalse` gate, and no `Play()` call is present.
- v1.0.25 statically imports 12 DRT helpers. v1.0.26 and v1.0.27 instead ask the loader to load the named DLL and look up those same ordinal numbers dynamically; their static import tables contain no DRT DLL. v1.0.28 statically imports only the ten v1.0.27 lookup hits, while `__aeabi_idiv` and `__aeabi_uidiv` resolve from the local SDK runtime.
- Audio assets (`wth.mp3`, `fn2000.wav`, `r.wav`) are included only to keep package payloads constant. No candidate plays audio or enables MIDI. v1.0.31's `mediaclientaudio.dll` import references remain behind a default-false gate; the normal path creates no player and opens no file.
- Static imports and host audits do not prove E65 ROM availability, loader acceptance, successful helper execution, codec support, direct DSP access, or playback behavior. DSA ARGB32 compile flags likewise do not prove which presenter the phone selects.

## Build validation

The v1.0.22–v1.0.31 candidates cross-built with the restored SDK 0.3.0 toolchain. The v1.0.28 proxy exposes its ten declared exports; v1.0.29 and v1.0.30 each expose the same ten plus exactly one selected division ordinal. All packages passed the local SIS decompression/hash/package-target audit for 19 payloads. Both new E32 import tables, proxy export lists, and ordinal isolation checks were verified before SIS packaging. `PATH=/home/user/quantum-sdk-cache/pyenv/bin:$PATH make -C symbian9 test` passed for the v1.0.28 source candidate, including both core suites (149,093 checks each), Python suites, asset/audio, heap, SID, key-up, and presentation checks. Host tests were not rerun for v1.0.29/.30 because their C++ sources are unchanged; their separate SIS and E32 import audits passed. v1.0.31 was built with Clang 23.1.2 and CMake 3.30.5; its only additional Symbian source is the false-gated MDA import probe. Host tests were not rerun for that import/package-only control. Its SIS audit passed, and its E32 import table was verified as v1.0.28 plus only MDA ordinals [15, 35, 36, 42]. Detailed build reports, manifests, logs, and audits are in each version directory.

## Device result

The user reports that v1.0.28 launched on the Nokia E65. Treat this as a successful launch of this particular static-import control, not proof that 222/223 alone caused v1.0.25's popup: v1.0.28 also resolves the division helpers locally and uses a custom ten-export proxy. Playback and MIDI remain disabled, and successful helper execution was not tested. v1.0.29 and v1.0.30 were separately packaged and audited; the user reports that both show “Feature not supported”. v1.0.31 is packaged and audited but has no E65 result yet; it changes the MDA import set only, with playback disabled.
