#ifndef QUANTUM_OMAP1710_PDIV_H
#define QUANTUM_OMAP1710_PDIV_H

#include "quantum_integer.h"

namespace code {
namespace Math {
namespace ArmV5teDsp {

/*
 * Portable model of quantum_omap1710_udiv_u32. It performs normalized
 * shift/subtract division and is used by host tests and non-Symbian builds of
 * the opt-in scalar-PDiv profile.
 */
inline bool omap1710UnsignedDivideReference(QUint32 dividend,
                                           QUint32 divisor,
                                           QUint32& quotient)
{
    quotient = 0;
    if (divisor == 0U) return false;
    if (dividend < divisor) return true;

    unsigned int dividendBits = 0;
    unsigned int divisorBits = 0;
    QUint32 scan = dividend;
    do {
        ++dividendBits;
        scan >>= 1U;
    } while (scan != 0U);
    scan = divisor;
    do {
        ++divisorBits;
        scan >>= 1U;
    } while (scan != 0U);

    unsigned int shift = dividendBits - divisorBits;
    QUint32 shiftedDivisor = static_cast<QUint32>(
        static_cast<QUint64>(divisor) << shift);
    QUint32 quotientBit = static_cast<QUint32>(1U) << shift;

    for (;;) {
        if (dividend >= shiftedDivisor) {
            dividend -= shiftedDivisor;
            quotient |= quotientBit;
        }
        if (quotientBit == 1U) break;
        quotientBit >>= 1U;
        shiftedDivisor >>= 1U;
    }
    return true;
}

} // namespace ArmV5teDsp
} // namespace Math
} // namespace code

#endif // QUANTUM_OMAP1710_PDIV_H
