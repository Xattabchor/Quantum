#!/usr/bin/env python3
"""Build isolated river-map overdraw A/B over immutable Tex3 release."""
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
BASE = ROOT / 'downloads/Quantum_StaticRoom_Tex3.jar'
BASE_SHA = '8617f348e04f4b10c03b0049e60f2e92c41532d6939546ad96f6e44d11aa6880'
BASE_COMMIT = 'b9b477f3de65e2a2ecc2e0f88ad10a09244f22fa'
STEM = 'Quantum_RiverMap_Overdraw'
NEW_CLASSES = {
    'code/Rendering/RiverOverdrawMesh.class',
    'code/Rendering/RiverOverdrawMesh$NativeImage.class',
    'code/Rendering/RiverOverdrawMesh$Part.class',
    'code/Rendering/RiverOverdrawMesh$Masked.class',
    'code/Rendering/RiverOverdrawMesh$Pending.class',
    'code/Rendering/RiverOverdrawMesh$Packet.class',
    'code/Rendering/RiverOverdrawPreview.class',
    'code/Rendering/RiverOverdrawPreview$Viewer.class',
    'code/Rendering/RiverOverdrawStats.class',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=os.environ.get('JAVA', 'java'))
    parser.add_argument('--ecj-jar', default=os.environ.get('ECJ_JAR', 'build/tools/ecj.jar'))
    parser.add_argument('--api-dir', type=Path, default=ROOT / 'build/sdk/lib')
    parser.add_argument('--proguard-jar', default=str(ROOT / 'build/sdk/proguard/lib/proguard.jar'))
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'artifacts/river-overdraw')
    args = parser.parse_args()
    subprocess.run([sys.executable, str(ROOT / 'scripts/verify_render_checkpoints.py')], check=True)
    if hashlib.sha256(BASE.read_bytes()).hexdigest() != BASE_SHA:
        raise ValueError('Wrong immutable Tex3 parent')

    work = ROOT / 'build/river-overdraw'
    classes = work / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    for stale in classes.rglob('*.class'):
        stale.unlink()
    api = [(args.api_dir / name).resolve() for name in API_NAMES]
    sources = sorted((ROOT / 'experiments/river-overdraw/src').rglob('*.java'))
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
        'MIDlet-Name: Quantum River Overdraw',
        'MIDlet-Version: 1.0.0',
        'MIDlet-Vendor: Quantum Experiments',
        'MIDlet-1: River Overdraw,,code.Rendering.RiverOverdrawPreview',
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
            (ROOT / 'experiments/static-room-packets/src/code/Rendering/StaticRoomPacketsMesh.java').read_bytes()
        ).hexdigest(),
        stats_parent_source_sha256=hashlib.sha256(
            (ROOT / 'experiments/static-room-layouts/src/code/Rendering/StaticRoomLayoutsStats.java').read_bytes()
        ).hexdigest(),
        diagnostic={
            'purpose': 'Reduce/measure opaque overdraw on the river/village map without changing selected geometry. No handset FPS claim.',
            'map': 'old sym9 village/river map from immutable Tex3 parent: maps/kordos/kordos.3d, baked lightmap/kordod.vla, Tex3 slots forest/map-selo1/river',
            'geometry_changed': False,
            'textures_changed': False,
            'lightmap_changed': False,
            'selection': 'same GRID selector and same selected faces in every mode; BVH explicitly OFF',
            'modes': {
                'default': 'C F2B',
                'key1': 'A LARGE: original per-material large VertexBuffers, original order (control)',
                'key3': 'C ORIG: compact <=512-corner spatial packets, original order',
                'key7': 'C F2B: same compact packets/calls, opaque packets sorted front-to-back by nearest AABB depth',
                'key9': 'C ZPRE: C F2B plus depth-only prepass (color/alpha write off), then color pass with depth write off; river pass unchanged',
                'key0': 'cycle A -> C ORIG -> C F2B -> C ZPRE',
                'star': 'freeze/show full counters; press again to resume with fresh warmup',
            },
            'hud': 'live measurement uses only two lines (mode/FPS and N/R/I); full counters only in frozen result',
            'packet_sort': 'stable insertion sort of selected opaque compact packets by nearest world-AABB depth along camera forward; sorting cost is in J/prepare, no per-frame index or vertex allocation',
            'depth_prepass': 'JSR-184 CompositingMode color/alpha write masks; cutout parts keep texture+alpha threshold in depth pass, fully opaque parts use no texture there; M3G depth test is LEQUAL so the color pass can pass at equal depth',
            'm3g_compatibility': 'Target user-reported Nokia E65 M3G 1.0: no M3G 1.1 OVERWRITE hint/getters or new renderer APIs; color/alpha write masks and LEQUAL depth are JSR-184 1.0 features, still to be verified on handset',
            'near': 128,
            'far': 60000,
            'configured_far': 68500,
            'fov': 74,
            'DMODE': 9,
            'world_scale': 6,
            'alpha': 'original per-image thresholds ON in color passes; no alpha-disable control',
            'depth': 'test ON; write ON for opaque color/prepass as specified per mode, OFF for river',
            'perspective': 'ON fixed',
            'river': 'all 4 cached frames, speed6, mode7, original pass/order; excluded from depth prepass',
            'pump': 'same worker repaint()+serviceRepaints()+sleep(1) in all modes',
            'warmup_ms': 1000,
            'measure_ms': 3000,
            'visual_risk': 'front-to-back packet order can change winners only for exactly/near coplanar overlapping faces; check for flicker/artifacts on handset',
            'scope': 'world-only preview: no sky/weather/gameplay/collision changes',
            'stats_note': 'J/B/K/R/E and N/C/I are Java API wall times, NOT GPU timestamps or proven LCD flips; VB counts actual submitted buffer capacities including both ZPRE passes',
        },
        handset_tested=False,
        handset_fps_measured=False,
        purpose='Isolated river-map overdraw A/B: large vs compact packets, front-to-back packet submission, and optional M3G depth prepass.',
    )
    (out / (STEM + '.build-report.json')).write_text(json.dumps(report, indent=2) + '\n')
    jad = manifest.replace('Manifest-Version: 1.0\r\n', '').rstrip()
    jad += '\r\nMIDlet-Jar-URL: ' + jar.name + '\r\nMIDlet-Jar-Size: ' + str(jar.stat().st_size) + '\r\n'
    (out / (STEM + '.jad')).write_bytes(jad.encode('utf-8'))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
