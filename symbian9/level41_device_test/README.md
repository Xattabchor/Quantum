# Quantum Level 41 device smoke test

This is a separate, fixed-camera S60v3 test application for the native C++
`SoftwareMeshRenderer`. It loads only the small Level 41 world mesh, grayscale
lightmap, skybox mesh, and the textures required by the scene. It renders one
static DMODE 9 view at the handset's native pixel dimensions when a bounded
framebuffer can be allocated, with a 120x160 low-memory fallback. A key exits
the test. It is not the Java game, does not contain gameplay or
controls, and is not Java pixel-parity evidence.

The raw WindowServer test originally compared key events against child window
handle 2. WindowServer tags `EEventKey` with the focused window group's client
handle 1, so no key reached the exit path. Version 1.0.7 uses the group handle
for keyboard events and the child handle only for redraws. The main executable
also used `RFs::Close()`; unlike the deferred probe, it still imported EFSRV
ordinal 390, an export added later for tracing. Version 1.0.8 closes through
`RHandleBase::Close()` to avoid that import. CI inspection confirms ordinal
390 is absent from full v1.0.9 and GDI-only v1.0.3; v1.0.9 also adds a stage
label to asset/render failures. Both executables still import EFSRV ordinals 68
(`RFs::Connect`) and 93, 264, 300, 257 for `RFile` operations; E65 runtime
confirmation is still required.

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
- Full Level 41 v1.0.8 reaches its startup screen at 240x320, then exits before
  showing the scene. The exact failing step is not yet known; DSA remains a
  likely point to isolate.
- GDI-only v1.0.2 shows `Asset/render error: -20`. Inspection found that this
  package was staged by the generic probe packager without the seven scene
  assets, and the no-DSA executable still looked in the full app's private
  directory. This is not yet a valid test of the renderer.
- GDI-only v1.0.3 now packages the shared assets under its own SID
  (`e000a016`). Its failure screen reports the asset/render stage so another
  E65 result can distinguish file packaging from parsing or rasterization.

Testing the corrected GDI-only package first will isolate the shared renderer;
comparing it with full v1.0.8 will then isolate the DSA path. Neither result is
known until the new package is tested on the E65.
