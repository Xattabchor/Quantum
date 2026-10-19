"""Audit explicit map-selo selection, frozen parent and controlled portal/BVH overlay."""
import hashlib
import json
from pathlib import Path
import struct
import unittest
import zipfile

ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'downloads/Quantum_PortalMap_Test.jar'
JAR=ROOT/'downloads/Quantum_PortalMap_Bvh.jar'
TEXTURE='portal-map-bvh/map-selo.png'
NEW={'code/Rendering/'+name+'.class' for name in (
    'PortalMapBvhPreview','PortalMapBvhPreview$Viewer','PortalMapBvhData','PortalMapBvhVisibility',
    'PortalMapBvh','PortalMapBvhFrustum','PortalMapBvhMesh','PortalMapBvhMesh$Part','PortalMapBvhMesh$NativeImage','PortalMapBvhMesh$Masked','PortalMapBvhStats')}
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()

class PortalMapBvhReleaseTest(unittest.TestCase):
    def test_overlay_and_texture(self):
        self.assertEqual(sha(BASE),'116abb3817186dec6c753e4031f4a8d66f03690f6c73db939989592062064be4')
        self.assertEqual(sha(ROOT/'sym 9.zip.zip'),'4d831f098a636c311e310bb9fa206b14ae234df6de30d9dc0072db878695f7c3')
        with zipfile.ZipFile(BASE) as base,zipfile.ZipFile(JAR) as jar,zipfile.ZipFile(ROOT/'sym 9.zip.zip') as sym:
            self.assertIsNone(jar.testzip());self.assertEqual(len(jar.namelist()),len(set(jar.namelist())))
            self.assertEqual(set(jar.namelist()),set(base.namelist())|NEW|{TEXTURE})
            for n in base.namelist():
                if n!='META-INF/MANIFEST.MF':self.assertEqual(base.read(n),jar.read(n),n)
            texture=jar.read(TEXTURE)
            self.assertEqual(texture,sym.read('maps/kordos/map-selo.png'));self.assertNotEqual(texture,sym.read('maps/kordos/map-selo1.png'))
            self.assertEqual(hashlib.sha256(texture).hexdigest(),'b90d9db6fd0653585cec73677114935553ce33ab76450c6a6918bfb926d44efb')
            self.assertEqual(struct.unpack('>II',texture[16:24]),(256,256))
            for n in jar.namelist():
                if n.endswith('.class'):self.assertEqual(struct.unpack('>IHH',jar.read(n)[:8]),(0xcafebabe,0,47),n)
            manifest=jar.read('META-INF/MANIFEST.MF').decode()
            self.assertIn('MIDlet-Name: Quantum Portal Map BVH\r\n',manifest)
            self.assertIn('MIDlet-1: Portals-BVH ON-OFF,,code.Rendering.PortalMapBvhPreview\r\n',manifest)
        jad=JAR.with_suffix('.jad').read_bytes().decode()
        self.assertIn('MIDlet-Jar-URL: '+JAR.name+'\r\n',jad);self.assertIn('MIDlet-Jar-Size: '+str(JAR.stat().st_size)+'\r\n',jad)

    def test_report_and_sources(self):
        r=json.loads(JAR.with_suffix('.build-report.json').read_text());parent=json.loads(BASE.with_suffix('.build-report.json').read_text())
        self.assertEqual(r['user_map'],parent['user_map']);self.assertEqual(r['classes'],201);self.assertEqual(r['class_versions'],[[47,0]])
        self.assertGreater(r['stackmap_attributes'],parent['stackmap_attributes']);self.assertEqual(set(r['added_classes']),NEW)
        self.assertEqual(r['base_sha256'],sha(BASE));self.assertEqual(r['jar_sha256'],sha(JAR));self.assertEqual(r['jar_bytes'],JAR.stat().st_size)
        self.assertTrue(r['all_190_parent_classes_and_all_assets_unchanged']);self.assertTrue(r['old_native_mesh_and_visibility_class_bytes_retained_unchanged'])
        self.assertEqual(r['builder_sha256'],sha(ROOT/'scripts/build_portal_map_bvh.py'))
        self.assertEqual(r['renderer_parent_source_sha256'],sha(ROOT/'experiments/portal-map/src/code/Rendering/PortalMapMesh.java'))
        self.assertEqual(r['auditor_sha256'],sha(ROOT/'scripts/audit_portal_map.py'))
        paths={str(p.relative_to(ROOT)) for p in (ROOT/'experiments/portal-map-bvh/src').rglob('*.java')}
        self.assertEqual(set(r['source_sha256']),paths)
        for name,digest in r['source_sha256'].items():self.assertEqual(digest,sha(ROOT/name))
        t=r['texture'];self.assertEqual(t['source'],'maps/kordos/map-selo.png');self.assertEqual(t['entry'],TEXTURE);self.assertEqual((t['width'],t['height'],t['bytes']),(256,256,144265));self.assertIn('NOT map-selo1',t['replacement'])
        with zipfile.ZipFile(JAR) as jar:self.assertEqual(hashlib.sha256(jar.read(TEXTURE)).hexdigest(),t['sha256'])
        d=r['diagnostic'];self.assertEqual(d['default'],'BVH ON');self.assertEqual(d['portals'],'always ON');self.assertEqual((d['near'],d['far'],d['fov'],d['DMODE'],d['world_scale']),(128,40000,74,13,6))
        for flag in ('alpha','depth','perspective'):self.assertEqual(d[flag],'ON')
        self.assertEqual(d['far_presets'],[40000,30000,20000]);self.assertIn('cycle far',d['star'])
        self.assertEqual((d['leaf_faces'],d['index_cache_entries_per_room']),(8,2));self.assertTrue(d['BVH']);self.assertFalse(d['BSP']);self.assertFalse(d['original_map_assets_changed'])
        self.assertIn('new partial visibility mask',d['selected_indices']);self.assertIn('one call',d['batching']);self.assertIn('FALLBACK',d['fallback']);self.assertIn('NOT GPU timestamps',d['timings'])
        self.assertFalse(r['handset_tested']);self.assertFalse(r['handset_fps_measured'])

    def test_portal_traversal_unchanged(self):
        source=(ROOT/'experiments/portal-map/src/code/Rendering/PortalMapVisibility.java').read_text()
        fork=(ROOT/'experiments/portal-map-bvh/src/code/Rendering/PortalMapBvhVisibility.java').read_text().replace('PortalMapBvhVisibility','PortalMapVisibility')
        fork=fork.replace('    // Only valid for selected rooms after traversal; OUT uses full viewport.\n    int window(int room,int component){return windows[room*4+component];}\n','')
        self.assertEqual(source,fork)
        self.assertEqual((ROOT/'experiments/portal-map/src/code/Rendering/PortalMapStats.java').read_text(),(ROOT/'experiments/portal-map-bvh/src/code/Rendering/PortalMapBvhStats.java').read_text().replace('PortalMapBvhStats','PortalMapStats').replace('PortalMapBvhMesh','PortalMapMesh'))

if __name__=='__main__':unittest.main()
