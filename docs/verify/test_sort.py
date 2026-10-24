#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка гибридной сортировки RenderObjectBuffer.sort() (после фикса мерцания).

sort() сортирует буфер полигонов по УБЫВАНИЮ obj.sz (отрисовка идёт с конца
списка). Короткие и почти упорядоченные диапазоны досортировываются вставками
(insertionSortLimited), а при исчерпании бюджета смещений диапазон
дорезвится УСТОЙЧИВОЙ сортировкой слияниями (mergeSortStable).

Устойчивость критична: z-буфера нет, для компланарных полигонов (sz равны -
например, [MESH]-дверь в одной плоскости со стеной) порядок отрисовки
определяет видимость. Оба пути гибрида устойчивы, поэтому порядок равных
всегда равен порядку добавления и не меняется между кадрами. Прежний fallback
(быстрая сортировка поверх частично сдвинутого вставками массива) порядок
равных менял каждый кадр - [MESH]-объекты мерцали.

Элементы моделируются парами (sz, tag); tag = позиция в порядке добавления.
Скрипт проверяет:
  1. результат гибрида ТОЧНО равен устойчивому эталону sorted(key=-sz)
     (и порядок по убыванию, и порядок равных = порядок добавления);
  2. множество элементов сохраняется;
  3. оба пути (вставки и слияния) реально упражняются;
  4. межкадровая детерминированность: при дрейфе sz (движение камеры)
     порядок равных между кадрами не меняется;
  5. свидетель регрессии: СТАРЫЙ гибрид (вставки + быстрая сортировка)
     обязан нарушать порядок равных хотя бы на одном кейсе - иначе тест
     нечувствителен к починенному багу.

