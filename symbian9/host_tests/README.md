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
