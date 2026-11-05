# Boot harness

Runs the real MIDlet (`code.utils.Main.startApp()`) on a desktop JVM against
LCDUI / MMAPI / RMS doubles and checks what a handset would see. It is **not**
hardware emulation and it does not render the game: it executes the actual
startup path and settings screens, where the phone used to show a black screen.

## Why

`downloads/Quantum_Kordos_Full.jar` (before the fix) threw
`NullPointerException` in `IniFile.set()` reached from `Main.init()`, so the
MIDlet died inside `startApp()` on every JVM. That is exactly what a black
screen or an immediate "application error" on a phone looks like.

```
python3 tests/boot/compare.py
```

```
=== before: downloads/Quantum_Kordos_Full.jar @ 1e39c9e
  boot      FAILED -> Caused by: java.lang.NullPointerException
  nomedia   FAILED -> Caused by: java.lang.IllegalStateException: MMAPI not available: types
  missing   FAILED -> Caused by: java.lang.NullPointerException
=== after: build/cldc (scripts/build_cldc.py)
  boot      passed
  nomedia   passed
  missing   passed
```

## Scenarios

| scenario | what it reproduces |
| --- | --- |
| `boot` | full path: settings -> resources -> splash -> language -> menu -> settings, every row toggled both ways |
| `noplatform` | `microedition.platform` is null/empty (first install) |
| `nomedia` | MMAPI present but every call throws |
| `brokenmedia` | MMAPI present, `createPlayer` raises `SecurityException` |
| `nomedia-noclass` | `javax.microedition.media` absent from the classpath entirely |
| `rmsdeny` | RMS access denied (locked phone) |
| `missing` | JAR without `/setting.txt` -> explanatory error screen |
| `nolangs` | language texts missing -> explanatory error screen |
| `nofont` | HUD font images missing -> explanatory error screen |
| `sound` | weapon sound on 10 + 20 consecutive shots, strict Symbian-like player |

## Running

```sh
sh tools/bootstrap_toolchain.sh          # tools + submodule patches
python3 scripts/build_cldc.py ...        # produces build/cldc/*.jar
python3 tests/boot/run.py                # all scenarios
python3 tests/boot/run.py boot nomedia   # selected scenarios
```

`build/cldc/Quantum-unverified.jar` is used by default; override with
`BOOT_CLASSES=/path/to/unpacked/classes` to test another build.
Assets are unpacked from `downloads/Quantum_Kordos_Full.jar` into
`tests/boot/res/` (git-ignored), never stored in the repository.

## What the doubles are

`stubs/` provides `javax.microedition.*` implementations: `Image` decodes real
PNGs through ImageIO, `Graphics` rasterises into an `int[]`, RMS is in memory,
and `Manager` behaviour is selected with `-Dstub.media=absent|broken`. The
`overrides/code/utils/StringTools.java` copy adds directory-based resource
lookup and an isolation switch; the real class stays CLDC-pure (no
`java.io.File`).

Asserts are intentionally strict: every screen must paint without throwing and
draw at least one primitive, every menu/settings row must be non-null, and a
broken package must land on `StartupErrorScreen` rather than a blank canvas.
