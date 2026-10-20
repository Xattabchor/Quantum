#!/usr/bin/env python3
"""Build isolated river-map pixel-cost diagnostic over Perspective release."""
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
STEM = 'Quantum_RiverMap_Pixel'
NEW_CLASSES = {
    'code/Rendering/RiverPixelMesh.class',
    'code/Rendering/RiverPixelMesh$NativeImage.class',
    'code/Rendering/RiverPixelMesh$Part.class',
    'code/Rendering/RiverPixelMesh$Masked.class',
    'code/Rendering/RiverPixelMesh$Pending.class',
    'code/Rendering/RiverPixelPreview.class',
    'code/Rendering/RiverPixelPreview$Viewer.class',
    'code/Rendering/RiverPixelStats.class',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=os.environ.get('JAVA', 'java'))
    parser.add_argument('--ecj-jar', default=os.environ.get('ECJ_JAR', 'build/tools/ecj.jar'))
    parser.add_argument('--api-dir', type=Path, default=ROOT / 'build/sdk/lib')
    parser.add_argument('--proguard-jar', default=str(ROOT / 'build/sdk/proguard/lib/proguard.jar'))
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'artifacts/river-pixel')
    args = parser.parse_args()
    subprocess.run([sys.executable, str(ROOT / 'scripts/verify_render_checkpoints.py')], check=True)
    if hashlib.sha256(BASE.read_bytes()).hexdigest() != BASE_SHA:
        raise ValueError('Wrong immutable Perspective parent')

    work = ROOT / 'build/river-pixel'
    classes = work / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    for stale in classes.rglob('*.class'):
        stale.unlink()
    api = [(args.api_dir / name).resolve() for name in API_NAMES]
    sources = sorted((ROOT / 'experiments/river-pixel/src').rglob('*.java'))
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
        'MIDlet-Name: Quantum River Pixel',
        'MIDlet-Version: 1.0.0',
        'MIDlet-Vendor: Quantum Experiments',
        'MIDlet-1: River Pixel,,code.Rendering.RiverPixelPreview',
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
            'purpose': 'Split R on the river map by shaded area and per-pixel texture/shading/filter cost. No handset FPS claim.',
            'map': 'old sym9 village/river map from immutable Perspective/Tex3 parent: maps/kordos/kordos.3d, baked lightmap/kordod.vla, Tex3 slots forest/map-selo1/river',
            'geometry_changed': False,
            'textures_changed': False,
            'lightmap_changed': False,
            'renderer': 'fork of immutable StaticRoomPerspectiveMesh/Stats; same GRID selector, face order, buffers, calls and stage timers',
            'default': 'S100 TEX SM B (full viewport, atlas textures, smooth shading, base-level filtering)',
            'controls': {
                'key1': 'BASE reset: S100 + TEX + SMOOTH + BASE_LEVEL',
                'key0': 'cycle render scale 100 -> 75 -> 50 -> 25 percent (other toggles preserved)',
                'key3': 'render scale 50 percent (other toggles preserved)',
                'key7': 'toggle atlas textures <-> 1x1 white texture modulated by baked vertex colors (alpha threshold forced 0 in 1X1; diagnostic image intentionally grayscale/solid)',
                'key9': 'toggle PolygonMode SMOOTH <-> FLAT shading',
                'star': 'toggle texture level filter BASE_LEVEL <-> NEAREST (nearest mip level; image filter remains NEAREST)',
                'key5': 'home; scale/texture/shading/filter preserved',
                'movement': '2/8 move, 4/6 turn; no strafe because 7/9 are pixel toggles',
            },
            'render_scale': 'native M3G Image/viewport itself is scaled with proportional focals and drawn 1:1 at top-left; uncovered Canvas bars are filled black outside native render timing',
            'hud': 'always two live lines: mode/FPS and N/R/I; no full diagnostic panel in measured frames',
            'm3g_compatibility': 'Target user-reported Nokia E65 M3G 1.0: only basic Texture2D filtering and PolygonMode shading switches already present in JSR-184; mip level filter is a hint and may be ignored by the implementation',
            'near': 128,
            'far': 60000,
            'configured_far': 68500,
            'fov': 74,
            'DMODE': 9,
            'world_scale': 6,
            'alpha': 'original thresholds in TEX modes; diagnostic 1X1 mode intentionally uses threshold 0',
            'depth': 'test/write ON except river write OFF, as parent',
            'perspective': 'ON fixed (previous handset result: OFF almost no effect)',
            'river': 'all 4 cached frames, speed6, mode7; in 1X1 mode the same geometry/pass samples the white texel instead',
            'pump': 'same worker repaint()+serviceRepaints()+sleep(1) in all modes',
            'warmup_ms': 1000,
            'measure_ms': 3000,
            'scope': 'world-only preview: no sky/weather/gameplay/collision changes',
            'stats_note': 'R is the Graphics3D.render loop inside N; Java API wall time, NOT GPU timestamps or proven LCD flips',
        },
        handset_tested=False,
        handset_fps_measured=False,
        purpose='Isolated river-map pixel-cost A/B: render scale, atlas vs 1x1 texture, smooth/flat shading, and base vs nearest-mip filtering.',
    )
    (out / (STEM + '.build-report.json')).write_text(json.dumps(report, indent=2) + '\n')
    jad = manifest.replace('Manifest-Version: 1.0\r\n', '').rstrip()
    jad += '\r\nMIDlet-Jar-URL: ' + jar.name + '\r\nMIDlet-Jar-Size: ' + str(jar.stat().st_size) + '\r\n'
    (out / (STEM + '.jad')).write_bytes(jad.encode('utf-8'))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
