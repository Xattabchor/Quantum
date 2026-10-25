#!/usr/bin/env python3
"""Downloads/Quantum_Kordos_Full.jar: full game on the kordos (old sym9
village) map with hardware (M3G) lighting and a weapon omni flash.

Content source (already published in this repository, so the pack is
reproducible offline):
  - downloads/Quantum_Sym9_Mode9.jar - the FULL original sym9 village
    bundle (kordos map + all lightmaps, skies, sounds, music, npc, props,
    shop, images). level*/setting.txt inside are replaced by the overlay.
  - tools/kordos-full/overlay/{setting.txt,level1.txt} - kordos world
    configuration with M3G_LIT=1 and combat spawns.

Code comes from the current tree (build_cldc.py); the manifest is patched
last so the MIDlet shows as "Quantum Kordos FX" instead of "Quantum".

Usage: python3 tools/kordos-full/build_kordos_full.py
Toolchain resolved the same way as scripts/build_cldc.py defaults
(build/tools/*, build/sdk/*); see .github or docs/build-cldc.md.
"""
import json
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
BUILD = ROOT / 'build/kordos-full'
STAGE = BUILD / 'stage.zip'
OUT = BUILD / 'out'
RESULT_JAR = ROOT / 'downloads/Quantum_Kordos_Full.jar'
RESULT_JAD = ROOT / 'downloads/Quantum_Kordos_Full.jad'

SYM9 = ROOT / 'downloads/Quantum_Sym9_Mode9.jar'
RIVER7 = ROOT / 'downloads/Quantum_Level41_River7_48bd4d0.jar'

# Assets the overlay (setting.txt/level1.txt) references but the sym9 bundle
# does not contain; pulled from the River7 jar (verified present there).
EXTRAS_RIVER7 = (
    'bots/blood.png', 'bots/decal_blood.png',
    'bots/evil/monster.3d', 'bots/evil/monster.png',
    'bots/shiver bot/damage.png',
    'bots/shiver bot/death0.png', 'bots/shiver bot/death1.png',
    'bots/shiver bot/shoot0.png', 'bots/shiver bot/shoot1.png',
    'bots/shiver bot/walk0.png', 'bots/shiver bot/walk1.png',
    'bots/shiver bot/walk_back0.png', 'bots/shiver bot/walk_back1.png',
    'bots/shiver bot/walk_side0.png', 'bots/shiver bot/walk_side1.png',
    'meshes/cars/carNagib.png', 'meshes/cars/car_down.3d',
    'meshes/computer.3d', 'meshes/computer.png',
    'shop/icon_medicine_chest.png', 'shop/icon_patron.png',
    'sounds/fx_step1.wav', 'sounds/fx_step3.wav',
)

# World decimation (silhouette-safe): welds coincident vertices, merges
# coplanar same-material tri pairs into quads, drops speck polys below
# DECIMATE_MIN_AREA world area, and rewrites the baked corner lightmap
# (DMODE=9 records) so the .vla stays in poly order. Controllable via
# env: KORDOS_DECIMATE=0 disables, KORDOS_MIN_AREA overrides the area.
DECIMATE_MIN_AREA = float(os.environ.get('KORDOS_MIN_AREA', '1e5'))
DECIMATE_ENABLED = os.environ.get('KORDOS_DECIMATE', '1') == '1'


def decimate_world(stage: dict) -> None:
    if not DECIMATE_ENABLED:
        return
    if 'maps/kordos/kordos.3d' not in stage:
        return
    tool = ROOT / 'tools/kordos-full/decimate3d.py'
    tmp = BUILD / 'decimate-inout'
    tmp.mkdir(parents=True, exist_ok=True)
    src3d = tmp / 'kordos.3d'; dst3d = tmp / 'kordos.dec.3d'
    srcvla = tmp / 'kordod.vla'; dstvla = tmp / 'kordod.dec.vla'
    src3d.write_bytes(stage['maps/kordos/kordos.3d'])
    srcvla.write_bytes(stage['lightmap/kordod.vla'])
    subprocess.run([sys.executable, str(tool), str(src3d), str(dst3d),
                    '--scale', '6', '--min-area', repr(DECIMATE_MIN_AREA),
                    '--vla', str(srcvla), str(dstvla)], check=True)
    stage['maps/kordos/kordos.3d'] = dst3d.read_bytes()
    stage['lightmap/kordod.vla'] = dstvla.read_bytes()


