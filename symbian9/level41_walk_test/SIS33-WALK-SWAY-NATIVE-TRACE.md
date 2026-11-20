# SIS v1.0.33 native walk/sway trace

## Scope and artifact identity

This note records only observations from the v1.0.33 SIS/E32 and the Java files
present in this workspace as a secondary cross-check. It does not use v1.0.34+
behavior as a reference and does not claim that the active C++ candidate is a
complete source-equivalent reconstruction.

Drive artifact: `quantum_level41_walk_test-v1.0.33-level1-import-drt-ten-plus-13-no-native-leaves-omap1710-dsa-argb32-armv5t.sis`, Drive ID
`1L-TVV6T-v5CSrJ0Ll_HwZRsIOf0Jz8Fu`. The SIS SHA-256 is
`002e698f84393f7304108716daa755904da8c33bacc7967d1c481293712ade8a` (741,128
bytes). Its extracted E32 is 190,916 bytes, SHA-256
`0d23b4b408167a83870f1c2d577dd9d51a5f1155b51f7a461ea056e7e72fb2b4`.
Disassembly uses the E32 code slice `[0x9c, 0x9c + 0x2df58)` at VA base
`0x8000`. These are separate hashes; the SIS digest is not the E32 digest.

## Supplemental non-weapon source/assets archive

Drive archive: `quantum last.zip`, ID `1lUuQ-BUSA0ABKCjWoCLELBVagUBGYJkr`,
1,353,665 bytes, SHA-256
`90683d18db5e281647835506942ab44784e744b35f695ccd496d4cb7e6e544a7`. Its
internal root and `SOURCE-ARCHIVE-README.md` identify a 2026-10-16 source/assets
snapshot. Drive metadata reports a later modified time, so the file is tracked
by ID and hash rather than using its timestamp as a version claim.

Use this archive as the supplemental baseline for non-weapon source and the
Level 41 assets it actually contains. It includes seven original level
mesh/lightmap/image inputs and four generated QTX files, but it is not a built
SIS/E32; its README says production audio and weapon art are not included. All 185 archived `symbian9/` files are present: 175 are byte-identical and 10
differ; none are missing. The archived Java `src/` tree (109 files), `.github/`
workflow sources (2 files), and the other shared project files are
byte-identical. The ten `symbian9/` divergences are confined to the walk/weapon
controllers and their headers/tests, the device input/update path, CMake A/B
presets, and the historical README. They include the prior Java-oriented
movement/sway implementation and are intentionally not overlaid on the active
v1.0.33-native restoration. No bulk extraction was performed.

The four generated QTX1 textures present in the archive but absent from the
checkout were copied into `build-assets/level41-qtx/`; the checked-in source
images were run through `symbian9/tools/prepare_level41_device_assets.py`, and
all four outputs matched the archive byte-for-byte. Their sizes and SHA-256
hashes are recorded in `build-assets/level41-qtx/README.md`. The archive's
`SOURCE-ARCHIVE-README.md` was not imported: it describes the snapshot as
Java-aligned and its build-status note is not current for this native
restoration. The archive's current weapon code and package candidates are not
the old-weapon specification; use only artifacts from v1.0.33 or earlier to
restore that behavior. Java weapon files remain a secondary cross-check only.
Neither this archive nor the current assets are proof of identity with assets
embedded in a compiled SIS.

## Input and movement call path

- `sub_835f` forwards the event words in `r0/r1` to `sub_a01d` (entry
  `0xa01c`). `sub_a01d` indexes the pointer table at `0x329b0` using
  `iScanCode - 0x0e` and writes the selected held-state byte. In this build,
  scan codes map as follows: Left `0x0e` → `0x200f575d`, Right `0x0f` →
  `0x200f575e`, Up `0x10` → `0x200f575b`, Down `0x11` → `0x200f575c`.
- In `sub_9489` (entry `0x9488`), the four held bytes are loaded from offsets
  `+3…+6` of the state at `0x200f5758`. The native forward axis is
  `Up - Down`; its left/right axis is `Left - Right`. The frame loop then
  calls `sub_261cd` with a temporary input record made by `sub_260c7`, the
  elapsed-time value, and native state/configuration pointers.
