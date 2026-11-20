/*
 * Isolated double-division kernel from CC3DBench/tools/float/fastfp.c.
 * The user confirms the author supplied this code for the experiment.
 *
 * Deliberately exported as fastfp_ddiv, never as __aeabi_ddiv: the system EABI
 * routine remains the platform baseline and is also used by this kernel's
 * exceptional-input fallback.
 */
typedef unsigned int u32;
typedef unsigned long long u64;

#define UMULH(a, b) ((u32)(((u64)(u32)(a) * (u32)(b)) >> 32))

static volatile u32 gFallbackCount;

static u64 gcc_ddiv(u64 a, u64 b)
{
    union {
        u64 bits;
        double value;
    } numerator, denominator, quotient;
    volatile double result;

    numerator.bits = a;
    denominator.bits = b;
    result = numerator.value / denominator.value;
    quotient.value = result;
    ++gFallbackCount;
    return quotient.bits;
}

/* Index by the divisor's top bits after its leading one: 1 / midpoint,
 * represented as 8 fraction bits above 1. */
static const unsigned char kReciprocal[64] = {
    126, 122, 118, 115, 111, 108, 104, 101, 98, 95, 92, 89, 86, 83, 81, 78,
    76, 73, 71, 68, 66, 64, 61, 59, 57, 55, 53, 51, 49, 47, 45, 44,
    42, 40, 38, 37, 35, 33, 32, 30, 29, 27, 26, 24, 23, 22, 20, 19,
    18, 16, 15, 14, 13, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1
};

/* b has its top bit set; returns 2^63 / b to about 28 bits. */
static inline u32 reciprocal(u32 b)
{
    u32 x = (u32)(128 + kReciprocal[(b >> 25) & 63]) << 24;
    x = UMULH(x, 0u - UMULH(b, x)) << 1;
    return UMULH(x, 0u - UMULH(b, x)) << 1;
}

#define DOUBLE_MANTISSA 0xFFFFFFFFFFFFFULL
#define DOUBLE_ONE      0x10000000000000ULL

/*
 * Two quotient digits of 27 bits from a single-word reciprocal, each corrected
 * by its remainder.  This is the author's kernel with only the EABI symbol
 * renamed and the system fallback kept local to the probe.
 */
u64 fastfp_ddiv(u64 a, u64 b)
{
    u32 ea = (u32)(a >> 52) & 0x7FF;
    u32 eb = (u32)(b >> 52) & 0x7FF;
    u64 ma, mb, rem, q, m;
    u32 x, digit;
    int e;

    if (ea - 1 >= 2046 || eb - 1 >= 2046) return gcc_ddiv(a, b);
    ma = (a & DOUBLE_MANTISSA) | DOUBLE_ONE;
    mb = (b & DOUBLE_MANTISSA) | DOUBLE_ONE;
    e = (int)ea - (int)eb + 1023;
    if (ma < mb) {
        ma <<= 1;
        e--;
    }
    if (e < 1 || e >= 2046) return gcc_ddiv(a, b);

    x = reciprocal((u32)(mb >> 21));
    digit = UMULH((u32)(ma >> 22), x) >> 4;
    rem = (ma << 26) - digit * mb;
    while ((long long)rem < 0) {
        digit--;
        rem += mb;
    }
    while (rem >= mb) {
        digit++;
        rem -= mb;
    }

    q = (u64)digit << 27;
    digit = UMULH((u32)(rem >> 21), x) >> 4;
    rem = (rem << 27) - digit * mb;
    while ((long long)rem < 0) {
        digit--;
        rem += mb;
    }
    while (rem >= mb) {
        digit++;
        rem -= mb;
    }

    q += digit;
    m = q >> 1;
    if (((u32)q & 1) && (rem != 0 || ((u32)m & 1))) m++;
    return ((a ^ b) & 0x8000000000000000ULL) +
           ((u64)(u32)(e - 1) << 52) + m;
}

u32 fastfp_ddiv_fallback_count(void)
{
    return gFallbackCount;
}

void fastfp_ddiv_reset_fallback_count(void)
{
    gFallbackCount = 0;
}
