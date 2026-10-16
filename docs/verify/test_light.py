# Сравнение оригинального освещения MathUtils.calcLight/microCalcLight
# (double + Math.sqrt) с целочисленными MathUtils.calcLightFast/
# microCalcLightFast, которые включаются профилем DeviceProfile.fastLight.
# Переключатели ROUND/LONGDIV ниже соответствуют вариантам, описанным в
# таблице docs/ARM9.md; shipped-конфигурация - ROUND=True, LONGDIV=True.
# Нужен только Python 3.

"""Сравнение оригинального (double) и быстрого (int) освещения."""
import math, random
M32 = 0xFFFFFFFF
def i32(x):
    x &= M32
    return x - (1 << 32) if x >= (1 << 31) else x
def ashr(a, n): return i32(a >> n)
def jdiv(a, b):
    q = abs(a) // abs(b)
    return i32(q if (a < 0) == (b < 0) else -q)

FP_SHIFT = 12
def div2(v): return ashr(i32(v + ((v & M32) >> 31)), 1)
def divFPTrunc(t): return ashr(t, FP_SHIFT) if t >= 0 else i32(-ashr(-t, FP_SHIFT))
def clamp255(v): return 0 if v < 0 else (255 if v > 255 else v)

SQRT_MAX = 46340
def isqrt(n):
    n = i32(n)
    if n <= 0: return 0
    if n < 0x10000:
        if n < 0x100:
            if n < 0x10: r = 2 if n < 0x4 else 4
            else: r = 8 if n < 0x40 else 16
        else:
            if n < 0x1000: r = 32 if n < 0x400 else 64
            else: r = 128 if n < 0x4000 else 256
    elif n < 0x1000000:
        if n < 0x40000: r = 512
        elif n < 0x100000: r = 1024
        elif n < 0x400000: r = 2048
        else: r = 4096
    else:
        if n < 0x4000000: r = 8192
        elif n < 0x10000000: r = 16384
        elif n < 0x40000000: r = 32768
        else: r = 65536
    for _ in range(3):
        r = ashr(i32(r + jdiv(n, r)), 1)
    while r * r > n: r -= 1
    while r < SQRT_MAX and (r + 1) * (r + 1) <= n: r += 1
    return r

# ---------- ОРИГИНАЛЫ ----------
def calcLight(nx, ny, nz, sx, sy, sz):
    while abs(sx) > 30000 or abs(sy) > 30000 or abs(sz) > 30000:
        sx = jdiv(sx, 2); sy = jdiv(sy, 2); sz = jdiv(sz, 2)
    while abs(nx) > 30000 or abs(ny) > 30000 or abs(nz) > 30000:
        nx = jdiv(nx, 2); ny = jdiv(ny, 2); nz = jdiv(nz, 2)
    n2 = i32(i32(nx*nx) + i32(ny*ny) + nz*nz)
    s2 = i32(i32(sx*sx) + i32(sy*sy) + sz*sz)
    if n2 < 0 or s2 < 0:
        return 0        # Math.sqrt(<0) = NaN -> (int)NaN = 0 -> clamp = 0
    nw = math.sqrt(float(n2))
    lw = math.sqrt(float(s2))
    if nw < 1: nw = 1
    if lw < 1: lw = 1
    dot = i32(i32(i32(nx*sx) + i32(ny*sy)) + nz*sz)
    l = dot / (nw * lw)
    v = l * 256
    iv = 0 if math.isnan(v) else int(v)   # (int) в Java: усечение, NaN -> 0
    return min(max(iv, 0), 255)

def microCalcLight(nx, ny, nz, sx, sy, sz):
    while sx > 30000 or sy > 30000 or sz > 30000 or sx < -30000 or sy < -30000 or sz < -30000:
        sx = ashr(sx, 1); sy = ashr(sy, 1); sz = ashr(sz, 1)
    s2 = i32(i32(sx*sx) + i32(sy*sy) + sz*sz)
    if s2 < 0:
        return 0        # NaN -> (int)NaN = 0
    lw = math.sqrt(float(s2))
    if lw < 1: lw = 1
    dot = i32(i32(i32(nx*sx) + i32(ny*sy)) + nz*sz)
    v = dot / (lw * 4096) * 255
    iv = 0 if math.isnan(v) else int(v)
    return 255 if iv > 255 else iv

def isqrtRound(n):
    """Ближайшее целое к sqrt(n): уточнение после isqrt."""
    n = i32(n)
    if n <= 0: return 0
    r = isqrt(n)
    if r < SQRT_MAX and n - r*r > r:   # n - r*r <= 2r  -> r+1 ближе
        r += 1
    return r

