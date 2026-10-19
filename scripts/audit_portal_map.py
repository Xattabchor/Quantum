#!/usr/bin/env python3
"""Validate the user's exact portal-map archive independently of the Java loader."""
from pathlib import Path
import collections
import hashlib
import struct
import zipfile

ROOT=Path(__file__).resolve().parents[1]
ARCHIVE=ROOT/'fixtures/portal-map/lightmap.zip'
ARCHIVE_SHA='16beaa29fe143cc4010b66e21d90c729a0f83c5f2f3f282b04c17c96577b1ddf'
ASSETS={'level1.txt':'portal-map/level1.txt','kordon.3d':'portal-map/maps/kordon.3d','kordon.png':'portal-map/maps/kordon.png','kordon.vla':'portal-map/lightmap/kordon.vla','skybox5.3d':'portal-map/skies/skybox5.3d','n.png':'portal-map/skies/n.png'}


def audit(path=ARCHIVE):
    raw=Path(path).read_bytes()
    if hashlib.sha256(raw).hexdigest()!=ARCHIVE_SHA:raise ValueError('wrong user archive')
    with zipfile.ZipFile(path) as z:
        if len(z.namelist())!=len(ASSETS) or set(z.namelist())!=set(ASSETS) or z.testzip():raise ValueError('unexpected archive members')
        assets={target:z.read(name) for name,target in ASSETS.items()}
    data=assets['portal-map/maps/kordon.3d'];offset=0
    def read(fmt):
        nonlocal offset
        result=struct.unpack_from('>'+fmt,data,offset);offset+=struct.calcsize('>'+fmt)
        return result[0] if len(result)==1 else result
    rooms=[]
    for r in range(read('i')):
        vertices=[tuple(x*6 for x in read('hhh')) for _ in range(read('h'))];faces=[]
        for count in (3,4):
            mat=0
            for f in range(read('h')):
                first=read('h')
                if first==-32768:mat=read('h');first=read('h')
                indices=[first]+[read('h') for _ in range(count-1)];uv=read('b'*(count*2))
                if mat!=0 or any(i<0 or i>=len(vertices) for i in indices):raise ValueError('invalid material/index')
                faces.append(indices)
        rooms.append((vertices,faces))
    if offset!=len(data) or len(rooms)!=16:raise ValueError('model length/room count')
    links=[]
    for face in rooms[-1][1]:
        portal=[rooms[-1][0][i] for i in face]
        matched=[r for r,(vertices,_) in enumerate(rooms[:-1]) if any(all(abs(a[k]-b[k])<=25 for k in range(3)) for a in portal for b in vertices)]
        if len(matched)!=2:raise ValueError('ambiguous/unpaired portal')
        links.append(matched)
    reachable={0}
    while True:
        newer=reachable|{r for edge in links if any(v in reachable for v in edge) for r in edge}
        if newer==reachable:break
        reachable=newer
    if len(reachable)!=15:raise ValueError('disconnected portal graph')
    light=assets['portal-map/lightmap/kordon.vla'];at=0;rgb=gray=0
    for vertices,faces in rooms:
        for face in faces:
            at+=len(face);flag=light[at];at+=1
            if flag not in (0,1):raise ValueError('lightmap flag')
            if flag:at+=len(face)*2;rgb+=1
            else:gray+=1
    if at!=len(light):raise ValueError('lightmap does not cover all meshes exactly')
    config=assets['portal-map/level1.txt'].decode('cp1251')
    for line in ('DMODE=13','DIST=40000','WORLD_SCALE=6','START=-76338,-2536,-102034'):
        if line not in config.splitlines():raise ValueError('wrong config')
    shapes={name:struct.unpack_from('>II',assets[name],16) for name in ('portal-map/maps/kordon.png','portal-map/skies/n.png')}
    report=dict(archive_sha256=ARCHIVE_SHA,archive_bytes=len(raw),rooms=15,portals=len(links),links=links,
                triangles_by_room=[sum(len(f)-2 for f in faces) for _,faces in rooms[:-1]],
                corners_by_room=[sum(len(f) for f in faces) for _,faces in rooms[:-1]],
                source_room_vertices=[len(v) for v,_ in rooms[:-1]],
                world_triangles=sum(len(f)-2 for _,faces in rooms[:-1] for f in faces),
                portal_mesh_triangles=sum(len(f)-2 for f in rooms[-1][1]),
                lightmap_rgb_faces=rgb,lightmap_gray_faces=gray,lightmap_bytes=len(light),
                image_dimensions=shapes,config=dict(DMODE=13,DIST=40000,WORLD_SCALE=6,START=[-76338,-2536,-102034]),
                assets_sha256={name:hashlib.sha256(value).hexdigest() for name,value in sorted(assets.items())})
    return assets,report


if __name__=='__main__':
    import json
    print(json.dumps(audit()[1],indent=2))
