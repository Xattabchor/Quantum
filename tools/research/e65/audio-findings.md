# Nokia E65 (RM-208): ROM-backed audio findings

**Status:** This report combines offline ROM analysis, a focused execution of real ROM audio code in Unicorn, and user-supplied E65 probe results. The earlier capability probe and a later v1.0.0 PCM `WriteL` test were run on one phone; the user heard no test tone, and the 11.025 kHz playback case was not run. The MDA physical route and DAC clock remain unknown. The ROM was not booted as a complete Symbian system, and no driver was loaded.

## Bottom line

For a normal E65 application, the defensible path is the public **MDA/MMF → DevSound → Nokia adaptation/routing** stack. Keep the project’s existing `CMdaAudioPlayerUtility`/MDA path as the primary route. For a software mixer, use `CMdaAudioOutputStream` (or the public DevSound API), and negotiate a format that the running E65 reports as supported. Do **not** configure McBSP/DMA registers, load `ehwa.ldd`/`ehwaomap.pdd`, or call `DspMemMapper` ordinals from the application.

The dump establishes that the E65 firmware contains a substantial audio policy, stream, DevSound, DSP-bridge and DSP-codec stack. The dump alone does **not** establish the current output route or physical sample rate. The phone's public probe reported a runtime DevSound mask and accepted two MDA opens; the separate v1.0.0 PCM test returned successful 8 kHz `WriteL`/copy results but the user heard nothing. A new v1.0.1 candidate requests half-scale MDA stream volume and waits for natural completion, but it still needs a repeat device run. Unicorn confirms only the ROM's sample-rate-code conversion; its conversion table is not a device capability or DAC-clock report.

## Scope and integrity

- Input: `google_drive/romdumpplus_090549.dmp`, 19,013,632 bytes (`0x1222000`), SHA-256 `3b4ba70bab1a6b17f214ae1c91a279520fe82254f62e39cfa4cb8cb7ae3724fa`.
- Header values: ROM base `0xF8000000`, declared size `0x1300000`, root-list pointer `0xF8008F40`, root variant `0x09080004`. The dump is **0xDE000 (909,312) bytes shorter than its declared ROM size**; why is unknown.
- The bounds-checked parser walked the entire directory tree represented in the supplied bytes and extracted all 935 logical files: 945 tree records, 10 directories, 900 XIP images, zero invalid file ranges, zero parser errors. Every referenced logical payload is in the captured range. This validates the parsed tree and files, but it does not fill the missing declared tail or prove the dump is a complete physical ROM image.
- Full extraction: `google_drive/e65-rom-full/` (ignored research output, about 30 MB). Keyword-filtered strings from all extracted files: `google_drive/e65-rom-analysis/full-audio-strings.txt`.
- Tools/checks: `tools/research/e65/rom_extract.py`; `python3 -m unittest discover -s tools/research/e65 -v` — 2 tests pass. Selected ARM/Thumb code was inspected with Capstone 5.0.9 in `google_drive/e65-venv`.

## What the ROM directly shows

### Public MMF/MDA and device-server layer

