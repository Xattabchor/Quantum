#!/usr/bin/env python3
"""downloads/Quantum_FillTest.jar: the fill benchmark, ready to run.

Same code and same assets as the shipped build, but the developer menu is
reachable without digging through Settings and the perf HUD comes up already
on, so the result of "Run M3G SpeedTest" is visible immediately:

  Settings -> ... -> Debug  (or Menu -> Debug once it is on)
  Menu -> last item -> "Run M3G SpeedTest"

Only setting.txt differs from a normal build; no source is patched.
"""
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
BUILD = ROOT / 'build/filltest'
STAGE = BUILD / 'stage.zip'
OUT = BUILD / 'out'
RESULT = ROOT / 'downloads/Quantum_FillTest.jar'
JAD = ROOT / 'downloads/Quantum_FillTest.jad'
APP = 'Quantum Fill Test'

# Keys forced for the test build. M3G_PROFILE prints the pass profile to stdout
# as well, so a run captured through a logger shows both tables.
FORCE = {
    'M3G_PROFILE': '1',
}


def stage(base_jar: Path) -> None:
    stage_items = {}
    with zipfile.ZipFile(base_jar) as jar:
        for name in jar.namelist():
            if name.endswith('.class') or name.startswith('META-INF'):
                continue
            stage_items[name] = jar.read(name)
    text = stage_items['setting.txt'].decode('cp1251')
    lines = [l for l in text.splitlines()
             if not any(l.strip().startswith(k + '=') for k in FORCE)]
    for k, v in FORCE.items():
        lines.append('%s=%s' % (k, v))
    stage_items['setting.txt'] = ('\n'.join(lines) + '\n').encode('cp1251')
    BUILD.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(STAGE, 'w', zipfile.ZIP_DEFLATED) as jar:
        for name in sorted(stage_items):
            jar.writestr(name, stage_items[name])
    print('staged %d assets' % len(stage_items))


def main() -> None:
    base = ROOT / 'build/res3/stage.zip'
    if not base.exists():
        raise SystemExit('run tools/res3/build_res3.py first')
    stage(base)
    if OUT.exists():
        shutil.rmtree(OUT)
    subprocess.run([sys.executable, str(ROOT / 'scripts/build_cldc.py'),
                    '--java', str(ROOT / 'build/tools/jdk8/bin/java'),
                    '--ecj-jar', str(ROOT / 'build/tools/package/server/plugins/'
                                     'org.eclipse.jdt.core_3.16.0.v20181004-1305.jar'),
                    '--api-dir', str(ROOT / 'build/sdk/lib'),
                    '--proguard-jar', str(ROOT / 'build/sdk/proguard/lib/proguard.jar'),
                    '--output-dir', str(OUT),
                    '--resource-archive', str(STAGE)], check=True)
    jar = OUT / 'Quantum.jar'
    tmp = jar.with_suffix('.tmp')
    with zipfile.ZipFile(jar) as src, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            data = src.read(info.filename)
            if info.filename == 'META-INF/MANIFEST.MF':
                t = data.decode('ascii')
                t = t.replace('MIDlet-Name: Quantum', 'MIDlet-Name: %s' % APP)
                t = t.replace('MIDlet-1: Quantum,', 'MIDlet-1: %s,' % APP)
                data = t.encode('ascii')
            dst.writestr(info, data)
    tmp.replace(jar)
    RESULT.write_bytes(jar.read_bytes())
    jad = (OUT / 'Quantum.jad').read_text(encoding='ascii').replace('\r\n', '\n')
    out = []
    for line in jad.split('\n'):
        if line.startswith('MIDlet-Jar-Size:'):
            line = 'MIDlet-Jar-Size: %d' % RESULT.stat().st_size
        elif line.startswith('MIDlet-Jar-URL:'):
            line = 'MIDlet-Jar-URL: %s' % RESULT.name
        elif line.startswith('MIDlet-Name:'):
            line = 'MIDlet-Name: %s' % APP
        elif line.startswith('MIDlet-1:'):
            line = 'MIDlet-1: %s,,code.utils.Main' % APP
        out.append(line)
    JAD.write_text('\r\n'.join(out), encoding='ascii')
    print('published %s (%d B)' % (RESULT, RESULT.stat().st_size))


if __name__ == '__main__':
    main()
