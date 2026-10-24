package code.Math;

/**
 * Целочисленные замены для "тяжёлой" арифметики (double, long, остаток от
 * деления), которая встречается в горячем коде рендера.
 *
 * Зачем это нужно именно для ARM9:
 *  - ARM946E-S / ARM926EJ-S (Nintendo DS, Samsung SGH/GT, Sony Ericsson
 *    DB2000/DB2010, Nokia S40, Motorola, LG, Siemens и т.п.) не имеют ни
 *    аппаратного делителя, ни FPU. Каждое idiv/irem/ldiv и каждая операция
 *    с double/float уходит в программную библиотечную подпрограмму.
 *  - J2ME-машина на таких аппаратах чаще всего интерпретирует байткод,
 *    поэтому цена операции ~ числу байткодов, а не числу тактов.
 *
 * Все методы ниже дают ТОТ ЖЕ результат, что и исходные выражения Java
 * (проверено перебором, см. docs/ARM9.md), если в комментарии сказано
 * "точный". Методы с пометкой "приближённый" используются только там, где
 * отклонение не видно (см. code.utils.DeviceProfile).
 *
 * Класс не использует ничего вне CLDC 1.x: ни java.util.Arrays, ни
 * Integer.numberOfLeadingZeros, ни генериков.
 *
 * @author Quantum engine (ARM9 optimization pack)
 */
public final class FastMath {

    /** Количество дробных бит у текстурных координат (как в TexturingAffine). */
    public static final int FP_SHIFT = 12;
    /** 4096 */
    public static final int FP = 1 << FP_SHIFT;
    /** 4095 */
    public static final int FP_MASK = FP - 1;

    /** Наибольшее r, для которого r*r ещё помещается в int. */
    private static final int SQRT_MAX = 46340;

    private FastMath() {
    }

    /**
     * Точный аналог {@code x % 4096} (знак результата как у делимого, по
     * правилам Java), но без irem: 2-4 байткода вместо вызова деления.
     *
     * В интерпретируемой J2ME-машине {@code %} для long/int - это вызов
     * подпрограммы деления; в растеризаторе он выполняется на каждой
     * скан-линии, поэтому замена существенна.
     */
    public static final int modFP(int x) {
        return x >= 0 ? (x & FP_MASK) : (x - ((x + FP_MASK) & ~FP_MASK));
    }

    /**
     * Точный аналог {@code x % (mask + 1)}, где {@code mask + 1} - степень
     * двойки. Знак результата соответствует правилам Java.
     */
    public static final int modPow2(int x, int mask) {
        return x >= 0 ? (x & mask) : (x - ((x + mask) & ~mask));
    }

    /**
     * Точный аналог {@code (int)Math.sqrt(n)} для int-аргумента:
     * целочисленный корень (округление вниз), 0 для n <= 0.
     *
     * Совпадает с оригиналом во всём диапазоне int, включая случай
     * переполнения суммы квадратов: оригинал при отрицательном аргументе
     * даёт {@code (int)NaN == 0}, этот метод тоже возвращает 0.
     *
     * Реализация: оценка сверху по старшему биту + 3 шага Ньютона +
     * доводка (не более нескольких итераций). Ни одной операции с double.
     */
    public static final int isqrt(int n) {
        if(n <= 0) return 0;

        // r = 2^ceil(bitLength(n)/2), всегда r >= sqrt(n)
        int r;
        if(n < 0x10000) { // n < 2^16
            if(n < 0x100) { // n < 2^8
                if(n < 0x10) r = n < 0x4 ? 2 : 4;
                else r = n < 0x40 ? 8 : 16;
            } else {
                if(n < 0x1000) r = n < 0x400 ? 32 : 64;
                else r = n < 0x4000 ? 128 : 256;
            }
        } else if(n < 0x1000000) { // n < 2^24
            if(n < 0x40000) r = 512;
            else if(n < 0x100000) r = 1024;
            else if(n < 0x400000) r = 2048;
            else r = 4096;
        } else { // n >= 2^24
            if(n < 0x4000000) r = 8192;
            else if(n < 0x10000000) r = 16384;
            else if(n < 0x40000000) r = 32768;
            else r = 65536;
        }

        // Ньютон: r = (r + n/r) / 2. После первого же шага r >= sqrt(n).
        r = (r + n / r) >> 1;
        r = (r + n / r) >> 1;
        r = (r + n / r) >> 1;

        // Точная доводка. Сравнение через long, чтобы r*r не переполнил int.
        while((long) r * r > n) r--;
        while(r < SQRT_MAX && (long) (r + 1) * (r + 1) <= n) r++;

        return r;
    }

    /**
     * Ближайшее целое к sqrt(n): isqrt() с одним уточняющим шагом.
     * Даёт вдвое меньшую относительную погрешность, чем округление вниз,
     * что важно в освещении (см. MathUtils.calcLightFast).
     */
    public static final int isqrtRound(int n) {
        if(n <= 0) return 0;
        int r = isqrt(n);
        // n - r*r <= 2r; если остаток больше половины делителя - ближе r+1
        if(r < SQRT_MAX && n - r * r > r) r++;
        return r;
    }

