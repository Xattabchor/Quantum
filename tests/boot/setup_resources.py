#!/usr/bin/env python3
"""Materialises res/ for the boot harness: plain files + a classpath root.

Resource lookup on a handset goes through the MIDlet class loader, so the
harness needs /setting.txt etc. on the classpath, not just in a JAR.
"""
import sys
import zipfile
from pathlib import Path

RES = Path(__file__).resolve().parent / "res"
JAR = Path(sys.argv[1]) if len(sys.argv) > 1 else None

if RES.exists():
    import shutil
    shutil.rmtree(RES)
RES.mkdir(parents=True)

if JAR is None or not JAR.exists():
    print("no resource jar given")
    sys.exit(0)

count = 0
with zipfile.ZipFile(JAR) as z:
    for info in z.infolist():
        if info.is_dir():
            continue
        name = info.filename
        if name.lower().endswith(".class") or name.upper().startswith("META-INF/"):
            continue
        target = RES / name.lstrip("/")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(z.read(name))
        count += 1
print("resources:", count, "->", RES)
