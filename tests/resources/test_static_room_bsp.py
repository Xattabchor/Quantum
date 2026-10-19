"""Release bytes/CLDC isolation audit; no native raster or handset FPS claim."""
import hashlib
import json
from pathlib import Path
import struct
import unittest
import zipfile

ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'downloads/Quantum_StaticRoom_Tex3.jar'
JAR=ROOT/'downloads/Quantum_StaticRoom_Bsp.jar'
DATA=ROOT/'experiments/static-room-bsp/resources/maps/kordos/kordos.bsp'
RESOURCE='maps/kordos/kordos.bsp'
NEW={
    'code/Rendering/StaticRoomBspGrid.class',
    *{'code/Rendering/StaticRoomBspGrid$'+n+'.class' for n in ('NativeImage','Part','Masked','Pending')},
    'code/Rendering/StaticRoomBspMesh.class',
    *{'code/Rendering/StaticRoomBspMesh$'+n+'.class' for n in ('NativeImage','Material','Page','Selection')},
    'code/Rendering/StaticRoomBspPreview.class','code/Rendering/StaticRoomBspPreview$Viewer.class',
    'code/Rendering/StaticRoomBspStats.class',
}
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()


class BspReleaseTest(unittest.TestCase):
    def test_immutable_baseline_and_valid_overlay(self):
        self.assertEqual(sha(BASE),'8617f348e04f4b10c03b0049e60f2e92c41532d6939546ad96f6e44d11aa6880')
        with zipfile.ZipFile(BASE) as base,zipfile.ZipFile(JAR) as jar:
            self.assertIsNone(jar.testzip())
            self.assertEqual(len(jar.namelist()),len(set(jar.namelist())))
            self.assertEqual(set(jar.namelist()),set(base.namelist())|NEW|{RESOURCE})
            for name in base.namelist():
                if name!='META-INF/MANIFEST.MF':self.assertEqual(base.read(name),jar.read(name),name)
            self.assertEqual(jar.read(RESOURCE),DATA.read_bytes())
            for name in jar.namelist():
                if name.endswith('.class'):self.assertEqual(struct.unpack('>IHH',jar.read(name)[:8]),(0xcafebabe,0,47),name)
            manifest=jar.read('META-INF/MANIFEST.MF').decode()
            for line in ('MIDlet-Name: Quantum Static Room BSP','MIDlet-1: GRID Z - BSP noZ,,code.Rendering.StaticRoomBspPreview','MicroEdition-Configuration: CLDC-1.1','MicroEdition-Profile: MIDP-2.0'):
                self.assertIn(line+'\r\n',manifest)
        jad=JAR.with_suffix('.jad').read_bytes().decode()
        self.assertIn('MIDlet-Jar-URL: '+JAR.name+'\r\n',jad)
        self.assertIn('MIDlet-Jar-Size: '+str(JAR.stat().st_size)+'\r\n',jad)

    def test_report_sources_and_offline_tree(self):
        r=json.loads(JAR.with_suffix('.build-report.json').read_text())
        self.assertEqual(r['classes'],195)
        self.assertEqual(r['class_versions'],[[47,0]])
        self.assertGreater(r['stackmap_attributes'],1185)
        self.assertEqual(set(r['added_classes']),NEW)
        self.assertEqual(r['jar_bytes'],JAR.stat().st_size)
        self.assertEqual(r['jar_sha256'],sha(JAR))
        self.assertEqual(r['base_sha256'],sha(BASE))
        self.assertTrue(r['all_182_baseline_classes_and_all_map_assets_unchanged'])
        self.assertFalse(r['handset_tested']);self.assertFalse(r['handset_fps_measured'])
        sources={str(p.relative_to(ROOT)) for p in (ROOT/'experiments/static-room-bsp/src').rglob('*.java')}
        self.assertEqual(set(r['source_sha256']),sources)
        for name,digest in r['source_sha256'].items():self.assertEqual(digest,sha(ROOT/name),name)
        for field,path in [('builder_sha256','scripts/build_static_room_bsp.py'),('converter_sha256','scripts/prepare_static_room_bsp.py'),('exporter_sha256','tests/static_room_bsp/StaticRoomBspExport.java'),('renderer_parent_source_sha256','experiments/static-room-perspective/src/code/Rendering/StaticRoomPerspectiveMesh.java')]:self.assertEqual(r[field],sha(ROOT/path))
        self.assertEqual(r['resource_sha256'],sha(DATA))
        self.assertEqual(r['bsp_data'],json.loads(DATA.with_suffix('.json').read_text()))
        t=r['bsp_data'];self.assertEqual(t['data_sha256'],sha(DATA))
        self.assertEqual(struct.unpack('>8I',DATA.read_bytes()[:32]),(0x51425350,1,4750,5827,76,5852,9591,68))
        self.assertEqual((t['source_triangles'],t['split_triangles'],t['uploaded_vertices']),(4750,9591,28773))
        self.assertLessEqual(t['max_position_component_error_world'],1.5)
        d=r['diagnostic'];self.assertEqual(d['default'],'A GRID+Z');self.assertEqual(d['modes'],['A GRID+Z','B BSP-Z','C BSP+Z'])
        self.assertEqual((d['near'],d['far'],d['fov']),(128,60000,74));self.assertEqual((d['warmup_ms'],d['measure_ms']),(1000,3000))
        self.assertIn('NOT PVS/occlusion',d['bsp']);self.assertIn('NOT GPU timestamps',d['native_stages'])
        self.assertIn('two exact',d['batching']);self.assertIn('B all OFF',d['depth'])
        self.assertTrue(d['no_software_fallback']);self.assertTrue(d['VB_is_not_driver_processing_counter']);self.assertFalse(d['map_assets_changed'])


if __name__=='__main__':unittest.main()
