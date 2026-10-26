# Quantum Level 41 device smoke test

This is a separate, fixed-camera S60v3 test application for the native C++
`SoftwareMeshRenderer`. It loads only the small Level 41 world mesh, grayscale
lightmap, skybox mesh, and the textures required by the scene. It repeatedly
renders a fixed-camera DMODE 9 view at the handset's native pixel dimensions
when a bounded framebuffer can be allocated, with a 120x160 low-memory
fallback. The tested GDI build displays a 10-second end-to-end FPS average,
plus average/maximum time spent inside `SoftwareMeshRenderer::renderScene()`.
The FPS includes framebuffer clear, software rendering, WindowServer GDI
presentation, and redraw scheduling; the mesh timing isolates the renderer
call. Asset loading is done once before timing. After the sample, the final
results remain on screen; any key exits. This is a renderer performance
smoke-test for one Level 41 view, not a measure of the complete game or Java
pixel-parity evidence.

The raw WindowServer test originally compared key events against child window
handle 2. WindowServer tags `EEventKey` with the focused window group's client
handle 1, so no key reached the exit path. Version 1.0.7 uses the group handle
for keyboard events and the child handle only for redraws. The main executable
also used `RFs::Close()`; unlike the deferred probe, it still imported EFSRV
ordinal 390, an export added later for tracing. Version
1.0.8 closes through `RHandleBase::Close()` to avoid that import. Version 1.0.9
added a stage label to asset/render failures. After the user confirmed that the
self-contained GDI-only v1.0.3 renders on the E65 while the DSA-enabled full
v1.0.9 exits after startup, v1.0.10 compiles DSA out of the main app and uses
the tested WindowServer GDI path. The user confirms v1.0.10 works on the E65.
CI inspection confirms ordinal 390 and the `BitGdi` import are absent from
v1.0.10; the EFSRV `RFile` imports remain. DSA stays disabled in the main
candidate until that path is repaired and verified separately.

The user's E65 measurement of v1.0.12 was 0.3 end-to-end FPS, with 111/112 ms
average/maximum inside `SoftwareMeshRenderer::renderScene()`, for 78 polygons
and 148 triangles. This points to WindowServer presentation as the dominant
cost in that build. Version 1.0.13 added a WindowServer GDI bitmap presenter:
it writes RGB565 pixels into an `EColor64K` `CWsBitmap` and submits the frame
with one `BitBlt`, while keeping the per-pixel GDI path as a fallback. The E65
reported 5.6 FPS with `Presenter: bitmap/bitblt`; mesh time remained
111/113 ms for the same 78 polygons and 148 triangles. The output change
therefore produced a large measured end-to-end improvement, but this remains
one fixed scene, not full-game FPS or renderer-path coverage. Bitmap access
uses the legacy `LockHeap`/`UnlockHeap` API rather than the later
`BeginDataAccess`/`EndDataAccess` calls; this is not a compatibility claim for
OS 9.1.

Version 1.0.14 adds an optional projected-vertex workspace to the native mesh
renderer. The Level 41 benchmark supplies that scratch buffer for the
visibility/queue pass. The E65 reported 5.7 FPS and 108/109 ms mesh
average/maximum, with bitmap BitBlt still active. Review then found that the
DirectX7 draw pass still recalculated projected positions for every polygon.
Version 1.0.15 reuses the cached coordinates in that final draw pass as well;
callers that omit the optional buffer retain the original projection path. A
local differential check matched framebuffer output and renderer statistics
with and without the cache on both projection paths. The E65 then reported
5.8 FPS and 108/109 ms mesh average/maximum for the same scene. Version 1.0.16
adds opt-in timing for validation, transform/projection, polygon queueing,
sorting, and drawing/rasterization. Its E65 run reported 5.7 FPS and 108/110 ms
mesh average/maximum, with phase averages of 5/7/17/0/79 ms respectively.
Drawing/rasterization is the dominant measured phase (about 73% of mesh time).
The added clock reads make this a diagnostic build; the phase times are
approximate and its FPS may include a small profiling cost. The E65 measured
v1.0.17 at 5.6 FPS and 109/111 ms mesh average/maximum, with the same 5/7/17/0/79
ms phase breakdown. That short run did not show a measurable improvement in
the dominant raster phase. Version 1.0.18 adds a branch-free mode-0 copy path
for textures explicitly guaranteed not to contain the transparent-zero key;
all other texture views retain the conservative behavior. The Level 41 asset
loader can make that guarantee from its RGBA alpha bytes. Host tests pass, and
the Level 41 host framebuffer is pixel-identical with and without the fast path.
The user measured v1.0.18 on the E65 at 5.8 FPS and 106/107 ms mesh
average/maximum, with phase averages of 5/7/17/0/76 ms (validation,
transform/projection, cull/queue, sort, draw/raster). Compared with v1.0.17,
that is a 0.2 FPS gain, 3 ms lower average mesh time, and 3 ms lower
raster time in this same fixed-scene benchmark.

Version 1.0.19 uses the successful untimed warm-up render to establish that
Level 41's loaded mesh, material, and texture data passed full validation.
Subsequent frames skip only the repeated per-polygon and texture-content
validation loop; renderer envelope, pointer, capacity, and runtime option
checks remain active. The optimization is opt-in in the general renderer and
requires the caller to keep validated mesh/material/texture data immutable.
The Level 41 host render produces a byte-identical framebuffer with and
without prevalidation. A host renderer check also confirms that its envelope
and capacity checks still reject an undersized workspace in prevalidated mode.
The E65 reported v1.0.19 at 5.9 FPS and 100/101 ms mesh average/maximum,
with bitmap BitBlt and phase averages of 0/7/17/0/76 ms. Validation fell from
5 ms to 0 ms; draw/raster remained 76 ms, and average mesh time fell by 6 ms
from v1.0.18.

