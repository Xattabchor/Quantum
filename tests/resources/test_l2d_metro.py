"""Indoor metro probe: provenance, isolated release and stock-asset audit."""
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

EXP = ROOT / 'experiments/l2d-metro'
UPSTREAM = ROOT / 'experiments/l2d-map'
JAR = ROOT / 'downloads/Quantum_L2D_Metro.jar'
BASE = ROOT / 'downloads/Quantum_StaticRoom_Tex3.jar'
L2D = ROOT / 'downloads/Quantum_L2D_Map.jar'
METRO_3D2_SHA = '037ef807028d8fd7d6ae66282f7d6d4f13c82143bb1a5f0da80e9c07ef315b0c'
METRO_BMP_SHA = '26571df37660dcc2e720b1ca52327a55a59f16e791b4620bdaa455ea28fdb038'


class L2DMetroReleaseTest(unittest.TestCase):
    def test_release(self):
        report = json.loads(JAR.with_suffix('.build-report.json').read_text())
        self.assertEqual(hashlib.sha256(BASE.read_bytes()).hexdigest(), '8617f348e04f4b10c03b0049e60f2e92c41532d6939546ad96f6e44d11aa6880')
        self.assertEqual(report['jar_sha256'], hashlib.sha256(JAR.read_bytes()).hexdigest())
        self.assertEqual(report['jar_bytes'], JAR.stat().st_size)
        self.assertEqual(report['l2d_lineage_sha256'], hashlib.sha256(L2D.read_bytes()).hexdigest())
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
            self.assertIn('MIDlet-1: Metro L2D,,code.Rendering.L2DMetroPreview\r\n', jar.read('META-INF/MANIFEST.MF').decode())
            self.assertEqual(jar.read('META-INF/L2D-LICENSE.txt'), (UPSTREAM / 'upstream/LICENSE').read_bytes())
            self.assertEqual(jar.read('META-INF/L2D-ORIGIN.json'), (UPSTREAM / 'ORIGIN.json').read_bytes())
        jad = JAR.with_suffix('.jad').read_bytes().decode()
        self.assertIn('MIDlet-Jar-Size: ' + str(JAR.stat().st_size) + '\r\n', jad)
        self.assertIn('MIDlet-Jar-URL: ' + JAR.name + '\r\n', jad)
        self.assertFalse(report['handset_tested'])
        self.assertFalse(report['handset_fps_measured'])
        self.assertEqual(report['l2d_commit'], '35c5c26a967aec4562c707f7aba532db20d17d21')
        for name, digest in report['source_sha256'].items():
            self.assertEqual(hashlib.sha256((ROOT / name).read_bytes()).hexdigest(), digest, name)
        self.assertEqual(report['builder_sha256'], hashlib.sha256((ROOT / 'scripts/build_l2d_metro.py').read_bytes()).hexdigest())
        self.assertEqual(report['auditor_sha256'], hashlib.sha256((ROOT / 'scripts/build_cldc.py').read_bytes()).hexdigest())
        audited = audit_jar(JAR, allowed_prefixes=('code/', 'com/', 'home/'))
        for key, value in audited.items():
            self.assertEqual(report[key], value)
        with self.assertRaisesRegex(ValueError, 'Unexpected packaged class'):
            audit_jar(JAR)
        diagnostic = report['diagnostic']
        self.assertIn('1640', diagnostic['map'])
        self.assertIn('3245', diagnostic['map'])
        self.assertIn('22', diagnostic['map'])
        self.assertIn('stock', diagnostic['renderer'])
        self.assertIn('2005', diagnostic['camera'])
        self.assertEqual(set(diagnostic), {
            'map', 'renderer', 'states', 'camera', 'default', 'key0', 'key1', 'key5',
            'movement', 'warmup_ms', 'measure_ms', 'fractional_fps', 'counters', 'scope',
            'm3g_compatibility',
        })

    def test_upstream_assets(self):
        origin = json.loads((UPSTREAM / 'ORIGIN.json').read_text())
        self.assertEqual(origin['commit'], '35c5c26a967aec4562c707f7aba532db20d17d21')
        self.assertEqual(hashlib.sha256((EXP / 'assets/l2d-metro/metro.3d2').read_bytes()).hexdigest(), METRO_3D2_SHA)
        self.assertEqual(hashlib.sha256((EXP / 'assets/l2d-metro/metro_1.bmp').read_bytes()).hexdigest(), METRO_BMP_SHA)
        # Stock 3D2 header sanity: magic, 22 meshes, file AABB and scale as documented.
        data = (EXP / 'assets/l2d-metro/metro.3d2').read_bytes()
        self.assertEqual(data[:4], b'3D2\x00')
        version, pos_scale, uv_scale = struct.unpack_from('>Hff', data, 4)
        self.assertEqual((version, pos_scale, uv_scale), (0, 256.0, 256.0))
        self.assertEqual(struct.unpack_from('>6h', data, 14), (-159, -480, -88, 4151, 107, 2036))
        self.assertEqual(struct.unpack_from('>H', data, 26), (22,))


if __name__ == '__main__':
    unittest.main()
