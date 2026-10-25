#!/usr/bin/env python3
"""Decimate a Quantum-engine .3d file: vertex weld + coplanar tri->quad merge
+ sub-world-area detail drop. Silhouette-safe defaults (drops only polys far
below visibility scale). Writes a valid file (same format, big-endian).

Usage: decimate3d.py IN.3d OUT.3d [--scale 6] [--min-area 1e5] [--no-merge]
"""
import struct, sys, math
from collections import defaultdict

def read3d(path):
    d = open(path, 'rb').read()
    o = [4]
    def rs():
        v = struct.unpack_from('>h', d, o[0])[0]; o[0] += 2; return v
    meshes = []
    nm = struct.unpack_from('>i', d, 0)[0]
    for _ in range(nm):
        nv = rs()
        verts = [(rs(), rs(), rs()) for _ in range(nv)]
        def polys(n):
            cnt = rs(); out = []; mat = 0
            for _ in range(cnt):
                va = rs()
                if va == -32768:
                    mat = rs(); va = rs()
                idx = [va] + [rs() for _ in range(n - 1)]
                uv = list(d[o[0]:o[0] + 2 * n]); o[0] += 2 * n
                out.append((mat, idx, uv))
            return out
        tris = polys(3); quads = polys(4)
        meshes.append([verts, tris, quads])
    assert o[0] == len(d), (o[0], len(d))
    return meshes

def area2x2(v0, v1, v2):  # squared doubled area
    ux, uy, uz = v1[0]-v0[0], v1[1]-v0[1], v1[2]-v0[2]
    wx, wy, wz = v2[0]-v0[0], v2[1]-v0[1], v2[2]-v0[2]
    cx, cy, cz = uy*wz-uz*wy, uz*wx-ux*wz, ux*wy-uy*wx
    return cx*cx + cy*cy + cz*cz

def plane_key(verts, idx, mat, eps_deg=2.0):
    # normals quantized to ~2 degrees + plane shift bucket (per material)
    v0, v1, v2 = verts[idx[0]], verts[idx[1]], verts[idx[2]]
    ux, uy, uz = v1[0]-v0[0], v1[1]-v0[1], v1[2]-v0[2]
    wx, wy, wz = v2[0]-v0[0], v2[1]-v0[1], v2[2]-v0[2]
    nx, ny, nz = uy*wz-uz*wy, uz*wx-ux*wz, ux*wy-uy*wx
    l = math.sqrt(nx*nx + ny*ny + nz*nz)
    if l == 0: return ('deg', mat)
    k = 1.0 / (l * math.tan(math.radians(eps_deg)))
    qn = (round(nx*k), round(ny*k), round(nz*k))
    dp = (nx*v0[0] + ny*v0[1] + nz*v0[2]) / l
    return (mat, qn, round(dp / 4.0))  # 4 raw units shift tolerance