Запуск:  python3 docs/verify/test_sort.py
Ожидание: "failures: 0"
"""
import random
import sys

sys.setrecursionlimit(100000)

MAX_BUDGET = 2 ** 62

stats = {'insertion': 0, 'merge': 0}


def insertion_sort_limited(b, start, end, budget):
    """Точная копия RenderObjectBuffer.insertionSortLimited() (устойчивая)."""
    work = 0
    for i in range(start + 1, end + 1):
        v = b[i]
        vsz = v[0]
        j = i - 1
        while j >= start and b[j][0] < vsz:
            b[j + 1] = b[j]
            j -= 1
            work += 1
            if work > budget:
                b[j + 1] = v
                return False
        b[j + 1] = v
    return True


def merge_sort_stable(b, start, end, scratch):
    """Точная копия RenderObjectBuffer.mergeSortStable() (восходящая, устойчивая)."""
    n = end - start + 1
    tmp = scratch.get('arr')
    if tmp is None or len(tmp) < n:
        tmp = [None] * n
        scratch['arr'] = tmp
    tmp[0:n] = b[start:start + n]
    src_is_tmp = True
    width = 1
    while width < n:
        step = width << 1
        lo = 0
        while lo < n:
            mid = min(lo + width, n)
            hi = min(lo + step, n)
            i, j, k = lo, mid, lo
            if src_is_tmp:
                while i < mid and j < hi:
                    # при равенстве sz берём левый прогон (устойчиво)
                    if tmp[j][0] > tmp[i][0]:
                        b[start + k] = tmp[j]; k += 1; j += 1
                    else:
                        b[start + k] = tmp[i]; k += 1; i += 1
                while i < mid:
                    b[start + k] = tmp[i]; k += 1; i += 1
                while j < hi:
                    b[start + k] = tmp[j]; k += 1; j += 1
            else:
                while i < mid and j < hi:
                    if b[start + j][0] > b[start + i][0]:
                        tmp[k] = b[start + j]; k += 1; j += 1
                    else:
                        tmp[k] = b[start + i]; k += 1; i += 1
                while i < mid:
                    tmp[k] = b[start + i]; k += 1; i += 1
                while j < hi:
                    tmp[k] = b[start + j]; k += 1; j += 1
            lo += step
        src_is_tmp = not src_is_tmp
        width = step
    if src_is_tmp:
        b[start:start + n] = tmp[0:n]


def hybrid_sort(b, start, end, scratch, count=True):
    """Точная копия нового RenderObjectBuffer.sort()."""
    if start >= end:
        return
    n = end - start + 1
    budget = MAX_BUDGET if (end - start) <= 24 else n * 2
    if insertion_sort_limited(b, start, end, budget):
        if count:
            stats['insertion'] += 1
        return
    if count:
        stats['merge'] += 1
    merge_sort_stable(b, start, end, scratch)


def quick_sort_original(b, start, end):
    """Прежняя быстрая сортировка (нестабильная) - для свидетеля регрессии."""
    if start >= end:
        return
    opora = b[(start + end) // 2][0]
    first, second = start, end
    while first <= second:
        while b[first][0] > opora:
            first += 1
        while b[second][0] < opora:
            second -= 1
        if first <= second:
            b[first], b[second] = b[second], b[first]
            first += 1
            second -= 1
    if start < second:
        quick_sort_original(b, start, second)
    if end > first:
        quick_sort_original(b, first, end)


def old_hybrid_sort(b, start, end, scratch):
    """СТАРЫЙ гибрид (вставки + быстрая сортировка) - только для свидетеля регрессии."""
    if start >= end:
        return
    n = end - start + 1
    budget = MAX_BUDGET if (end - start) <= 24 else n * 2
    if insertion_sort_limited(b, start, end, budget):
        return
    quick_sort_original(b, start, end)


def stable_reference(case):
    """Устойчивый эталон: убывание sz, равные - в порядке добавления."""
    return sorted(case, key=lambda t: -t[0])


def build_cases():
    random.seed(7)
    cases = []
    for n in [0, 1, 2, 3, 5, 16, 17, 24, 25, 64, 200, 1000, 3000]:
        cases.append([(random.randint(-5000, 5000), i) for i in range(n)])   # случайный
        cases.append([(n - i, i) for i in range(n)])                          # уже отсортирован
        cases.append([(i + 1, i) for i in range(n)])                          # обратный порядок
        cases.append([(random.randint(0, 3), i) for i in range(n)])           # много равных
        if n > 1:
            vals = sorted((random.randint(-5000, 5000) for _ in range(n)), reverse=True)
            nearly = [(vals[i], i) for i in range(n)]
            for _ in range(max(1, n // 20)):                                  # почти отсортирован
                i = random.randrange(n)
                j = random.randrange(n)
                nearly[i], nearly[j] = nearly[j], nearly[i]
            cases.append(nearly)
    # Реалистичный кейс: стена (много равных sz) + компланарная дверь,
    # добавленная позже; камера чуть повёрнута - часть равных остаётся равной.
    wall = [(-700 + (i % 4) * 3, i) for i in range(120)]
    door = [(-700, 120 + i) for i in range(8)]
    cases.append(wall + door)
    cases.append(list(reversed(wall + door)))
    return cases


def check_frames(failures):
    """Межкадровая детерминированность: дрейф sz, порядок равных постоянен."""
    random.seed(11)
    n = 300
    # база: sz // 3 - много равных (компланарные полигоны)
    base = [(random.randint(-1000, 0) // 3, i) for i in range(n)]
    scratch = {}
    frames = 50
    bad = 0
    for f in range(frames):
        # кадр: sz плавно дрейфуют (движение камеры), равные остаются равными
        frame = [(sz + (f * (tag % 7)) % 5, tag) for sz, tag in base]
        expect = stable_reference(frame)
        work = frame[:]
        hybrid_sort(work, 0, len(work) - 1, scratch, count=False)
        if work != expect:
            bad += 1
        # порядок равных между собой = порядок добавления (тег растёт)
        i = 0
        while i < len(work):
            j = i
            while j + 1 < len(work) and work[j + 1][0] == work[i][0]:
                j += 1
            group = [work[k][1] for k in range(i, j + 1)]
            if group != sorted(group):
                bad += 1
            i = j + 1
    print('frames: %d  tie-order violations: %d' % (frames, bad))
    return failures + (1 if bad else 0)


def main():
    failures = 0
    cases = build_cases()
    scratch = {}
    old_dev = 0
    for case in cases:
        expect = stable_reference(case)

        hybrid = case[:]
        hybrid_sort(hybrid, 0, len(hybrid) - 1, scratch)
        if hybrid != expect or sorted(hybrid) != sorted(case):
            failures += 1
            if failures <= 5:
                print('FAIL n=%d hybrid != stable reference' % len(case))

        # свидетель: старый гибрид на кейсах с равными должен нарушать порядок
        old = case[:]
        old_hybrid_sort(old, 0, len(old) - 1, scratch)
        if old != expect:
            old_dev += 1

    print('sort cases: %d  failures: %d' % (len(cases), failures))
    print('paths exercised: insertion=%d merge=%d' % (stats['insertion'], stats['merge']))
    print('old hybrid tie-order deviations (witness): %d' % old_dev)
    if stats['insertion'] == 0 or stats['merge'] == 0:
        print('FAIL: one of the sort paths was never exercised')
        failures += 1
    if old_dev == 0:
        print('FAIL: witness found no deviation - test is insensitive to the fixed bug')
        failures += 1

    failures = check_frames(failures)

    print('TOTAL FAILURES: %d' % failures)
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
