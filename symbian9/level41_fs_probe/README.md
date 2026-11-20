# Level 41 File Server probe

This is the File Server-only half of the Level 41 service probe. It uses the
known-good raw E32/WindowServer/GDI launch path, then checks `RFs::Connect` and
`RFile::Open`/`Size`/`Read` on its own executable. The screen shows each result
code. It does not link Symbian Math/libm or the mesh renderer. Version 1.0.1
also includes the raw WindowServer key-event handle fix: input is matched to
group client handle 1, not the child window handle 2. E65 confirmation is
still required.

The user previously reported “Feature not supported” from this File Server
profile while the Math-only probe launched. The File Server probe combines
`RFs::Connect` with `RFile` operations. Version 1.0.1 also contains the
WindowServer key-event handle fix, but E65 testing is still needed to determine
whether the File Server profile itself now starts.
