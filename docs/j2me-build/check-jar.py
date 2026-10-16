#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка CLDC-готовности JAR (или каталога классов) после препроверки.

Что проверяется:
  1. Все class-файлы - версии 48 (Java 1.4) или ниже: KVM не принимает
     более новые, а StackMapTable из Java 6 он не понимает вовсе.
  2. Препроверка реально выполнена: у методов с ветвлениями есть атрибут
     StackMap (CLDC), и нигде нет StackMapTable.
  3. Нет обращений к классам вне CLDC/MIDP/JSR и вне самого архива
     (java.util.ArrayList, java.io.File, java.awt.* и т.п. убили бы MIDlet
     в момент загрузки класса).
  4. Манифест: MIDlet-1 ведёт на существующий класс, MicroEdition-* на месте.
  5. Если рядом есть .jad - MIDlet-Jar-Size совпадает с размером JAR.

Запуск:
    python3 check-jar.py <Quantum.jar|каталог> [--lib a.jar --lib b.jar ...]
Код возврата 0 - всё в порядке, 1 - найдены проблемы.
"""
import os
import struct
import sys
import zipfile

# Классы, которые всегда доступны в CLDC/MIDP-среде сборки (если --lib не задан)
DEFAULT_LIBS = []


class ClassFile(object):
    """Разбор class-файла: версия, поля, методы, их атрибуты, ссылки на классы.

    Структура берётся строго из JVMS, а в конце разбор сверяется с длиной файла:
    если смещения собьются, будет исключение, а не молчаливые нули в статистике.
    """

    def __init__(self, data):
        self.data = data
        if data[:4] != b'\xca\xfe\xba\xbe':
            raise ValueError('не class-файл')
        self.minor, self.major = struct.unpack('>HH', data[4:8])
        self.class_refs = set()
        self.field_count = 0
        self.method_count = 0
        self.methods = []          # (Code, StackMap, StackMapTable, переходы, цели)
        self._parse()

    # --- чтение чисел ---
    def _u2(self, p):
        return struct.unpack('>H', self.data[p:p + 2])[0]

    def _u4(self, p):
        return struct.unpack('>I', self.data[p:p + 4])[0]

    # --- constant pool ---
    def _constant_pool(self):
        """Возвращает (utf8, class_idx, p); p - позиция access_flags класса."""
        d = self.data
        cp_count = self._u2(8)
        i = 10
        n = 1
        utf8 = {}
        class_idx = {}
        while n < cp_count:
            tag = d[i]
            if tag == 1:                                   # Utf8
                ln = self._u2(i + 1)
                utf8[n] = d[i + 3:i + 3 + ln].decode('utf-8', 'replace')
                i += 3 + ln
            elif tag == 7:                                 # Class
                class_idx[n] = self._u2(i + 1)
                i += 3
            elif tag in (8, 16, 19, 20):                   # String, MethodType, Module, Package
                i += 3
            elif tag == 15:                                # MethodHandle
                i += 4
            elif tag in (3, 4, 9, 10, 11, 12, 17, 18):     # Int/Float/Fieldref/Methodref/...
                i += 5
            elif tag in (5, 6):                            # Long/Double - занимают две ячейки
                i += 9
                n += 1
            else:
                raise ValueError('неизвестный тег constant pool: %d' % tag)
            n += 1
        return utf8, class_idx, i

    # --- атрибуты ---
    def _skip_attributes(self, p):
        """p указывает на attributes_count; возвращает позицию за атрибутами."""
        ac = self._u2(p)
        p += 2
        for _ in range(ac):
            p += 6 + self._u4(p + 2)      # name_index(2) + length(4) + info
        return p

    def _code_attribute(self, p, utf8):
        """Атрибут Code: длина байткода, переходы и их цели, StackMap/StackMapTable."""
        body = p + 6                                   # за name_index(2) и length(4)
        code_len = self._u4(body + 4)                  # max_stack, max_locals, code_length
        jumps, targets = scan_code(self.data[body + 8:body + 8 + code_len])
        q = body + 8 + code_len
        q += 2 + 8 * self._u2(q)                       # exception_table
        sm = smt = 0
        cac = self._u2(q)
        q += 2
        for _ in range(cac):
            cn = utf8.get(self._u2(q), '')
            if cn == 'StackMap':
                sm += 1
            elif cn == 'StackMapTable':
                smt += 1
            q += 6 + self._u4(q + 2)
        return code_len, jumps, targets, sm, smt

    def _parse(self):
        d = self.data
        utf8, class_idx, p = self._constant_pool()

        for name_idx in class_idx.values():
            name = utf8.get(name_idx, '')
            while name.startswith('['):                # "[Lfoo/Bar;" -> элемент
                name = name[1:]
            if name.startswith('L') and name.endswith(';'):
                name = name[1:-1]
            if name:
                self.class_refs.add(name)

        p += 6                                         # access_flags, this_class, super_class
        p += 2 + 2 * self._u2(p)                       # interfaces_count + interfaces

        self.field_count = self._u2(p)
        p += 2
        for _ in range(self.field_count):
            p += 6                                     # access_flags, name, descriptor
            p = self._skip_attributes(p)

        self.method_count = self._u2(p)
        p += 2
        for _ in range(self.method_count):
            p += 6                                     # access_flags, name, descriptor
            ac = self._u2(p)
            p += 2
            has_code, sm, smt, jumps, targets = False, 0, 0, 0, frozenset()
            for _ in range(ac):
                aname = utf8.get(self._u2(p), '')
                alen = self._u4(p + 2)
                if aname == 'Code':
                    _, jumps, targets, sm, smt = self._code_attribute(p, utf8)
                    has_code = True
                p += 6 + alen
            self.methods.append((has_code, sm, smt, jumps, targets))

        p = self._skip_attributes(p)                   # атрибуты самого класса
        if p != len(d):
            raise ValueError('разбор не сошёлся: осталось %d байт' % (len(d) - p))

    def parse_methods(self):
        """Список (есть Code, StackMap, StackMapTable, переходы, цели) по методам."""
        return self.methods


# --- точный разбор байткода -------------------------------------------------
# Полная длина инструкции (opcode + операнды) по JVMS. Переменную длину имеют
# только tableswitch/lookupswitch (0xAA/0xAB) и wide (0xC4) - их разбираем особо.
_OP_LEN = {}
for _op in (list(range(0x00, 0x10)) + list(range(0x1A, 0x36)) +
            list(range(0x3B, 0x84)) + list(range(0x85, 0x99)) +
            list(range(0xAC, 0xB2)) + [0xBE, 0xBF, 0xC2, 0xC3]):
    _OP_LEN[_op] = 1
for _op in (0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19, 0x36, 0x37, 0x38, 0x39,
            0x3A, 0xA9, 0xBC):
    _OP_LEN[_op] = 2
for _op in ([0x11, 0x13, 0x14, 0x84, 0xA7, 0xA8, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6,
             0xB7, 0xB8, 0xBB, 0xBD, 0xC0, 0xC1, 0xC6, 0xC7] +
            list(range(0x99, 0xA7))):
    _OP_LEN[_op] = 3
_OP_LEN[0xC5] = 4                                   # multianewarray
for _op in (0xB9, 0xBA, 0xC8, 0xC9):                # invoke*, goto_w, jsr_w
    _OP_LEN[_op] = 5

# переходы со смещением относительно текущего адреса
_BRANCH_REL = set(list(range(0x99, 0xA7)) + [0xA7, 0xA8, 0xC6, 0xC7, 0xC8, 0xC9])


def _align4(i):
    """Выравнивание операндов switch относительно начала байткода метода."""
    return (i + 3) & ~3


def _s2(code, p):
    return struct.unpack('>h', code[p:p + 2])[0]


def _s4(code, p):
    return struct.unpack('>i', code[p:p + 4])[0]


def scan_code(code):
    """Проход по байткоду инструкции за инструкцией.

    Возвращает (число переходов, множество их целей). Цель перехода - это адрес,
    для которого KVM нужна stack map frame; переход "вслед за инструкцией" кадра
    не требует. Если целей нет - атрибут StackMap методу не нужен, и его
    отсутствие законно (именно так решает настоящий препроверщик).
    """
    jumps = 0
    targets = set()
    i = 0
    L = len(code)
    while i < L:
        op = code[i]
        if op == 0xC4:                                   # wide
            i += 1
            op2 = code[i]
            i += 6 if op2 == 0x84 else 4                 # wide iinc длиннее прочих
            continue
        if op in (0xAA, 0xAB):                           # tableswitch / lookupswitch
            d = _align4(i + 1)
            jumps += 1
            targets.add(i + _s4(code, d))                # default
            if op == 0xAA:
                low, high = _s4(code, d + 4), _s4(code, d + 8)
                off = d + 12
                for _ in range(high - low + 1):
                    targets.add(i + _s4(code, off))
                    off += 4
            else:
                npairs = _s4(code, d + 4)
                off = d + 8
                for _ in range(npairs):
                    targets.add(i + _s4(code, off + 4))
                    off += 8
            i = off
            continue
        ln = _OP_LEN.get(op)
        if ln is None:
            raise ValueError('opcode 0x%02X не распознан на смещении %d' % (op, i))
        if op in _BRANCH_REL:
            jumps += 1
            off = _s4(code, i + 1) if ln == 5 else _s2(code, i + 1)
            t = i + off
            if t != i + ln:
                targets.add(t)
        i += ln
    return jumps, targets


# --- чтение содержимого ------------------------------------------------------
def entries_of(target):
    """Список (имя, байты) для JAR/ZIP или для каталога с классами."""
    if os.path.isdir(target):
        out = []
        for root, dirs, files in os.walk(target):
            dirs.sort()
            for f in sorted(files):
                full = os.path.join(root, f)
                rel = os.path.relpath(full, target).replace(os.sep, '/')
                with open(full, 'rb') as fh:
                    out.append((rel, fh.read()))
        return out
    with zipfile.ZipFile(target) as z:
        return [(n, z.read(n)) for n in z.namelist() if not n.endswith('/')]


def class_names_in_lib(lib):
    """Имена классов банка API в виде "java/lang/Object" (без .class)."""
    if not os.path.exists(lib):
        return set()
    with zipfile.ZipFile(lib) as z:
        return set(n[:-6] for n in z.namelist() if n.endswith('.class'))


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    target = argv[1]
    libs = []
    i = 2
    while i < len(argv):
        if argv[i] == '--lib' and i + 1 < len(argv):
            libs.append(argv[i + 1])
            i += 2
        else:
            i += 1

    is_dir = os.path.isdir(target)
    entries = entries_of(target)
    classes = [(n, d) for n, d in entries if n.endswith('.class')]
    own = set(n[:-6] for n, _ in classes)
    print('проверяем: %s' % target)
    print('записей: %d, из них классов: %d' % (len(entries), len(classes)))
    if not classes:
        print('ПРОБЛЕМА: классов нет')
        return 1

    available = set(own)
    for lib in libs:
        if os.path.exists(lib):
            available |= class_names_in_lib(lib)
    if not libs:
        print('  (--lib не задан: проверка отсутствующих классов пропущена)')

    problems = []
    bad_ver = []
    no_stackmap = 0
    with_stackmap = 0
    smt_total = 0
    sm_total = 0
    methods_with_code = 0
    methods_with_branches = 0
    missing = {}

    for n, d in classes:
        try:
            cf = ClassFile(d)
        except Exception as e:
            problems.append('%s: не разбирается (%s)' % (n, e))
            continue
        if cf.major > 48:
            bad_ver.append((n, cf.major))
        for has_code, sm, smt, jumps, targets in cf.parse_methods():
            if has_code:
                methods_with_code += 1
            sm_total += sm
            smt_total += smt
            if sm:
                with_stackmap += 1
            if targets:
                methods_with_branches += 1
                if not sm and not smt:
                    no_stackmap += 1
        if libs:
            for ref in cf.class_refs:
                if ref in available:
                    continue
                if ref.startswith('java/') or ref.startswith('javax/'):
                    missing.setdefault(ref, []).append(n)
                elif '/' in ref and not ref.startswith('['):
                    missing.setdefault(ref, []).append(n)

    print('методов с кодом: %d, из них с целями переходов: %d' % (methods_with_code,
                                                                methods_with_branches))
    print('атрибутов StackMap (CLDC-препроверка): %d' % sm_total)
    print('атрибутов StackMapTable (Java 6+, KVM не понимает): %d' % smt_total)
    print('методов, которым нужен StackMap, но его нет: %d' % no_stackmap)
    if bad_ver:
        problems.append('class version > 48 у %d классов (например %s)'
                        % (len(bad_ver), bad_ver[0]))
    if smt_total:
        problems.append('найдены StackMapTable - препроверка не для CLDC')
    if no_stackmap:
        problems.append('%d методов с ветвлениями без StackMap' % no_stackmap)
    if missing:
        ext = [k for k in missing if k.startswith(('java/', 'javax/'))]
        if ext:
            problems.append('ссылки на классы вне доступных библиотек: %s'
                            % ', '.join(sorted(ext)[:10]))

    # манифест (у каталога классов его по определению нет)
    if is_dir:
        print('манифест/JAD: не проверяются (цель - каталог классов)')
        print()
        if problems:
            print('ПРОБЛЕМЫ (%d):' % len(problems))
            for pr in problems:
                print('  -', pr)
            return 1
        print('Проверка пройдена: классы препроверены для CLDC.')
        return 0

    mf = None
    for n, d in entries:
        if n.upper() == 'META-INF/MANIFEST.MF':
            mf = d.decode('utf-8', 'replace')
    if mf is None:
        problems.append('нет META-INF/MANIFEST.MF')
    else:
        attrs = {}
        for line in mf.replace('\r\n', '\n').split('\n'):
            if ':' in line and not line.startswith(' '):
                k, v = line.split(':', 1)
                attrs[k.strip()] = v.strip()
        need = ['MIDlet-Name', 'MIDlet-Version', 'MIDlet-Vendor',
                'MicroEdition-Configuration', 'MicroEdition-Profile']
        for k in need:
            if k not in attrs:
                problems.append('в манифесте нет %s' % k)
        m1 = attrs.get('MIDlet-1', '')
        if m1:
            parts = [x.strip() for x in m1.split(',')]
            if len(parts) == 3:
                cls = parts[2].replace('.', '/')
                if cls not in own:
                    problems.append('MIDlet-1 ведёт на отсутствующий класс %s' % cls)
                else:
                    print('MIDlet-1: %s -> %s (класс на месте)' % (parts[0], parts[2]))
            else:
                problems.append('MIDlet-1 malformed: %s' % m1)
        else:
            problems.append('в манифесте нет MIDlet-1')
        print('MicroEdition-Configuration: %s, Profile: %s'
              % (attrs.get('MicroEdition-Configuration', '-'),
                 attrs.get('MicroEdition-Profile', '-')))

    # JAD
    if target.endswith('.jar'):
        jad = target[:-4] + '.jad'
        if os.path.exists(jad):
            size = os.path.getsize(target)
            txt = open(jad, encoding='utf-8', errors='replace').read()
            found = None
            for line in txt.replace('\r\n', '\n').split('\n'):
                if line.startswith('MIDlet-Jar-Size:'):
                    found = line.split(':', 1)[1].strip()
            if found is None:
                problems.append('в JAD нет MIDlet-Jar-Size')
            elif found != str(size):
                problems.append('MIDlet-Jar-Size=%s, а JAR весит %d' % (found, size))
            else:
                print('JAD: MIDlet-Jar-Size совпадает (%s)' % size)

    print()
    if problems:
        print('ПРОБЛЕМЫ (%d):' % len(problems))
        for p in problems:
            print('  -', p)
        return 1
    print('Проверка пройдена: JAR пригоден для CLDC/MIDP.')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
