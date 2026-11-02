# E65 launch/import diagnostic matrix

## Goal

Compare the launch-only import variants for the already-reported **“Feature not supported”** popup. v1.0.22 is the no-audio/no-DRT baseline; v1.0.23 adds native-leaves/DRT; v1.0.24 adds MDA imports; v1.0.25 links the complete DRT import proxy without native-leave/personality scaffolding; and v1.0.26 dynamically loads `drtaeabi.dll` and looks up v1.0.25's 12 helper ordinals. **v1.0.27 is a readability-only revision** of that lookup probe. v1.0.28 is a new static-import control that links only the ten ordinals reported non-null by the v1.0.27 E65 lookup and excludes 222/223. No candidate enables gameplay playback or MIDI.

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

Downloads: [v1.0.22 baseline](https://drive.google.com/file/d/12LFx5fGrUhy28c3pTkKDs9PzXFt_yKUm/view?usp=drivesdk) · [v1.0.23 DRT-only](https://drive.google.com/file/d/1VtqRmMdDG1VVWJQVQ5gNzS9v1Gk3uJT4/view?usp=drivesdk) · [v1.0.24 DRT+MDA](https://drive.google.com/file/d/1rhJzecHwYvSNZSXj6w3Wbw6OraD8ccnj/view?usp=drivesdk) · [v1.0.25 DRT proxy/no native leaves](https://drive.google.com/file/d/13hWfbu7-Qj8k9hLmj7r1ssiXqgnl66ZI/view?usp=drivesdk) · [v1.0.26 dynamic DRT lookup](https://drive.google.com/file/d/1kipGIy5t-MbSuHL1mpR1fDP9fPlzmVmx/view?usp=drivesdk) · [v1.0.27 readable DRT panel](https://drive.google.com/file/d/1WPgZE_fNdN5qCMj_vwVKXlV1LEFgGYVG/view?usp=drivesdk) · [v1.0.28 ten-ordinal static import control](https://drive.google.com/file/d/1mXfVd9lVIz8m7TzkFjRpqvhZYTjkB9U3/view?usp=drivesdk)

All seven packages use ARMv5T/EKA2, OMAP1710, DSA ARGB32, the same package UID, and the same Level 1 payload set. All 18 non-E32 payloads are byte-identical across v1.0.22–v1.0.28, including the three retained audio files. The no-audio v1.0.22 E32 is byte-identical to v1.0.20. v1.0.27's E32 static imports are identical to v1.0.26; v1.0.28 changes only the executable import/runtime control while preserving every non-E32 payload.

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

## Sixth import control: static ten-ordinal DRT proxy (v1.0.28)

v1.0.28 builds a custom import proxy for `drtaeabi.dll` from only the ten exports reported non-null by the v1.0.27 E65 lookup: ordinals **135, 139, 140, 141, 142, 147, 148, 155, 157, and 221**. The generated proxy and final E32 were independently inspected; the E32 contains exactly that DRT ordinal set and excludes **222 (`__aeabi_idiv`) and 223 (`__aeabi_uidiv`)**. No static MDA/MIDI import is present. The EUser import set remains identical to the working v1.0.22 baseline. Gameplay audio and MIDI are disabled, and the E65 OMAP1710 DSA ARGB32/Level 1 package path is preserved.

The SDK legacy EKA2 runtime supplies local `__aeabi_idiv` and `__aeabi_uidiv` implementations so the program remains linkable without importing those two DRT ordinals. The probe adds no direct call into a DRT helper and performs no dynamic lookup; ordinary compiler/runtime references may still use the imported runtime services. This local resolution is a build difference that must be considered when interpreting the result.

The unsigned SIS is **740,660 bytes**, SHA-256 `7cc5206d07cffc1768dcd52c7988410f08fcbef7a234cbd60a1b48880e1025bb`, MD5 `232b9054434eae764627761961be260c`. It was uploaded to Drive and passed the 19-payload SIS audit; all 18 non-E32 payloads match v1.0.27 byte-for-byte. The user reports that it launched on the Nokia E65. This favors, but does not prove, the missing 222/223 imports as a factor in v1.0.25: v1.0.28 also supplies those division helpers locally and uses a custom ten-export proxy. The result confirms launch for this candidate only; it does not establish helper execution or playback behavior.

## Interpretation limits and scope

- v1.0.23 combines native-leave/personality support with its associated `drtaeabi.dll`/EUser import surface.
- v1.0.24 adds `mediaclientaudio.dll` ordinals 15, 35, 36, and 42 (`SetPosition`, `NewL`, `Close`, `OpenFileL`) on top of that path. Those references are behind an `EFalse` gate, and no `Play()` call is present.
- v1.0.25 statically imports 12 DRT helpers. v1.0.26 and v1.0.27 instead ask the loader to load the named DLL and look up those same ordinal numbers dynamically; their static import tables contain no DRT DLL. v1.0.28 statically imports only the ten v1.0.27 lookup hits, while `__aeabi_idiv` and `__aeabi_uidiv` resolve from the local SDK runtime.
- Audio assets (`wth.mp3`, `fn2000.wav`, `r.wav`) are included only to keep package payloads constant. No candidate plays audio or enables MIDI.
- Static imports and host audits do not prove E65 ROM availability, loader acceptance, successful helper execution, codec support, direct DSP access, or playback behavior. DSA ARGB32 compile flags likewise do not prove which presenter the phone selects.

## Build validation

The v1.0.22–v1.0.28 candidates cross-built with the restored SDK 0.3.0 toolchain; v1.0.28's custom proxy exposes only its ten declared exports. All packages passed the local SIS decompression/hash/package-target audit for 19 payloads. The v1.0.28 E32 import table, custom proxy export list, and excluded division ordinals were checked before SIS packaging/upload. `PATH=/home/user/quantum-sdk-cache/pyenv/bin:$PATH make -C symbian9 test` passed, including both core suites (149,093 checks each), Python suites, asset/audio, heap, SID, key-up, and presentation checks. The detailed v1.0.28 build logs, `BUILD-REPORT.md`, `payload-manifest.json`, and audit JSON files are in its version directory; previous candidate links and results are recorded above.

## Device result

The user reports that v1.0.28 launched on the Nokia E65. Treat this as a successful launch of this particular static-import control, not proof that 222/223 alone caused v1.0.25's popup: v1.0.28 also resolves the division helpers locally and uses a custom ten-export proxy. Playback and MIDI remain disabled, and successful helper execution was not tested.
