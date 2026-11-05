#!/usr/bin/env python3
"""Semantic compare: methods, instruction streams (cp-symbolized), StackMap frames.
Tolerances: ldc vs ldc_w, branch operand distance bytes (ldc_w inflation shifts
them coherently), old pipeline's Long<->Double tag mixup (both -> 'wide')."""
import struct, sys, zipfile, os

def parse(data):
    if data[:4] != b'\xca\xfe\xba\xbe':
        return None
    cp_count = struct.unpack('>H', data[8:10])[0]
    i = 10; entry = 1
    cp_tag = {}; cp_utf8 = {}; cp_class = {}; cp_namedesc = {}; cp_member = {}; cp_raw = {}
    while entry < cp_count:
        tag = data[i]; i += 1; cp_tag[entry] = tag
        if tag == 1:
            ln = struct.unpack('>H', data[i:i+2])[0]
            cp_utf8[entry] = data[i+2:i+2+ln].decode('utf8', errors='replace'); i += 2 + ln
        elif tag in (3, 4):
            cp_raw[entry] = data[i:i+4].hex(); i += 4
        elif tag in (5, 6):
            cp_raw[entry] = data[i:i+8].hex(); i += 8; entry += 1
        elif tag == 7:
            cp_class[entry] = struct.unpack('>H', data[i:i+2])[0]; i += 2
        elif tag in (8, 16, 19, 20):
            cp_raw[entry] = data[i:i+2].hex(); i += 2
            if tag == 8: cp_utf8.setdefault(-entry, None)
        elif tag in (9, 10, 11):
            cp_member[entry] = struct.unpack('>HH', data[i:i+4]); i += 4
        elif tag == 12:
            cp_namedesc[entry] = struct.unpack('>HH', data[i:i+4]); i += 4
        elif tag in (17, 18): i += 4
        elif tag == 15: i += 3
        else: raise ValueError('cp tag %d @%d' % (tag, i))
        entry += 1
    i += 6
    itf = struct.unpack('>H', data[i:i+2])[0]; i += 2 + 2 * itf
    methods = {}
    for kind in range(2):
        n = struct.unpack('>H', data[i:i+2])[0]; i += 2
        for _k in range(n):
            name = cp_utf8[struct.unpack('>H', data[i+2:i+4])[0]]
            desc = cp_utf8[struct.unpack('>H', data[i+4:i+6])[0]]
            i += 6
            na = struct.unpack('>H', data[i:i+2])[0]; i += 2
            for _a in range(na):
                name_idx = struct.unpack('>H', data[i:i+2])[0]
                alen = struct.unpack('>I', data[i+2:i+6])[0]
                if kind == 1 and cp_utf8.get(name_idx) == 'Code':
                    methods[name + desc] = parse_code(data, i + 6, cp_utf8)
                i += 6 + alen
    return cp_utf8, cp_class, cp_namedesc, cp_member, cp_tag, cp_raw, methods

def parse_code(data, off, cp_utf8):
    code_len = struct.unpack('>I', data[off+4:off+8])[0]
    i = off + 8
    code = data[i:i+code_len]; i += code_len
    et = struct.unpack('>H', data[i:i+2])[0]; i += 2 + 8 * et
    na = struct.unpack('>H', data[i:i+2])[0]; i += 2
    stackmap = None
    for _ in range(na):
        aname = cp_utf8.get(struct.unpack('>H', data[i:i+2])[0])
        alen = struct.unpack('>I', data[i+2:i+6])[0]
        if aname == 'StackMap':
            stackmap = data[i+6:i+6+alen]
        i += 6 + alen
    return code, stackmap

_OP_LEN = {op: 0 for op in range(256)}
_OP_LEN[0x10] = 1
for _op in [0x12,0x15,0x16,0x17,0x18,0x19,0x36,0x37,0x38,0x39,0x3A,0xA9,0xBC]: _OP_LEN[_op] = 1
for _op in [0x11,0x99,0x9A,0x9B,0x9C,0x9D,0x9E,0x9F,0xA0,0xA1,0xA2,0xA3,0xA4,0xA5,0xA6,0xA7,0xA8,0xC6,0xC7]: _OP_LEN[_op] = 2
for _op in [0x13,0x14,0x84,0xB2,0xB3,0xB4,0xB5,0xB6,0xB7,0xB8,0xBB,0xBD,0xC0,0xC1]: _OP_LEN[_op] = 2
_OP_LEN[0xB9] = _OP_LEN[0xBA] = 4
_OP_LEN[0xC5] = 3
for _op in [0xC8, 0xC9]: _OP_LEN[_op] = 4

