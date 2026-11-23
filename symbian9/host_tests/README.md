# Host regression tests

Run the Level 41 controller and renderer regressions with:

```sh
symbian9/host_tests/run_level41_walk_weapon_regression_test.sh
```

That runner builds the walk/weapon suite and then runs
`run_world_objects_rendering_regression_test.sh`. The latter covers Java-style
world-sprite projection, texture-frame animation, flicker/color animation,
keyed/add/multiply/alpha/fog modes, shared mesh/sprite painter sorting, per-object
`ADDSZ`, mesh morphing, and queue-capacity failure. Both suites use
AddressSanitizer and UndefinedBehaviorSanitizer on the host.

The world-object suite also drives a synthetic `[MESH]`/`[SPRITE]` fixture
through `SoftwareLevelObjectLoader::measure()` and `load()`, then checks object
lifecycle and `SoftwareLevelObjectRenderer` batching alongside static meshes.
A production-profile regression additionally loads the checked-in
`sym9-level1/level1-objects.txt`, parses the real `prop/md.3d` model and both
QTX1 textures, verifies the exact Level 1 positions/scales/modes, and submits
one mesh plus one sprite through the combined painter queue. The runtime
resource callbacks themselves are Symbian-specific and are validated by the
ARM build; neither host suite proves E65 device rendering or installation.

## Perspective segment length (`q`)

Java exposes the perspective segment length as the "PERSQ" quality setting
(`Main.q`, 5 steps; `q = 24` for persQ 0..2, `q = 12` above). The device target
sets it explicitly in `level41_main.cc`; the engine default stays at the
reference value of 24 for code that builds settings itself.

Measured on host x86-64 (`-O2`), one 176x144 triangle with steep `w` variation,
fidelity baseline = q=12 (the finest setting the reference offers):

| q | ms/tri | speed vs q=24 | mean channel error | pixels changed |
| --- | ---: | ---: | ---: | ---: |
| 12 | 0.129 | 0.46x | 0.0 (baseline) | - |
| 24 | 0.060 | 1.00x | 13.1 | 89.6% |
| 48 | 0.035 | 1.71x | 38.3 | 97.4% |
| 96 | 0.020 | 3.05x | 61.2 | 98.3% |

q=96 is the shipped device choice. It is a real quality trade, not a free win:
61 of 255 per channel means steep floors stop converging to perspective over
most of a scanline. Revert to 24 for reference-accurate perspective, or to 12
for the best quality the reference itself offers.

