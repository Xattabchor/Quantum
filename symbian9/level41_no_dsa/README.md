# Level 41 GDI-only diagnostic

This companion SISX runs the same Level 41 mesh loading and software renderer,
but compiles out Direct Screen Access, `CWsBitmap`/`CFbsBitmapDevice`,
`SetAutoUpdate`, and the BitGdi import. It uses the existing WindowServer GDI
fallback to display the scene.

Version 1.0.3 includes the raw WindowServer input fix and closes the File
Server session through `RHandleBase::Close()`, avoiding the later `RFs::Close()`
EFSRV ordinal 390. It also packages all seven Level 41 assets under this
executable's own private directory (`e000a016`). The preceding v1.0.2 package
used a generic probe packager and omitted those assets; its `-20` screen was
therefore not evidence of a renderer failure. CI inspection confirms ordinal
390 is absent and the assets are included. E65 rendering still needs
confirmation.

The minimal `level41_launch_probe` launched on the E65, confirming that the
shared raw E32 startup and basic WindowServer/GDI path can run there. An earlier
GDI-only package showed “Feature not supported”; v1.0.2 then reached its
`Asset/render error: -20` screen, but that package omitted the scene assets, so
neither result isolated the renderer. Version 1.0.3 is the first self-contained
GDI-only render test. The combined File Server/math and File Server-only probes
previously showed “Feature not supported”, while the Math-only probe launched.
The deferred `RFs::Connect` probe now returns 0 on the E65, confirming Connect
itself succeeds.

This is a temporary diagnostic package, not a replacement for the main Level
41 candidate and not a general S60v3 compatibility claim. On slower devices,
wait for the software scene to finish drawing after the startup message.
