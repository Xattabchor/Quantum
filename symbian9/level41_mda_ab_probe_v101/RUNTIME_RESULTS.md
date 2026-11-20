# E65 A/B probe v1.0.1 runtime observations

User supplied three photographs and reports: a shot is audible on the first press of either mode, then the other mode shows -11.

- Initial screen: `Mode:0 Start:0`, counters at initial values.
- Mode 1 (no periodic poller): `Start:0`, `Exit type 2 reason 46`, category `E32USER-CBase`, `O:1 I:0 P:1/1`, `C:0 E:9999 T:0`, `TO:0 R:0 S:6`. This shows OpenFileL returned, successful init callback, Play called and returned, and no completion callback recorded before the worker panic. The result field 0 is its initial value when the worker panics, not normal-success proof. No timeout was recorded. The user reported hearing a shot on the first press.
- Mode 2 subsequent attempt: `Mode:2 Start:-11`, `O:0 I:9999 P:0/0`, `C:0 E:9999 T:0`, `TO:0 R:0 S:0`; no exit-type line is displayed. In this probe Start is the return from `RThread::Create`; therefore the second worker did not start. -11 is `KErrAlreadyExists`. The code creates every worker with the fixed name `Q41MdaABWorker`; a name collision with an existing thread is a plausible explanation, but the exact ownership and lifetime of the conflicting name are not established. It is not an MDA playback error, and this screen is not a valid mode 2 audio comparison.

Mode 1's result is important: the same class of panic 46 can occur without this probe's 20 ms periodic poller, so that poller is not necessary for the isolated reproduction. Both modes retain a one-shot watchdog, self-completed active objects, shared telemetry, MDA and SDK/runtime; none of those has been isolated by this observation. It does not prove the cause of the game's exit or of panic 46.

In a follow-up the user reports that when mode 2 is pressed first, it produces sound and the screen is like mode 1, while the subsequent mode 1 shows -11. A subsequent photo confirms mode 2: `Start:0`, `Exit type 2 reason 46`, category `E32USER-CBase`, `O:1 I:0 P:1/1`, `C:0 E:9999 T:21`, `TO:0 R:0 S:6`. Thus the periodic control ran and registered 21 ticks, OpenFileL returned, init succeeded, Play returned, and no completion callback was recorded before panic. R:0 is only the initial value after panic. The user reported sound in this mode. The two screenshots establish panic 46 both with and without the periodic poller, not its source.

The fixed-name retry issue is a defect in this diagnostic; future probe versions should use unique per-run names and handle failed thread creation visibly. Do not count the failed `Start:-11` as a mode-2 playback result.
