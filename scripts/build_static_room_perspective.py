#!/usr/bin/env python3
"""Build isolated M3G map perspective hint ON/OFF; published builds unchanged."""
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
STEM = 'Quantum_StaticRoom_Perspective'
NEW_CLASSES = {
    'code/Rendering/StaticRoomPerspectiveMesh.class',
    'code/Rendering/StaticRoomPerspectiveMesh$NativeImage.class',
    'code/Rendering/StaticRoomPerspectiveMesh$Part.class',
    'code/Rendering/StaticRoomPerspectiveMesh$Masked.class',
    'code/Rendering/StaticRoomPerspectiveMesh$Pending.class',
    'code/Rendering/StaticRoomPerspectivePreview.class',
    'code/Rendering/StaticRoomPerspectivePreview$Viewer.class',
    'code/Rendering/StaticRoomPerspectiveStats.class',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=os.environ.get('JAVA', 'java'))
    parser.add_argument('--ecj-jar', default=os.environ.get('ECJ_JAR', 'build/tools/ecj.jar'))
    parser.add_argument('--api-dir', type=Path, default=ROOT / 'build/sdk/lib')
    parser.add_argument('--proguard-jar', default=str(ROOT / 'build/sdk/proguard/lib/proguard.jar'))
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'artifacts/static-room-perspective')
    args = parser.parse_args()
    subprocess.run([sys.executable, str(ROOT / 'scripts/verify_render_checkpoints.py')], check=True)
    if hashlib.sha256(BASE.read_bytes()).hexdigest() != BASE_SHA:
        raise ValueError('Wrong Tex3 baseline')
    work = ROOT / 'build/static-room-perspective'
    classes = work / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    for stale in classes.rglob('*.class'):
        stale.unlink()
    api = [(args.api_dir / name).resolve() for name in API_NAMES]
    sources = sorted((ROOT / 'experiments/static-room-perspective/src').rglob('*.java'))
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
        'Manifest-Version: 1.0', 'MIDlet-Name: Quantum Static Room Perspective', 'MIDlet-Version: 1.0.0',
        'MIDlet-Vendor: Quantum Experiments', 'MIDlet-1: Perspective A-B,,code.Rendering.StaticRoomPerspectivePreview',
        'MicroEdition-Configuration: CLDC-1.1', 'MicroEdition-Profile: MIDP-2.0', '', ''])
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
        base_jar=BASE.name, base_commit='b9b477f3de65e2a2ecc2e0f88ad10a09244f22fa', base_sha256=BASE_SHA,
        jar_bytes=jar.stat().st_size, jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
        all_182_baseline_classes_and_all_map_assets_unchanged=True,
        old_native_mesh_and_visibility_class_bytes_retained_unchanged=True,
        added_classes=sorted(NEW_CLASSES),
        source_sha256={str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},
        builder_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        renderer_parent_source_sha256=hashlib.sha256((ROOT / 'experiments/static-room-chunks/src/code/Rendering/StaticRoomChunksMesh.java').read_bytes()).hexdigest(),
        diagnostic=dict(default='PERS ON', modes=['ON','OFF'],
                        key0='toggle hint, freeze movement, preserve pose',key1='ON',key3='OFF',key5='spawn/yaw0',
                        selection='unchanged legacy GRID4x4 in both modes; original large VBs/call groups and exact strip order',
                        changed_state='PolygonMode.setPerspectiveCorrectionEnable only; OFF may be ignored by implementation',
                        cold_only=True, mask_cache_entries=2,
                        near=128,far=60000,fov=74,viewport='same full Canvas/Image target',
                        alpha='original thresholds ON',river='all4 cached frames/speed6/mode7/order unchanged',
                        warmup_ms=1000,measure_ms=3000,fractional_fps=True,
                        native_stages='J prepare, B bind, K camera/state/clear, R render loop, E release; API wall time, NOT GPU timestamps',
                        counters='T/DC/VB/S/V/CUT and N/C/I',VB_is_not_driver_processing_counter=True,
                        no_software_fallback=True,failure='explicit error, no silent whole-room fallback',map_assets_changed=False),
        handset_tested=False, handset_fps_measured=False,
        purpose='Controlled perspective-correction hint ON/OFF on actual map, not a speedup claim. Same geometry/state except hint; device may ignore it.')
    (out / (STEM + '.build-report.json')).write_text(json.dumps(report, indent=2) + '\n')
    jad = manifest.replace('Manifest-Version: 1.0\r\n', '').rstrip()
    jad += '\r\nMIDlet-Jar-URL: ' + jar.name + '\r\nMIDlet-Jar-Size: ' + str(jar.stat().st_size) + '\r\n'
    (out / (STEM + '.jad')).write_bytes(jad.encode('utf-8'))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
