#!/usr/bin/env python3
"""After scripts/build_cldc.py, test original MIDP framebuffer presentation.
Uses JAVA/JAVAC or JAVA/ECJ_JAR; no Nokia UI API or adapters are used.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
TEST = Path(__file__).resolve().parent
JAVA = os.environ.get("JAVA", "java")
APP = ROOT / "build/cldc/Quantum-unverified.jar"
API = ROOT / "build/cldc/api.jar"

# Regression guards for the frame and menu paths: do not reintroduce the old
# auto-selected vendor output adapter or its compile-time dependency.
for source in (ROOT / "src").rglob("*.java"):
    text = source.read_text()
    assert "PixelBlitter" not in text and "com.nokia.mid.ui" not in text, source
for name in ("Setting", "KeysSettings", "PauseScreen", "DeveloperMenu"):
    text = (ROOT / "src/code/HUD" / (name + ".java")).read_text()
    assert "g.drawRGB((int[]) background," in text, name
with zipfile.ZipFile(API) as api:
    assert not any(p.startswith("com/nokia/") for p in api.namelist()), "Nokia SDK still in build API"
with zipfile.ZipFile(APP) as app:
    assert not any("PixelBlitter" in p for p in app.namelist()), "Obsolete adapter in JAR"

with tempfile.TemporaryDirectory(prefix="quantum-midp-") as temp:
    classes = Path(temp) / "classes"
    classes.mkdir()
    sources = sorted((TEST / "stubs").rglob("*.java")) + [TEST / "FramebufferTest.java"]
    if os.environ.get("ECJ_JAR"):
        compiler = [JAVA, "-jar", os.environ["ECJ_JAR"], "-8", "-proc:none", "-nowarn"]
    else:
        compiler = [os.environ.get("JAVAC", "javac"), "-source", "8", "-target", "8"]
    subprocess.run(compiler + ["-classpath", os.pathsep.join(map(str, (APP, API))),
                              "-d", str(classes)] + list(map(str, sources)), check=True)
    subprocess.run([JAVA, "-cp", os.pathsep.join(map(str, (classes, APP, API))), "FramebufferTest"], check=True)
