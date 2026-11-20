#include <stdint.h>
#include <stdio.h>

extern uint64_t fastfp_ddiv(uint64_t numerator, uint64_t denominator);
extern uint32_t fastfp_ddiv_fallback_count(void);
extern void fastfp_ddiv_reset_fallback_count(void);

static uint32_t next_random(uint32_t* state)
{
    *state = *state * 1664525u + 1013904223u;
    return *state;
}

static uint64_t normal_bits(uint32_t* state, int negative)
{
    const uint32_t exponent = 256u + next_random(state) % 1500u;
    const uint64_t fraction =
        (((uint64_t)next_random(state) << 20) |
         (next_random(state) & 0xfffffu)) & 0xfffffffffffffULL;
    const uint64_t sign = (uint64_t)(negative != 0) << 63;
    return sign | ((uint64_t)exponent << 52) | fraction;
}

static uint64_t system_divide(uint64_t numerator, uint64_t denominator)
{
    union {
        uint64_t bits;
        double value;
    } left, right, result;
    left.bits = numerator;
    right.bits = denominator;
    result.value = left.value / right.value;
    return result.bits;
}

int main(void)
{
    uint32_t state = 0x31415926u;
    uint32_t mismatches = 0;
    uint32_t cases = 0;
    fastfp_ddiv_reset_fallback_count();

    for (uint32_t i = 0; i < 1000000u; ++i) {
        const uint64_t numerator = normal_bits(&state, (i & 1u) != 0);
        const uint64_t denominator = normal_bits(&state, (i & 2u) != 0);
        if (fastfp_ddiv(numerator, denominator) !=
            system_divide(numerator, denominator)) {
            ++mismatches;
        }
        ++cases;
    }

    {
        const uint64_t one = 0x3ff0000000000000ULL;
        const uint64_t edge_cases[][2] = {
            {0, one}, {one, 0}, {0, 0},
            {0x7ff0000000000000ULL, one}, {one, 1}, {1, one},
            {0x7ff8000000000001ULL, one}
        };
        for (uint32_t i = 0; i < sizeof(edge_cases) / sizeof(edge_cases[0]); ++i) {
            if (fastfp_ddiv(edge_cases[i][0], edge_cases[i][1]) !=
                system_divide(edge_cases[i][0], edge_cases[i][1])) {
                ++mismatches;
            }
            ++cases;
        }
    }

    printf("cases=%u mismatches=%u fallbacks=%u\n",
           cases, mismatches, fastfp_ddiv_fallback_count());
    return mismatches ? 1 : 0;
}
