# Level 41 deferred RFs::Connect probe

Version 1.0.3 draws its status screen before calling any File Server API. It
then calls `RFs::Connect` after a keypress and displays the result code if the
call returns. The user reports that v1.0.2 launches and v1.0.3 returns RFs
result code 0 (`KErrNone`) on the E65, confirming that `RFs::Connect` works.

The earlier v1.0.1 executable imported `efsrv.dll` ordinals 68 and 390. The
public ARM EABI export definition maps those to `RFs::Connect(int)` and
`RFs::Close()` respectively. Public Symbian source notes that `RFs::Close()`
was added for tracing and is absent from earlier OS versions. Version 1.0.2
closes the session through `RHandleBase::Close()` instead, avoiding that newer
EFSRV import while keeping the `RFs::Connect` test. This is a plausible cause
of the earlier startup failure, not proof of which exports are in the E65 ROM.

The v1.0.2 screen accepted no key input because its event loop compared a key
event's handle with the child window handle (2). WindowServer tags `EEventKey`
with the focused window group's client handle (1). Version 1.0.3 checks the
group handle. Its key handling still needs confirmation on the E65.

Interpretation on the E65:

- No initial screen: points to import resolution or another startup issue in
the File Server-linked executable.
- Initial screen, then no second screen after a key: input routing still needs
investigation.
- Second screen with a result code: `RFs::Connect` returned; report the
  numeric code.

This narrows the failure but is not by itself a general Symbian compatibility
claim.
