#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка divPairFp() - замены 64-битных делений /wz в ядрах полов и
перспективы (TexturingFloors, TexturingPers; §21).

Оригинал на каждый сканлайн/сегмент:  q = (int)(((long)n << 12) / wz) -
четыре 64-битных деления (на ARM9 - программная эмуляция). Новый
divPairFp(nu, nv, den) считает пару (u,v) с общим знаменателем:
  A: |nu|,|nv| < 2^19        -> две ТОЧНЫЕ 32-битные деления (n<<12 влезает
                                в int32; бит-в-бит == оригинал, включая
                                INT_MIN/-1);
  B: |den| >= 64, |n| < 2^24 -> rq = 2^30/den (одна 32-битная) + два
                                умножения; погрешность < 65 fp-единиц
                                (1/63 тексела);
  иначе                      -> точные 64-битные деления (== оригинал).

Скрипт моделирует все три пути с int-семантикой Java (обёртывание int32/
int64, деление с усечением к нулю, арифметический сдвиг) и проверяет:
  1. пути A и fallback - бит-в-бит равны оригиналу на всём домене
     (граничные сетки + случайный fuzz);
  2. путь B - |отклонение| <= 65 fp-единиц, точное значение влезает в
     int32 (каст не искажает);
  3. симуляция пролёта: сегментный цикл перспективы (мутации uz_a+=duz4,
     wz_a+=dwz4, пересчёт du/dv каждый сегмент, три формы wrapper'а:
     *FPq>>fp, /sz21, /sz2) - отклонение траектории u/v ограничено и НЕ
     накапливается по сегментам (du/dv каждый сегмент перепривязываются к
     свежевычисленным значениям);
  4. статистика путей на реалистичных величинах.

