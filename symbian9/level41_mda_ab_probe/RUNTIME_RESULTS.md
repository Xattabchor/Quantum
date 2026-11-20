# E65 runtime result: v1.0.0

User supplied photographs of three screens. The initial screen is mode 0 (no run started). The two actual runs show:

| Field | Mode 1 (no periodic poller) | Mode 2 (20 ms poller) |
|---|---:|---:|
| Start | 0 | 0 |
| Worker exit type / reason | 2 / 66 | 2 / 66 |
| Category | E32USER-CBase | E32USER-CBase |
| O | 0 | 0 |
| I | 9999 | 9999 |
| P entered / returned | 0 / 0 | 0 / 0 |
| C | 0 | 0 |
| E | 9999 | 9999 |
| T | 0 | 0 |
| Timeout / Result | 0 / 0 | 0 / 0 |

`9999` is the no-callback sentinel; `Result:0` is the initialized value and is not a success after panic. `Start:0` means worker creation succeeded, not MDA initialization. O:0 proves the probe did not reach the trace immediately preceding the dynamic OpenFileL call; in the program `NewL` occurs before O is set. It does not prove exactly where the panic occurred. No user audibility report was supplied; do not infer it from the image.

This test **did not reach playback in either mode** and cannot compare the periodic timer hypothesis. This is a probe failure, not a new game failure and not the earlier worker panic 46. `CTrapCleanup::New` creates a cleanup stack, but the probe calls `session->Start()` before starting the scheduler and without a surrounding TRAP. `Start` calls `CMdaAudioPlayerUtility::NewL`; Symbian documentation states `CleanupStack::PushL` may panic E32USER-CBase 66 when no prior TRAP is present. Missing top-level TRAP around the leaving startup path is a strong code-level explanation, not a confirmed exact panic location. The earlier v1.0.1 shot probe opens from a poller RunL, which runs under the scheduler's trap harness; this difference is relevant but does not establish the original panic 46's cause.

Do not ask for further repeats of v1.0.0. Next: correct the isolated probe startup with a valid Symbian trap/leave boundary, instrument stages surrounding player construction, rebuild and audit, then repeat the A/B device test. The SDK's previous TRAP link limitation must be addressed and the emitted executable verified, rather than simply suppressing the panic. See https://devlib.symbian.slions.net/belle/GUID-C6E5F800-0637-419E-8FE5-1EBB40E725AA/GUID-0DD554D7-B9B1-3FD3-898F-4D855144FCEA.html for the documented cleanup stack panic.
