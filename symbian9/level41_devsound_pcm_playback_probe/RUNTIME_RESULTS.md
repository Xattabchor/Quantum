# E65 runtime results — PCM `WriteL` candidate v1.0.1

**Status: v1.0.1 has now been run once on the E65.** A user-provided final
screen photo confirms successful DevSound initialization, MDA stream loading,
and an 8 kHz mono buffer-copy callback. The playback-complete callback did not
arrive naturally before the probe's timeout. The user reports hearing one
sound but cannot identify its frequency. Since the 11.025 kHz stereo test was
not reached, it was likely the first test's 440 Hz tone; that frequency is
inferred from the test sequence, not confirmed by the listener. The user
reports Bluetooth and headset disconnected and phone music volume at maximum.
The physical endpoint is still not queried or confirmed.

Do not treat successful API calls or a copied buffer as proof of audible
output, endpoint routing, or a particular DAC clock.

## Candidate v1.0.1 identity

- SISX: `quantum_level41_devsound_pcm_playback_probe-v1.0.1-e65-candidate.sisx`
- Package UID: `0xE000A033`; executable UID3: `0xE000A032`
- Version: `1.0.1`
- SISX size: 54,940 bytes
- SISX SHA-256: `17d8842db16caed77ad9949d843523fe5e81735166effded958b67672ec1827c`
- E32 executable size: 52,616 bytes
- E32 SHA-256: `30a5f78bc28a75aa7e3a8e4efb213388dd35d8faac67a616f0a3a5e7f7b4cd1a`
- Architecture: ARMv5T/EKA2; capability words are zero.
- Static imports: `estlib.dll`, `euser.dll`, `gdi.dll`, `mmfdevsound.dll`,
  `ws32.dll`; the MDA stream DLL remains runtime-loaded at ordinal 3.
- Signature: self-signed RSA-1024/SHA-1 test certificate, valid from
  2026-10-01 through 2036-10-01. The temporary private key was removed after
  package signing. This is not a trusted production signature.
- Package/build audit: `quantum_level41_devsound_pcm_playback_probe-v1.0.1-e65-candidate-audit.json`.

## v1.0.1 E65 run — user-provided screen photo

The screen reported:

```text
InitializeComplete: 0
Caps rate: 0x0000067F
Caps enc/ch: 0x00000002/0x00000003
Cfg r/e/ch: 0x00000001/0x00000002/0x00000001
MDA DLL: 0; NewL #3: 0
8k mono 440Hz O:0 W:0
Copy 0 8000/8000 P:-3 S:Y V:5/10
11k stereo 660Hz O:-- W:--
Copy -- 0/0 P:-- S:N V:--
```

Interpretation, limited to what this screen and the probe code establish:

- DevSound initialization completed successfully. Its reported rate,
  encoding/channel masks and config are API results, not measurements of a
  physical output clock.
- `MDA DLL: 0; NewL #3: 0` means the MDA output-stream library loaded and its
  ordinal-3 factory lookup succeeded.
- For 8 kHz mono / 440 Hz, MDA open and `WriteL` returned success (`O:0`,
  `W:0`); the copy callback reported success for all 8,000 expected bytes.
- `S:Y` means the probe requested `Stop`. Since the copy callback succeeded,
  the probe's code path implies its five-second natural-playback-complete wait
  expired before that cleanup request. The subsequent `P:-3` is consistent
  with the cancel/completion callback after cleanup; it is not evidence of
  audible playback or natural playback completion.
- `V:5/10` is the MDA per-stream volume request (`MaxVolume()` was 10, and the
  probe requested 5). It does not report or set the phone's master/media
  volume.
- The 11.025 kHz stereo fields are all unrun (`O:--`, `W:--`, `P:--`, `S:N`,
  `V:--`); no conclusion about MDA open or playback for that format is
  available from this run.
- The user subsequently reported hearing one sound but could not identify its
  frequency. Because only the first (440 Hz) test was recorded as run, that is
  the likely tone, but this is not a frequency measurement. This is a useful
  subjective report that sound was audible during the v1.0.1 run; it does not
  establish the physical output route or DAC rate.
- The user reports Bluetooth and headset were disconnected and the phone's
  music volume was at maximum. With no headset connected, the default internal
  speaker is plausible, but the screen does not query or confirm the physical
  endpoint; the MDA route remains unverified.

## Earlier v1.0.0 E65 run

The earlier screen also reported `InitializeComplete: 0`, DevSound rate mask
`0x0000067F`, encoding `0x00000002`, channels `0x00000003`, and config
`0x00000001/0x00000002/0x00000001` (8 kHz, PCM16, mono). Its 8 kHz test showed
`O:0`, `W:0`, and `Copy 0 8000/8000`, then `P:-3` after the v1.0.0 probe
requested `Stop` following its 900 ms drain delay. The user reported hearing
no tone on that run, with no headset connected; ordinary music worked through
the phone speaker. The phone's master/media volume was not recorded, and the
MDA endpoint was not identified. The 11.025 kHz stereo test was not run.

## Follow-up needed

The 11.025 kHz stereo test remains untested; the current screen shows it was
not reached. The next software step is to investigate why the second test did
not start before deciding whether another E65 run is needed.

No physical route, audible-output, or DAC-rate claim is made from either the
API results or ROM/Unicorn emulation.