| ROM path | ROM entry offset | Image body size | Exports | Relevant evidence |
|---|---:|---:|---:|---|
| `/Sys/Bin/MediaClientAudio.dll` | `0xA10680` | `0x880C` | 160 | Contains `CMdaAudioPlayerUtility`; the restored SDK `.def` maps `OpenFileL(const TDesC16&)` to ordinal 42, which the project’s MDA player code uses. |
| `/Sys/Bin/MMFDevSound.dll` | `0x28FC60` | `0x1B04` | 48 | Implements public `CMMFDevSound` entry points. SDK ordinal map identifies `SetConfigL` as 3, `Capabilities` as 8, and `PlayInitL` as 35. Disassembled wrappers delegate into the DevSound body; they are not register-level audio controls. |
| `/Sys/Bin/MmfAudioServer.exe` | `0x29A0B0` | `0x0E24` | 0 | RTTI contains `CMMFAudioServer` and `CStartAndMonitorDevSoundThread`. |
| `/Sys/Bin/MmfDevSoundServer.dll` | `0x2917E0` | `0x5F4C` | 4 | RTTI contains `CMMFDevSoundServer`, `CMMFDevSoundSession`, `MAudioPolicyObserver`, `MMMFHwDeviceObserver`, and `MAudioRoutingObserver`. |
| `/Sys/Bin/DevSoundAdaptationLayer.dll` | `0x29C670` | `0x11E4` | 12 | Device-adaptation component is present in ROM. |
| `/Sys/Bin/AudioStream.dll` | `0x1FE9E0` | `0x1010` | 135 | RTTI includes logical/physical audio stream classes and managers. |
| `/Sys/Bin/AudioServer.dll` | `0x42B420` | `0xD310` | 61 | Contains audio server, stream, tone, effect and resource-manager class names. |
| `/Sys/Bin/audiopolicyserver.dll` | `0x2005F0` | `0x8E80` | 8 | Contains policy, routing, profile and routing-limitation classes. |
| `/Sys/Bin/AudioOutputRouting.dll` | `0xBCC6E0` | `0x0224` | 8 | Routing-related module is present. |
| `/Sys/Bin/Aamm.dll`, `/Sys/Bin/Lac.dll`, `/Sys/Bin/NokiaDSY.DLL` | `0x1F9580`, `0x1FBCC0`, `0x225710` | `0x26BC`, `0x2CA0`, `0xF08C` | 53, 24, 1 | Nokia-specific class names include `CAammAudio`, `CLacAudio`, `CLacPhysicalAudioStreamManager`, and `CLDSYAudioMapper`/`CLDSYAudioServiceLogic`. These names establish components, not their complete call order. |

The resulting architecture is consistent with the public Symbian model: MDA/MMF client APIs sit above DevSound; DevSound is the device abstraction/adaptation boundary. The SDK header says `CMMFDevSound::Capabilities()` returns supported rate/encoding/channel/buffer settings and `SetConfigL()` applies a selected configuration. It describes initial DevSound operation as PCM16 at 8 kHz, with mono as the default in the capability definitions. Those are **SDK defaults**, not a measured E65 output capability report.

### Sample-rate conversion code found in `AudioServer.dll`

A Thumb routine at `AudioServer.dll` code offset `+0x705A` (`0xF84324F2`) switches on the `TMMFSampleRate` bit values and returns integer Hertz. Its literal constants and immediates match the SDK enum:

| `TMMFSampleRate` bit | Rate translated by the routine |
|---:|---:|
| `0x00000001` | 8,000 Hz |
| `0x00000002` | 11,025 Hz |
| `0x00000004` | 16,000 Hz |
| `0x00000008` | 22,050 Hz |
| `0x00000010` | 32,000 Hz |
| `0x00000020` | 44,100 Hz |
| `0x00000040` | 48,000 Hz |
| `0x00000080` | 88,200 Hz |
| `0x00000100` | 96,000 Hz |
| `0x00000200` | 12,000 Hz |
| `0x00000400` | 24,000 Hz |
| `0x00000800` | 64,000 Hz |

The table entries at `code+0x72A0..0x72BC` were cross-referenced from Thumb PC-relative literal loads; the other rates are formed by immediate arithmetic. This is solid evidence that the ROM has conversion logic for the standard MMF rate codes. It is **not** evidence that E65 hardware advertises all twelve values: the list includes rates up to 96 kHz, so the conversion table plainly cannot stand in for a device capability query. The code was then executed in Unicorn: all twelve single-bit flags returned the listed Hertz values, and the routine's real import veneer reached `AudioStream.dll` export 111. The 0, multi-bit and runtime-mask inputs returned zero as expected for a single-code translator. Reproducible details and the boot limitation are in `tools/research/e65/audio-unicorn-results.md`.

### DSP and kernel-side multimedia components

