# E65 runtime — shot audio probe v1.0.1

The user reports one audible shot, followed by no response to further attempts. Screen transcription:

```
Shot commands 1
Worker exit type 2 reason 46
E32USER-CBASE
O:1 I:0 P:1/1
C:0 E:9999 H:0
```

`O:1`: the shot OpenFileL call was entered; `I:0`: init callback returned success; `P:1/1`: Play was entered once and returned once. `C:0` means no play-completion callback was recorded before the snapshot, and `E:9999` is the probe's unset sentinel, not a Symbian error. `H:0` means the poller handled no play completion. Worker exit type 2 / reason 46 / E32USER-CBase indicates the worker panicked on an active-scheduler stray signal. The trace does not prove exactly when the stray signal was generated or identify the originating request. After worker death this probe deliberately refuses further shot commands, so absence of sound on later presses is not evidence that the keypad failed.

The v1.0.1 experiment deferred handling of MapcInitComplete and the initial Play call to the poller, unlike v1.0.0. It still produced the same worker panic, so this isolated change did not solve the reported probe failure. The probe is not the full game; it does not establish the exact cause of the game's silent exit. No game fix has been applied. Next investigate the worker's asynchronous request/scheduler and MDA interactions, and instrument the relevant state before the panic rather than suppressing it.
