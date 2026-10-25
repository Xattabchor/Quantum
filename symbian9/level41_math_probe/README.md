# Level 41 math probe

This is the math-only half of the Level 41 service probe. It uses the
known-good raw E32/WindowServer/GDI launch path, then calls Symbian
`Math::Sqrt`, `Math::Sin`, `Math::Cos` and `libm` `sqrt`. The screen shows each
result code. It does not link File Server or the mesh renderer.

If it shows the status screen, math imports are not the reason the combined
service probe failed. If it produces “Feature not supported”, the Symbian
Math/libm group is isolated as the likely source.
