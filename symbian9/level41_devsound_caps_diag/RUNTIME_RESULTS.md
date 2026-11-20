# Nokia E65 DevSound probe — runtime result

**Evidence:** user-supplied photo of the final screen on the E65 DevSound Probe. The display is readable after rotating the photo upright. These are values reported by the public API on that device, not an offline inference from the ROM. The screenshot explicitly says that no audio output or route was tested.

## Screen values

| Field | Reported value | Interpretation |
|---|---:|---|
| `InitializeComplete` | `0` | DevSound initialization succeeded (`KErrNone`). |
| `Capabilities().iRate` | `0x0000067F` | The probe decoded the set bits as 8,000; 11,025; 12,000; 16,000; 22,050; 24,000; 32,000; 44,100; and 48,000 Hz. |
| `Capabilities().iEncoding` | `0x00000002` | Signed 16-bit PCM bit is set. |
| `Capabilities().iChannels` | `0x00000003` | Both mono (`0x1`) and stereo (`0x2`) bits are set. |
| `Capabilities().iBufferSize` | `4100` | The API-reported buffer-size field; the screen labels it “buffer bytes”. |
| `Config().iRate` | `0x00000001` | Current initialized rate flag is 8,000 Hz. |
| `Config().iEncoding` | `0x00000002` | Current encoding flag is signed 16-bit PCM. |
| `Config().iChannels` | `0x00000001` | Current channel flag is mono. |
| `Config().iBufferSize` | `0` | Value returned by `Config()`; no further meaning is inferred. |
| Stream DLL load / `NewL` ordinal 3 lookup | `0` / `0` | The optional MDA stream DLL loaded and ordinal lookup returned success on this run. |
| MDA open: 8,000 Hz mono PCM16 | `OK (0)` | Open callback succeeded. |
| MDA open: 11,025 Hz stereo PCM16 | `OK (0)` | Open callback succeeded. |

The exact screen rate list is `8000 11025 12000 16000 22050 24000 32000 44100 48000`. The mask also shows that 64,000, 88,200, and 96,000 Hz bits were not set in this result.

## What this establishes

- On this E65 run, public DevSound initialization succeeded and the capability/configuration values above were returned.
- The E65 reported PCM16, mono and stereo, and the listed rates in this initialization context.
- `mediaclientaudiostream.dll` loaded, its ordinal 3 was found, and both requested MDA formats completed their asynchronous **open-only** tests successfully.
- In particular, **11,025 Hz stereo PCM16 is now runtime-validated for MDA stream opening on this E65**. The SNDDMA stream request therefore has a successful format-open check, but SNDDMA remains an experiment until actual playback is tested.
- The returned current DevSound config is 8 kHz, PCM16, mono. This is a reported software configuration, not proof that the physical DAC is clocked at 8 kHz.

## What it does not establish

The screen states `Open only; no WriteL / PlayData` and `No audible output or route tested`. Therefore this is **not** evidence that sound was heard, that the speaker or headset route works, that the physical output rate equals a requested stream rate, or that the game’s complete audio path is correct. An MDA open result confirms acceptance of that stream configuration, not rendered audio. Keep the normal MDA file-player path as the project’s primary route; do not force a sample rate for that path from this probe alone.
