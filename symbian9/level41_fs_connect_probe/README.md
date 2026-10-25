# Level 41 deferred RFs::Connect probe

Version 1.0.1 deliberately draws its status screen before calling any File
Server API. The E32 executable still contains the `RFs::Connect` reference, so
an import/startup failure should happen before the screen. Press any key on the
initial screen to call `RFs::Connect`; if it returns, the result code is shown
on a second screen.

Interpretation on the E65:

- No initial screen: points to import resolution or another startup issue in
the File Server-linked executable.
- Initial screen, then failure after the key: points to the runtime connection
call or its interaction with the running app.
- Second screen with a result code: the call returned; report the numeric code.

This narrows the failure but is not by itself a general Symbian compatibility
claim.
