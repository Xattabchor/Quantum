#!/usr/bin/env python3
"""Resource ZIP reader tests; no SDK, emulator or game archive required."""
from pathlib import Path
import hashlib
import json
import re
import struct
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from game_resources import inventory, read_resources
from build_cldc import overlay_resources, restore_unicode_resources


class ResourceOverlayTest(unittest.TestCase):
    def test_override_is_opt_in_and_preserves_other_bytes(self):
        original = {'level12.txt': b'DIST=39000\r\n', 'other.png': b'\xff\x00'}
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'level12.txt').write_bytes(b'DIST=30000\r\n\xc0')
            (root / 'nested').mkdir()
            (root / 'nested/new.txt').write_bytes(b'new')
            result = overlay_resources(original, root)
        self.assertEqual(result['level12.txt'], b'DIST=30000\r\n\xc0')
        self.assertEqual(result['other.png'], original['other.png'])
        self.assertEqual(result['nested/new.txt'], b'new')
        self.assertEqual(original['level12.txt'], b'DIST=39000\r\n')

    def test_reject_code_and_manifest(self):
        for name in ('Main.class', 'old.CLASS', 'META-INF/MANIFEST.MF', 'meta-inf/old.sf'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'not an asset')
                with self.assertRaises(ValueError):
                    overlay_resources({}, root)

    def test_missing_directory(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(ValueError):
                overlay_resources({}, Path(temp) / 'missing')

    def test_all_stlkr_levels_only_change_distance_and_fog(self):
        archive = ROOT / 'Stlkr by ebk.jar (1).zip'
        resources = read_resources(archive)
        profile = json.loads((ROOT / 'configs/stlkr-profile.json').read_text())
        self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(), profile['archive_sha256'])
        self.assertEqual(set(profile['levels']), {str(i) for i in range(1, 14)})
        directory = ROOT / 'configs/stlkr'
        self.assertEqual({p.name for p in directory.iterdir()}, {'level%d.txt' % i for i in range(1, 14)})
        for number, changes in profile['levels'].items():
            name = 'level%s.txt' % number
            original = resources[name]
            expected = original
            newline = b'\r\n' if b'\r\n' in original else b'\n'
            with self.subTest(level=number):
                self.assertLessEqual(set(changes), {'DIST', 'FOGD'})
                for key, values in changes.items():
                    self.assertGreater(values['after'], 0)
                    if values['before'] is not None:
                        self.assertLess(values['after'], values['before'])
                        pattern = rb'(?m)^' + key.encode() + b'=' + str(values['before']).encode() + rb'(?=\r?$)'
                        expected, count = re.subn(pattern, key.encode() + b'=' + str(values['after']).encode(), expected)
                        self.assertEqual(count, 1)
                    else:
                        self.assertEqual(key, 'DIST')
                        self.assertNotRegex(original, rb'(?m)^DIST=')
                        expected = expected.replace(b'START=', b'DIST=' + str(values['after']).encode() + newline + b'START=', 1)
                optimized = (directory / name).read_bytes()
                self.assertEqual(optimized, expected)
                # No NPC, quest, spawn, weather, palette, mode or geometry edits.
                self.assertEqual(original.partition(b'[')[2], optimized.partition(b'[')[2])

    def test_aggressive_fog_end_remains_inside_draw_distance(self):
        profile = json.loads((ROOT / 'configs/stlkr-profile.json').read_text())
        for number, changes in profile['levels'].items():
            if 'FOGD' in changes:
                with self.subTest(level=number):
                    self.assertGreaterEqual(changes['DIST']['after'] - changes['FOGD']['after'], 2000)
            else:
                self.assertIn(number, ('2', '3', '4'))


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

    def test_unicode_packaging_preserves_verified_classes_and_original_assets(self):
        name = 'gamedata/HUD/background2е.png'
        payload = b'original binary resource\x00\xff'
        bytecode = b'preverified class with unchanged StackMap bytes'
        manifest = b'Manifest-Version: 1.0\r\n\r\n'
        path = self.archive([('META-INF/MANIFEST.MF', manifest),
                             ('code/Example.class', bytecode), (name, payload)])
        # Reproduce ProGuard's missing UTF-8 flags in local + central headers.
        with zipfile.ZipFile(path) as source:
            offsets = [entry.header_offset for entry in source.infolist()]
        raw = bytearray(path.read_bytes())
        for offset in offsets:
            flags = struct.unpack_from('<H', raw, offset + 6)[0]
            struct.pack_into('<H', raw, offset + 6, flags & ~0x800)
        offset = 0
        while True:
            offset = raw.find(b'PK\x01\x02', offset)
            if offset < 0:
                break
            flags = struct.unpack_from('<H', raw, offset + 8)[0]
            struct.pack_into('<H', raw, offset + 8, flags & ~0x800)
            offset += 46
        path.write_bytes(raw)
        with zipfile.ZipFile(path) as broken:
            self.assertNotIn(name, broken.namelist())
        restore_unicode_resources(path, {name: payload})
        with zipfile.ZipFile(path) as result:
            self.assertEqual(set(result.namelist()),
                             {'META-INF/MANIFEST.MF', 'code/Example.class', name})
            self.assertEqual(result.read('code/Example.class'), bytecode)
            self.assertEqual(result.read('META-INF/MANIFEST.MF'), manifest)
            self.assertEqual(result.read(name), payload)
            self.assertTrue(result.getinfo(name).flag_bits & 0x800)
        self.assertFalse(path.with_name(path.name + '.resources.tmp').exists())

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
