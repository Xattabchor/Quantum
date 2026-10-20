"""Exact rational coverage/UV proof, synthetic fail-closed cases and release audit."""
from collections import Counter
from fractions import Fraction as F
import hashlib
import json
from pathlib import Path
import struct
import sys
import unittest
import zipfile

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
from simplify_village_planar import read_model,write_model,read_order,simplify,triangles,triangle_key,area2,cross,sub
BASE=ROOT/'downloads/Quantum_StaticRoom_Perspective.jar'
JAR=ROOT/'downloads/Quantum_Village_Planar.jar'
NEW={'code/Rendering/VillagePlanarPreview.class','code/Rendering/VillagePlanarPreview$Viewer.class'}
ASSETS={'village-planar/'+n for n in ('kordos-planar.3d','kordos-planar.draworder','original-neutral.vla','planar-neutral.vla')}
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()

# Independent polygon clipping: no production BVH, plane equations or fan builder.
def orient(a,b,c):return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])
def positive(p):return p if area2(p)>0 else list(reversed(p))
def intersection(a,b):
    out=positive(list(a));b=positive(list(b))
    for p,q in zip(b,b[1:]+b[:1]):
        src=out;out=[]
        if not src:break
        last=src[-1];dl=orient(p,q,last)
        for cur in src:
            dc=orient(p,q,cur)
            if (dl>=0)!=(dc>=0):
                t=F(dl,dl-dc);out.append(tuple(x+t*(y-x) for x,y in zip(last,cur)))
            if dc>=0:out.append(cur)
            last=cur;dl=dc
    return abs(area2(out)) if out else 0

