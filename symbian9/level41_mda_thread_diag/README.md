# MDA NewL thread diagnostic

This companion test isolates exactly one MDA call,
`CMdaAudioPlayerUtility::NewL(observer)`, in a worker thread. The call runs
from an active object's `RunL`; its `RunError` records a leave code without a
direct `TTrap::Trap` / `TTrap::UnTrap` import. An explicit worker panic is
reported by `RThread::Logon`, including category and reason. If the factory
returns, the player is deleted immediately.

No file is opened, no `Play` method is called, and MIDI and all other audio
paths remain disabled.

## v1.0.1 E65 result: harness panic, not an MDA result

The user observed `E32USER-CBase`, reason 66. The Symbian `CleanupStack`
reference says reason 66 is raised when `CleanupStack::PushL` is called without
a prior `TRAP`. The v1.0.1 worker called `NewL` outside a leave context because
SDK 0.3.0's `euser.dso` proxy omits `TTrap::Trap` and `TTrap::UnTrap`.
Therefore that result is explained by the test harness and does not establish
whether the E65 can initialize an MDA player.

## Corrected v1.0.2 candidate

The corrected worker executes `NewL` inside `CActive::RunL`; a leave is caught
by `RunError`, while a panic is still isolated to the worker. The E32 import
table has only `mediaclientaudio.dll` ordinal 35 (the MDA `NewL` export); no
file-open or playback API is imported. The generated E32 is ARMv5T/EKA2,
UID3 `0xE000A028`, and has no capabilities. Its SIS package UID is
`0xE000A029`, version 1.0.2. The signed SISX is 46,788 bytes with SHA-256
`ecae219f7904793e9d7a88e19dd0f831e49ff413846df7cef974bd36f75601de`.

[Download the signed E65 candidate from Drive](https://drive.google.com/file/d/1K7MHuEmePBb8WKBxPDdWPl6HCbfDK6Ae/view?usp=drivesdk).

The candidate uses a self-signed test certificate and has a new package/UID3
pair, so it is separate from the old v1.0.1 unsigned test. Package inspection
and signature verification passed locally.

**User-reported E65 result:** `NewL returned; player deleted`. This confirms
that the MDA player factory returned normally and the object was deleted in
this probe. It does not test opening a file, codec initialization, playback, or
audible output. The user also saw `NewL called inside TRAP`; that footer is
stale text in the shipped v1.0.2 screen. The actual code calls `NewL` from
`CActive::RunL` and reports leaves through `RunError`, without a direct
`TTrap::Trap` import. The source footer has been corrected; no rerun is needed
for this text-only change.

The next isolated step was the separate
[`level41_mda_open_des_diag`](../level41_mda_open_des_diag/README.md)
candidate. It retained the successful `NewL` call and tested `OpenDesL` on a
silent in-memory WAV descriptor, still without filesystem access, `Play`, or
MIDI. The user-reported E65 result was `MapcInitComplete: success`,
`Duration: 100 ms`.