- The input record made by `sub_260c7` stores the signed Up–Down and
  Left–Right differences at record offsets `+0` and `+4`. The frame update calls
  `sub_261cd` at `0x9594` with the settings object at `0x200f5958` in `r2` and
  the elapsed-time value in `r3`. The setup path at `0xCEB4–0xCEB8` initializes
  that same object: the literal at `0xCF4C` is `0x200f5930`, and adding `0x28`
  gives `0x200f5958`.
- In that settings object, `sub_260d1` writes `10` at `+4` and `150` at
  `+0x20`. `sub_261cd` uses `+4` with the signed turn input and elapsed time,
  divides by 50, and updates wrapped yaw (`0x26252–0x26276`). It also adds
  `+0x20` to the velocity state on the active movement path and clamps the
  result (`0x2631c–0x2634c`); later in the same routine it applies elapsed-time
  `/50` scaling to movement integration (`0x2634e–0x26390`, `0x263e2–0x263f4`).
  Thus the native default 150 is an acceleration/update value, not a
  150-unit key-event displacement; this agrees with the Java reference's
  `Player.walk` → `Character.moveX/moveZ` path as a cross-check. The requested
  active candidate uses the native `walkSpeed=150` scalar (the earlier 300
  override was reverted). Other native settings fields and their full
  source-level mapping are still unresolved.

## Weapon sway trigger and update gate

- After processing the axes, `sub_9489` compares the held Up and Down bytes
  (`0x95de–0x95e8`). If they differ, it calls `sub_29885` (`0x29884` entry).
  That setter writes byte `1` to `[r0 + 0x50]`; the caller passes
  `r0 = 0x2009b4e0`. The native trigger is therefore forward/back state only;
  the left/right axis is not part of this gate.
- `sub_9489` calls `sub_29a3d` at `0x9650` with `r0 = 0x2009b480`, `r1`
  equal to the clamped frame duration, and `r2 = 0x2009b4e0`. `sub_29a3d`
  clamps its duration argument to 1–200 ms. It reads `[r5 + 0x50]` at `0x29a58`,
  where `r5` is the distinct `0x2009b480` object. Do not conflate that read
  with the one-shot marker: the latter is tested through `[r4 + 0x50]` and
  cleared at `0x29cfc`, where `r4` is `0x2009b4e0`.
- The update routine selects animation divisor 29 or 17 according to the
  value at `[r5 + 0x18]` compared with 256 (`0x29b60–0x29b6c`). This is a
  native observation; naming that field `attackIntensityQ8` in C++ remains a
  candidate mapping, not a proven source-level identity.

## Native object initializer and call-site links

- The setup path at `0xCEBC–0xCEC4` loads the two globals from literals at
  `0xCF50`/`0xCF54` and calls `sub_293d4`/`sub_2944c`: `sub_293d4` receives
  `0x2009b480`, and `sub_2944c` receives `0x2009b4e0`. The update call at
  `0x9650` passes those same globals as `r0` and `r2`, respectively. This
  directly links `sub_29a3d`'s `r5` config object and `r4` state/marker object
  to their v1.0.33 initializers; it is not inferred from the C++ layout.
- `sub_293d4` initializes the config words at offsets `+0`, `+4`, `+8`,
  `+0x0c`, `+0x10`, `+0x14`, and `+0x18` to `12`, `200`, `8`, `2`, `24`,
  `0`, and `256`, and sets `[config+0x50]` to `1`. Those literal defaults,
  combined with the updater's reads, support the candidate correspondences
  `+0x08` delay/rollover value, `+0x0c` frame threshold, `+0x10` reload
  duration, and `+0x18` intensity; the updater reads the magazine-capacity
  value at `+0` when completing a reload. These are a partial behavioral map,
  not proof of all config field names or Java/source identity.
