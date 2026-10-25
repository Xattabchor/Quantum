# Level 41 service-import probe

This small menu app keeps the known-good raw E32/WindowServer/GDI launch path,
then exercises the extra platform services used by Level 41 without loading
or rendering its scene:

- File Server: `RFs::Connect`, opening the running executable through `RFile`,
  reading its size and first bytes.
- Symbian math: `Math::Sqrt`, `Math::Sin`, and `Math::Cos`, plus `libm` `sqrt`.

The Math-only app launches on the E65. The File Server-only and combined
profiles previously showed “Feature not supported”. Deferred RFs probe v1.0.1
did not launch; its `efsrv.dll` imports included ordinals 68 (`RFs::Connect`)
and 390 (`RFs::Close`). Public Symbian source says `RFs::Close()` was added
for tracing and was absent from earlier OS versions. Version 1.0.2 replaced
that call with `RHandleBase::Close()`; the user reports v1.0.2 now launches on
the E65. Its import table contains only `efsrv.dll` ordinal 68, though the
missing-export explanation remains a hypothesis until confirmed against the
E65 ROM.

A separate input bug affected every raw WindowServer test: the code matched
`EEventKey` against the child window handle (2), while WindowServer tags a key
event with the focused window group's client handle (1). The fix is included
in the rebuilt packages: combined, File Server, and Math probes v1.0.1 and the
deferred RFs probe v1.0.3. Device confirmation is still needed. This remains a
diagnostic suite, not a game or compatibility claim.
