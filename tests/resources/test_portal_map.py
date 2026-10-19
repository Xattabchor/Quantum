"""Archive provenance, isolated CLDC overlay and byte-preserved user map."""
import hashlib
import json
from pathlib import Path
import struct
import sys
import unittest
import zipfile
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
from audit_portal_map import ARCHIVE,ARCHIVE_SHA,ASSETS,audit
BASE=ROOT/'downloads/Quantum_StaticRoom_Tex3.jar'
JAR=ROOT/'downloads/Quantum_PortalMap_Test.jar'
NEW={'code/Rendering/PortalMapMesh.class','code/Rendering/PortalMapMesh$NativeImage.class','code/Rendering/PortalMapMesh$Part.class','code/Rendering/PortalMapVisibility.class','code/Rendering/PortalMapData.class','code/Rendering/PortalMapPreview.class','code/Rendering/PortalMapPreview$Viewer.class','code/Rendering/PortalMapStats.class'}
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()

class PortalMapReleaseTest(unittest.TestCase):
    def test_latest_archive_and_actual_topology(self):
        self.assertEqual(sha(ARCHIVE),ARCHIVE_SHA)
        assets,info=audit()
        self.assertEqual((info['rooms'],info['portals'],info['world_triangles'],info['portal_mesh_triangles']),(15,18,3761,36))
        self.assertEqual(sum(info['corners_by_room']),7955)
        self.assertEqual((info['lightmap_rgb_faces'],info['lightmap_gray_faces'],info['lightmap_bytes']),(2085,30,25956))
        self.assertEqual(info['image_dimensions']['portal-map/maps/kordon.png'],(256,256))
        self.assertEqual(info['image_dimensions']['portal-map/skies/n.png'],(512,512))
        self.assertEqual(info['links'],[[4,13],[5,13],[2,8],[6,10],[10,12],[2,3],[0,10],[0,2],[12,13],[1,10],[6,7],[4,6],[6,9],[6,11],[5,7],[8,11],[3,14],[8,9]])
        self.assertEqual(set(assets),set(ASSETS.values()))

    def test_overlay_has_no_baseline_replacements(self):
        self.assertEqual(sha(BASE),'8617f348e04f4b10c03b0049e60f2e92c41532d6939546ad96f6e44d11aa6880')
        assets,_=audit()
        with zipfile.ZipFile(BASE) as base,zipfile.ZipFile(JAR) as jar:
            self.assertIsNone(jar.testzip());self.assertEqual(len(jar.namelist()),len(set(jar.namelist())))
            self.assertEqual(set(jar.namelist()),set(base.namelist())|NEW|set(assets))
            for name in base.namelist():
                if name!='META-INF/MANIFEST.MF':self.assertEqual(jar.read(name),base.read(name),name)
            for name,data in assets.items():self.assertEqual(jar.read(name),data,name)
            for name in jar.namelist():
                if name.endswith('.class'):self.assertEqual(struct.unpack('>IHH',jar.read(name)[:8]),(0xcafebabe,0,47),name)
            manifest=jar.read('META-INF/MANIFEST.MF').decode()
            self.assertIn('MIDlet-Name: Quantum Portal Map Test\r\n',manifest)
            self.assertIn('MIDlet-1: Portals ON-OFF,,code.Rendering.PortalMapPreview\r\n',manifest)
        jad=JAR.with_suffix('.jad').read_bytes().decode()
        self.assertIn('MIDlet-Jar-URL: '+JAR.name+'\r\n',jad);self.assertIn('MIDlet-Jar-Size: '+str(JAR.stat().st_size)+'\r\n',jad)

    def test_report_source_hashes_and_controlled_modes(self):
        r=json.loads(JAR.with_suffix('.build-report.json').read_text());assets,info=audit()
        self.assertEqual(r['user_map'],json.loads(json.dumps(info)))
        self.assertEqual(r['classes'],190);self.assertEqual(r['class_versions'],[[47,0]]);self.assertGreater(r['stackmap_attributes'],1185)
        self.assertEqual(r['jar_sha256'],sha(JAR));self.assertEqual(r['jar_bytes'],JAR.stat().st_size);self.assertEqual(r['base_sha256'],sha(BASE))
        self.assertTrue(r['all_182_baseline_classes_and_all_map_assets_unchanged']);self.assertEqual(set(r['added_classes']),NEW)
        self.assertEqual(r['auditor_sha256'],sha(ROOT/'scripts/audit_portal_map.py'));self.assertEqual(r['builder_sha256'],sha(ROOT/'scripts/build_portal_map.py'))
        self.assertEqual(r['renderer_parent_source_sha256'],sha(ROOT/'experiments/static-room-perspective/src/code/Rendering/StaticRoomPerspectiveMesh.java'))
        sources={str(p.relative_to(ROOT)) for p in (ROOT/'experiments/portal-map/src').rglob('*.java')};self.assertEqual(set(r['source_sha256']),sources)
        for name,digest in r['source_sha256'].items():self.assertEqual(digest,sha(ROOT/name),name)
        self.assertFalse(r['handset_tested']);self.assertFalse(r['handset_fps_measured'])
        d=r['diagnostic'];self.assertEqual(d['modes'],['PORT ON','PORT OFF']);self.assertEqual(d['default'],'PORT ON')
        self.assertEqual((d['DMODE'],d['world_scale'],d['near'],d['far'],d['fov']),(13,6,128,40000,74))
        self.assertEqual((d['warmup_ms'],d['measure_ms']),(1000,3000));self.assertFalse(d['hot_index_builds']);self.assertFalse(d['hot_geometry_uploads'])
        self.assertTrue(d['cold_only']);self.assertTrue(d['no_software_fallback']);self.assertFalse(d['original_map_assets_changed'])
        self.assertIn('NOT GPU timestamps',d['native_stages']);self.assertIn('world-only',d['effects']);self.assertIn('ALL15',d['outside'])

if __name__=='__main__':unittest.main()
