#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Проверка замены Math.sin/Math.cos на таблицу Matrix.sin/Matrix.cos в Player.walk().

Было:
    new Vector3D((int)(-Math.sin(modelRot * Math.PI / 180) * walkSpeed), 0,
                 (int)(-Math.cos(modelRot * Math.PI / 180) * walkSpeed));
Стало:
    int deg = (int) modelRot;                      // так же приводит Matrix.rotateY()
    walkDir.set(-Matrix.sin(deg) * walkSpeed / Matrix.FP, 0,
                -Matrix.cos(deg) * walkSpeed / Matrix.FP);

Таблица Matrix (статический инициализатор):
    sin[i] = (short)(Math.sin(Math.toRadians(i)) * FP),  FP = 1 << 14, i = 0..359
    Matrix.sin(deg): while(deg<0) deg+=360; return sin[deg%360];

Сравниваются все углы от -720 до 720 градусов с шагом 0.05 и скорости
walkSpeed от 1 до 512 (реальный диапазон: walkSpeed() возвращает единицы и
десятки; *7/3 в режиме полёта).
"""
import math
import sys

FP = 1 << 14
SIN = [int(math.sin(math.radians(d)) * FP) for d in range(360)]
COS = [int(math.cos(math.radians(d)) * FP) for d in range(360)]


def matrix_sin(deg):
    while deg < 0:
        deg += 360
    return SIN[deg % 360]


def matrix_cos(deg):
    while deg < 0:
        deg += 360
    return COS[deg % 360]


def trunc_div(a, b):
    """Java '/' для int: усечение к нулю."""
    q = abs(a) // abs(b)
    return -q if (a < 0) != (b < 0) else q


def main():
    # Метрики разделены, потому что целочисленное квантование самого оригинала
    # (приведение (int) вектора длиной walkSpeed) вносит +-1 единицу, что при
    # малых walkSpeed выглядит как большой "поворот". Поэтому:
    #  1) НАПРАВЛЕНИЕ сравнивается до квантования: (-sin a, -cos a) против
    #     (-Matrix.sin(deg), -Matrix.cos(deg)) - ошибка только от шага таблицы;
    #  2) КОМПОНЕНТЫ сравниваются как есть, с порогом max(1, 2% walkSpeed).
    # Реальный диапазон walkSpeed: Player.walkSpeed = 150, из ini TPPose
    # WALK_SPEED, в полёте *7/3.
    speeds = [1, 3, 7, 12, 20, 35, 45, 64, 100, 150, 180, 255, 350, 512]
    max_angle = 0.0
    max_angle_at = None
    max_dev = 0
    max_dev_at = None
    violations = []
    total = exact = 0
    hist = {}

    step = 0.05
    n = int(1440 / step)
    for k in range(-n // 2, n // 2 + 1):
        modelRot = k * step
        deg = int(modelRot)                     # (int)float в Java - усечение к нулю
        ms = matrix_sin(deg)
        mc = matrix_cos(deg)

        # 1) направление до квантования
        ex, ez = -math.sin(modelRot * math.pi / 180.0), -math.cos(modelRot * math.pi / 180.0)
        nx0, nz0 = -ms / float(FP), -mc / float(FP)
        d = abs(math.atan2(ex, ez) - math.atan2(nx0, nz0))
        if d > math.pi:
            d = 2 * math.pi - d
        da = math.degrees(d)
        if da > max_angle:
            max_angle, max_angle_at = da, modelRot

        # 2) компоненты после квантования
        for ws in speeds:
            ox = int(ex * ws)
            oz = int(ez * ws)
            nx = trunc_div(-ms * ws, FP)
            nz = trunc_div(-mc * ws, FP)
            total += 1
            dev = max(abs(nx - ox), abs(nz - oz))
            hist[dev] = hist.get(dev, 0) + 1
            if dev == 0:
                exact += 1
            if dev > max_dev:
                max_dev, max_dev_at = dev, (modelRot, ws, ox, oz, nx, nz)
            limit = max(1, int(0.02 * ws) + 1)   # 2% + единица на усечение
            if dev > limit and len(violations) < 5:
                violations.append((modelRot, ws, dev, limit))

    print('проверено комбинаций        :', total)
    print('совпало бит-в-бит           : %d (%.2f%%)' % (exact, 100.0 * exact / total))
    print('макс. ошибка НАПРАВЛЕНИЯ    : %.4f градуса (modelRot=%s)' % (max_angle, max_angle_at))
    print('макс. отклонение компоненты : %d единиц при (modelRot=%s, ws=%s, было=(%s,%s), стало=(%s,%s))'
          % ((max_dev,) + tuple(str(v) for v in max_dev_at)))
    print('распределение отклонений    :',
          ', '.join('%d:%d' % kv for kv in sorted(hist.items())[:8]))
    print('нарушений порога 2%%+1      :', len(violations), violations)
    print()
    print('Пороги: направление <= 1.05 градуса (шаг таблицы Matrix равен 1 градусу,')
    print('и Matrix.rotY((int)rotateY) приводит угол модели точно так же);')
    print('компоненты <= max(1, 2% walkSpeed + 1): 2% - это sin(1 градуса) =')
    print('1.75% от шага таблицы, +1 единица на усечение (int) у обоих вариантов.')
    ok = max_angle <= 1.05 and not violations
    print('РЕЗУЛЬТАТ:', 'OK' if ok else 'ПРОВАЛ')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
