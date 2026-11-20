# E65 shot audio isolation probe v1.0.0

Standalone experimental test, not a game fix. The MDA audio worker is copied from the v1.0.53 source archive and current game source (SHA-256 `0f98a11d5e348757af346dceb9c490cbddac32e7a89ac804adac155ede974134`). The only changes in its implementation are the names of the two effect paths; the header adds worker exit inspection for the screen. No game renderer, weapon or physics code is linked. It opens the shot on the first trigger rather than pre-opening both effects at game startup; it does not play music or reload. Consequently a negative result does not clear the game audio integration or resource contention.

The packaged `q41_probe_shot.wav` was extracted from the signed v1.0.53 SISX using the SIS inspector; it is byte-identical to that package's `q41_fn2000.wav`, SHA-256 `99ef9989a3b17949a2bd4cfbadfa6b2f16d84f33b207a01b91b42d72c0e25d3b` (8 kHz, mono, PCM16, 400 ms). The diagnostic uses a unique filename and package identity; it does not overwrite the game's WAV or historical SISX.

## E65 instructions

Install `quantum_shot_audio_probe-v1.0.0-e65.sisx` **to phone memory C:** (the probe deliberately opens `C:\data\q41_probe_shot.wav`). This is a self-signed test package; stop if the device refuses installation. Launch **Shot Audio Probe**, press **5** once, and listen. The screen refreshes after three seconds. Photograph the screen, report whether the sound played and whether the app remained open, then press 5 a few more times with pauses and report any exit. Escape/Back exits. If the worker dies, the screen attempts to report its exit type, reason and category; a whole-process panic may instead close the app before it can display them. `AUDIO` is the existing game status summary, not a detailed callback trace: READY alone does not prove that a completion callback was received. If no sound plays, report the complete status, installation drive and phone volume. Never infer a physical endpoint from the status.

## Local build and package verification

Cross-built with SDK 0.3.0 Clang for ARMv5T/EKA2. E32 UID3 `0xE000A036`, package UID `0xE000A037`. SISX is self-signed RSA-1024/SHA-1, validity 2000-01-01 to 2045-12-31; temporary signing key was removed. The local SIS writer reported `signed=true`, `registered=true`, four embedded files; its extraction was checked byte-for-byte against the v1.0.53 WAV. Static MDA imports are ordinals 35 and 15; OpenFileL 42 is looked up dynamically. MIDI imports absent.

- E32 SHA-256 `92cd8a032fd169850465116b021bdc7ac0f1d4130c4b41f122b6f1c377483c20`.
- SISX SHA-256 `902f42772f11555c71f0e59056cb26d23d5d69efd87c0e3105834551f9881345`.

E65 runtime result: one audible shot, then the audio worker panicked with `E32USER-CBase 46`; further shot presses produced no sound. See [RUNTIME_RESULTS.md](RUNTIME_RESULTS.md). The origin of the stray event and the cause of the game's whole-application exit remain unconfirmed.
