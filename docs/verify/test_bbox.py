#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка BoundingBox.reSort()/isVisible() после фикса мутации вершин (§20.4).

DirectX7.transformSave() МУТИРУЕТ вершины: x,y,z перезаписываются
трансформированными значениями (оригинал сохраняется в sx,sy,rz). В оригинале
reSort() и isVisible() каждый вызов начинали с set(minx,..), сбрасывая 8 углов,
поэтому мутация была безвредной. Оптимизация этапа 2 сделала вершины полем
экземпляра ("заполняются один раз в конструкторе") и убрала set() - но
MeshObject вызывает reSort() КАЖДЫЙ КАДР, и бокс начал накапливать умножение
на матрицу (box*mat^k): полный тест видимости получал мусорные вершины,
[MESH]-объекты мерцали и исчезали.

Фикс: reSort() больше не вызывает transformSave(), а считает трансформированный
AABB напрямую из неизменяемых minx..maxz (те же 8 углов, те же формулы,
бит-в-бит minpx..maxpz); вершины остаются нетронутыми для isVisible().

Скрипт моделирует три реализации на Python (int-семантика Java: обёртывание
int32, арифметический >>, деление с усечением к нулю):
  original - set() перед каждым reSort/isVisible (эталон, как в 214a3fc);
  new      - вершины из конструктора + прямой reSort (текущий код);
  buggy    - вершины из конструктора + reSort через transformSave (HEAD до
             фикса) - свидетель регрессии.

Проверяется:
  1. new.reSort == original.reSort бит-в-бит (minpx..maxpz) каждый кадр
     многокадровых серий (статичная матрица, дрейфующая матрица, чистый
     перенос, перевёрнутый бокс);
  2. new.isVisible == original.isVisible (полный тест) каждый кадр;
  3. new не мутирует вершины (x,y,z постоянны после reSort/isVisible);
  4. свидетель: buggy расходится с original и по reSort, и по isVisible
     (флипы isVisible = то самое мерцание), иначе тест нечувствителен.

