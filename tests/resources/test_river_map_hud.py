"""Release audit for isolated river-map HUD FULL/MIN/OFF diagnostic."""
import hashlib
import json
from pathlib import Path
import struct
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'downloads/Quantum_StaticRoom_Perspective.jar'
JAR = ROOT / 'downloads/Quantum_RiverMap_Hud.jar'
NEW = {
    'code/Rendering/RiverMapHudPreview.class',
    'code/Rendering/RiverMapHudPreview$Viewer.class',
}
RIVER_ASSETS = {
    'level1.txt',
    'lightmap/kordod.vla',
    'maps/kordos/forestOrigy.png',
    'maps/kordos/kordos.3d',
    'maps/kordos/kordos.draworder',
    'maps/kordos/map-selo1.png',
    'maps/kordos/river0.bmp',
    'maps/kordos/river1.bmp',
    'maps/kordos/river2.bmp',
    'maps/kordos/river3.bmp',
}
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()


class RiverMapHudReleaseTest(unittest.TestCase):
    def test_release_audit(self):
        self.assertEqual(sha(BASE), '652626cddcc8b991a4c9ba76ac810cd1d163f7041f393ff7e31fd8f44f3dcb57')
        report = json.loads(JAR.with_suffix('.build-report.json').read_text())
        with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(JAR) as jar:
            self.assertIsNone(jar.testzip())
            self.assertEqual(len(jar.namelist()), len(set(jar.namelist())))
            self.assertEqual(set(jar.namelist()), set(base.namelist()) | NEW)
            for name in base.namelist():
                if name != 'META-INF/MANIFEST.MF':
                    self.assertEqual(base.read(name), jar.read(name), name)
            for name in RIVER_ASSETS:
                self.assertIn(name, jar.namelist())
                self.assertEqual(base.read(name), jar.read(name), name)
            for name in (
                'code/Rendering/StaticRoomPerspectiveMesh.class',
                'code/Rendering/StaticRoomPerspectiveStats.class',
                'code/Rendering/StaticRoomPerspectivePreview$Viewer.class',
            ):
                self.assertEqual(base.read(name), jar.read(name), name)
            for name in jar.namelist():
                if name.endswith('.class'):
                    self.assertEqual(struct.unpack('>IHH', jar.read(name)[:8]), (0xCAFEBABE, 0, 47), name)
            manifest = jar.read('META-INF/MANIFEST.MF').decode('utf-8')
            self.assertIn('MIDlet-Name: Quantum River Map HUD\r\n', manifest)
            self.assertIn('MIDlet-1: River HUD A-B,,code.Rendering.RiverMapHudPreview\r\n', manifest)
            self.assertEqual(sum(name.endswith('.class') for name in base.namelist()) + len(NEW), report['classes'])

        jad = JAR.with_suffix('.jad').read_bytes().decode('utf-8')
        self.assertIn('MIDlet-Jar-Size: ' + str(JAR.stat().st_size) + '\r\n', jad)
        self.assertIn('MIDlet-Jar-URL: ' + JAR.name + '\r\n', jad)
        self.assertEqual(report['jar_sha256'], sha(JAR))
        self.assertEqual(report['jar_bytes'], JAR.stat().st_size)
        self.assertEqual(report['base_sha256'], sha(BASE))
        self.assertEqual(report['builder_sha256'], sha(ROOT / 'scripts/build_river_map_hud.py'))
        self.assertEqual(
            report['renderer_parent_source_sha256'],
            sha(ROOT / 'experiments/static-room-perspective/src/code/Rendering/StaticRoomPerspectiveMesh.java'),
        )
        self.assertEqual(
            report['stats_parent_source_sha256'],
            sha(ROOT / 'experiments/static-room-perspective/src/code/Rendering/StaticRoomPerspectiveStats.java'),
        )
        expected_sources = {
            str(path.relative_to(ROOT))
            for path in (ROOT / 'experiments/river-map-hud/src').rglob('*.java')
        }
        self.assertEqual(set(report['source_sha256']), expected_sources)
        for name, digest in report['source_sha256'].items():
            self.assertEqual(sha(ROOT / name), digest, name)
        self.assertTrue(report['all_parent_classes_and_all_assets_unchanged_except_manifest'])
        self.assertEqual(report['added_assets'], [])
        self.assertFalse(report['handset_tested'])
        self.assertFalse(report['handset_fps_measured'])
        diagnostic = report['diagnostic']
        self.assertFalse(diagnostic['geometry_changed'])
        self.assertFalse(diagnostic['textures_changed'])
        self.assertFalse(diagnostic['lightmap_changed'])
        self.assertFalse(diagnostic['renderer_changed'])
        self.assertEqual((diagnostic['near'], diagnostic['far'], diagnostic['configured_far']), (128, 60000, 68500))
        self.assertEqual(set(diagnostic['hud_modes']), {'key1', 'key3', 'key0', 'star'})
        self.assertIn('serviceRepaints()+sleep(1)', diagnostic['pump'])
        self.assertIn('all 4 cached frames', diagnostic['river'])


if __name__ == '__main__':
    unittest.main()
