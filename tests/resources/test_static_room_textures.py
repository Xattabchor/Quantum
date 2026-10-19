"""Slot-only map edit: independent binary decode + archive and river identity."""
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import sys
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from build_cldc import audit_jar
from merge_room_texture_slots import merge_config, merge_model, REMAP, MODEL_ENTRY, ORDER_ENTRY
spec = importlib.util.spec_from_file_location('textures', ROOT / 'scripts/build_static_room_textures.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


def decode(data):
    pos = 0
    def read(fmt):
        nonlocal pos
        out = struct.unpack_from(fmt, data, pos)
        pos += struct.calcsize(fmt)
        return out[0] if len(out) == 1 else out
    assert read('>i') == 1
    vertices = [read('>hhh') for _ in range(read('>h'))]
    faces = []
    for size in (3, 4):
        material = 0
        for _ in range(read('>h')):
            first = read('>h')
            if first == -32768:
                material, first = read('>h'), read('>h')
            ids = (first,) + tuple(read('>h') for _ in range(size - 1))
            uv = tuple(read('>BB') for _ in range(size))
            faces.append((material, ids, uv))
    assert pos == len(data)
    return vertices, faces


def block(data):
    return data.split(b'[WTEX_BEGIN]\r\n')[1].split(b'[WTEX_END]')[0].splitlines()


class TextureSlotsTest(unittest.TestCase):
    def test_slots_material_markers_and_order(self):
        with zipfile.ZipFile(builder.BASE) as old, zipfile.ZipFile(ROOT/'downloads/Quantum_StaticRoom_Tex3.jar') as new:
            source, target = old.read(MODEL_ENTRY), new.read(MODEL_ENTRY)
            a, faces = decode(source)
            b, actual = decode(target)
            self.assertEqual(a, b)
            self.assertEqual(len(faces), 2641)
            self.assertEqual([(REMAP[m], ids, uv) for m, ids, uv in faces], actual)
            self.assertEqual(sum(len(ids)-2 for _,ids,_ in actual),4750)
            self.assertEqual(sum(len(ids) for _,ids,_ in actual),10032)
            self.assertEqual({m for m,_,_ in actual},{0,1,2})
            patched, order, report = merge_model(source)
            self.assertEqual(target,patched)
            self.assertEqual(order,new.read(ORDER_ENTRY))
            allowed={byte for offset in report['material_marker_offsets'] for byte in (offset,offset+1)}
            self.assertEqual(len(source),len(target))
            self.assertTrue(all(x==y or i in allowed for i,(x,y) in enumerate(zip(source,target))))
            count=struct.unpack_from('>i',order)[0]
            permutation=[]; index=0
            for i in range(count):
                index+=struct.unpack_from('>h',order,4+2*i)[0];permutation.append(index)
            self.assertEqual(permutation,sorted(range(len(faces)),key=lambda i:faces[i][0]))
            self.assertEqual(len(set(permutation)),2641)
            old_config,new_config=old.read('level1.txt'),new.read('level1.txt')
            self.assertEqual(new_config,merge_config(old_config))
            before,after=block(old_config),block(new_config)
            self.assertEqual(after,[before[0],before[1],before[4]])
            for token in (b'[WTEX_BEGIN]',b'[WTEX_END]'):
                part=0 if token==b'[WTEX_BEGIN]' else 1
                self.assertEqual(old_config.split(token)[part],new_config.split(token)[part])
            self.assertEqual(after[2],before[4]) # River line: paths, speed6, mode7 untouched.
            for name in old.namelist():
                if name not in ('META-INF/MANIFEST.MF','level1.txt',MODEL_ENTRY):
                    self.assertEqual(new.read(name),old.read(name),name)
            self.assertEqual(new.read('lightmap/kordod.vla'),old.read('lightmap/kordod.vla'))
            self.assertFalse(any(name.startswith('code/Rendering/StaticRoomAlpha') for name in new.namelist()))
            self.assertEqual(set(new.namelist()),set(old.namelist())|builder.NEW_CLASSES|{ORDER_ENTRY})
            self.assertIsNone(new.testzip())
        for malformed in (source[:3], source[:-1], source+b'\0'):
            with self.assertRaises((ValueError,struct.error)):
                merge_model(malformed)
        with self.assertRaises(ValueError):
            merge_config(old_config.replace(b'map-selo1.png,std,-3500',b'other.png,std,-3500'))

    def test_manifest_source_hashes_and_cldc(self):
        jar=ROOT/'downloads/Quantum_StaticRoom_Tex3.jar'
        report=json.loads(jar.with_suffix('.build-report.json').read_text())
        self.assertEqual(hashlib.sha256(builder.BASE.read_bytes()).hexdigest(),builder.BASE_SHA)
        self.assertEqual(hashlib.sha256(jar.read_bytes()).hexdigest(),report['jar_sha256'])
        self.assertEqual(jar.stat().st_size,report['jar_bytes'])
        for key,value in audit_jar(jar).items():
            self.assertEqual(value,report[key])
        self.assertEqual(report['classes'],182)
        self.assertFalse(report['handset_tested'])
        with zipfile.ZipFile(jar) as archive:
            for name,value in report['artifact_resource_sha256'].items():
                self.assertEqual(hashlib.sha256(archive.read(name)).hexdigest(),value)
        for name,value in report['transform_source_sha256'].items():
            self.assertEqual(hashlib.sha256((ROOT/name).read_bytes()).hexdigest(),value)
        for name,value in report['source_sha256'].items():
            self.assertEqual(hashlib.sha256((ROOT/name).read_bytes()).hexdigest(),value)
        jad=jar.with_suffix('.jad').read_bytes()
        self.assertIn(b'code.Rendering.StaticRoomTexturesPreview',jad)
        self.assertIn(('MIDlet-Jar-Size: %d\r\n'%jar.stat().st_size).encode(),jad)


if __name__=='__main__':
    unittest.main()
