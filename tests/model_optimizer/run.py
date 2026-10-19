#!/usr/bin/env python3
"""Node/WASM unit tests + independent Python decode of real exported village.
No npm, network, game builds or original-resource writes.
"""
import hashlib
import json
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EXPECTED = json.loads((Path(__file__).with_name('expected-village.json')).read_text())
CLI = ROOT / 'tools/model-optimizer/cli.cjs'
BUILD = ROOT / 'build/model-optimizer-tests'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def decode(data, light):
    """Independent adaptation of Room.createFrom3d and DMODE9 loadLightMap."""
    pos = lp = 0

    def read(fmt):
        nonlocal pos
        n = struct.calcsize(fmt)
        out = struct.unpack_from(fmt, data, pos)
        pos += n
        return out[0] if len(out) == 1 else out

    meshes = []
    for _ in range(read('>i')):
        vertices = [read('>hhh') for _ in range(read('>h'))]
        faces = []
        for size in (3, 4):
            material = 0
            for _ in range(read('>h')):
                first = read('>h')
                if first == -32768:
                    material, first = read('>h') & 255, read('>h')
                ids = [first] + [read('>h') for _ in range(size - 1)]
                assert all(0 <= v < len(vertices) for v in ids)
                uvs = [read('>BB') for _ in ids]
                corners = []
                for v, uv in reversed(list(zip(ids, uvs))):
                    corners.append((vertices[v], uv, light[lp] ^ 128))
                    lp += 1
                faces.append((material, corners))
        meshes.append((vertices, faces))
    assert pos == len(data) and lp == len(light)
    return meshes


def face_triangles(c):
    return [c] if len(c) == 3 else [[c[0], c[1], c[3]], [c[3], c[1], c[2]]]


def stats(meshes):
    return dict(vertices=sum(len(v) for v, _ in meshes),
                polygons=sum(len(f) for _, f in meshes),
                triangles=sum(len(c) - 2 for _, f in meshes for _, c in f),
                expandedCorners=sum(len(c) for _, f in meshes for _, c in f))


def component_map(meshes):
    """Source positional components PER mesh/material (same as anchor rule)."""
    parents = {}

    def find(k):
        parents.setdefault(k, k)
        root = k
        while parents[root] != root:
            root = parents[root]
        while parents[k] != k:
            next_key = parents[k]
            parents[k] = root
            k = next_key
        return root

    for m, (_, faces) in enumerate(meshes):
        for mat, corners in faces:
            for tri in face_triangles(corners):
                keys = [(m, mat, c[0]) for c in tri]
                root = find(keys[0])
                for k in keys[1:]:
                    parents[find(k)] = root
    return {k: find(k) for k in parents}


def protected(meshes):
    return [[(mat, corners) for mat, corners in faces if mat == 4] for _, faces in meshes]


