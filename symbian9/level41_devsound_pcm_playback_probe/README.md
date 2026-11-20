# E65 PCM playback probe — candidate v1.0.1

A small Nokia E65 diagnostic using the public MDA output-stream `WriteL` path.
The 8 kHz mono test is first, followed by 11.025 kHz stereo. Unlike v1.0.0,
this candidate requests **half of the MDA stream's `MaxVolume()` after each
successful `Open`**, waits for the natural playback-complete callback, and
only calls `Stop` after a copy error or a playback-completion timeout.

| Test | PCM | Tone | Payload |
| --- | --- | ---: | ---: |
| 1 | 8 kHz, mono, signed PCM16 little-endian | 440 Hz, 500 ms | 4,000 frames / 8,000 bytes |
| 2 | 11.025 kHz, stereo, signed PCM16 little-endian | 660 Hz, 500 ms | 5,513 frames / 22,052 bytes |

Samples peak at 8,192 (25% of full scale) and have short fades at both ends.
The app requests stream volume `MaxVolume()/2`; this is a per-stream request, not
a change to the phone's master/media-volume setting. It does not force or report
a physical output route. The system's MDA policy/default route remains in
control. The app does not use DevSound `PlayData`/`PlayInitL`, MIDI, or a private
driver/DSP/DMA path.

## Install and run on the E65

1. Transfer and install
   `quantum_level41_devsound_pcm_playback_probe-v1.0.1-e65-candidate.sisx`.
   It is a **test-signed, self-signed** package, not a trusted production
   release. The phone may show its normal unsigned/untrusted-certificate
   warning. Do not weaken device security to install it; stop if the phone
   rejects it.
2. For a comparable repeat, use the phone's built-in speaker: disconnect wired
   and Bluetooth headsets, and use the normal/default audio route. Set the
   phone's media volume to a clearly audible but moderate level; the app itself
   requests half the MDA stream maximum after `Open`.
3. Launch **E65 PCM Playback**. Listen for the first short, lower 440 Hz tone,
   then the higher 660 Hz tone. Let both tests finish; photograph the final
   screen before pressing a key to exit.
4. Report which tones were audible, any crackle/distortion, the phone's media
   volume setting, whether a headset/Bluetooth device was connected, and any
   unexpected delay, timeout, or crash. If useful, repeat separately on another
   route and identify it; the app never selects that route itself.

## Reading the result screen

- `InitializeComplete: 0` means the DevSound initialization callback returned
  success. Capability masks are reported separately from the MDA stream result.
- Each format has `O` (stream open), `W` (`WriteL`), `Copy`
  (`MaoscBufferCopied` error and copied/expected bytes), `P`
  (`MaoscPlayComplete` callback error), `S` (whether the probe requested
  `Stop`), and `V` (requested stream volume / reported `MaxVolume`).
- The v1.0.1 success sequence is: open → request stream volume → `WriteL` →
  wait for the copy callback and then the natural playback-complete callback.
  There is no fixed 900 ms delay followed by a normal-path `Stop`. A successful
  copy still does not prove that the samples were audible.
- `TO` means the relevant callback timed out; after 5 seconds without a
  playback-complete callback the probe requests `Stop` and waits up to 2 more
  seconds for its callback. `S:Y` can also result from a copy error. A `P` code
  received after a requested stop is a cancellation/cleanup result, not proof
  of playback. `--` means the field was not reached or no callback was received.
- The MDA volume number is only the per-stream request. It does not prove the
  phone's master volume, selected endpoint, physical DAC clock, or audible
  output. Those require an on-device report.

## Scope and build record

The earlier v1.0.0 run reported `InitializeComplete: 0`, DevSound rate mask
`0x0000067F`, PCM16 `0x00000002`, channels `0x00000003`; the 8 kHz mono test
reported `O:0`, `W:0`, and `Copy 0 8000/8000`, then `P:-3` after the probe
requested `Stop`. The user reported no audible sound, no headset, and that
ordinary music plays through the speaker. That run did not establish the MDA
stream's physical route or phone volume; the 11.025 kHz test was not run.

Candidate v1.0.1 has been run once on the E65. The supplied screen photo
shows successful 8 kHz mono open/write/copy, but no natural playback-complete
callback before the timeout; cleanup then reports `P:-3` after `Stop`. The user
reports hearing one sound but cannot identify its frequency. Since the 11.025
kHz stereo test was not reached, the sound was likely the first test's 440 Hz
tone, though this is not confirmed. The user reports Bluetooth/headset
disconnected and phone music volume at maximum. The physical output endpoint
still is not confirmed. See `RUNTIME_RESULTS.md` for details. The package/build
audit remains package-time metadata only; do not add runtime claims to it.