- `/Sys/Bin/DspMemMapper.dll` (`offset 0x44C8E0`, body `0x538`, 11 exports), `/Sys/Bin/ehwaomap.pdd` (`0x45A0A0`, body `0x1776C`) and `/Sys/Bin/ehwa.ldd` (`0x471890`, body `0x17F4`) are present.
- `ehwaomap.pdd` contains DSP Bridge, shared-memory, DSP MMU/reset, mailbox and DSP-frequency markers. `ehwa.ldd` contains the literal “MultimediaDD capability is checked by DSP Bridge LDD”; its ROM image capability mask is `0x000FFFFF`. `DspMemMapper.dll`/`ehwa*` masks are kernel/component metadata, **not** an app entitlement or recommendation to use those interfaces.
- `/resource/plugins/baseimage.dof` includes `.dspbridge_symbols` and `.dspbridge_mem`; DSP-side `pcmdll.dll55L`, `mp3dyn.dll55L`, `mpeg4aac_dec_dyn.dll55L` and other `.dll55L` plugins contain node/decoder symbols. This confirms a DSP software/plugin ecosystem in the ROM. It does not prove that a third-party game can schedule arbitrary DSP work, nor that ordinary MDA playback uses a particular codec on the DSP.
- `MCBSPSPI.DLL` has `McBspSpi` and SPI receive/transmit/resource-handler strings. Its presence is not proof that the E65 audio output path is an application-accessible McBSP interface.

## Recommended configuration for this project

1. **Keep the current MDA player as the primary path.** `CMdaAudioPlayerUtility` is the right high-level choice for playing existing music/effect files; let the OS controller, policy and DevSound adaptation decode and route them. Do not bolt on a private hardware route or force a sample rate for file playback.
2. **For a real-time software mixer, keep it as an optional MDA stream experiment.** `CMdaAudioOutputStream` is the public raw-PCM streaming API. The SDK says it defaults to PCM16 unless a different data type is explicitly requested, and its callback contract is asynchronous. Choose the rate/channel count from the capabilities the target phone actually reports; set a single supported rate (do not OR multiple rate codes), request mono/stereo as a single supported mode, and handle `Open`/completion errors. Do not change the stream format after streaming has started.
3. **Treat 11,025 Hz stereo as a candidate, not an E65 fact.** The current SNDDMA-side output source requests 11,025 Hz stereo through `CMdaAudioOutputStream`; its mixer/resampler runs in software. This is not direct DMA and does not offload mixing to the DSP. The ROM’s rate converter shows that the rate code is understood by the framework, but only an E65 runtime capability/open test can validate that combination.
4. **Use 8 kHz mono PCM16 only as a basic smoke-test candidate, not as the claimed optimal game setting.** It is the documented DevSound initialization/default example in the SDK. For higher-quality audio, select another rate only after the device reports it and a stream-open test succeeds.
5. **Do not call `DspMemMapper.dll` by guessed ordinal, read the `0xFFFE1000` physical address, or install/use the EHWA LDD/PDD for ordinary playback.** Drive notes and scripts proposing such probes are unverified hypotheses. The ROM shows a privileged DSP Bridge boundary and a `MultimediaDD` check; no public third-party DSP audio API or safe device-side access contract was established here.

The project’s current normal MDA player path (`symbian9/level41_device_test/quantum_level41_audio.cpp`) and separate stream prototype (`symbian9/level41_snddma_port/quantum_snddma_output.cpp`) were read for context; neither was modified. The latter explicitly requests 11,025 Hz stereo and remains experimental. Keep MIDI disabled as already requested.

## Public-API probe status and E65 runtime result

