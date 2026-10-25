# Level 41 GDI-only diagnostic

This companion SISX runs the same Level 41 mesh loading and software renderer,
but compiles out Direct Screen Access, `CWsBitmap`/`CFbsBitmapDevice`,
`SetAutoUpdate`, and the BitGdi import. It uses the existing WindowServer GDI
fallback to display the scene.

The minimal `level41_launch_probe` already launched on the E65, confirming that
the shared raw E32 startup, registration and basic WindowServer/GDI path can
run there. Testing this variant next isolates whether the remaining launch
failure is specific to DSA/BitGdi or comes from another Level 41 dependency
(such as asset loading, math imports or renderer initialization).

This is a temporary diagnostic package, not a replacement for the main Level
41 candidate and not a general S60v3 compatibility claim. On slower devices,
wait for the software scene to finish drawing after the startup message.
