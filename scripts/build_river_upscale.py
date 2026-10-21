#!/usr/bin/env python3
"""Build isolated river-map upscale-delivery diagnostic over the Pixel parent.

Reuses the published Quantum_RiverMap_Pixel renderer classes byte-for-byte
(Quantum_StaticRoom_Perspective remains the immutable binary root); compiles
only the new RiverUpscalePreview classes. No geometry/texture changes.
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
STEM = 'Quantum_RiverMap_Upscale'
REUSED_CLASSES = {
    'code/Rendering/RiverPixelMesh.class',
    'code/Rendering/RiverPixelMesh$NativeImage.class',
    'code/Rendering/RiverPixelMesh$Part.class',
    'code/Rendering/RiverPixelMesh$Masked.class',
    'code/Rendering/RiverPixelMesh$Pending.class',
    'code/Rendering/RiverPixelStats.class',
}
NEW_CLASSES = {
    'code/Rendering/RiverUpscalePreview.class',
    'code/Rendering/RiverUpscalePreview$Viewer.class',
}
ADDED_CLASSES = REUSED_CLASSES | NEW_CLASSES
EXCLUDED_PIXEL_CLASSES = {
    'code/Rendering/RiverPixelPreview.class',
    'code/Rendering/RiverPixelPreview$Viewer.class',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=os.environ.get('JAVA', 'java'))
    parser.add_argument('--ecj-jar', default=os.environ.get('ECJ_JAR', 'build/tools/ecj.jar'))
    parser.add_argument('--api-dir', type=Path, default=ROOT / 'build/sdk/lib')
    parser.add_argument('--proguard-jar', default=str(ROOT / 'build/sdk/proguard/lib/proguard.jar'))
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'artifacts/river-upscale')
    args = parser.parse_args()
    subprocess.run([sys.executable, str(ROOT / 'scripts/verify_render_checkpoints.py')], check=True)
    if hashlib.sha256(BASE.read_bytes()).hexdigest() != BASE_SHA:
        raise ValueError('Wrong immutable Perspective parent')
    if hashlib.sha256(PIXEL.read_bytes()).hexdigest() != PIXEL_SHA:
        raise ValueError('Wrong immutable Pixel parent')

    work = ROOT / 'build/river-upscale'
    classes = work / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    for stale in classes.rglob('*.class'):
        stale.unlink()
    api = [(args.api_dir / name).resolve() for name in API_NAMES]
    sources = sorted((ROOT / 'experiments/river-upscale/src').rglob('*.java'))
    dependencies = os.pathsep.join([str(PIXEL), str(BASE)])
    subprocess.run([
        args.java, '-jar', args.ecj_jar, '-source', '1.3', '-target', '1.3',
        '-encoding', 'UTF-8', '-proc:none', '-nowarn',
        '-bootclasspath', os.pathsep.join(map(str, api)),
        '-classpath', dependencies, '-d', str(classes),
    ] + list(map(str, sources)), check=True)

    raw, verified = work / 'unverified.jar', work / 'verified.jar'
    with zipfile.ZipFile(raw, 'w', zipfile.ZIP_DEFLATED) as target:
        for path in sorted(classes.rglob('*.class')):
            target.write(path, path.relative_to(classes).as_posix())
    command = [args.java, '-jar', args.proguard_jar, '-injars', str(raw), '-outjars', str(verified),
               '-libraryjars', str(BASE), '-libraryjars', str(PIXEL)]
    for path in api:
        command += ['-libraryjars', str(path)]
    subprocess.run(command + ['-microedition', '-dontshrink', '-dontoptimize', '-dontobfuscate', '-dontnote', '-dontwarn'], check=True)

    out = args.output_dir.resolve()
    out.mkdir(parents=True, exist_ok=True)
    manifest = '\r\n'.join([
        'Manifest-Version: 1.0',
        'MIDlet-Name: Quantum River Upscale',
        'MIDlet-Version: 1.0.0',
        'MIDlet-Vendor: Quantum Experiments',
        'MIDlet-1: River Upscale,,code.Rendering.RiverUpscalePreview',
        'MicroEdition-Configuration: CLDC-1.1',
        'MicroEdition-Profile: MIDP-2.0',
        '',
        '',
    ])
    jar = out / (STEM + '.jar')
    with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(PIXEL) as pixel, \
            zipfile.ZipFile(verified) as patch, zipfile.ZipFile(jar, 'w') as result:
        compiled = {name for name in patch.namelist() if name.endswith('.class')}
        assert compiled == NEW_CLASSES and not compiled.intersection(base.namelist())
        assert not compiled.intersection(pixel.namelist())
        assert REUSED_CLASSES.issubset(set(pixel.namelist()))
        assert REUSED_CLASSES.isdisjoint(set(base.namelist()))
        assert REUSED_CLASSES.isdisjoint(EXCLUDED_PIXEL_CLASSES)
        entries = {name: base.read(name) for name in base.namelist() if name != 'META-INF/MANIFEST.MF'}
        entries.update({name: pixel.read(name) for name in sorted(REUSED_CLASSES)})
        entries.update({name: patch.read(name) for name in compiled})
        entries['META-INF/MANIFEST.MF'] = manifest.encode('utf-8')
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, (2026, 10, 21, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            result.writestr(info, entries[name], compresslevel=9)

    with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(PIXEL) as pixel, zipfile.ZipFile(jar) as result:
        assert result.testzip() is None
        assert set(result.namelist()) == set(base.namelist()) | ADDED_CLASSES
        assert all(result.read(name) == base.read(name) for name in base.namelist() if name != 'META-INF/MANIFEST.MF')
        assert all(result.read(name) == pixel.read(name) for name in REUSED_CLASSES)
        for name in EXCLUDED_PIXEL_CLASSES:
            assert name not in result.namelist()

    report = audit_jar(jar)
    report.update(
        base_jar=BASE.name,
        base_commit=BASE_COMMIT,
        base_sha256=BASE_SHA,
        pixel_parent_jar=PIXEL.name,
        pixel_parent_commit=PIXEL_COMMIT,
        pixel_parent_sha256=PIXEL_SHA,
        jar_bytes=jar.stat().st_size,
        jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
        all_parent_classes_and_all_assets_unchanged_except_manifest=True,
        reused_pixel_classes_byte_identical=True,
        reused_pixel_classes=sorted(REUSED_CLASSES),
        excluded_pixel_classes=sorted(EXCLUDED_PIXEL_CLASSES),
        added_classes=sorted(ADDED_CLASSES),
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
        toolchain_note=(
            'Restored ephemeral toolchain (jdk4py 25.0.2.1, ECJ 3.16.0 from npm coc-java@1.0.0, '
            'api jars and ProGuard from vipaoL/j2me-build-tools). Rebuilding Quantum_RiverMap_Pixel '
            'from unchanged sources is byte-identical for 209/210 entries; only the compiled '
            'RiverPixelPreview$Viewer differs: the original toolchain bound inherited '
            'getWidth/getHeight calls to the compile-time subclass (Viewer), while the restored ECJ '
            'binds them to the declaring supertype javax/microedition/lcdui/Displayable. Both '
            'references resolve identically on a real MIDP 2.0 device. This build therefore reuses '
            'the six published RiverPixel renderer/stats classes byte-for-byte instead of '
            'recompiling them, and no RiverPixelPreview classes are packaged.'
        ),
        diagnostic={
            'purpose': 'Price nearest-neighbour upscale delivery (C) of a reduced viewport against the fill it saves (R). No handset FPS claim.',
            'map': 'old sym9 village/river map from immutable Perspective/Tex3 parent: maps/kordos/kordos.3d, baked lightmap/kordod.vla, Tex3 slots forest/map-selo1/river',
            'geometry_changed': False,
            'textures_changed': False,
            'lightmap_changed': False,
            'renderer': 'byte-identical published RiverPixelMesh/Stats classes; verifier compares jar entries against Quantum_RiverMap_Pixel',
            'handset_model_prior': {
                'area_model': 'R ~= 30 ms fixed + ~74 ms x full-screen area, confirmed at S100/S75/S50/S25; S50 R48-51, S25 R32',
                'fill_split_fullscreen': 'texture ~20 ms, smooth colour interpolation ~16 ms, depth+iterate+write ~42 ms',
                'farm_pump_copy_baseline': 'full-screen Image drawImage copy was small (~1 ms) in FramePump; Java NN upscale expand cost unknown on E65',
            },
            'default': 'S100 DIR TEX SM B (full viewport, 1:1 delivery, atlas textures, smooth shading, base-level filtering)',
            'controls': {
                'key1': 'BASE reset: S100 + DIR + TEX + SMOOTH + BASE_LEVEL',
                'key0': 'cycle render scale 100 -> 75 -> 50 -> 25 percent (other toggles preserved)',
                'key3': 'toggle delivery DIR <-> UP: black-bar 1:1 image copy vs nearest upscale to full screen',
                'key7': 'toggle atlas textures <-> 1x1 white texture (pixel diagnostic inheritance)',
                'key9': 'toggle PolygonMode SMOOTH <-> FLAT shading',
                'star': 'toggle texture level filter BASE_LEVEL <-> NEAREST (nearest mip level; a hint, may be ignored)',
                'key5': 'home; scale/delivery/texture/shading/filter preserved',
                'movement': '2/8 move, 4/6 turn; no strafe because 7/9 are pixel toggles',
            },
            'delivery': {
                'dir': 'native Image rendered at scaled viewport size, drawn 1:1 top-left; uncovered Canvas bars filled black outside native render timing (identical to river pixel diagnostic)',
                'up': 'same offscreen native Image, then Image.getRGB + integer nearest-neighbour expand + Graphics.drawRGB false-alpha over the whole Canvas; at S100 UP intentionally degrades to the same 1:1 drawImage as DIR (pipeline control)',
                'upscale_algorithm': 'dst(x,y)=src(floor(x*sw/dw), floor(y*sh/dh)); same-source rows reused via arraycopy; buffers allocated once per scale change, not per frame',
                'hud': 'drawn after delivery so it is never upscaled; two live lines: mode/FPS and N/R/C/I where C is the measured delivery window getRGB+expand+draw (or DIR bar+copy)',
            },
            'm3g_compatibility': 'Target user-reported Nokia E65 M3G 1.0: renderer unchanged from the pixel diagnostic; upscale uses only MIDP 2.0 Graphics/Image entry points already exercised by the game',
            'near': 128,
            'far': 60000,
            'configured_far': 68500,
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
            'stats_note': 'N/R/C/I are Java API wall time, NOT GPU timestamps or proven LCD flips; C is not free even when it reads 0.0 because lower timer resolution may hide sub-ms work',
        },
        handset_tested=False,
        handset_fps_measured=False,
        purpose='Isolated river-map upscale-delivery A/B: DIR black bars vs UP nearest full-screen expansion over the existing pixel-cost renderer.',
    )
    (out / (STEM + '.build-report.json')).write_text(json.dumps(report, indent=2) + '\n')
    jad = manifest.replace('Manifest-Version: 1.0\r\n', '').rstrip()
    jad += '\r\nMIDlet-Jar-URL: ' + jar.name + '\r\nMIDlet-Jar-Size: ' + str(jar.stat().st_size) + '\r\n'
    (out / (STEM + '.jad')).write_bytes(jad.encode('utf-8'))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
