"""Packaging regressions; no Java toolchain or handset required."""
import hashlib
import importlib.util
import io
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
spec = importlib.util.spec_from_file_location('static_room_lite', ROOT / 'scripts/build_static_room_lite.py')
lite = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lite)

EXPECTED = {
    'setting.txt', 'level1.txt', 'maps/kordos/kordos.3d', 'lightmap/kordod.vla',
    'maps/kordos/forestOrigy.png', 'maps/kordos/map-selo1.png',
    *('maps/kordos/river%d.bmp' % frame for frame in range(4)),
}


class LitePackagingTest(unittest.TestCase):
    def test_exact_dependencies(self):
        with zipfile.ZipFile(lite.SOURCE) as source:
            self.assertEqual(lite.dependencies(source), EXPECTED)

    def test_missing_and_unsafe_dependencies_fail(self):
        with zipfile.ZipFile(lite.SOURCE) as source:
            configs = {name: source.read(name) for name in EXPECTED}
        for change in ('missing', 'unsafe', 'wtex'):
            buffer = io.BytesIO()
            changed = dict(configs)
            if change == 'missing':
                del changed['maps/kordos/river3.bmp']
            elif change == 'unsafe':
                changed['level1.txt'] = changed['level1.txt'].replace(
                    b'/lightmap/kordod.vla', b'/../lightmap/kordod.vla')
            else:
                changed['level1.txt'] = changed['level1.txt'].replace(b'[WTEX_BEGIN]', b'[DISABLED]')
            with zipfile.ZipFile(buffer, 'w') as result:
                for name, data in changed.items():
                    result.writestr(name, data)
            with zipfile.ZipFile(buffer) as result, self.subTest(change=change):
                with self.assertRaises(ValueError):
                    lite.dependencies(result)

    def test_repack_identity_size_manifest_and_reproducibility(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            report = lite.build(output_dir=output)
            jar = output / (lite.STEM + '.jar')
            first = jar.read_bytes()
            source_before = hashlib.sha256(lite.SOURCE.read_bytes()).hexdigest()
            with zipfile.ZipFile(lite.SOURCE) as source, zipfile.ZipFile(jar) as result:
                classes = {n for n in source.namelist() if n.endswith('.class')}
                self.assertEqual(len(classes), 162)
                self.assertEqual(set(result.namelist()), classes | EXPECTED | {'META-INF/MANIFEST.MF'})
                for name in classes | EXPECTED:
                    self.assertEqual(source.read(name), result.read(name), name)
                self.assertIn(b'MIDlet-Name: Quantum Static Room Cull Lite\r\n', result.read('META-INF/MANIFEST.MF'))
                self.assertNotIn(b'code.utils.Main', result.read('META-INF/MANIFEST.MF'))
            self.assertLess(len(first), report['source_bytes'] * .1)
            self.assertEqual(report['classes'], 162)
            self.assertEqual(report['class_versions'], [[47, 0]])
            self.assertEqual(set(report['retained_resources']), EXPECTED)
            jad = (output / (lite.STEM + '.jad')).read_bytes()
            self.assertIn(('MIDlet-Jar-Size: %d\r\n' % len(first)).encode(), jad)
            self.assertIn(('MIDlet-Jar-URL: ' + lite.STEM + '.jar\r\n').encode(), jad)
            lite.build(output_dir=output)
            self.assertEqual(first, jar.read_bytes())
            self.assertEqual(source_before, hashlib.sha256(lite.SOURCE.read_bytes()).hexdigest())

    def test_unpinned_input_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'wrong.jar'
            source.write_bytes(b'not the tested cached renderer')
            with self.assertRaisesRegex(ValueError, 'immutable'):
                lite.build(source_path=source, output_dir=Path(directory) / 'out')
            self.assertFalse((Path(directory) / 'out').exists())


if __name__ == '__main__':
    unittest.main()
