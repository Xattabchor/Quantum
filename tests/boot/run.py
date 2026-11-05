#!/usr/bin/env python3
"""Boots the real MIDlet on a desktop JVM and checks what a handset would see.

Not a substitute for hardware testing, but it reproduces the exact startup code
path (Main.startApp -> init -> resources -> splash -> menu -> settings) and the
failure modes that leave a phone on a black screen:

  boot         full path with assets present
  missing      no /setting.txt (wrong or stripped JAR)
  noplatform   microedition.platform == null (first install)
  nomedia      MMAPI absent (every Player call throws)
  brokenmedia  MMAPI present but refusing players
  rmsdeny      RMS access denied (locked/read-only phone)
  nolangs      no /languages/*.txt

Build inputs come from build/cldc (scripts/build_cldc.py) and build/sdk.
"""
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEST = Path(__file__).resolve().parent
CLASSES = Path(os.environ.get("BOOT_CLASSES", ROOT / "build/cldc/Quantum-unverified.jar"))
API = ROOT / "build/cldc/api.jar"
JAVA = os.environ.get("JAVA", str(ROOT / "build/tools/jdk8/bin/java"))
JAVAC = os.environ.get("JAVAC", str(ROOT / "build/tools/jdk8/bin/javac"))
RESOURCE_JAR = Path(os.environ.get("BOOT_RESOURCE_JAR",
                                   ROOT / "downloads/Quantum_Kordos_Full.jar"))


def run(cmd, **kw):
    return subprocess.run(cmd, check=True, **kw)


def build(out):
    out.mkdir(parents=True, exist_ok=True)
    sources = (sorted(TEST.glob("stubs/**/*.java"))
                + sorted(TEST.glob("overrides/**/*.java"))
                + sorted(TEST.glob("java/**/*.java")))
    run([JAVAC, "-nowarn", "-source", "8", "-target", "8",
         "-classpath", os.pathsep.join([str(CLASSES), str(API)]),
         "-d", str(out)] + [str(p) for p in sources])


def resources():
    """Assets come from the game JAR, never from the repo (tests/boot/res is ignored)."""
    res = TEST / "res"
    if not (res / "setting.txt").is_file():
        run([sys.executable, str(TEST / "setup_resources.py"), str(RESOURCE_JAR)])


def main():
    scenarios = sys.argv[1:] or ["boot", "noplatform", "nomedia", "brokenmedia",
                                 "rmsdeny", "missing", "nolangs", "nofont"]
    work = ROOT / "build/boot"
    work.mkdir(parents=True, exist_ok=True)
    resources()
    # A jar and a directory cannot share a classpath entry, so pre-fix runs
    # (BOOT_CLASSES=unpacked jar dir) get their own output directory.
    tag = "orig" if Path(CLASSES).is_dir() else "fixed"
    harness = work / ("classes-" + tag)
    if harness.exists():
        shutil.rmtree(harness)
    build(harness)
    for scenario in scenarios:
        res = TEST / "res"
        if scenario == "missing":
            # a JAR built without game assets (except language files, so the
            # harness reaches the "no setting.txt" path and reports it)
            res = work / "res-missing"
            if not res.exists():
                res.mkdir(parents=True, exist_ok=True)
                langs = TEST / "res" / "languages"
                if langs.is_dir():
                    shutil.copytree(langs, res / "languages")
        if scenario == "nolangs":
            # Language texts gone, fonts kept: the fallback chain cannot load any
            # language, so startup must report it instead of drawing nothing.
            res = work / "res-nolangs"
            if res.exists():
                shutil.rmtree(res)
            shutil.copytree(TEST / "res", res)
            for text in ("english.txt", "russian.txt"):
                target = res / "languages" / text
                if target.exists():
                    target.unlink()
        if scenario == "nofont":
            # Font images gone: the custom HUD font cannot be built.
            res = work / "res-nofont"
            if res.exists():
                shutil.rmtree(res)
            shutil.copytree(TEST / "res", res)
            for name in ("font.png", "bfont.png", "ffont.png", "gfont.png",
                         "ggfont.png", "bbfont.png", "en_font.txt", "ru_font.txt"):
                target = res / "languages" / name
                if target.exists():
                    target.unlink()
        props = ["-Dstub.resource.dir=" + str(res),
                 "-Dquantum.resource.dir=" + str(res)]
        if scenario in ("missing", "nolangs", "nofont"):
            props.append("-Dstub.isolate=true")
        if scenario == "noplatform":
            props.append("-Dmicroedition.platform=")  # empty -> property reads as ""
        if scenario == "nomedia":
            props.append("-Dstub.media=absent")
        if scenario == "brokenmedia":
            props.append("-Dstub.media=broken")
        if scenario == "rmsdeny":
            props.append("-Dstub.rms=deny")
        props.append("-Dstub.build=" + tag)
        if scenario == "nomedia-noclass":
            # Real absent-MMAPI test: javax.microedition.media is not on the
            # classpath at all, so referencing Player/Manager in a method body
            # must not break class loading or startup.
            classpath = os.pathsep.join([str(harness), str(CLASSES), str(API)])
            result = subprocess.run([JAVA] + props + ["-cp", classpath,
                                                      "code.HUD.NoMediaBoot", scenario],
                                    cwd=str(ROOT), stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, text=True)
            sys.stdout.write(result.stdout)
            if result.returncode != 0:
                print("FAILED scenario " + scenario)
                return 1
            continue
        classpath = os.pathsep.join([str(harness), str(CLASSES), str(API)])
        print("== " + scenario)
        result = subprocess.run([JAVA] + props + ["-cp", classpath,
                                                  "code.HUD.BootTest", scenario],
                                cwd=str(ROOT), stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True)
        sys.stdout.write(result.stdout)
        if result.returncode != 0:
            print("FAILED scenario " + scenario)
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
