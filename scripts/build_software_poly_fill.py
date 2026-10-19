#!/usr/bin/env python3
"""Build rollback-safe Quantum SOFTWARE polygon/fillrate benchmark; original map untouched."""
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
STEM = 'Quantum_SoftwarePolyFill_Test'
NEW_CLASSES = {
    'code/Rendering/SoftwarePolyFillMesh.class',
    'code/Rendering/SoftwarePolyFillWindow.class',
    'code/Rendering/SoftwarePolyFillWindow$Result.class',
    'code/Rendering/SoftwarePolyFillPreview.class',
    'code/Rendering/SoftwarePolyFillPreview$Viewer.class',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=os.environ.get('JAVA', 'java'))
    parser.add_argument('--ecj-jar', default=os.environ.get('ECJ_JAR', 'build/tools/ecj.jar'))
    parser.add_argument('--api-dir', type=Path, default=ROOT / 'build/sdk/lib')
    parser.add_argument('--proguard-jar', default=str(ROOT / 'build/sdk/proguard/lib/proguard.jar'))
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'artifacts/software-poly-fill')
    args = parser.parse_args()
    subprocess.run([sys.executable, str(ROOT / 'scripts/verify_render_checkpoints.py')], check=True)
    if hashlib.sha256(BASE.read_bytes()).hexdigest() != BASE_SHA:
        raise ValueError('Wrong Tex3 baseline')
    work = ROOT / 'build/software-poly-fill'
    classes = work / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    for stale in classes.rglob('*.class'):
        stale.unlink()
    api = [(args.api_dir / name).resolve() for name in API_NAMES]
    sources = sorted((ROOT / 'experiments/software-poly-fill/src').rglob('*.java'))
    subprocess.run([args.java, '-jar', args.ecj_jar, '-source', '1.3', '-target', '1.3',
                    '-encoding', 'UTF-8', '-proc:none', '-nowarn', '-bootclasspath', os.pathsep.join(map(str, api)),
                    '-classpath', str(BASE), '-d', str(classes)] + list(map(str, sources)), check=True)
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
        'Manifest-Version: 1.0', 'MIDlet-Name: Quantum Software PolyFill Benchmark', 'MIDlet-Version: 1.0.0',
        'MIDlet-Vendor: Quantum Experiments', 'MIDlet-1: SoftwarePolyFill,,code.Rendering.SoftwarePolyFillPreview',
        'MicroEdition-Configuration: CLDC-1.1', 'MicroEdition-Profile: MIDP-2.0', '', ''])
    jar = out / (STEM + '.jar')
    with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(verified) as patch, zipfile.ZipFile(jar, 'w') as result:
        added = {name for name in patch.namelist() if name.endswith('.class')}
        assert added == NEW_CLASSES and not added.intersection(base.namelist())
        entries = {name: base.read(name) for name in base.namelist() if name != 'META-INF/MANIFEST.MF'}
        entries.update({name: patch.read(name) for name in added})
        entries['META-INF/MANIFEST.MF'] = manifest.encode('utf-8')
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, (2026, 10, 19, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            result.writestr(info, entries[name], compresslevel=9)
    with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(jar) as result:
        assert result.testzip() is None
        assert set(result.namelist()) == set(base.namelist()) | NEW_CLASSES
        assert all(result.read(name) == base.read(name) for name in base.namelist() if name != 'META-INF/MANIFEST.MF')
    report = audit_jar(jar)
    report.update(
        base_jar=BASE.name, base_commit='b9b477f3de65e2a2ecc2e0f88ad10a09244f22fa', base_sha256=BASE_SHA,
        jar_bytes=jar.stat().st_size, jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
        all_182_baseline_classes_and_all_map_assets_unchanged=True,
        old_native_mesh_and_visibility_class_bytes_retained_unchanged=True,
        added_classes=sorted(NEW_CLASSES),
        source_sha256={str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},
        builder_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        benchmark=dict(geometry_grids=[8,16,24,32,48,64,96,120],
                       geometry_triangles=[128,512,1152,2048,4608,8192,18432,28800],
                       fill_layers=[1,2,4,8,16,32], backend='explicit DirectX7.SOFTWARE + renderSoftware, frozen Tex3 CPU classes',
                       pipeline='clearDisplay -> transform/project shared vertices -> visibility/enqueue -> painter sort -> LightedPolygon3V -> TexturingPers/Affine -> flush/drawRGB',
                       texture='same opaque procedural64x64 checker, drawmode9, no mipmaps',
                       shading='P4 la221 becomes220 via original getLight255/256; P0 renderFast receives220 directly; CPU sampler rounding differs from M3G',
                       default_persQ=4, optional_persQ=0, q=24, fogQ=2, fDist=1,
                       depth='painter: NO Z buffer; native POLY has depth ON; FILL native Z OFF',
                       alpha='opaque texels only; CPU nonzero sampling, not native threshold0.5',
                       projection='constant-depth full rectangle, integer screen grid; native subpixel/strip representation differs',
                       viewport='canvas width x (canvas height - 90), HUD excluded',
                       warmup_ms=1000, measure_ms=3000, auto_profiles=14,
                       metrics='fractional FPS, R/software pipeline ms, C/drawRGB ms, I/frame interval; end-to-end Ktri/s or nominal Mpix/s',
                       no_m3g_calls=True, no_nokia_directgraphics=True,
                       cold_only_geometry=True, queue_prewarmed=True, builds_and_pause_excluded=True,
                       small_resolution='grid > min(width,height) marked ERR, never count collapsed triangles',
                       failure_policy='manual lower/retry; auto OOM/ERR and skip, no alternative backend',
                       results='in-memory table/stdout, cleared on resize or P4/P0 toggle; not persisted after exit'),
        handset_tested=False, handset_fps_measured=False,
        purpose='Actual original Quantum software pipeline throughput, same nominal workloads as native PolyFill; NOT pixel-identical depth-equivalent rendering or a game FPS prediction.')
    (out / (STEM + '.build-report.json')).write_text(json.dumps(report, indent=2) + '\n')
    jad = manifest.replace('Manifest-Version: 1.0\r\n', '').rstrip()
    jad += '\r\nMIDlet-Jar-URL: ' + jar.name + '\r\nMIDlet-Jar-Size: ' + str(jar.stat().st_size) + '\r\n'
    (out / (STEM + '.jad')).write_bytes(jad.encode('utf-8'))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