    /** {@code Math.min(Math.max(v, 0), 255)} без вызовов Math. */
    public static final int clamp255(int v) {
        if(v < 0) return 0;
        return v > 255 ? 255 : v;
    }

    /**
     * Точный аналог {@code v / 2} для int (усечение к нулю, как в Java),
     * но без idiv. Нужен в halving-циклах освещения, где оригинал писал
     * {@code sx /= 2} и {@code sx >>= 1} в двух разных методах.
     */
    public static final int div2(int v) {
        return (v + (v >>> 31)) >> 1;
    }

    /** true, если n > 0 и n - степень двойки. */
    /**
     * Показатель степени для положительной степени двойки, иначе -1.
     * Нужен, чтобы заменить деление на chunkSize сдвигом: делитель там
     * задаётся уровнем и обычно равен 2048/4096/8192.
     */
    public static final int shiftFor(int n) {
        if(n <= 0 || (n & (n - 1)) != 0) return -1;
        int s = 0;
        while((1 << s) != n) s++;
        return s;
    }

    /**
     * Точный аналог {@code v / 100} для int (усечение к нулю, как в Java), но
     * без idiv: умножение на 1374389535 и сдвиг на 37. Совпадение проверено
     * перебором в docs/verify/test_fastmath_java.py (сплошной перебор 0..3 млн,
     * 400 тыс. случайных значений и все критические точки k*100-1, k*100,
     * k*100+1 во всём диапазоне int): расхождений нет.
     *
     * Нужен в анимации: {@code frame100 * animSpeed / 100} и
     * {@code frame100 / 100} выполнялись на каждый NPC, зомби и лифт каждый кадр.
     */
    public static final int div100(int v) {
        if(v >= 0) return (int) (((long) v * 1374389535L) >> 37);
        if(v == -2147483648) return v / 100;   // -v не помещается в int
        return -(int) (((long) -v * 1374389535L) >> 37);
    }

    /**
     * Точный аналог {@code v / 50} для int (усечение к нулю, как в Java), но
     * без idiv: умножение на 2748779070 (ceil(2^37/50)) и сдвиг на 37.
     * Точность для всего диапазона int проверена перебором в
     * docs/verify/test_setup_math.py. Нужен погоде: частицы дождя и снега
     * делятся на 50 каждый кадр на каждую частицу.
     */
    public static final int div50(int v) {
        if(v >= 0) return (int) (((long) v * 2748779070L) >> 37);
        if(v == -2147483648) return v / 50;   // -v не помещается в int
        return -(int) (((long) -v * 2748779070L) >> 37);
    }

    /**
     * Точный аналог {@code x / 255} (усечение к нулю) без idiv:
     * {@code (|x|+1+(|x|>>8))>>8} верно при 0 <= |x| < 65535 (первый сбой на
     * 65535 = 255*257), вне диапазона - обычный idiv. Та же формула, что в
     * приватном TexturingPers.div255; нужна режиму 9 (аффинный
     * cameraVectorLight-путь paintAffine_9: ar*calcLightRender/255 на
     * каждую вершину треугольника). Перебор: docs/verify/test_modes913.py.
     */
    public static final int div255(int x) {
        int a = x < 0 ? -x : x;          // x == MIN_VALUE -> a < 0 -> idiv
        if(a < 0 || a >= 65535) return x / 255;
        int q = (a + 1 + (a >> 8)) >> 8;
        return x < 0 ? -q : q;
    }

    /**
     * Точный аналог {@code v / 3} (усечение к нулю) без idiv:
     * для |v| < 131072 верно {@code (|v|*43691)>>>17} (43691 = ceil(2^17/3),
     * ошибка |v|/393216 < 1/3 не накапливается), вне диапазона - idiv.
     * Нужен режимам 9/13: приведение per-channel освещения к серому
     * ((af+ag+ab)/3 на треугольник при drawmode 13 и fogQ==1).
     * Перебор: docs/verify/test_modes913.py.
     */
    public static final int div3(int v) {
        if(v >= 0) {
            if(v < 131072) return (v * 43691) >>> 17;
            return v / 3;
        }
        if(v == -2147483648) return v / 3;   // -v не помещается в int
        if(v > -131072) return -((-v) * 43691 >>> 17);
        return v / 3;
    }

    /**
     * Точный аналог {@code v / 1000} для int (усечение к нулю), но без idiv:
     * умножение на 274877907 (ceil(2^38/1000)) и сдвиг на 38. Остаток магии
     * e = 528 у ceil(2^37/1000) давал перелёт на k*1000-1 при v > 2.6e8;
     * с s=38 ошибка не превышает 4.4e-4 - меньше шага дроби 1/1000, поэтому
     * формула точна на всём диапазоне int (перебор в test_setup_math.py).
     * Нужен анимации спрайтов: при animation_speed == 1.0f и period < 2^24
     * значение (int)((float)t * speed / 1000) совпадает с t/1000 бит-в-бит.
     */
    public static final int div1000(int v) {
        if(v >= 0) return (int) (((long) v * 274877907L) >> 38);
        if(v == -2147483648) return v / 1000;   // -v не помещается в int
        return -(int) (((long) -v * 274877907L) >> 38);
    }

    public static final boolean isPow2(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }

}
