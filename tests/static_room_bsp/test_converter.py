"""Independent coverage/attribute/order checks. Not a native M3G rasterizer."""
import hashlib
import json
import math
from pathlib import Path
import random
import struct
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
import prepare_static_room_bsp as bsp
ASSET = ROOT/'experiments/static-room-bsp/resources/maps/kordos/kordos.bsp'


def ray_triangle(origin,direction,tri):
    a,e,f = tri
    hx=direction[1]*f[2]-direction[2]*f[1];hy=direction[2]*f[0]-direction[0]*f[2];hz=direction[0]*f[1]-direction[1]*f[0]
    det=e[0]*hx+e[1]*hy+e[2]*hz
    if abs(det)<1e-10:
        return None
    inv=1/det;sx=origin[0]-a[0];sy=origin[1]-a[1];sz=origin[2]-a[2]
    u=(sx*hx+sy*hy+sz*hz)*inv
    if u < -1e-9 or u > 1+1e-9:
        return None
    qx=sy*e[2]-sz*e[1];qy=sz*e[0]-sx*e[2];qz=sx*e[1]-sy*e[0]
    v=(direction[0]*qx+direction[1]*qy+direction[2]*qz)*inv
    if v < -1e-9 or u+v > 1+1e-9:
        return None
    t=(f[0]*qx+f[1]*qy+f[2]*qz)*inv
    return t if t>1e-6 else None


class ConverterTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'input.bin'
            subprocess.run([sys.executable,str(ROOT/'tests/static_room_bsp/run.py'),'--export',str(path)],check=True)
            cls.source=bsp.read_input(path)
            cls.input_sha=hashlib.sha256(path.read_bytes()).hexdigest()
        cls.nodes,cls.stats=bsp.build(cls.source)
        cls.data=bsp.write_tree(cls.nodes,cls.stats,len(cls.source),cls.source)

    def test_deterministic_actual_map_artifact(self):
        self.assertEqual(self.data,ASSET.read_bytes())
        report=json.loads(ASSET.with_suffix('.json').read_text())
        self.assertEqual(report,{**self.stats,'input_sha256':self.input_sha})
        self.assertEqual(len(self.nodes),5827)
        self.assertEqual(self.stats['source_triangles'],4750)
        self.assertEqual(self.stats['split_triangles'],9591)
        self.assertEqual(self.stats['resident_pages'],76)
        self.assertLessEqual(self.stats['max_position_component_error_world'],1.5)

    def test_every_source_area_winding_and_interpolated_attribute(self):
        areas=[0.]*len(self.source)
        vertices=0
        for node in self.nodes:
            for poly in node['polygons']:
                original=self.source[poly.source];a,b,c=original.v
                normal=bsp.cross(bsp.sub(b,a),bsp.sub(c,a));drop=max(range(3),key=lambda k:abs(normal[k]));axes=[k for k in range(3) if k!=drop];x,y=axes
                det=(b[x]-a[x])*(c[y]-a[y])-(b[y]-a[y])*(c[x]-a[x])
                areas[poly.source]+=bsp.area(poly.v)
                for tri in bsp.triangles(poly):
                    self.assertGreaterEqual(bsp.dot(normal,bsp.cross(bsp.sub(tri[1],tri[0]),bsp.sub(tri[2],tri[0]))),-1e-5)
                for v in poly.v:
                    u=((v[x]-a[x])*(c[y]-a[y])-(v[y]-a[y])*(c[x]-a[x]))/det
                    w=((b[x]-a[x])*(v[y]-a[y])-(b[y]-a[y])*(v[x]-a[x]))/det
                    self.assertGreaterEqual(min(u,w,1-u-w),-1e-6)
                    for k in range(8):
                        expected=a[k]+u*(b[k]-a[k])+w*(c[k]-a[k])
                        self.assertAlmostEqual(v[k],expected,delta=max(1e-6,abs(expected)*1e-9))
                    vertices+=1
        for poly,actual in zip(self.source,areas):
            self.assertAlmostEqual(actual,bsp.area(poly.v),delta=max(1e-5,bsp.area(poly.v)*1e-8))
        self.assertGreater(vertices,20000)
        origin=self.stats['global_position_origin'];step=self.stats['global_position_step_world']
        for p in self.source:
            for v in p.v:
                for k in range(3):self.assertEqual(origin[k]+round((v[k]-origin[k])/step)*step,v[k])

    def test_partition_ancestry_no_unsorted_leaves(self):
        def visit(index,ancestors):
            if index<0:
                return
            node=self.nodes[index]
            self.assertTrue(node['polygons'])
            for poly in node['polygons']:
                self.assertEqual(bsp.kind(bsp.distances(poly,node['plane'])),0)
                for p,side in ancestors:
                    for distance in bsp.distances(poly,p):
                        self.assertGreaterEqual(side*distance,-bsp.EPS*2)
            visit(node['front'],ancestors+[(node['plane'],1)])
            visit(node['back'],ancestors+[(node['plane'],-1)])
        visit(0,[])

    def test_independent_ray_coverage_and_painter_order_before_quantization(self):
        # Double-sided rays are a stronger ordering check than just front faces.
        # Attributes/coverage are proven before packing; packed rounding is NOT
        # claimed pixel-identical. B/C on the handset checks its visual impact.
        fragments=[]
        for n,node in enumerate(self.nodes):
            for poly in sorted(node['polygons'],key=lambda p:p.source):
                for tri in bsp.triangles(poly):
                    fragments.append((n,(tri[0],bsp.sub(tri[1],tri[0]),bsp.sub(tri[2],tri[0]))))
        original=[(p.v[0],bsp.sub(p.v[1],p.v[0]),bsp.sub(p.v[2],p.v[0])) for p in self.source]
        randomizer=random.Random(184)
        hits_checked=0
        for r in range(512):
            origin=(randomizer.uniform(-125000,-30000),randomizer.uniform(-3000,18000),randomizer.uniform(-145000,-35000))
            target=self.source[randomizer.randrange(len(self.source))].v
            u=randomizer.uniform(.1,.8);v=randomizer.uniform(.05,1-u-.01)
            point=tuple(target[0][k]+u*(target[1][k]-target[0][k])+v*(target[2][k]-target[0][k]) for k in range(3))
            direction=bsp.sub(point,origin);length=math.sqrt(bsp.dot(direction,direction));direction=tuple(x/length for x in direction)
            ranks={}
            def visit(index):
                if index<0:return
                node=self.nodes[index];p=node['plane'];front=bsp.dot(p,origin)+p[3]>=0
                visit(node['back' if front else 'front']);ranks[index]=len(ranks);visit(node['front' if front else 'back'])
            visit(0)
            hits=[]
            for node,tri in fragments:
                t=ray_triangle(origin,direction,tri)
                if t is not None:hits.append((ranks[node],t))
            hits.sort(key=lambda h:h[0])
            for a,b in zip(hits,hits[1:]):self.assertGreaterEqual(a[1]+.001,b[1],msg='painter nearer geometry submitted before farther')
            source_hits=[t for tri in original if (t:=ray_triangle(origin,direction,tri)) is not None]
            self.assertEqual(bool(hits),bool(source_hits))
            if hits:self.assertAlmostEqual(min(t for _,t in hits),min(source_hits),delta=.001)
            hits_checked+=len(hits)
        self.assertGreater(hits_checked,500)
        print('PASS offline ray oracle: 512 rays;',hits_checked,'ordered intersections; pre-quantization geometry, NOT phone pixels')


if __name__=='__main__':
    unittest.main()