A separate app lives in `symbian9/level41_devsound_caps_diag/`. It queries `CMMFDevSound::Capabilities()` and `Config()` after public `InitializeL(..., EMMFStatePlaying)`, then conditionally opens 8 kHz mono and 11.025 kHz stereo PCM16 MDA streams only when all requested flags are present. It performs no `PlayInitL`, `PlayData`, `WriteL`, tone, recording, driver, DSP, or direct-DMA operation. The optional MDA stream DLL is runtime-loaded. The restored SDK export definition maps the relevant `CMdaAudioOutputStream::NewL(observer, CMdaServer*)` overload to ordinal 3.

The user supplied a readable photo of the final E65 screen. It reports `InitializeComplete=0`, `Capabilities().iRate=0x0000067F`, encoding `0x00000002`, channels `0x00000003`, and buffer size `4100`. The returned `Config()` is rate `0x00000001` (8 kHz), encoding `0x00000002` (PCM16), channels `0x00000001` (mono), buffer size `0`. The screen's decoded rates are 8,000, 11,025, 12,000, 16,000, 22,050, 24,000, 32,000, 44,100, and 48,000 Hz. `mediaclientaudiostream.dll` load and ordinal-3 lookup both returned `0`; both 8,000 Hz mono PCM16 and 11,025 Hz stereo PCM16 MDA `Open` callbacks returned `0`. Exact transcription: `symbian9/level41_devsound_caps_diag/RUNTIME_RESULTS.md`.

This open-only result is runtime evidence that the tested E65's public API advertised those formats in this initialized playback context and accepted both MDA open requests. In particular, 11,025 Hz stereo PCM16 is **open-validated** on this device, so the SNDDMA experiment's requested format is viable at the MDA-open stage. That original screen did not call `WriteL`/`PlayData` and did not test audibility or route. `Config()`'s 8 kHz mono values do not prove the physical DAC clock or imply that the normal MDA file player should be forced to that rate.

