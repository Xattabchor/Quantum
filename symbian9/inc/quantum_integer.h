#ifndef QUANTUM_INTEGER_H
#define QUANTUM_INTEGER_H

#include "quantum_types.h"

#if !defined(__SYMBIAN32__)
#include <stdexcept>
#endif

namespace code {
namespace Math {

/*
 * Java's int arithmetic wraps at 32 bits. Signed overflow is undefined in
 * C++, so do the arithmetic in the corresponding unsigned type and convert
 * the bit pattern back without relying on an out-of-range signed cast.
 */
inline QInt32 javaIntFromUnsigned(QUint32 bits)
{
    if (bits <= static_cast<QUint32>(0x7fffffffUL)) {
        return static_cast<QInt32>(bits);
    }
    return static_cast<QInt32>(-1 - static_cast<QInt32>(~bits));
}

inline QInt32 javaIntAdd(QInt32 left, QInt32 right)
{
    return javaIntFromUnsigned(static_cast<QUint32>(left) +
                               static_cast<QUint32>(right));
}

inline QInt32 javaIntSub(QInt32 left, QInt32 right)
{
    return javaIntFromUnsigned(static_cast<QUint32>(left) -
                               static_cast<QUint32>(right));
}

inline QInt32 javaIntMul(QInt32 left, QInt32 right)
{
    return javaIntFromUnsigned(static_cast<QUint32>(left) *
                               static_cast<QUint32>(right));
}

/* Java masks int shift counts to five bits and wraps the shifted bit pattern. */
inline QInt32 javaShiftLeft(QInt32 value, unsigned int bits)
{
    const unsigned int shift = bits & 31U;
    return javaIntFromUnsigned(static_cast<QUint32>(value) << shift);
}

inline QInt32 javaIntFromLong(QInt64 value)
{
    const QUint64 bits = static_cast<QUint64>(value);
    return javaIntFromUnsigned(static_cast<QUint32>(
        bits & static_cast<QUint64>(0xffffffffUL)));
}

inline QInt64 javaLongFromUnsigned(QUint64 bits)
{
    if (bits <= (static_cast<QUint64>(-1) >> 1)) {
        return static_cast<QInt64>(bits);
    }
    return static_cast<QInt64>(-1 - static_cast<QInt64>(~bits));
}

inline QInt64 javaLongAdd(QInt64 left, QInt64 right)
{
    return javaLongFromUnsigned(static_cast<QUint64>(left) +
                                static_cast<QUint64>(right));
}

inline QInt64 javaLongSub(QInt64 left, QInt64 right)
{
    return javaLongFromUnsigned(static_cast<QUint64>(left) -
                                static_cast<QUint64>(right));
}

inline QInt64 javaLongMulInt(QInt32 left, QInt32 right)
{
    /* Every signed 32-bit product fits in a signed 64-bit value. */
    return static_cast<QInt64>(left) * static_cast<QInt64>(right);
}

inline QInt32 javaIntDiv(QInt32 numerator, QInt32 denominator)
{
    if (denominator == 0) {
#if defined(__SYMBIAN32__)
        User::Leave(KErrDivideByZero);
        return 0; // Required only for compilers that do not know Leave never returns.
#else
        throw std::domain_error("Java-compatible integer division by zero");
#endif
    }

    /* Java specifies this overflow case; native C++ division does not. */
    if (numerator == static_cast<QInt32>(-2147483647 - 1) && denominator == -1) {
        return numerator;
    }
    return numerator / denominator;
}

inline QInt32 javaIntAbs(QInt32 value)
{
    /* Math.abs(Integer.MIN_VALUE) is still Integer.MIN_VALUE in Java. */
    if (value == static_cast<QInt32>(-2147483647 - 1)) {
        return value;
    }
    return value < 0 ? -value : value;
}

/* Java's >>> is a logical shift with the same five-bit count masking. */
inline QInt32 javaShiftRightUnsigned(QInt32 value, unsigned int bits)
{
    return javaIntFromUnsigned(static_cast<QUint32>(value) >> (bits & 31U));
}

/* Java's >> is an arithmetic (sign-extending) shift. */
inline QInt32 javaShiftRight(QInt32 value, unsigned int bits)
{
    if (bits == 0) {
        return value;
    }
    if (bits >= 32) {
        return value < 0 ? static_cast<QInt32>(-1) : 0;
    }
    if (value >= 0) {
        return static_cast<QInt32>(static_cast<QUint32>(value) >> bits);
    }

    const QUint64 magnitude = static_cast<QUint64>(
        -static_cast<QInt64>(value));
    const QUint64 divisor = static_cast<QUint64>(1) << bits;
    const QUint64 roundedMagnitude = (magnitude + divisor - 1) / divisor;
    return javaIntFromUnsigned(static_cast<QUint32>(0) -
                               static_cast<QUint32>(roundedMagnitude));
}

inline QInt64 javaShiftRightLong(QInt64 value, unsigned int bits)
{
    if (bits == 0) {
        return value;
    }
    if (bits >= 64) {
        return value < 0 ? static_cast<QInt64>(-1) : 0;
    }
    if (value >= 0) {
        return static_cast<QInt64>(static_cast<QUint64>(value) >> bits);
    }

    const QUint64 magnitude = static_cast<QUint64>(-(value + 1)) + 1;
    const QUint64 divisor = static_cast<QUint64>(1) << bits;
    const QUint64 roundedMagnitude = (magnitude + divisor - 1) / divisor;
    return javaLongFromUnsigned(static_cast<QUint64>(0) - roundedMagnitude);
}

/* Java masks long shift counts to six bits and wraps the shifted bit pattern. */
inline QInt64 javaShiftLeftLong(QInt64 value, unsigned int bits)
{
    const unsigned int shift = bits & 63U;
    return javaLongFromUnsigned(static_cast<QUint64>(value) << shift);
}

inline QInt64 javaLongDiv(QInt64 numerator, QInt64 denominator)
{
    if (denominator == 0) {
#if defined(__SYMBIAN32__)
        User::Leave(KErrDivideByZero);
        return 0;
#else
        throw std::domain_error("Java-compatible long division by zero");
#endif
    }

    const QUint64 signBit = static_cast<QUint64>(1) << 63;
    if (static_cast<QUint64>(numerator) == signBit && denominator == -1) {
        return numerator;
    }
    return numerator / denominator;
}

inline QInt32 javaDoubleToInt(double value)
{
    /* JLS narrowing conversion: NaN -> 0, infinities/out-of-range saturate. */
    if (value != value) {
        return 0;
    }
    if (value >= 2147483647.0) {
        return static_cast<QInt32>(2147483647L);
    }
    if (value <= -2147483648.0) {
        return static_cast<QInt32>(-2147483647 - 1);
    }
    return static_cast<QInt32>(value);
}

} // namespace Math
} // namespace code

#endif // QUANTUM_INTEGER_H