Запуск:  python3 docs/verify/test_wz_div.py
Ожидание: "TOTAL FAILURES: 0"
"""
import random
import sys

FP = 12
RQ_BITS = 30
RQB = 1 << RQ_BITS
M32 = 1 << 32
M64 = 1 << 64

stats = {'A': 0, 'B': 0, 'F': 0, 'Bmax': 0}


def i32(v):
    v &= M32 - 1
    return v - M32 if v >= (1 << 31) else v


def i64(v):
    v &= M64 - 1
    return v - M64 if v >= (1 << 63) else v


def tdiv(a, b):
    """Деление с усечением к нулю (Java /)."""
    q = abs(a) // abs(b)
    return -q if (a < 0) != (b < 0) else q


def original_div(num, den):
    """(int)(((long)num << FP) / den) с Java-семантикой."""
    return i32(tdiv(i64(num) << FP, den))


def div_pair_fp(nu, nv, den):
    """Точная копия TexturingFloors.divPairFp(); возвращает (qu, qv, path)."""
    if -(1 << 19) <= nu < (1 << 19) and -(1 << 19) <= nv < (1 << 19):
        # A: int-сдвиг тоже моделируем (гарантированно без обёртывания)
        return i32(tdiv(i32(nu << FP), den)), i32(tdiv(i32(nv << FP), den)), 'A'
    if (den >= 64 or den <= -64) \
            and -(1 << 24) <= nu < (1 << 24) and -(1 << 24) <= nv < (1 << 24):
        rq = i32(tdiv(RQB, den))
        qu = i32(i64(nu) * rq >> (RQ_BITS - FP))
        qv = i32(i64(nv) * rq >> (RQ_BITS - FP))
        return qu, qv, 'B'
    return original_div(nu, den), original_div(nv, den), 'F'


def check_pair(nu, nv, den, failures):
    qu, qv, path = div_pair_fp(nu, nv, den)
    stats[path] += 1
    eu, ev = original_div(nu, den), original_div(nv, den)
    if path in ('A', 'F'):
        if qu != eu or qv != ev:
            failures[0] += 1
            if failures[0] <= 5:
                print('FAIL exact-path %s: (%d,%d)/%d -> (%d,%d), ждали (%d,%d)'
                      % (path, nu, nv, den, qu, qv, eu, ev))
    else:
        # B: точное значение обязано влезать в int32 (иначе каст исказит),
        # отклонение <= 65 fp-единиц
        tu = tdiv(i64(nu) << FP, den)
        tv = tdiv(i64(nv) << FP, den)
        if not (-(1 << 31) <= tu < (1 << 31) and -(1 << 31) <= tv < (1 << 31)):
            failures[0] += 1
            print('FAIL B-path exact overflow: (%d,%d)/%d' % (nu, nv, den))
        d = max(abs(qu - eu), abs(qv - ev))
        if d > stats['Bmax']:
            stats['Bmax'] = d
        if d > 65:
            failures[0] += 1
            if failures[0] <= 5:
                print('FAIL B-path error %d > 65: (%d,%d)/%d' % (d, nu, nv, den))


def grid_cases():
    """Граничные сетки: края полос A/B, особые den."""
    edge_nums = [0, 1, -1, 2, -2,
                 (1 << 19) - 1, -(1 << 19), (1 << 19), -(1 << 19) - 1,
                 (1 << 24) - 1, -(1 << 24), (1 << 24), -(1 << 24) - 1,
                 (1 << 31) - 1, -(1 << 31), (1 << 26), -(1 << 26)]
    edge_dens = [1, -1, 2, -2, 63, -63, 64, -64, 65, -65,
                 (1 << 12), -(1 << 12), (1 << 20), -(1 << 20),
                 (1 << 30), -(1 << 30), (1 << 31) - 1, -(1 << 31)]
    for nu in edge_nums:
        for den in edge_dens:
            yield (nu, i32(nu * 7 + 13), den)     # nv - смещённая копия nu
    for den in edge_dens:
        for nv in edge_nums:
            yield (i32(nv * 3 - 5), nv, den)


def fuzz_cases(rng, count):
    for _ in range(count):
        band = rng.randrange(4)
        if band == 0:      # типичные величины A-полосы
            nu = rng.randint(-(1 << 18), 1 << 18)
            nv = rng.randint(-(1 << 18), 1 << 18)
            den = rng.choice([1, -1]) * rng.randint(1, 1 << 22)
        elif band == 1:    # B-полоса
            nu = rng.randint(-(1 << 24), 1 << 24)
            nv = rng.randint(-(1 << 24), 1 << 24)
            den = rng.choice([1, -1]) * rng.randint(64, 1 << 26)
        elif band == 2:    # fallback
            nu = rng.randint(-(1 << 31), (1 << 31) - 1)
            nv = rng.randint(-(1 << 31), (1 << 31) - 1)
            den = rng.randint(-(1 << 31), (1 << 31) - 1)
            if den == 0:
                den = 1
        else:              # узкие числа вокруг границ полос
            base = rng.choice([1 << 19, 1 << 24, 64])
            nu = rng.choice([1, -1]) * (base + rng.randint(-2, 2))
            nv = rng.choice([1, -1]) * (base + rng.randint(-2, 2))
            den = rng.choice([1, -1]) * (rng.randint(1, 3) * base)
            if den == 0:
                den = 1
        yield (i32(nu), i32(nv), i32(den))


def simulate_span(rng, form, failures):
    """Сегментный цикл перспективы (как в TexturingPers): состояние
    uz_a/vz_a/wz_a мутируется точными сложениями (одинаково в обеих
    версиях), du/dv пересчитываются КАЖДЫЙ сегмент - проверяем, что
    траектории u/v отклоняются ограниченно и не накапливают расхождение."""
    seg = {'fpq': 32, 'sz21': 16, 'sz2': 24}[form]
    fpq = (1 << FP) // seg if form == 'fpq' else 0
    uz_a = rng.randint(1 << 20, 1 << 23)
    vz_a = rng.randint(1 << 20, 1 << 23)
    wz_a = rng.randint(1 << 10, 1 << 16)
    duz4 = rng.randint(300, 3000)
    dvz4 = rng.randint(300, 3000)
    dwz4 = rng.randint(1, 60)
    uz_b = uz_a + duz4 * seg * 60
    vz_b = vz_a + dvz4 * seg * 60
    wz_b = wz_a + dwz4 * seg * 60

    u_o = original_div(uz_a, wz_a)
    v_o = original_div(vz_a, wz_a)
    u_n, v_n, _p = div_pair_fp(uz_a, vz_a, wz_a)

    so, sn = (uz_a, vz_a, wz_a), (uz_a, vz_a, wz_a)
    max_dev = 0
    for _s in range(60):
        so = (so[0] + duz4 * seg, so[1] + dvz4 * seg, so[2] + dwz4 * seg)
        sn = so  # мутации идентичны (точные сложения)
        eo_u = original_div(so[0], so[2])
        eo_v = original_div(so[1], so[2])
        en_u, en_v, _p2 = div_pair_fp(sn[0], sn[1], sn[2])
        if form == 'fpq':
            du_o = i32(i32(eo_u - u_o) * fpq) >> FP
            dv_o = i32(i32(eo_v - v_o) * fpq) >> FP
            du_n = i32(i32(en_u - u_n) * fpq) >> FP
            dv_n = i32(i32(en_v - v_n) * fpq) >> FP
        else:
            du_o = tdiv(i32(eo_u - u_o), seg + 1 if form == 'sz21' else seg)
            dv_o = tdiv(i32(eo_v - v_o), seg + 1 if form == 'sz21' else seg)
            du_n = tdiv(i32(en_u - u_n), seg + 1 if form == 'sz21' else seg)
            dv_n = tdiv(i32(en_v - v_n), seg + 1 if form == 'sz21' else seg)
        for _px in range(seg):
            u_o = i32(u_o + du_o); v_o = i32(v_o + dv_o)
            u_n = i32(u_n + du_n); v_n = i32(v_n + dv_n)
            max_dev = max(max_dev, abs(u_n - u_o), abs(v_n - v_o))
    # хвостовой сегмент (/length) до uz_b,wz_b
    length = 37
    eo_u = original_div(uz_b, wz_b); eo_v = original_div(vz_b, wz_b)
    en_u, en_v, _p3 = div_pair_fp(uz_b, vz_b, wz_b)
    du_o = tdiv(i32(eo_u - u_o), length); dv_o = tdiv(i32(eo_v - v_o), length)
    du_n = tdiv(i32(en_u - u_n), length); dv_n = tdiv(i32(en_v - v_n), length)
    for _px in range(length):
        u_o = i32(u_o + du_o); v_o = i32(v_o + dv_o)
        u_n = i32(u_n + du_n); v_n = i32(v_n + dv_n)
        max_dev = max(max_dev, abs(u_n - u_o), abs(v_n - v_o))
    # 300 fp-единиц = 300/4096 тексела ~ 1/13 тексела (у FPq §18 было 171)
    if max_dev > 300:
        failures[0] += 1
        print('FAIL span/%s: max deviation %d > 300 fp' % (form, max_dev))
    return max_dev


def main():
    rng = random.Random(31)
    failures = [0]

    n = 0
    for nu, nv, den in grid_cases():
        if den == 0:
            continue
        check_pair(nu, nv, den, failures)
        n += 1
    for nu, nv, den in fuzz_cases(rng, 300000):
        if den == 0:
            continue
        check_pair(nu, nv, den, failures)
        n += 1
    print('пар проверено: %d  пути: A=%d B=%d fallback=%d  макс. ошибка B: %d fp'
          % (n, stats['A'], stats['B'], stats['F'], stats['Bmax']))

    devs = []
    for form in ('fpq', 'sz21', 'sz2'):
        for _ in range(40):
            devs.append(simulate_span(rng, form, failures))
    print('пролёты: 120 симуляций (fpq/sz21/sz2), макс. отклонение траектории: '
          '%d fp-единиц (<= 300 = 1/13 тексела)' % max(devs))

    if stats['A'] == 0 or stats['B'] == 0 or stats['F'] == 0:
        print('FAIL: какой-то путь не упражнялся')
        failures[0] += 1

    print('TOTAL FAILURES: %d' % failures[0])
    return 1 if failures[0] else 0


if __name__ == '__main__':
    sys.exit(main())
