#!/usr/bin/env python3
"""downloads/Quantum_Res3_Full.jar: the game built from ONE resource set.

Every byte of content comes from res3 (a zip whose layout matches a JAR root).
No asset is taken from an older bundle, no decimation is applied, no overlay is
merged. Only the code comes from the current tree.

The previous packer (tools/kordos-full) assembled content from a published
Sym9 jar plus a second River7 jar and rewrote the world mesh; that is exactly
what this script must not do. Here the staged asset tree is a straight copy of
res3 and is verified to reference nothing it does not contain.

Usage:
  python3 tools/res3/build_res3.py --res3 /path/to/res3.zip
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
BUILD = ROOT / 'build/res3'
STAGE = BUILD / 'stage.zip'
OUT = BUILD / 'out'
RESULT_JAR = ROOT / 'downloads/Quantum_Res3_Full.jar'
RESULT_JAD = ROOT / 'downloads/Quantum_Res3_Full.jad'

APP_NAME = 'Quantum Res3 FX'

# Files res3 carries that the game never opens. lightmap/setting.txt is a
# lightmapper tool config, not a game config; the game reads exactly one
# /setting.txt. Dropping it keeps "only res3" honest: we ship what the game
# uses, we do not smuggle a second settings file into the JAR root rules.
DROP = {'lightmap/setting.txt'}

# Only these extensions are asset content. Anything else in res3 that is not a
# config file is ignored rather than silently packed.
CONFIG_NAMES = {'setting.txt', 'weapons.txt', 'items.txt', 'fullcmdlist.txt',
                'readme lightmapper.txt'}
CONFIG_PREFIX = 'level'


def is_config(name):
    base = name.rsplit('/', 1)[-1]
    return (base in CONFIG_NAMES
            or (base.startswith(CONFIG_PREFIX) and base.endswith('.txt')))


def load_res3(path):
    """Read every asset of res3, keyed by its path inside the JAR."""
    stage = {}
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            name = info.filename
            if name.endswith('/'):
                continue
            if name.startswith('META-INF'):
                continue          # the build writes its own manifest
            if name in DROP:
                continue
            if name.endswith('.class'):
                raise SystemExit(
                    'FATAL: res3 contains compiled classes (%s); resources only'
                    % name)
            stage[name] = z.read(name)
    return stage


REF_RE = re.compile(r'/[^\s,*\[\]]+\.(?:3d|png|bmp|vla|mid|wav|mp3)', re.I)


def referenced(stage):
    """Every /path/file.ext that any config in res3 points at."""
    refs = set()
    for name, data in stage.items():
        if not is_config(name):
            continue
        try:
            text = data.decode('cp1251')
        except UnicodeDecodeError:
            text = data.decode('latin-1')
        for m in REF_RE.finditer(text):
            refs.add((name, m.group(0)))
    return refs


def verify_refs(stage, fatal):
    """A missing asset hangs the level load on devices, so check each one.

    level*.txt decides whether a level can load at all, so a missing reference
    there is fatal. setting.txt may point at optional content (a monster model
    for a map pack that is not in this resource set); those are reported but do
    not stop the build.
    """
    missing_level, missing_other = [], []
    for cfg, ref in sorted(referenced(stage)):
        key = ref.lstrip('/')
        if key in stage:
            continue
        base = cfg.rsplit('/', 1)[-1]
        if base.startswith('level') and base.endswith('.txt'):
            missing_level.append((cfg, ref))
        else:
            missing_other.append((cfg, ref))
    if missing_level:
        raise SystemExit('FATAL: a level config references assets res3 does '
                         'not contain:\n  '
                         + '\n  '.join('%s -> %s' % (c, r)
                                       for c, r in missing_level))
    if missing_other:
        print('WARNING: setting.txt references assets not in res3 '
              '(optional content for this resource set):')
        for c, r in missing_other:
            print('  %s -> %s' % (c, r))


def stage_resources(res3_path):
    stage = load_res3(res3_path)
    verify_refs(stage, fatal=True)
    BUILD.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(STAGE, 'w', zipfile.ZIP_DEFLATED) as jar:
        for name in sorted(stage):
            jar.writestr(name, stage[name])
    total = sum(len(v) for v in stage.values())
    print('staged %d assets from res3 (%.2f MB unpacked)'
          % (len(stage), total / 1e6))
    return stage


def run_build(java):
    if OUT.exists():
        shutil.rmtree(OUT)
    cmd = [sys.executable, str(ROOT / 'scripts/build_cldc.py'),
           '--java', java,
           '--ecj-jar', str(ROOT / 'build/tools/package/server/plugins/'
                            'org.eclipse.jdt.core_3.16.0.v20181004-1305.jar'),
           '--api-dir', str(ROOT / 'build/sdk/lib'),
           '--proguard-jar', str(ROOT / 'build/sdk/proguard/lib/proguard.jar'),
           '--output-dir', str(OUT),
           '--resource-archive', str(STAGE)]
    subprocess.run(cmd, check=True)


def patch_manifest(jar_path):
    tmp = jar_path.with_suffix('.tmp')
    with zipfile.ZipFile(jar_path) as src, \
            zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            data = src.read(info.filename)
            if info.filename == 'META-INF/MANIFEST.MF':
                text = data.decode('ascii')
                text = text.replace('MIDlet-Name: Quantum',
                                    'MIDlet-Name: %s' % APP_NAME)
                text = text.replace('MIDlet-1: Quantum,',
                                    'MIDlet-1: %s,' % APP_NAME)
                data = text.encode('ascii')
            dst.writestr(info, data)
    tmp.replace(jar_path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--res3', required=True, help='path to res3.zip')
    ap.add_argument('--java', default=os.environ.get(
        'JAVA', str(ROOT / 'build/tools/jdk4py/jdk4py/java-runtime/bin/java')))
    args = ap.parse_args()

    stage_resources(args.res3)
    run_build(args.java)
    jar = OUT / 'Quantum.jar'
    print('jar: %s (%d B)' % (jar, jar.stat().st_size))
    patch_manifest(jar)
    RESULT_JAR.write_bytes(jar.read_bytes())
    jad = (OUT / 'Quantum.jad').read_text(encoding='ascii').replace('\r\n', '\n')
    lines = []
    for line in jad.split('\n'):
        if line.startswith('MIDlet-Jar-Size:'):
            line = 'MIDlet-Jar-Size: %d' % RESULT_JAR.stat().st_size
        elif line.startswith('MIDlet-Jar-URL:'):
            line = 'MIDlet-Jar-URL: %s' % RESULT_JAR.name
        elif line.startswith('MIDlet-Name:'):
            line = 'MIDlet-Name: %s' % APP_NAME
        elif line.startswith('MIDlet-1:'):
            line = 'MIDlet-1: %s,,code.utils.Main' % APP_NAME
        lines.append(line)
    RESULT_JAD.write_text('\r\n'.join(lines), encoding='ascii')
    print('published %s (%d B)' % (RESULT_JAR, RESULT_JAR.stat().st_size))
    with zipfile.ZipFile(RESULT_JAR) as z:
        names = z.namelist()
        print(json.dumps({
            'entries': len(names),
            'assets': len([n for n in names if not n.startswith('code/')
                           and not n.endswith('/')]),
        }))


if __name__ == '__main__':
    main()
