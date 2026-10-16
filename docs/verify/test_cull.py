#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка BoundingBox.maybeVisible() (FAST_CULL) против полного теста isVisible().

Полный тест (оригинал, src/code/Rendering/Meshes/BoundingBox.java):
  8 углов бокса преобразуются матрицей и проецируются ровно так же, как
  DirectX7.transformAndProjectVertices(), затем

    return !( maxX < x1 || minX > x2 || maxY < y1 || minY > y2 ||
              minZ >= 0 || -maxZ > drDist );

Быстрый тест обязан быть КОНСЕРВАТИВНЫМ:
    maybeVisible() == False  =>  полный тест вернул бы false.
Обратное не требуется (быстрый тест имеет право пропустить объект на полный).

Эмуляция целочисленной семантики Java: 32 бита со знаком, >> - арифметический
сдвиг (как в Python), / - усечение к нулю (НЕ floor), переполнение - по модулю 2**32.
"""
import random
import sys

MASK = 0xFFFFFFFF


def i32(x):
    x &= MASK
    return x - 0x100000000 if x >= 0x80000000 else x


def imul(a, b):
    return i32(a * b)


def sar(x, s):
    """Java '>>' для int: арифметический сдвиг, совпадает с Python."""
    return i32(i32(x) >> s)


def idiv(a, b):
    """Java '/' для int: усечение к нулю."""
    a = i32(a)
    b = i32(b)
    q = abs(a) // abs(b)
    return i32(-q if (a < 0) != (b < 0) else q)


FP_MATRIX = 14          # Matrix.fp
FP_PROJ = FP_MATRIX - 2  # локальный fp в transformAndProjectVertices


def project_corner(m, x, y, z, distX, distY, centreX, centreY):
    """Точно как DirectX7.transformAndProjectVertices(Vertex[], Matrix)."""
    m00 = sar(m[0], 2); m01 = sar(m[1], 2); m02 = sar(m[2], 2); m03 = m[3]
    m10 = sar(m[4], 2); m11 = sar(m[5], 2); m12 = sar(m[6], 2); m13 = m[7]
    m20 = sar(m[8], 2); m21 = sar(m[9], 2); m22 = sar(m[10], 2); m23 = m[11]

    sx = i32(m03 + sar(imul(x, m00), FP_PROJ) + sar(imul(y, m01), FP_PROJ)
             + sar(imul(z, m02), FP_PROJ))
    sy = i32(-m13 - sar(imul(x, m10), FP_PROJ) - sar(imul(y, m11), FP_PROJ)
             - sar(imul(z, m12), FP_PROJ))
    rz = i32(m23 + sar(imul(x, m20), FP_PROJ) + sar(imul(y, m21), FP_PROJ)
             + sar(imul(z, m22), FP_PROJ))
    if rz < 0:
        sx = idiv(imul(sx, distX), i32(-rz + distX))
        sy = idiv(imul(sy, distY), i32(-rz + distY))
    return i32(sx + centreX), i32(sy + centreY), rz


def exact_visible(m, box, x1, y1, x2, y2, distX, distY, centreX, centreY, drDist):
    (minx, miny, minz, maxx, maxy, maxz) = box
    corners = [
        (minx, miny, minz), (maxx, miny, minz),
        (minx, miny, maxz), (maxx, miny, maxz),
        (minx, maxy, minz), (maxx, maxy, minz),
        (minx, maxy, maxz), (maxx, maxy, maxz),
    ]
    minX = maxX = minY = maxY = minZ = maxZ = None
    for (cx, cy, cz) in corners:
        sx, sy, rz = project_corner(m, cx, cy, cz, distX, distY, centreX, centreY)
        if minX is None:
            minX = maxX = sx; minY = maxY = sy; minZ = maxZ = rz
        else:
            if sx < minX: minX = sx
            if sx > maxX: maxX = sx
            if sy < minY: minY = sy
            if sy > maxY: maxY = sy
            if rz < minZ: minZ = rz
            if rz > maxZ: maxZ = rz
    return not (maxX < x1 or minX > x2 or maxY < y1 or minY > y2
                or minZ >= 0 or -maxZ > drDist)


def max3(a, b, c):
    a = -a if a < 0 else a
    b = -b if b < 0 else b
    c = -c if c < 0 else c
    return max(a, b, c)


BAILED = {}


def bail(why):
    BAILS = BAILED
    BAILS[why] = BAILS.get(why, 0) + 1
    return True


def maybe_visible(m, box, x1, y1, x2, y2, distX, distY, centreX, centreY, drDist):
    """Точная копия BoundingBox.maybeVisible() + кэша из set()."""
    (minx, miny, minz, maxx, maxy, maxz) = box
    cull_ok = maxx >= minx and maxy >= miny and maxz >= minz
    cx = cy = cz = l1 = l1x = l1y = l1z = 0
    if cull_ok:
        cx = sar(minx + maxx, 1)
        cy = sar(miny + maxy, 1)
        cz = sar(minz + maxz, 1)
        l1x = sar(maxx - minx, 1)
        l1y = sar(maxy - miny, 1)
        l1z = sar(maxz - minz, 1)
        l1 = i32(l1x + l1y + l1z)
        if l1 > (1 << 20):
            cull_ok = False
    if not cull_ok:
        return bail('cull_ok=false')
    if x1 > x2 or y1 > y2:
        return bail('перевёрнутый прямоугольник')

    m00 = sar(m[0], 2); m01 = sar(m[1], 2); m02 = sar(m[2], 2)
    m10 = sar(m[4], 2); m11 = sar(m[5], 2); m12 = sar(m[6], 2)
    m20 = sar(m[8], 2); m21 = sar(m[9], 2); m22 = sar(m[10], 2)
    x, y, z = cx, cy, cz

    hx = sar(l1x + 15, 4); hy = sar(l1y + 15, 4); hz = sar(l1z + 15, 4)

    a00 = -m00 if m00 < 0 else m00; a01 = -m01 if m01 < 0 else m01; a02 = -m02 if m02 < 0 else m02
    a10 = -m10 if m10 < 0 else m10; a11 = -m11 if m11 < 0 else m11; a12 = -m12 if m12 < 0 else m12
    a20 = -m20 if m20 < 0 else m20; a21 = -m21 if m21 < 0 else m21; a22 = -m22 if m22 < 0 else m22

    rx = max3(a00, a01, a02); ry = max3(a10, a11, a12); rzm = max3(a20, a21, a22)
    if rx > 8192 or ry > 8192 or rzm > 8192:
        return bail('матрица с большим масштабом')

    cmax = i32(max3(-x if x < 0 else x, -y if y < 0 else y, -z if z < 0 else z) + l1)
    if cmax < 0:
        return bail('переполнение оценки центра')
    rmax = max3(rx, ry, rzm)
    shift = 3 if rmax <= 1024 else (2 if rmax <= 2048 else (1 if rmax <= 4096 else 0))
    if cmax > ((1 << 17) << shift):
        return bail('координаты вне целочисленных границ')

    radX = i32(sar(imul(a00, hx) + 255, 8) + sar(imul(a01, hy) + 255, 8) + sar(imul(a02, hz) + 255, 8))
    radY = i32(sar(imul(a10, hx) + 255, 8) + sar(imul(a11, hy) + 255, 8) + sar(imul(a12, hz) + 255, 8))
    radZ = i32(sar(imul(a20, hx) + 255, 8) + sar(imul(a21, hy) + 255, 8) + sar(imul(a22, hz) + 255, 8))

    sx = i32(m[3] + sar(imul(x, m00), FP_PROJ) + sar(imul(y, m01), FP_PROJ)
             + sar(imul(z, m02), FP_PROJ))
    sy = i32(-m[7] - sar(imul(x, m10), FP_PROJ) - sar(imul(y, m11), FP_PROJ)
             - sar(imul(z, m12), FP_PROJ))
    rz = i32(m[11] + sar(imul(x, m20), FP_PROJ) + sar(imul(y, m21), FP_PROJ)
             + sar(imul(z, m22), FP_PROJ))

    if i32(rz - radZ) >= 0:
        return False
    if i32(rz + radZ) < -drDist:
        return False

    dzX = i32(distX - rz - radZ)
    dzY = i32(distY - rz - radZ)
    if dzX <= 0 or dzY <= 0:
        return bail('бокс пересекает плоскость камеры')

    ax = -sx if sx < 0 else sx
    ay = -sy if sy < 0 else sy
    nx = i32(ax + radX)
    ny = i32(ay + radY)
    if nx < 0 or ny < 0:
        return bail('переполнение nx/ny')
    if imul(sar(nx, 8), distX) > 8000000:
        return bail('переполнение по X')
    if imul(sar(ny, 8), distY) > 8000000:
        return bail('переполнение по Y')

    half = idiv(imul(nx, distX), dzX)
    if half > (1 << 24):
        return bail('занимает весь экран X')
    if i32(centreX + half) < x1:
        return False
    if i32(centreX - half) > x2:
        return False

    half = idiv(imul(ny, distY), dzY)
    if half > (1 << 24):
        return bail('занимает весь экран Y')
    if i32(centreY + half) < y1:
        return False
    if i32(centreY - half) > y2:
        return False
    return True


# ------------------------------------------------------------------ наборы данных
def fov_params(width, height, fov):
    """DirectX7.resize()/updateFov(): centreX, centreY, distX, distY."""
    import math
    centreX = width // 2
    centreY = height // 2
    distX = int(centreX / math.tan(fov * math.pi / 360))
    distY = int(centreY / math.tan(fov * math.pi / 360))
    distX = distX * height // width
    return centreX, centreY, distX, distY


def rigid_matrix(ay, ax, tx, ty, tz, fp=FP_MATRIX):
    """Поворот вокруг Y, затем вокруг X - целочисленные таблицы, как в Matrix."""
    import math
    FP = 1 << fp
    sy = math.sin(math.radians(ay)); cy = math.cos(math.radians(ay))
    sx = math.sin(math.radians(ax)); cx = math.cos(math.radians(ax))
    # rotY * rotX, записанные в фиксированной точке Matrix (FP = 1 << 14)
    m00 = int(round(cy * FP));      m01 = 0;                 m02 = int(round(-sy * FP))
    m10 = int(round(sx * sy * FP)); m11 = int(round(cx * FP)); m12 = int(round(sx * cy * FP))
    m20 = int(round(cx * sy * FP)); m21 = int(round(-sx * FP)); m22 = int(round(cx * cy * FP))
    return [i32(m00), i32(m01), i32(m02), tx,
            i32(m10), i32(m11), i32(m12), ty,
            i32(m20), i32(m21), i32(m22), tz]


def random_matrix(rnd, scale_hi=False):
    lim = 65536 if scale_hi else 16384
    return [rnd.randint(-lim, lim) if i % 4 != 3 else rnd.randint(-2000000, 2000000)
            for i in range(12)]


def main():
    import math
    rnd = random.Random(20261016)

    screens = []
    for (w, h, fov) in [(240, 320, 74), (240, 320, 60), (240, 320, 90),
                        (320, 240, 74), (176, 220, 74), (128, 128, 100),
                        (360, 640, 74)]:
        cx, cy, dx, dy = fov_params(w, h, fov)
        screens.append((w, h, fov, cx, cy, dx, dy))

    rects = [(0, 0, None, None), (20, 20, None, None), (-50, -50, None, None),
             (100, 150, None, None)]

    drDists = [30000000, 1000000, 200000, 20000]

    total = 0
    bad = 0
    exact_reject = 0
    fast_reject = 0
    fast_saved = 0          # быстрый тест отсек => полный тест не выполнялся
    fast_overhead = 0       # быстрый тест пропустил объект дальше
    examples = []

    cases = []

    # 1. Систематический обход: бокс в каждом октанте на разных дистанциях
    for (w, h, fov, cx, cy, dx, dy) in screens[:3]:
        m = rigid_matrix(rnd.randrange(360), rnd.randrange(-40, 40), 0, 0, 0)
        for gx in range(-4, 5):
            for gy in range(-4, 5):
                for gz in range(-6, 3):
                    hw = rnd.choice([50, 500, 5000, 40000])
                    px, py, pz = gx * 60000, gy * 40000, gz * 80000
                    cases.append((m, (px - hw, py - hw, pz - hw, px + hw, py + hw, pz + hw),
                                  (0, 0, w - 1, h - 1), dx, dy, cx, cy, 30000000))

    # 2. Случайные матрицы (включая вырожденные/с нулями) и случайные боксы
    for i in range(120000):
        (w, h, fov, cx, cy, dx, dy) = rnd.choice(screens)
        kind = rnd.random()
        if kind < 0.45:
            m = rigid_matrix(rnd.randrange(360), rnd.randrange(-90, 90),
                             rnd.randint(-1500000, 1500000), rnd.randint(-1500000, 1500000),
                             rnd.randint(-1500000, 1500000))
        elif kind < 0.6:
            m = [0] * 12
            m[0] = m[5] = m[10] = 1 << FP_MATRIX
            m[3] = rnd.randint(-1000000, 1000000)
            m[7] = rnd.randint(-1000000, 1000000)
            m[11] = rnd.randint(-1000000, 1000000)
        elif kind < 0.75:
            m = random_matrix(rnd)
        else:
            m = random_matrix(rnd, scale_hi=True)

        style = rnd.random()
        if style < 0.5:      # бокс NPC-размера рядом с камерой
            px = rnd.randint(-150000, 150000)
            py = rnd.randint(-150000, 150000)
            pz = rnd.randint(-400000, 150000)
            hw = rnd.randint(0, 8000)
        elif style < 0.8:    # крупный бокс (комната/чанк)
            px = rnd.randint(-400000, 400000)
            py = rnd.randint(-400000, 400000)
            pz = rnd.randint(-900000, 400000)
            hw = rnd.randint(0, 200000)
        else:                # крайности, включая вырожденные
            px = rnd.randint(-3000000, 3000000)
            py = rnd.randint(-3000000, 3000000)
            pz = rnd.randint(-3000000, 3000000)
            hw = rnd.choice([0, 1, 100000, 600000, 1200000])
        if rnd.random() < 0.03:      # вырожденный бокс (min > max)
            box = (px + hw, py + hw, pz + hw, px - hw, py - hw, pz - hw)
        else:
            hx = hw if rnd.random() < 0.7 else rnd.randint(0, hw + 1)
            hy = hw if rnd.random() < 0.7 else rnd.randint(0, hw + 1)
            hz = hw if rnd.random() < 0.7 else rnd.randint(0, hw + 1)
            box = (px - hx, py - hy, pz - hz, px + hx, py + hy, pz + hz)

        r = rnd.choice(rects)
        rect = (r[0], r[1], r[2] if r[2] is not None else w - 1,
                r[3] if r[3] is not None else h - 1)
        cases.append((m, box, rect, dx, dy, cx, cy, rnd.choice(drDists)))

    C = dict(total=0, bad=0, exact_reject=0, fast_reject=0, overhead=0)

    def check_case(c):
        (m, box, rect, dx, dy, cx, cy, drd) = c
        x1, y1, x2, y2 = rect
        ev = exact_visible(m, box, x1, y1, x2, y2, dx, dy, cx, cy, drd)
        fv = maybe_visible(m, box, x1, y1, x2, y2, dx, dy, cx, cy, drd)
        C['total'] += 1
        if not ev:
            C['exact_reject'] += 1
        if not fv:
            C['fast_reject'] += 1
            if ev:            # отсекли видимое - недопустимо
                C['bad'] += 1
                if len(examples) < 5:
                    examples.append(c)
        else:
            C['overhead'] += 1

    realistic = []
    for i in range(60000):
        (w, h, fov, cx, cy, dx, dy) = rnd.choice(screens)
        m = rigid_matrix(rnd.randrange(360), rnd.randrange(-60, 60),
                         rnd.randint(-60000, 60000), rnd.randint(-40000, 40000),
                         rnd.randint(-30000, 30000))
        # игрок в центре уровня, объекты вокруг: от 500 до 60000 единиц
        ang = rnd.uniform(0, 6.283)
        dist = rnd.choice([rnd.randint(300, 3000), rnd.randint(3000, 20000),
                           rnd.randint(20000, 60000)])
        px = int(dist * math.cos(ang)); py = rnd.randint(-8000, 8000)
        pz = int(dist * math.sin(ang))
        hw = rnd.choice([400, 900, 2000, 6000])
        realistic.append((m, (px - hw, py - hw, pz - hw, px + hw, py + hw, pz + hw),
                          (0, 0, w - 1, h - 1), dx, dy, cx, cy, rnd.choice(drDists)))

    def run_set(name, subset):
        t0, b0, e0, f0 = C['total'], C['bad'], C['exact_reject'], C['fast_reject']
        for c in subset:
            check_case(c)
        n = C['total'] - t0
        print('%-22s случаев %7d | полный отверг %6.1f%% | быстрый отверг %6.1f%%'
              ' | ошибок %d' % (name, n,
                                100.0 * (C['exact_reject'] - e0) / max(1, n),
                                100.0 * (C['fast_reject'] - f0) / max(1, n),
                                C['bad'] - b0))

    run_set('экстремальный набор', cases)
    run_set('реалистичный набор', realistic)

    total = C['total']; bad = C['bad']
    exact_reject = C['exact_reject']; fast_reject = C['fast_reject']
    fast_overhead = C['overhead']; fast_saved = fast_reject

    print('всего случаев            :', total)
    print('полный тест отверг       :', exact_reject,
          '(%.1f%%)' % (100.0 * exact_reject / total))
    print('быстрый тест отверг      :', fast_reject,
          '(%.1f%% от всех)' % (100.0 * fast_reject / total))
    if exact_reject:
        print('доля отвергнутых полным, отсеянных быстрым: %.1f%%'
              % (100.0 * fast_reject / exact_reject))
    print('прошло дальше (накладные):', fast_overhead,
          '(%.1f%%)' % (100.0 * fast_overhead / total))
    print('ОШИБОК (отсечён видимый) :', bad)
    print('выходы на полный тест по причинам:')
    for k, v in sorted(BAILED.items(), key=lambda kv: -kv[1]):
        print('   %-34s %7d (%.1f%%)' % (k, v, 100.0 * v / total))
    for e in examples:
        print('  пример:', e)
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