def decimate(meshes, scale, min_area, do_merge, vla=None, mat_map=None):
    out_meshes = []
    stats = defaultdict(int)
    new_vla = bytearray()
    for verts, tris, quads in meshes:
        if mat_map:
            # Remap material slots to a shortened WTEX list (e.g. the sym9
            # map references slots 2/3 (std ground layers, intentionally cut
            # by the user) and 4 (animated river) -> user layout 0..2).
            tris = [(mat_map.get(m, m), ix, uv) for m, ix, uv in tris]
            quads = [(mat_map.get(m, m), ix, uv) for m, ix, uv in quads]
            # no reordering: polygon order must stay stable for the .vla sync
        # per-poly baked light corner values (DMODE=9: 3 bytes/tri, 4/quad)
        tri_lm = qua_lm = None
        if vla is not None:
            tri_lm = vla[:3 * len(tris)]
            qua_lm = vla[3 * len(tris):3 * len(tris) + 4 * len(quads)]
            assert len(qua_lm) == 4 * len(quads)
            vla = vla[3 * len(tris) + 4 * len(quads):]
        # 1) exact weld
        remap = {}; newverts = []
        for i, v in enumerate(verts):
            if v not in remap:
                remap[v] = len(newverts); newverts.append(v)
            remap[i] = remap[v]
        def fix(idx): return [remap[i] for i in idx]
        tris = [(m, fix(ix), uv) for m, ix, uv in tris]
        quads = [(m, fix(ix), uv) for m, ix, uv in quads]
        v0 = len(verts)
        stats['verts_welded'] += v0 - len(newverts)

        # 2) drop micro polys (world area below threshold)
        def warea(idx):
            a = math.sqrt(area2x2(newverts[idx[0]], newverts[idx[1]],
                                  newverts[idx[2]])) / 2 * scale * scale
            if len(idx) == 4:
                a += math.sqrt(area2x2(newverts[idx[0]], newverts[idx[2]],
                                       newverts[idx[3]])) / 2 * scale * scale
            return a
        if min_area > 0:
            keep_tri = []
            for pi, p in enumerate(tris):
                ok = warea(p[1]) >= min_area
                if not ok: stats['polys_dropped'] += 1
                if ok: keep_tri.append(pi)
            keep_qua = []
            for pi, p in enumerate(quads):
                ok = warea(p[1]) >= min_area
                if not ok: stats['polys_dropped'] += 1
                if ok: keep_qua.append(pi)
        else:
            keep_tri = list(range(len(tris)))
            keep_qua = list(range(len(quads)))
        tris = [tris[pi] for pi in keep_tri]
        quads = [quads[pi] for pi in keep_qua]
        if tri_lm is not None:
            tri_lm = bytes(b for pi in keep_tri for b in tri_lm[3*pi:3*pi+3])
            qua_lm = bytes(b for pi in keep_qua for b in qua_lm[4*pi:4*pi+4])

        # 3) coplanar tri+tri -> quad on a shared edge (uv must agree)
        merged_out = []
        merged_lm = []       # synthesized 4-byte corner values per merged quad
        merged_keep_lm = None
        consumed = set()
        if do_merge:
            by_edge = defaultdict(list)
            for pi, (m, ix, uv) in enumerate(tris):
                for e, (a, b) in enumerate(((0,1),(1,2),(2,0))):
                    by_edge[(m, tuple(sorted((ix[a], ix[b]))))].append((pi, e))
            consumed = set()
            for key, lst in by_edge.items():
                if len(lst) != 2: continue
                (p1, e1), (p2, e2) = lst
                if p1 in consumed or p2 in consumed: continue
                m1, ix1, uv1 = tris[p1]
                m2, ix2, uv2 = tris[p2]
                # coplanar same material?
                if plane_key(newverts, ix1, m1) != plane_key(newverts, ix2, m2):
                    continue
                # apex is the vertex NOT on the shared edge: for edge e=(a,b)
                # the remaining corner index is 3-a-b (tri indices 0..2).
                pairs = ((0, 1), (1, 2), (2, 0))
                pe1 = pairs[e1]; pe2 = pairs[e2]
                ap1 = ix1[3 - pe1[0] - pe1[1]]
                ap2 = ix2[3 - pe2[0] - pe2[1]]
                a1, b1 = ix1[pe1[0]], ix1[pe1[1]]
                uvmap1 = {ix1[pe1[0]]: (uv1[2*pe1[0]], uv1[2*pe1[0]+1]),
                          ix1[pe1[1]]: (uv1[2*pe1[1]], uv1[2*pe1[1]+1]),
                          ap1: (uv1[2*(3-pe1[0]-pe1[1])], uv1[2*(3-pe1[0]-pe1[1])+1])}
                uvmap2 = {ap2: (uv2[2*(3-pe2[0]-pe2[1])], uv2[2*(3-pe2[0]-pe2[1])+1])}
                # shared verts' uv must agree
                ok = True
                for sv in (a1, b1):
                    j1 = ix1.index(sv); j2 = ix2.index(sv)
                    if (uv1[2*j1], uv1[2*j1+1]) != (uv2[2*j2], uv2[2*j2+1]):
                        ok = False; break
                if not ok: continue
                seq = [a1, b1, ap2, ap1]  # convex quad around the shared diagonal
                # convexity: 4 corners, no reflex angle (same-side normals)
                vseq = [newverts[i] for i in seq]
                nn = []
                okc = True
                for i in range(4):
                    pA, pB, pC = vseq[i], vseq[(i+1) % 4], vseq[(i+2) % 4]
                    ux, uy, uz = pB[0]-pA[0], pB[1]-pA[1], pB[2]-pA[2]
                    wx, wy, wz = pC[0]-pB[0], pC[1]-pB[1], pC[2]-pB[2]
                    nn.append((uy*wz-uz*wy, uz*wx-ux*wz, ux*wy-uy*wx))
                n0 = nn[0]
                for n in nn[1:]:
                    if n0[0]*n[0]+n0[1]*n[1]+n0[2]*n[2] < 0: okc = False; break
                if not okc: continue
                uvsq = []
                for i in seq:
                    uu = uvmap1.get(i) or uvmap2.get(i)
                    uvsq += [uu[0] & 0xff, uu[1] & 0xff]
                merged_out.append((m1, seq, uvsq))
                consumed.add(p1); consumed.add(p2)
                stats['tri_pairs_merged'] += 1
                # synthesize the quad's 4 corner light bytes from the two tris
                if tri_lm is not None:
                    lmq = []
                    for i in seq:
                        if i in ix1:
                            lmq.append(tri_lm[3*p1 + ix1.index(i)])
                        else:
                            lmq.append(tri_lm[3*p2 + ix2.index(i)])
                    merged_lm.append(lmq)
        merged_keep_lm = [pi for pi in range(len(tris)) if pi not in consumed]
        tris = [p for pi, p in enumerate(tris) if pi not in consumed]
        # baked light for dropped/merged polys stays in sync (DMODE=9 records)
        if tri_lm is not None and merged_keep_lm is not None:
            tri_lm = bytes(b for pi in merged_keep_lm for b in tri_lm[3*pi:3*pi+3])
        quads = quads + merged_out
        if qua_lm is not None and merged_lm:
            qua_lm = qua_lm + bytes(b for rec in merged_lm for b in rec)

        # drop degenerate/never-referenced verts compactly
        used = sorted({i for _, ix, _ in tris + quads for i in ix})
        remap2 = {old: i for i, old in enumerate(used)}
        newverts = [newverts[i] for i in used]
        tris = [(m, [remap2[i] for i in ix], uv) for m, ix, uv in tris]
        quads = [(m, [remap2[i] for i in ix], uv) for m, ix, uv in quads]
        out_meshes.append((newverts, tris, quads))
        stats['verts_final'] += len(newverts); stats['tris_final'] += len(tris)
        stats['quads_final'] += len(quads)
        if tri_lm is not None:
            new_vla += tri_lm + qua_lm
    return out_meshes, stats, bytes(new_vla)

