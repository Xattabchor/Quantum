# Quantum device-render smoke test

This separate S60v3 diagnostic app exercises the native software rasterizer on a
handset. It draws the same textured trapezoid twice into an in-memory 64x64 RGB
framebuffer: affine mapping on the left and perspective-correct mapping on the
right. The right panel uses the native perspective scanline kernel with a
12-pixel correction interval.

It is deliberately not the Quantum game or the Level 41 scene: the current
Avkon shell does not yet call the renderer. This small test isolates the
rasterizer and avoids depending on external game assets.

The `device-render-test.yml` workflow builds an ARMv5T/EKA2 SIS using the
public Symbian Platform 0.3.0 SDK release. It downloads the SDK at job time;
SDK binaries are not committed, and the workflow does not retain package
artifacts. The current self-signed installer is checked in here as
`quantum_render_device_test-signed.sis`. Signing does not bypass the phone's
installation policy. Launch **Quantum Render Test**, inspect the
affine/perspective panels, then press a key to exit.

A successful cloud build proves compilation and SIS structure only. Actual
loader, screen and performance behavior still need to be checked on the target
phone; this smoke app does not establish support for every S60v3 model.
