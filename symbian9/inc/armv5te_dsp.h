#ifndef QUANTUM_ARMV5TE_DSP_H
#define QUANTUM_ARMV5TE_DSP_H

#include "quantum_integer.h"

namespace code {
namespace Math {
namespace ArmV5teDsp {

/*
 * SMULWB takes the signed low halfword of its third operand and returns the
 * signed 32-bit product shifted right by 16. This portable model is used by
 * host tests; the E65 build calls the matching ARM-state SMULWB/SMLAWB
 * instructions in the isolated OMAP1710 assembly source.
 */
inline QInt32 smulwbReference(QInt32 left, QInt32 right)
{
    const QUint32 lowHalfword = static_cast<QUint32>(right) & 0xffffU;
    QInt32 signedHalfword;
    if (lowHalfword <= 0x7fffU) {
        signedHalfword = static_cast<QInt32>(lowHalfword);
    } else {
        signedHalfword = static_cast<QInt32>(lowHalfword) - 65536;
    }

    const QInt64 product = static_cast<QInt64>(left) *
                           static_cast<QInt64>(signedHalfword);
    const QInt64 divisor = 65536;
    QInt64 shifted;
    if (product >= 0) {
        shifted = product / divisor;
    } else {
        const QInt64 magnitude = -product;
        shifted = -((magnitude + divisor - 1) / divisor);
    }
    return javaIntFromLong(shifted);
}

/*
 * SMLAWB returns accumulator + ((left * signed_low_halfword(right)) >> 16).
 * The final addition wraps as an ordinary 32-bit ARM integer operation.
 */
inline QInt32 smlawbReference(QInt32 accumulator,
                              QInt32 left,
                              QInt32 right)
{
    return javaIntAdd(accumulator, smulwbReference(left, right));
}

/*
 * A remainder modulo 4096 is in [-4095, 4095]. The signed low halfword used
 * by SMULWB directly represents fractions in [-2048, 2047] after scaling by
 * 16. Outside that central range, the low halfword of fraction*16 naturally
 * wraps by one Q12 period: SMLAWB adds +slope for the upper range or -slope
 * for the lower range. These identities preserve floor(slope*fraction/4096),
 * including negative products and Java's wrapping int result.
 */
inline bool canUseSmulwbDirectForQ12Fraction(QInt32 fraction)
{
    return fraction >= -2048 && fraction <= 2047;
}

inline bool canUseArmV5teDspForQ12Fraction(QInt32 fraction)
{
    return fraction >= -4095 && fraction <= 4095;
}

inline bool q12ProductShiftArmV5teDspReference(QInt32 slope,
                                               QInt32 fraction,
                                               QInt32& result)
{
    if (!canUseArmV5teDspForQ12Fraction(fraction)) {
        return false;
    }

    const QInt32 scaledFraction = fraction * 16;
    if (fraction > 2047) {
        result = smlawbReference(slope, slope, scaledFraction);
    } else if (fraction < -2048) {
        result = smlawbReference(
            javaIntSub(0, slope), slope, scaledFraction);
    } else {
        result = smulwbReference(slope, scaledFraction);
    }
    return true;
}

} // namespace ArmV5teDsp
} // namespace Math
} // namespace code

#endif // QUANTUM_ARMV5TE_DSP_H
