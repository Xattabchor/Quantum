#!/usr/bin/env python3
import struct, sys, zipfile
BRANCH_OPS = set(range(0x99, 0xA9)) | {0xAA, 0xAB, 0xC6, 0xC7, 0xA9, 0xA8, 0xC8, 0xC9}
OP_LEN = {op: 0 for op in range(256)}
OP_LEN[0x10] = 1
for op in [0x12,0x15,0x16,0x17,0x18,0x19,0x36,0x37,0x38,0x39,0x3A,0xA9,0xBC]: OP_LEN[op] = 1
for op in [0x11,0x99,0x9A,0x9B,0x9C,0x9D,0x9E,0x9F,0xA0,0xA1,0xA2,0xA3,0xA4,0xA5,0xA6,0xA7,0xA8,0xC6,0xC7]: OP_LEN[op] = 2
for op in [0x13,0x14,0x84,0xB2,0xB3,0xB4,0xB5,0xB6,0xB7,0xB8,0xBB,0xBD,0xC0,0xC1]: OP_LEN[op] = 2
OP_LEN[0xB9] = OP_LEN[0xBA] = 4
OP_LEN[0xC5] = 3
for op in [0xC8, 0xC9]: OP_LEN[op] = 4
def scan_code(code):
    i, has_branch = 0, False
    while i < len(code):
        op = code[i]
        if op in BRANCH_OPS: has_branch = True
        if op == 0xAA:
            pad = (4 - ((i + 1) & 3)) & 3
            j = i + pad + 1
            low, high = struct.unpack('>ii', code[j+4:j+12])
            i = j + 12 + 4 * (high - low + 1); continue
        if op == 0xAB:
            pad = (4 - ((i + 1) & 3)) & 3
            j = i + pad + 1
            npairs = struct.unpack('>i', code[j+4:j+8])[0]
            i = j + 8 + 8 * npairs; continue
        if op == 0xC4:
            i += 6 if code[i+1] == 0x84 else 4; continue
        ln = OP_LEN.get(op)
        if ln is None: raise ValueError('unknown opcode 0x%02x at %d' % (op, i))
        i += 1 + ln
    return has_branch
def parse_class(data):
    if data[:4] != b'\xca\xfe\xba\xbe': return
    cp_count = struct.unpack('>H', data[8:10])[0]
    i = 10; entry = 1; cp_utf8 = {}
    while entry < cp_count:
        tag = data[i]; i += 1
        if tag == 1:
            ln = struct.unpack('>H', data[i:i+2])[0]
            cp_utf8[entry] = data[i+2:i+2+ln].decode('utf8',errors='replace'); i += 2 + ln
        elif tag in (3, 4): i += 4
        elif tag in (5, 6): i += 8; entry += 1
        elif tag in (7, 8, 16, 19, 20): i += 2
        elif tag in (9, 10, 11, 12, 17, 18): i += 4
        elif tag == 15: i += 3
        else: raise ValueError('cp tag %d' % tag)
        entry += 1
    i += 6
    itf = struct.unpack('>H', data[i:i+2])[0]; i += 2 + 2 * itf
    for kind in range(2):
        n = struct.unpack('>H', data[i:i+2])[0]; i += 2
        for _k in range(n):
            i += 6
            na = struct.unpack('>H', data[i:i+2])[0]; i += 2
            for _a in range(na):
                name_idx = struct.unpack('>H', data[i:i+2])[0]
                alen = struct.unpack('>I', data[i+2:i+6])[0]
                if kind == 1 and cp_utf8.get(name_idx) == 'Code':
                    yield (data, i + 6, cp_utf8)
                i += 6 + alen
def code_attrs(data, off, cp_info):
    code_len = struct.unpack('>I', data[off+4:off+8])[0]
    i = off + 8
    code = data[i:i+code_len]; i += code_len
    et = struct.unpack('>H', data[i:i+2])[0]; i += 2 + 8 * et
    na = struct.unpack('>H', data[i:i+2])[0]; i += 2
    names = []
    for _ in range(na):
        aname = cp_info.get(struct.unpack('>H', data[i:i+2])[0])
        alen = struct.unpack('>I', data[i+2:i+6])[0]
        names.append(aname); i += 6 + alen
    return code, names
jar = sys.argv[1]
z = zipfile.ZipFile(jar)
total = with_sm = missing = 0
for name in z.namelist():
    if not name.endswith('.class'): continue
    total += 1
    data = z.read(name)
    any_sm = False
    for (dd, off, cp) in parse_class(data):
        code, attrs = code_attrs(dd, off, cp)
        br = scan_code(code)
        if 'StackMap' in attrs: any_sm = True
        if br and 'StackMap' not in attrs:
            print('MISSING StackMap with branches:', name); missing += 1
    if any_sm: with_sm += 1
print('OK classes=%d with_stackmap=%d branch_methods_missing_stackmap=%d' % (total, with_sm, missing))
sys.exit(1 if missing else 0)