def verify_overlay_refs(stage: dict) -> None:
    # Every /path/file.ext referenced by the overlay MUST exist in the staged
    # assets — a missing file hangs the level load on real devices/emu.
    import re
    overlay = ROOT / 'tools/kordos-full/overlay'
    missing = []
    for cfg in ('level1.txt', 'setting.txt'):
        refs = set(re.findall(r'/[\w\-./ ]+\.(?i:3d|png|bmp|wav|mid|mp3|vla)',
                              (overlay / cfg).read_text()))
        missing += sorted(r for r in refs if r.lstrip('/') not in stage)
    if missing:
        raise SystemExit('FATAL: overlay references missing assets:\n  '
                         + '\n  '.join(missing))


def stage_resources() -> None:
    stage: dict[str, bytes] = {}
    # Full sym9 bundle: every non-class, non-config resource of the original
    # village build. level*.txt/setting.txt come from the overlay instead.
    with zipfile.ZipFile(SYM9) as jar:
        for name in jar.namelist():
            if name.endswith(('.class',)) or name.startswith('META-INF'):
                continue
            base = name.rsplit('/', 1)[-1]
            if base in ('setting.txt',) or base.startswith('level') and base.endswith('.txt'):
                continue  # replaced by the overlay, on purpose
            stage[name] = jar.read(name)
    # Overlay-referenced extras missing from the sym9 bundle.
    with zipfile.ZipFile(RIVER7) as jar:
        for name in EXTRAS_RIVER7:
            if name not in stage:
                stage[name] = jar.read(name)
    decimate_world(stage)
    verify_overlay_refs(stage)
    BUILD.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(STAGE, 'w', zipfile.ZIP_DEFLATED) as jar:
        for name in sorted(stage):
            jar.writestr(name, stage[name])
    print(f'staged {len(stage)} assets',
          f'({sum(len(v) for v in stage.values()) / 1e6:.2f} MB unpacked)')


def run_build(java: str) -> None:
    if OUT.exists():
        shutil.rmtree(OUT)
    cmd = [sys.executable, str(ROOT / 'scripts/build_cldc.py'),
           '--java', java,
           '--ecj-jar', str(ROOT / 'build/tools/package/server/plugins/org.eclipse.jdt.core_3.16.0.v20181004-1305.jar'),
           '--api-dir', str(ROOT / 'build/sdk/lib'),
           '--proguard-jar', str(ROOT / 'build/sdk/proguard/lib/proguard.jar'),
           '--output-dir', str(OUT),
           '--resource-archive', str(STAGE),
           '--resource-overlay', str(ROOT / 'tools/kordos-full/overlay')]
    subprocess.run(cmd, check=True)


def patch_manifest(jar_path: Path, app_name='Quantum Kordos FX') -> None:
    tmp = jar_path.with_suffix('.tmp')
    with zipfile.ZipFile(jar_path) as src, \
            zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            data = src.read(info.filename)
            if info.filename == 'META-INF/MANIFEST.MF':
                text = data.decode('ascii')
                text = text.replace('MIDlet-Name: Quantum', f'MIDlet-Name: {app_name}')
                text = text.replace('MIDlet-1: Quantum,', f'MIDlet-1: {app_name},')
                data = text.encode('ascii')
            dst.writestr(info, data)
    tmp.replace(jar_path)


def main() -> None:
    java = os.environ.get('JAVA', str(ROOT / 'build/tools/jdk4py/jdk4py/java-runtime/bin/java'))
    stage_resources()
    run_build(java)
    jar = OUT / 'Quantum.jar'
    print(f'jar: {jar} ({jar.stat().st_size} B)')
    patch_manifest(jar)
    RESULT_JAR.write_bytes(jar.read_bytes())  # final bytes AFTER repacking
    jad_text = (OUT / 'Quantum.jad').read_text(encoding='ascii') \
        .replace('\r\n', '\n')
    lines = []
    for line in jad_text.split('\n'):
        if line.startswith('MIDlet-Jar-Size:'):
            line = f'MIDlet-Jar-Size: {RESULT_JAR.stat().st_size}'
        elif line.startswith('MIDlet-Jar-URL:'):
            line = 'MIDlet-Jar-URL: Quantum_Kordos_Full.jar'
        elif line.startswith('MIDlet-Name:'):
            line = f'MIDlet-Name: Quantum Kordos FX'
        elif line.startswith('MIDlet-1:'):
            line = 'MIDlet-1: Quantum Kordos FX,,code.utils.Main'
        lines.append(line)
    RESULT_JAD.write_text('\r\n'.join(lines), encoding='ascii')
    print(f'published {RESULT_JAR} ({RESULT_JAR.stat().st_size} B)')
    with zipfile.ZipFile(RESULT_JAR) as z:
        names = z.namelist()
        print(json.dumps({
            'entries': len(names),
            'assets': sum(1 for n in names if not n.endswith('.class') and not n.startswith('META-INF')),
        }))


if __name__ == '__main__':
    main()
