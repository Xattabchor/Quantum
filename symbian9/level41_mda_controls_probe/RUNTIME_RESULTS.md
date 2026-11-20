# E65 runtime results — MDA Controls Probe v1.0.0

User reported:

| Mode | Start | Exit type / reason | O | I | P enter/return | C / E | T / TO | R / S | Sound |
|---|---|---|---|---|---|---|---|---|---|
| 1 timer only | 0 | 0 / -6 | 0 | 9999 | 0/0 | not reported | not reported / 1 | 0 / 0 | not reported |
| 2 open only | 0 | 0 / -6 | 1 | 0 | 0/0 | 0 / 9999 | 0 / 1 | 0 / 6 | not reported |
| 3 open and Play | 0 | 2 / 46, E32USER-CBase | 1 | 0 | 1/1 | 0 / 9999 | 0 / 0 | 0 / 6 | audible |

TO:1 in modes 1 and 2 indicates the observation timer callback was reached. Mode 2 also reached successful init without Play. Exit reason -6 is KErrArgument; type 0 is not the type 2 panic observed in mode 3. Subsequent static analysis found a strong, unconfirmed cleanup allocation/deallocation mismatch hypothesis for -6; see `tools/research/e65/controls-cleanup-investigation.md`. R:0 records the worker's result before subsequent cleanup and must not be equated with the exit reason or treated as a clean pass. In mode 3 Play returned, playback was audible, no completion callback was recorded, and the observation timer had not fired when panic was recorded. This comparison narrows the trigger to changes associated with Play or subsequent playback in these runs; it does not locate the stray signal or rule out harness, ABI or timing effects. No game fix follows from this diagnostic. Tests are individual user reports, not a measured repeat-rate.
