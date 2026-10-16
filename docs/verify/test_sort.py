#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка гибридной сортировки RenderObjectBuffer.sort().

sort() сортирует буфер полигонов по УБЫВАНИЮ obj.sz (отрисовка идёт с конца
списка). Теперь короткие и почти упорядоченные диапазоны досортировываются
вставками (insertionSortLimited), а при исчерпании бюджета смещений диапазон
дорезвится прежней быстрой сортировкой. Скрипт повторяет обе реализации на
Python и проверяет, что результат гибрида - тот же корректный порядок по
убыванию, что и у оригинала, и что набор элементов не изменился.

Запуск:  python3 docs/verify/test_sort.py
Ожидание: "failures: 0"
"""
import random
import sys

sys.setrecursionlimit(100000)

MAX_BUDGET = 2 ** 62


def insertion_sort_limited(b, start, end, budget):
    """Точная копия RenderObjectBuffer.insertionSortLimited()."""
    work = 0
    for i in range(start + 1, end + 1):
        v = b[i]
        vsz = v
        j = i - 1
        while j >= start and b[j] < vsz:
            b[j + 1] = b[j]
            j -= 1
            work += 1
            if work > budget:
                b[j + 1] = v
                return False
        b[j + 1] = v
    return True


def hybrid_sort(b, start, end):
    """Точная копия нового RenderObjectBuffer.sort()."""
    if start >= end:
        return
    n = end - start + 1
    budget = MAX_BUDGET if (end - start) <= 24 else n * 2
    if insertion_sort_limited(b, start, end, budget):
        return

    opora = b[(start + end) // 2]
    first, second = start, end
    while first <= second:
        while b[first] > opora:
            first += 1
        while b[second] < opora:
            second -= 1
        if first <= second:
            b[first], b[second] = b[second], b[first]
            first += 1
            second -= 1
    if start < second:
        hybrid_sort(b, start, second)
    if end > first:
        hybrid_sort(b, first, end)


def original_sort(b, start, end):
    """Прежняя быстрая сортировка (для сравнения)."""
    if start >= end:
        return
    opora = b[(start + end) // 2]
    first, second = start, end
    while first <= second:
        while b[first] > opora:
            first += 1
        while b[second] < opora:
            second -= 1
        if first <= second:
            b[first], b[second] = b[second], b[first]
            first += 1
            second -= 1
    if start < second:
        original_sort(b, start, second)
    if end > first:
        original_sort(b, first, end)


def is_descending(a):
    return all(a[i] >= a[i + 1] for i in range(len(a) - 1))


def build_cases():
    random.seed(7)
    cases = []
    for n in [0, 1, 2, 3, 5, 16, 17, 24, 25, 64, 200, 1000, 3000]:
        cases.append([random.randint(-5000, 5000) for _ in range(n)])  # случайный
        cases.append(list(range(n, 0, -1)))                            # уже отсортирован
        cases.append(list(range(1, n + 1)))                            # обратный порядок
        cases.append([random.randint(0, 3) for _ in range(n)])         # много равных
        if n > 1:
            nearly = sorted((random.randint(-5000, 5000) for _ in range(n)), reverse=True)
            for _ in range(max(1, n // 20)):                           # почти отсортирован
                i = random.randrange(n)
                j = random.randrange(n)
                nearly[i], nearly[j] = nearly[j], nearly[i]
            cases.append(nearly)
    return cases


def main():
    failures = 0
    cases = build_cases()
    for case in cases:
        hybrid = case[:]
        original = case[:]
        hybrid_sort(hybrid, 0, len(hybrid) - 1)
        original_sort(original, 0, len(original) - 1)

        ok = (is_descending(hybrid)
              and sorted(hybrid) == sorted(case)
              and is_descending(original))
        if not ok:
            failures += 1
            if failures <= 5:
                print('FAIL n=%d descending=%s multiset=%s'
                      % (len(case), is_descending(hybrid), sorted(hybrid) == sorted(case)))

    print('sort cases: %d  failures: %d' % (len(cases), failures))
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
