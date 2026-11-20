# MDA A/B probe v1.0.0 — E65 diagnostic

**E65 result: invalid test.** Both modes panicked E32USER-CBase 66 before OpenFileL was requested; do not repeat v1.0.0. See `RUNTIME_RESULTS.md`. The startup path lacks a surrounding TRAP, a strong unconfirmed explanation; a corrected probe is required. The rest of this document describes the original test design.

This is a separate experiment, not a game fix. It was intended to play the exact v1.0.53 `fn2000.wav` through MDA in a new worker thread. Mode 1 has no periodic worker poller; mode 2 adds a 20 ms repeating `RTimer` active object. Both otherwise use the same code: one `NewL`, dynamic `OpenFileL` ordinal 42, defer `Play()` via a self-completed active object, then wait for `MapcPlayComplete`. A new worker is created for every run. This A/B is a *minimal* control rather than the game worker: neither mode includes the game's queue/state machine, music, reload, nor v1.0.1's atomic counters. The periodic control increments a mutex-protected tick count, so timing and synchronization also differ. A pass in mode 1 alone would suggest an interaction, not prove root cause or repair.

Both modes use a **one-shot 8-second watchdog timer** so a missing callback can end the run; mode 1 therefore has no periodic poller, not literally zero timers. When either the callback or watchdog stops the scheduler, objects are deleted after the scheduler unwinds. Exit waits up to 10 seconds for a stuck worker, then terminates it as a last resort. If a synchronous MDA call blocks, the worker watchdog itself cannot fire. The probe does not suppress scheduler panics.

## Device test

1. Install the separate **MDA A B Probe** SISX to phone memory C:. It has new UIDs and its own `C:\data\q41_ab_shot.wav`; it does not replace the game or earlier shot probe. It is self-signed and may show an untrusted-certificate warning; do not weaken device security if the phone rejects it.
2. Use the usual speaker route and a clearly audible volume. Start the app, press **1** (no periodic poller). The screen refreshes after three seconds. Listen and photograph the screen. If exit type indicates the worker is still pending, wait at least 9 seconds and press **0** to refresh; photograph again.
3. Press **2** (20 ms poller), listen and photograph with the same procedure. If a worker is still running, mode changes are ignored until it ends; press **0** after waiting to refresh. If the entire app closes, relaunch it for the other mode.
4. Repeat each mode at least three times, waiting for each run to end before the next. Report audible shots, screenshots, any delay, panic or whole-app exit. Every run is a fresh player and worker, not replay in the same player.

## Fields

`O` open requested; `I` init error; `P` entered/returned Play; `C` completion callback count; `E` callback error; `T` periodic timer ticks (zero in mode 1); `Timeout` watchdog fired; `Result` worker result (initial 0, meaningful only if worker has exited normally). `9999` means callback not observed, not an OS error. Exit type/reason and panic category are displayed for a finished/panicked worker. The screen is not continuously refreshed; use 0 to refresh. Audio callback success alone does not prove audibility.

## Build and audit

Built ARMv5T/EKA2 against SDK 0.3.0 with `SYMBIAN_RUNTIME_LEGACY_EKA2=ON`. E32 UID3 `0xE000A038`, package UID `0xE000A039`. SISX SHA-256 `9e2a09edb061e5ac573fb4ba8d7db89b09b4ff18acc42776ef3001a4c97475a4`; E32 `d2ce936cd43d70291a973d9d33eb278fc7ebaab93a230be5f867c3efda13eefc`; WAV `99ef9989a3b17949a2bd4cfbadfa6b2f16d84f33b207a01b91b42d72c0e25d3b`. Local SIS writer reported signed and registered, one MDA static import ordinal 35, no MIDI imports, and four files. Extracted E32 and WAV match those hashes. Certificate is self-signed RSA-1024/SHA-1 with validity 2000–2045; private key was removed. Build/audit are not evidence of playback. E65 runtime observations are recorded in `RUNTIME_RESULTS.md`.
