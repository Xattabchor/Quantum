#!/usr/bin/env python3
"""Build isolated river-map geometry-cost diagnostic over the Perspective release.

Forks the published river pixel renderer (source lineage pinned by hash) and
adds exactly two geometry knobs (F far cycle, L screen-radius cell reject) on
top of the already handset-proven DIR/UP delivery stack. No geometry/texture
data changes; default F60000/L0 reproduces the parent stream pose by pose.
"""
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
PIXEL = ROOT / 'downloads/Quantum_RiverMap_Pixel.jar'
PIXEL_SHA = '2f3d602dc347cbd8cb7897859a1e8d3640e7982c731e617960a2ef401011de0c'
PIXEL_COMMIT = 'd7d1e67bd59b020957df1eaef78d128be8a39228'
UPSCALE = ROOT / 'downloads/Quantum_RiverMap_Upscale.jar'
UPSCALE_SHA = '00204419b4c068193e1aa2f9c96fb13f2884066b608a3424a617d6ced3f925d9'
UPSCALE_COMMIT = '4a298afa8b9b8d0ffdda5f7dab80bc38d9f884fb'
STEM = 'Quantum_RiverMap_Geo'
NEW_CLASSES = {
    'code/Rendering/RiverGeoMesh.class',
    'code/Rendering/RiverGeoMesh$NativeImage.class',
    'code/Rendering/RiverGeoMesh$Part.class',
    'code/Rendering/RiverGeoMesh$Masked.class',
    'code/Rendering/RiverGeoMesh$Pending.class',
    'code/Rendering/RiverGeoPreview.class',
    'code/Rendering/RiverGeoPreview$Viewer.class',
    'code/Rendering/RiverGeoStats.class',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=os.environ.get('JAVA', 'java'))
    parser.add_argument('--ecj-jar', default=os.environ.get('ECJ_JAR', 'build/tools/ecj.jar'))
    parser.add_argument('--api-dir', type=Path, default=ROOT / 'build/sdk/lib')
    parser.add_argument('--proguard-jar', default=str(ROOT / 'build/sdk/proguard/lib/proguard.jar'))
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'artifacts/river-geo')
    args = parser.parse_args()
    subprocess.run([sys.executable, str(ROOT / 'scripts/verify_render_checkpoints.py')], check=True)
    if hashlib.sha256(BASE.read_bytes()).hexdigest() != BASE_SHA:
        raise ValueError('Wrong immutable Perspective parent')
    if hashlib.sha256(PIXEL.read_bytes()).hexdigest() != PIXEL_SHA:
        raise ValueError('Wrong immutable Pixel lineage parent')
    if hashlib.sha256(UPSCALE.read_bytes()).hexdigest() != UPSCALE_SHA:
        raise ValueError('Wrong immutable Upscale lineage parent')

    work = ROOT / 'build/river-geo'
    classes = work / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    for stale in classes.rglob('*.class'):
        stale.unlink()
    api = [(args.api_dir / name).resolve() for name in API_NAMES]
    sources = sorted((ROOT / 'experiments/river-geo/src').rglob('*.java'))
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
        'MIDlet-Name: Quantum River Geo',
        'MIDlet-Version: 1.0.0',
        'MIDlet-Vendor: Quantum Experiments',
        'MIDlet-1: River Geo,,code.Rendering.RiverGeoPreview',
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
            info = zipfile.ZipInfo(name, (2026, 10, 21, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            result.writestr(info, entries[name], compresslevel=9)

    with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(PIXEL) as pixel, \
            zipfile.ZipFile(UPSCALE) as upscale, zipfile.ZipFile(jar) as result:
        assert result.testzip() is None
        assert set(result.namelist()) == set(base.namelist()) | NEW_CLASSES
        assert all(result.read(name) == base.read(name) for name in base.namelist() if name != 'META-INF/MANIFEST.MF')
        assert all(name not in result.namelist() for name in set(pixel.namelist()) - set(base.namelist()) - {'META-INF/MANIFEST.MF'}
                   if not name.startswith('code/Rendering/RiverPixel'))
        for name in (
            'code/Rendering/RiverPixelMesh.class',
            'code/Rendering/RiverPixelPreview.class',
            'code/Rendering/RiverUpscalePreview.class',
        ):
            assert name not in result.namelist()

    report = audit_jar(jar)
    report.update(
        base_jar=BASE.name,
        base_commit=BASE_COMMIT,
        base_sha256=BASE_SHA,
        pixel_lineage_jar=PIXEL.name,
        pixel_lineage_commit=PIXEL_COMMIT,
        pixel_lineage_sha256=PIXEL_SHA,
        upscale_lineage_jar=UPSCALE.name,
        upscale_lineage_commit=UPSCALE_COMMIT,
        upscale_lineage_sha256=UPSCALE_SHA,
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
            (ROOT / 'experiments/river-pixel/src/code/Rendering/RiverPixelMesh.java').read_bytes()
        ).hexdigest(),
        stats_parent_source_sha256=hashlib.sha256(
            (ROOT / 'experiments/river-pixel/src/code/Rendering/RiverPixelStats.java').read_bytes()
        ).hexdigest(),
        preview_parent_source_sha256=hashlib.sha256(
            (ROOT / 'experiments/river-upscale/src/code/Rendering/RiverUpscalePreview.java').read_bytes()
        ).hexdigest(),
        toolchain_note=(
            'Restored ephemeral toolchain (jdk4py 25.0.2.1, ECJ 3.16.0 from npm coc-java@1.0.0, '
            'api jars and ProGuard from vipaoL/j2me-build-tools). New classes bind inherited '
            'getWidth/getHeight to javax/microedition/lcdui/Displayable, which resolves identically '
            'on a real MIDP 2.0 device; pixel-linage classes are NOT repackaged in this jar.'
        ),
        diagnostic={
            'purpose': 'Price the fixed ~30 ms geometry/setup term at the measured S50+UP working point; test how much of it is reducible per-triangle via far distance and a screen-radius cell reject. No handset FPS claim.',
            'map': 'old sym9 village/river map from immutable Perspective/Tex3 parent: maps/kordos/kordos.3d, baked lightmap/kordod.vla, Tex3 slots forest/map-selo1/river',
            'geometry_data_changed': False,
            'textures_changed': False,
            'lightmap_changed': False,
            'renderer': 'source fork of the published RiverPixelMesh with two added knobs; defaults F60000/L0 reproduce the parent draw stream exactly (pinned by the 144-pose sweep test); stats fork is a re-type of RiverPixelStats',
            'delivery': 'identical DIR/UP stack from the published upscale diagnostic: DIR black bars at 1:1, UP Image.getRGB + integer nearest-neighbour expand + Graphics.drawRGB false-alpha; upscaleNN copied verbatim',
            'handset_model_prior': {
                'area_model': 'R ~= 30 ms fixed + ~74 ms x full-screen area, confirmed S100/S75/S50/S25',
                'upscale_price': 'full-screen delivery constant ~24-33 ms; S50 UP FPS 9.1 vs S100 DIR 7.0; S75 UP dead point; S25 UP ~12.8 at 4x grain',
                'why_now': 'at S50 the fixed ~30 ms term is ~60% of R; per-triangle reduction should show up directly, unlike at S100 where fill dominated',
            },
            'default': 'S100 DIR F60K L0 B (full viewport, bars delivery, far 60000, radius limit off, base-level filtering)',
            'controls': {
                'key1': 'BASE reset: S100 + DIR + F60K + L0 + BASE_LEVEL',
                'key0': 'cycle render scale 100 -> 75 -> 50 -> 25 percent (other toggles preserved)',
                'key3': 'toggle delivery DIR <-> UP (identical to upscale diagnostic)',
                'key7': 'cycle far 60000 -> 40000 -> 20000 (projection + frustum distance; river/fog untouched)',
                'key9': 'cycle screen-radius cell reject L 0 -> 8 -> 16 render-viewport pixels; whole GRID cells whose full-bounds sphere projects below L are dropped, camera-inside cells never cut',
                'star': 'toggle texture level filter BASE_LEVEL <-> NEAREST nearest mip level (hint, may be ignored)',
                'key5': 'home; toggles preserved',
                'movement': '2/8 move, 4/6 turn',
            },
            'line1': 'S{scale} {DIR|UP} F{60K|40K|20K} L{0|8|16} {B|M}; warming or FPS',
            'line2': 'T submitted triangles (last publish) + N/R/C/I; T is the geometry-knob witness',
            'm3g_compatibility': 'Target user-reported Nokia E65 M3G 1.0: same JSR-184 1.0 entry points as the pixel/upscale diagnostics',
            'near': 128,
            'far': 60000,
            'far_cycle': [60000, 40000, 20000],
            'configured_far': 68500,
            'radius_limits_render_px': [0, 8, 16],
            'fov': 74,
            'DMODE': 9,
            'world_scale': 6,
            'alpha': 'original thresholds in TEX modes, as parent',
            'depth': 'test/write ON except river write OFF, as parent',
            'perspective': 'ON fixed',
            'river': 'all 4 cached frames, speed6, mode7, as parent',
            'pump': 'same worker repaint()+serviceRepaints()+sleep(1) in all modes, as parent',
            'warmup_ms': 1000,
            'measure_ms': 3000,
            'scope': 'world-only preview: no sky/weather/gameplay/collision changes',
            'stats_note': 'T/N/R/C/I are Java API wall time / submission counts, NOT GPU timestamps or proven LCD flips',
        },
        handset_tested=False,
        handset_fps_measured=False,
        purpose='Isolated river-map geometry-cost A/B at the S50+UP working point: far cycle and screen-radius cell reject over the handset-proven delivery stack.',
    )
    (out / (STEM + '.build-report.json')).write_text(json.dumps(report, indent=2) + '\n')
    jad = manifest.replace('Manifest-Version: 1.0\r\n', '').rstrip()
    jad += '\r\nMIDlet-Jar-URL: ' + jar.name + '\r\nMIDlet-Jar-Size: ' + str(jar.stat().st_size) + '\r\n'
    (out / (STEM + '.jad')).write_bytes(jad.encode('utf-8'))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
