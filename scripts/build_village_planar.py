#!/usr/bin/env python3
"""Build isolated exact planar remesh A/B over immutable Perspective release."""
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
from simplify_village_planar import simplify

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'downloads/Quantum_StaticRoom_Perspective.jar'
BASE_SHA = '652626cddcc8b991a4c9ba76ac810cd1d163f7041f393ff7e31fd8f44f3dcb57'
STEM = 'Quantum_Village_Planar'
NEW_CLASSES = {'code/Rendering/VillagePlanarPreview.class','code/Rendering/VillagePlanarPreview$Viewer.class'}



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=os.environ.get('JAVA', 'java'))
    parser.add_argument('--ecj-jar', default=os.environ.get('ECJ_JAR', 'build/tools/ecj.jar'))
    parser.add_argument('--api-dir', type=Path, default=ROOT / 'build/sdk/lib')
    parser.add_argument('--proguard-jar', default=str(ROOT / 'build/sdk/proguard/lib/proguard.jar'))
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'artifacts/village-planar')
    args = parser.parse_args()
    subprocess.run([sys.executable, str(ROOT / 'scripts/verify_render_checkpoints.py')], check=True)
    if hashlib.sha256(BASE.read_bytes()).hexdigest() != BASE_SHA:
        raise ValueError('Wrong immutable Perspective parent')
    with zipfile.ZipFile(BASE) as parent:
        model,order,geometry=simplify(parent.read('maps/kordos/kordos.3d'),parent.read('maps/kordos/kordos.draworder'))
    assets={'village-planar/kordos-planar.3d':model,'village-planar/kordos-planar.draworder':order,
            'village-planar/original-neutral.vla':bytes([127])*geometry['before']['corners'],
            'village-planar/planar-neutral.vla':bytes([127])*geometry['after']['corners']}
    assert geometry['before']['triangles']==4750 and geometry['after']['triangles']==4748
    work = ROOT / 'build/village-planar'
    classes = work / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    for stale in classes.rglob('*.class'):
        stale.unlink()
    api = [(args.api_dir / name).resolve() for name in API_NAMES]
    sources = sorted((ROOT / 'experiments/village-planar/src').rglob('*.java'))
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
        'Manifest-Version: 1.0', 'MIDlet-Name: Quantum Village Planar', 'MIDlet-Version: 1.0.0',
        'MIDlet-Vendor: Quantum Experiments', 'MIDlet-1: Planar A-B,,code.Rendering.VillagePlanarPreview',
        'MicroEdition-Configuration: CLDC-1.1', 'MicroEdition-Profile: MIDP-2.0', '', ''])
    jar = out / (STEM + '.jar')
    with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(verified) as patch, zipfile.ZipFile(jar, 'w') as result:
        added = {name for name in patch.namelist() if name.endswith('.class')}
        assert added == NEW_CLASSES and not added.intersection(base.namelist())
        entries = {name: base.read(name) for name in base.namelist() if name != 'META-INF/MANIFEST.MF'}
        entries.update({name: patch.read(name) for name in added})
        assert not set(assets).intersection(entries)
        entries.update(assets)
        entries['META-INF/MANIFEST.MF'] = manifest.encode('utf-8')
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, (2026, 10, 20, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            result.writestr(info, entries[name], compresslevel=9)
    with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(jar) as result:
        assert result.testzip() is None
        assert set(result.namelist()) == set(base.namelist()) | NEW_CLASSES | set(assets)
        assert all(result.read(name) == base.read(name) for name in base.namelist() if name != 'META-INF/MANIFEST.MF')
    report = audit_jar(jar)
    report.update(
        geometry=geometry,
        asset_sha256={name:hashlib.sha256(data).hexdigest() for name,data in assets.items()},
        simplifier_sha256=hashlib.sha256((ROOT/'scripts/simplify_village_planar.py').read_bytes()).hexdigest(),
        base_jar=BASE.name, base_commit='7bbc7b172c87aa05026c98bed3b6854b94f9a1a5', base_sha256=BASE_SHA,
        jar_bytes=jar.stat().st_size, jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
        all_190_parent_classes_and_all_assets_unchanged=True,
        old_native_mesh_and_visibility_class_bytes_retained_unchanged=True,
        added_classes=sorted(NEW_CLASSES),
        source_sha256={str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},
        builder_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        renderer_parent_source_sha256=hashlib.sha256((ROOT / 'experiments/static-room-perspective/src/code/Rendering/StaticRoomPerspectiveMesh.java').read_bytes()).hexdigest(),
        diagnostic=dict(default='SIMPLE',key0='toggle original/simplified, freeze movement, preserve pose',key1='SIMPLE',key3='ORIGINAL',star='focus edited patch; same pose for both meshes',key5='home',
                        lighting='neutral255 in BOTH modes, explicitly NOT old bake; requires new bake',
                        source_map='old sym9 kordos.3d, Tex3 slots/order',texture='unchanged old map-selo1.png, forest and animated river unchanged',
                        renderer='same immutable StaticRoomPerspectiveMesh with perspective ON; GRID4x4 in both modes; no new BVH/BSP',
                        near=128,far=60000,configured_far=68500,fov=74,DMODE=9,world_scale=6,
                        alpha='original thresholds ON',depth='ON, river depth write OFF',perspective='ON',
                        buffers='both meshes built once; no vertex/texture rebuild on A/B; selected-index two-entry cache on movement',
                        warmup_ms=1000,measure_ms=3000,
                        scope='world geometry only; no sky/weather/gameplay; not collision replacement',
                        caution='2 / 4750 triangles removed; no material FPS benefit expected. Not a large LOD reduction.'),
        handset_tested=False,handset_fps_measured=False,
        purpose='Strictly exact geometry/UV planar-patch reduction, old village, neutral-light A/B. Re-bake separately.')
    (out / (STEM + '.build-report.json')).write_text(json.dumps(report, indent=2) + '\n')
    jad = manifest.replace('Manifest-Version: 1.0\r\n', '').rstrip()
    jad += '\r\nMIDlet-Jar-URL: ' + jar.name + '\r\nMIDlet-Jar-Size: ' + str(jar.stat().st_size) + '\r\n'
    (out / (STEM + '.jad')).write_bytes(jad.encode('utf-8'))
    bundle={name.split('/',1)[1]:data for name,data in assets.items() if not name.endswith('original-neutral.vla')}
    bundle['geometry-report.json']=(json.dumps(geometry,indent=2)+'\n').encode()
    bundle['README.txt']=("Old sym9 village: 4750 -> 4748 triangles; exact plane/UV, boundaries retained.\n"
        "Original vertex coordinates, source scale6, Tex3 materials 0 forest / 1 map-selo1 / 2 river.\n"
        "IMPORTANT: planar-neutral.vla is a NEUTRAL255 PLACEHOLDER, NOT a new light bake.\n"
        "Re-bake light for kordos-planar.3d; do NOT reuse old kordod.vla.\n"
        "Native runtime must restore kordos-planar.draworder AFTER loading the new lightmap.\n"
        "Draworder: BE int32 face count, then int16 delta-coded file-face permutation.\n"
        "Original map/gameplay/collision and texture assets are not replaced.\n").encode()
    with zipfile.ZipFile(out/(STEM+'_Model.zip'),'w') as archive:
        for name,data in sorted(bundle.items()):
            info=zipfile.ZipInfo(name,(2026,10,20,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
            archive.writestr(info,data,compresslevel=9)
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
