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
with and without the cache on both projection paths.

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
- The v1.0.15 candidate also reuses cached projections in the DirectX7 draw
  pass. Its E65 performance has not yet been measured.
