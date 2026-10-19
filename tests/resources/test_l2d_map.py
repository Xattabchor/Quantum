"""Actual L2D fork provenance, isolated release and converted resource audit."""
import hashlib
import json
from pathlib import Path
import struct
import sys
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from build_cldc import audit_jar

EXP = ROOT / 'experiments/l2d-map'
JAR = ROOT / 'downloads/Quantum_L2D_Map.jar'
BASE = ROOT / 'downloads/Quantum_StaticRoom_Tex3.jar'


class L2DMapReleaseTest(unittest.TestCase):
    def test_release(self):
        report = json.loads(JAR.with_suffix('.build-report.json').read_text())
        self.assertEqual(hashlib.sha256(BASE.read_bytes()).hexdigest(), '8617f348e04f4b10c03b0049e60f2e92c41532d6939546ad96f6e44d11aa6880')
        self.assertEqual(report['jar_sha256'], hashlib.sha256(JAR.read_bytes()).hexdigest())
        self.assertEqual(report['jar_bytes'], JAR.stat().st_size)
        with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(JAR) as jar:
            self.assertIsNone(jar.testzip())
            self.assertEqual(len(jar.namelist()), len(set(jar.namelist())))
            self.assertEqual(set(jar.namelist()), set(base.namelist()) | set(report['added_classes']) | set(report['asset_sha256']))
            for name in base.namelist():
                if name != 'META-INF/MANIFEST.MF':
                    self.assertEqual(base.read(name), jar.read(name), name)
            for name, digest in report['asset_sha256'].items():
                self.assertEqual(hashlib.sha256(jar.read(name)).hexdigest(), digest, name)
            for name in report['added_classes']:
                self.assertTrue(name.startswith(('com/', 'home/', 'code/')))
                self.assertEqual(struct.unpack('>IHH', jar.read(name)[:8]), (0xcafebabe, 0, 47))
            self.assertIn('MIDlet-1: Map L2D vs Q,,code.Rendering.L2DMapPreview\r\n', jar.read('META-INF/MANIFEST.MF').decode())
            self.assertEqual(jar.read('META-INF/L2D-LICENSE.txt'), (EXP / 'upstream/LICENSE').read_bytes())
        jad = JAR.with_suffix('.jad').read_bytes().decode()
        self.assertIn('MIDlet-Jar-Size: ' + str(JAR.stat().st_size) + '\r\n', jad)
        self.assertIn('MIDlet-Jar-URL: ' + JAR.name + '\r\n', jad)
        self.assertFalse(report['handset_tested'])
        self.assertEqual(report['diagnostic']['triangles'], 4750)
        self.assertEqual(report['diagnostic']['vertices'], 10032)
        self.assertFalse(report['diagnostic']['culling'])
        self.assertEqual((report['diagnostic']['near'], report['diagnostic']['far']), (128, 60000))
        for group in ('source_sha256', 'converter_sha256'):
            for name, digest in report[group].items():
                self.assertEqual(hashlib.sha256((ROOT / name).read_bytes()).hexdigest(), digest, name)
        self.assertEqual(report['builder_sha256'], hashlib.sha256((ROOT / 'scripts/build_l2d_map.py').read_bytes()).hexdigest())
        self.assertEqual(report['auditor_sha256'], hashlib.sha256((ROOT / 'scripts/build_cldc.py').read_bytes()).hexdigest())
        audited = audit_jar(JAR, allowed_prefixes=('code/', 'com/', 'home/'))
        for key, value in audited.items():
            self.assertEqual(report[key], value)
        # Opt-in extension must NOT weaken the production/default namespace check.
        with self.assertRaisesRegex(ValueError, 'Unexpected packaged class'):
            audit_jar(JAR)

    def test_upstream_provenance(self):
        origin = json.loads((EXP / 'ORIGIN.json').read_text())
        self.assertEqual(origin['commit'], '35c5c26a967aec4562c707f7aba532db20d17d21')
        self.assertEqual(set(origin['modified_files']), {'src/com/Renderer.java', 'src/com/MeshData.java'})
        changed = set()
        for name, digest in origin['upstream_sha256'].items():
            if hashlib.sha256((EXP / 'upstream' / name).read_bytes()).hexdigest() != digest:
                changed.add(name)
        self.assertEqual(changed, set(origin['modified_files']))
        renderer = (EXP / 'upstream/src/com/Renderer.java').read_text()
        self.assertIn('g3d.render(node, null);', renderer)
        self.assertIn('g3d.bindTarget(g, true, g3dClearFlags);', renderer)
        self.assertIn('configureBenchmark', renderer)

    def test_converted_manifest(self):
        data = (EXP / 'assets/l2d-map/map.bin').read_bytes()
        offset = 0
        def take(fmt):
            nonlocal offset
            values = struct.unpack_from('>' + fmt, data, offset)
            offset += struct.calcsize('>' + fmt)
            return values[0] if len(values) == 1 else values
        def text():
            nonlocal offset
            count = take('H')
            value = data[offset:offset+count].decode('ascii')
            offset += count
            return value
        self.assertEqual(take('I'), 0x4c32424d)
        self.assertEqual(take('H'), 3)
        self.assertEqual(take('I'), 4750)
        self.assertEqual(take('I'), 10032)
        triangles = vertices = 0
        for part in range(3):
            path = text()
            self.assertEqual(path, '/l2d-map/part-' + str(part) + '.3d2')
            self.assertEqual(take('?'), part == 2)
            count, faces, frames = take('HHB')
            vertices += count
            strips = [take('B') for _ in range(faces)]
            self.assertTrue(all(s in (3, 4) for s in strips))
            triangles += sum(s-2 for s in strips)
            for _ in range(sum(strips)):
                self.assertLess(take('H'), count)
            for _ in range(frames):
                name = text()
                w, h = take('HH')
                self.assertGreaterEqual(take('f'), 0)
                self.assertEqual(len((EXP / 'assets' / name.lstrip('/')).read_bytes()), w*h*4)
            self.assertEqual(frames, 4 if part == 2 else 1)
        self.assertEqual((triangles, vertices, offset), (4750, 10032, len(data)))
