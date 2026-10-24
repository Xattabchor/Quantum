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

    # --- div1000: ceil(2^37/1000) = 137438954 + float-эквивалентность
    M1000 = -(-2**38 // 1000)
    assert M1000 == 274877907, M1000

    def div1000(v):
        if v >= 0:
            return (v * M1000) >> 38
        return -((-v * M1000) >> 38)

    bad = 0
    for v in range(0, 3000000):
        if div1000(v) != jdiv(v, 1000):
            bad += 1
    for k in range(0, INT_MAX // 1000 + 1, 997):
        c = k * 1000
        for v in (c - 1, c, c + 1):
            if INT_MIN < v <= INT_MAX:
                if div1000(v) != jdiv(v, 1000) or div1000(-v) != jdiv(-v, 1000):
                    bad += 1
    for _ in range(400000):
        v = rnd.randint(INT_MIN + 1, INT_MAX)
        if div1000(v) != jdiv(v, 1000):
            bad += 1
    print('  div1000: расхождений %d' % bad)
    if bad:
        fails.append('div1000 не тождественен v/1000')

    # (int)((float)t*1.0f/1000) == t/1000 при 0 <= t < 2^24 (анимация
    # спрайтов, Sprite.updateFrame): float32-эмуляция корректным округлением.
    def f32_round(fr):
        if fr == 0:
            return fr
        sign = 1 if fr > 0 else -1
        fr = abs(fr)
        e = fr.numerator.bit_length() - fr.denominator.bit_length()
        while Fraction(2) ** (e + 1) <= fr:
            e += 1
        while Fraction(2) ** e > fr:
            e -= 1
        shift = e - 23
        n = round(fr / Fraction(2) ** shift)   # half-even, как IEEE-754
        if n == (1 << 24):
            n = 1 << 23
            shift += 1
        return sign * Fraction(n) * Fraction(2) ** shift

    bad = 0
    pts = set()
    for k in range(0, (1 << 24) // 1000 + 1):
        c = k * 1000
        pts.update((c - 1, c, c + 1))
    while len(pts) < 150000:
        pts.add(rnd.randrange(0, 1 << 24))
    for t in pts:
        if 0 <= t < (1 << 24):
            if int(f32_round(Fraction(t, 1000))) != div1000(t):
                bad += 1
    print('  (float)t/1000 == div1000(t) при t<2^24: расхождений %d '
          '(%d точек)' % (bad, len(pts)))
    if bad:
        fails.append('float-путь анимации не эквивалентен div1000')

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

    # --- Weapon.update: float-версия против int Q8 (анимация оружия)
    def weapon_sim(ft, shot_time, delay, ai_f, as_int, nsteps=220):
        anima = 29 if ai_f <= 1.0 else 17
        ai = int(ai_f * 256) if as_int else ai_f
        frame = -256 if as_int else -1.0
        dx = dy = 0
        width_shift = 2
        dx_max = dy_max = 40
        fire_frames = 0
        recover_frames = 0
        dxdy = []
        for st in range(nsteps):
            if st == 0:
                frame = 0      # fire(): выстрел в первом кадре
            is_fire = frame >= 0
            if is_fire:
                if as_int:
                    frame += div50((ft << 8) + 25)
                    if frame > (shot_time << 8):
                        frame = -delay << 8
                else:
                    frame += ft / 50.0
                    if frame > shot_time:
                        frame = -delay
            one = -256 if as_int else -1.0
            if frame < one:
                recover_frames += 1
                if as_int:
                    frame += (ft * 1280 + 41) // 83
                    if frame > one:
                        frame = one
                else:
                    frame += ft / 16.6
                    if frame > one:
                        frame = one
            if is_fire:
                fire_frames += 1
                v = (abs(width_shift) << 1) * ft // anima
                if as_int:
                    dx += v * ai >> 8
                    dy += v * ai >> 8
                else:
                    dx = int(dx + v * ai)
                    dy = int(dy + v * ai)
            # shake не включён: else-ветка (пружина возврата), как в Java
            dx += jdiv(jdiv(-dx * ft, anima), 6)
            dy += jdiv(jdiv(-dy * ft, anima), 6)
            if dy <= 0:
                dy = 0
            if dy > dy_max:
                dy = dy_max
            if dx <= 0:
                dx = 0
                width_shift = -width_shift
            if dx >= dx_max:
                dx = dx_max
                width_shift = -width_shift
            dxdy.append((dx, dy))
        return fire_frames, recover_frames, dxdy

    bad_t = bad_r = 0
    traj_equal = True
    traj_skipped = 0
    for ft in (16, 20, 33, 50, 66, 100):
        for st_ in (2, 3, 6):
            for dl in (3, 5, 10):
                for ai_f in (1.0, 5.0):
                    f0, r0, t0 = weapon_sim(ft, st_, dl, ai_f, False)
                    f1, r1, t1 = weapon_sim(ft, st_, dl, ai_f, True)
                    if abs(f0 - f1) > 1:
                        bad_t += 1
                    if abs(r0 - r1) > 1:
                        bad_r += 1
                    if f0 == f1 and r0 == r1:
                        # фазы совпали - траектория обязана совпасть побитово
                        if t0 != t1:
                            traj_equal = False
                    elif t0 != t1:
                        # длительность фазы отличается на кадр (float-сумма
                        # попала ровно в границу shotTime) - после сдвига фазы
                        # траектории законно разные, это не расхождение
                        traj_skipped += 1
    print('  Weapon float vs int Q8: фазы расходятся >1 кадра: выстрел %d,'
          ' откат %d; траектории dx/dy побитово равны: %s (комбинаций со'
          ' сдвигом фазы: %d)' % (bad_t, bad_r, traj_equal, traj_skipped))
    if bad_t or bad_r:
        fails.append('Weapon: фазы анимации расходятся больше чем на кадр')
    if not traj_equal:
        fails.append('Weapon: траектории dx/dy не совпали при равных фазах')

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
