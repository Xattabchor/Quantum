#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка табличной замены преломления в "стекле" (режим рисования 7).

Оригинал (paint_glass / paintPers_glass, на каждый пиксель):
    coord = ((((col & 0xff0000) - 0x800000) * scale) >> 24)
          + ((((col & 0xff00) - 0x8000) * scale) >> 16) * w + x1;

Стало (таблицы TexturingAffine.glassEnsure, строятся один раз на размер
экрана):
    offX[R]  = ((R - 128) * scale) >> 8            // R = (col>>>16)&0xFF
    offYW[G] = (((G - 128) * scale) >> 8) * w      // G = (col>>>8)&0xFF
    coord = offX[R] + offYW[G] + x1;

Эквивалентность: (R-128)*65536*scale >> 24 == (R-128)*scale >> 8, потому
что арифметический сдвиг вправо - это floor-деление на степень двойки, а
множитель 65536/2**24 сокращается до 1/2**8. Аналогично для G:
(G-128)*256*scale >> 16 == (G-128)*scale >> 8. Переполнение int в
оригинале невозможно при scale <= 256 (128*65536*256 == 2**31 - граница,
которая заворачивается ровно в -2**31 и даёт тот же -128); реальный
scale = 64*(H+W)/560 достигает 256 только на экранах крупнее 1120x1120,
которых на J2ME-аппаратах нет (640x480 -> scale=128).

Тестируется в 32-битной знаковой арифметике Java (обёртка j32), сдвиги -
арифметические (Python >> для отрицательных = floor, как в Java).
"""
import sys

MASK32 = 0xFFFFFFFF


def j32(x):
    """Приведение к int32 как в Java (знаковое переполнение)."""
    x &= MASK32
    return x - 0x100000000 if x >= 0x80000000 else x


def orig_dx(col, scale):
    return j32(j32((col & 0xff0000) - 0x800000) * scale) >> 24


def orig_dy_w(col, scale, w):
    return j32((j32(j32((col & 0xff00) - 0x8000) * scale) >> 16) * w)


def table_t(i, scale):
    return j32((i - 128) * scale) >> 8


fails = 0
checked = 0

# 1) dx: все R и все scale, при которых оригинал не переполняется (1..256)
for scale in range(1, 257):
    for R in range(256):
        col = R << 16
        if orig_dx(col, scale) != table_t(R, scale):
            fails += 1
            if fails < 5:
                print('DX MISMATCH scale=%d R=%d: orig=%d table=%d'
                      % (scale, R, orig_dx(col, scale), table_t(R, scale)))
        checked += 1

# 2) dy*w: реальные экраны J2ME (scale выводится из размеров как в движке)
screens = [
    (128, 128), (128, 160), (176, 220), (240, 320), (320, 240),
    (240, 240), (360, 640), (640, 360), (240, 400), (480, 800),
    (640, 480), (480, 640), (800, 480),
]
# Экраны крупнее 1120x1120 (H+W > 2240 -> scale > 256) не тестируются:
# там переполняется УЖЕ оригинальная формула ((R-128)*65536*scale не
# помещается в int32) и её результат - мусор; табличная версия, наоборот,
# переполнение исключает. На реальных J2ME-аппаратах scale <= 128.
for w, H in screens:
    scale = (64 * (H + w)) // 560          # Java int-деление, всё положительно
    for G in range(256):
        col = G << 8
        if orig_dy_w(col, scale, w) != j32(table_t(G, scale) * w):
            fails += 1
            if fails < 5:
                print('DY MISMATCH %dx%d scale=%d G=%d: orig=%d table=%d'
                      % (w, H, scale, G, orig_dy_w(col, scale, w),
                         j32(table_t(G, scale) * w)))
        checked += 1
    # 3) полная формула coord-смещения на случайных col (все байты)
    import random
    rnd = random.Random(w * 1000 + H)
    for _ in range(2000):
        col = rnd.getrandbits(32)
        if j32(col) < 0:
            col = j32(col)                  # col может быть отрицательным int
        o = orig_dx(col, scale) + orig_dy_w(col, scale, w)
        R = (col >> 16) & 0xFF if col >= 0 else (j32(col) >> 16) & 0xFF
        G = (col >> 8) & 0xFF
        t = table_t(R, scale) + j32(table_t(G, scale) * w)
        if o != t:
            fails += 1
            if fails < 5:
                print('COORD MISMATCH %dx%d col=0x%08x: orig=%d table=%d'
                      % (w, H, col & MASK32, o, t))
        checked += 2

print('glass offset cases: %d  failures: %d' % (checked, fails))
if fails:
    sys.exit(1)
print('OK: таблицы преломления стекла бит-в-бит равны оригинальным '
      'умножениям для scale 1..256 и всех реальных размеров экрана.')
