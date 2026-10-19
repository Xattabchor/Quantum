#!/usr/bin/env python3
"""Compile and preverify Quantum without NetBeans. Requires real Java ME API jars.

Example:
  python3 scripts/build_cldc.py --java /path/to/java --ecj-jar /path/to/ecj.jar \
      --api-dir /path/to/j2me/lib --proguard-jar /path/to/proguard.jar

Use an ECJ supporting -source/-target 1.3 (tested with 3.16.0) and ProGuard
supporting -microedition (tested with 6.2.2). SDK libraries are never packaged.
No tools, SDKs, or game resources are downloaded by this script.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import zipfile
if __package__:
    from .game_resources import read_resources
    from .frame_profile import prepare_sources
else:
    from game_resources import read_resources
    from frame_profile import prepare_sources

ROOT = Path(__file__).resolve().parents[1]
API_NAMES = ("cldc11.jar", "midp21.jar", "jsr135.jar",
             "jsr184.jar", "jsr75_file.jar")


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def restore_unicode_resources(path, resources):
    """Keep verified classes, restore original resource bytes/names/UTF-8 flags.

    ProGuard 6.x writes UTF-8 entry names but can omit the ZIP UTF-8 flag.
    Repack only builds with non-ASCII asset names; do not guess mojibake names.
    Classfile contents (including CLDC StackMaps) are never transformed here.
    """
    if not any(not name.isascii() for name in resources):
        return
    temporary = path.with_name(path.name + '.resources.tmp')
    try:
        with zipfile.ZipFile(path) as source, zipfile.ZipFile(
                temporary, 'w', zipfile.ZIP_DEFLATED) as target:
            for entry in source.infolist():
                if entry.filename.endswith('.class') or entry.filename == 'META-INF/MANIFEST.MF':
                    target.writestr(entry, source.read(entry))
            for name, data in sorted(resources.items()):
                if name.lower().endswith('.class') or name.upper().startswith('META-INF/'):
                    raise ValueError('Executable/metadata is not a game resource: ' + name)
                target.writestr(name, data)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def audit_jar(path, allowed_prefixes=("code/",)):
    """Structural audit; actual CLDC data-flow preverification is ProGuard's job."""
    counts = {"classes": 0, "code_methods": 0, "stackmap_attributes": 0}
    versions = set()
    with zipfile.ZipFile(path) as jar:
        for name in jar.namelist():
            if not name.endswith(".class"):
                continue
            if not name.startswith(allowed_prefixes):
                raise ValueError("Unexpected packaged class: " + name)
            data = jar.read(name)
            pos = 0

            def take(n):
                nonlocal pos
                value = data[pos:pos + n]
                if len(value) != n:
                    raise ValueError("Truncated class: " + name)
                pos += n
                return value

            def u1():
                return take(1)[0]

            def u2():
                return struct.unpack(">H", take(2))[0]

            def u4():
                return struct.unpack(">I", take(4))[0]

            if u4() != 0xcafebabe:
                raise ValueError("Bad class magic: " + name)
            minor, major = u2(), u2()
            versions.add((major, minor))
            if major != 47 or minor != 0:
                raise ValueError("Expected Java 1.3 class file: " + name)
            pool = {}
            index, size = 1, u2()
            while index < size:
                tag = u1()
                if tag == 1:
                    pool[index] = take(u2()).decode("utf-8", errors="replace")
                elif tag in (3, 4, 9, 10, 11, 12):
                    take(4)
                elif tag in (5, 6):
                    take(8)
                    index += 1
                elif tag in (7, 8):
                    take(2)
                else:
                    raise ValueError("Non-CLDC constant pool tag: " + str(tag))
                index += 1

            def attributes():
                for _ in range(u2()):
                    kind, length = pool[u2()], u4()
                    end = pos + length
                    if kind == "Code":
                        counts["code_methods"] += 1
                        take(4)  # max_stack, max_locals
                        take(u4())
                        take(u2() * 8)  # exception table
                        attributes()
                    elif kind == "StackMap":
                        counts["stackmap_attributes"] += 1
                        take(length)
                    elif kind == "StackMapTable":
                        raise ValueError("Java SE StackMapTable in CLDC output")
                    else:
                        take(length)
                    if pos != end:
                        raise ValueError("Malformed attribute in " + name)

            take(6)  # access flags, this_class, super_class
            take(u2() * 2)  # interfaces
            for _ in range(2):  # fields, methods
                for _ in range(u2()):
                    take(6)
                    attributes()
            attributes()
            if pos != len(data):
                raise ValueError("Trailing class bytes: " + name)
            counts["classes"] += 1
    if not counts["classes"] or not counts["stackmap_attributes"]:
        raise ValueError("Missing classes or CLDC StackMap attributes")
    counts["class_versions"] = [list(v) for v in sorted(versions)]
    return counts


