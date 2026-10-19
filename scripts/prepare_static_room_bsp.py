#!/usr/bin/env python3
"""Offline polygon-plane BSP, with actual splitting (not centroid/depth sorting).
Input is the frozen Tex3 native triangle stream exported by the desktop harness.
No conversion or tree building is performed on the phone. Pure Python, deterministic.
"""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import struct

EPS = 1e-5
MAX_DEPTH = 120
MAX_NODES = 8192
MAX_VERTICES = 16383


def dot(a, b):
    return sum(a[i] * b[i] for i in range(3))


def cross(a, b):
    return (a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])


def sub(a, b):
    return tuple(a[i]-b[i] for i in range(3))


def area(v):
    return sum(math.sqrt(dot(c, c))/2 for c in (cross(sub(v[i], v[0]), sub(v[i+1], v[0])) for i in range(1, len(v)-1)))


def plane(v):
    n = cross(sub(v[1], v[0]), sub(v[2], v[0]))
    length = math.sqrt(dot(n, n))
    if length < 1e-10:
        return None
    n = tuple(x/length for x in n)
    sign = next(x for x in n if abs(x) > 1e-12)
    if sign < 0:
        n = tuple(-x for x in n)
    return n + (-dot(n, v[0]),)


class Poly:
    def __init__(self, source, material, vertices, p=None):
        self.source, self.material, self.v = source, material, vertices
        self.plane = plane(vertices) if p is None else p


def read_input(path):
    data = Path(path).read_bytes()
    magic, count = struct.unpack_from('>II', data)
    if magic != 0x51425349 or len(data) != 8 + count * 193:
        raise ValueError('invalid frozen input')
    polys = []
    for i in range(count):
        offset = 8 + i*193
        material = data[offset]
        values = struct.unpack_from('>24d', data, offset+1)
        if material > 2 or not all(math.isfinite(x) for x in values):
            raise ValueError('invalid attributes')
        polys.append(Poly(i, material, [values[j:j+8] for j in range(0,24,8)]))
    return polys


def distances(poly, p):
    return [p[0]*v[0]+p[1]*v[1]+p[2]*v[2]+p[3] for v in poly.v]


def kind(ds):
    pos, neg = max(ds) > EPS, min(ds) < -EPS
    return 2 if pos and neg else 1 if pos else -1 if neg else 0


def split(poly, p, ds):
    front, back = [], []
    for i, a in enumerate(poly.v):
        b = poly.v[(i+1) % len(poly.v)]
        da, db = ds[i], ds[(i+1) % len(ds)]
        if da >= -EPS:
            front.append(a)
        if da <= EPS:
            back.append(a)
        if (da > EPS and db < -EPS) or (da < -EPS and db > EPS):
            t = da/(da-db)
            v = tuple(a[k] + t*(b[k]-a[k]) for k in range(8))
            front.append(v)
            back.append(v)
    if len(front) < 3 or len(back) < 3:
        raise ValueError('invalid split')
    # Every cut conserves coverage and preserves all eight affine attributes.
    a = area(poly.v)
    if abs(area(front)+area(back)-a) > max(1e-5, a*1e-9):
        raise ValueError('split lost area')
    return Poly(poly.source, poly.material, front, poly.plane), Poly(poly.source, poly.material, back, poly.plane)


