"""Independent release/rollback audit. No renderer-double performance claims."""
import hashlib
import json
from pathlib import Path
import struct
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'downloads/Quantum_StaticRoom_Tex3.jar'
JAR = ROOT / 'downloads/Quantum_PolyFill_Test.jar'
BASE_SHA = '8617f348e04f4b10c03b0049e60f2e92c41532d6939546ad96f6e44d11aa6880'
NEW = {
    'code/Rendering/PolyFillMesh.class', 'code/Rendering/PolyFillWindow.class',
    'code/Rendering/PolyFillWindow$Result.class', 'code/Rendering/PolyFillPreview.class',
    'code/Rendering/PolyFillPreview$Viewer.class',
}


class PolyFillReleaseTest(unittest.TestCase):
    def test_isolated_overlay_and_rollback(self):
        self.assertEqual(hashlib.sha256(BASE.read_bytes()).hexdigest(), BASE_SHA)
        with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(JAR) as jar:
            self.assertIsNone(jar.testzip())
            self.assertEqual(len(jar.namelist()), len(set(jar.namelist())))
            self.assertEqual(set(jar.namelist()), set(base.namelist()) | NEW)
            self.assertEqual(sum(name.endswith('.class') for name in base.namelist()), 182)
            for name in base.namelist():
                if name != 'META-INF/MANIFEST.MF':
                    self.assertEqual(jar.read(name), base.read(name), name)
            for name in jar.namelist():
                if name.endswith('.class'):
                    data = jar.read(name)
                    self.assertEqual(struct.unpack('>IHH', data[:8]), (0xcafebabe, 0, 47), name)
            manifest = jar.read('META-INF/MANIFEST.MF').decode()
            self.assertIn('MIDlet-Name: Quantum PolyFill Benchmark\r\n', manifest)
            self.assertIn('MIDlet-1: PolyFill,,code.Rendering.PolyFillPreview\r\n', manifest)
            self.assertIn('MicroEdition-Configuration: CLDC-1.1\r\n', manifest)
            self.assertIn('MicroEdition-Profile: MIDP-2.0\r\n', manifest)
        jad = JAR.with_suffix('.jad').read_bytes().decode()
        self.assertIn('MIDlet-Jar-URL: ' + JAR.name + '\r\n', jad)
        self.assertIn('MIDlet-Jar-Size: ' + str(JAR.stat().st_size) + '\r\n', jad)

    def test_build_provenance_and_declared_workloads(self):
        report = json.loads(JAR.with_suffix('.build-report.json').read_text())
        self.assertEqual(report['base_sha256'], BASE_SHA)
        self.assertEqual(report['jar_bytes'], JAR.stat().st_size)
        self.assertEqual(report['jar_sha256'], hashlib.sha256(JAR.read_bytes()).hexdigest())
        self.assertEqual(report['classes'], 187)
        self.assertEqual(report['class_versions'], [[47, 0]])
        self.assertGreater(report['stackmap_attributes'], 1185)
        self.assertEqual(set(report['added_classes']), NEW)
        self.assertTrue(report['all_182_baseline_classes_and_all_map_assets_unchanged'])
        self.assertFalse(report['handset_tested'])
        self.assertFalse(report['handset_fps_measured'])
        sources = {str(path.relative_to(ROOT)) for path in (ROOT / 'experiments/poly-fill/src').rglob('*.java')}
        self.assertEqual(set(report['source_sha256']), sources)
        for name, digest in report['source_sha256'].items():
            self.assertEqual(digest, hashlib.sha256((ROOT / name).read_bytes()).hexdigest(), name)
        self.assertEqual(report['builder_sha256'], hashlib.sha256((ROOT / 'scripts/build_poly_fill.py').read_bytes()).hexdigest())
        b = report['benchmark']
        self.assertEqual(b['geometry_grids'], [8, 16, 24, 32, 48, 64, 96, 120])
        self.assertEqual(b['geometry_triangles'], [2 * n * n for n in b['geometry_grids']])
        self.assertLessEqual(max((n + 1) ** 2 for n in b['geometry_grids']), 16384)
        self.assertEqual(b['fill_layers'], [1, 2, 4, 8, 16, 32])
        self.assertEqual(b['render_calls_per_frame'], 1)
        self.assertTrue(b['geometry_depth_test_write'])
        self.assertFalse(b['fill_depth_test_write'])
        self.assertEqual(b['alpha_threshold'], .5)
        self.assertTrue(b['alpha_disable_control_absent'])
        self.assertFalse(b['hot_geometry_texture_uploads'])
        self.assertEqual((b['warmup_ms'], b['measure_ms'], b['auto_profiles']), (1000, 3000, 14))
        self.assertIn('nominal', b['metrics'])
        self.assertIn('no GPU timestamps', b['timings'])


if __name__ == '__main__':
    unittest.main()
