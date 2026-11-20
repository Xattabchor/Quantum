# E65 ROM audio helper — Unicorn execution record

## Scope and input

- ROM dump: `google_drive/romdumpplus_090549.dmp`
- Size: 19,013,632 bytes (`0x1222000`); SHA-256:
  `3b4ba70bab1a6b17f214ae1c91a279520fe82254f62e39cfa4cb8cb7ae3724fa`
- Parsed `TRomHeader` ROM base: `0xF8000000`; declared size: `0x1300000`.
  The captured file is short by `0xDE000` (909,312) bytes. The ROM tree
  references no file payload outside the captured bytes, but this does not
  repair the missing tail or establish a complete physical ROM image.
- The captured bytes were mapped contiguously at the parsed base in Unicorn
  using ARM little-endian mode. The code under test includes Thumb functions.
  The harness uses synthetic RAM only for a small object, stack, and return
  sentinel; its test object contains one MMF sample-rate flag.

## ROM routine executed

The harness executed `AudioServer.dll` at `0xF84324F2` (code offset `+0x705A`)
through its actual ROM import veneer at `0xF843507C`. That veneer resolves to
`AudioStream.dll` export ordinal 111 at `0xF81FEC45` (Thumb entry address
`0xF81FEC44`). The getter reads the rate flag from the test object's `+0x38`
field; the `AudioServer.dll` routine returns the corresponding nominal Hertz
value. Neither module was stubbed for this call.

| MMF rate flag | Unicorn result (Hz) | Present in E65 probe mask `0x0000067F` |
| ---: | ---: | :---: |
| `0x00000001` | 8,000 | yes |
| `0x00000002` | 11,025 | yes |
| `0x00000004` | 16,000 | yes |
| `0x00000008` | 22,050 | yes |
| `0x00000010` | 32,000 | yes |
| `0x00000020` | 44,100 | yes |
| `0x00000040` | 48,000 | yes |
| `0x00000080` | 88,200 | no |
| `0x00000100` | 96,000 | no |
| `0x00000200` | 12,000 | yes |
| `0x00000400` | 24,000 | yes |
| `0x00000800` | 64,000 | no |

All twelve single-bit values returned the tabled value. Zero, `0x3`, and the
multi-bit mask `0x67F` returned zero. The helper therefore translates one rate
flag at a time; it is not itself a capability query. The runtime capability
mask is a separate observation from the E65 public-API probe.

## Boot-vector smoke attempt and limits

The dump was also entered at `0xF8000000` with a synthetic stack/`SL` context.
Unicorn executed 47 instructions and stopped at the first unmapped read:
`PC=0xF800378C`, address `0xFFFECE14`, size 2. The real reset/loader register
state, Symbian boot metadata, kernel services, peripheral/MMIO behavior, and
missing ROM tail are not modeled here. This is not a booted E65 or a running
MmfAudioServer session.

## What this says about sound setup

The emulated routine is a framework rate-code converter. It explains how
`AudioServer.dll` translates symbolic MMF rate flags; it does **not** select a
physical DAC clock, establish that the codec runs at the requested rate, or
identify a loudspeaker/headset route. The E65 public API previously reported
capabilities `0x0000067F` (8,000 through 48,000 Hz for the nine listed flags),
and the user-supplied MDA `Open` checks accepted 8 kHz mono and 11.025 kHz
stereo. A separate 8 kHz `WriteL` run reported successful open/write/copy
callbacks but no audible sound; its `PlayComplete -3` arrived after the probe
requested `Stop`. The 11.025 kHz `WriteL` test was not run.

The defensible application setup remains public MDA/MMF streaming with a
format advertised by the device and normal OS policy/routing. Keep the game’s
existing MDA file-player path primary; do not write audio hardware registers,
load private EHWA drivers, or guess DSP ordinals. The ROM/emulator result alone
cannot explain the silent handset test or validate audible output.

## Reproduction

With Unicorn Engine installed (`python3 -m pip install unicorn`), run from the
repository root:

```sh
python3 tools/research/e65/unicorn_audio_trace.py \
  --rom google_drive/romdumpplus_090549.dmp
```

The command validates the ROM tree and imported getter target, runs the 15 rate
cases, and performs the limited boot-vector smoke attempt. Add
`--skip-boot-smoke` to run only the audio helper. The extractor's independent
synthetic tests are `python3 -m unittest discover -s tools/research/e65 -v`.
