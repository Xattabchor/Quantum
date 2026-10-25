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

The GitHub Actions workflow builds the ARMv5T/EKA2 executable, creates fresh
registration resources and a standard SIS v9/SISX package, and publishes the
resulting candidate in this directory. The package is self-signed for legacy
Symbian testing; this does not bypass the handset's installation policy.

The host Level 41 mesh-renderer smoke path remains separate. A successful host
render or a successful SIS build does not establish that the package installs,
that the E65 runtime supports every import, or that the renderer performs well
on device. Until the E65 test is reported, this candidate is not device-verified
and does not imply support for every S60v3 model.
