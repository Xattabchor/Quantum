# Quantum Level 41 device smoke test

This is a separate, fixed-camera S60v3 test application for the native C++
`SoftwareMeshRenderer`. It loads only the small Level 41 world mesh, grayscale
lightmap, skybox mesh, and the textures required by the scene. It renders one
static DMODE 9 view at the handset's native pixel dimensions when a bounded
framebuffer can be allocated, with a 120x160 low-memory fallback. A key exits
the test. It is not the Java game, does not contain gameplay or
controls, and is not Java pixel-parity evidence.

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

The host Level 41 mesh-renderer smoke path remains separate. A successful host
render or SIS build does not establish handset runtime compatibility. Package
version 1.0.0 reached the E65 menu entry but showed “Feature not supported”
when selected. Version 1.0.1 opens its window before loading/rendering and
reduces temporary texture memory. In EKA2L1, the screenshot showed only the sky
and a narrow horizon; version 1.0.2's periodic WindowServer flush did not make
the lower scene appear. Version 1.0.3 tried Symbian Direct Screen Access
(CDirectScreenAccess) for the static frame, with the WindowServer path retained
as a fallback. Version 1.0.4 changes the DSA output to a bitmap-backed transfer:
the CPU renderer draws into a full-screen `CWsBitmap` through
`CFbsBitmapDevice`, then DSA blits that bitmap to the display in one operation.
This reimplements the public Pixmap+DSA flow demonstrated by ESknot 1.04; it
does not import its GLES/EGL renderer, since Level 41 remains software-rendered.
The DSA path enables `SetAutoUpdate(ETrue)` and preserves the WindowServer GDI
fallback. Version 1.0.5 renders at actual display dimensions when its framebuffer
fits the memory cap and can be allocated; otherwise it falls back to 120x160.
Projection is scaled uniformly to preserve the scene's shape, removing the
intentional nearest-neighbor enlargement on typical 240x320 displays at the
cost of more software-rendering work. Version 1.0.6 removed the explicit
`CFbsScreenDevice::Update()` call while investigating the E65's OS 9.1 import
baseline. The user then confirmed that the same “Feature not supported” message
still appears when launching v1.0.6, so removing that call was not sufficient
and no root cause is established. The separate `level41_launch_probe` target builds a minimal WindowServer/GDI
menu app with the same raw E32 startup and registration. The user reports that
this probe launches on the E65, narrowing the failure to dependencies or
initialization specific to the Level 41 app rather than the shared basic launch
path. A GDI-only Level 41 variant now compiles out DSA and BitGdi to isolate that
boundary. The scene candidates remain unverified on the physical E65; emulator
output does not establish handset compatibility.
