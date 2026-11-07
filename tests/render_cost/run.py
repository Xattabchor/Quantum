#!/usr/bin/env python3
"""Counts native draw calls per frame for a real level.

Compiles the renderer subset against recording M3G doubles (see stubs/) and runs
FrameCostProbe against a level shipped in the game archive. Reports draw calls,
where world batches were split, and how the texture cache behaved.

No production source is modified: the doubles live in tests/render_cost/stubs and
are only ever compiled into a throwaway directory.

Usage:
    python3 tests/render_cost/run.py [level] [-Dprobe.key=value ...]
"""
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEST = Path(__file__).resolve().parent
JAVA = os.environ.get("JAVA", str(ROOT / "build/tools/jdk8/bin/java"))
ECJ = os.environ.get(
    "ECJ_JAR",
    str(ROOT / "build/tools/package/server/plugins/org.eclipse.jdt.core_3.16.0.v20181004-1305.jar"),
)
APP = Path(os.environ.get("RENDER_COST_CLASSES", ROOT / "build/cldc/Quantum-unverified.jar"))
API = ROOT / "build/cldc/api.jar"
RESOURCE_JAR = Path(os.environ.get("RENDER_COST_RESOURCES", ROOT / "downloads/Quantum_Kordos_Full.jar"))

DEFAULT_LEVELS = ["/level1.txt"]

# The renderer plus the maths and asset helpers it links against. Anything outside
# this subset comes from APP, which keeps the harness independent of unrelated
# in-flight edits elsewhere in the tree.
RENDER_SUBSET = [
    "src/code/Rendering/*.java",
    "src/code/Rendering/Meshes/*.java",
    "src/code/Math/Matrix.java",
    "src/code/Math/MathUtils.java",
]


def fail(message):
    print(message, file=sys.stderr)
    sys.exit(1)


def main(argv):
    levels = []
    properties = []
    for arg in argv:
        if arg.startswith("-D"):
            properties.append(arg)
        else:
            levels.append(arg)
    if not APP.is_file() or not API.is_file():
        fail("missing build/cldc; run: sh tools/bootstrap_toolchain.sh")
    if not levels:
        levels = DEFAULT_LEVELS

    work = Path(tempfile.mkdtemp(prefix="quantum-drawcalls-"))
    try:
        stubs = work / "stubs"
        sources = list(TEST.glob("stubs/**/*.java"))
        run([JAVA, "-jar", ECJ, "-8", "-proc:none", "-nowarn",
             "-classpath", str(API), "-d", str(stubs)] + [str(p) for p in sources])

        classes = work / "classes"
        render = []
        for pattern in RENDER_SUBSET:
            render += sorted(ROOT.glob(pattern))
        sources = [p for p in render
                   if p.name != "Vector3D.java"
                   # Retained mode (World, Camera.setTransform) is not in the
                   # recording doubles - and draw-call accounting does not need
                   # it, because it never runs with M3G_CLEAN=1.
                   and p.name != "M3GRetainedMap.java"]
        sources.append(TEST / "java/code/Rendering/FrameCostProbe.java")
        run([JAVA, "-jar", ECJ, "-8", "-proc:none", "-nowarn",
             "-classpath", os.pathsep.join([str(stubs), str(APP), str(API)]),
             "-d", str(classes)] + [str(p) for p in sources])

        assets = work / "assets"
        unpack(RESOURCE_JAR, assets)
        # Assets BEFORE the class jar: the build jar also embeds the resource
        # archive's setting.txt/levels, and first-wins would silently measure the
        # built-in copy instead of the bundle under test.
        classpath = os.pathsep.join([str(stubs), str(classes), str(assets), str(APP), str(API)])
        for level in levels:
            print("=" * 60)
            print("level " + level + (" " + " ".join(properties) if properties else ""))
            run([JAVA] + properties + ["-cp", classpath, "code.Rendering.FrameCostProbe", level])
            print()
    finally:
        shutil.rmtree(work, ignore_errors=True)


def unpack(archive, target):
    if not archive.is_file():
        fail("missing game archive: %s" % archive)
    target.mkdir(parents=True, exist_ok=True)
    import zipfile
    with zipfile.ZipFile(archive) as jar:
        for name in jar.namelist():
            # Assets only: a stale code/ tree on the classpath would shadow the build.
            if name.startswith("code/") or name.endswith("/"):
                continue
            jar.extract(name, target)


def run(cmd):
    subprocess.run([str(part) for part in cmd], check=True)


if __name__ == "__main__":
    main(sys.argv[1:])