Запуск:  python3 docs/verify/test_bbox.py
Ожидание: "TOTAL FAILURES: 0"
"""
import random
import sys

MASK = (1 << 32) - 1
FP_BITS = 14          # Matrix.fp
DR_DIST = 60000       # DirectX7.drDist в тесте
DIST_X, DIST_Y = 320, 240
CENTRE_X, CENTRE_Y = 88, 66


def i32(v):
    v &= MASK
    return v - (1 << 32) if v >= (1 << 31) else v


def jshr(v, s):
    """Java >> : арифметический сдвиг int32 (Python >> над нормализованным
    int32 даёт то же значение)."""
    return i32(i32(v) >> s)


def jmul(a, b):
    return i32(a * b)


def tdiv(a, b):
    """Java / : усечение к нулю."""
    q = abs(a) // abs(b)
    return -q if (a < 0) != (b < 0) else q


class Vertex(object):
    __slots__ = ('x', 'y', 'z', 'sx', 'sy', 'rz')

    def __init__(self, x, y, z):
        self.x, self.y, self.z = x, y, z
        self.sx = self.sy = self.rz = 0


def corners(box):
    """8 углов в том же порядке, что заполняет BoundingBox.set()."""
    minx, miny, minz, maxx, maxy, maxz = box
    return [
        Vertex(minx, miny, minz), Vertex(maxx, miny, minz),
        Vertex(minx, miny, maxz), Vertex(maxx, miny, maxz),
        Vertex(minx, maxy, minz), Vertex(maxx, maxy, minz),
        Vertex(minx, maxy, maxz), Vertex(maxx, maxy, maxz),
    ]


def prep(m):
    """m>>2 и fp=Matrix.fp-2, как в transformSave/transformAndProjectVertices."""
    return ([jshr(m[k], 2) for k in range(9)] + [m[9], m[10], m[11]], FP_BITS - 2)


def transform_save(vers, m):
    """Точная копия DirectX7.transformSave() для Vertex[] (МУТИРУЕТ x,y,z)."""
    (m00, m01, m02, m10, m11, m12, m20, m21, m22, m03, m13, m23), fp = prep(m)
    for v in vers:
        x, y, z = v.x, v.y, v.z
        v.sx, v.sy, v.rz = x, y, z
        v.x = i32(jshr(jmul(x, m00), fp) + jshr(jmul(y, m01), fp) + jshr(jmul(z, m02), fp) + m03)
        v.y = i32(jshr(jmul(x, m10), fp) + jshr(jmul(y, m11), fp) + jshr(jmul(z, m12), fp) + m13)
        v.z = i32(jshr(jmul(x, m20), fp) + jshr(jmul(y, m21), fp) + jshr(jmul(z, m22), fp) + m23)


def transform_and_project(vers, m):
    """Коппия DirectX7.transformAndProjectVertices(Vertex[], Matrix): пишет
    только sx,sy,rz (dense-ветвь; разреженная даёт те же значения)."""
    (m00, m01, m02, m10, m11, m12, m20, m21, m22, m03, m13, m23), fp = prep(m)
    for v in vers:
        x, y, z = v.x, v.y, v.z
        sx = i32(m03 + jshr(jmul(x, m00), fp) + jshr(jmul(y, m01), fp) + jshr(jmul(z, m02), fp))
        sy = i32(-m13 - jshr(jmul(x, m10), fp) - jshr(jmul(y, m11), fp) - jshr(jmul(z, m12), fp))
        rz = i32(m23 + jshr(jmul(x, m20), fp) + jshr(jmul(y, m21), fp) + jshr(jmul(z, m22), fp))
        if rz < 0:
            sx = tdiv(jmul(sx, DIST_X), i32(-rz + DIST_X))
            sy = tdiv(jmul(sy, DIST_Y), i32(-rz + DIST_Y))
        v.sx = i32(sx + CENTRE_X)
        v.sy = i32(sy + CENTRE_Y)
        v.rz = rz


def full_visible(vers, x1, y1, x2, y2):
    """Полный тест isVisible() после проекции (6 условий отсечения)."""
    minx = maxx = vers[0].sx
    miny = maxy = vers[0].sy
    minz = maxz = vers[0].rz
    for v in vers[1:]:
        if v.sx < minx: minx = v.sx
        if v.sy < miny: miny = v.sy
        if v.rz < minz: minz = v.rz
        if v.rz > maxz: maxz = v.rz
        if v.sx > maxx: maxx = v.sx
        if v.sy > maxy: maxy = v.sy
    return not (maxx < x1 or minx > x2 or maxy < y1 or miny > y2
                or minz >= 0 or -maxz > DR_DIST)


def scan_xyz(vers):
    mnx = mxx = vers[0].x
    mny = mxy = vers[0].y
    mnz = mxz = vers[0].z
    for v in vers[1:]:
        if v.x < mnx: mnx = v.x
        if v.y < mny: mny = v.y
        if v.z < mnz: mnz = v.z
        if v.x > mxx: mxx = v.x
        if v.y > mxy: mxy = v.y
        if v.z > mxz: mxz = v.z
    return (mnx, mny, mnz, mxx, mxy, mxz)


class BBoxOriginal(object):
    def __init__(self, box):
        self.box = box

    def resort(self, m):
        vers = corners(self.box)          # set(minx,..) как в оригинале
        transform_save(vers, m)
        return scan_xyz(vers)

    def visible(self, m, rect):
        vers = corners(self.box)          # set(minx,..) как в оригинале
        transform_and_project(vers, m)
        return full_visible(vers, *rect)


class BBoxNew(object):
    def __init__(self, box):
        self.box = box
        self.verts = corners(box)         # set() один раз в конструкторе

    def resort(self, m):
        """Прямой расчёт из minx..maxz - копия нового reSort()."""
        (m00, m01, m02, m10, m11, m12, m20, m21, m22, m03, m13, m23), fp = prep(m)
        minx, miny, minz, maxx, maxy, maxz = self.box
        mnx = mny = mnz = mxx = mxy = mxz = 0
        for i in range(8):
            x = minx if (i & 1) == 0 else maxx
            y = miny if (i & 2) == 0 else maxy
            z = minz if (i & 4) == 0 else maxz
            tx = i32(jshr(jmul(x, m00), fp) + jshr(jmul(y, m01), fp) + jshr(jmul(z, m02), fp) + m03)
            ty = i32(jshr(jmul(x, m10), fp) + jshr(jmul(y, m11), fp) + jshr(jmul(z, m12), fp) + m13)
            tz = i32(jshr(jmul(x, m20), fp) + jshr(jmul(y, m21), fp) + jshr(jmul(z, m22), fp) + m23)
            if i == 0:
                mnx = mxx = tx; mny = mxy = ty; mnz = mxz = tz
            else:
                if tx < mnx: mnx = tx
                if ty < mny: mny = ty
                if tz < mnz: mnz = tz
                if tx > mxx: mxx = tx
                if ty > mxy: mxy = ty
                if tz > mxz: mxz = tz
        return (mnx, mny, mnz, mxx, mxy, mxz)

    def visible(self, m, rect):
        transform_and_project(self.verts, m)   # вершины не сбрасываются - не должны портиться
        return full_visible(self.verts, *rect)


class BBoxBuggy(object):
    """HEAD до фикса: reSort через transformSave без set() - мутация копится."""
    def __init__(self, box):
        self.box = box
        self.verts = corners(box)

    def resort(self, m):
        transform_save(self.verts, m)
        return scan_xyz(self.verts)

    def visible(self, m, rect):
        transform_and_project(self.verts, m)
        return full_visible(self.verts, *rect)


def identity():
    FP = 1 << FP_BITS
    return [FP, 0, 0, 0, FP, 0, 0, 0, FP, 0, 0, 0]


def random_matrix(rng):
    FP = 1 << FP_BITS
    m = [rng.randint(-FP, FP) for _ in range(9)]
    m += [rng.randint(-200000, 200000) for _ in range(3)]
    return m


def drift_matrix(rng, f):
    """Матрица кадра f: лёгкий дрейф вращения и переноса (движение объекта)."""
    FP = 1 << FP_BITS
    base = [rng.randint(-FP, FP) for _ in range(9)]
    t = [rng.randint(-100000, 100000) for _ in range(3)]
    m = [i32(v + f * 37) for v in base]
    m += [i32(t[k] + f * 911 * (k + 1)) for k in range(3)]
    return m


def run_series(name, box, mats, rect, stats, failures):
    orig = BBoxOriginal(box)
    new = BBoxNew(box)
    buggy = BBoxBuggy(box)
    ctor_xyz = [(v.x, v.y, v.z) for v in new.verts]
    bad = 0
    for f, m in enumerate(mats):
        ro, rn, rb = orig.resort(m), new.resort(m), buggy.resort(m)
        if rn != ro:
            bad += 1
            if bad <= 3:
                print('  FAIL %s frame %d: new resort %s != original %s' % (name, f, rn, ro))
        vo, vn = orig.visible(m, rect), new.visible(m, rect)
        if vn != vo:
            bad += 1
            if bad <= 3:
                print('  FAIL %s frame %d: new visible %s != original %s' % (name, f, vn, vo))
        vb = buggy.visible(m, rect)
        if rb != ro:
            stats['buggy_resort'] += 1
        if vb != vo:
            stats['buggy_visible'] += 1
    now_xyz = [(v.x, v.y, v.z) for v in new.verts]
    if now_xyz != ctor_xyz:
        bad += 1
        print('  FAIL %s: new box mutated its vertices!' % name)
    failures[0] += 1 if bad else 0
    print('%s: %d кадров, расхождений new-vs-original: %d' % (name, len(mats), bad))


def main():
    rng = random.Random(23)
    stats = {'buggy_resort': 0, 'buggy_visible': 0}
    failures = [0]

    boxes = []
    for _ in range(6):
        a = [rng.randint(-50000, 50000) for _ in range(3)]
        b = [rng.randint(-50000, 50000) for _ in range(3)]
        boxes.append((min(a[0], b[0]), min(a[1], b[1]), min(a[2], b[2]),
                      max(a[0], b[0]), max(a[1], b[1]), max(a[2], b[2])))
    boxes.append((-500, -800, -500, 500, 1200, 500))          # типичный проп
    boxes.append((2147483647, 2147483647, 2147483647,          # перевёрнутый бокс
                  -2147483648, -2147483648, -2147483648))      # (пустой Morphing)
    boxes.append((0, 0, 0, 0, 0, 0))                           # вырожденный

    rect = (0, 0, 176, 132)
    frames = 60

    for bi, box in enumerate(boxes):
        # статичная матрица (постоянный объект: кадр за кадром одна и та же)
        static = random_matrix(rng)
        run_series('box%d/static' % bi, box, [static] * frames, rect, stats, failures)
        # чистый перенос (identity-вращение + constant translation)
        tr = identity()
        tr[9], tr[10], tr[11] = 12345, -6789, 4321
        run_series('box%d/translate' % bi, box, [tr] * frames, rect, stats, failures)
        # дрейфующая матрица (движение объекта/камеры)
        seed = rng.getstate()
        mats = [drift_matrix(rng, f) for f in range(frames)]
        run_series('box%d/drift' % bi, box, mats, rect, stats, failures)
        rng.setstate(seed)

    print('свидетель регрессии (buggy): reSort расхождений %d, isVisible флипов %d'
          % (stats['buggy_resort'], stats['buggy_visible']))
    if stats['buggy_resort'] == 0 or stats['buggy_visible'] == 0:
        print('FAIL: свидетель не воспроизвёл баг - тест нечувствителен')
        failures[0] += 1

    print('TOTAL FAILURES: %d' % failures[0])
    return 1 if failures[0] else 0


if __name__ == '__main__':
    sys.exit(main())
