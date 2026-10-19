"""Independent release/rollback audit. No renderer-double performance claims."""
import hashlib
import json
from pathlib import Path
import struct
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'downloads/Quantum_StaticRoom_Tex3.jar'
JAR = ROOT / 'downloads/Quantum_SoftwarePolyFill_Test.jar'
BASE_SHA = '8617f348e04f4b10c03b0049e60f2e92c41532d6939546ad96f6e44d11aa6880'
NEW = {
    'code/Rendering/SoftwarePolyFillMesh.class', 'code/Rendering/SoftwarePolyFillWindow.class',
    'code/Rendering/SoftwarePolyFillWindow$Result.class', 'code/Rendering/SoftwarePolyFillPreview.class',
    'code/Rendering/SoftwarePolyFillPreview$Viewer.class',
}


class SoftwarePolyFillReleaseTest(unittest.TestCase):
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
            self.assertIn('MIDlet-Name: Quantum Software PolyFill Benchmark\r\n', manifest)
            self.assertIn('MIDlet-1: SoftwarePolyFill,,code.Rendering.SoftwarePolyFillPreview\r\n', manifest)
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
        sources = {str(path.relative_to(ROOT)) for path in (ROOT / 'experiments/software-poly-fill/src').rglob('*.java')}
        self.assertEqual(set(report['source_sha256']), sources)
        for name, digest in report['source_sha256'].items():
            self.assertEqual(digest, hashlib.sha256((ROOT / name).read_bytes()).hexdigest(), name)
        self.assertEqual(report['builder_sha256'], hashlib.sha256((ROOT / 'scripts/build_software_poly_fill.py').read_bytes()).hexdigest())
        b = report['benchmark']
        self.assertEqual(b['geometry_grids'], [8, 16, 24, 32, 48, 64, 96, 120])
        self.assertEqual(b['geometry_triangles'], [2 * n * n for n in b['geometry_grids']])
        self.assertLessEqual(max((n + 1) ** 2 for n in b['geometry_grids']), 16384)
        self.assertEqual(b['fill_layers'], [1, 2, 4, 8, 16, 32])
        self.assertIn('DirectX7.SOFTWARE', b['backend'])
        self.assertIn('sort', b['pipeline'])
        self.assertIn('NO Z buffer', b['depth'])
        self.assertEqual((b['default_persQ'],b['optional_persQ'],b['q'],b['fDist']), (4,0,24,1))
        self.assertTrue(b['no_m3g_calls'])
        self.assertTrue(b['no_nokia_directgraphics'])
        self.assertTrue(b['cold_only_geometry'])
        self.assertTrue(b['queue_prewarmed'])
        self.assertEqual((b['warmup_ms'], b['measure_ms'], b['auto_profiles']), (1000, 3000, 14))
        self.assertIn('nominal', b['metrics'])
        sources_text='\n'.join((ROOT / name).read_text() for name in sources)
        self.assertNotIn('javax.microedition.m3g',sources_text)
        self.assertNotIn('com.nokia',sources_text)
        self.assertIn('cpu.renderSoftware()',sources_text)


if __name__ == '__main__':
    unittest.main()