def overlay_resources(resources, directory):
    """Apply an opt-in game-specific overlay, never executable/manifest files."""
    directory = Path(directory)
    if not directory.is_dir():
        raise ValueError("Missing resource overlay directory: " + str(directory))
    result = dict(resources)
    for path in sorted(directory.rglob("*")):
        if path.is_file():
            name = path.relative_to(directory).as_posix()
            if name.lower().endswith(".class") or name.upper().startswith("META-INF/"):
                raise ValueError("Not a resource overlay file: " + name)
            result[name] = path.read_bytes()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--ecj-jar", type=Path, required=True)
    parser.add_argument("--api-dir", type=Path, required=True)
    parser.add_argument("--proguard-jar", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "artifacts")
    parser.add_argument("--resource-archive", type=Path,
                        help="Optional user game ZIP/JAR; assets only, never its classes or manifest")
    parser.add_argument("--resource-overlay", type=Path,
                        help="Opt-in directory overriding game assets (requires --resource-archive)")
    parser.add_argument("--profile-frame", action="store_true",
                        help="Diagnostic-only source copies with exclusive per-frame wall-clock HUD")
    args = parser.parse_args()
    archive_resources = read_resources(args.resource_archive) if args.resource_archive else {}
    if args.resource_overlay and not args.resource_archive:
        parser.error("--resource-overlay requires --resource-archive")
    if args.resource_overlay:
        # Validate before compiling; overlays take precedence over archive and res/.
        try:
            overlay_resources({}, args.resource_overlay)
        except ValueError as error:
            parser.error(str(error))
    ecj, proguard = args.ecj_jar.resolve(), args.proguard_jar.resolve()
    apis = [(args.api_dir / name).resolve() for name in API_NAMES]
    for path in [ecj, proguard] + apis:
        if not path.is_file():
            parser.error("Missing tool/API: " + str(path))
    source_dirs = [ROOT / name for name in ("src", "Utils/src", "UtilsText/src", "UtilsFPS/src")]
    for directory in source_dirs:
        if not directory.is_dir():
            parser.error("Missing sources; run git submodule update --init --recursive")
    work = ROOT / "build/cldc"
    work.mkdir(parents=True, exist_ok=True)
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    # Do not accidentally offer an old successful artifact after a failed build.
    for name in ("Quantum.jar", "Quantum.jad", "build-report.json"):
        (output / name).unlink(missing_ok=True)

    def run(command, log):
        with (work / log).open("w") as stream:
            subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, check=True)

    # SDK jars overlap; resolve each API class once in compile classpath order.
    seen = set()
    with zipfile.ZipFile(work / "api.jar", "w", zipfile.ZIP_DEFLATED) as merged:
        for api in apis:
            with zipfile.ZipFile(api) as jar:
                for name in jar.namelist():
                    if name.endswith(".class") and name not in seen:
                        merged.writestr(name, jar.read(name))
                        seen.add(name)
    sources = sorted(p for d in source_dirs for p in d.rglob("*.java"))
    if args.profile_frame:
        sources = prepare_sources(ROOT, sources, work / "frame-profile-src")
    # A fresh directory avoids including stale classes from a previous build.
    import tempfile
    with tempfile.TemporaryDirectory(prefix="classes-", dir=work) as temp:
        classes = Path(temp)
        source_args = work / "sources.args"
        source_args.write_text("\n".join('"' + str(p) + '"' for p in sources), encoding="utf-8")
        run([args.java, "-cp", str(ecj), "org.eclipse.jdt.internal.compiler.batch.Main",
             "-source", "1.3", "-target", "1.3", "-encoding", "UTF-8", "-proc:none",
             "-nowarn", "-bootclasspath", str(work / "api.jar"), "-d", str(classes),
             "@" + str(source_args)], "compile.log")
        app_name = "Quantum Profile" if args.profile_frame else "Quantum"
        manifest = ("Manifest-Version: 1.0\r\nMIDlet-Name: " + app_name + "\r\n"
                    "MIDlet-Version: 1.0.0\r\nMIDlet-Vendor: AE-MODS.RU\r\n"
                    "MIDlet-1: " + app_name + ",,code.utils.Main\r\n"
                    "MicroEdition-Configuration: CLDC-1.1\r\n"
                    "MicroEdition-Profile: MIDP-2.0\r\n\r\n")
        resources = dict(archive_resources)
        # Explicit repository resources override the optional archive. Neither
        # source is edited, and archive executable classes never enter the JAR.
        for path in sorted((ROOT / "res").rglob("*")):
            if path.is_file() and path.name != "README.md":
                resources[path.relative_to(ROOT / "res").as_posix()] = path.read_bytes()
        if args.resource_overlay:
            resources = overlay_resources(resources, args.resource_overlay)
        with zipfile.ZipFile(work / "Quantum-unverified.jar", "w", zipfile.ZIP_DEFLATED) as jar:
            jar.writestr("META-INF/MANIFEST.MF", manifest)
            for path in sorted(classes.rglob("*.class")):
                jar.write(path, path.relative_to(classes).as_posix())
            for name, data in sorted(resources.items()):
                jar.writestr(name, data)

    # Keep every class/method/name, especially the reflection-loaded M3G backend.
    # No -dontpreverify / -ignorewarnings: preverification failures are build failures.
    config = work / "preverify.pro"
    config.write_text("-injars Quantum-unverified.jar\n-outjars Quantum.jar\n"
                      "-libraryjars api.jar\n-microedition\n-target 1.3\n"
                      "-dontshrink\n-dontoptimize\n-dontobfuscate\n-keepattributes *\n-verbose\n")
    run([args.java, "-jar", str(proguard), "@" + str(config)], "preverify.log")
    restore_unicode_resources(work / "Quantum.jar", resources)
    result = audit_jar(work / "Quantum.jar")
    import shutil
    shutil.copyfile(work / "Quantum.jar", output / "Quantum.jar")
    jad = manifest.replace("Manifest-Version: 1.0\r\n", "").rstrip() + "\r\n"
    jad += "MIDlet-Jar-URL: Quantum.jar\r\nMIDlet-Jar-Size: " + str((output / "Quantum.jar").stat().st_size) + "\r\n"
    (output / "Quantum.jad").write_bytes(jad.encode("utf-8"))
    result.update({"jar_sha256": sha256(output / "Quantum.jar"),
                   "resource_files": len(resources), "source_files": len(sources),
                   "preverifier": "ProGuard -microedition (CLDC StackMap)",
                   "tools_sha256": {p.name: sha256(p) for p in [ecj, proguard] + apis},
                   "resource_archive_sha256": sha256(args.resource_archive) if args.resource_archive else None,
                   "frame_profile": args.profile_frame,
                   "runtime_tested": False})
    if args.resource_overlay:
        result["resource_overlay_sha256"] = {
            name: hashlib.sha256(data).hexdigest()
            for name, data in overlay_resources({}, args.resource_overlay).items()}
    (output / "build-report.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    print("Artifacts:", output)


if __name__ == "__main__":
    main()