# ---------- БЫСТРЫЕ ----------
def calcLightFast(nx, ny, nz, sx, sy, sz):
    while sx > 30000 or sy > 30000 or sz > 30000 or sx < -30000 or sy < -30000 or sz < -30000:
        sx = div2(sx); sy = div2(sy); sz = div2(sz)
    while nx > 30000 or ny > 30000 or nz > 30000 or nx < -30000 or ny < -30000 or nz < -30000:
        nx = div2(nx); ny = div2(ny); nz = div2(nz)
    n2 = i32(i32(nx*nx) + i32(ny*ny) + nz*nz)
    s2 = i32(i32(sx*sx) + i32(sy*sy) + sz*sz)
    if n2 < 0 or s2 < 0: return 0
    rn = ROUND and isqrtRound(n2) or isqrt(n2)
    if rn < 1: rn = 1
    rs = ROUND and isqrtRound(s2) or isqrt(s2)
    if rs < 1: rs = 1
    dot = i32(i32(i32(nx*sx) + i32(ny*sy)) + nz*sz)
    if LONGDIV:
        v = int((dot * 256) // (rn * rs)) if (dot >= 0) == (rn*rs >= 0) else -int(abs(dot*256)//abs(rn*rs))
    else:
        v = jdiv(i32(jdiv(dot, rn) << 8), rs)
    return clamp255(v)

def microCalcLightFast(nx, ny, nz, sx, sy, sz):
    while sx > 30000 or sy > 30000 or sz > 30000 or sx < -30000 or sy < -30000 or sz < -30000:
        sx = ashr(sx, 1); sy = ashr(sy, 1); sz = ashr(sz, 1)
    s2 = i32(i32(sx*sx) + i32(sy*sy) + sz*sz)
    if s2 < 0: return 0
    rs = ROUND and isqrtRound(s2) or isqrt(s2)
    if rs < 1: rs = 1
    dot = i32(i32(i32(nx*sx) + i32(ny*sy)) + nz*sz)
    if LONGDIV:
        den = rs * 4096
        v = int((dot * 255) / den) if (dot >= 0) == (den >= 0) else -int(abs(dot*255)//abs(den))
        v = i32(v)
    else:
        t = i32(jdiv(dot, rs) * 255)
        v = divFPTrunc(t)
    return 255 if v > 255 else v

# ---------- ТЕСТЫ ----------
ROUND = True   # shipped: MathUtils.*Fast используют FastMath.isqrtRound
LONGDIV = True
random.seed(11)
def rnd_normal(scale=4096):
    x, y, z = random.gauss(0,1), random.gauss(0,1), random.gauss(0,1)
    L = math.sqrt(x*x+y*y+z*z)
    return [i32(int(c/L*scale)) for c in (x,y,z)]

worst_c = worst_m = 0
hist_c = {}
hist_m = {}
for trial in range(200000):
    mode = trial % 4
    if mode == 0:            # типичный случай: нормаль и направление ~4096
        n = rnd_normal(4096); s = rnd_normal(4096)
    elif mode == 1:          # направление света из level-файла (short)
        n = rnd_normal(4096); s = [random.randint(-32768, 32767) for _ in range(3)]
    elif mode == 2:          # вектор камера->вершина (большие координаты)
        n = rnd_normal(4096); s = [random.randint(-3000000, 3000000) for _ in range(3)]
    else:                    # мелкие/вырожденные вектора
        n = rnd_normal(4096); s = [random.randint(-300, 300) for _ in range(3)]
    a = calcLight(*n, *s); b = calcLightFast(*n, *s)
    d = abs(a-b); worst_c = max(worst_c, d); hist_c[d] = hist_c.get(d,0)+1
    a = microCalcLight(*n, *s); b = microCalcLightFast(*n, *s)
    d = abs(a-b); worst_m = max(worst_m, d); hist_m[d] = hist_m.get(d,0)+1

print("calcLight      worst dev: %d   hist: %s" % (worst_c, sorted(hist_c.items())[:8]))
print("microCalcLight worst dev: %d   hist: %s" % (worst_m, sorted(hist_m.items())[:8]))

# вырожденные случаи
edge = [(0,0,0,0,0,0), (0,0,0,1,2,3), (1,0,0,0,0,0), (4096,0,0,4096,0,0), (4096,0,0,-4096,0,0),
        (30000,30000,30000,30000,30000,30000), (32767,32767,32767,1,1,1), (0,0,4096,0,0,1)]
for e in edge:
    print("  edge", e, "calc:", calcLight(*e), calcLightFast(*e), " micro:", microCalcLight(*e), microCalcLightFast(*e))
