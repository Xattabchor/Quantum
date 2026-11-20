# E65 shot audio probe v1.0.0 — reported result

User reports one audible shot. The screen then displayed:

```
Shot commands 1
Worker exit type 2 reason 46
E32USER-CBASE
5 shot back/esc exit
Wait 3sec after each shot
```

Further presses did not produce another shot. The probe intentionally refuses to enqueue a shot when its worker has exited, so this does not itself establish whether the keypad or main UI stopped responding. The screen identifies a panic (`EExitPanic`, type 2) in the isolated audio worker. Symbian's E32USER-CBase 46 indicates an active-scheduler stray event: a request completion signal for which the scheduler cannot find a ready active object. It does not identify the originating request or prove exactly when it occurred relative to `MapcPlayComplete`.

The diagnostic uses the same worker implementation as the v1.0.53 source except for the isolated effect filenames and monitoring methods in the header; it uses a byte-identical shot WAV. Unlike the game it opens on the first shot and does not start music or preload reload. The result reproduces an audio-worker panic without graphics/game logic and makes the worker/audio async path a strong suspect, but does not prove the game's silent whole-application exit has the same cause. The probe status does not record whether the playback completion callback ran. No fix has been applied or tested.

Reference for panic meaning: https://devlib.symbian.slions.net/s3/GUID-251A35C1-CC66-4DE4-9EBE-964026E89E7F/GUID-067293BF-B28C-3CEC-92F4-1351A795EA7F.html (CActive::SetActive documentation).
