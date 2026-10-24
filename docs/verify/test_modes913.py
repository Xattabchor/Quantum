#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка оптимизаций режимов рисования 9 и 13 (и общих сегментных ядер
перспективы _1/_3/_3_HQ/_5/_6_HQ):

1. FastMath.div3(v) == v/3 (усечение к нулю, как в Java) - полный перебор
   -200000..200000 и все критические точки (0, +-131071/131072, +-2^31-1,
   MIN_VALUE). Быстрый путь: (|v|*43691)>>>17 при |v| < 131072.
2. FastMath.div255(x) == x/255 (усечение к нулю) - перебор -70000..70000 и
   границы 65534/65535/65536 (первый сбой тождества (a+1+(a>>8))>>8 - на
   65535 = 255*257; guard уходит в idiv).
3. FPq-замена сегментного деления: du = (x*FPq)>>fp вместо x/q,
   FPq = FP/q = 4096/q (та же замена, что режим 13 использует с оригинальной
   оптимизации). Проверяется |отклонение| в fp-единицах (1/4096 тексела):
   <= max(1, |x|/4096 + 1), т.е. субпиксельное; на границе сегмента u/v
   пересчитываются точным long-делением, поэтому отклонение не
   накапливается. q пробегает все реальные значения Main.q/PERSPECTIVE_STEP.

Запуск:  python3 docs/verify/test_modes913.py
"""
import sys

INT_MIN = -2147483648
INT_MAX = 2147483647
fails = 0


def tdiv(x, d):
    """Java x/d: усечение к нулю."""
    if x >= 0:
        return x // d
    return -((-x) // d)


# ---------- 1. div3 ----------
def java_div3(v):
    if v >= 0:
        if v < 131072:
            return (v * 43691) >> 17        # >>> для неотрицательных = >>
        return tdiv(v, 3)
    if v == INT_MIN:
        return tdiv(v, 3)
    if v > -131072:
        return -(((-v) * 43691) >> 17)
    return tdiv(v, 3)


n = 0
for v in range(-200000, 200001):
    if java_div3(v) != tdiv(v, 3):
        fails += 1
        if fails < 5:
            print('DIV3 MISMATCH v=%d: got=%d want=%d' % (v, java_div3(v), tdiv(v, 3)))
    n += 1
for v in [INT_MIN, INT_MAX, INT_MAX - 1, 131071, 131072, -131071, -131072,
          393215, 393216, 0, 1, 2, 3, -1, -2, -3]:
    if java_div3(v) != tdiv(v, 3):
        fails += 1
        print('DIV3 SPECIAL MISMATCH v=%d: got=%d want=%d' % (v, java_div3(v), tdiv(v, 3)))
    n += 1
print('div3: %d значений, расхождений с v/3 нет' % n if fails == 0 else 'div3: FAIL')

# ---------- 2. div255 ----------
def java_div255(x):
    a = -x if x < 0 else x
    if x == INT_MIN:
        a = INT_MIN                          # -x не помещается -> a < 0
    if a < 0 or a >= 65535:
        return tdiv(x, 255)
    q = (a + 1 + (a >> 8)) >> 8
    return -q if x < 0 else q


n255 = 0
f255 = 0
for x in range(-70000, 70001):
    if java_div255(x) != tdiv(x, 255):
        f255 += 1
        if f255 < 5:
            print('DIV255 MISMATCH x=%d: got=%d want=%d' % (x, java_div255(x), tdiv(x, 255)))
    n255 += 1
for x in [INT_MIN, INT_MAX, 65533, 65534, 65535, 65536, 65537, -65534, -65535,
          -65536, 16711680, 16711681, 65025]:   # 65025 = 255*255 (ar*light)
    if java_div255(x) != tdiv(x, 255):
        f255 += 1
        print('DIV255 SPECIAL MISMATCH x=%d: got=%d want=%d' % (x, java_div255(x), tdiv(x, 255)))
    n255 += 1
fails += f255
print('div255: %d значений, расхождений с x/255 нет' % n255 if f255 == 0 else 'div255: FAIL')

# ---------- 3. FPq ----------
FP, fp = 4096, 12
worst = {}
fq_fail = 0
for q in [12, 24, 32, 48, 64, 96, 128, 192, 256]:
    FPq = FP // q                            # Java int-деление, всё > 0
    maxerr = 0
    xs = set()
    for x in range(-1024, 1025):
        xs.add(x)
    for base in (-1048576, -65536, -4096, 4096, 65536, 1048576):
        for d in range(-3, 4):
            xs.add(base + d)
    for k in range(-320, 321):               # кратные q и соседние
        xs.add(k * q)
        xs.add(k * q + 1)
        xs.add(k * q - 1)
    for x in xs:
        du = (x * FPq) >> fp                 # Java >> арифметический = floor
        ref = tdiv(x, q)
        err = abs(du - ref)
        bound = max(1, abs(x) // FP + 1)
        if err > bound:
            fq_fail += 1
            if fq_fail < 5:
                print('FPQ MISMATCH q=%d x=%d: got=%d want=%d err=%d bound=%d'
                      % (q, x, du, ref, err, bound))
        maxerr = max(maxerr, err)
    worst[q] = maxerr
fails += fq_fail
print('FPq: макс. отклонение по q (в fp-единицах, 1/4096 тексела): %s'
      % dict(sorted(worst.items())))

# ---------- итог ----------
print('TOTAL FAILURES: %d' % fails)
if fails:
    sys.exit(1)
print('OK: div3 и div255 бит-в-бит равны Java-делению; FPq-замена отклоняется '
      'на субпиксельную величину (не накапливается: границы сегментов '
      'пересчитываются точным long-делением).')
