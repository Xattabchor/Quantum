#!/usr/bin/env python3
"""Adapter contract tests on a desktop JVM; no SDK jars or game assets needed.

Use JAVA/JAVAC for a JDK, or JAVA and ECJ_JAR for Eclipse's batch compiler.
These test doubles deliberately live outside the MIDlet's src directory.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
TEST = Path(__file__).resolve().parent
SOURCES = [ROOT / "src/code/Rendering" / (name + ".java") for name in
           ("PixelBlitter", "PixelBlitterBackend", "NokiaPixelBlitter")]
SOURCES += sorted((TEST / "stubs").rglob("*.java")) + [TEST / "PixelBlitterTest.java"]
JAVA = os.environ.get("JAVA", "java")

with tempfile.TemporaryDirectory(prefix="quantum-pixels-") as temp:
    classes = Path(temp) / "classes"
    classes.mkdir()
    if os.environ.get("ECJ_JAR"):
        compiler = [JAVA, "-jar", os.environ["ECJ_JAR"], "-8", "-proc:none"]
    else:
        compiler = [os.environ.get("JAVAC", "javac"), "-source", "8", "-target", "8"]
    subprocess.run(compiler + ["-d", str(classes)] + [str(p) for p in SOURCES], check=True)
    for mode in ("direct", "linkage", "oom"):
        subprocess.run([JAVA, "-Dnokia.mode=" + mode, "-cp", str(classes),
                        "PixelBlitterTest", mode], check=True)
    # Actually remove the optional API from the runtime, not just emulate a
    # missing API with a flag. Each test runs in a fresh VM (fresh lazy load).
    shutil.rmtree(classes / "com")
    subprocess.run([JAVA, "-cp", str(classes), "PixelBlitterTest", "missing-api"], check=True)
    (classes / "code/Rendering/NokiaPixelBlitter.class").unlink()
    subprocess.run([JAVA, "-cp", str(classes), "PixelBlitterTest", "missing-backend"], check=True)
