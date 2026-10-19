#!/usr/bin/env python3
"""Lossless, dedicated kordos material-slot remap. No decimation/reordering/UV edit.
Only signed-short material-marker payloads change: 0->0, 1/2/3->1, 4->2.
A delta permutation preserves legacy native submission order at startup AFTER
loading the unchanged baked lightmap. The file face order itself never changes.
"""
from collections import Counter
import struct

MODEL_ENTRY = 'maps/kordos/kordos.3d'
ORDER_ENTRY = 'maps/kordos/kordos.draworder'
REMAP = {0: 0, 1: 1, 2: 1, 3: 1, 4: 2}
REMOVE_LINES = [b'/maps/kordos/map-selo1.png,std,-3500\r\n',
                b'/maps/kordos/map-selo1.png,std,-7000\r\n']


def merge_config(data):
    begin, end = b'[WTEX_BEGIN]\r\n', b'[WTEX_END]'
    if data.count(begin) != 1 or data.count(end) != 1:
        raise ValueError('Unexpected WTEX boundaries')
    start = data.index(begin) + len(begin)
    stop = data.index(end, start)
    block = data[start:stop]
    lines = block.splitlines()
    if len(lines) != 5 or [line.split(b',')[0] for line in lines[1:4]] != [b'/maps/kordos/map-selo1.png'] * 3:
        raise ValueError('Expected exactly 5 slots / three atlas references')
    for line in REMOVE_LINES:
        if block.count(line) != 1:
            raise ValueError('Unexpected duplicate atlas options')
        block = block.replace(line, b'')
    return data[:start] + block + data[stop:]


def merge_model(data):
    patched = bytearray(data)
    pos = 0
    markers, materials = [], []

    def read(fmt):
        nonlocal pos
        size = struct.calcsize(fmt)
        if pos + size > len(data):
            raise ValueError('Truncated model')
        values = struct.unpack_from(fmt, data, pos)
        pos += size
        return values[0] if len(values) == 1 else values

    if read('>i') != 1:
        raise ValueError('This draw-order format is for a single room mesh')
    vertices = read('>h')
    if vertices < 0:
        raise ValueError('Negative vertex count')
    for _ in range(vertices):
        read('>hhh')
    sizes = []
    for corners in (3, 4):
        count = read('>h')
        if count < 0:
            raise ValueError('Negative face count')
        material = 0
        for _ in range(count):
            first = read('>h')
            if first == -32768:
                offset = pos
                material = read('>h')
                if material not in REMAP:
                    raise ValueError('Unsupported source material')
                markers.append(offset)
                struct.pack_into('>h', patched, offset, REMAP[material])
                first = read('>h')
            indices = [first] + [read('>h') for _ in range(corners - 1)]
            if any(i < 0 or i >= vertices for i in indices):
                raise ValueError('Invalid vertex index')
            for _ in range(corners):
                read('>BB')
            materials.append(material)
            sizes.append(corners)
    if pos != len(data) or len(materials) > 32767:
        raise ValueError('Trailing bytes or unsupported face count')
    permutation = sorted(range(len(materials)), key=lambda i: materials[i])
    order = bytearray(struct.pack('>i', len(permutation)))
    previous = 0
    for index in permutation:
        order.extend(struct.pack('>h', index - previous))
        previous = index
    allowed = {byte for offset in markers for byte in (offset, offset + 1)}
    if any(a != b and i not in allowed for i, (a, b) in enumerate(zip(data, patched))):
        raise AssertionError('Bytes outside material markers changed')
    return bytes(patched), bytes(order), dict(
        remap=REMAP, vertices=vertices, faces=len(materials),
        triangles=sum(c - 2 for c in sizes), corners=sum(sizes),
        old_material_faces=dict(Counter(materials)),
        new_material_faces=dict(Counter(REMAP[m] for m in materials)),
        material_marker_offsets=markers, face_order_unchanged=True,
        xyz_uv_topology_bytes_unchanged=True, lightmap_bytes_unchanged=True,
        native_submission_order_restored_after_lightmap=True)
