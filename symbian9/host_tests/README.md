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
