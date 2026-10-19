"""Read-only archive/rollback/CLDC audit; BVH JAR has no alpha-disable overlay."""
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
spec = importlib.util.spec_from_file_location('bvh', ROOT / 'scripts/build_static_room_bvh.py')
bvh = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bvh)


class BvhPackagingTest(unittest.TestCase):
    def test_original_resources_no_alpha_overlay_and_preverify(self):
        jar = ROOT / 'downloads' / (bvh.STEM + '.jar')
        self.assertEqual(hashlib.sha256(bvh.BASE.read_bytes()).hexdigest(), bvh.BASE_SHA)
        with zipfile.ZipFile(bvh.BASE) as base, zipfile.ZipFile(jar) as result:
            self.assertEqual(set(result.namelist()), set(base.namelist()) | bvh.NEW_CLASSES)
            self.assertEqual(len([n for n in base.namelist() if n.endswith('.class')]), 172)
            for name in base.namelist():
                if name != 'META-INF/MANIFEST.MF':
                    self.assertEqual(result.read(name), base.read(name), name)
            self.assertFalse(any(n.startswith('code/Rendering/StaticRoomAlpha') for n in result.namelist()))
            self.assertNotIn(b'setAlphaTestEnabled', result.read('code/Rendering/StaticRoomBvhMesh.class'))
            manifest = result.read('META-INF/MANIFEST.MF')
            self.assertIn(b'MIDlet-Name: Quantum Static Room BVH\r\n', manifest)
            self.assertIn(b'code.Rendering.StaticRoomBvhPreview\r\n', manifest)
            self.assertIsNone(result.testzip())
        report = json.loads(jar.with_suffix('.build-report.json').read_text())
        self.assertEqual(report['jar_bytes'], jar.stat().st_size)
        self.assertEqual(report['jar_sha256'], hashlib.sha256(jar.read_bytes()).hexdigest())
        for key, value in audit_jar(jar).items():
            self.assertEqual(value, report[key], key)
        self.assertEqual(report['classes'], 180)
        self.assertFalse(report['handset_tested'])
        self.assertFalse(report['handset_fps_measured'])
        d = report['diagnostic']
        self.assertTrue(d['default_bvh'])
        self.assertTrue(d['alpha_discard_original_thresholds_always'])
        self.assertTrue(d['alpha_disable_code_absent'])
        self.assertFalse(d['occlusion_culling'])
        self.assertEqual((d['fixed_near'], d['fixed_far']), (128, 60000))
        self.assertEqual((d['uploaded_vertices'], d['full_source_triangles']), (10032, 4750))
        self.assertEqual(d['mask_cache_entries_per_packet'], 2)
        for name, value in report['source_sha256'].items():
            self.assertEqual(value, hashlib.sha256((ROOT / name).read_bytes()).hexdigest(), name)
        jad = jar.with_suffix('.jad').read_bytes()
        self.assertIn(('MIDlet-Jar-Size: %d\r\n' % jar.stat().st_size).encode(), jad)
        self.assertIn(('MIDlet-Jar-URL: ' + bvh.STEM + '.jar\r\n').encode(), jad)


if __name__ == '__main__':
    unittest.main()
