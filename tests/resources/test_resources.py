#!/usr/bin/env python3
"""Resource ZIP reader tests; no SDK, emulator or game archive required."""
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from game_resources import inventory, read_resources


class ResourceArchiveTest(unittest.TestCase):
    def archive(self, entries):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / 'game.zip'
        with zipfile.ZipFile(path, 'w') as archive:
            for name, data in entries:
                archive.writestr(name, data)
        return path

    def test_original_bytes_and_no_code_or_manifest(self):
        data = b'SKYBOX_COLOR=0,0,0\r\n\xff\xc0'
        path = self.archive([('code/utils/Main.class', b'old code'),
                             ('javax/microedition/m3g/Graphics3D.CLASS', b'old API'),
                             ('META-INF/MANIFEST.MF', b'old manifest'),
                             ('meta-inf/OLD.SF', b'old signature'),
                             ('skies/', b''), ('level1.txt', data),
                             ('skies/metro dead city.png', b'image')])
        self.assertEqual(read_resources(path), {
            'level1.txt': data, 'skies/metro dead city.png': b'image'})

    def test_reject_unsafe_names_without_extracting(self):
        for name in ('../setting.txt', '/setting.txt', 'a/../b', './a', 'C:/a', 'a\\b'):
            with self.subTest(name=name):
                with self.assertRaisesRegex(ValueError, 'Unsafe'):
                    read_resources(self.archive([(name, b'bad')]))

    def test_reject_duplicate_assets(self):
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            path = self.archive([('setting.txt', b'one'), ('setting.txt', b'two')])
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            read_resources(path)

    def test_empty_and_code_only_archive(self):
        for entries in ([], [('Old.class', b'code')]):
            with self.assertRaisesRegex(ValueError, 'No game resources'):
                read_resources(self.archive(entries))

    def test_inventory(self):
        png = b'\x89PNG\r\n\x1a\n' + b'\0\0\0\rIHDR' + struct.pack('>II', 256, 128)
        report = inventory({'skies/city.png': png, 'level32.txt':
                            b'WORLD_MODEL=/map.3d\r\nSKYBOX_2D=/skies/city.png,0.25,-20\r\n'})
        self.assertEqual(report['resource_files'], 2)
        self.assertEqual(report['levels']['level32.txt']['WORLD_MODEL'], '/map.3d')
        self.assertTrue(report['png']['skies/city.png']['power_of_two'])
        self.assertFalse(report['runtime_tested'])
        self.assertEqual(len(report['files_sha256']['skies/city.png']), 64)


if __name__ == '__main__':
    unittest.main()