A rewrite of `perspectiveDivide` to a single 64-bit divide (`((long)n << 12)/d`,
matching Java's own expression) was verified bit-identical over 641 operand
combinations and is 1.4x faster in an unoptimised build, but it is not enabled:
on ARMv5 it depends on the toolchain's 64-bit divide cost, which has to be
measured on the device before being trusted.

## PCM-one effects audio

`run_pcm_one_audio_test.sh` covers the Level 41 effects backend in
`symbian9/level41_pcm_audio/`. Effects play on a single
`CMdaAudioOutputStream` opened at 8 kHz mono PCM16 with one voice; music keeps
the shipped `CMdaAudioPlayerUtility` path, so that half is unchanged.

Fire and reload are no longer separate player utilities. While a clip is in
flight, or before its estimated duration has elapsed, a new trigger is
**dropped** -- it is not queued behind the clip and not mixed with it. That is
the reference "PCM one" behaviour and it is what the E65 runs reliably; the
two-voice mixer was explicitly ruled out.

The device-independent half lives in
`symbian9/level41_pcm_audio/quantum_level41_pcm_core.{h,cpp}` (no Symbian
headers) so it can be tested here:

- RIFF/WAVE PCM16 mono parsed chunk by chunk with bounds checks; `LIST`/`fact`
  chunks are skipped. 8 kHz data is copied verbatim, 16 kHz is averaged
  pairwise into 8 kHz so clip duration is preserved.
- Only 8 kHz and 16 kHz mono PCM16 are accepted, matching the stream settings
  the device actually opens. Stereo, 8-bit and 11.025 kHz are rejected rather
  than played at the wrong pitch.
- `VoiceGate` implements the one-voice admission rule, including the fact that
  `MaoscBufferCopied` is **not** a playback-ended notification: the estimated
  clip duration from the last submission is what frees the voice. A negative
  elapsed time counts as "no estimate", not as an early repeat.

Callbacks deliberately do very little, matching the device-tested reference:

- `MaoscOpenComplete` only sets the ready flag. It does **not** submit, so the
  first trigger of a level goes out from the next poller turn instead of
  re-entering the media server from inside its own callback.
- `MaoscPlayComplete` does **not** clear the in-flight flag and does not
  submit. `MaoscBufferCopied` is what releases the descriptor.
- The stream is deleted **without** a preceding `Stop()`. `Stop()` posts an
  asynchronous completion into an object that is about to be destroyed, which
  is the shape of the worker-exit panics the per-effect player path produced.

When a packaged build tree is present the test also decodes the real
`fn2000.wav` and `r.wav` the SIS installs (6400 and 27098 stream bytes), which
catches format drift. Note `r.wav` is 16 kHz with a `LIST` chunk, so the fold-
down and chunk skipping are both exercised by real data.

The new backend is selected with `-DQUANTUM_LEVEL41_PCM_ONE_AUDIO=ON` (default)
in `symbian9/level41_device_test/CMakeLists.txt`. It is mutually exclusive with
the retired `QUANTUM_LEVEL41_SNDDMA_AUDIO` mixer profile. Building a `.sis`
still needs the S60 3rd Edition SDK, so this is source-verified and host-tested
only; the device retest is still outstanding.

## Perspective bit-exactness and fast-path parity

`run_perspective_bitexact_test.sh` pins the 32-bit perspective reference path
itself. The fast-rasterizer test compares 16-bit against 32-bit with a
tolerance, which cannot see a one-channel change in the reference; this one
renders three fixed triangles through every effect mode the walk uses (copy,
shade, fog add, surface add, fog blend low/high) into a 96x80 framebuffer and
compares an FNV-1a hash of the whole frame against stored golden values.

Two textures are used: an opaque one with a colour ramp, and a keyed one with
every third texel cleared so the `MayContainZeroTexels` template is exercised.
Re-run with `--print` to regenerate hashes after an intentional change. The
harness was validated by sabotaging the mode-1 addend mask and confirming it
reports a mismatch.

`run_perspective_parity_test.sh` renders the same triangles through the 32-bit
reference and the fast 16-bit path and reports the worst per-channel
difference. Current worst values, which must not grow:

| pass | step 9 | step 24 | step 96 |
| --- | ---: | ---: | ---: |
| mode 0 copy | 7 | 7 | 7 |
| mode 9 shade | - | 8 | 5 |

Those match the budgets already recorded for this project (perspective mode0
q=24: 8, perspective mode9 q=24: 8), so the harness is measuring the same
thing the device builds were tuned against. Steps 9 and 96 are included because
the scanline is walked in `perspectiveStepPixels`-sized pieces and the divisor
is special-cased for 24 only.

One optimisation landed on top of these harnesses: `drawPerspectiveEffectSpan`
used to compute `screenY * destination.width + x1` inside the pixel loop, but
only `EPerspectiveGlass` reads that value, and it is a row base plus an
increment. It is now hoisted to `logicalRowBase` and advanced with `++`. All
eight golden hashes are unchanged.

A second candidate was rejected rather than shipped. Specialising the 16-bit
texel expansion by template in the perspective path does nothing, because
`SoftwareTextureView` is always 0xAARRGGBB: 16-bit textures are widened by the
asset pipeline and by the fast rasterizer, not here. Only `software_fast_rasterizer.cpp`
samples 16-bit storage, and it already selects `Is1555`/`Is4444` at compile
time via `drawPerspectiveSpanBody`. Adding a template here would have been code
that compiles to the same instructions.