def build(polys):
    nodes = []
    stats = dict(splits=0, max_depth=0)
    degenerate = [p for p in polys if p.plane is None]
    polys = [p for p in polys if p.plane is not None]

    def rec(items, depth):
        if not items:
            return -1
        if depth > MAX_DEPTH or len(nodes) >= MAX_NODES:
            raise ValueError('BSP budget exceeded; no approximate unsorted leaves')
        stats['max_depth'] = max(stats['max_depth'], depth)
        unique = {}
        # Broad deterministic sample plus large surfaces: balances split cost/depth.
        candidates = items if len(items) <= 32 else [items[i*len(items)//24] for i in range(24)] + sorted(items, key=lambda x: -area(x.v))[:8]
        for poly in candidates:
            key = tuple(round(x, 6) for x in poly.plane)
            unique.setdefault(key, poly.plane)
        best = None
        for p in unique.values():
            counts = Counter(kind(distances(poly, p)) for poly in items)
            score = 8*counts[2] + .35*abs(counts[1]-counts[-1]) - .1*counts[0]
            if best is None or score < best[0]:
                best = (score, p)
        p = best[1]
        index = len(nodes)
        node = dict(plane=p, polygons=[], front=-1, back=-1)
        nodes.append(node)
        front, back = [], []
        for poly in items:
            ds = distances(poly, p)
            k = kind(ds)
            if k == 0:
                node['polygons'].append(poly)
            elif k == 1:
                front.append(poly)
            elif k == -1:
                back.append(poly)
            else:
                a, b = split(poly, p, ds)
                front.append(a)
                back.append(b)
                stats['splits'] += 1
        if not node['polygons']:
            raise ValueError('partition without progress')
        node['front'] = rec(front, depth+1)
        node['back'] = rec(back, depth+1)
        return index

    if rec(polys, 1) != 0:
        raise ValueError('empty geometry')
    # Raster-inert source degenerates remain present, explicitly counted.
    nodes[0]['polygons'].extend(degenerate)
    stats['source_degenerate_triangles'] = len(degenerate)
    return nodes, stats


def triangles(poly):
    return [[poly.v[0], poly.v[i], poly.v[i+1]] for i in range(1, len(poly.v)-1)]


def pack_packet(material, vertices, step, origin):
    low = [min(v[k] for v in vertices) for k in range(3)]
    high = [max(v[k] for v in vertices) for k in range(3)]
    # One common lattice for ALL pages: original source vertices stay exact,
    # and equal cut points do not snap differently on neighbouring pages.
    bias = [origin[k]+math.floor(((low[k]+high[k])/2-origin[k])/step+.5)*step for k in range(3)]
    xyz = [math.floor((v[k]-bias[k])/step+.5) for v in vertices for k in range(3)]
    uv = [math.floor(v[k]*128+.5) for v in vertices for k in (3,4)]
    colors = [max(0,min(255,math.floor(v[k]+.5))) for v in vertices for k in (5,6,7)]
    if any(not -32768 <= x <= 32767 for x in xyz+uv):
        raise ValueError('short packing overflow')
    decoded = [tuple(xyz[i*3+k]*step+bias[k] for k in range(3)) for i in range(len(vertices))]
    error = max(abs(decoded[i][k]-v[k]) for i,v in enumerate(vertices) for k in range(3))
    bounds = [math.floor(min(v[k] for v in decoded))-4 for k in range(3)] + [math.ceil(max(v[k] for v in decoded))+4 for k in range(3)]
    blob = struct.pack('>BH4f', material,len(vertices),step,*bias)
    blob += struct.pack('>'+str(len(xyz))+'h',*xyz) + struct.pack('>'+str(len(uv))+'h',*uv) + bytes(colors)
    return blob, bounds, error


def write_tree(nodes, stats, source_count, source=None):
    # Cold compact pages, not thousands of tiny native VertexArrays. The phone
    # batches ONLY consecutive same-page spans in BSP order; never by material
    # across an intervening surface. Exact ordered selections have a 2-entry cache.
    if source is None:
        raise ValueError('original triangles required to certify an exact source lattice')
    origin = source[0].v[0][:3]
    gcd = 0
    for poly in source:
        for v in poly.v:
            for k in range(3):
                if v[k] != int(v[k]):raise ValueError('non-integer original map')
                gcd = math.gcd(gcd,abs(int(v[k]-origin[k])))
    step = float(gcd or 1)
    extent = max(max(v[k] for p in source for v in p.v)-min(v[k] for p in source for v in p.v) for k in range(3))
    if extent <= 0:raise ValueError('empty world extent')
    if extent > 65532*step:raise ValueError('source lattice cannot fit, do not silently alter original vertices')
    while step/2 >= .0625 and extent <= 65532*(step/2):step /= 2
    if step > 256:raise ValueError('global scale exceeds native budget')
    pages, spans, current = [], [], {}
    page_vertices = 384
    for node in nodes:
        node['first'] = len(spans)
        for poly in sorted(node['polygons'], key=lambda x:x.source):
            for tri in triangles(poly):
                page_id = current.get(poly.material, -1)
                if page_id < 0 or len(pages[page_id]['vertices'])+3 > page_vertices:
                    page_id = len(pages)
                    pages.append(dict(material=poly.material,vertices=[]))
                    current[poly.material] = page_id
                page = pages[page_id]
                first = len(page['vertices'])
                if len(spans) > node['first'] and spans[-1][0] == page_id and spans[-1][1]+spans[-1][2] == first:
                    spans[-1][2] += 3
                else:
                    spans.append([page_id,first,3])
                page['vertices'].extend(tri)
        node['count'] = len(spans)-node['first']
    blobs, max_error, total_triangles = [], 0., 0
    for page in pages:
        blob, _, error = pack_packet(page['material'], page['vertices'],step,origin)
        blobs.append(blob)
        max_error = max(max_error,error)
        total_triangles += len(page['vertices'])//3
        _, n, step, bx, by, bz = struct.unpack_from('>BH4f',blob)
        xyz = struct.unpack_from('>'+str(n*3)+'h',blob,19)
        page['decoded'] = [tuple(xyz[i*3+k]*step+(bx,by,bz)[k] for k in range(3)) for i in range(n)]
    for node in reversed(nodes):
        vertices = [v for page,first,count in spans[node['first']:node['first']+node['count']] for v in pages[page]['decoded'][first:first+count]]
        boxes = [[math.floor(min(v[k] for v in vertices))-4 for k in range(3)] + [math.ceil(max(v[k] for v in vertices))+4 for k in range(3)]]
        boxes += [nodes[c]['bounds'] for c in (node['front'],node['back']) if c >= 0]
        node['bounds'] = [min(b[k] for b in boxes) for k in range(3)] + [max(b[k] for b in boxes) for k in range(3,6)]
    data = struct.pack('>8I',0x51425350,1,source_count,len(nodes),len(pages),len(spans),total_triangles,stats['max_depth'])
    for node in nodes:
        data += struct.pack('>4f10i',*node['plane'],node['front'],node['back'],node['first'],node['count'],*node['bounds'])
    for span in spans:
        data += struct.pack('>3I',*span)
    data += b''.join(blobs)
    stats.update(nodes=len(nodes),resident_pages=len(pages),page_max_vertices=page_vertices,ordered_spans=len(spans),
                 source_triangles=source_count,split_triangles=total_triangles,uploaded_vertices=total_triangles*3,
                 max_position_component_error_world=max_error,max_uv_component_error=1/65536,max_color_component_error=0.5,
                 position_packing='common lattice, exact original vertices, only new cut vertices rounded; NOT pixel-identical',
                 global_position_step_world=step,global_position_origin=list(origin),
                 data_bytes=len(data),data_sha256=hashlib.sha256(data).hexdigest())
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args = parser.parse_args()
    source = read_input(args.input)
    nodes, stats = build(source)
    data = write_tree(nodes,stats,len(source),source)
    stats['input_sha256'] = hashlib.sha256(args.input.read_bytes()).hexdigest()
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_bytes(data)
    args.output.with_suffix('.json').write_text(json.dumps(stats,indent=2)+'\n')
    print(json.dumps(stats,indent=2))


if __name__ == '__main__':
    main()
