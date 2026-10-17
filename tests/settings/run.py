#!/usr/bin/env python3
"""Actual Setting/Main/RmsUtils contracts without game resources or a device UI."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
TEST = Path(__file__).resolve().parent
APP = ROOT / "build/cldc/Quantum-unverified.jar"
API = ROOT / "build/cldc/api.jar"
JAVA = os.environ.get("JAVA", "java")

with tempfile.TemporaryDirectory(prefix="quantum-settings-") as temp:
    classes = Path(temp) / "classes"
    classes.mkdir()
    sources = sorted((TEST / "stubs").rglob("*.java")) + [TEST / "SettingsTest.java"]
    if os.environ.get("ECJ_JAR"):
        compiler = [JAVA, "-jar", os.environ["ECJ_JAR"], "-8", "-proc:none", "-nowarn"]
    else:
        compiler = [os.environ.get("JAVAC", "javac"), "-source", "8", "-target", "8"]
    subprocess.run(compiler + ["-classpath", os.pathsep.join(map(str, (APP, API))),
                              "-d", str(classes)] + list(map(str, sources)), check=True)
    subprocess.run([JAVA, "-cp", os.pathsep.join(map(str, (classes, APP, API))), "code.HUD.SettingsTest"], check=True)