- `sub_2944c` initializes the state frame pair at `+0/+4` to
  `0xffff0000/0xffffffff` (signed Q16.16 `-1.0`), reload timer `+0x10` to
  `-1`, offsets `+0x14/+0x18` to zero, shift-like values `+0x24/+0x28` to
  `2/5`, and the one-shot byte at `+0x50` to zero. The updater reads and
  writes those same offsets as its frame, reload-timer, two offset, two shift,
  and marker fields. This corroborates those selected candidate semantics;
  it does not make the C++ object byte-layout-compatible with the native one
  or establish every field's source-level identity.

## Additional `sub_29a3d` arithmetic recovered

- The routine treats `[r4 + 0]` / `[r4 + 4]` as a signed 64-bit frame pair.
  Its low-word increment is `(elapsedMs << 16) / 50` (`0x29ad2–0x29ae0`),
  confirming Q16.16 frame arithmetic. The frame comparison threshold is read
  from `[r5 + 0x0c]` and scaled by `0x10000`; on rollover the frame is set from
  the negated value at `[r5 + 8]`. Recovery below the `-1.0` sentinel adds
  `(elapsedMs * 0xA0000) / 166` and clamps to the exact Q16.16 `-1.0` value
  (`0xffffffffffff0000`). These offsets strongly resemble shot-time/delay
  tuning, but that semantic mapping is not established by the trace alone.
- With a nonnegative frame and the `[r5 + 0x50]` gate set, the routine updates
  two offset-like fields using `abs(shift) * 2 * elapsedMs / divisor`, scales
  by the raw `[r5 + 0x18]` value at an eight-bit fixed-point boundary, then
  applies signed-short bounds (`0x29b78–0x29bfa`). When the one-shot marker at
  `[r4+0x50]` is present, raw intensity at most 256 takes the impulse path,
  which uses `shift * elapsedMs / 29`, signed-byte narrowing, then signed-short
  addition. Raw intensity above 256 skips the impulse. **In both marker
  branches the marker is consumed and control proceeds to bounds without
  damping** (`0x29cfc–0x29d06`); the damping operation
  `-(offset * elapsedMs) / divisor / 6` is reached only when the one-shot
  marker is absent. Both offset-like fields clamp to bound-like values and
  reverse their paired shifts at an endpoint.
- The disassembly therefore now establishes more than the trigger alone: it
  establishes the raw frame, scaling, marker, impulse, damping, and bounds
  operations. It still does **not** establish the complete object layouts or
  prove that the C++ `SoftwareWeaponState`/`SoftwareWeaponTuning` fields map
  one-to-one to those native offsets. The `r5+0x50` activity gate and
  `r4+0x50` one-shot marker are separate bytes on separate objects.

## What the current checks prove

The candidate stores its animation frame in signed Q16.16 and uses the
observed `/50` frame step and `*0xA0000/166` recovery arithmetic. Focused host
regressions cover these values and the translated marker impulse/damping edge
cases. The Java oracle separately cross-checks the shared no-marker update path
and common animation cases; it is not used as the native specification. The
ASan/UBSan runner passed its default experiment, `--sis33-baseline`, and
`--sis33-baseline-no-sway` variants; compiler output contains only the existing
`matrix.cpp:57–58` deprecated enum/floating-point warnings. These tests do not
execute the SIS routine. The disassembly and initializer/call-site analysis
establish only the selected field correspondences above; complete source-to-
native mapping, full native-state equivalence, and pixel parity remain
unproven. The SIS `.33` disassembly is the only native baseline used here; no
v1.0.34+ behavior is used.

After restoring the Drive SDK and host GLIBC 2.38 runtime, the
`e65-level41-walk-asm` CMake preset completed its 31-step ARMv5T/EKA2 build
with standard MDA, SNDDMA off, and the requested OMAP1710 ASM/DSA profile. The
E32 is 183,552 bytes, SHA-256
`e4fceecf37b818a1f1639544a2374b0cd15725ca3368ef9388f27c71995a0c18`. This is
compile/link validation only: it has not been installed or run on an E65.
Complete native field mapping and device behavior remain open questions.
