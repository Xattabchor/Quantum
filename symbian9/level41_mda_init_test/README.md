# MDA player initialization probe — stage 1

This is a separate, minimal E65 diagnostic based on the known WindowServer/GDI
launch path. It adds only the MDA sample-player API surface and makes one
runtime call: `CMdaAudioPlayerUtility::NewL(observer)`. The returned object is
deleted immediately after the factory returns.

The probe does **not** call the MDA utility's `OpenFileL`, `OpenDesL`,
`OpenUrlL`, `SetPosition`, `Close`, or `Play`; it contains no guessed asset
path or audio payload. MIDI, gameplay audio, output streams, and other audio
APIs are not linked. The two required observer callbacks are empty.

The on-screen result reports whether `NewL` returned a player object or
reported an error. A successful return demonstrates only that this factory
call returned in this app configuration. It does not demonstrate media/file
initialization, codec support, DSP access, or audible playback. If E65 shows
“Feature not supported” before the screen appears, record that as a launch
result for this exact candidate; it still does not identify the unavailable
component.

The device-test CI workflow generates both the raw SIS package
`quantum_level41_mda_init_test.sis` and the signed E65 candidate
`quantum_level41_mda_init_test-e65-candidate.sisx`.