def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    subprocess.run(['node', '--test', 'tests/model_optimizer/optimizer.test.cjs'], cwd=ROOT, check=True)
    subprocess.run([sys.executable, 'scripts/build_model_optimizer.py'], cwd=ROOT, check=True)
    artifacts = [ROOT / 'downloads/Quantum_Model_Optimizer.html', ROOT / 'downloads/Quantum_Model_Optimizer.zip']
    first_build = [p.read_bytes() for p in artifacts]
    subprocess.run([sys.executable, 'scripts/build_model_optimizer.py'], cwd=ROOT, check=True)
    assert first_build == [p.read_bytes() for p in artifacts], 'Non-reproducible bundle'
    assert b'<script src=' not in first_build[0] and b'MESHOPTIMIZER LICENSE:' in first_build[0]
    with zipfile.ZipFile(artifacts[1]) as archive:
        assert archive.read('Quantum_Model_Optimizer.html') == first_build[0]
        assert archive.read('model-optimizer/optimizer.js') == (ROOT / 'tools/model-optimizer/optimizer.js').read_bytes()
        assert 'LICENSE' in archive.namelist()
    jar = ROOT / 'downloads/Quantum_StaticRoom_Depth.jar'
    jar_bytes = jar.read_bytes()
    assert sha(jar_bytes) == EXPECTED['jarSha256']
    with zipfile.ZipFile(jar) as archive:
        original, light = archive.read(EXPECTED['modelEntry']), archive.read(EXPECTED['lightmapEntry'])
    assert sha(original) == EXPECTED['modelSha256'] and sha(light) == EXPECTED['lightmapSha256']
    source = decode(original, light)
    assert stats(source) == EXPECTED['before']
    samples = {(m, mat, c) for m, (_, faces) in enumerate(source) for mat, corners in faces for c in corners}
    components = component_map(source)
    all_components = set(components.values())
    results = []
    with tempfile.TemporaryDirectory(prefix='village-', dir=BUILD) as temp:
        temp = Path(temp)
        input_path, map_path = temp / 'input.3d', temp / 'input.vla'
        input_path.write_bytes(original)
        map_path.write_bytes(light)
        for index, case in enumerate(EXPECTED['cases']):
            out, out_map = temp / f'output{index}.3d', temp / f'output{index}.vla'
            command = ['node', str(CLI), 'optimize', str(input_path), '--output', str(out),
                       '--lightmap', str(map_path), '--lightmap-output', str(out_map),
                       '--static', '--ratio', '.5', '--mode', case['mode'], '--error', str(case['error'])]
            if case['mode'] == 'experimental':
                command.append('--allow-seam-changes')
            subprocess.run(command, cwd=ROOT, check=True)
            model_bytes, map_bytes = out.read_bytes(), out_map.read_bytes()
            exported = decode(model_bytes, map_bytes)
            count = stats(exported)
            for key in ('triangles', 'expandedCorners'):
                assert count[key] == case[key], (case, count)
                assert count[key] < EXPECTED['before'][key]
            assert len(model_bytes) == case['modelBytes'] and len(map_bytes) == case['lightmapBytes']
            assert len(exported) == len(source)
            assert protected(exported) == protected(source), 'Protected river changed'
            seen = set()
            for m, (_, faces) in enumerate(exported):
                for mat, corners in faces:
                    for c in corners:
                        assert (m, mat, c) in samples, 'New/incorrect position, UV or light sample'
                    for tri in face_triangles(corners):
                        roots = {components[(m, mat, c[0])] for c in tri}
                        assert len(roots) == 1, 'Components merged'
                        seen.update(roots)
            assert seen == all_components, 'Disconnected component disappeared'
            report = json.loads(Path(str(out) + '.report.json').read_text())
            assert report['after']['triangles'] == count['triangles']
            assert not report['fpsMeasured'] and not report['handsetTested'] and not report['gameResourcesModified']
            assert report['inputSha256'] == sha(original) and report['outputSha256'] == sha(model_bytes)
            results.append(dict(case, outputSha256=sha(model_bytes), outputLightmapSha256=sha(map_bytes),
                                componentsPreserved=len(seen), protectedMaterial4Unchanged=True,
                                allOutputCornerSamplesFromSource=True, fpsMeasured=False))
        assert input_path.read_bytes() == original and map_path.read_bytes() == light
    assert jar.read_bytes() == jar_bytes, 'Original JAR modified'
    validation = dict(inputModelSha256=sha(original), inputLightmapSha256=sha(light), before=stats(source), cases=results)
    (BUILD / 'validation.json').write_text(json.dumps(validation, indent=2) + '\n')
    subprocess.run([sys.executable, 'scripts/verify_render_checkpoints.py'], cwd=ROOT, check=True)
    print('PASS: independent real-model decode, 4 LOD variants, UV/light provenance, protected water, all positional components, immutable JAR, reproducible offline bundles')


if __name__ == '__main__':
    main()
