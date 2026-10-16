# Зеркало семантики Java для code.Math.FastMath: проверяет, что быстрые
# целочисленные замены дают ТОТ ЖЕ результат, что исходные выражения Java
# (isqrt против (int)Math.sqrt, modFP/modPow2 против %, div2 против /2).
# Нужен только Python 3. Подробное описание - в docs/ARM9.md.

# Точная зеркальная копия FastMath.java для проверки эквивалентности.
import math, random
M32 = 0xFFFFFFFF
def i32(x):
    x &= M32
    return x - (1 << 32) if x >= (1 << 31) else x
def ashr(a, n): return i32(a >> n)
def ushr(a, n): return i32((a & M32) >> n)
def jdiv(a, b):
    q = abs(a) // abs(b)
    return i32(q if (a < 0) == (b < 0) else -q)
def jmod(a, b):
    r = abs(a) % abs(b)
    return i32(r if a >= 0 else -r)

FP_SHIFT = 12; FP = 1 << FP_SHIFT; FP_MASK = FP - 1
def modFP(x):
    return i32(x & FP_MASK) if x >= 0 else i32(x - i32(i32(x + FP_MASK) & i32(~FP_MASK)))
def modPow2(x, mask):
    nm = i32(~mask)
    return i32(x & mask) if x >= 0 else i32(x - i32(i32(x + mask) & nm))

SQRT_MAX = 46340
def isqrt(n):
    n = i32(n)
    if n <= 0: return 0
    n_u = n  # n > 0
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
    # while ((long)r*r > n) r--
    while r * r > n: r -= 1
    while r < SQRT_MAX and (r + 1) * (r + 1) <= n: r += 1
    return r

def div2(v): return ashr(i32(v + ushr(v, 31)), 1)

def div100(v):
    """Зеркало FastMath.div100: v/100 без idiv (умножение на 1374389535, сдвиг 37)."""
    v = i32(v)
    if v >= 0:
        return i32((v * 1374389535) >> 37)
    if v == -(2**31):
        return jdiv(v, 100)
    return i32(-(((-v) * 1374389535) >> 37))
def clamp255(v): return 0 if v < 0 else (255 if v > 255 else v)

def java_sqrt_int(q):
    """(int)Math.sqrt(q) в семантике Java (double)."""
    if q < 0: return 0           # NaN -> (int)NaN == 0
    return int(math.sqrt(float(q)))

fail = 0
# --- isqrt: полный перебор 0..2^22
for n in range(0, 1 << 22):
    if isqrt(n) != java_sqrt_int(n):
        print("isqrt FAIL", n, isqrt(n), java_sqrt_int(n)); fail += 1
        if fail > 5: break
# --- isqrt: границы, степени, случайные до 2^31-1
random.seed(7)
special = [2**31-1, 2**31-2, 2**30, 2**30-1, 2**28, 2**24, 2**16, 46340*46340, 46341*46341-1, 0, 1, 2, 3]
tests = special + [random.randrange(0, 2**31-1) for _ in range(400000)]
tests += [k*k for k in range(0, 46341)] + [k*k-1 for k in range(1, 46341)] + [k*k+1 for k in range(0, 46340)]
for n in tests:
    if n > 2**31-1: continue
    if isqrt(n) != java_sqrt_int(n):
        print("isqrt FAIL(big)", n, isqrt(n), java_sqrt_int(n)); fail += 1
        if fail > 10: break
# --- отрицательные (переполнение суммы квадратов)
for n in (-1, -2, -(2**31), -12345):
    if isqrt(n) != java_sqrt_int(n):
        print("isqrt FAIL(neg)", n, isqrt(n), java_sqrt_int(n)); fail += 1

# --- modFP / modPow2
for x in list(range(-70000, 70000)) + [2**31-1, -(2**31), -(2**31)+1, 4096, -4096, 0]:
    x = i32(x)
    if modFP(x) != jmod(x, FP):
        print("modFP FAIL", x, modFP(x), jmod(x, FP)); fail += 1; break
for mask in (15, 255, 4095, 65535, 262143):
    p = mask + 1
    for x in list(range(-70000, 70000)) + [2**31-1, -(2**31)]:
        x = i32(x)
        if modPow2(x, mask) != jmod(x, p):
            print("modPow2 FAIL", x, p, modPow2(x, mask), jmod(x, p)); fail += 1; break

# --- div2
for x in list(range(-100000, 100000)) + [2**31-1, -(2**31), -(2**31)+1]:
    x = i32(x)
    if div2(x) != jdiv(x, 2):
        print("div2 FAIL", x, div2(x), jdiv(x, 2)); fail += 1; break

# --- div100 (замена "/ 100" в анимации NPC/зомби/лифтов)
import random as _rnd
_r = _rnd.Random(100)
checked = 0
for x in range(0, 3000000):
    checked += 1
    if div100(x) != jdiv(x, 100):
        print("div100 FAIL", x, div100(x), jdiv(x, 100)); fail += 1; break
for x in range(-3000000, 0):
    checked += 1
    if div100(x) != jdiv(x, 100):
        print("div100 FAIL(neg)", x, div100(x), jdiv(x, 100)); fail += 1; break
for _ in range(300000):
    x = i32(_r.randrange(-(2**31), 2**31))
    checked += 1
    if div100(x) != jdiv(x, 100):
        print("div100 FAIL(rand)", x, div100(x), jdiv(x, 100)); fail += 1; break
# критические точки k*100-1, k*100, k*100+1 по всему диапазону int
k = 0
while k * 100 < 2**31:
    for d in (-1, 0, 1):
        x = k * 100 + d
        if 0 <= x < 2**31:
            checked += 1
            if div100(x) != jdiv(x, 100):
                print("div100 FAIL(crit+)", x, div100(x), jdiv(x, 100)); fail += 1
        x = -x
        if -(2**31) <= x < 0:
            checked += 1
            if div100(x) != jdiv(x, 100):
                print("div100 FAIL(crit-)", x, div100(x), jdiv(x, 100)); fail += 1
    k += 9973
for x in (2**31 - 1, -(2**31), -(2**31) + 1, 0, 99, 100, 101, -99, -100, -101):
    x = i32(x)
    checked += 1
    if div100(x) != jdiv(x, 100):
        print("div100 FAIL(edge)", x, div100(x), jdiv(x, 100)); fail += 1
print("div100 проверено значений:", checked)

print("TOTAL FAILURES:", fail)
