"""Validate published Strips container; never modify rollback artifacts."""
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
spec = importlib.util.spec_from_file_location('strips', ROOT / 'scripts/build_static_room_strips.py')
strips = importlib.util.module_from_spec(spec)
spec.loader.exec_module(strips)


class StripsPackagingTest(unittest.TestCase):
    def test_published_assets_classes_and_manifest(self):
        jar = ROOT / 'downloads' / (strips.STEM + '.jar')
        self.assertEqual(hashlib.sha256(strips.BASE.read_bytes()).hexdigest(), strips.BASE_SHA)
        with zipfile.ZipFile(strips.BASE) as base, zipfile.ZipFile(jar) as result:
            self.assertEqual(set(result.namelist()), set(base.namelist()) | strips.NEW_CLASSES)
            for name in base.namelist():
                if name != 'META-INF/MANIFEST.MF':
                    self.assertEqual(result.read(name), base.read(name), name)
            manifest = result.read('META-INF/MANIFEST.MF')
            self.assertIn(b'MIDlet-Name: Quantum Static Room Strips\r\n', manifest)
            self.assertIn(b'code.Rendering.StaticRoomStripsPreview\r\n', manifest)
            self.assertIsNone(result.testzip())
        report = json.loads(jar.with_suffix('.build-report.json').read_text())
        self.assertEqual(report['jar_bytes'], jar.stat().st_size)
        self.assertEqual(report['jar_sha256'], hashlib.sha256(jar.read_bytes()).hexdigest())
        for key, value in audit_jar(jar).items():
            self.assertEqual(value, report[key], key)
        self.assertEqual(report['classes'], 172)
        self.assertFalse(report['handset_fps_measured'])
        self.assertTrue(report['benchmark']['culling_enabled_in_both'])
        for name, value in report['source_sha256'].items():
            self.assertEqual(value, hashlib.sha256((ROOT / name).read_bytes()).hexdigest(), name)
        jad = jar.with_suffix('.jad').read_bytes()
        self.assertIn(('MIDlet-Jar-Size: %d\r\n' % jar.stat().st_size).encode(), jad)
        self.assertIn(('MIDlet-Jar-URL: '+strips.STEM+'.jar\r\n').encode(), jad)


if __name__ == '__main__':
    unittest.main()
