# Level 41 Fastfp A/B probe

This is an opt-in, isolated target benchmark for the `__aeabi_ddiv` candidate
from `CC3DBench/tools/float/fastfp.c`. It exports only the private probe symbol
`fastfp_ddiv`; it does **not** replace or interpose `__aeabi_ddiv`. Ordinary
C++ `double / double` operations and exceptional-input fallbacks continue to
use the platform EABI routine.

The probe checks raw division results (including exceptional/fallback inputs)
and compares the existing `MathUtils::microCalcLight` and `MathUtils::calcLight`
results with test-only copies that use `fastfp_ddiv`. It then measures raw
division and both lighting workloads using `User::FastCounter`, reporting the
median of three alternating system/Fastfp runs. `HAL::Get(EFastCounterFrequency)`
provides milliseconds when supported; otherwise the screen reports the counter
frequency and the relative speedup.

No Fastfp change is made to the main application or production build. The probe
is built only when `QUANTUM_BUILD_LEVEL41_FASTFP_PROBE=ON` is selected. A
reproducible host smoke check is available with:

```sh
cc -O2 -Wall -Wextra -fno-strict-aliasing \
  ../host_tests/fastfp_ddiv_check.c fastfp_ddiv.c -o fastfp-host-check
./fastfp-host-check
```

On the current host, this checked 1,000,007 normal/edge input pairs with zero
bitwise mismatches. That host smoke check is separate from the user-reported
E65 target run recorded below.

## Reported E65 result

Three alternating rounds at a reported FastCounter frequency of 32,768 Hz;
100,000 raw divisions and 8,000 lighting calls per mode per round. The timer
resolution is about 30.5 microseconds.

| Workload | System | Fastfp | Screen ratio (system/Fastfp) | Interpretation |
|---|---:|---:|---:|---|
| Raw `ddiv` | 68 ms | 395 ms | 0.2x | Fastfp about 5.8x slower |
| `microCalcLight` | 79 ms | 109 ms | 0.7x | Fastfp about 1.38x slower |
| `calcLight` | 142 ms | 171 ms | 0.8x | Fastfp about 1.20x slower |

Correctness was clean in the reported cases: raw `0/206` mismatches, zero
lighting mismatches, and checksums `1/1/1`. The `raw/d/m/c` fallback counts
were `19/0/0/0`; thus the timed Fastfp paths did not fall back to system
division. Conclusion: correct for these tests, but slower on this E65; keep
Fastfp out of the main build.

## Device run

The generated self-signed package is `quantum_level41_fastfp_probe-selfsigned.sisx`
(no capabilities requested). If an earlier temporary probe build was installed,
uninstall it first; this 1.0.3 package uses a fresh test certificate. E65
installation policy may still reject a self-signed certificate; if so, sign the
unsigned `.sis` with a certificate trusted by that handset. Launch **Fastfp
Probe**. It opens its window first and displays the current test phase; please
wait for the results screen. If it remains on one phase for an unusually long
time, report the exact phase text.

`x > 1.0` means Fastfp was faster; any future run should have zero mismatch
counts and checksum flags `1/1/1`. The `d/m/c` fallback counts show how often
the candidate returned to system division during each timed workload. The
reported E65 run shows no performance case for production integration.
