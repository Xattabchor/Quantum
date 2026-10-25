# Level 41 RFs::Connect-only probe

The full File Server-only probe fails on the E65 while the Math-only probe
launches. This smaller variant calls only `RFs::Connect()` and closes the
session before displaying its result; it does not call `RProcess::FileName()` or
any `RFile` method.

If this launches, the File Server failure is later in the executable-path or
`RFile` operations. If it still shows “Feature not supported”, the failure is
narrowed to the `RFs` connection/import path or another startup dependency
shared by that profile. The on-device result is still required to distinguish
those cases.