Version 1.0.20 extends the zero-key-free pixel specialization to mode-9
shading in both affine and perspective rasterizers, but only when the texture
view guarantees that no texel equals the transparent-zero key. The mode-9
shading arithmetic is unchanged; texture views with the conservative default
still perform the key test. A Level 41 host differential render using the
opaque-texture guarantees is byte-identical to the conservative render.
The E65 reported v1.0.20 at 5.9 FPS and 100/103 ms mesh average/maximum,
with bitmap BitBlt and phase averages of 0/7/17/0/76 ms. This is no measurable
change in FPS, average mesh time, or phase breakdown versus v1.0.19.

Version 1.0.21 uses the projected-vertex workspace cache without constructing
full temporary camera/projected vertices for every polygon in the cull/queue
pass. The cached path gathers only camera depth and projected x/y coordinates
needed for culling, sorting, and mip selection; callers without the optional
cache retain the previous projection path. The 720x960 Level 41 host render
with the cached path is byte-identical to the previous render. Device
performance is pending.

The necessary Level 41 inputs are intentionally limited to the seven files in
`assets/level41/`. No SDK, build tool, certificate, or private signing key is
stored here. The host asset-preparation script converts the four PNG/JPEG
textures to a small RGBA format; the E65 app reads those files and executes the
native mesh traversal and rasterizer on the phone.

The Level 41 CMake target is opt-in with
`-DQUANTUM_BUILD_LEVEL41_DEVICE_TEST=ON`, so the earlier small renderer test
continues to build independently. The GitHub Actions workflow enables that
option, builds the ARMv5T/EKA2 executable, creates fresh registration resources
and a standard SIS v9/SISX package, and publishes the resulting candidate in
this directory. The package is self-signed for legacy Symbian testing; this
does not bypass the handset's installation policy.

The host Level 41 mesh-renderer smoke path remains separate: a successful host
render or SIS build does not establish handset runtime compatibility.

Current E65 feedback:

- The minimal `level41_launch_probe` starts, and the deferred RFs Connect probe
  v1.0.3 returns code 0 (`KErrNone`).
- GDI-only v1.0.3 works on the E65, confirming the packaged assets and the
  shared software-rendering/WindowServer GDI path.
- DSA-enabled full v1.0.9 closes after showing its startup screen (`Starting
  native renderer`). Since the GDI build works with the same renderer, DSA is
  the remaining differentiating path.
- Main v1.0.10 uses the tested GDI path with DSA compiled out; the user
  confirms it works on the E65.
- The v1.0.12 E65 baseline was 0.3 FPS, 111/112 ms mesh average/maximum,
  78 polygons, and 148 triangles.
- The v1.0.13 E65 run reported 5.6 FPS and 111/113 ms mesh average/maximum,
  with `Presenter: bitmap/bitblt`; the polygon and triangle counts were
  unchanged. This confirms a substantial output-path improvement on this
  scene, not full-game performance.
- The v1.0.14 E65 run reported 5.7 FPS and 108/109 ms mesh average/maximum,
  with the bitmap BitBlt presenter; counts remained unchanged.
- The v1.0.15 E65 run reported 5.8 FPS and 108/109 ms mesh average/maximum,
  with bitmap BitBlt active; it rendered 78 polygons and 148 triangles.
- The v1.0.16 E65 run reported 5.7 FPS, 108/110 ms mesh average/maximum,
  bitmap BitBlt, and phase averages: validation 5 ms, transform/projection
  7 ms, cull/queue 17 ms, sort 0 ms, draw/raster 79 ms. Rasterization is the
  largest measured phase on this benchmark.
- The v1.0.17 E65 run reported 5.6 FPS, 109/111 ms mesh average/maximum,
  bitmap BitBlt, and phase averages: validation 5 ms, transform/projection
  7 ms, cull/queue 17 ms, sort 0 ms, draw/raster 79 ms. It did not show a
  measurable reduction in the dominant phase.
- The v1.0.18 E65 run reported 5.8 FPS, 106/107 ms mesh average/maximum,
  bitmap BitBlt, and phase averages: validation 5 ms, transform/projection
  7 ms, cull/queue 17 ms, sort 0 ms, draw/raster 76 ms. Against v1.0.17,
  FPS increased by 0.2 and both mesh average and draw/raster time fell by 3 ms.
- The v1.0.19 E65 run reported 5.9 FPS, 100/101 ms mesh average/maximum,
  bitmap BitBlt, and phase averages: validation 0 ms, transform/projection
  7 ms, cull/queue 17 ms, sort 0 ms, draw/raster 76 ms. Validation fell from
  5 ms to 0 ms and average mesh time fell by 6 ms versus v1.0.18; raster time
  remained unchanged.
- The v1.0.20 E65 run reported 5.9 FPS, 100/103 ms mesh average/maximum,
  bitmap BitBlt, and phases of 0/7/17/0/76 ms. This is no measurable change
  versus v1.0.19 despite skipping mode-9 zero-key checks on opaque textures.
- The v1.0.21 candidate reduces per-polygon temporary vertex copying in the
  cull/queue pass when the optional projected-vertex cache is supplied. Host
  output is byte-identical; device performance is pending.
