#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка оптимизаций физики/AI (фаза 19): старая и новая версии трёх
горячих геометрических запросов должны давать БИТ-В-БИТ одинаковые
результаты на случайных мешах.

Что сравнивается (зеркала кода на Python, int-семантика Java):
1. SphereCast.sphereCast(mesh,pos,rad,recalc):
   - фильтр неколлизионных текстур поднят ДО AABB и distanceSphereToPolygon
     (раньше полный тест считался, затем результат выбрасывался);
   - для статических мешей (recalc=false) min/max полигона берутся из кэша
     Mesh.getAABB() вместо пересчёта по вершинам.
   Сравниваются: результат col И финальная позиция сферы (pos мутируется
   внутри цикла - порядок мутаций обязан совпасть).
2. HeightComputer.computeHeight(mesh,null,height,updatePos):
   - тот же подъём фильтра текстур, но ПОСЛЕ ветки ny>=0 (underRoof в
     оригинале ставится до проверки collision - семантика сохранена);
   - XZ-AABB из кэша.
   Сравниваются: height, индекс выбранного полигона, cx/cz/cy, underRoof.
3. Room.isPointOnMesh(x,y,z):
   - отсечка по кэшному XZ-AABB до полного теста (деление плоскости +
     векторные произведения). Отсечка точная в диапазоне координат без
     переполнения int в cross-тестах (реальные мировые координаты движка
     ~ ±2^22, запросы гейтятся isPointInRoomBox): true из cross-теста =>
     точка внутри выпуклой области => внутри AABB. При экстремальных
     |x|~2^31 (недостижимо в движке) старый тест переполняется и может
     дать мусорный true ЗА пределами AABB - новый код их отбрасывает
     (строго корректнее; оригинальный HeightComputer всегда использовал
     тот же AABB-фильтр).
   Сравнивается: индекс первого попадания (-1 при отсутствии).
4. Корректность самого построителя AABB (Mesh.getAABB): значения кэша
   равны inline-пересчёту; для не-Polygon3V/4V - "пустой" бокс.

