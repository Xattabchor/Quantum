"""Far presets may change only projection; frozen parent renderer is never replaced."""
import hashlib
import json
from pathlib import Path
import struct
import unittest
import zipfile

ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'downloads/Quantum_PortalMap_Test.jar'
JAR=ROOT/'downloads/Quantum_PortalMap_Far.jar'
NEW={'code/Rendering/PortalMapFarPreview.class','code/Rendering/PortalMapFarPreview$Viewer.class'}
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()

class PortalMapFarReleaseTest(unittest.TestCase):
    def test_frozen_parent_and_overlay(self):
        self.assertEqual(sha(BASE),'116abb3817186dec6c753e4031f4a8d66f03690f6c73db939989592062064be4')
        with zipfile.ZipFile(BASE) as base,zipfile.ZipFile(JAR) as jar:
            self.assertIsNone(jar.testzip());self.assertEqual(len(jar.namelist()),len(set(jar.namelist())))
            self.assertEqual(set(jar.namelist()),set(base.namelist())|NEW)
            self.assertEqual(sum(n.endswith('.class') for n in base.namelist()),190)
            for n in base.namelist():
                if n!='META-INF/MANIFEST.MF':self.assertEqual(base.read(n),jar.read(n),n)
            for n in jar.namelist():
                if n.endswith('.class'):self.assertEqual(struct.unpack('>IHH',jar.read(n)[:8]),(0xcafebabe,0,47),n)
            manifest=jar.read('META-INF/MANIFEST.MF').decode()
            self.assertIn('MIDlet-Name: Quantum Portal Map Far\r\n',manifest)
            self.assertIn('MIDlet-1: Far 40k-30k-20k,,code.Rendering.PortalMapFarPreview\r\n',manifest)
        jad=JAR.with_suffix('.jad').read_bytes().decode()
        self.assertIn('MIDlet-Jar-URL: '+JAR.name+'\r\n',jad);self.assertIn('MIDlet-Jar-Size: '+str(JAR.stat().st_size)+'\r\n',jad)

    def test_report_and_sources(self):
        r=json.loads(JAR.with_suffix('.build-report.json').read_text())
        parent=json.loads(BASE.with_suffix('.build-report.json').read_text())
        self.assertEqual(r['user_map'],parent['user_map']);self.assertEqual(r['classes'],192)
        self.assertEqual(r['class_versions'],[[47,0]]);self.assertGreater(r['stackmap_attributes'],parent['stackmap_attributes'])
        self.assertEqual(r['base_sha256'],sha(BASE));self.assertEqual(r['jar_sha256'],sha(JAR));self.assertEqual(r['jar_bytes'],JAR.stat().st_size)
        self.assertEqual(set(r['added_classes']),NEW);self.assertTrue(r['all_190_parent_classes_and_all_assets_unchanged'])
        self.assertEqual(r['builder_sha256'],sha(ROOT/'scripts/build_portal_map_far.py'))
        self.assertEqual(r['renderer_parent_source_sha256'],sha(ROOT/'experiments/portal-map/src/code/Rendering/PortalMapMesh.java'))
        self.assertEqual(r['auditor_sha256'],sha(ROOT/'scripts/audit_portal_map.py'))
        paths={str(p.relative_to(ROOT)) for p in (ROOT/'experiments/portal-map-far/src').rglob('*.java')}
        self.assertEqual(set(r['source_sha256']),paths)
        for name,digest in r['source_sha256'].items():self.assertEqual(digest,sha(ROOT/name))
        d=r['diagnostic'];self.assertEqual(d['default'],40000);self.assertEqual(d['far_presets'],[40000,30000,20000]);self.assertEqual((d['key1'],d['key3'],d['star']),(40000,30000,20000))
        self.assertEqual((d['near'],d['fov'],d['DMODE'],d['world_scale']),(128,74,13,6))
        self.assertEqual((d['warmup_ms'],d['measure_ms']),(1000,3000));self.assertIn('always ON',d['portals']);self.assertIn('unchanged',d['counters']);self.assertIn('far only',d['changed_state'])
        for field in ['hot_geometry_uploads','hot_index_builds','original_map_assets_changed','BVH','BSP']:self.assertFalse(d[field])
        self.assertTrue(d['no_software_fallback']);self.assertIn('NOT GPU timestamps',d['native_stages']);self.assertFalse(r['handset_tested']);self.assertFalse(r['handset_fps_measured'])

if __name__=='__main__':unittest.main()
