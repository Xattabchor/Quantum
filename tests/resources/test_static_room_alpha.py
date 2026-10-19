"""Read-only audit of isolated alpha-test diagnostic and rollback identity."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from build_cldc import audit_jar
spec = importlib.util.spec_from_file_location('alpha', ROOT / 'scripts/build_static_room_alpha.py')
alpha = importlib.util.module_from_spec(spec)
spec.loader.exec_module(alpha)


class AlphaPackagingTest(unittest.TestCase):
    def test_original_classes_assets_manifest_and_preverify(self):
        jar = ROOT / 'downloads' / (alpha.STEM + '.jar')
        self.assertEqual(hashlib.sha256(alpha.BASE.read_bytes()).hexdigest(), alpha.BASE_SHA)
        with zipfile.ZipFile(alpha.BASE) as base, zipfile.ZipFile(jar) as result:
            self.assertEqual(set(result.namelist()), set(base.namelist()) | alpha.NEW_CLASSES)
            original_classes = [n for n in base.namelist() if n.endswith('.class')]
            self.assertEqual(len(original_classes), 172)
            for name in base.namelist():
                if name != 'META-INF/MANIFEST.MF':
                    self.assertEqual(result.read(name), base.read(name), name)
            manifest = result.read('META-INF/MANIFEST.MF')
            self.assertIn(b'MIDlet-Name: Quantum Static Room Alpha\r\n', manifest)
            self.assertIn(b'code.Rendering.StaticRoomAlphaPreview\r\n', manifest)
            self.assertIsNone(result.testzip())
        report = json.loads(jar.with_suffix('.build-report.json').read_text())
        self.assertEqual(report['jar_bytes'], jar.stat().st_size)
        self.assertEqual(report['jar_sha256'], hashlib.sha256(jar.read_bytes()).hexdigest())
        for key, value in audit_jar(jar).items():
            self.assertEqual(value, report[key], key)
        self.assertEqual(report['classes'], 179)
        self.assertFalse(report['handset_tested'])
        self.assertFalse(report['handset_fps_measured'])
        d = report['diagnostic']
        self.assertFalse(d['default_alpha_test'])
        self.assertEqual((d['fixed_near'], d['fixed_far']), (128, 60000))
        self.assertEqual((d['uploaded_vertices'], d['full_source_triangles']), (10032, 4750))
        self.assertTrue(d['texture_RGBA_bytes_blending_depth_states_unchanged'])
        self.assertTrue(d['near_far_keys_disabled'])
        for name, value in report['source_sha256'].items():
            self.assertEqual(value, hashlib.sha256((ROOT / name).read_bytes()).hexdigest(), name)
        jad = jar.with_suffix('.jad').read_bytes()
        self.assertIn(('MIDlet-Jar-Size: %d\r\n' % jar.stat().st_size).encode(), jad)
        self.assertIn(('MIDlet-Jar-URL: ' + alpha.STEM + '.jar\r\n').encode(), jad)


if __name__ == '__main__':
    unittest.main()
