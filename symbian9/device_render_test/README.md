# Quantum device-render smoke test

This separate S60v3 diagnostic app exercises the native software rasterizer on a
handset. It draws the same textured trapezoid twice into an in-memory 64x64 RGB
framebuffer: affine mapping on the left and perspective-correct mapping on the
right. The right panel uses the native perspective scanline kernel with a
12-pixel correction interval.

It is deliberately not the Quantum game or the Level 41 scene: the current
Avkon shell does not yet call the renderer. This small test isolates the
rasterizer and avoids depending on external game assets.

The `device-render-test.yml` workflow still packages through Symbian Platform
0.3.0's experimental SIS writer. Its checked-in output,
`quantum_render_device_test-signed.sis`, was rejected by a Nokia E65 with
`file corrupted`; treat that file as obsolete and do not install it.

A replacement candidate is available as
`quantum_render_device_test-e65-candidate.sisx` (32,940 bytes; SHA-256
`7f21a2048f181d685985c24fcaf728913646ee963f7f1e0a7740ab5c89be0b5e`). It was
repackaged from the same four payloads using GnuPoc MakeSIS 4 and SignSIS, the
SIS v9 toolchain for Symbian OS 9. The four embedded payload SHA-1 hashes match
the original files. The packer's 19 SIS and one signed-SISX regression fixtures
passed, and its parser can read the generated controller and signature. The
Symbian Platform inspector cannot inspect this packer's compressed SIS streams,
so that tool provides no additional verdict on this candidate.

The candidate is self-signed with a 1024-bit RSA/SHA-1 certificate valid from
2000 through 2045; this legacy signing format does not bypass the phone's
installation policy. No installation or launch test has yet been performed on
the E65; the candidate is not being claimed as device-verified or as proof of
support for every S60v3 model. Do not change phone security settings just to
force installation. If the standard installer accepts it, launch **Quantum
Render Test**, inspect the affine/perspective panels, then press a key to exit.
The native loader, display and performance still require real-device checks.
Version 1.0.1 also corrects the raw WindowServer key filter: `EEventKey`
uses the focused window group's client handle (1), not the child window handle
(2). Input on the E65 still needs confirmation.
