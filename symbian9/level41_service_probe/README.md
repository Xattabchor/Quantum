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
both show “Feature not supported”. Version 1.0.1 draws its screen before calling
`RFs::Connect`, to distinguish an import/startup failure from the runtime call.
The exact cause is not yet established. This is diagnostic only, not a game or
compatibility claim.
