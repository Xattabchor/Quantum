#!/usr/bin/env python3
"""Build separate three-slot room preview; river pixels/animation and lightmap unchanged."""
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
from merge_room_texture_slots import merge_config, merge_model, MODEL_ENTRY, ORDER_ENTRY

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'downloads/Quantum_StaticRoom_Bvh.jar'
BASE_SHA = '1eecd62d8a01917eff0d7b5354f15f44fd6199351ef8ce5d5937c2a1ba4f93aa'
STEM = 'Quantum_StaticRoom_Tex3'
NEW_CLASSES = {
    'code/Rendering/StaticRoomTexturesPreview.class',
    'code/Rendering/StaticRoomTexturesPreview$Viewer.class',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=os.environ.get('JAVA', 'java'))
    parser.add_argument('--ecj-jar', default=os.environ.get('ECJ_JAR', 'build/tools/ecj.jar'))
    parser.add_argument('--api-dir', type=Path, default=ROOT / 'build/sdk/lib')
    parser.add_argument('--proguard-jar', default=str(ROOT / 'build/sdk/proguard/lib/proguard.jar'))
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'artifacts/static-room-textures')
    args = parser.parse_args()
    subprocess.run([sys.executable, str(ROOT / 'scripts/verify_render_checkpoints.py')], check=True)
    if hashlib.sha256(BASE.read_bytes()).hexdigest() != BASE_SHA:
        raise ValueError('Wrong BVH baseline')
    work = ROOT / 'build/static-room-textures'
    classes = work / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    for stale in classes.rglob('*.class'):
        stale.unlink()
    api = [(args.api_dir / name).resolve() for name in API_NAMES]
    sources = sorted((ROOT / 'experiments/static-room-textures/src').rglob('*.java'))
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
        'Manifest-Version: 1.0', 'MIDlet-Name: Quantum Static Room Tex3', 'MIDlet-Version: 1.0.0',
        'MIDlet-Vendor: Quantum Experiments', 'MIDlet-1: Tex3,,code.Rendering.StaticRoomTexturesPreview',
        'MicroEdition-Configuration: CLDC-1.1', 'MicroEdition-Profile: MIDP-2.0', '', ''])
    jar = out / (STEM + '.jar')
    with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(verified) as patch, zipfile.ZipFile(jar, 'w') as result:
        added = {name for name in patch.namelist() if name.endswith('.class')}
        assert added == NEW_CLASSES and not added.intersection(base.namelist())
        entries = {name: base.read(name) for name in base.namelist() if name != 'META-INF/MANIFEST.MF'}
        entries.update({name: patch.read(name) for name in added})
        model, order, material_report = merge_model(base.read(MODEL_ENTRY))
        entries[MODEL_ENTRY] = model
        entries['level1.txt'] = merge_config(base.read('level1.txt'))
        entries[ORDER_ENTRY] = order
        entries['META-INF/MANIFEST.MF'] = manifest.encode('utf-8')
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, (2026, 10, 19, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            result.writestr(info, entries[name], compresslevel=9)
    with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(jar) as result:
        assert result.testzip() is None
        assert set(result.namelist()) == set(base.namelist()) | NEW_CLASSES | {ORDER_ENTRY}
        assert all(result.read(name) == base.read(name) for name in base.namelist() if name not in ('META-INF/MANIFEST.MF', 'level1.txt', MODEL_ENTRY))
    report = audit_jar(jar)
    report.update(
        base_jar=BASE.name, base_commit='b8ef81fcda64ca155ee72dc4edb6bcf932f4e4b9', base_sha256=BASE_SHA,
        jar_bytes=jar.stat().st_size, jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
        all_180_baseline_class_bytes_unchanged=True,
        modified_assets=['level1.txt', MODEL_ENTRY], added_asset=ORDER_ENTRY,
        material_remap=material_report,
        all_texture_pixels_and_lightmap_bytes_unchanged=True,
        artifact_resource_sha256={name: hashlib.sha256(data).hexdigest() for name,data in entries.items() if not name.endswith('.class')},
        old_native_mesh_and_visibility_class_bytes_retained_unchanged=True,
        added_classes=sorted(NEW_CLASSES),
        transform_source_sha256={name: hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in ('scripts/build_static_room_textures.py','scripts/merge_room_texture_slots.py')},
        source_sha256={str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},
        diagnostic=dict(default_mode='B GRID (based on user BVH/grid comparison)', key0='A BVH / B GRID',
                        old_slots=5, new_slots=3, forest_slot=0, atlas_slot=1, river_old_slot=4, river_new_slot=2,
                        river_WTEX_line_images_speed_mode_unchanged=True,
                        fixed_near=128, fixed_far=60000, alpha_discard_original_thresholds_always=True,
                        native_render_calls_maximum=3, uploaded_vertices=10032, full_source_triangles=4750,
                        native_submission_order_preserved_by_startup_delta_permutation=True,
                        no_frame_geometry_or_texture_uploads=True,
                        note='PNG was already stored once and native atlas batches already merged; no drawcall/FPS reduction promised.'),
        handset_tested=False, handset_fps_measured=False,
        purpose='Authorized 3 atlas references -> 1. Three total material slots, river unchanged except slot renumbering. Separate rollback-safe preview.')
    (out / (STEM + '.build-report.json')).write_text(json.dumps(report, indent=2) + '\n')
    jad = manifest.replace('Manifest-Version: 1.0\r\n', '').rstrip()
    jad += '\r\nMIDlet-Jar-URL: ' + jar.name + '\r\nMIDlet-Jar-Size: ' + str(jar.stat().st_size) + '\r\n'
    (out / (STEM + '.jad')).write_bytes(jad.encode('utf-8'))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