class VillagePlanarTest(unittest.TestCase):
    def test_exact_model_and_uv_coverage(self):
        with zipfile.ZipFile(BASE) as z:
            source=z.read('maps/kordos/kordos.3d');order=z.read('maps/kordos/kordos.draworder')
        model,new_order,r=simplify(source,order);v,original=read_model(source);w,changed=read_model(model)
        self.assertEqual(v,w);self.assertEqual((r['before']['triangles'],r['after']['triangles']),(4750,4748));self.assertEqual((r['before']['corners'],r['after']['corners']),(10032,10028));self.assertEqual(len(r['patches']),1)
        logical_a=[original[i] for i in read_order(order,len(original))];logical_b=[changed[i] for i in read_order(new_order,len(changed))]
        removed=set();replacements={}
        for patch in r['patches']:
            ids=patch['source_native_face_ordinals'];removed.update(ids)
            replacement=[(1,tuple((c[0],tuple(c[1])) for c in cs)) for cs in patch['replacement_corners']];replacements[min(ids)]=replacement
            before=[t for i in ids for t in triangles(logical_a[i][1])];after=[t for _,cs in replacement for t in triangles(cs)]
            # Exact per-vertex winding, plane and U/V affine mapping; no tolerances.
            key=triangle_key(v,before[0]);self.assertIsNotNone(key)
            for t in before+after:self.assertEqual(triangle_key(v,t),key)
            axes=key[2];project=lambda t:[tuple(F(v[c[0]][k]) for k in axes) for c in t]
            a=[positive(project(t)) for t in before];b=[positive(project(t)) for t in after]
            for p in b:self.assertEqual(sum(intersection(p,q) for q in a),abs(area2(p)),'new surface not fully covered by old triangles')
            for p in a:self.assertEqual(sum(intersection(p,q) for q in b),abs(area2(p)),'old surface missing')
            for group in (a,b):
                for i,p in enumerate(group):
                    for q in group[i+1:]:self.assertEqual(intersection(p,q),0,'overlapping triangle interiors')
            # Every boundary point and UV retained. No newly created T-junctions.
            def boundary(ts):
                edges=Counter(( (v[a[0]],a[1]),(v[b[0]],b[1]) ) for t in ts for a,b in zip(t,t[1:]+t[:1]))
                return Counter({e:n-edges[(e[1],e[0])] for e,n in edges.items() if n>edges[(e[1],e[0])]})
            self.assertEqual(boundary(before),boundary(after))
        expected=[]
        for i,face in enumerate(logical_a):
            if i in replacements:expected.extend(replacements[i])
            elif i not in removed:expected.append(face)
        self.assertEqual(expected,logical_b,'unrelated face/UV/material/native order edited')
        for material in (0,2):self.assertEqual([f for f in logical_a if f[0]==material],[f for f in logical_b if f[0]==material])
        # Independently count actual native degenerates / exact oriented duplicates.
        ts=[(m,t) for m,cs in logical_a for t in triangles(cs)]
        self.assertEqual(sum(cross(sub(v[t[1][0]],v[t[0][0]]),sub(v[t[2][0]],v[t[0][0]]))==(0,0,0) for _,t in ts),0)
        canonical=lambda t:min(tuple((v[c[0]],c[1]) for c in t[i:]+t[:i]) for i in range(3))
        self.assertEqual(len(ts),len(set((m,canonical(t)) for m,t in ts)))

    def test_synthetic_fail_closed(self):
        # Four convex quads around a centre, same exact affine UV map.
        vertices=[(-3,0,0),(-2,0,-2),(0,0,-3),(2,0,-2),(3,0,0),(2,0,2),(0,0,3),(-2,0,2),(0,0,0)]
        face=lambda ids,mat=1:(mat,tuple((i,(vertices[i][0]+20,vertices[i][2]+20)) for i in ids))
        faces=[face([8,0,1,2]),face([8,2,3,4]),face([8,4,5,6]),face([8,6,7,0])]
        data,order=write_model(vertices,faces);_,_,r=simplify(data,order);self.assertEqual(r['after']['triangles'],6)
        for mat in (0,2):
            d,o=write_model(vertices,[(mat,cs) for _,cs in faces]);_,_,r=simplify(d,o);self.assertEqual(r['patches'],[])
        wrong=list(vertices);wrong[8]=(0,1,0);d,o=write_model(wrong,faces);_,_,r=simplify(d,o);self.assertEqual(r['patches'],[],'nonplanar merged')
        # An UV seam must not be silently welded or reparameterized.
        seam=[(m,tuple((i,(uv[0]+(1 if k==0 else 0),uv[1])) for i,uv in cs)) for k,(m,cs) in enumerate(faces)]
        d,o=write_model(vertices,seam);_,_,r=simplify(d,o);self.assertEqual(r['patches'],[],'UV seam merged')
        # Duplicate/nonmanifold faces, a missing quadrant and zero-area input fail closed.
        for fs in (faces+[faces[0]],faces[:3],[(1,((0,(0,0)),)*3)]):
            d,o=write_model(vertices,fs);_,_,r=simplify(d,o);self.assertEqual(r['patches'],[])
        with self.assertRaises(ValueError):read_model(data+b'bad')
        with self.assertRaises(ValueError):read_order(order[:-1],4)

    def test_release_audit(self):
        self.assertEqual(sha(BASE),'652626cddcc8b991a4c9ba76ac810cd1d163f7041f393ff7e31fd8f44f3dcb57')
        r=json.loads(JAR.with_suffix('.build-report.json').read_text())
        with zipfile.ZipFile(BASE) as base,zipfile.ZipFile(JAR) as jar:
            self.assertIsNone(jar.testzip());self.assertEqual(len(jar.namelist()),len(set(jar.namelist())));self.assertEqual(set(jar.namelist()),set(base.namelist())|NEW|ASSETS)
            for n in base.namelist():
                if n!='META-INF/MANIFEST.MF':self.assertEqual(base.read(n),jar.read(n),n)
            for n in jar.namelist():
                if n.endswith('.class'):self.assertEqual(struct.unpack('>IHH',jar.read(n)[:8]),(0xcafebabe,0,47))
            self.assertIn('MIDlet-1: Planar A-B,,code.Rendering.VillagePlanarPreview\r\n',jar.read('META-INF/MANIFEST.MF').decode())
            m,o,g=simplify(base.read('maps/kordos/kordos.3d'),base.read('maps/kordos/kordos.draworder'))
            self.assertEqual(m,jar.read('village-planar/kordos-planar.3d'));self.assertEqual(o,jar.read('village-planar/kordos-planar.draworder'));self.assertEqual(json.loads(json.dumps(g)),r['geometry'])
            self.assertEqual(jar.read('village-planar/original-neutral.vla'),bytes([127])*10032);self.assertEqual(jar.read('village-planar/planar-neutral.vla'),bytes([127])*10028)
            for n,d in r['asset_sha256'].items():self.assertEqual(hashlib.sha256(jar.read(n)).hexdigest(),d)
        jad=JAR.with_suffix('.jad').read_bytes().decode();self.assertIn('MIDlet-Jar-Size: '+str(JAR.stat().st_size)+'\r\n',jad);self.assertIn('MIDlet-Jar-URL: '+JAR.name+'\r\n',jad)
        self.assertEqual(r['jar_sha256'],sha(JAR));self.assertEqual(r['jar_bytes'],JAR.stat().st_size);self.assertEqual(r['classes'],192);self.assertTrue(r['all_190_parent_classes_and_all_assets_unchanged'])
        self.assertEqual(r['base_sha256'],sha(BASE));self.assertEqual(r['builder_sha256'],sha(ROOT/'scripts/build_village_planar.py'));self.assertEqual(r['simplifier_sha256'],sha(ROOT/'scripts/simplify_village_planar.py'))
        for n,d in r['source_sha256'].items():self.assertEqual(sha(ROOT/n),d)
        self.assertEqual(set(r['source_sha256']),{str(p.relative_to(ROOT)) for p in (ROOT/'experiments/village-planar/src').rglob('*.java')})
        self.assertEqual(r['renderer_parent_source_sha256'],sha(ROOT/'experiments/static-room-perspective/src/code/Rendering/StaticRoomPerspectiveMesh.java'))
        self.assertFalse(r['handset_tested']);self.assertFalse(r['handset_fps_measured']);self.assertIn('neutral255',r['diagnostic']['lighting']);self.assertEqual((r['diagnostic']['near'],r['diagnostic']['far']),(128,60000))
        with zipfile.ZipFile(ROOT/'downloads/Quantum_Village_Planar_Model.zip') as bundle:
            self.assertEqual(bundle.read('kordos-planar.3d'),m);self.assertEqual(bundle.read('kordos-planar.draworder'),o);self.assertIn(b'NOT a new light bake',bundle.read('README.txt'));self.assertNotIn('kordod.vla',bundle.namelist())

if __name__=='__main__':unittest.main()
