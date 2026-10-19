#!/usr/bin/env python3
"""Build isolated portal + BVH viewer over immutable PortalMap release."""
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
from audit_portal_map import audit as audit_portal_archive, ARCHIVE

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'downloads/Quantum_PortalMap_Test.jar'
BASE_SHA = '116abb3817186dec6c753e4031f4a8d66f03690f6c73db939989592062064be4'
STEM = 'Quantum_PortalMap_Bvh'
NEW_CLASSES = {'code/Rendering/'+name+'.class' for name in (
    'PortalMapBvhPreview','PortalMapBvhPreview$Viewer','PortalMapBvhData','PortalMapBvhVisibility',
    'PortalMapBvh','PortalMapBvhFrustum','PortalMapBvhMesh','PortalMapBvhMesh$Part','PortalMapBvhMesh$NativeImage','PortalMapBvhMesh$Masked','PortalMapBvhStats')}
TEXTURE_ARCHIVE = ROOT/'sym 9.zip.zip'
TEXTURE_ARCHIVE_SHA = '4d831f098a636c311e310bb9fa206b14ae234df6de30d9dc0072db878695f7c3'
TEXTURE_PATH = 'maps/kordos/map-selo.png'
TEXTURE_ENTRY = 'portal-map-bvh/map-selo.png'
TEXTURE_SHA = 'b90d9db6fd0653585cec73677114935553ce33ab76450c6a6918bfb926d44efb'



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=os.environ.get('JAVA', 'java'))
    parser.add_argument('--ecj-jar', default=os.environ.get('ECJ_JAR', 'build/tools/ecj.jar'))
    parser.add_argument('--api-dir', type=Path, default=ROOT / 'build/sdk/lib')
    parser.add_argument('--proguard-jar', default=str(ROOT / 'build/sdk/proguard/lib/proguard.jar'))
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'artifacts/portal-map-bvh')
    args = parser.parse_args()
    subprocess.run([sys.executable, str(ROOT / 'scripts/verify_render_checkpoints.py')], check=True)
    if hashlib.sha256(BASE.read_bytes()).hexdigest() != BASE_SHA:
        raise ValueError('Wrong immutable PortalMap parent')
    user_assets,map_report = audit_portal_archive()
    assert hashlib.sha256(TEXTURE_ARCHIVE.read_bytes()).hexdigest()==TEXTURE_ARCHIVE_SHA
    with zipfile.ZipFile(TEXTURE_ARCHIVE) as archive:texture=archive.read(TEXTURE_PATH)
    assert hashlib.sha256(texture).hexdigest()==TEXTURE_SHA
    work = ROOT / 'build/portal-map-bvh'
    classes = work / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    for stale in classes.rglob('*.class'):
        stale.unlink()
    api = [(args.api_dir / name).resolve() for name in API_NAMES]
    sources = sorted((ROOT / 'experiments/portal-map-bvh/src').rglob('*.java'))
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
        'Manifest-Version: 1.0', 'MIDlet-Name: Quantum Portal Map BVH', 'MIDlet-Version: 1.0.0',
        'MIDlet-Vendor: Quantum Experiments', 'MIDlet-1: Portals-BVH ON-OFF,,code.Rendering.PortalMapBvhPreview',
        'MicroEdition-Configuration: CLDC-1.1', 'MicroEdition-Profile: MIDP-2.0', '', ''])
    jar = out / (STEM + '.jar')
    with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(verified) as patch, zipfile.ZipFile(jar, 'w') as result:
        added = {name for name in patch.namelist() if name.endswith('.class')}
        assert added == NEW_CLASSES and not added.intersection(base.namelist())
        entries = {name: base.read(name) for name in base.namelist() if name != 'META-INF/MANIFEST.MF'}
        entries.update({name: patch.read(name) for name in added})
        assert all(entries[name]==data for name,data in user_assets.items())
        entries[TEXTURE_ENTRY]=texture
        entries['META-INF/MANIFEST.MF'] = manifest.encode('utf-8')
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, (2026, 10, 20, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            result.writestr(info, entries[name], compresslevel=9)
    with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(jar) as result:
        assert result.testzip() is None
        assert set(result.namelist()) == set(base.namelist()) | NEW_CLASSES | {TEXTURE_ENTRY}
        assert all(result.read(name) == base.read(name) for name in base.namelist() if name != 'META-INF/MANIFEST.MF')
    report = audit_jar(jar)
    report.update(
        user_map=map_report,source_archive=str(ARCHIVE.relative_to(ROOT)),
        auditor_sha256=hashlib.sha256((ROOT/'scripts/audit_portal_map.py').read_bytes()).hexdigest(),
        base_jar=BASE.name, base_commit='6fe1257924b52fa252461c4ef36bb78cb57edc2f', base_sha256=BASE_SHA,
        jar_bytes=jar.stat().st_size, jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
        all_190_parent_classes_and_all_assets_unchanged=True,
        old_native_mesh_and_visibility_class_bytes_retained_unchanged=True,
        added_classes=sorted(NEW_CLASSES),
        source_sha256={str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},
        builder_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        renderer_parent_source_sha256=hashlib.sha256((ROOT / 'experiments/portal-map/src/code/Rendering/PortalMapMesh.java').read_bytes()).hexdigest(),
        texture=dict(archive=TEXTURE_ARCHIVE.name,archive_sha256=TEXTURE_ARCHIVE_SHA,source=TEXTURE_PATH,
                     entry=TEXTURE_ENTRY,sha256=TEXTURE_SHA,bytes=len(texture),width=256,height=256,
                     replacement='explicit map-selo.png, NOT map-selo1.png; same atlas in BVH ON/OFF; UVs not remapped'),
        diagnostic=dict(default='BVH ON',portals='always ON',key0='toggle BVH, freeze motion and reset stats',key1='BVH ON',key3='BVH OFF',star='cycle far 40000/30000/20000; preserve pose, freeze movement, reset stats',key5='home',
                        near=128,far=40000,far_presets=[40000,30000,20000],fov=74,DMODE=13,world_scale=6,alpha='ON',depth='ON',perspective='ON',
                        warmup_ms=1000,measure_ms=3000,leaf_faces=8,index_cache_entries_per_room=2,
                        vertex_texture_uploads='cold only',selected_indices='immutable; built BEFORE bind only on a new partial visibility mask; two-entry room cache; no stationary rebuild',
                        batching='at most one call per selected nonempty room, original face order; no per-leaf draw calls',
                        selection='original portal room windows + per-room median BVH/AABB vs native camera clip planes and room window',
                        cache='exact native camera16 + viewport/focals/far/near/room mask/seed + BVH state',
                        fallback='index allocation OOM drops caches and uses full original selected-room indices; explicit BVH FALLBACK HUD',
                        BVH=True,BSP=False,original_map_assets_changed=False,
                        effects='geometry only: no sky/weather/gameplay in either mode',
                        counters='T submitted triangles, DC API calls, VB resident room buffer capacity; not post-native-clipping counters',
                        timings='J/B/K/R/E API wall time, NOT GPU timestamps'),
        handset_tested=False,handset_fps_measured=False,
        purpose='Portal map + per-room BVH ON/OFF, sym9 map-selo.png atlas. No handset speed claim.')
    (out / (STEM + '.build-report.json')).write_text(json.dumps(report, indent=2) + '\n')
    jad = manifest.replace('Manifest-Version: 1.0\r\n', '').rstrip()
    jad += '\r\nMIDlet-Jar-URL: ' + jar.name + '\r\nMIDlet-Jar-Size: ' + str(jar.stat().st_size) + '\r\n'
    (out / (STEM + '.jad')).write_bytes(jad.encode('utf-8'))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
