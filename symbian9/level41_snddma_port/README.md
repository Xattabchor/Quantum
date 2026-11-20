# Level 41 PCM/SNDDMA-style audio experiment

This directory contains a separate, rollback-safe SNDDMA-style backend. It does not replace `quantum_level41_audio.*`; the normal Level 41 preset explicitly keeps MDA selected and SNDDMA disabled. SNDDMA is only included by the opt-in `e65-level41-walk-snddma-asm` preset. The user reports the v1.0.44 SNDDMA candidate did not start on the E65, so this path is not considered device-working.

## Design

The implementation borrows the *structure* of Quake's sound path, not its Quake client dependencies:

1. Parse the two required 16-bit PCM WAV effects into non-owning views.
2. Mix up to eight simultaneous voices into signed stereo PCM16 blocks. The mixer resamples the existing 8 kHz firing sound and 16 kHz reload sound to 11.025 kHz.
3. Submit three fixed output blocks to Symbian `CMdaAudioOutputStream`; retain each block until its stream callback completes it, then refill asynchronously through `CAsyncCallBack`.
4. Keep looping `wth.mp3` on the separate MDA Player Utility path. The output-stream API accepts PCM; it is not the MP3 decoder.

This is not register-level DMA. The OMAP1710 silicon has DMA hardware, but a normal Symbian application should use the audio service/DevSound abstraction rather than program DMA or codec registers.

## Current files

- `quantum_snddma_mixer.h/.cpp` — portable WAV parser and Quake-inspired fixed-pool channel mixer.
- `quantum_snddma_output.h/.cpp` — Symbian MDA output-stream sink.
- `quantum_level41_snddma_audio.h/.cpp` — worker/thread wrapper selected only by the separate SNDDMA candidate.
- `../host_tests/snddma_mixer_test.cpp` — host regression suite.
- `../host_tests/run_snddma_mixer_test.sh` — ASan/UBSan test runner.
- `PORT-STATUS.md` — hardware findings, validation state, references and rollback hashes.

## Host validation

From the repository root:

```sh
symbian9/host_tests/run_snddma_mixer_test.sh
```

The suite covers parser bounds/format validation, mono-to-stereo mixing, pan, clipping, overlapping voices, sample-rate conversion and voice-pool replacement. The current `fn2000.wav` (8 kHz mono PCM16) and `r.wav` (16 kHz mono PCM16) were also parsed successfully in a separate local check.

## EKA2 integration contract

`CQuantumSndDmaOutput` must be created and called from the thread that owns an installed `CActiveScheduler`. The caller owns and must retain the WAV descriptor backing bytes for the entire output object's lifetime. Call `SetWave(EShotEffect, ...)` and `SetWave(EReloadEffect, ...)`, then route firing/reload events to `PlayShot()` / `PlayReload()` on that same worker thread. Link the target with the SDK's `Symbian::AudioStream` component.

The SDK target remains opt-in: configure with `-DQUANTUM_BUILD_LEVEL41_SNDDMA_ADAPTER=ON` and `-DQUANTUM_LEVEL41_SNDDMA_AUDIO=ON` to link it into the separate walk executable. The normal MDA preset explicitly passes both options as `OFF`.

The v1.0.44 SNDDMA candidate was ARMv5T/EKA2 cross-built and passed local package audit, but the user reports that it did not start on an E65. The cause is not yet isolated. Host mixer tests and cross-build success are not evidence of E65 startup or audible playback; use the MDA v1.0.43 rollback until a device-tested SNDDMA build exists.
