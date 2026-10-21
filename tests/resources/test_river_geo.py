"""Release audit for isolated river-map geometry-cost diagnostic."""
import hashlib
import json
from pathlib import Path
import struct
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'downloads/Quantum_StaticRoom_Perspective.jar'
PIXEL = ROOT / 'downloads/Quantum_RiverMap_Pixel.jar'
UPSCALE = ROOT / 'downloads/Quantum_RiverMap_Upscale.jar'
JAR = ROOT / 'downloads/Quantum_RiverMap_Geo.jar'
NEW = {
    'code/Rendering/RiverGeoMesh.class',
    'code/Rendering/RiverGeoMesh$NativeImage.class',
    'code/Rendering/RiverGeoMesh$Part.class',
    'code/Rendering/RiverGeoMesh$Masked.class',
    'code/Rendering/RiverGeoMesh$Pending.class',
    'code/Rendering/RiverGeoPreview.class',
    'code/Rendering/RiverGeoPreview$Viewer.class',
    'code/Rendering/RiverGeoStats.class',
}
EXCLUDED = {
    'code/Rendering/RiverPixelMesh.class',
    'code/Rendering/RiverPixelMesh$NativeImage.class',
    'code/Rendering/RiverPixelMesh$Part.class',
    'code/Rendering/RiverPixelMesh$Masked.class',
    'code/Rendering/RiverPixelMesh$Pending.class',
    'code/Rendering/RiverPixelStats.class',
    'code/Rendering/RiverPixelPreview.class',
    'code/Rendering/RiverPixelPreview$Viewer.class',
    'code/Rendering/RiverUpscalePreview.class',
    'code/Rendering/RiverUpscalePreview$Viewer.class',
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


class RiverGeoReleaseTest(unittest.TestCase):
    def test_release_audit(self):
        self.assertEqual(sha(BASE), '652626cddcc8b991a4c9ba76ac810cd1d163f7041f393ff7e31fd8f44f3dcb57')
        self.assertEqual(sha(PIXEL), '2f3d602dc347cbd8cb7897859a1e8d3640e7982c731e617960a2ef401011de0c')
        self.assertEqual(sha(UPSCALE), '00204419b4c068193e1aa2f9c96fb13f2884066b608a3424a617d6ced3f925d9')
        report = json.loads(JAR.with_suffix('.build-report.json').read_text())
        with zipfile.ZipFile(BASE) as base, zipfile.ZipFile(JAR) as jar:
            self.assertIsNone(jar.testzip())
            self.assertEqual(len(jar.namelist()), len(set(jar.namelist())))
            self.assertEqual(set(jar.namelist()), set(base.namelist()) | NEW)
            for name in EXCLUDED:
                self.assertNotIn(name, jar.namelist())
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
            self.assertIn('MIDlet-Name: Quantum River Geo\r\n', manifest)
            self.assertIn('MIDlet-1: River Geo,,code.Rendering.RiverGeoPreview\r\n', manifest)
            self.assertEqual(sum(name.endswith('.class') for name in base.namelist()) + len(NEW), report['classes'])

        jad = JAR.with_suffix('.jad').read_bytes().decode('utf-8')
        self.assertIn('MIDlet-Jar-Size: ' + str(JAR.stat().st_size) + '\r\n', jad)
        self.assertIn('MIDlet-Jar-URL: ' + JAR.name + '\r\n', jad)
        self.assertEqual(report['jar_sha256'], sha(JAR))
        self.assertEqual(report['jar_bytes'], JAR.stat().st_size)
        self.assertEqual(report['base_sha256'], sha(BASE))
        self.assertEqual(report['pixel_lineage_sha256'], sha(PIXEL))
        self.assertEqual(report['upscale_lineage_sha256'], sha(UPSCALE))
        self.assertEqual(report['builder_sha256'], sha(ROOT / 'scripts/build_river_geo.py'))
        self.assertEqual(
            report['renderer_parent_source_sha256'],
            sha(ROOT / 'experiments/river-pixel/src/code/Rendering/RiverPixelMesh.java'),
        )
        self.assertEqual(
            report['stats_parent_source_sha256'],
            sha(ROOT / 'experiments/river-pixel/src/code/Rendering/RiverPixelStats.java'),
        )
        self.assertEqual(
            report['preview_parent_source_sha256'],
            sha(ROOT / 'experiments/river-upscale/src/code/Rendering/RiverUpscalePreview.java'),
        )
        expected_sources = {
            str(path.relative_to(ROOT))
            for path in (ROOT / 'experiments/river-geo/src').rglob('*.java')
        }
        self.assertEqual(set(report['source_sha256']), expected_sources)
        for name, digest in report['source_sha256'].items():
            self.assertEqual(sha(ROOT / name), digest, name)
        self.assertTrue(report['all_parent_classes_and_all_assets_unchanged_except_manifest'])
        self.assertEqual(set(report['added_classes']), NEW)
        self.assertIn('Restored ephemeral toolchain', report['toolchain_note'])
        self.assertEqual(report['added_assets'], [])
        self.assertFalse(report['handset_tested'])
        self.assertFalse(report['handset_fps_measured'])
        diagnostic = report['diagnostic']
        self.assertFalse(diagnostic['geometry_data_changed'])
        self.assertFalse(diagnostic['textures_changed'])
        self.assertFalse(diagnostic['lightmap_changed'])
        self.assertIn('S100 DIR F60K L0', diagnostic['default'])
        self.assertEqual((diagnostic['near'], diagnostic['far'], diagnostic['configured_far']), (128, 60000, 68500))
        self.assertEqual(diagnostic['far_cycle'], [60000, 40000, 20000])
        self.assertEqual(diagnostic['radius_limits_render_px'], [0, 8, 16])
        self.assertEqual(set(diagnostic['controls']), {'key1', 'key0', 'key3', 'key7', 'key9', 'star', 'key5', 'movement'})
        self.assertIn('100 -> 75 -> 50 -> 25', diagnostic['controls']['key0'])
        self.assertIn('DIR <-> UP', diagnostic['controls']['key3'])
        self.assertIn('60000 -> 40000 -> 20000', diagnostic['controls']['key7'])
        self.assertIn('L 0 -> 8 -> 16', diagnostic['controls']['key9'])
        self.assertIn('nearest mip level', diagnostic['controls']['star'])
        self.assertIn('M3G 1.0', diagnostic['m3g_compatibility'])
        self.assertIn('serviceRepaints()+sleep(1)', diagnostic['pump'])
        self.assertIn('all 4 cached frames', diagnostic['river'])
        self.assertIn('30 ms', diagnostic['purpose'])


if __name__ == '__main__':
    unittest.main()
