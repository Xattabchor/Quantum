#!/usr/bin/env python3
"""Analyze / decimate a .3d mesh file (Quantum engine format).

Format (big-endian, DataInputStream):
  int32 meshCount
  per mesh:
    int16 vertexCount; per vertex: int16 x,y,z (raw units, *scale at runtime)
    int16 triCount;   per tri:  [int16 -32768 marker -> int16 material]
                                int16 a,b,c + 6 uv bytes
    int16 quadCount;  per quad: [marker -> material] int16 a..d + 8 uv bytes

Usage:
  analyze3d.py FILE.3d [--scale N]          stats only
  analyze3d.py FILE.3d --scale N --merge out.3d   weld + tri->quad merge
"""
import struct, sys
from collections import defaultdict

def read3d(path):
    d = open(path, 'rb').read()
    off = [0]
    def ri(n):
        v = struct.unpack_from(('>h' if n == 2 else '>i'), d, off[0])[0]
        off[0] += n
        return v
    meshes = []
    nmeshes = ri(4)
    for _ in range(nmeshes):
        nv = ri(2)
        verts = [(ri(2), ri(2), ri(2)) for _ in range(nv)]
        def polys(n):
            out = []
            mat = 0
            for _ in range(n):
                va = ri(2)
                if va == -32768:
                    mat = ri(2); va = ri(2)
                idx = [va] + [ri(2) for _ in range(n - 1)]
                nu = (2 * n)
                uv = list(d[off[0]:off[0] + nu]); off[0] += nu
                out.append((mat, idx, uv))
            return out
        tris = polys(3)
        quads = polys(4)
        meshes.append((verts, tris, quads))
    assert off[0] == len(d), (off[0], len(d))
    return meshes

def area2(v0, v1, v2):
    ux, uy, uz = v1[0]-v0[0], v1[1]-v0[1], v1[2]-v0[2]
    wx, wy, wz = v2[0]-v0[0], v2[1]-v0[1], v2[2]-v0[2]
    cx, cy, cz = uy*wz-uz*wy, uz*wx-ux*wz, ux*wy-uy*wx
    return (cx*cx + cy*cy + cz*cz)  # squared doubled area

def main():
    path = sys.argv[1]
    scale = 6.0
    if '--scale' in sys.argv:
        scale = float(sys.argv[sys.argv.index('--scale') + 1])
    meshes = read3d(path)
    tot_t = tot_q = 0
    for mi, (verts, tris, quads) in enumerate(meshes):
        print(f'mesh[{mi}]: verts={len(verts)} tris={len(tris)} quads={len(quads)}')
        hist = defaultdict(int)
        mats = defaultdict(int)
        for mat, idx, uv in tris + quads:
            mats[mat] += 1
            a2 = area2(verts[idx[0]], verts[idx[1]], verts[idx[2]])
            aw = (a2 ** 0.5) / 2 * scale * scale  # world area
            # bucket by log10
            import math
            b = int(math.log10(max(aw, 1)))
            hist[b] += 1
        tot_t += len(tris); tot_q += len(quads)
        for b in sorted(hist):
            print(f'  area 10^{b}..10^{b+1} (world): {hist[b]}')
        print('  by material:', dict(sorted(mats.items())))
    print(f'TOTAL polys={tot_t + tot_q} (tris={tot_t}, quads={tot_q})')
    # coplanar same-material adjacency potential: shared-edge count
    for mi, (verts, tris, quads) in enumerate(meshes):
        edges = defaultdict(list)
        for pi, (mat, idx, uv) in enumerate(tris):
            for a, b in ((0,1),(1,2),(2,0)):
                e = tuple(sorted((idx[a], idx[b])))
                edges[e].append(pi)
    shared = sum(1 for v in edges.values() if len(v) == 2)
    print(f'tri edges shared by exactly 2 tris: {shared}')

if __name__ == '__main__':
    main()
