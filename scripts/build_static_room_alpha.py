#!/usr/bin/env python3
"""Build isolated alpha-test OFF/ON diagnostic on immutable Depth baseline.
All 172 baseline classes and 10 assets retained byte-for-byte; no model LOD.
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
BASE = ROOT / 'downloads/Quantum_StaticRoom_Depth.jar'
BASE_SHA = '053252da302fa8e9c8661617e6b4985b20887c57af942fb8e10939a8f0079316'
STEM = 'Quantum_StaticRoom_Alpha'
NEW_CLASSES = {
    'code/Rendering/StaticRoomAlphaPreview.class',
    'code/Rendering/StaticRoomAlphaPreview$Viewer.class',
    'code/Rendering/StaticRoomAlphaMesh.class',
    'code/Rendering/StaticRoomAlphaMesh$Part.class',
    'code/Rendering/StaticRoomAlphaMesh$Pending.class',
    'code/Rendering/StaticRoomAlphaMesh$NativeImage.class',
    'code/Rendering/StaticRoomAlphaMesh$Masked.class',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=os.environ.get('JAVA', 'java'))
    parser.add_argument('--ecj-jar', default=os.environ.get('ECJ_JAR', 'build/tools/ecj.jar'))
    parser.add_argument('--api-dir', type=Path, default=ROOT / 'build/sdk/lib')
    parser.add_argument('--proguard-jar', default=str(ROOT / 'build/sdk/proguard/lib/proguard.jar'))
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'artifacts/static-room-alpha')
    args = parser.parse_args()
    subprocess.run([sys.executable, str(ROOT / 'scripts/verify_render_checkpoints.py')], check=True)
    if hashlib.sha256(BASE.read_bytes()).hexdigest() != BASE_SHA:
        raise ValueError('Wrong Depth baseline')
    work = ROOT / 'build/static-room-alpha'
    classes = work / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    for stale in classes.rglob('*.class'):
        stale.unlink()
    api = [(args.api_dir / name).resolve() for name in API_NAMES]
    sources = sorted((ROOT / 'experiments/static-room-alpha/src').rglob('*.java'))
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
        'Manifest-Version: 1.0', 'MIDlet-Name: Quantum Static Room Alpha', 'MIDlet-Version: 1.0.0',
        'MIDlet-Vendor: Quantum Experiments', 'MIDlet-1: Alpha A-B,,code.Rendering.StaticRoomAlphaPreview',
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
        base_jar=BASE.name, base_commit='1cc9663e22a7aa3753b780570fb34f839b3208ea', base_sha256=BASE_SHA,
        jar_bytes=jar.stat().st_size, jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
        all_baseline_class_bytes_and_10_assets_unchanged=True,
        old_native_mesh_and_visibility_class_bytes_retained_unchanged=True,
        added_classes=sorted(NEW_CLASSES),
        source_sha256={str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},
        diagnostic=dict(A='alpha test OFF (threshold0)', B='alpha test ON (original per-image threshold)',
                        key0='toggle alpha discard only; resets timing window', default_alpha_test=False,
                        fixed_near=128, fixed_far=60000, near_far_keys_disabled=True,
                        original_config_DIST_bytes_unchanged=True, target='same offscreen Image', culling=True,
                        strips='original, not joined', native_render_calls_maximum=3,
                        uploaded_vertices=10032, full_source_triangles=4750,
                        texture_RGBA_bytes_blending_depth_states_unchanged=True,
                        caution='Cutout rectangles can fill/write depth when discard is OFF; visible surfaces and overdraw change.',
                        hypothesis='Alpha discard may be costly; threshold0 does not prove a particular E65 driver fast path.'),
        handset_tested=False, handset_fps_measured=False,
        purpose='Alpha discard A/B only. Any FPS change also includes changed cutout occlusion/overdraw, not isolated GPU instruction cost.')
    (out / (STEM + '.build-report.json')).write_text(json.dumps(report, indent=2) + '\n')
    jad = manifest.replace('Manifest-Version: 1.0\r\n', '').rstrip()
    jad += '\r\nMIDlet-Jar-URL: ' + jar.name + '\r\nMIDlet-Jar-Size: ' + str(jar.stat().st_size) + '\r\n'
    (out / (STEM + '.jad')).write_bytes(jad.encode('utf-8'))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