def symb_code(code, cp_utf8, cp_class, cp_namedesc, cp_member, cp_tag, cp_raw):
    out = []
    i = 0
    while i < len(code):
        op = code[i]
        def resolve(idx):
            tag = cp_tag.get(idx)
            if tag in (9, 10, 11):
                ci, ni = cp_member[idx]
                cls = cp_utf8[cp_class[ci]]
                nm, ds = cp_namedesc[ni]
                return '%s.%s%s' % (cls, cp_utf8[nm], cp_utf8[ds])
            if tag == 7:
                return 'cls:' + cp_utf8[cp_class[idx]]
            if tag == 8:
                return 'str'
            if tag == 1:
                return 'utf8:' + cp_utf8[idx]
            if tag in (3, 4, 5, 6):
                return 'num@%d:%s' % (tag, cp_raw.get(idx, '?'))
            return 'cp%d' % (tag or -1)
        if op == 0xAA:
            pad = (4 - ((i + 1) & 3)) & 3
            j = i + pad + 1
            low, high = struct.unpack('>ii', code[j+4:j+12])
            out.append((op, low, high))
            i = j + 12 + 4 * (high - low + 1); continue
        if op == 0xAB:
            pad = (4 - ((i + 1) & 3)) & 3
            j = i + pad + 1
            npairs = struct.unpack('>i', code[j+4:j+8])[0]
            pairs = struct.unpack('>%di' % (2*npairs), code[j+8:j+8+8*npairs])
            out.append((op, tuple(pairs[::2])))
            i = j + 8 + 8 * npairs; continue
        if op == 0xC4:
            wop = code[i+1]
            if wop == 0x84:
                out.append((op, tuple(code[i+1:i+6]))); i += 6
            else:
                out.append((op, tuple(code[i+1:i+4]))); i += 4
            continue
        ln = _OP_LEN.get(op)
        if ln is None: raise ValueError('opcode 0x%02x' % op)
        ops = code[i+1:i+1+ln]
        if op == 0x13:
            out.append((0x12, resolve(struct.unpack('>H', ops)[0])))   # ldc_w ~ ldc
        elif op == 0x12:
            out.append((op, resolve(ops[0])))
        elif op in (0x14, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xBB, 0xBD, 0xC0, 0xC1):
            out.append((op, resolve(struct.unpack('>H', ops)[0])))
        elif op in (0xB9, 0xBA):
            out.append((op, resolve(struct.unpack('>H', ops[:2])[0]), tuple(ops[2:])))
        elif op == 0xC5:
            out.append((op, resolve(struct.unpack('>H', ops[:2])[0]), tuple(ops[2:])))
        elif 0x99 <= op <= 0xA8 or op in (0xC6, 0xC7):
            out.append((op,))  # branch-offset bytes shift with ldc_w inflation
        else:
            out.append((op, tuple(ops)))
        i += 1 + ln
    return tuple(out)

def decode_stackmap(sm, cp_utf8, cp_class):
    out = []
    if sm is None: return out
    n = struct.unpack('>H', sm[:2])[0]
    pos = 2
    for _ in range(n):
        off = struct.unpack('>H', sm[pos:pos+2])[0]; pos += 2
        nl = struct.unpack('>H', sm[pos:pos+2])[0]; pos += 2
        locs = []
        for _l in range(nl):
            t, pos = read_type(sm, pos, cp_utf8, cp_class)
            locs.append(t)
        ns = struct.unpack('>H', sm[pos:pos+2])[0]; pos += 2
        stk = []
        for _l in range(ns):
            t, pos = read_type(sm, pos, cp_utf8, cp_class)
            stk.append(t)
        out.append((off, tuple(locs), tuple(stk)))
    return out

def read_type(sm, pos, cp_utf8, cp_class):
    tag = sm[pos]; pos += 1
    if tag == 7:
        idx = struct.unpack('>H', sm[pos:pos+2])[0]; pos += 2
        cn = cp_utf8[cp_class[idx]]
        if cn[0] == '[':
            cn = cn.replace('L', '').replace(';', '')
        return ('obj:' + cn, pos)
    if tag == 8:
        off = struct.unpack('>H', sm[pos:pos+2])[0]; pos += 2
        return ('uninit:%d' % off, pos)
    if tag in (3, 4):
        return ('wide', pos)
    return (tag, pos)

def main():
    ref = zipfile.ZipFile(sys.argv[1])
    bdir = sys.argv[2]
    total = same = diff = shown = 0
    for name in sorted(ref.namelist()):
        if not name.endswith('.class'): continue
        p = parse(ref.read(name))
        f = os.path.join(bdir, name)
        if not os.path.exists(f):
            continue
        q = parse(open(f, 'rb').read())
        if p is None or q is None: continue
        total += 1
        pmet = p[6]; qmet = q[6]
        if set(pmet.keys()) != set(qmet.keys()):
            diff += 1
            if shown < 10:
                print('METHOD SET DIFF', name)
                print('  old-only:', sorted(set(pmet)-set(qmet))[:6])
                print('  new-only:', sorted(set(qmet)-set(pmet))[:6])
                shown += 1
            continue
        bad = []
        for m in sorted(pmet.keys()):
            pcode, psm = pmet[m]
            qcode, qsm = qmet[m]
            psym = symb_code(pcode, p[0], p[1], p[2], p[3], p[4], p[5])
            qsym = symb_code(qcode, q[0], q[1], q[2], q[3], q[4], q[5])
            if psym != qsym:
                for _k in range(min(len(psym), len(qsym))):
                    if psym[_k] != qsym[_k]:
                        bad.append(m + ' code-diff insn%d: %r vs %r' % (_k, psym[_k], qsym[_k]))
                        break
                else:
                    bad.append(m + ' code-len %d vs %d' % (len(psym), len(qsym)))
            pd = decode_stackmap(psm, p[0], p[1])
            qd = decode_stackmap(qsm, q[0], q[1])
            if len(pd) != len(qd):
                bad.append(m + ' stackmap entries %d vs %d' % (len(pd), len(qd)))
            else:
                for k in range(len(pd)):
                    # same order; offsets may differ (ldc_w); compare types only
                    if pd[k][1] != qd[k][1] or pd[k][2] != qd[k][2]:
                        bad.append(m + ' frame%d %s vs %s' % (k, pd[k][1:], qd[k][1:]))
                        break
        if bad:
            diff += 1
            if shown < 15:
                print('DIFF', name)
                for b in bad[:4]: print('   ', b)
                shown += 1
        else:
            same += 1
    print('SUMMARY same=%d diff=%d total=%d' % (same, diff, total))
    sys.exit(1 if diff else 0)

if __name__ == '__main__':
    main()
