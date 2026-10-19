#!/usr/bin/env python3
"""Build actual L2D renderer/map import versus frozen Quantum FULL-scene control."""
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
STEM = 'Quantum_L2D_Map'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=os.environ.get('JAVA', 'java'))
    parser.add_argument('--ecj-jar', default=os.environ.get('ECJ_JAR', 'build/tools/ecj.jar'))
    parser.add_argument('--api-dir', type=Path, default=ROOT / 'build/sdk/lib')
    parser.add_argument('--proguard-jar', default=str(ROOT / 'build/sdk/proguard/lib/proguard.jar'))
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'artifacts/l2d-map')
    args = parser.parse_args()
    subprocess.run([sys.executable, str(ROOT / 'scripts/verify_render_checkpoints.py')], check=True)
    if hashlib.sha256(BASE.read_bytes()).hexdigest() != BASE_SHA:
        raise ValueError('Wrong Tex3 baseline')
    work = ROOT / 'build/l2d-map'
    classes = work / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    for stale in classes.rglob('*.class'):
        stale.unlink()
    api = [(args.api_dir / name).resolve() for name in API_NAMES]
    sources = sorted((ROOT / 'experiments/l2d-map/src').rglob('*.java')) + sorted((ROOT / 'experiments/l2d-map/upstream/src').rglob('*.java'))
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
        'Manifest-Version: 1.0', 'MIDlet-Name: Quantum L2D Map', 'MIDlet-Version: 1.0.0',
        'MIDlet-Vendor: Quantum Experiments', 'MIDlet-1: Map L2D vs Q,,code.Rendering.L2DMapPreview',
        'MicroEdition-Configuration: CLDC-1.1', 'MicroEdition-Profile: MIDP-2.0', '', ''])
    jar = out / (STEM + '.jar')
    with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(verified) as patch, zipfile.ZipFile(jar, 'w') as result:
        added = {name for name in patch.namelist() if name.endswith('.class')}
        assert added and not added.intersection(base.namelist())
        NEW_CLASSES=added
        entries = {name: base.read(name) for name in base.namelist() if name != 'META-INF/MANIFEST.MF'}
        entries.update({name: patch.read(name) for name in added})
        entries['META-INF/MANIFEST.MF'] = manifest.encode('utf-8')
        extras={str(p.relative_to(ROOT / 'experiments/l2d-map/assets')):p.read_bytes() for p in sorted((ROOT / 'experiments/l2d-map/assets').rglob('*')) if p.is_file()}
        extras['META-INF/L2D-LICENSE.txt']=(ROOT / 'experiments/l2d-map/upstream/LICENSE').read_bytes()
        extras['META-INF/L2D-ORIGIN.json']=(ROOT / 'experiments/l2d-map/ORIGIN.json').read_bytes()
        assert not set(extras).intersection(entries)
        entries.update(extras)
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, (2026, 10, 19, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            result.writestr(info, entries[name], compresslevel=9)
    with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(jar) as result:
        assert result.testzip() is None
        assert set(result.namelist()) == set(base.namelist()) | NEW_CLASSES | set(extras)
        assert all(result.read(name) == base.read(name) for name in base.namelist() if name != 'META-INF/MANIFEST.MF')
    report = audit_jar(jar, allowed_prefixes=("code/", "com/", "home/"))
    report.update(
        base_jar=BASE.name, base_commit='b9b477f3de65e2a2ecc2e0f88ad10a09244f22fa', base_sha256=BASE_SHA,
        jar_bytes=jar.stat().st_size, jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
        all_182_baseline_classes_and_all_map_assets_unchanged=True,
        old_native_mesh_and_visibility_class_bytes_retained_unchanged=True,
        added_classes=sorted(NEW_CLASSES),
        source_sha256={str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},
        builder_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        auditor_sha256=hashlib.sha256((ROOT / 'scripts/build_cldc.py').read_bytes()).hexdigest(),
        renderer_parent_source_sha256=hashlib.sha256((ROOT / 'experiments/static-room-bvh/src/code/Rendering/StaticRoomBvhMesh.java').read_bytes()).hexdigest(),
        l2d_commit='35c5c26a967aec4562c707f7aba532db20d17d21',
        asset_sha256={name:hashlib.sha256(data).hexdigest() for name,data in extras.items()},
        converter_sha256={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT / 'tools/l2d-map').glob('*')) if p.is_file()},
        diagnostic=dict(default='B L2D IMAGE', A='immutable Quantum FULL scene Image', B='actual L2D MeshData3D2 + Renderer.addMesh(Node), Image', C='same L2D path direct Canvas, optional target test',
                        triangles=4750,vertices=10032,calls=3,culling=False,near=128,far=60000,fov=74,
                        colors='L2D stock RGB; Quantum RGBA with alpha255; same values, representation intentionally differs',
                        map='exact3d2 conversion + strip-order sidecar; no decimation/weld/requantization',
                        states='same smooth CW/back cull, nearest MODULATE textures, baked RGB, alpha/depth, river4frames/speed6/mode7',
                        camera='exact Quantum fixed-point camera copied to L2D configureBenchmark; stock setClip/addMesh/prepareRender/flush path',
                        key0='A/B toggle', key1='A', key3='B', star='C Canvas; error explicit, 3 returns Image', key5='spawn/yaw0',
                        warmup_ms=1000,measure_ms=3000,fractional_fps=True,
                        counters='T/DC/VB and N/C/I; wall time not GPU timestamps',
                        no_software_fallback=True,map_assets_changed=False),
        handset_tested=False, handset_fps_measured=False,
        purpose='Real L2D renderer versus immutable Quantum FULL scene; same4750 triangles, viewport and camera. Not whole-game compatibility or a proof of intrinsic map/GPU limit.')
    (out / (STEM + '.build-report.json')).write_text(json.dumps(report, indent=2) + '\n')
    jad = manifest.replace('Manifest-Version: 1.0\r\n', '').rstrip()
    jad += '\r\nMIDlet-Jar-URL: ' + jar.name + '\r\nMIDlet-Jar-Size: ' + str(jar.stat().st_size) + '\r\n'
    (out / (STEM + '.jad')).write_bytes(jad.encode('utf-8'))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
