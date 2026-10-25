# Level 41 GDI-only diagnostic

This companion SISX runs the same Level 41 mesh loading and software renderer,
but compiles out Direct Screen Access, `CWsBitmap`/`CFbsBitmapDevice`,
`SetAutoUpdate`, and the BitGdi import. It uses the existing WindowServer GDI
fallback to display the scene.

The minimal `level41_launch_probe` launched on the E65, confirming that the
shared raw E32 startup, registration and basic WindowServer/GDI path can run
there. This variant was intended to isolate DSA/BitGdi from the rest of Level
41. The user reports that it still shows “Feature not supported”, so DSA/BitGdi
alone are not the cause. File Server, math/libm, renderer code and static/runtime
requirements remain candidates. The combined probe and File Server-only probe
show “Feature not supported” on the E65, while the Math-only probe launches.
A deferred `RFs::Connect` probe now draws first, then runs the connection call
on a key press to distinguish startup/import failure from the runtime call.

This is a temporary diagnostic package, not a replacement for the main Level
41 candidate and not a general S60v3 compatibility claim. On slower devices,
wait for the software scene to finish drawing after the startup message.
