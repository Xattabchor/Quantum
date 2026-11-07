#!/usr/bin/env python3
"""Publish a Res3 build with one extra setting.txt key injected.

Every M3G tuning key in this branch lives in setting.txt, which is inside the
JAR - so each hypothesis used to cost a rebuild and a reflash. This makes a
byte-identical copy of the baseline JAR with a single key added, so the only
difference between two test builds is the one thing under test.

The key is inserted directly after M3G_LIT_BAKED=1, which sits above the first
[section] header; keys after a section header are ignored by IniFile.

Usage:  python3 tools/res3/build_variant.py NAME "KEY=VALUE" "Menu title"
"""
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "downloads", "Quantum_Res3_Full.jar")
SRC_JAD = os.path.join(ROOT, "downloads", "Quantum_Res3_Full.jad")
ANCHOR = "M3G_LIT_BAKED=1"


def build(name, key, title):
    dst = os.path.join(ROOT, "downloads", name + ".jar")
    jad = os.path.join(ROOT, "downloads", name + ".jad")

    src = zipfile.ZipFile(SRC)
    items = [(i, src.read(i.filename)) for i in src.infolist()]
    src.close()

    out = []
    done = False
    for info, data in items:
        if info.filename == "setting.txt":
            lines = data.decode("cp1251").split("\n")
            if key in "\n".join(lines):
                raise SystemExit("%s is already in setting.txt" % key)
            idx = next(i for i, l in enumerate(lines) if l.strip() == ANCHOR)
            first = next((i for i, l in enumerate(lines) if l.startswith("[")), len(lines))
            if idx >= first:
                raise SystemExit("anchor sits inside a section; keys there are ignored")
            lines.insert(idx + 1, key)
            data = "\n".join(lines).encode("cp1251")
            done = True
        out.append((info, data))
    if not done:
        raise SystemExit("setting.txt not found in the baseline JAR")

    zo = zipfile.ZipFile(dst, "w")
    for info, data in out:
        zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
        zi.compress_type = info.compress_type
        zi.external_attr = info.external_attr
        zi.create_system = info.create_system
        zo.writestr(zi, data)
    zo.close()

    size = os.path.getsize(dst)
    rows = []
    for line in open(SRC_JAD, encoding="ascii").read().replace("\r\n", "\n").split("\n"):
        if line.startswith("MIDlet-Jar-Size:"):
            line = "MIDlet-Jar-Size: %d" % size
        elif line.startswith("MIDlet-Jar-URL:"):
            line = "MIDlet-Jar-URL: %s.jar" % name
        elif line.startswith("MIDlet-Name:"):
            line = "MIDlet-Name: Quantum Res3 %s" % title
        elif line.startswith("MIDlet-1:"):
            line = "MIDlet-1: Quantum Res3 %s,,code.utils.Main" % title
        rows.append(line)
    open(jad, "w", newline="\r\n").write("\n".join(rows))
    print("%-26s %8d  %s" % (name + ".jar", size, key))


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    build(sys.argv[1], sys.argv[2], sys.argv[3])
