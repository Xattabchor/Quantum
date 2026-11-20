# E65 runtime results — MDA Controls Probe v1.0.1

User reported:

| Mode | Start | Exit type / reason | O | I | P | C / E | K | TO | R / S |
|---|---|---|---|---|---|---|---|---|---|
| 1 timer only | 0 | 0 / 0 | 0 | 9999 | 0/0 | 0 / 9999 | 6 | 1 | 0 / 0 |
| 2 open only | 0 | 0 / 0 | 1 | 0 | 0/0 | 0 / 9999 | 6 | 1 | 0 / 6 |
| 3 open + Play | 0 | 2 / 46, E32USER-CBase | 1 | 0 | 1/1 | 0 / 9999 | 0 | 0 | 0 / 6 |

User subsequently confirmed sound was audible in mode 3. Modes 1 and 2 now completed cleanup and exited with reason 0; this supports the allocator/deallocator mismatch hypothesis for v1.0.0's -6 (other instrumentation and timing also changed). It does not establish the exact executed instruction. Mode 3 still panicked before callback, timer observation, or cleanup (K:0). Therefore the correction to these two diagnostic objects does not resolve panic 46. This is not proof of the location of the stray request signal or a game fix. Single reported run per mode; do not infer stability.
