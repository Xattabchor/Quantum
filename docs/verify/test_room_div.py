#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка Room.divChunk()/divChunkRender() и FastMath.shiftFor().

Деление на chunkSize/chunkSizeRender (степени двойки, задаваемые уровнем)
заменено сдвигом, потому что деление на переменный делитель на ARM9 -
библиотечная подпрограмма, а таких делений на персонажа за кадр 8+.
Подмена обязана быть ТОЧНОЙ: у Java '/' усечение к нулю, а у '>>' - пол,
поэтому для отрицательных значений введена поправка. Скрипт сравнивает
реализацию со знаковой семантикой Java для степеней двойки, не-степеней,
нуля, отрицательных делителей и крайних значений int.

Запуск:  python3 docs/verify/test_room_div.py
Ожидание: "TOTAL FAILURES: 0"
"""
import sys

INT_MIN = -(2 ** 31)
INT_MAX = 2 ** 31 - 1


def java_int(x):
    """Приведение к 32-битному знаковому int (как в Java)."""
    x &= 0xFFFFFFFF
    return x - 0x100000000 if x >= 0x80000000 else x


def java_div(a, b):
    """a / b в семантике Java: усечение к нулю."""
    q = abs(a) // abs(b)
    return q if (a < 0) == (b < 0) else -q


def shift_for(n):
    """Копия FastMath.shiftFor(): показатель степени двойки либо -1."""
    n = java_int(n)
    if n <= 0 or (n & (n - 1)) != 0:
        return -1
    s = 0
    while java_int(1 << s) != n:
        s += 1
    return s


def div_chunk(v, cs):
    """Копия Room.divChunk() (кэш сдвига опущен - он не влияет на результат)."""
    v = java_int(v)
    cs = java_int(cs)
    s = shift_for(cs)
    if s < 0:
        return java_div(v, cs)
    # v >> s в Java - арифметический сдвиг, то есть пол; в Python для
    # отрицательных v '>>' тоже даёт пол, поэтому копируем напрямую.
    q = v >> s
    if v < 0 and java_int(q << s) != v:
        q += 1
    return java_int(q)


def values():
    yield 0
    yield 1
    yield -1
    yield INT_MAX
    yield INT_MIN
    yield INT_MIN + 1
    for v in range(-70000, 70001, 997):
        yield v
    for v in (-123456789, 123456789, -2147483647, 2147483646):
        yield v


def chunk_sizes():
    for cs in (1, 2, 4, 8, 16, 512, 1024, 2048, 4096, 8192, 16384, 65536,
               3, 1000, 5000, 12345, 0, -4096, -1, INT_MAX, INT_MIN):
        yield cs


def main():
    failures = 0
    checked = 0
    vals = list(values())
    for cs in chunk_sizes():
        for v in vals:
            if cs == 0:
                continue                      # деление на ноль бросает исключение и там, и там
            expected = java_div(v, cs)
            got = div_chunk(v, cs)
            checked += 1
            if got != expected:
                failures += 1
                if failures <= 8:
                    print('FAIL chunkSize=%d v=%d expected=%d got=%d' % (cs, v, expected, got))

    # shiftFor: степени двойки и всё остальное
    for n in (1, 2, 4, 8, 4096, 65536, 1 << 30):
        if shift_for(n) < 0:
            failures += 1
            print('FAIL shiftFor(%d) = %d' % (n, shift_for(n)))
    for n in (0, -1, -4096, 3, 1000, 5000, INT_MAX, INT_MIN, 6):
        if shift_for(n) != -1:
            failures += 1
            print('FAIL shiftFor(%d) = %d, ожидалось -1' % (n, shift_for(n)))

    print('checked pairs: %d' % checked)
    print('TOTAL FAILURES: %d' % failures)
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
