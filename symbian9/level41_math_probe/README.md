# Level 41 math probe

This is the math-only half of the Level 41 service probe. It uses the
known-good raw E32/WindowServer/GDI launch path, then calls Symbian
`Math::Sqrt`, `Math::Sin`, `Math::Cos` and `libm` `sqrt`. The screen shows each
result code. It does not link File Server or the mesh renderer.

The user reports that this probe launches on the E65. That makes Symbian
Math/libm less likely to be the cause of the combined service-probe failure,
but does not yet verify the return values shown on screen or general device
compatibility.
