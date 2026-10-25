# Level 41 service-import probe

This small menu app keeps the known-good raw E32/WindowServer/GDI launch path,
then exercises the extra platform services used by Level 41 without loading
or rendering its scene:

- File Server: `RFs::Connect`, opening the running executable through `RFile`,
  reading its size and first bytes.
- Symbian math: `Math::Sqrt`, `Math::Sin`, and `Math::Cos`, plus `libm` `sqrt`.

The screen displays the result codes and waits for a key. The user reports that
this combined probe also shows “Feature not supported”. The Math-only app
launches, but the File Server-only app and a pre-UI `RFs::Connect`-only app
both show “Feature not supported”. Deferred RFs probe v1.0.1 also did not
launch. Its E32 imports include `efsrv.dll` ordinals 68 (`RFs::Connect(int)`)
and 390 (`RFs::Close()`) per the public ARM EABI definition. Public Symbian
source notes that `RFs::Close()` was added for tracing and was absent from
earlier OS versions, so a missing ordinal 390 is a strong candidate for the
startup failure, but its absence from the E65 ROM is not confirmed. The next
candidate v1.0.2 calls `RHandleBase::Close()` to avoid that newer EFSRV import
and leaves `RFs::Connect` as the deferred test. This is diagnostic only, not
a game or compatibility claim.
