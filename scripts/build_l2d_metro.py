#!/usr/bin/env python3
"""Build indoor metro-level probe: stock L2D renderer over stock upstream metro.3d2.

No map conversion and no engine edits: metro.3d2 and metro_1.bmp are pinned
upstream bytes; MeshData/Renderer come from the already-vendored upstream
sources (same commit). World-only preview; not a game port, not FPS proof.
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
BASE = ROOT / 'downloads/Quantum_StaticRoom_Tex3.jar'
BASE_SHA = '8617f348e04f4b10c03b0049e60f2e92c41532d6939546ad96f6e44d11aa6880'
L2D = ROOT / 'downloads/Quantum_L2D_Map.jar'
STEM = 'Quantum_L2D_Metro'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=os.environ.get('JAVA', 'java'))
    parser.add_argument('--ecj-jar', default=os.environ.get('ECJ_JAR', 'build/tools/ecj.jar'))
    parser.add_argument('--api-dir', type=Path, default=ROOT / 'build/sdk/lib')
    parser.add_argument('--proguard-jar', default=str(ROOT / 'build/sdk/proguard/lib/proguard.jar'))
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'artifacts/l2d-metro')
    args = parser.parse_args()
    subprocess.run([sys.executable, str(ROOT / 'scripts/verify_render_checkpoints.py')], check=True)
    if not L2D.is_file():
        raise ValueError('Missing L2D lineage parent: ' + str(L2D))
    if hashlib.sha256(BASE.read_bytes()).hexdigest() != BASE_SHA:
        raise ValueError('Wrong Tex3 baseline')
    work = ROOT / 'build/l2d-metro'
    classes = work / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    for stale in classes.rglob('*.class'):
        stale.unlink()
    api = [(args.api_dir / name).resolve() for name in API_NAMES]
    sources = sorted((ROOT / 'experiments/l2d-metro/src').rglob('*.java')) + sorted((ROOT / 'experiments/l2d-map/upstream/src').rglob('*.java'))
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
        'Manifest-Version: 1.0', 'MIDlet-Name: Quantum L2D Metro', 'MIDlet-Version: 1.0.0',
        'MIDlet-Vendor: Quantum Experiments', 'MIDlet-1: Metro L2D,,code.Rendering.L2DMetroPreview',
        'MicroEdition-Configuration: CLDC-1.1', 'MicroEdition-Profile: MIDP-2.0', '', ''])
    jar = out / (STEM + '.jar')
    with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(verified) as patch, zipfile.ZipFile(jar, 'w') as result:
        added = {name for name in patch.namelist() if name.endswith('.class')}
        assert added and not added.intersection(base.namelist())
        NEW_CLASSES = added
        entries = {name: base.read(name) for name in base.namelist() if name != 'META-INF/MANIFEST.MF'}
        entries.update({name: patch.read(name) for name in added})
        entries['META-INF/MANIFEST.MF'] = manifest.encode('utf-8')
        extras = {str(p.relative_to(ROOT / 'experiments/l2d-metro/assets')): p.read_bytes() for p in sorted((ROOT / 'experiments/l2d-metro/assets').rglob('*')) if p.is_file()}
        extras['META-INF/L2D-LICENSE.txt'] = (ROOT / 'experiments/l2d-map/upstream/LICENSE').read_bytes()
        extras['META-INF/L2D-ORIGIN.json'] = (ROOT / 'experiments/l2d-map/ORIGIN.json').read_bytes()
        assert not set(extras).intersection(entries)
        entries.update(extras)
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, (2026, 10, 21, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            result.writestr(info, entries[name], compresslevel=9)
    with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(jar) as result:
        assert result.testzip() is None
        assert set(result.namelist()) == set(base.namelist()) | NEW_CLASSES | set(extras)
        assert all(result.read(name) == base.read(name) for name in base.namelist() if name != 'META-INF/MANIFEST.MF')
    # NOTE: the published L2D map jar was compiled by an earlier toolchain; its
    # com/* bytes are not reproducible with the restored ECJ batch compiler
    # even from pristine upstream sources. Lineage is therefore pinned by
    # upstream source hashes + ORIGIN.json, not by class bytes.
    report = audit_jar(jar, allowed_prefixes=("code/", "com/", "home/"))
    report.update(
        base_jar=BASE.name, base_commit='b9b477f3de65e2a2ecc2e0f88ad10a09244f22fa', base_sha256=BASE_SHA,
        l2d_lineage_jar=L2D.name,
        l2d_lineage_sha256=hashlib.sha256(L2D.read_bytes()).hexdigest(),
        jar_bytes=jar.stat().st_size, jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
        all_baseline_classes_and_all_map_assets_unchanged=True,
        upstream_engine_classes_byte_identical_to_l2d_map_jar=False,
        upstream_engine_lineage_note=(
            'com/* classes are compiled from the vendored experiments/l2d-map/upstream sources '
            '(pinned by source_sha256 + ORIGIN.json, upstream commit 35c5c26). They differ from the '
            'published Quantum_L2D_Map jar bytes because that jar was produced by an earlier '
            'toolchain; the vendored sources additionally carry the recorded BMPLoader resource-anchor '
            'patch (ORIGIN.json modified_files) so desktop classloaders resolve bundled textures.'),
        build_reproducible=True,
        added_classes=sorted(NEW_CLASSES),
        source_sha256={str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sources},
        builder_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        auditor_sha256=hashlib.sha256((ROOT / 'scripts/build_cldc.py').read_bytes()).hexdigest(),
        l2d_commit='35c5c26a967aec4562c707f7aba532db20d17d21',
        asset_sha256={name: hashlib.sha256(data).hexdigest() for name, data in extras.items()},
        upstream_asset_paths={
            'l2d-metro/metro.3d2': 'res/metro.3d2',
            'l2d-metro/metro_1.bmp': 'res/metro_1.bmp',
            'l2d_level': 'res/level1.txt (WORLD_MODEL=/metro.3d2, WORLD_TEXTURE=/metro_1.bmp, WORLD_SCALE=7500, START=475,0,-1773)',
        },
        diagnostic=dict(
            map='stock upstream metro.3d2 (level1, indoor, no skybox): 22 meshes, 22 submeshes, 820 quads = 1640 triangles, 3245 vertices; bytes unmodified, no conversion',
            renderer='stock MeshData.loadMeshes3D2 + Renderer.addMesh(Node) exactly as upstream Respawn/Scene; benchmark adapter only sets camera/projection/clear',
            states='stock per-submesh appearances: shared PolygonMode (perspective ON; normalized CULL_BACK/WINDING_CW/SHADE_SMOOTH as in the kordos L2D experiment), shared CompositingMode (depth test+write, REPLACE, alpha 0), FUNC per 3d2 flags (vertex colors present -> default MODULATE still applies through stock code path)',
            camera='same projection math as the kordos L2D adapter: near 128, far 60000, FOV 74, pitch shift by fy; camera ground + 2005 (upstream character eye height)',
            default='S100 full surface, DIR bars at reduced scales',
            key0='cycle render scale 100 -> 75 -> 50 -> 25 percent (bars delivery only)',
            key1='BASE scale 100',
            key5='home pose (START eye)',
            movement='2/8 move, 4/6 turn, 7/9 strafe',
            warmup_ms=1000, measure_ms=3000, fractional_fps=True,
            counters='T/DC/VB static counts and N/C/I windows; wall time not GPU timestamps',
            scope='world-only: no house parts/portals/AI/HUD/skybox; FPS claims not made',
            m3g_compatibility='stock upstream engine + JSR-184 entry points as in the kordos L2D build',
        ),
        handset_tested=False, handset_fps_measured=False,
        purpose='Indoor-scene probe on the real upstream metro level with the stock L2D renderer; complement to the river/village fill-vs-geometry measurements, not whole-game compatibility.')
    (out / (STEM + '.build-report.json')).write_text(json.dumps(report, indent=2) + '\n')
    jad = manifest.replace('Manifest-Version: 1.0\r\n', '').rstrip()
    jad += '\r\nMIDlet-Jar-URL: ' + jar.name + '\r\nMIDlet-Jar-Size: ' + str(jar.stat().st_size) + '\r\n'
    (out / (STEM + '.jad')).write_bytes(jad.encode('utf-8'))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
