# Level 41 deferred RFs::Connect probe

Version 1.0.2 deliberately draws its status screen before calling any File
Server API. It then calls `RFs::Connect` after a keypress and displays the
result code if the call returns.

The earlier v1.0.1 executable imported `efsrv.dll` ordinals 68 and 390. The
public ARM EABI export definition maps those to `RFs::Connect(int)` and
`RFs::Close()` respectively. Public Symbian source notes that `RFs::Close()`
was added for tracing and is absent from earlier OS versions. Version 1.0.2
closes the session through `RHandleBase::Close()` instead, intended to avoid
that newer EFSRV import while keeping the `RFs::Connect` test. This is a
compatibility hypothesis to test on the E65, not proof of which ROM export is
missing.

Interpretation on the E65:

- No initial screen: points to import resolution or another startup issue in
the File Server-linked executable. If v1.0.2 has no `efsrv.dll` ordinal 390,
this further narrows startup failures but does not prove ordinal 68 is present.
- Initial screen, then failure after the key: points to the runtime connection
call or its interaction with the running app.
- Second screen with a result code: the call returned; report the numeric code.

This narrows the failure but is not by itself a general Symbian compatibility
claim.
