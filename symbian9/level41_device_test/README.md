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
- The v1.0.11 candidate adds a ten-second repeated-render benchmark for the
  fixed Level 41 DMODE 9 view. It reports end-to-end device FPS and average /
  maximum time inside `SoftwareMeshRenderer::renderScene()`. It has not yet
  been measured on the E65; a successful CI build is not a device result.
