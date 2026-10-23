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

    public static final boolean isPow2(int n) {
        return n > 0 && (n & (n - 1)) == 0;
    }

}
