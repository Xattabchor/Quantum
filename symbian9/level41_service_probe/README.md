# Level 41 service-import probe

This small menu app keeps the known-good raw E32/WindowServer/GDI launch path,
then exercises the extra platform services used by Level 41 without loading
or rendering its scene:

- File Server: `RFs::Connect`, opening the running executable through `RFile`,
  reading its size and first bytes.
- Symbian math: `Math::Sqrt`, `Math::Sin`, and `Math::Cos`, plus `libm` `sqrt`.

The screen displays the result codes and waits for a key. If this probe opens,
those service imports can be removed from the remaining suspects; the next
focus is Level 41 renderer initialization and its larger static/data footprint.
If it fails with “Feature not supported”, compare these imports with the E65
OS 9.1 baseline. This is diagnostic only, not a game or compatibility claim.