Запуск:  python3 docs/verify/test_ai_physics.py
Ожидание: "TOTAL FAILURES: 0"
"""
import math
import random

M32 = 0xFFFFFFFF
IMAX = 2**31 - 1
IMIN = -(2**31)


def i32(x):
    x &= M32
    return x - (1 << 32) if x >= (1 << 31) else x


def ashr(a, n):
    return i32(a >> n)          # Python >> для отрицательных = пол, как в Java


def jdiv(a, b):
    """a / b в семантике Java: усечение к нулю."""
    if b == 0:
        raise ZeroDivisionError
    q = abs(a) // abs(b)
    return i32(q if (a < 0) == (b < 0) else -q)


def jdiv64(a, b):
    """64-битное деление с усечением к нулю (Java long '/'), результат -> int."""
    q = abs(a) // abs(b)
    return i32(q if (a < 0) == (b < 0) else -q)


SQRT_MAX = 46340


def isqrt(n):
    """Зеркало FastMath.isqrt (копия из test_fastmath_java.py)."""
    n = i32(n)
    if n <= 0:
        return 0
    if n < 0x10000:
        if n < 0x100:
            if n < 0x10:
                r = 2 if n < 0x4 else 4
            else:
                r = 8 if n < 0x40 else 16
        else:
            if n < 0x1000:
                r = 32 if n < 0x400 else 64
            else:
                r = 128 if n < 0x4000 else 256
    elif n < 0x1000000:
        if n < 0x40000:
            r = 512
        elif n < 0x100000:
            r = 1024
        elif n < 0x400000:
            r = 2048
        else:
            r = 4096
    elif n < 0x4000000:
        r = 8192
    elif n < 0x10000000:
        r = 16384
    elif n < 0x40000000:
        r = 32768
    else:
        r = 65536
    for _ in range(3):
        r = ashr(i32(r + jdiv(n, r)), 1)
    while r * r > n:
        r -= 1
    while r < SQRT_MAX and (r + 1) * (r + 1) <= n:
        r += 1
    return r


def isqrt_round(n):
    if n <= 0:
        return 0
    r = isqrt(n)
    if r < SQRT_MAX and n - r * r > r:
        r += 1
    return r


# ---------------- MathUtils2 ----------------

def ipp_quad(px, py, x1, y1, x2, y2, x3, y3, x4, y4):
    """isPointOnPolygon 2D (4 вершины) - int-умножения с переполнением."""
    return (i32(i32(x2 - x1) * i32(py - y1)) <= i32(i32(px - x1) * i32(y2 - y1)) and
            i32(i32(x3 - x2) * i32(py - y2)) <= i32(i32(px - x2) * i32(y3 - y2)) and
            i32(i32(x4 - x3) * i32(py - y3)) <= i32(i32(px - x3) * i32(y4 - y3)) and
            i32(i32(x1 - x4) * i32(py - y4)) <= i32(i32(px - x4) * i32(y1 - y4)))


def ipp_tri(px, py, x1, y1, x2, y2, x3, y3):
    return (i32(i32(x2 - x1) * i32(py - y1)) <= i32(i32(px - x1) * i32(y2 - y1)) and
            i32(i32(x3 - x2) * i32(py - y2)) <= i32(i32(px - x2) * i32(y3 - y2)) and
            i32(i32(x1 - x3) * i32(py - y3)) <= i32(i32(px - x3) * i32(y1 - y3)))


def ipp_quad_nory(px, pz, ax, az, bx, bz, cx, cz, dx, dz, norY):
    if norY >= 0:
        return ipp_quad(px, pz, ax, az, bx, bz, cx, cz, dx, dz)
    if norY < 0:
        return ipp_quad(px, pz, dx, dz, cx, cz, bx, bz, ax, az)
    return False


def ipp_tri_nory(px, pz, ax, az, bx, bz, cx, cz, norY):
    if norY >= 0:
        return ipp_tri(px, pz, ax, az, bx, bz, cx, cz)
    if norY < 0:
        return ipp_tri(px, pz, cx, cz, bx, bz, ax, az)
    return False


def ipp_3d_quad(point, a, b, c, d, normal):
    """isPointOnPolygon(Vector3D x6) - выбор доминантной оси по нормали."""
    nx = normal[0] if normal[0] > 0 else -normal[0]
    ny = normal[1] if normal[1] > 0 else -normal[1]
    nz = normal[2] if normal[2] > 0 else -normal[2]
    px, py, pz = point
    if nx >= ny and nx >= nz:
        if normal[0] >= 0:
            return ipp_quad(pz, py, a[2], a[1], b[2], b[1], c[2], c[1], d[2], d[1])
        return ipp_quad(pz, py, d[2], d[1], c[2], c[1], b[2], b[1], a[2], a[1])
    if ny >= nx and ny >= nz:
        if normal[1] >= 0:
            return ipp_quad(px, pz, a[0], a[2], b[0], b[2], c[0], c[2], d[0], d[2])
        return ipp_quad(px, pz, d[0], d[2], c[0], c[2], b[0], b[2], a[0], a[2])
    if nz >= nx and nz >= ny:
        if normal[2] <= 0:
            return ipp_quad(px, py, a[0], a[1], b[0], b[1], c[0], c[1], d[0], d[1])
        return ipp_quad(px, py, d[0], d[1], c[0], c[1], b[0], b[1], a[0], a[1])
    return True


def ipp_3d_tri(point, a, b, c, normal):
    nx = normal[0] if normal[0] > 0 else -normal[0]
    ny = normal[1] if normal[1] > 0 else -normal[1]
    nz = normal[2] if normal[2] > 0 else -normal[2]
    px, py, pz = point
    if nx >= ny and nx >= nz:
        if normal[0] >= 0:
            return ipp_tri(pz, py, a[2], a[1], b[2], b[1], c[2], c[1])
        return ipp_tri(pz, py, c[2], c[1], b[2], b[1], a[2], a[1])
    if ny >= nx and ny >= nz:
        if normal[1] >= 0:
            return ipp_tri(px, pz, a[0], a[2], b[0], b[2], c[0], c[2])
        return ipp_tri(px, pz, c[0], c[2], b[0], b[2], a[0], a[2])
    if nz >= nx and nz >= ny:
        if normal[2] <= 0:
            return ipp_tri(px, py, a[0], a[1], b[0], b[1], c[0], c[1])
        return ipp_tri(px, py, c[0], c[1], b[0], b[1], a[0], a[1])
    return True


def distance_to_line(point, a, b):
    dx = i32(b[0] - a[0]); dy = i32(b[1] - a[1]); dz = i32(b[2] - a[2])
    wx = i32(point[0] - a[0]); wy = i32(point[1] - a[1]); wz = i32(point[2] - a[2])
    fp = 14
    dp = dx * dx + dy * dy + dz * dz          # Java: (long)dx*dx + dy*dy + dz*dz
    dt = 0                                    # wy*dy и wz*dz - int-умножения!
    if dp != 0:
        num = (wx * dx + i32(wy * dy) + i32(wz * dz)) << fp
        # Java long '/' - усечение к нулю:
        q = abs(num) // abs(dp)
        dt = q if (num < 0) == (dp < 0) else -q
    if dt < 0:
        dt = 0
    if dt > 16384:
        dt = 16384
    dx = i32(a[0] + i32((dx * dt) >> fp))
    dy = i32(a[1] + i32((dy * dt) >> fp))
    dz = i32(a[2] + i32((dz * dt) >> fp))
    dx = i32(dx - point[0]); dy = i32(dy - point[1]); dz = i32(dz - point[2])
    return i32(i32(dx * dx) + i32(dy * dy) + i32(dz * dz))


def set_normal_from_cross(x, y, z):
    while x > 20000 or y > 20000 or z > 20000 or x < -20000 or y < -20000 or z < -20000:
        x >>= 1; y >>= 1; z >>= 1
    xi, yi, zi = i32(x), i32(y), i32(z)
    r = isqrt_round(i32(i32(xi * xi) + i32(yi * yi) + i32(zi * zi)))
    if r < 1:
        r = 1
    return (jdiv64(xi * 4096, r), jdiv64(yi * 4096, r), jdiv64(zi * 4096, r))


def calc_normal_fast(a, b, c):
    x = i32(a[1] - b[1]) * i32(a[2] - c[2]) - i32(a[2] - b[2]) * i32(a[1] - c[1])
    y = i32(a[2] - b[2]) * i32(a[0] - c[0]) - i32(a[0] - b[0]) * i32(a[2] - c[2])
    z = i32(a[0] - b[0]) * i32(a[1] - c[1]) - i32(a[1] - b[1]) * i32(a[0] - c[0])
    return set_normal_from_cross(x, y, z)


def compute_polygon_y(a, nx, ny, nz, px, py, pz):
    if ny >= 0:
        return IMAX
    s = i32(i32(i32(px - a[0]) * nx) + i32(i32(py - a[1]) * ny) + i32(i32(pz - a[2]) * nz))
    return i32(py - jdiv(s, ny))


# ---------------- SphereCast ----------------

def edge_far(p, a, b, rad):
    mn = a[0] if a[0] < b[0] else b[0]
    mx = a[0] if a[0] > b[0] else b[0]
    d = mn - p[0] if p[0] < mn else (p[0] - mx if p[0] > mx else 0)
    if d > rad:
        return True
    mn = a[1] if a[1] < b[1] else b[1]
    mx = a[1] if a[1] > b[1] else b[1]
    d = mn - p[1] if p[1] < mn else (p[1] - mx if p[1] > mx else 0)
    if d > rad:
        return True
    mn = a[2] if a[2] < b[2] else b[2]
    mx = a[2] if a[2] > b[2] else b[2]
    d = mn - p[2] if p[2] < mn else (p[2] - mx if p[2] > mx else 0)
    return d > rad


def dsp(verts, nor, point, rad):
    """distanceSphereToPolygon (3 или 4 вершины)."""
    a = verts[0]
    tx = i32(point[0] - a[0]); ty = i32(point[1] - a[1]); tz = i32(point[2] - a[2])
    dotv = i32(i32(tx * nor[0]) + i32(ty * nor[1]) + i32(tz * nor[2]))
    dot = ashr(dotv, 12)
    if dot > rad:
        return IMAX
    proj = (i32(point[0] - ashr(i32(nor[0] * dot), 12)),
            i32(point[1] - ashr(i32(nor[1] * dot), 12)),
            i32(point[2] - ashr(i32(nor[2] * dot), 12)))
    if len(verts) == 4:
        inside = ipp_3d_quad(proj, verts[0], verts[1], verts[2], verts[3], nor)
    else:
        inside = ipp_3d_tri(proj, verts[0], verts[1], verts[2], nor)
    if inside:
        dis = dot if dot >= 0 else -dot
        return rad - dis
    lens = []
    n = len(verts)
    for k in range(n):
        va = verts[k]
        vb = verts[(k + 1) % n]
        lens.append(IMAX if edge_far(point, va, vb, rad) else distance_to_line(point, va, vb))
    mn = min(lens)
    if mn <= i32(rad * rad):
        return rad - isqrt(mn)
    return IMAX


def sphere_cast_old(polys, texcol, pos, rad, recalc):
    """Оригинальный цикл SphereCast.sphereCast: AABB inline, фильтр текстур В КОНЦЕ."""
    col = False
    for el in polys:
        dis = IMAX
        mat = -1
        nor = None
        if el['kind'] == 4:
            a, b, c, d = el['v']
            mat = el['tex']
            xs = (a[0], b[0], c[0], d[0]); ys = (a[1], b[1], c[1], d[1]); zs = (a[2], b[2], c[2], d[2])
            maxx, minx = max(xs), min(xs)
            maxy, miny = max(ys), min(ys)
            maxz, minz = max(zs), min(zs)
            if maxx < pos[0] - rad: continue
            if minx > pos[0] + rad: continue
            if maxz < pos[2] - rad: continue
            if minz > pos[2] + rad: continue
            if maxy < pos[1] - rad: continue
            if miny > pos[1] + rad: continue
            v = (a, b, c, d)
            nor = (el['nx'], el['ny'], el['nz'])
            if recalc:
                nor = calc_normal_fast(v[0], v[1], v[3])
            dis = dsp(v, nor, pos, rad)
        elif el['kind'] == 3:
            a, b, c = el['v']
            mat = el['tex']
            xs = (a[0], b[0], c[0]); ys = (a[1], b[1], c[1]); zs = (a[2], b[2], c[2])
            maxx, minx = max(xs), min(xs)
            maxy, miny = max(ys), min(ys)
            maxz, minz = max(zs), min(zs)
            if maxx < pos[0] - rad: continue
            if minx > pos[0] + rad: continue
            if maxz < pos[2] - rad: continue
            if minz > pos[2] + rad: continue
            if maxy < pos[1] - rad: continue
            if miny > pos[1] + rad: continue
            v = (a, b, c)
            nor = (el['nx'], el['ny'], el['nz'])
            if recalc:
                nor = calc_normal_fast(v[0], v[1], v[2])
            dis = dsp(v, nor, pos, rad)
        if mat != -1 and not texcol[mat]:
            continue
        if dis != IMAX and dis > 0:
            pos = (i32(pos[0] + ashr(i32(-nor[0] * dis), 12)),
                   i32(pos[1] + ashr(i32(-nor[1] * dis), 12)),
                   i32(pos[2] + ashr(i32(-nor[2] * dis), 12)))
            col = True
    return col, pos


def sphere_cast_new(polys, texcol, pos, rad, recalc, bb):
    """Новый цикл: фильтр текстур ПЕРВЫМ, AABB из кэша при recalc==false."""
    col = False
    for i, el in enumerate(polys):
        dis = IMAX
        nor = None
        if el['kind'] == 4 or el['kind'] == 3:
            mat = el['tex']
            if mat != -1 and not texcol[mat]:
                continue
            v = el['v']
            if bb is not None:
                o = i * 6
                minx, miny, minz = bb[o], bb[o + 1], bb[o + 2]
                maxx, maxy, maxz = bb[o + 3], bb[o + 4], bb[o + 5]
            else:
                xs = tuple(p[0] for p in v); ys = tuple(p[1] for p in v); zs = tuple(p[2] for p in v)
                maxx, minx = max(xs), min(xs)
                maxy, miny = max(ys), min(ys)
                maxz, minz = max(zs), min(zs)
            if maxx < pos[0] - rad: continue
            if minx > pos[0] + rad: continue
            if maxz < pos[2] - rad: continue
            if minz > pos[2] + rad: continue
            if maxy < pos[1] - rad: continue
            if miny > pos[1] + rad: continue
            nor = (el['nx'], el['ny'], el['nz'])
            if recalc:
                nor = calc_normal_fast(v[0], v[1], v[-1])
            dis = dsp(v, nor, pos, rad)
        else:
            continue
        if dis != IMAX and dis > 0:
            pos = (i32(pos[0] + ashr(i32(-nor[0] * dis), 12)),
                   i32(pos[1] + ashr(i32(-nor[1] * dis), 12)),
                   i32(pos[2] + ashr(i32(-nor[2] * dis), 12)))
            col = True
    return col, pos


# ---------------- HeightComputer ----------------

def compute_height_old(polys, texcol, x, y, z, h_init):
    height = h_init; sel = -1
    cx = cz = cy = None
    under = False
    for i, el in enumerate(polys):
        polyY = IMAX
        cx_ = cz_ = cy_ = IMAX
        mat = -1
        if el['kind'] == 4:
            a, b, c, d = el['v']
            mat = el['tex']
            xs = (a[0], b[0], c[0], d[0]); zs = (a[2], b[2], c[2], d[2])
            maxx, minx = max(xs), min(xs)
            maxz, minz = max(zs), min(zs)
            if minx > x: continue
            if minz > z: continue
            if maxx < x: continue
            if maxz < z: continue
            if el['ny'] >= 0:
                if el['ny'] > 2048 and ashr(i32(a[1] + b[1] + c[1] + d[1]), 2) > y:
                    under = True
                continue
            if ipp_quad_nory(x, z, a[0], a[2], b[0], b[2], c[0], c[2], d[0], d[2], el['ny']):
                s = i32(i32(i32(x - a[0]) * el['nx']) + i32(i32(y - a[1]) * el['ny']) + i32(i32(z - a[2]) * el['nz']))
                polyY = i32(y - jdiv(s, el['ny']))
                cx_ = ashr(i32(a[0] + b[0] + c[0] + d[0]), 2)
                cz_ = ashr(i32(a[2] + b[2] + c[2] + d[2]), 2)
                cy_ = ashr(i32(a[1] + b[1] + c[1] + d[1]), 2)
        elif el['kind'] == 3:
            a, b, c = el['v']
            mat = el['tex']
            xs = (a[0], b[0], c[0]); zs = (a[2], b[2], c[2])
            maxx, minx = max(xs), min(xs)
            maxz, minz = max(zs), min(zs)
            if minx > x: continue
            if minz > z: continue
            if maxx < x: continue
            if maxz < z: continue
            if el['ny'] >= 0:
                if el['ny'] > 2048 and jdiv(i32(a[1] + b[1] + c[1]), 3) > y:
                    under = True
                continue
            if ipp_tri_nory(x, z, a[0], a[2], b[0], b[2], c[0], c[2], el['ny']):
                s = i32(i32(i32(x - a[0]) * el['nx']) + i32(i32(y - a[1]) * el['ny']) + i32(i32(z - a[2]) * el['nz']))
                polyY = i32(y - jdiv(s, el['ny']))
                cx_ = jdiv(i32(a[0] + b[0] + c[0]), 3)
                cz_ = jdiv(i32(a[2] + b[2] + c[2]), 3)
                cy_ = jdiv(i32(a[1] + b[1] + c[1]), 3)
        if mat != -1 and not texcol[mat]:
            continue
        if polyY < y and polyY > height:
            height = polyY; sel = i; cx, cz, cy = cx_, cz_, cy_
    return height, sel, cx, cz, cy, under


def compute_height_new(polys, texcol, x, y, z, h_init, bb):
    height = h_init; sel = -1
    cx = cz = cy = None
    under = False
    for i, el in enumerate(polys):
        polyY = IMAX
        cx_ = cz_ = cy_ = IMAX
        if el['kind'] == 4 or el['kind'] == 3:
            mat = el['tex']
            v = el['v']
            o = i * 6
            minx, minz = bb[o], bb[o + 2]
            maxx, maxz = bb[o + 3], bb[o + 5]
            if minx > x: continue
            if minz > z: continue
            if maxx < x: continue
            if maxz < z: continue
            if el['ny'] >= 0:      # крыша - ДО фильтра текстур, как в оригинале
                if el['kind'] == 4:
                    avg = ashr(i32(v[0][1] + v[1][1] + v[2][1] + v[3][1]), 2)
                else:
                    avg = jdiv(i32(v[0][1] + v[1][1] + v[2][1]), 3)
                if el['ny'] > 2048 and avg > y:
                    under = True
                continue
            if mat != -1 and not texcol[mat]:
                continue
            if el['kind'] == 4:
                a, b, c, d = v
                if ipp_quad_nory(x, z, a[0], a[2], b[0], b[2], c[0], c[2], d[0], d[2], el['ny']):
                    s = i32(i32(i32(x - a[0]) * el['nx']) + i32(i32(y - a[1]) * el['ny']) + i32(i32(z - a[2]) * el['nz']))
                    polyY = i32(y - jdiv(s, el['ny']))
                    cx_ = ashr(i32(a[0] + b[0] + c[0] + d[0]), 2)
                    cz_ = ashr(i32(a[2] + b[2] + c[2] + d[2]), 2)
                    cy_ = ashr(i32(a[1] + b[1] + c[1] + d[1]), 2)
            else:
                a, b, c = v
                if ipp_tri_nory(x, z, a[0], a[2], b[0], b[2], c[0], c[2], el['ny']):
                    s = i32(i32(i32(x - a[0]) * el['nx']) + i32(i32(y - a[1]) * el['ny']) + i32(i32(z - a[2]) * el['nz']))
                    polyY = i32(y - jdiv(s, el['ny']))
                    cx_ = jdiv(i32(a[0] + b[0] + c[0]), 3)
                    cz_ = jdiv(i32(a[2] + b[2] + c[2]), 3)
                    cy_ = jdiv(i32(a[1] + b[1] + c[1]), 3)
        else:
            continue
        if polyY < y and polyY > height:
            height = polyY; sel = i; cx, cz, cy = cx_, cz_, cy_
    return height, sel, cx, cz, cy, under


# ---------------- Room.isPointOnMesh ----------------

def room_ipp(el, x, y, z):
    """Room.isPointOnPolygon(x,y,z,obj)."""
    if el['kind'] == 4:
        if el['ny'] >= 0:
            return False
        v1, v2, v3, v4 = el['v']
        if compute_polygon_y(v1, el['nx'], el['ny'], el['nz'], x, y, z) <= y:
            return ipp_quad(x, z, v4[0], v4[2], v3[0], v3[2], v2[0], v2[2], v1[0], v1[2])
        return False
    if el['kind'] == 3:
        if el['ny'] >= 0:
            return False
        v1, v2, v3 = el['v']
        if compute_polygon_y(v1, el['nx'], el['ny'], el['nz'], x, y, z) <= y:
            return ipp_tri(x, z, v3[0], v3[2], v2[0], v2[2], v1[0], v1[2])
        return False
    return False


def ipom_old(polys, x, y, z):
    for i, el in enumerate(polys):
        if room_ipp(el, x, y, z):
            return i
    return -1


def ipom_new(polys, x, y, z, bb):
    for i, el in enumerate(polys):
        o = i * 6
        if x < bb[o] or x > bb[o + 3] or z < bb[o + 2] or z > bb[o + 5]:
            continue
        if room_ipp(el, x, y, z):
            return i
    return -1


# ---------------- Mesh.getAABB ----------------

def build_aabb(polys):
    bb = [0] * (len(polys) * 6)
    for i, el in enumerate(polys):
        o = i * 6
        if el['kind'] in (3, 4):
            v = el['v']
            xs = tuple(p[0] for p in v); ys = tuple(p[1] for p in v); zs = tuple(p[2] for p in v)
            bb[o], bb[o + 1], bb[o + 2] = min(xs), min(ys), min(zs)
            bb[o + 3], bb[o + 4], bb[o + 5] = max(xs), max(ys), max(zs)
        else:
            bb[o] = bb[o + 1] = bb[o + 2] = IMAX
            bb[o + 3] = bb[o + 4] = bb[o + 5] = IMIN
    return bb


# ---------------- Генерация мешей ----------------

def gen_convex_poly(rng, kind, cx, cy, cz, spread):
    n = kind
    angles = sorted(rng.uniform(0, 6.283) for _ in range(n))
    verts = []
    for ang in angles:
        rx = spread * rng.uniform(0.3, 1.0)
        rz = spread * rng.uniform(0.3, 1.0)
        verts.append((i32(cx + int(rx * 1000 * math.cos(ang))),
                      i32(cy + rng.randint(-max(spread // 4, 1), max(spread // 4, 1))),
                      i32(cz + int(rz * 1000 * math.sin(ang)))))
    if rng.random() < 0.05:      # вырожденный полигон (дубли вершин)
        verts[1] = verts[0]
    return verts


def gen_normal(rng, verts, kind):
    r = rng.random()
    if r < 0.55:      # нормаль, согласованная с обходом (как в уровнях)
        if kind == 4:
            n = calc_normal_fast(verts[0], verts[1], verts[3])
        else:
            n = calc_normal_fast(verts[0], verts[1], verts[2])
        if n == (0, 0, 0):
            n = (0, rng.choice((-4096, 4096)), 0)
        return n
    if r < 0.75:      # перевёрнутая
        n = calc_normal_fast(verts[0], verts[1], verts[2]) if kind == 3 else calc_normal_fast(verts[0], verts[1], verts[3])
        return tuple(-c for c in n)
    if r < 0.9:       # случайная доминантная ось
        axis = rng.randrange(3)
        n = [0, 0, 0]
        n[axis] = rng.choice((-4096, -2048, 2048, 4096))
        n[(axis + 1) % 3] = rng.randint(-500, 500)
        return tuple(n)
    return (rng.randint(-4096, 4096), rng.randint(-4096, 4096), rng.randint(-4096, 4096))


def gen_mesh(rng, xr=150000, yr=40000, off=20000, spread_max=20):
    """xr/yr - диапазон базы, off - разброс полигонов вокруг базы,
    spread_max*1000 - максимальный размер полигона (FP-единицы)."""
    npolys = rng.randint(2, 10)
    base = (rng.randint(-xr, xr), rng.randint(-yr, yr), rng.randint(-xr, xr))
    polys = []
    for _ in range(npolys):
        r = rng.random()
        if r < 0.08:
            polys.append({'kind': 0, 'v': (), 'tex': -1, 'nx': 0, 'ny': 0, 'nz': 0})
            continue
        kind = 4 if rng.random() < 0.6 else 3
        cx = base[0] + rng.randint(-off, off)
        cy = base[1] + rng.randint(-off, off)
        cz = base[2] + rng.randint(-off, off)
        v = gen_convex_poly(rng, kind, cx, cy, cz, rng.randint(2, spread_max))
        nx, ny, nz = gen_normal(rng, v, kind)
        polys.append({'kind': kind, 'v': tuple(v), 'tex': rng.choice((-1, 0, 1, 2, 3)),
                      'nx': nx, 'ny': ny, 'nz': nz})
    return polys, base


def gen_texcol(rng):
    return [rng.random() < 0.6 for _ in range(4)]


# ---------------- Прогоны ----------------

fail = 0
rng = random.Random(20261024)

# 1) SphereCast: статический путь (bb) и recalc-путь (inline), 3 и 4 вершины,
#    неколлизионные текстуры, мутация pos внутри цикла.
hits = 0
for it in range(40000):
    polys, base = gen_mesh(rng)
    texcol = gen_texcol(rng)
    bb = build_aabb(polys)
    # sanity: кэш == inline
    for i, el in enumerate(polys):
        if el['kind'] in (3, 4):
            xs = tuple(p[0] for p in el['v'])
            assert bb[i * 6] == min(xs) and bb[i * 6 + 3] == max(xs)
    for attempt in range(3):
        pos = (base[0] + rng.randint(-25000, 25000),
               base[1] + rng.randint(-25000, 25000),
               base[2] + rng.randint(-25000, 25000))
        rad = rng.choice((1, 40, 800, 1500, 4000, 12000))
        recalc = rng.random() < 0.3
        c1, p1 = sphere_cast_old(polys, texcol, pos, rad, recalc)
        c2, p2 = sphere_cast_new(polys, texcol, pos, rad, recalc, None if recalc else bb)
        if c1:
            hits += 1
        if (c1, p1) != (c2, p2):
            print('sphereCast FAIL it=%d recalc=%s rad=%d' % (it, recalc, rad), c1, p1, c2, p2)
            fail += 1
            if fail > 5:
                break
    if fail > 5:
        break
print('sphereCast: 120000 сравнений, коллизий в %d, расхождений нет' % hits if fail == 0
      else 'sphereCast: ЕСТЬ РАСХОЖДЕНИЯ')

# 2) HeightComputer: статический путь; точки внутри/снаружи/на границах AABB,
#    крыши (ny>=0) для underRoof, h_init случайный.
hits2 = under_hits = 0
for it in range(40000):
    polys, base = gen_mesh(rng)
    texcol = gen_texcol(rng)
    bb = build_aabb(polys)
    for attempt in range(3):
        r = rng.random()
        if r < 0.4:      # точка над случайным полигоном (будут попадания)
            cand = [el for el in polys if el['kind'] in (3, 4) and el['ny'] < 0]
            if not cand:
                continue
            el = rng.choice(cand)
            v = el['v']
            x = sum(p[0] for p in v) // len(v) + rng.randint(-300, 300)
            z = sum(p[2] for p in v) // len(v) + rng.randint(-300, 300)
            y = sum(p[1] for p in v) // len(v) + rng.randint(100, 20000)
        elif r < 0.6:    # точно на границе AABB какого-нибудь полигона
            i = rng.randrange(len(polys))
            o = i * 6
            x = rng.choice((bb[o], bb[o + 3]))
            z = rng.choice((bb[o + 2], bb[o + 5]))
            y = base[1] + rng.randint(-30000, 30000)
        else:
            x = base[0] + rng.randint(-30000, 30000)
            y = base[1] + rng.randint(-30000, 30000)
            z = base[2] + rng.randint(-30000, 30000)
        h0 = rng.randint(-60000, 60000)
        r1 = compute_height_old(polys, texcol, x, y, z, h0)
        r2 = compute_height_new(polys, texcol, x, y, z, h0, bb)
        if r1[1] != -1:
            hits2 += 1
        if r1[5]:
            under_hits += 1
        if r1 != r2:
            print('computeHeight FAIL it=%d' % it, (x, y, z), h0, r1, r2)
            fail += 1
            if fail > 5:
                break
    if fail > 5:
        break
print('computeHeight: 120000 сравнений, попаданий %d, underRoof %d, расхождений нет' % (hits2, under_hits)
      if fail == 0 else 'computeHeight: ЕСТЬ РАСХОЖДЕНИЯ')

# 3) Room.isPointOnMesh: первый индекс попадания, включая ny>=0 (всегда false)
#    и unknown-kind (пустой AABB vs false).
#    Координаты ограничены диапазоном, в котором cross-произведения 2D-теста
#    НЕ переполняют int (|разность| < ~22000 => произведение < 2^31): в этом
#    диапазоне отсечка по AABB математически точна (true из cross-теста =>
#    точка внутри выпуклой области => внутри AABB). При экстремальных
#    координатах (|x| ~ 2^31, в движке недостижимых: запросы гейтятся
#    isPointInRoomBox, мир ~ ±2^22) старый cross-тест переполняется и может
#    вернуть мусорный true для точки ЗА пределами AABB - новый код такие
#    попадания отбрасывает, что строго корректнее; оригинальный
#    HeightComputer всегда использовал тот же AABB-фильтр.
hits3 = 0
for it in range(40000):
    polys, base = gen_mesh(rng, xr=8000, yr=8000, off=4000, spread_max=4)
    bb = build_aabb(polys)
    for attempt in range(3):
        r = rng.random()
        if r < 0.5:
            cand = [el for el in polys if el['kind'] in (3, 4) and el['ny'] < 0]
            if not cand:
                continue
            el = rng.choice(cand)
            v = el['v']
            x = sum(p[0] for p in v) // len(v) + rng.randint(-200, 200)
            z = sum(p[2] for p in v) // len(v) + rng.randint(-200, 200)
            y = sum(p[1] for p in v) // len(v) + rng.randint(0, 5000)
        elif r < 0.7:
            # граница AABB реального полигона (unknown-kind пропускаем: его
            # "пустой" бокс IMAX/IMIN - синтетический страж, не координата)
            cand = [i for i, el in enumerate(polys) if el['kind'] in (3, 4)]
            if not cand:
                continue
            i = rng.choice(cand)
            o = i * 6
            x = rng.choice((bb[o], bb[o + 3], bb[o] - 1, bb[o + 3] + 1))
            z = rng.choice((bb[o + 2], bb[o + 5], bb[o + 2] - 1, bb[o + 5] + 1))
            y = base[1] + rng.randint(-6000, 6000)
        else:
            x = base[0] + rng.randint(-6000, 6000)
            y = base[1] + rng.randint(-6000, 6000)
            z = base[2] + rng.randint(-6000, 6000)
        r1 = ipom_old(polys, x, y, z)
        r2 = ipom_new(polys, x, y, z, bb)
        if r1 != -1:
            hits3 += 1
        if r1 != r2:
            print('isPointOnMesh FAIL it=%d' % it, (x, y, z), r1, r2)
            fail += 1
            if fail > 5:
                break
    if fail > 5:
        break
print('isPointOnMesh: 120000 сравнений, попаданий %d, расхождений нет' % hits3
      if fail == 0 else 'isPointOnMesh: ЕСТЬ РАСХОЖДЕНИЯ')

print('TOTAL FAILURES: %d' % fail)
if fail == 0:
    print('OK: подъём фильтра текстур и кэш AABB бит-в-бит сохраняют поведение '
          'SphereCast.sphereCast (col+позиция сферы), HeightComputer.computeHeight '
          '(высота/полигон/центры/underRoof) и Room.isPointOnMesh (первый индекс).')
