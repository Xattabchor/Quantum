#!/usr/bin/env python3
"""Rebuild /tmp/res3.zip from a published JAR.

build_cldc.py needs the level resources as a zip. The archive is generated
(untracked), so a sandbox reset takes it away; any published JAR that carries
the res3 assets is a sufficient source - take everything that is not a class
or META-INF entry.
"""
import zipfile

SRC = "downloads/Quantum_Res3_Full.jar"
DST = "/tmp/res3.zip"

src = zipfile.ZipFile(SRC)
names = [n for n in src.namelist()
         if not n.endswith(".class") and not n.startswith("META-INF")]
out = zipfile.ZipFile(DST, "w", zipfile.ZIP_DEFLATED)
for n in names:
    out.writestr(n, src.read(n))
out.close()
print("  %s: %d entries" % (DST, len(names)))
