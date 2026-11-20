# MDA player initialization probe — stage 1

This is a separate, minimal E65 diagnostic based on the known WindowServer/GDI
launch path. Its only MDA operation is one call to
`CMdaAudioPlayerUtility::NewL(observer)`. If the factory returns, the player is
deleted immediately.

The probe contains no call to `OpenFileL`, `OpenDesL`, `OpenUrlL`,
`SetPosition`, `Close`, or `Play`; it has no guessed asset path or audio
payload. MIDI, gameplay audio, output streams, and other audio APIs are not
linked. The two required observer callbacks are empty.

The corrected source calls `NewL` from an active object's `RunL`; its
`RunError` records a leave code without a direct `TTrap::Trap/UnTrap` import.
An explicit panic still terminates this single-thread probe, so the companion
thread diagnostic is used to capture such panics. The active scheduler is not
used to open media or start playback.

## E65 observation: v1.0.1 harness was invalid

The user reports that the v1.0.1 signed candidate showed `NewL: starting...`
and then exited. The companion v1.0.1 thread diagnostic displayed
`E32USER-CBase`, panic reason 66. Symbian's `CleanupStack::PushL` reference
defines reason 66 as a call to `PushL` without a preceding `TRAP`.

Both v1.0.1 probes deliberately called `NewL` untrapped because the SDK 0.3.0
`euser.dso` import proxy does not export `TTrap::Trap` / `TTrap::UnTrap`.
Thus the observed panic is explained by the probe's missing leave context; it
is **not evidence that MDA initialization itself failed**. No valid result for
`NewL` has been obtained yet. The source has been revised to use
`CActive::RunL` / `RunError`. The corrected worker-thread v1.0.2 candidate is
built and signed at
[`level41_mda_thread_diag`](../level41_mda_thread_diag/README.md); run that
candidate from Drive before drawing a conclusion about MDA.

The SDK documentation for the panic code is in the
[CleanupStack reference](https://devlib.symbian.slions.net/belle/GUID-C6E5F800-0637-419E-8FE5-1EBB40E725AA/GUID-0DD554D7-B9B1-3FD3-898F-4D855144FCEA.html).

The device-test workflow previously generated the raw SIS package
`quantum_level41_mda_init_test.sis` and signed candidate
`quantum_level41_mda_init_test-e65-candidate.sisx`; those v1.0.1 artifacts
contain the untrapped call and must not be used to infer MDA behavior.
