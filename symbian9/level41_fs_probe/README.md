# Level 41 File Server probe

This is the File Server-only half of the Level 41 service probe. It uses the
known-good raw E32/WindowServer/GDI launch path, then checks `RFs::Connect` and
`RFile::Open`/`Size`/`Read` on its own executable. The screen shows each result
code. It does not link Symbian Math/libm or the mesh renderer.

On the E65, the user reports that this probe also shows “Feature not
supported”, while the Math-only probe launches. This narrows the failure to the
File Server-specific profile, but this probe combines `RFs::Connect` with
`RFile` operations. A version 1.0.1 deferred `RFs::Connect` probe draws a
screen before the call, then lets the user trigger it, to separate
import/startup failure from the connection call itself.
