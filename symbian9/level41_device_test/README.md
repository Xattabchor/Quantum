# Quantum Level 41 device smoke test

This is a separate, fixed-camera S60v3 test application for the native C++
`SoftwareMeshRenderer`. It loads only the small Level 41 world mesh, grayscale
lightmap, skybox mesh, and the textures required by the scene. It renders one
static DMODE 9 view at 120x160, then scales the result to the handset display.
A key exits the test. It is not the Java game, does not contain gameplay or
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
reduces temporary texture memory; the EKA2L1 screenshot shows that only the sky
and a narrow horizon are painted, while the lower scene remains black. Version
1.0.2 flushes WindowServer drawing commands every eight scanlines to bound the
large per-pixel GDI batch. This is a targeted rendering-display change, not a
confirmed root-cause fix; EKA2L1 and physical E65 retests are still required.
None of these versions is considered device-verified, and this test does not
imply support for every S60v3 model.
