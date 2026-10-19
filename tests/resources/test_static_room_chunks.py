"""Independent release/rollback audit. No renderer-double performance claims."""
import hashlib
import json
from pathlib import Path
import struct
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'downloads/Quantum_StaticRoom_Tex3.jar'
JAR = ROOT / 'downloads/Quantum_StaticRoom_Chunks.jar'
BASE_SHA = '8617f348e04f4b10c03b0049e60f2e92c41532d6939546ad96f6e44d11aa6880'
NEW = {
    'code/Rendering/StaticRoomChunksMesh.class', 'code/Rendering/StaticRoomChunksMesh$NativeImage.class',
    'code/Rendering/StaticRoomChunksMesh$Part.class', 'code/Rendering/StaticRoomChunksMesh$Masked.class',
    'code/Rendering/StaticRoomChunksMesh$Pending.class', 'code/Rendering/StaticRoomChunkGrid.class',
    'code/Rendering/StaticRoomChunksPreview.class', 'code/Rendering/StaticRoomChunksPreview$Viewer.class',
    'code/Rendering/StaticRoomChunksStats.class',
}


class ChunksReleaseTest(unittest.TestCase):
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
            self.assertIn('MIDlet-Name: Quantum Static Room Chunks\r\n', manifest)
            self.assertIn('MIDlet-1: Chunks 60k-40k,,code.Rendering.StaticRoomChunksPreview\r\n', manifest)
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
        self.assertEqual(report['classes'], 191)
        self.assertEqual(report['class_versions'], [[47, 0]])
        self.assertGreater(report['stackmap_attributes'], 1185)
        self.assertEqual(set(report['added_classes']), NEW)
        self.assertTrue(report['all_182_baseline_classes_and_all_map_assets_unchanged'])
        self.assertFalse(report['handset_tested'])
        self.assertFalse(report['handset_fps_measured'])
        sources = {str(path.relative_to(ROOT)) for path in (ROOT / 'experiments/static-room-chunks/src').rglob('*.java')}
        self.assertEqual(set(report['source_sha256']), sources)
        for name, digest in report['source_sha256'].items():
            self.assertEqual(digest, hashlib.sha256((ROOT / name).read_bytes()).hexdigest(), name)
        self.assertEqual(report['builder_sha256'], hashlib.sha256((ROOT / 'scripts/build_static_room_chunks.py').read_bytes()).hexdigest())
        self.assertEqual(report['renderer_parent_source_sha256'], hashlib.sha256((ROOT / 'experiments/static-room-bvh/src/code/Rendering/StaticRoomBvhMesh.java').read_bytes()).hexdigest())
        d = report['diagnostic']
        self.assertEqual(d['default'], 60000)
        self.assertEqual(d['chunk_sizes'], [60000, 40000])
        self.assertEqual(d['control'], 'legacy fixed4x4')
        self.assertEqual((d['near'], d['far'], d['fov']), (128, 60000, 74))
        self.assertEqual((d['warmup_ms'], d['measure_ms']), (1000, 3000))
        self.assertTrue(d['cold_only'])
        self.assertEqual(d['mask_cache_entries'], 2)
        self.assertTrue(d['VB_is_not_driver_processing_counter'])
        self.assertTrue(d['no_software_fallback'])
        self.assertFalse(d['map_assets_changed'])
        self.assertIn('world-unit', d['selection'])
        self.assertIn('no3x3 radius cut', d['selection'])
        self.assertIn('NOT GPU timestamps', d['native_stages'])




if __name__ == '__main__':
    unittest.main()
