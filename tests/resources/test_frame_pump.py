"""Standalone release gate: no map/game classes/assets, honest metadata and CLDC."""
import hashlib
import json
from pathlib import Path
import struct
import sys
import unittest
import zipfile
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
from build_cldc import audit_jar
JAR=ROOT/'downloads/Quantum_FramePump_Test.jar'
EXPECTED={'code/Diagnostics/'+n+'.class' for n in ('FramePumpPreview','FramePumpPreview$PaintView','FramePumpPreview$FlushView','FramePumpStats')}
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()

class FramePumpReleaseTest(unittest.TestCase):
    def test_standalone_cldc(self):
        with zipfile.ZipFile(JAR) as z:
            self.assertIsNone(z.testzip());self.assertEqual(len(z.namelist()),len(set(z.namelist())))
            self.assertEqual(set(z.namelist()),EXPECTED|{'META-INF/MANIFEST.MF'})
            for n in EXPECTED:self.assertEqual(struct.unpack('>IHH',z.read(n)[:8]),(0xcafebabe,0,47),n)
            m=z.read('META-INF/MANIFEST.MF').decode();self.assertIn('MIDlet-Name: Quantum Frame Pump\r\n',m);self.assertIn('MIDlet-1: Frame Pump,,code.Diagnostics.FramePumpPreview\r\n',m)
            self.assertIn('MicroEdition-Configuration: CLDC-1.1\r\n',m);self.assertIn('MicroEdition-Profile: MIDP-2.0\r\n',m)
        jad=JAR.with_suffix('.jad').read_bytes().decode()
        self.assertIn('MIDlet-Jar-URL: '+JAR.name+'\r\n',jad);self.assertIn('MIDlet-Jar-Size: '+str(JAR.stat().st_size)+'\r\n',jad)
        self.assertLess(JAR.stat().st_size,20000)

    def test_report(self):
        r=json.loads(JAR.with_suffix('.build-report.json').read_text());audit=audit_jar(JAR)
        for key,value in audit.items():self.assertEqual(r[key],value,key)
        self.assertEqual(r['classes'],4);self.assertEqual(r['class_versions'],[[47,0]]);self.assertGreater(r['stackmap_attributes'],0)
        self.assertEqual(r['jar_sha256'],sha(JAR));self.assertEqual(r['jar_bytes'],JAR.stat().st_size)
        self.assertEqual(r['builder_sha256'],sha(ROOT/'scripts/build_frame_pump.py'))
        paths={str(p.relative_to(ROOT)) for p in (ROOT/'experiments/frame-pump/src').rglob('*.java')}
        self.assertEqual(set(r['source_sha256']),paths)
        for name,digest in r['source_sha256'].items():self.assertEqual(digest,sha(ROOT/name))
        self.assertTrue(r['standalone']);self.assertFalse(r['game_files_changed'])
        self.assertEqual((r['geometry_render_calls'],r['textures'],r['world_assets']),(0,0,0));self.assertEqual(len(r['modes']),3)
        self.assertIn('SELF',r['modes'][1]);self.assertIn('flushGraphics',r['modes'][2])
        m=r['measurement'];self.assertEqual((m['warmup_ms'],m['window_ms']),(1000,3000));self.assertIn('draw+flush',m['P']);self.assertIn('not pure OS wait',m['G']);self.assertIn('NOT proven LCD',m['clock'])
        self.assertIn('no silent',r['unsupported']);self.assertIn('no sleep/yield/serviceRepaints',r['pump']);self.assertIn('does not wake worker',r['pump'])
        self.assertFalse(r['handset_tested']);self.assertFalse(r['handset_fps_measured'])

    def test_no_game_dependencies_or_native_draws(self):
        sources=list((ROOT/'experiments/frame-pump/src').rglob('*.java'))
        all_text='\n'.join(p.read_text() for p in sources)
        for forbidden in ('code.Rendering','code.Gameplay','getResourceAsStream','javax.microedition.m3g','Image.createImage','Thread.sleep(', 'Thread.yield(', 'serviceRepaints('):self.assertNotIn(forbidden,all_text)
        self.assertEqual(all_text.count('game.flushGraphics()'),1)
        old=(ROOT/'experiments/empty-frame/src/code/Diagnostics/EmptyFrameStats.java').read_text()
        new=(ROOT/'experiments/frame-pump/src/code/Diagnostics/FramePumpStats.java').read_text()
        self.assertEqual(old.replace('EmptyFrameStats','FramePumpStats'),new)

if __name__=='__main__':unittest.main()
