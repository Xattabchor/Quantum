#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка целочисленной подготовки полигонов и погоды (пакет ARM9 §11):

  A. Python-перебор формул из TexturingPers/WeatherGenerator/FastMath:
     - div255: тождество (x+1+(x>>8))>>8 == x/255 (усечение) на всём int;
     - FastMath.div50: умножение на ceil(2^37/50) точно для всего int;
     - градиенты: ceil(2^37/dy), dy>=64 — ТОЧНОЕ усечение (delta<<12)/dy;
     - туман FAST_SETUP: floor(2^34/d) — погрешность не больше 1 из 255;
     - INV_DEPTH: ceil(2^24/d), d=1..65 — погрешность <= 1 единицы Q8;
     - рациональные константы погоды совпадают с исходными float-формулами.
  B. TestWeather (компилируется ECJ вместе с движком, выполняется на JRE):
     инварианты 400 кадров — границы, глубины, движение, перерождение частиц.

Запуск:  python3 docs/verify/test_setup_math.py
Переменные окружения: T (тулчейн), JAVA (путь к java). Код 0 = всё зелёное.
"""
import os
import random
import subprocess
import sys
from fractions import Fraction

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.dirname(os.path.dirname(HERE))
T = os.environ.get('T', os.path.expanduser('~/tools/j2me'))
JAVA = os.environ.get('JAVA', 'java')
WORK = os.path.join('/tmp', 'setuptest')
ECJ = ['org.eclipse.jdt.internal.compiler.batch.Main']
BOOT = os.pathsep.join([os.path.join(T, 'lib', n) for n in
                        ('cldc_1.1.jar', 'midp_2.0.jar', 'jsr184_1.1.jar',
                         'jsr075_stub.jar')])

INT_MIN = -2147483648
INT_MAX = 2147483647


def jdiv(x, y):
    """Деление Java: усечение к нулю."""
    q = abs(x) // abs(y)
    return q if (x < 0) == (y < 0) else -q


def to_int32(x):
    x &= 0xFFFFFFFF
    return x - 0x100000000 if x >= 0x80000000 else x


def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        print(r.stdout[-4000:])
        print(r.stderr[-4000:])
        raise SystemExit('команда завершилась с кодом %d: %s'
                         % (r.returncode, ' '.join(cmd[:6])))
    return r.stdout


fails = []


def part_a():
    rnd = random.Random(20261023)

    # --- div255: (x+1+(x>>8))>>8 для x>=0 и -((-x+1+(-x>>8))>>8) для x<0
    def div255(x):
        a = -x if x < 0 else x
        if a < 0 or a >= 65535:
            return jdiv(x, 255)
        q = (a + 1 + (a >> 8)) >> 8
        return -q if x < 0 else q

    bad = 0
    for k in range(0, (INT_MAX // 255) + 1, 1):
        c = k * 255
        for x in (c - 1, c, c + 1):
            if 0 <= x <= INT_MAX and div255(x) != jdiv(x, 255):
                bad += 1
    for x in range(0, 3000000):
        if div255(x) != jdiv(x, 255):
            bad += 1
    for _ in range(400000):
        x = rnd.randint(INT_MIN + 1, INT_MAX)   # INT_MIN идёт путём idiv
        if div255(x) != jdiv(x, 255):
            bad += 1
    print('  div255: расхождений %d (критические точки, 0..3М, 400К случайных;'
          ' guard a<65535, вне его idiv)' % bad)
    if bad:
        fails.append('div255 не тождественен x/255')

    # --- FastMath.div50: ceil(2^37/50) = 2748779070
    M50 = -(-2**37 // 50)
    assert M50 == 2748779070, M50

    def div50(v):
        if v >= 0:
            return (v * M50) >> 37
        return -((-v * M50) >> 37)

    bad = 0
    for v in range(0, 3000000):
        if div50(v) != jdiv(v, 50):
            bad += 1
    for k in range(0, INT_MAX // 50 + 1, 97):
        c = k * 50
        for v in (c - 1, c, c + 1):
            if INT_MIN < v <= INT_MAX:
                if div50(v) != jdiv(v, 50) or div50(-v) != jdiv(-v, 50):
                    bad += 1
    for _ in range(400000):
        v = rnd.randint(INT_MIN + 1, INT_MAX)
        if div50(v) != jdiv(v, 50):
            bad += 1
    print('  div50: расхождений %d' % bad)
    if bad:
        fails.append('div50 не тождественен v/50')

    # --- градиенты: m=ceil(2^37/dy), dy>=64, |x|<2^31 -> ТОЧНО
    def grad_magic(dy):
        return -(-(2**37) // dy)

    def div_grad(n, dy):
        m = grad_magic(dy)
        an = abs(n)
        q = (an * m) >> 37
        if an - q * dy < 0:
            q -= 1
        return -q if n < 0 else q

    bad = 0
    dys = [64, 65, 100, 239, 240, 321, 480, 1000, 4096, 65535, 1 << 20,
           (1 << 24) + 1]
    for dy in dys:
        pts = set()
        for k in range(0, min(INT_MAX // dy, 200000), 101):
            c = k * dy
            pts.update((c - 1, c, c + 1, -c, -(c + 1), -(c - 1)))
        for _ in range(20000):
            pts.add(rnd.randint(INT_MIN + 1, INT_MAX))
        for x in pts:
            if INT_MIN < x <= INT_MAX and div_grad(x, dy) != jdiv(x, dy):
                bad += 1
    print('  градиенты (ceil 2^37/dy, dy>=64): расхождений %d' % bad)
    if bad:
        fails.append('магия градиентов не точна')

    # --- туман FAST_SETUP: m=floor(2^34/d), d>=256 -> погрешность <=1
    def fog_magic(v, d):
        m = (2**34) // d
        q = (abs(v) * m) >> 34
        return -q if v < 0 else q

    worst = 0
    exact = tot = 0
    for d in (256, 257, 1000, 30000, 65536, 1000003):
        pts = set()
        for k in range(0, min(INT_MAX // d, 100000), 101):
            c = k * d
            pts.update((c - 1, c, c + 1, -c, -(c + 1), -(c - 1)))
        for _ in range(20000):
            pts.add(rnd.randint(INT_MIN + 1, INT_MAX))
        for v in pts:
            if INT_MIN < v <= INT_MAX:
                e = fog_magic(v, d) - jdiv(v, d)
                worst = max(worst, abs(e))
                exact += (e == 0)
                tot += 1
    print('  туман (floor 2^34/d, d>=256): макс. погрешность %d, точно %d/%d'
          % (worst, exact, tot))
    if worst > 1:
        fails.append('погрешность тумана больше 1/255')

    # --- INV_DEPTH: ceil(2^24/d), d=1..65 -> 0..+1 единицы Q8
    worst = 0
    for d in range(1, 66):
        m = -(-(2**24) // d)
        for xx in (0, 1, d - 1, d, d + 1, 768000, rnd.randint(0, 1 << 21)):
            e = ((xx * m) >> 24) - (xx // d)
            worst = max(worst, abs(e))
    print('  INV_DEPTH (ceil 2^24/d): макс. погрешность %d ед. Q8' % worst)
    if worst > 1:
        fails.append('INV_DEPTH даёт больше 1/256 пиксела')

    # --- рациональные константы погоды равны исходным float-формулам
    consts = [
        ('moveY  y*1.3*4/50 -> 6656/250',
         Fraction(6656, 250), Fraction(13, 10) * 4 * 256 / 50),
        ('moveY2 y*1.3*3/80/50 -> 624/2500',
         Fraction(624, 2500), Fraction(13, 10) * 3 * 256 / 80 / 50),
        ('move   zz/3.5 -> 11/700',
         Fraction(11, 700), Fraction(275, 100) / 50 / Fraction(35, 10)),
        ('moveB  zz/4 -> 11/800',
         Fraction(11, 800), Fraction(275, 100) / 50 / 4),
        ('mz     0.05/5/50 -> 8192/625 (Q16)',
         Fraction(8192, 625), Fraction(5, 100) / 5 / 50 * 65536),
        ('moveX  x*3/50 -> 768/50 (Q8)',
         Fraction(768, 50), Fraction(3, 1) * 256 / 50),
        ('снег   /50/4 -> /200', Fraction(1, 200), Fraction(1, 50) / 4),
    ]
    for name, got, want in consts:
        ok = got == want
        print('  %-42s %s' % (name, 'OK' if ok else 'РАСХОЖДЕНИЕ'))
        if not ok:
            fails.append('константа погоды: ' + name)


def part_b():
    for d in ('classes', 'stub', 'run'):
        os.makedirs(os.path.join(WORK, d), exist_ok=True)

    print('[B] компиляция движка и TestWeather')
    srcs = os.path.join(WORK, 'srcs.txt')
    with open(srcs, 'w') as f:
        for root in ('src', 'Utils/src', 'UtilsFPS/src', 'UtilsText/src'):
            for dirpath, _, files in os.walk(os.path.join(SRC, root)):
                for fn in files:
                    if fn.endswith('.java'):
                        f.write(os.path.join(dirpath, fn) + '\n')
    run([JAVA, '-cp', os.path.join(T, 'lib', 'ecj.jar')] + ECJ
        + ['-source', '1.4', '-target', '1.4', '-encoding', 'UTF-8',
           '-bootclasspath', BOOT, '-nowarn',
           '-d', os.path.join(WORK, 'classes'), '@' + srcs])
    run([JAVA, '-cp', os.path.join(T, 'lib', 'ecj.jar')] + ECJ
        + ['-source', '1.4', '-target', '1.4', '-encoding', 'UTF-8',
           '-bootclasspath', BOOT, '-nowarn',
           '-d', os.path.join(WORK, 'stub'),
           os.path.join(HERE, 'stub', 'code', 'utils', 'Main.java')])
    run([JAVA, '-cp', os.path.join(T, 'lib', 'ecj.jar')] + ECJ
        + ['-source', '1.4', '-target', '1.4', '-encoding', 'UTF-8',
           '-bootclasspath', BOOT, '-nowarn',
           '-classpath', os.path.join(WORK, 'classes'),
           '-d', os.path.join(WORK, 'run'),
           os.path.join(HERE, 'TestWeather.java')])

    out = run([JAVA, '-cp', os.pathsep.join([
        os.path.join(T, 'lib', 'cldc_1.1.jar'),
        os.path.join(T, 'lib', 'midp_2.0.jar'),
        os.path.join(WORK, 'stub'),
        os.path.join(WORK, 'classes'),
        os.path.join(WORK, 'run')]), 'TestWeather'])
    for line in out.splitlines():
        print('   ', line)
    if 'WEATHER OK' not in out:
        fails.append('TestWeather: инварианты нарушены')


def main():
    print('[A] формулы целочисленной подготовки и погоды')
    part_a()
    part_b()
    print()
    if fails:
        print('ПРОВАЛЕНО (%d):' % len(fails))
        for f in fails:
            print('  -', f)
        return 1
    print('Подготовка полигонов: градиенты и /255 точны, туман +/-1 из 255;')
    print('div50 точен для всего int; погода Q8 держит инварианты 400 кадров.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
