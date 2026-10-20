#!/usr/bin/env python3
"""Build isolated river-map HUD FULL/MIN/OFF diagnostic over Perspective release."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_cldc import API_NAMES, audit_jar

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'downloads/Quantum_StaticRoom_Perspective.jar'
BASE_SHA = '652626cddcc8b991a4c9ba76ac810cd1d163f7041f393ff7e31fd8f44f3dcb57'
BASE_COMMIT = '7bbc7b172c87aa05026c98bed3b6854b94f9a1a5'
STEM = 'Quantum_RiverMap_Hud'
NEW_CLASSES = {
    'code/Rendering/RiverMapHudPreview.class',
    'code/Rendering/RiverMapHudPreview$Viewer.class',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=os.environ.get('JAVA', 'java'))
    parser.add_argument('--ecj-jar', default=os.environ.get('ECJ_JAR', 'build/tools/ecj.jar'))
    parser.add_argument('--api-dir', type=Path, default=ROOT / 'build/sdk/lib')
    parser.add_argument('--proguard-jar', default=str(ROOT / 'build/sdk/proguard/lib/proguard.jar'))
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'artifacts/river-map-hud')
    args = parser.parse_args()
    subprocess.run([sys.executable, str(ROOT / 'scripts/verify_render_checkpoints.py')], check=True)
    if hashlib.sha256(BASE.read_bytes()).hexdigest() != BASE_SHA:
        raise ValueError('Wrong immutable Perspective parent')

    work = ROOT / 'build/river-map-hud'
    classes = work / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    for stale in classes.rglob('*.class'):
        stale.unlink()
    api = [(args.api_dir / name).resolve() for name in API_NAMES]
    sources = sorted((ROOT / 'experiments/river-map-hud/src').rglob('*.java'))
    subprocess.run([
        args.java, '-jar', args.ecj_jar, '-source', '1.3', '-target', '1.3',
        '-encoding', 'UTF-8', '-proc:none', '-nowarn',
        '-bootclasspath', os.pathsep.join(map(str, api)),
        '-classpath', str(BASE), '-d', str(classes),
    ] + list(map(str, sources)), check=True)

    raw, verified = work / 'unverified.jar', work / 'verified.jar'
    with zipfile.ZipFile(raw, 'w', zipfile.ZIP_DEFLATED) as target:
        for path in sorted(classes.rglob('*.class')):
            target.write(path, path.relative_to(classes).as_posix())
    command = [args.java, '-jar', args.proguard_jar, '-injars', str(raw), '-outjars', str(verified), '-libraryjars', str(BASE)]
    for path in api:
        command += ['-libraryjars', str(path)]
    subprocess.run(command + ['-microedition', '-dontshrink', '-dontoptimize', '-dontobfuscate', '-dontnote', '-dontwarn'], check=True)

    out = args.output_dir.resolve()
    out.mkdir(parents=True, exist_ok=True)
    manifest = '\r\n'.join([
        'Manifest-Version: 1.0',
        'MIDlet-Name: Quantum River Map HUD',
        'MIDlet-Version: 1.0.0',
        'MIDlet-Vendor: Quantum Experiments',
        'MIDlet-1: River HUD A-B,,code.Rendering.RiverMapHudPreview',
        'MicroEdition-Configuration: CLDC-1.1',
        'MicroEdition-Profile: MIDP-2.0',
        '',
        '',
    ])
    jar = out / (STEM + '.jar')
    with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(verified) as patch, zipfile.ZipFile(jar, 'w') as result:
        added = {name for name in patch.namelist() if name.endswith('.class')}
        assert added == NEW_CLASSES and not added.intersection(base.namelist())
        entries = {name: base.read(name) for name in base.namelist() if name != 'META-INF/MANIFEST.MF'}
        entries.update({name: patch.read(name) for name in added})
        entries['META-INF/MANIFEST.MF'] = manifest.encode('utf-8')
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, (2026, 10, 20, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            result.writestr(info, entries[name], compresslevel=9)

    with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(jar) as result:
        assert result.testzip() is None
        assert set(result.namelist()) == set(base.namelist()) | NEW_CLASSES
        assert all(result.read(name) == base.read(name) for name in base.namelist() if name != 'META-INF/MANIFEST.MF')

    report = audit_jar(jar)
    report.update(
        base_jar=BASE.name,
        base_commit=BASE_COMMIT,
        base_sha256=BASE_SHA,
        jar_bytes=jar.stat().st_size,
        jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
        all_parent_classes_and_all_assets_unchanged_except_manifest=True,
        added_classes=sorted(NEW_CLASSES),
        added_assets=[],
        source_sha256={
            str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sources
        },
        builder_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        renderer_parent_source_sha256=hashlib.sha256(
            (ROOT / 'experiments/static-room-perspective/src/code/Rendering/StaticRoomPerspectiveMesh.java').read_bytes()
        ).hexdigest(),
        stats_parent_source_sha256=hashlib.sha256(
            (ROOT / 'experiments/static-room-perspective/src/code/Rendering/StaticRoomPerspectiveStats.java').read_bytes()
        ).hexdigest(),
        diagnostic={
            'purpose': 'Measure whether the diagnostic HUD itself changes frame interval on the river/village map. No renderer optimization claim.',
            'map': 'old sym9 village/river map from immutable Perspective/Tex3 parent: maps/kordos/kordos.3d, baked kordod.vla, Tex3 slots forest/map-selo1/river',
            'geometry_changed': False,
            'textures_changed': False,
            'lightmap_changed': False,
            'renderer_changed': False,
            'renderer': 'immutable parent StaticRoomPerspectiveMesh/Stats class bytes retained; GRID4x4, perspective ON',
            'hud_modes': {
                'key1': 'FULL: six lines on 90px panel, drawn every frame',
                'key3': 'MIN: heading + N/C/I on 30px panel, drawn every frame',
                'key0': 'OFF: no panel/text after map Image copy; stats still collected',
                'star': 'in OFF, freeze and show last completed FULL result; press star again to resume with fresh warmup',
            },
            'default': 'FULL at original START pose/yaw0',
            'key5': 'home; preserves selected HUD mode, resets stats',
            'pump': 'same parent worker repaint()+serviceRepaints()+sleep(1) in all HUD modes; not FramePump SELF/S0',
            'near': 128,
            'far': 60000,
            'configured_far': 68500,
            'fov': 74,
            'DMODE': 9,
            'world_scale': 6,
            'alpha': 'original thresholds ON',
            'depth': 'ON; river depth write OFF as in parent materials',
            'perspective': 'ON (fixed; no ON/OFF toggle in this HUD diagnostic)',
            'river': 'all 4 cached frames, speed6, mode7, UV/order unchanged',
            'warmup_ms': 1000,
            'measure_ms': 3000,
            'scope': 'world-only preview: no sky/weather/gameplay/collision changes',
            'stats_note': 'StaticRoomPerspectiveStats wall-clock API timings, NOT GPU timestamps or proven LCD flips',
        },
        handset_tested=False,
        handset_fps_measured=False,
        purpose='Isolated river-map HUD FULL/MIN/OFF A/B over immutable Perspective release; map/renderer/pump unchanged.',
    )
    (out / (STEM + '.build-report.json')).write_text(json.dumps(report, indent=2) + '\n')
    jad = manifest.replace('Manifest-Version: 1.0\r\n', '').rstrip()
    jad += '\r\nMIDlet-Jar-URL: ' + jar.name + '\r\nMIDlet-Jar-Size: ' + str(jar.stat().st_size) + '\r\n'
    (out / (STEM + '.jad')).write_bytes(jad.encode('utf-8'))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