The ARMv5T/EKA2 open-only executable has zero capability words and statically imports only `estlib.dll`, `euser.dll`, `gdi.dll`, `mmfdevsound.dll`, and `ws32.dll`. The signed test SISX is [in Drive](https://drive.google.com/file/d/13aOmV-bVB2-YpaBHHt_MX0H3TKjzvAcK/view?usp=drivesdk); the build audit is `symbian9/level41_devsound_caps_diag/AUDIT.json`. The runtime transcription is [also in Drive](https://drive.google.com/file/d/1MjuWaqYMeSTReHES1U_tfr_C52jn2fGp/view?usp=drivesdk) and in `symbian9/level41_devsound_caps_diag/RUNTIME_RESULTS.md`. The earlier Drive archive `e65-caps-decision.zip` remains unrelated offline synthetic LDD loader-capability work, not an audio measurement.

A separate v1.0.0 public MDA playback candidate subsequently called `WriteL` for 8 kHz mono PCM16. The user-provided screen showed `O:0`, `W:0`, and `Copy 0 8000/8000`, followed by `P:-3` after the probe requested `Stop`; the user heard no sound. No headset was connected and ordinary music works through the phone speaker, but the test's MDA endpoint and phone media-volume setting are unknown. The 11.025 kHz stereo playback test was not reached. Candidate v1.0.1 now requests `MaxVolume()/2` after open and waits for the natural playback-complete callback; it is not yet device-tested. See `symbian9/level41_devsound_pcm_playback_probe/RUNTIME_RESULTS.md`.

Keep the existing MDA player as the main game path and SNDDMA as a separate experiment. The remaining step is to rerun the v1.0.1 `WriteL` candidate with its half-maximum stream-volume request and natural-completion wait, then report whether each tone is audible, on which route, and at what phone media-volume setting. API callbacks and Unicorn results remain distinct from physical sound.

## Confirmed vs. unknown

### Confirmed from the supplied ROM/SDK and user-supplied E65 screen

- The provided ROM tree contains MDA/MMF, DevSound, Nokia audio policy/routing and DSP-bridge components.
- The public SDK exposes `Capabilities()` / `SetConfigL()`; MDA output streaming is a callback-driven raw-PCM API.
- `AudioServer.dll` translates the full standard sample-rate code set above; this ROM table is separate from the runtime device mask.
- The user-supplied probe screen reports playback-context DevSound flags: rate mask `0x0000067F` (9 listed rates from 8,000 to 48,000 Hz), PCM16 (`0x2`), mono+stereo (`0x3`), and `iBufferSize=4100`.
- The same screen reports the initialized `Config()` as 8 kHz, PCM16, mono, with `iBufferSize=0`.
- On that open-only run, the stream DLL loaded and ordinal 3 lookup succeeded; MDA open callbacks succeeded for 8 kHz mono PCM16 and 11,025 Hz stereo PCM16.
- A separate v1.0.0 run reported 8 kHz mono `WriteL=0` and a full successful buffer-copy callback; the user reported no audible tone. `PlayComplete=-3` followed the probe's explicit `Stop`. The 11.025 kHz playback test was not run.
- Unicorn executed the ROM's `AudioServer.dll` sample-rate converter and its real `AudioStream.dll` ordinal-111 getter for 15 test inputs. This validates the translator's integer outputs only, not physical audio behavior. The cold-boot smoke stopped at an unmapped address before a system boot.
- The ROM has DSP firmware/plugin artifacts and a kernel-side MultimediaDD gate, but no public driver path was used by these probes.

### Still unknown

- Whether candidate v1.0.1 (which requests half the stream's maximum volume and waits for natural completion) produces audible sound on the E65; the previous v1.0.0 user run was silent.
- Which output route the MDA stream selected (speaker, Pop-Port headset, Bluetooth, telephony), or whether route changes affect the result.
- The physical DAC clock/rate and any resampling between the requested MDA stream rate and hardware.
- The precise per-call chain from every MDA client through Nokia-specific classes to the codec/output, or which stages run on ARM vs. DSP for this ROM build.
- A supported application-facing API for direct DSP or DMA access.

To close the remaining audio-output gap, rerun the short v1.0.1 **public-API playback** test on the phone's speaker, wait for both formats to finish, and report audibility, phone volume, and route. Do not reinterpret `MaoscOpenComplete(0)`, `WriteL(0)`, or Unicorn output as audible success. For now, 11,025 Hz stereo is only MDA-open-validated on this E65; normal MDA remains the game's primary path and SNDDMA remains an experiment.

## Materials

- Runtime probe transcription: `symbian9/level41_devsound_caps_diag/RUNTIME_RESULTS.md` (based on the user-supplied phone-screen photo).
- Probe source, build/package instructions, and audit: `symbian9/level41_devsound_caps_diag/`.
- ROM parser and tests: `tools/research/e65/rom_extract.py`, `tools/research/e65/test_rom_extract.py`.
- ROM inventory/extract outputs: `google_drive/e65-rom-analysis/summary.json`, `e32-images.json`, `e32-exports.json`, `full-audio-strings.txt`; full files under `google_drive/e65-rom-full/files/`.
- Restored SDK declarations: `google_drive/restored-sdk/include/native/mmf/server/sounddevice.h`, `google_drive/restored-sdk/include/native/mda/common/audio.h`, `google_drive/restored-sdk/include/native/mdaaudiooutputstream.h`, and `google_drive/restored-sdk/share/symbian/native/defs/mmfdevsound.dll.def` / `mediaclientaudio.dll.def`.
- General Symbian references: [CMMFDevSound API reference](https://docs.huihoo.com/symbian/nokia-symbian3-developers-library-v0.8/GUID-B3E9B015-1413-35A6-A316-D1C01C1001AD.html), [audio output streaming tutorial](https://docs.huihoo.com/symbian/nokia-symbian3-developers-library-v0.8/GUID-ECBA6331-2187-52C9-A5DF-20CD1EEFE781.html), and [Multimedia on Symbian OS: Inside the Convergence Device](https://ebin.pub/multimedia-on-symbian-os-inside-the-convergence-device-symbian-press.html). These describe the general platform architecture, not E65-specific runtime behavior.
