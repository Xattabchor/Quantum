# Level 41 service-import probe

This small menu app keeps the known-good raw E32/WindowServer/GDI launch path,
then exercises the extra platform services used by Level 41 without loading
or rendering its scene:

- File Server: `RFs::Connect`, opening the running executable through `RFile`,
  reading its size and first bytes.
- Symbian math: `Math::Sqrt`, `Math::Sin`, and `Math::Cos`, plus `libm` `sqrt`.

The screen displays the result codes and waits for a key. The user reports that
this combined probe also shows “Feature not supported”. Separate File Server
and Math-only menu apps are now built to identify which import group triggers
the launch failure. This is diagnostic only, not a game or compatibility claim.
