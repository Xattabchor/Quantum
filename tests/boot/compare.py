#!/usr/bin/env python3
"""Runs the boot harness against the pre-fix JAR and the current build.

Proves the fix: the shipped JAR crashes during startApp() while the rebuilt one
reaches the menu. Not a hardware substitute, but it is the same code path.
"""
import os
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEST = Path(__file__).resolve().parent
# downloads/Quantum_Kordos_Full.jar is rebuilt by the fix, so the "before" JAR
# comes from the commit the branch was based on, not from the working tree.
BASE_COMMIT = os.environ.get("BOOT_BASE_COMMIT", "1e39c9e")
ORIG_JAR_PATH = "downloads/Quantum_Kordos_Full.jar"
ORIG_JAR = ROOT / "build/boot/before.jar"
ORIG_DIR = ROOT / "build/boot/orig"
SCENARIOS = ("boot", "nomedia", "missing")


def extract_before_jar():
    """Pre-fix JAR straight out of git history (never from the working tree)."""
    ORIG_JAR.parent.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(["git", "show", BASE_COMMIT + ":" + ORIG_JAR_PATH],
                            cwd=str(ROOT), stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE)
    if result.returncode != 0:
        raise SystemExit("cannot read " + ORIG_JAR_PATH + " from " + BASE_COMMIT
                         + ": " + result.stderr.decode(errors="replace").strip())
    ORIG_JAR.write_bytes(result.stdout)


def unpack_original():
    if (ORIG_DIR / "code").is_dir():
        return
    extract_before_jar()
    ORIG_DIR.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(ORIG_JAR) as z:
        for info in z.infolist():
            if not info.filename.startswith("code/") or info.is_dir():
                continue
            target = ORIG_DIR / info.filename
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(z.read(info))
            os.chmod(target, 0o644)


def run(classes, scenarios):
    env = dict(os.environ, BOOT_CLASSES=str(classes))
    env["BOOT_TAG"] = "orig" if classes == ORIG_DIR else "fixed"
    env["BOOT_CONTINUE"] = "1"  # the "before" build is expected to crash
    result = subprocess.run([sys.executable, str(TEST / "run.py"), *scenarios],
                            env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True)
    return result.returncode, result.stdout


def first_failure(output):
    """Report the exception a scenario died on, not the harness wrapper."""
    lines = output.splitlines()
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("Caused by:"):
            # Prefer the cause: that is the real defect, the wrapper is noise.
            return stripped
        if stripped.startswith("java.lang.") and i + 1 < len(lines):
            nxt = lines[i + 1].strip()
            if nxt.startswith("at "):
                return stripped
    for line in output.splitlines():
        if "FAILED" in line:
            return line.strip()
    return "(no failure)"


def scenario_block(output, scenario):
    """Everything between this scenario's header and the next one."""
    blocks = {}
    current = None
    for line in output.splitlines(keepends=True):
        if line.startswith("== "):
            current = line[3:].strip()
            blocks[current] = []
        elif current is not None:
            blocks[current].append(line)
    return "".join(blocks.get(scenario, []))


def main():
    unpack_original()
    print("=== before: " + ORIG_JAR_PATH + " @ " + BASE_COMMIT)
    before_code, before = run(ORIG_DIR, SCENARIOS)
    for scenario in SCENARIOS:
        block = scenario_block(before, scenario)
        ok = "[ok]" in block
        print("  %-9s %s" % (scenario, "passed" if ok else "FAILED -> " + first_failure(block)))

    print("=== after: build/cldc (scripts/build_cldc.py)")
    after_code, after = run(ROOT / "build/cldc/Quantum-unverified.jar", SCENARIOS)
    for scenario in SCENARIOS:
        block = scenario_block(after, scenario)
        ok = "[ok]" in block
        print("  %-9s %s" % (scenario, "passed" if ok else "FAILED -> " + first_failure(block)))

    print()
    print("before: " + ("all scenarios passed" if before_code == 0 else
                        "startup crashes (this is the phone symptom)"))
    print("after:  " + ("all scenarios passed" if after_code == 0 else "STILL BROKEN"))
    return after_code


if __name__ == "__main__":
    sys.exit(main())
