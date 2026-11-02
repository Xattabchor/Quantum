# Quantum Level 41 interactive walk test

This is a **separate** interactive build target over the already-running native
Level 41 walk harness. The standard local profile uses its Level 41 mesh,
lightmap, and sky; the archive-backed `sym9.zip` profile stages the Kordos
Level 1 world and sky resources. Neither profile replaces or changes the
fixed-camera FPS benchmark.
The app uses the native mesh renderer, `SphereCast`, and floor-height query to
move a camera/player with a sphere collision proxy through that scene.

After the 3D scene is rendered, the native walk target composites the Java
first-person weapon images into the software framebuffer. The default USP
weapon's right-hand sprite and separate muzzle-flash layer are rendered from
pre-scaled ARGB images; zoom-sight, left-hand/two-hand, melee offset, and
replacement-fire paths are implemented in the shared host-tested
`SoftwareWeaponRenderer`. The overlay uses Java's 240x256 reference scaling,
16.16 `ImageResize` bilinear weights, draw anchors, alpha, and the
`GameScreen`/`Arsenal` visibility and zoom dispatch. Sprite resizing happens
once at load time; each frame uses the prepared image buffers and source-over
ARGB composition (pixel parity with MIDP's platform `Graphics` is not claimed).

The walk target has a small native gameplay slice for the one equipped USP.
`SoftwareWeaponController` ports the Java `Weapon.frame` shot/cooldown,
`Magazine` ammo/reload progression, and the first-person `dx`/`dy` sway and
recoil updates. The movement controller now samples held input on rendered
frames and ports Java's default `PLAYER_SPEED=150`, velocity accumulation,
the `/50` frame-time integration factor, grounded division-by-four friction,
and `Scene.gravity=-20`. Native elapsed time is sampled in milliseconds; the
Java `QFPS` timing base (`FPS.java`) is absent from this source tree, so exact
timing-unit parity is not verified. The default `0` key jumps only while
grounded, adding the Java 150 impulse and 1.2 velocity multiplier; arcade
mid-air jumping remains off by default. The exact Level 41 `PLAYER_SPEED` override has not been found,
so this native harness currently uses the Java fallback value 150. This is not
a full character/skeletal walking animation or a full Java `Player` port.

Fire with **5** or the center/select key. A shot consumes magazine ammo; trying
to fire on an empty magazine starts the Java-style timed reload when reserve
ammo is available. The overlay shows magazine/reserve counts, reload progress,
and short-lived `WALL`/`CLEAR` feedback. Shots ray-cast against the Level 41
static collision mesh only: there are no native enemies, damage, hit reactions,
or projectile/tracer simulation in this test. The walk target still has one
equipped weapon and does not load QWA1 loadouts or switch inventory. It now reads weapon `[0]` sound paths from the packaged
QWC1 catalog and routes successful shots and the automatic reload-start
transition through `CQuantumGameAudio`. When the generated audio profile is
present, it also starts the configured Level 1 music on scene load, looping the
`MUSIC` resource selected from `level1.txt`; menu/title tracks are not used by
this no-menu test. The `CQuantumLevel41Audio` adapter uses the configured
`SHOOT_SOUND`/`RELOAD_SOUND` values and the separately staged files under
`private\e000a020\audio\`; missing catalog/audio files or MMF errors remain
optional and do not stop the walk test. The GDI fallback services active-object
callbacks while waiting for window/audio requests; the DSA path already runs
under the active scheduler. This code path has not been built or tested on an
E65, so it does not establish device codec support or audible playback.
Player death and the rest of the game HUD are not integrated here. Separately,
the shared host-tested `SoftwareWeaponCatalog` parses all dense QWC1 profiles,
and `SoftwareArsenalController` parses QWA1 defaults and models weapon
ownership/selection, ammo state, and Shop pricing/purchases. This is ongoing
port work, not evidence that the complete Java weapon system is done.

When the original Quantum MIDlet JAR is available, the asset tool emits
`weapon-gameplay.qwg` for the group-[0] smoke path, a QWC1
`weapon-catalog.qwc` for every configured weapon, a QWA1
`weapon-loadout.qwa` for `Shop` defaults, and namespaced QTX1 sprites for each
configured image. QWG2 carries the Java `KW`/`KH` muzzle ratios; the native
loader also retains support for older QWG1 files and still does not load
QWC1/QWA1 or switch profiles. Without JAR-derived configuration, the HUD
labels its **test-only** 12-round USP profile as `TEST`; those fallback values
are not claimed to match the Java USP.
Weapon art remains optional: without the QTX1 files, the world and gameplay
state still run without a first-person sprite. The separate muzzle-flash image
is likewise optional.

Controls on the E65 D-pad: hold **Up/Down** to move forward/back and
**Left/Right** to turn; **0** jumps when grounded; **5/center** fires. Releasing
a direction stops adding acceleration, while grounded friction damps existing
velocity. The walk starts at the world origin and settles onto a nearby floor
during startup; the overlay reports X/Z/yaw and weapon ammo. The native target
uses the default Java speed/jump constants because it does not load a
level-specific `PLAYER_SPEED` or `ARCADE_JUMP_PHYSICS` override. Presentation
keeps DSA enabled. For v1.0.5, the direct
EColor16MU ARGB bitmap path is experimental; if its bitmap cannot be created,
the presenter falls back to EColor64K, while the existing bitmap-GDI fallback
remains available if DSA setup or drawing fails. The DSA event waiter handles
input while the frame runner continues rendering. The E65-only OMAP1710
ARMv5TE, RGB565 copy, and ARGB bitmap paths remain behind their existing build
gates; this does not enable them for the general ARMv5T target. The walk
renderer keeps
all five world materials on the Level 1 standard draw mode 9, including the
animated river; the leading `6:` in its texture entry is animation speed, not
a draw-mode override.

Collision uses the Level 41 render mesh and assumes its five texture materials
are collidable, matching the Java `Texture` defaults; no full-game
collision-flag parity is claimed. It adapts renderer polygons directly into
the collision view, avoiding an intermediate per-polygon copy on the device.
This separate Level 41 target is not evidence that the complete Java `Player`,
`GameScreen`, inventory, actor damage, or gameplay has been ported. Host tests
now cover the non-river shot-light coordinate fix, but the Symbian walk target
has not yet been cross-compiled, packaged, or visually verified on an E65.

The v1.0.2 E65 attempt read `skybox8.3d` completely (`506/506`) and reported
counts `1/24/22/1`; the displayed skybox label was stale because startup had
advanced to walk-collision setup. The v1.0.3 retest identified the actual
failure: collision-buffer allocation failed with 2,641 world polygons and zero
built descriptors. The imported E32 converter caps the process heap at 1 MiB;
at 240x320, texture storage plus the software framebuffer use about 966,656
bytes before allocator overhead, leaving too little for the 105,640-byte
collision descriptor array. Version 1.0.4 sets the walk package's heap maximum
to 2 MiB in its manifest; package preparation updates the E32 heap fields and
recomputes the header CRC. It also keeps the direct render-mesh adapter, which
avoids the former temporary polygon-copy allocation. E65 retest is pending.

### Archive-backed Level 1 sky

The `sym9.zip` package path now uses the archive's `skies/sky.3d` and its
Level 1-configured daytime texture `skies/skuy.png`. Staging keeps the native
loader's legacy filename (`skybox8.3d`) and builds `sky.qtx` directly from
`skuy.png`. The archive mesh has 25 vertices and 20 polygons; the fixed buffers
now allow 25 vertices and 22 polygons, and the view uses the measured counts.
The existing Level 41 24/22 sky still fits those capacities, but it is not the
sky used by this archive-backed package.

The archive's `forestOrigy.png` is 128×64, while the older native profile only
accepted a 256×128 forest texture; using it caused the device's
`Failed at: texture size` startup error. The requested package keeps the
original local 256×128 `forestOrig.png` and uses the archive's world, map,
river, sky, and weather resources. The loader still accepts both forest sizes
for compatibility, and mesh UVs are scaled to each texture's measured
dimensions. A host regression test covers both accepted sizes and rejects
others. The v1.0.7 source was cross-built and packaged as a private unsigned SIS; its
E65 startup, presenter selection, walking behavior, and FPS remain unverified
until the user runs it on-device.

## Build and package

The target is declared in `level41_device_test/CMakeLists.txt` and is opt-in.
Configure the existing Symbian CMake project with both
`QUANTUM_BUILD_LEVEL41_DEVICE_TEST=ON` and
`QUANTUM_LEVEL41_INTERACTIVE_WALK=ON`, using the ARMv5T/EKA2 Symbian SDK; set
`$SDK` to the SDK installation directory. Before configuring/building, stage
the audio profile so the generated header supplies the Level 1 `MUSIC` path
from `sym9.zip` (rather than a guessed filename):

```sh
python3 symbian9/tools/prepare_game_audio_assets.py \
  --audio-archive google_drive/lite.zip \
  --config-archive google_drive/sym9.zip \
  --output symbian9/assets/audio
```

```sh
"$SDK/bin/cmake" -S symbian9 -B /tmp/quantum-walk-build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$SDK/cmake/symbian-arm.cmake" \
  -DSYMBIAN_SDK_PREFIX="$SDK" -DSYMBIAN_TARGET_ARCH=armv5t \
  -DSYMBIAN_RUNTIME_LEGACY_EKA2=ON \
  -DQUANTUM_BUILD_LEVEL41_DEVICE_TEST=ON \
  -DQUANTUM_LEVEL41_INTERACTIVE_WALK=ON \
  -DQUANTUM_LEVEL41_OMAP1710=ON \
  -DQUANTUM_LEVEL41_OMAP1710_ECOLOR64K=ON \
  -DCMAKE_MAKE_PROGRAM="$SDK/bin/ninja" -DCMAKE_BUILD_TYPE=Release
"$SDK/bin/cmake" --build /tmp/quantum-walk-build --target quantum_level41_walk_test_e32
```

The OMAP flags shown above are for an **E65/OMAP1710-only** candidate. The
walk target uses the same conditionally compiled mode-9 ARMv5TE and RGB565 copy
assembly as the fixed Level 41 target; both options remain off for the general
ARMv5T build and must not be used to imply support on other chipsets. To build
the v1.0.5 ARGB experiment, additionally pass
`-DQUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP=ON`; this requires both existing
E65 gates and now compiles the ARGB path into the walk executable.

For the private `sym9.zip` resource set, build the Kordos Level 1 world and sky
profile directly from its extracted resources (no MIDlet JAR is needed for the
current group-[0] FS2000 walk target):

```sh
STAGE=/tmp/quantum-sym9-stage-level1-sky
PACKAGE=/tmp/quantum-sym9-package-level1-sky
mkdir -p "$PACKAGE"
python3 symbian9/tools/prepare_level41_sym9_assets.py \
  --archive google_drive/sym9.zip --output "$STAGE" \
  --forest-texture symbian9/level41_device_test/assets/level41/forestOrig.png
python3 symbian9/tools/prepare_level41_device_assets.py \
  --assets "$STAGE/assets" --sky-texture "$STAGE/assets/skuy.png" \
  --output "$PACKAGE"
python3 symbian9/tools/prepare_level41_package.py \
  --project symbian9/level41_walk_test \
  --artifact /tmp/quantum-walk-build/level41_device_test/e32/quantum_level41_walk_test.exe \
  --output "$PACKAGE" \
  --asset-directory "$STAGE/assets" \
  --weapon-asset-directory "$STAGE/weapons" \
  --weather-config "$STAGE/weather.txt" \
  --audio-asset-directory symbian9/assets/audio
```

The JAR-based helpers remain the route for preparing the full multi-weapon
catalog. To build that JAR-derived profile, provide the original Quantum
MIDlet JAR locally (the JAR is not copied into source control or the code-only
archive):

```sh
PACKAGE=/tmp/quantum-level41-walk-package
mkdir -p "$PACKAGE" "$PACKAGE/weapons"
python3 symbian9/tools/prepare_level41_device_assets.py --output "$PACKAGE"
python3 symbian9/tools/prepare_level41_weapon_assets.py \
  --jar /path/to/Quantum.jar --output "$PACKAGE/weapons"
python3 symbian9/tools/prepare_level41_package.py \
  --project symbian9/level41_walk_test \
  --artifact /tmp/quantum-walk-build/level41_device_test/e32/quantum_level41_walk_test.exe \
  --output "$PACKAGE" \
  --asset-directory symbian9/level41_device_test/assets/level41 \
  --weapon-asset-directory "$PACKAGE/weapons"
```

The weapon-preparation tool verifies the existing group-[0] USP compatibility
paths, parses all dense numeric `weapons.txt` groups, and writes QTX1 images
with alpha. It emits QWG2 for the current group-[0] sample (including Java
`KW`/`KH` muzzle ratios), QWC1 profiles (including weapon purchase prices and
resource names), and QWA1 default Arsenal indices/ammo parsed from
`settings.txt` when present. The package staging step
includes these data files and every namespaced `weapon-NNN-role.qtx` it finds.
The walk executable continues to use the group-[0] gameplay/art files and uses
the QWC1 group-[0] record only for its configured shot/reload sounds; inventory
switching remains outside this test.
ImageMagick and the existing Symbian host-tool environment
(`symbian-platform==0.3.0`) are required. The package staging step emits the
`.pkg` and registration resources. It applies the manifest's E32
`heap_min`/`heap_max` values (recomputing the E32 header CRC) before packaging;
the walk target requests a 2 MiB maximum. Sign the result with the same local
`makesis`/`signsis` process as the fixed-camera test.

Host tests cover the walk controller's Java-default speed, time-scaled
velocity, grounded friction, floor contact, wall collision, gravity, normal and
arcade jump conditions; sprite preparation, Java-compatible bilinear resizing,
alpha composition, draw anchors, mirrors, zoom dispatch, visibility gates,
QWG1/QWG2/QWC1/QWA1 config generation/parsing, weapon prices and default loadouts,
Arsenal selection/purchase behavior, shot/cooldown transitions, ammo/reload
progression, and weapon sway/recoil. The v1.0.2 and v1.0.3 startup failures occurred before
weapon composition, so they provide no visual or FPS result for the overlay.
The v1.0.5 E65 candidate is built with DSA and the optional direct ARGB bitmap
path enabled; only the river material overrides standard mode 9 with draw mode
7. It is a separate Level 41 walk package, not a Java or Avkon build. It has not
been installed or run on an E65: ARGB activation, river appearance, startup,
controls, and FPS remain unverified. Even an ARGB render-byte match would not
establish final RGB565 screen parity.

## v1.0.7 E65 readout and DSA ARGB32 presenter follow-up

The v1.0.7 E65/OMAP1710 walk target was built with
`QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP=ON`, but the user reports that the
runtime presenter is `DSA EColor64K`, not `DSA ARGB32`. The user also reports
that FPS fell from 12.8 to 8.9 after the weapon and rain were included. That is
about a 30.5% reduction (roughly 78 ms to 112 ms per frame), but there is not
yet a same-scene component-isolation run, so no individual effect is assigned
that cost and the baseline effects remain enabled.

Source inspection found that the first ARGB eligibility check compared the
new direct-bitmap render with the preceding `gFramebuffer` frame. In an
interactive walk, the camera, weapon state and rain can advance between those
frames; that is not a valid same-frame pixel comparison and can trigger the
RGB565 fallback even when `EColor16MU` bitmap creation, geometry, stride and
address checks pass. The v1.0.8 source replays the just-rendered post-update
walk/effect state into the reference buffer before comparison, freezes the
weather timestamp for that one replay, and keeps the fallback for actual
creation, alignment, render or parity failures. This one-time check runs before
the benchmark reset. It is intended to fix a false fallback, not to claim that
the final displayed screen is RGB565-equivalent or that FPS has improved.

The weapon sprite blend and rain-particle routines remain C++ pixel loops;
the existing OMAP assembly does not accelerate those loops. Isolating weather
and weapon cost still requires matched device readouts. No quality-reducing
tuning or removal of either effect is promoted in the baseline.

## E65 weapon/rain ASM A/B experiment (v1.0.9 source candidate)

The new opt-in `QUANTUM_LEVEL41_OMAP1710_EFFECTS_ASM_EXPERIMENT` is restricted
to the interactive walk target and requires the OMAP1710 and DSA ARGB build
gates. The default C++ path and v1.0.8 package are unchanged. It adds two
ARMv5TE kernels to a separate experiment build:

- Weapon sprite rows are composited in ARM assembly, with forward and mirrored
  source traversal, preserving the exact existing alpha-zero skip, opaque
  replacement, and rounded divide-by-255 RGB blend.
- Rain streaks use an ARM add/compare/remainder recurrence instead of doing a
  64-bit multiply/divide on every visible scanline. The wrapper falls back to
the original Java-compatible C++ line routine for wrapped or unusual geometry.

No visual tradeoff is intentionally made. `tools/verify_omap1710_effects_asm.py`
executes the ARM-state kernels in an ARM926 emulator and compares output with
the C++ formulas; the current run passed 218 weapon spans (including mirrored
rows) and 728 rain streaks. This proves kernel output for those cases, **not**
E65 speed or full-frame device parity.

The experiment overlays `FX ASM/C++ W/R/U` averages: weapon draw, rain-particle
draw, and combined weapon/weather update (including the roof query). Press
`A` to switch between ASM and C++ and reset the averaging window. For an E65
A/B, stay in the same place, do not fire or walk, let each mode run for about
10 seconds, and record both the live FPS and the `W/R/U` line. That readout is
needed before deciding whether either effect is a confirmed E65 hotspot or
promoting the ASM path. The v1.0.9 candidate is now cross-built as an unsigned
SIS, and all 15 embedded payloads were decompressed and compared byte-for-byte
with staging (see `../exports/BUILD-REPORT-level41-walk-effects-asm-experiment-v1.0.9.md`).
It has not been run or timed on an E65; the user-reported 12.8→8.9 FPS drop
remains aggregate and unisolated. No performance improvement is claimed. The
compile-time DSA ARGB gate also does not prove the runtime-selected pixel format;
that still requires an E65 check.

The ARM926 verification tool requires `keystone-engine` and `unicorn`:

```sh
python3 -m pip install keystone-engine unicorn
python3 symbian9/tools/verify_omap1710_effects_asm.py
```

Build the walk target with the existing ARMv5T/EKA2 toolchain and the E65
options, adding `-DQUANTUM_LEVEL41_OMAP1710_EFFECTS_ASM_EXPERIMENT=ON`. The
source path is native-only and remains disabled for the common port; it does
not add TMS320 C55x DSP code or assume VFP/NEON.

## Original 80% render viewport (v1.0.10 experiment)

The Java MIDlet defaults `displaySize` to 80. `GameScreen` keeps the full
canvas width, renders the 3D scene at `height * displaySize / 100`, and centers
that area vertically. On a 240×320 E65 screen this is a 240×256 render area,
with 32-pixel black margins above and below. The native walk harness had been
rendering 240×320; this opt-in build now matches the original default geometry.
The height calculation uses the Java-compatible 32-bit multiply/divide helpers.

Enable `-DQUANTUM_LEVEL41_ORIGINAL_VIEWPORT_80_EXPERIMENT=ON` for the
interactive walk target. It is off by default and does not alter the fixed
benchmark or v1.0.9 artifacts. The change reduces the E65 scene framebuffer
from 76,800 to 61,440 pixels per frame (20% fewer); that is a pixel-count
change, **not a measured FPS gain**. DSA remains enabled, the viewport is
centered in the full-screen presenter, and the DSA ARGB/OMAP RGB565-copy paths
accept the centered viewport. The v1.0.10 comparison build leaves the separate
weapon/rain effects ASM experiment off so the viewport change is isolated.

The unsigned v1.0.10 SIS was independently parsed; its controller/data/header
checksums passed and all 15 payloads matched staging byte-for-byte. The
ARMv5T/EKA2 build and host regression tests passed, but it has not been run on
the E65. Runtime DSA format and the visual margins therefore still need an
on-device check; no compatibility or performance result is claimed. See
`../exports/BUILD-REPORT-level41-walk-original-viewport-v1.0.10-r1.md` (the r1 package is the latest 1.0.10 candidate).

## Combined 80% viewport + weapon/rain ASM (v1.0.11)

The v1.0.11 walk candidate enables both
`QUANTUM_LEVEL41_ORIGINAL_VIEWPORT_80_EXPERIMENT` and
`QUANTUM_LEVEL41_OMAP1710_EFFECTS_ASM_EXPERIMENT`, while retaining OMAP1710,
EColor64K and DSA ARGB gates. The options remain independently opt-in and do
not affect the fixed benchmark. This makes v1.0.10-r1 the viewport-only
control; inside v1.0.11, press `A` to switch the weapon/rain effects between
ASM and C++ and reset the `W/R/U` averaging window. Compare on the same device,
location and movement state; no E65 speed result is available yet.

The build enables the effects ASM parity-checked ARM kernels and the 80%
viewport together. To reproduce, add both
`-DQUANTUM_LEVEL41_ORIGINAL_VIEWPORT_80_EXPERIMENT=ON` and
`-DQUANTUM_LEVEL41_OMAP1710_EFFECTS_ASM_EXPERIMENT=ON` to the existing walk
build flags. The unsigned v1.0.11 package passed the ARMv5T/EKA2 build and
independent 15-payload SIS verification, but has not been run on an E65. See
`../exports/BUILD-REPORT-level41-walk-original-viewport-effects-asm-v1.0.11.md`.

## Separate river draw-mode parity change (not the reported issue)

The earlier interactive-walk source forced river material slot 4 to draw mode
7. That mode is the glass/refraction path and ignores the per-vertex lightmap
values. In the archive's `level1.txt`, `DMODE=9` is the world default; the
river texture entry's leading `6:` is its animation speed, not a draw-mode
setting. The walk target now leaves all five world materials on standard mode
9. This is a separate river parity correction only; it does not establish a
fix for the reported missing lighting on other polygons. The archived map has
24 river polygons (96 lightmap corner values).

The archived Level 1 VLA is 10,032 bytes and covers all 2,641 mesh polygons;
that proves data coverage, not visual parity. The non-river shot-light issue
was traced to a coordinate-space mismatch: native code subtracted the world
camera position from an already camera-space vertex, while Java keeps
`Vertex.x/y/z` in world space and subtracts the camera translation there. The
native renderer now uses each source mesh vertex for the shot/micro-light
vector (including the mode 6 camera-vector path). A regression test uses a
non-origin camera and confirms a correctly facing polygon brightens instead
of retaining only its baked lightmap value. Host tests pass; E65 runtime
visual confirmation and a new SIS build have not been done.

A host regression test checks that all five Level 41 world materials resolve
to standard mode 9 and that mode 9 consumes per-vertex brightness, while mode
7 does not. The earlier v1.0.12 SIS remains unchanged; see the v1.0.13 section
below for the separate arrow-key release fix and its validation status.

## v1.0.13 arrow-key release fix and launch regression

Version 1.0.13 added scan-code handling for `EEventKeyDown`/`EEventKeyUp`,
ignored the following arrow `EEventKey` so it could not relatch movement, and
cleared held input on focus and DSA transitions. Jump on `0` became a one-frame
press pulse. It also called `RWindowGroup::CaptureKeyUpAndDowns` for the four
arrows.

The v1.0.13 E32 has zero capabilities, while the SDK's `w32std.h` documentation
marks `CaptureKeyUpAndDowns` as requiring `SwEvent`. The startup code returned
immediately if a capture request failed, before drawing the first startup
screen. This is a strong match for the reported silent no-launch; the actual
E65 return code was not captured, so the diagnosis remains device-unconfirmed.
The fix is not to add a restricted capability to the unsigned SIS.

The unsigned v1.0.13 SIS remains at
`output/v1.0.13-arrow-keyup-fix/quantum_level41_walk_test-v1.0.13-arrow-keyup-fix-armv5t.sis`.
Its SHA-256 is
`e490aae399496a49500bbcf1dd8422141270dab7dc6c3d30c7426f0cdfcb3253`; it is
left unchanged for rollback. It was validated as ARMv5T/EKA2 with E32 UID3
`0xE000A020`, 2 MiB staged heap, package UID `0xE000A021`, version 1.0.13,
valid SIS checksums, and 15 payloads matching staging. The v1.0.12 SIS is also
unchanged.

## v1.0.14 no-SwEvent arrow input

Version 1.0.14 removes global key capture. The foreground window group receives
normal `EEventKeyDown`/`EEventKeyUp` events; movement state is updated from
`TKeyEvent.iScanCode`, and the paired `EEventKey` remains suppressed for the
arrows. Focus/DSA resets and the one-frame jump pulse remain. This keeps the
unsigned E32 at zero capabilities and avoids the privileged startup call.

The unsigned v1.0.14 SIS is at
`output/v1.0.14-no-sw-event-capture/quantum_level41_walk_test-v1.0.14-no-sw-event-key-capture-armv5t.sis`.
It was cross-built for ARMv5T/EKA2 with DSA enabled, the DSA ARGB32 gate off,
and capabilities 0. With the EColor64K gate also off, the selected DSA bitmap
format is the 24-bit `EColor16M` path; the on-device overlay must still confirm
that DSA—not a fallback presenter—is active. OMAP1710 and effects-ASM gates
remain off, matching the prior generic walk build profile. The packaged E32
UID3 is `0xE000A020`; its post-staging
header CRC and heap range 4,096..2,097,152 bytes were verified. The package UID
is `0xE000A021`, version 1.0.14. SIS validation checked the 16-byte UID/CRC
header and SISContents field at offset 16, both SIS checksums, and all 15
compressed payloads against staging. The file is 481,680 bytes (SHA-256
`d3882d07624b4f700ce09d23b98ee3ff96cbb309113b2a82f5e6cd6e2f8a7d80`).
The file is also in Drive at
[the v1.0.14 SIS](https://drive.google.com/file/d/1E3Tbzq4wjnIrCXXm3kbmxCz9sD4AtM-T/view?usp=drivesdk);
permissions were checked and only the owner has access. The v1.0.13 candidate
remains unchanged.

The user later reported that the sticky-key build displayed `DSA 24-bit`; the
exact SIS version was not specified. That status is consistent with v1.0.14's
build gates (ARGB32 and EColor64K both off), but does not establish whether
v1.0.14 fixed launch/key-release behavior. The original SwEvent cause remains
a strong hypothesis from the SDK contract and zero-capability E32, not a
captured device error. Full-gameplay FPS and the 15 FPS goal remain
unverified.

## v1.0.15 DSA ARGB32 follow-up

The user clarified that the sticky-key candidate should show DSA ARGB32, not
24-bit output. The existing v1.0.14 SIS was built with all E65/ARGB gates off,
so its `EColor16M`/24-bit presenter was expected and did not meet that
requirement. The v1.0.14 file remains unchanged.

A separate v1.0.15 unsigned ARMv5T/EKA2 candidate was built from the no-SwEvent
key-up/down source with `QUANTUM_LEVEL41_OMAP1710=ON`,
`QUANTUM_LEVEL41_OMAP1710_ECOLOR64K=ON`, and
`QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP=ON`; the effects ASM gate remains
off. Its E32 has UID3 `0xE000A020`, capabilities 0, 187,752 code bytes,
424 data bytes, and 1,011,776 BSS bytes. The staged E32 header CRC and heap
range 4,096..2,097,152 bytes were checked; it is the raw E32 with only the
heap bounds/CRC patch. The ARGB presenter strings and fallback label are in
the binary, but this is not proof of the runtime-selected presenter.

The SIS is at
`output/v1.0.15-dsa-argb32/quantum_level41_walk_test-v1.0.15-dsa-argb32-armv5t.sis`.
It is 483,700 bytes with SHA-256
`ba41918556d728bdd8c9b01140c906065b264da8df7b5fb0a72ad64804ae421e`;
package UID `0xE000A021`, version 1.0.15. The SIS header UID CRC, both SIS
checksums, SISContents root at offset 16, and all 15 decompressed payloads
were verified. The [v1.0.15 Drive copy](https://drive.google.com/file/d/1BCws9OQr6ZDWyTlZQb9CVoZ-PtQzxMH1/view?usp=drivesdk)
was uploaded to the same archive folder; its permissions were checked and
only the owner has access.

EColor64K remains the runtime fallback if EColor16MU bitmap creation or the
same-frame parity check fails. On the E65, only `Presenter: DSA ARGB32`
confirms the requested active path; `DSA EColor64K` means fallback. Launch,
key-up behavior, runtime presenter, and FPS still require device verification.

## v1.0.22–v1.0.31 launch/import diagnostics

The user reports that the no-audio/no-DRT v1.0.22 baseline works as expected,
while v1.0.23 (native-leaves/DRT-only), v1.0.24 (DRT plus trapped MDA imports,
no playback), and v1.0.25 (DRT proxy without native leaves) show the known
“Feature not supported” popup. The user reports that v1.0.26 works on the E65.
v1.0.25 imports 12 ordinary ARM EABI/runtime helpers; it is not a one-symbol
DLL-presence test. This comparison strengthens the association with the static
DRT import path but does not prove the exact cause or that the DLL is absent.

The fifth launch-only probe is v1.0.26. Its default-off
`QUANTUM_LEVEL41_IMPORT_PROBE_DRT_LOOKUP` option calls
`RLibrary::Load("drtaeabi.dll")`, checks the 12 v1.0.25 helper ordinals with
`RLibrary::Lookup()`, then closes the library. It never invokes a looked-up
function. The E32 has no static `drtaeabi.dll` import; its three added EUser
ordinals are 1308 (`RLibrary::Load`), 1311 (`RLibrary::Close`), and 1838
(`RLibrary::Lookup`). The v1.0.26 app run alone does not confirm a successful
load or lookup: the user reported that the DRT labels were mixed with older
diagnostic lines and unreadable, so the exact values remain unknown.

v1.0.27 is a readability-only revision of that same probe, not a new import
experiment. It adds a dedicated dark DRT panel to both the GDI overlay and the
DSA ARGB bitmap overlay, suppresses the old diagnostic strings in this probe
build, and retains the walk controls/exit hint. The readout separates
`DLL LOAD ERROR`, `ORDINALS FOUND`, and `ORDINAL MASK`. Its E32 static import
table is identical to v1.0.26, and all 18 non-E32 payloads match v1.0.26
byte-for-byte. The user reports this v1.0.27 Nokia E65 readout: load error `0`,
`10/12` found, mask `3FF`. This confirms that the named DLL loaded on that run.
Bits 10 and 11 are clear, identifying lookup misses at ordinal 222
(`__aeabi_idiv`) and 223 (`__aeabi_uidiv`); the other ten lookups returned
non-null.

Both versions keep the OMAP1710 DSA ARGB32 render path. Gameplay audio and MIDI
remain disabled; the three audio files are retained only for package
consistency. The two null lookups do not prove those exports are absent from all
E65 firmware, establish that the helpers can be called, validate v1.0.25's
static import fixups, or identify the cause of its popup.

v1.0.28 is the next static-import control. Its custom `drtaeabi.dll` proxy and
final E32 were audited to contain exactly ordinals `135, 139, 140, 141, 142,
147, 148, 155, 157, 221`; ordinals `222` and `223` are excluded. The SDK's
legacy EKA2 runtime resolves `__aeabi_idiv` and `__aeabi_uidiv` locally, while
the application probe performs no dynamic lookup and makes no direct helper
call. The E32 retains the same EUser import set as v1.0.22, has no MDA/MIDI
imports, and all 18 non-E32 payloads match v1.0.27. The user reports that
v1.0.28 launched on the Nokia E65. This favors, but does not prove, the two
missing ordinals as a factor in v1.0.25: the ten-import control also resolves
the division helpers locally and uses a custom proxy. Playback and MIDI remain
disabled, so this result says nothing about audio or helper execution.

See [`output/IMPORT-PROBE-MATRIX.md`](output/IMPORT-PROBE-MATRIX.md) for the
comparison table and earlier device results. The v1.0.28 `BUILD-REPORT.md`,
`payload-manifest.json`, `e32-import-audit.json`, and `sis-audit.json` are in its
version directory and record the exact package, import table, and audit results.

## v1.0.29 and v1.0.30 single-division-import controls

The next two launch-only SIS candidates separate the two E65 lookup misses:

- **v1.0.29** adds only DRT ordinal 222 (`__aeabi_idiv`) to v1.0.28's ten
  imports. `__aeabi_uidiv` remains local.
- **v1.0.30** adds only DRT ordinal 223 (`__aeabi_uidiv`) to the same ten
  imports. `__aeabi_idiv` remains local.

Neither package includes both 222 and 223. The E32 import tables and 11-export
proxies were inspected before SIS packaging; all non-DRT imports match
v1.0.28, and the EUser imports still match v1.0.22. Both unsigned SIS files
passed the 19-payload audit, and all 18 non-E32 payloads match v1.0.28
byte-for-byte. Gameplay audio and MIDI remain disabled, with audio files
retained only for package consistency. These checks establish package and
static-import metadata, not E65 loader acceptance or successful helper
execution. The user reports that both versions show the exact “Feature not supported” popup; this strengthens the association with the two single-ordinal import controls but does not prove the exact loader failure.

The v1.0.30 filtered runtime keeps the SDK's internal `__udivsi3` routine,
which the local `__aeabi_idiv` implementation depends on, but renames that
object's public `__aeabi_uidiv` alias in the temporary archive. That leaves
external `__aeabi_uidiv` references unresolved for the custom DRT proxy at
ordinal 223. The SIS contains no runtime archive.

Downloads: [v1.0.29 ordinal-222 control](https://drive.google.com/file/d/1CscxgVwC4UbsIPE1kdESASDBT4SuPi48/view?usp=drivesdk) · [v1.0.30 ordinal-223 control](https://drive.google.com/file/d/1IZnjvqYVI_gCkVt9gMp1YUCibMmh-i0V/view?usp=drivesdk)

See each version directory for its `BUILD-REPORT.md`, `payload-manifest.json`,
`e32-import-audit.json`, and `sis-audit.json`. The candidates remain launch
checks only: no helper is explicitly called, and audio, MIDI, codec, DSP, and
performance behavior are not established.

## v1.0.31 MDA static-import-only control

v1.0.31 is a controlled follow-up to v1.0.24. It starts from the
user-reported launched v1.0.28 executable/import configuration and adds only
`mediaclientaudio.dll` ordinals `15, 35, 36, 42` (SDK proxy names:
`SetPosition`, `NewL`, `Close`, `OpenFileL`). The ten v1.0.28 DRT imports remain
unchanged; no other E32 DLL/ordinal block changes. Unlike v1.0.24, this build
has no native-leave/personality import path, so the MDA import delta is isolated
on the known-good v1.0.28 baseline.

The MDA references are retained only behind the default-false volatile
`quantumMdaImportProbeEnabled` gate. The normal path does not create an MDA
player, open a file, call `Play()`, enable MIDI, or use a guessed audio/weapon
resource path. The three audio payloads are unchanged from v1.0.28 and remain
package-consistency controls only.

The unsigned SIS is **740,924 bytes**, SHA-256
`062dda9fe06d19d3f2311b66ec272627b6c9ca4c0fb4f67a82ccc22433818a4b`, MD5
`bd1274fefef3e8f10e68a3bd8023c6a3`. It passed the 19-payload SIS audit; all 18
non-E32 payloads match v1.0.28 byte-for-byte. The E32 audit confirmed the ten
DRT imports plus exactly MDA ordinals `[15, 35, 36, 42]`; EUser is unchanged
from v1.0.22/v1.0.28 and no MIDI import is present. [Download the unsigned SIS
from Drive](https://drive.google.com/file/d/1bc9NUYZ73_TQke7UljWBdqAkg_M19pOv/view?usp=drivesdk).

**User-reported E65 result: v1.0.31 works.** Since this control adds only the
four MDA imports to the user-reported-working v1.0.28 baseline, this run did not
reproduce the popup with that import delta. It weighs against those static MDA
imports alone causing the launch failure in this configuration; it does not
explain v1.0.24's different native-leave/DRT combination. MDA initialization,
file opening, codec support, and playback were not tested. See the v1.0.31
`BUILD-REPORT.md`, `payload-manifest.json`, `e32-import-audit.json`, and
`sis-audit.json` in its version directory.