def write3d(path, meshes):
    b = bytearray()
    b += struct.pack('>i', len(meshes))
    for verts, tris, quads in meshes:
        b += struct.pack('>h', len(verts))
        for v in verts: b += struct.pack('>hhh', *v)
        for pols, n in ((tris, 3), (quads, 4)):
            b += struct.pack('>h', len(pols))
            last_mat = 0; first = True
            for m, ix, uv in pols:
                if first or m != last_mat:
                    b += struct.pack('>hh', -32768, m)
                    last_mat = m; first = False
                b += struct.pack('>%dh' % n, *ix)
                b += bytes(uv)
    open(path, 'wb').write(bytes(b))

def main():
    src, dst = sys.argv[1], sys.argv[2]
    scale = float(sys.argv[sys.argv.index('--scale') + 1]) if '--scale' in sys.argv else 6.0
    min_area = float(sys.argv[sys.argv.index('--min-area') + 1]) if '--min-area' in sys.argv else 1e5
    do_merge = '--no-merge' not in sys.argv
    mat_map = None
    if '--mat-map' in sys.argv:
        mat_map = dict((int(a), int(b)) for a, b in
                       (p.split(':') for p in
                        sys.argv[sys.argv.index('--mat-map') + 1].split(',')))
    meshes = read3d(src)
    total0 = sum(len(t) + len(q) for _, t, q in meshes)
    verts0 = sum(len(v) for v, _, _ in meshes)
    vla = None
    if '--vla' in sys.argv:
        vla = open(sys.argv[sys.argv.index('--vla') + 1], 'rb').read()
    out, stats, new_vla = decimate(meshes, scale, min_area, do_merge, vla, mat_map)
    write3d(dst, out)
    if '--vla' in sys.argv:
        open(sys.argv[sys.argv.index('--vla') + 2], 'wb').write(new_vla)
        print(f'vla: {len(vla)} -> {len(new_vla)} bytes')
    total1 = stats['tris_final'] + stats['quads_final']
    print(f'in : verts={verts0} polys={total0}')
    for k, v in stats.items(): print(f'  {k}: {v}')
    print(f'out: verts={stats["verts_final"]} polys={total1} '
          f'(-{100 * (1 - total1 / total0):.1f}% polys)')

if __name__ == '__main__':
    main()
