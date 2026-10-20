#!/usr/bin/env python3
"""Strict planar remesh: exact integer geometry/Fraction UV maps, no QEM or LOD.
Only complete connected convex atlas patches may be replaced. All boundary
corners are retained; forest and animated river are excluded. Lighting is NOT
part of the proof: the caller explicitly plans a new bake.
"""
from collections import Counter, defaultdict
from fractions import Fraction
from math import gcd
import struct


def read_model(data):
    pos=0
    def take(fmt):
        nonlocal pos
        fmt='>'+fmt
        values=struct.unpack_from(fmt,data,pos);pos+=struct.calcsize(fmt)
        return values[0] if len(values)==1 else values
    if take('i')!=1:raise ValueError('one static village mesh required')
    n=take('h')
    if n<1:raise ValueError('vertices')
    vertices=[take('hhh') for _ in range(n)];faces=[]
    for size in (3,4):
        material=0;count=take('h')
        if count<0:raise ValueError('face count')
        for _ in range(count):
            first=take('h')
            if first==-32768:material=take('h');first=take('h')
            if not 0<=material<=255:raise ValueError('material')
            ids=[first]+[take('h') for _ in range(size-1)]
            if any(i<0 or i>=n for i in ids):raise ValueError('vertex index')
            uv=[take('BB') for _ in range(size)]
            faces.append((material,tuple(zip(reversed(ids),reversed(uv)))))
    if pos!=len(data):raise ValueError('trailing model bytes')
    return vertices,faces


def write_model(vertices,faces):
    result=bytearray(struct.pack('>ih',1,len(vertices)))
    for v in vertices:result.extend(struct.pack('>hhh',*v))
    canonical=[f for size in (3,4) for f in faces if len(f[1])==size]
    for size in (3,4):
        subset=[f for f in canonical if len(f[1])==size];result.extend(struct.pack('>h',len(subset)));material=0
        for mat,corners in subset:
            if material!=mat:result.extend(struct.pack('>hh',-32768,mat));material=mat
            file=tuple(reversed(corners));result.extend(struct.pack('>'+'h'*size,*(c[0] for c in file)))
            for _,uv in file:result.extend(bytes(uv))
    # Preserve logical native order across .3d's separate tri/quad sections.
    ids=[i for size in (3,4) for i,f in enumerate(faces) if len(f[1])==size];inverse={i:j for j,i in enumerate(ids)}
    order=bytearray(struct.pack('>i',len(faces)));last=0
    for i in range(len(faces)):
        index=inverse[i];order.extend(struct.pack('>h',index-last));last=index
    return bytes(result),bytes(order)


def read_order(data,n):
    if len(data)!=4+2*n or struct.unpack_from('>i',data)[0]!=n:raise ValueError('draw order size')
    order=[];last=0
    for delta, in struct.iter_unpack('>h',data[4:]):last+=delta;order.append(last)
    if sorted(order)!=list(range(n)):raise ValueError('draw order permutation')
    return order


def sub(a,b):return tuple(x-y for x,y in zip(a,b))
def cross(a,b):return (a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
def dot(a,b):return sum(x*y for x,y in zip(a,b))
def triangles(c):return (c,) if len(c)==3 else ((c[0],c[1],c[3]),(c[3],c[1],c[2]))


def triangle_key(vertices,tri):
    points=[vertices[c[0]] for c in tri];normal=cross(sub(points[1],points[0]),sub(points[2],points[0]));g=gcd(*normal)
    if not g:return None
    normal=tuple(v//g for v in normal);drop=max(range(3),key=lambda k:abs(normal[k]));axes=[k for k in range(3) if k!=drop]
    a,b,c=[tuple(p[k] for k in axes) for p in points];det=(b[0]-a[0])*(c[1]-a[1])-(c[0]-a[0])*(b[1]-a[1]);affine=[]
    for channel in (0,1):
        u,v,w=[corner[1][channel] for corner in tri]
        x=Fraction((v-u)*(c[1]-a[1])-(w-u)*(b[1]-a[1]),det)
        y=Fraction((b[0]-a[0])*(w-u)-(c[0]-a[0])*(v-u),det)
        affine.extend((x,y,u-x*a[0]-y*a[1]))
    return (normal,dot(normal,points[0]),tuple(axes),tuple(affine))


def corner_key(vertices,c):return (vertices[c[0]],c[1])
def turn(a,b,c):return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])
def area2(points):return sum(a[0]*b[1]-b[0]*a[1] for a,b in zip(points,points[1:]+points[:1]))


