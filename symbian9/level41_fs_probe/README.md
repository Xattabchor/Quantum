# Level 41 File Server probe

This is the File Server-only half of the Level 41 service probe. It uses the
known-good raw E32/WindowServer/GDI launch path, then checks `RFs::Connect` and
`RFile::Open`/`Size`/`Read` on its own executable. The screen shows each result
code. It does not link Symbian Math/libm or the mesh renderer.

If it shows the status screen, File Server imports are not the reason the
combined service probe failed. If it produces “Feature not supported”, the
File Server import/runtime group is isolated as the likely source.
