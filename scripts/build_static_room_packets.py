#!/usr/bin/env python3
"""Build fixed-visibility large/compact vertex-buffer A/B; every Tex3 asset unchanged."""
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
STEM = 'Quantum_StaticRoom_Packets'
NEW_CLASSES = {
    'code/Rendering/StaticRoomPacketsMesh.class',
    'code/Rendering/StaticRoomPacketsMesh$NativeImage.class',
    'code/Rendering/StaticRoomPacketsMesh$Part.class',
    'code/Rendering/StaticRoomPacketsMesh$Masked.class',
    'code/Rendering/StaticRoomPacketsMesh$Pending.class',
    'code/Rendering/StaticRoomPacketsMesh$Packet.class',
    'code/Rendering/StaticRoomPacketsPreview.class',
    'code/Rendering/StaticRoomPacketsPreview$Viewer.class',
    'code/Rendering/StaticRoomPacketsStats.class',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=os.environ.get('JAVA', 'java'))
    parser.add_argument('--ecj-jar', default=os.environ.get('ECJ_JAR', 'build/tools/ecj.jar'))
    parser.add_argument('--api-dir', type=Path, default=ROOT / 'build/sdk/lib')
    parser.add_argument('--proguard-jar', default=str(ROOT / 'build/sdk/proguard/lib/proguard.jar'))
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'artifacts/static-room-packets')
    args = parser.parse_args()
    subprocess.run([sys.executable, str(ROOT / 'scripts/verify_render_checkpoints.py')], check=True)
    if hashlib.sha256(BASE.read_bytes()).hexdigest() != BASE_SHA:
        raise ValueError('Wrong Tex3 baseline')
    work = ROOT / 'build/static-room-packets'
    classes = work / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    for stale in classes.rglob('*.class'):
        stale.unlink()
    api = [(args.api_dir / name).resolve() for name in API_NAMES]
    sources = sorted((ROOT / 'experiments/static-room-packets/src').rglob('*.java'))
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
        'Manifest-Version: 1.0', 'MIDlet-Name: Quantum Static Room Packets', 'MIDlet-Version: 1.0.0',
        'MIDlet-Vendor: Quantum Experiments', 'MIDlet-1: Packets A-B,,code.Rendering.StaticRoomPacketsPreview',
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
        renderer_parent_source_sha256=hashlib.sha256((ROOT / 'experiments/static-room-bvh/src/code/Rendering/StaticRoomBvhMesh.java').read_bytes()).hexdigest(),
        diagnostic=dict(A='original full per-material buffers and original selected index stream',
                        B='contiguous spatial runs; <=512 corners and <=2x2 parent-grid centroid cells',
                        default='A LARGE', key0='A/B only; freeze motion, reset warmup/timing; no pose change', key5='original spawn/yaw0',
                        selection='same GRID selector and exactly same ordered face/strip stream in both modes',
                        packet_faces_never_split_or_reordered=True, buffer_coordinates_uv_rgba_scale_bias='exact parent slices, no requantization',
                        near=128, far=60000, fov=74, viewport='full Canvas; unchanged Image target and projection',
                        alpha='original per-image threshold, cutout ON; no disable control',
                        river='same four cached original frames/speed6/mode7/depth-write false; original order',
                        geometry_texture_uploads='cold only; both layouts resident, no geometry/index/texture allocation on A/B switch at warmed fixed masks',
                        visibility_masks='two-entry cache per parent, lazy compact index cache; no whole-room OOM substitution',
                        metrics='fractional FPS; N/C/I ms; T selected triangles; D calls; VB sum submitted buffer capacities; S selected corners; RES both resident layouts',
                        VB_is_not_measured_driver_vertex_processing=True, draw_calls_may_increase=True,
                        warmup_ms=1000, measure_ms=3000, first_completed_frame_anchor=True,
                        failure='optional compact build OOM disables B but preserves A; render failure releases target; explicit A recovery, never silently labelled B',
                        no_software_fallback=True, map_assets_changed=False),
        handset_tested=False, handset_fps_measured=False,
        purpose='Test vertex-buffer-capacity hypothesis on the same city geometry. More compact buffers trade extra calls for smaller submitted capacities; no FPS gain or full-VB driver processing claimed.')
    (out / (STEM + '.build-report.json')).write_text(json.dumps(report, indent=2) + '\n')
    jad = manifest.replace('Manifest-Version: 1.0\r\n', '').rstrip()
    jad += '\r\nMIDlet-Jar-URL: ' + jar.name + '\r\nMIDlet-Jar-Size: ' + str(jar.stat().st_size) + '\r\n'
    (out / (STEM + '.jad')).write_bytes(jad.encode('utf-8'))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