def strict_patch(vertices,faces,indices,key):
    """Fail closed unless complete faces tile one simple strictly convex boundary.
    No boundary-vertex removal (no new T junctions), holes or nonplanar merging.
    """
    edges=Counter();corners={};project=lambda k:tuple(k[0][i] for i in key[2])
    source_area=0
    for i in indices:
        cs=faces[i][1];points=[corner_key(vertices,c) for c in cs];xy=[project(p) for p in points]
        sign=1 if area2(xy)>0 else -1
        if any(turn(xy[k-1],xy[k],xy[(k+1)%len(xy)])*sign<=0 for k in range(len(xy))):return None
        source_area+=area2(xy)
        for c,k in zip(cs,points):corners[k]=c
        for a,b in zip(points,points[1:]+points[:1]):edges[(a,b)]+=1
    boundary=[]
    for (a,b),count in edges.items():
        opposite=edges[(b,a)]
        if count!=1 or opposite>1:return None
        if not opposite:boundary.append((a,b))
    successors={a:b for a,b in boundary}
    if len(successors)!=len(boundary) or not boundary:return None
    loop=[min(successors)];seen={loop[0]}
    while True:
        nxt=successors.get(loop[-1])
        if nxt==loop[0]:break
        if nxt is None or nxt in seen:return None
        loop.append(nxt);seen.add(nxt)
    if len(loop)!=len(boundary) or len(loop)<4:return None
    xy=[project(k) for k in loop];total_area=area2(xy);sign=1 if total_area>0 else -1
    if not total_area or total_area!=source_area:return None
    if any(turn(xy[k-1],xy[k],xy[(k+1)%len(xy)])*sign<=0 for k in range(len(xy))):return None
    # Convex quads on a fan, retaining every single boundary corner. Native ABD/
    # DBC diagonal is safe because plane and BOTH affine UV maps are exact.
    result=[];p=1
    while p+2<len(loop):
        result.append((1,tuple(corners[loop[k]] for k in (0,p,p+1,p+2))));p+=2
    if p+1<len(loop):result.append((1,tuple(corners[loop[k]] for k in (0,p,p+1))))
    before=sum(len(faces[i][1])-2 for i in indices);after=sum(len(cs)-2 for _,cs in result)
    if after>=before or sum(len(cs) for _,cs in result)>=sum(len(faces[i][1]) for i in indices):return None
    if any(triangle_key(vertices,t)!=key for _,cs in result for t in triangles(cs)):return None
    return result


def simplify(data,draw_order):
    vertices,file_faces=read_model(data);order=read_order(draw_order,len(file_faces));faces=[file_faces[i] for i in order]
    groups=defaultdict(list)
    for i,(material,cs) in enumerate(faces):
        if material!=1:continue
        keys=[triangle_key(vertices,t) for t in triangles(cs)]
        if keys[0] is not None and all(k==keys[0] for k in keys):groups[keys[0]].append(i)
    replacements={};removed=set();patches=[]
    for key,group in groups.items():
        # Split disconnected islands using oppositely oriented shared edges.
        edge_faces=defaultdict(list)
        for i in group:
            points=[corner_key(vertices,c) for c in faces[i][1]]
            for a,b in zip(points,points[1:]+points[:1]):edge_faces[frozenset((a,b))].append(i)
        adjacency=defaultdict(set)
        for sharing in edge_faces.values():
            if len(sharing)==2:a,b=sharing;adjacency[a].add(b);adjacency[b].add(a)
        pending=set(group)
        while pending:
            stack=[min(pending)];component=set()
            while stack:
                i=stack.pop()
                if i in component:continue
                component.add(i);pending.discard(i);stack.extend(adjacency[i]-component)
            indices=sorted(component)
            if len(indices)<2:continue
            result=strict_patch(vertices,faces,indices,key)
            if result is None:continue
            replacements[indices[0]]=result;removed.update(indices)
            patches.append(dict(source_native_face_ordinals=indices,source_file_face_ordinals=[order[i] for i in indices],before_triangles=sum(len(faces[i][1])-2 for i in indices),after_triangles=sum(len(cs)-2 for _,cs in result),boundary_preserved=True,uv_exact=True,plane=list(key[0])+[key[1]],replacement_corners=[[[int(c[0]),list(c[1])] for c in cs] for _,cs in result]))
    result=[]
    for i,face in enumerate(faces):
        if i in replacements:result.extend(replacements[i])
        elif i not in removed:result.append(face)
    model,order_data=write_model(vertices,result)
    stats=lambda fs:dict(vertices=len(vertices),faces=len(fs),triangles=sum(len(cs)-2 for _,cs in fs),corners=sum(len(cs) for _,cs in fs),material_faces=dict(sorted(Counter(m for m,_ in fs).items())))
    report=dict(algorithm='exact convex planar patches + exact rational affine UV; boundary corners locked; no geometric tolerance/QEM/occlusion deletion',before=stats(faces),after=stats(result),patches=patches,protected_materials=[0,2],all_source_positions_retained=True,lighting='requires rebake; diagnostic uses identical neutral255 vertex light in both modes',warning='Very small conservative reduction; no handset FPS benefit promised.')
    return model,order_data,report
