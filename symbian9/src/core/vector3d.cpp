#include "vector3d.h"

#include "matrix.h"
#include "math_utils.h"
#include "qfps.h"
#include "quantum_integer.h"
#include "quantum_math.h"

namespace code {
namespace Math {

Vector3D::Vector3D() : x(0), y(0), z(0)
{
}

Vector3D::Vector3D(QInt32 xValue, QInt32 yValue, QInt32 zValue)
    : x(xValue), y(yValue), z(zValue)
{
}

Vector3D::Vector3D(const QInt32* values, QInt32 count)
    : x(0), y(0), z(0)
{
    if (values != 0 && count >= 3) {
        x = values[0];
        y = values[1];
        z = values[2];
    }
}

void Vector3D::set(QInt32 xValue, QInt32 yValue, QInt32 zValue)
{
    x = xValue;
    y = yValue;
    z = zValue;
}

void Vector3D::set(QInt32 value)
{
    x = value;
    y = value;
    z = value;
}

void Vector3D::set(const Vector3D& value)
{
    x = value.x;
    y = value.y;
    z = value.z;
}

void Vector3D::add(QInt32 xValue, QInt32 yValue, QInt32 zValue)
{
    x = javaIntAdd(x, xValue);
    y = javaIntAdd(y, yValue);
    z = javaIntAdd(z, zValue);
}

void Vector3D::div(QInt32 xValue, QInt32 yValue, QInt32 zValue)
{
    x = javaIntDiv(x, xValue);
    y = javaIntDiv(y, yValue);
    z = javaIntDiv(z, zValue);
}

void Vector3D::mul(QInt32 xValue, QInt32 yValue, QInt32 zValue)
{
    x = javaIntMul(x, xValue);
    y = javaIntMul(y, yValue);
    z = javaIntMul(z, zValue);
}

QInt32 Vector3D::length() const
{
    const QInt32 square = lengthSquared();
    if (square < 0) {
        /* Java sqrt(negative) is NaN and its int conversion is zero. */
        return 0;
    }
    return javaDoubleToInt(quantumSqrt(static_cast<double>(square)));
}

QInt32 Vector3D::lengthSquared() const
{
    const QInt32 xy = javaIntAdd(javaIntMul(x, x), javaIntMul(y, y));
    return javaIntAdd(xy, javaIntMul(z, z));
}

void Vector3D::setLength(QInt32 targetLength)
{
    if (lengthSquared() != javaIntMul(targetLength, targetLength)) {
        while (javaIntAdd(javaIntAdd(javaIntAbs(x), javaIntAbs(y)),
                          javaIntAbs(z)) > 30000) {
            x /= 2;
            y /= 2;
            z /= 2;
        }

        const QInt32 square = javaIntAdd(
            javaIntAdd(javaIntMul(x, x), javaIntMul(y, y)),
            javaIntMul(z, z));
        if (square < 0) {
            x = y = z = 0;
            return;
        }

        const double root = quantumSqrt(static_cast<double>(square));
        x = javaDoubleToInt((static_cast<double>(x) / root) * targetLength);
        y = javaDoubleToInt((static_cast<double>(y) / root) * targetLength);
        z = javaDoubleToInt((static_cast<double>(z) / root) * targetLength);
    }
}

void Vector3D::setLength2(QInt32 targetLength)
{
    if (targetLength == 0) {
        x = y = z = 0;
        return;
    }

    const QInt32 absX = javaIntAbs(x);
    const QInt32 absY = javaIntAbs(y);
    const QInt32 absZ = javaIntAbs(z);
    QInt32 oldLength = absX > absY ? absX : absY;
    oldLength = oldLength > absZ ? oldLength : absZ;
    if (oldLength == 0) {
        return;
    }

    x = javaIntDiv(javaIntMul(x, targetLength), oldLength);
    y = javaIntDiv(javaIntMul(y, targetLength), oldLength);
    z = javaIntDiv(javaIntMul(z, targetLength), oldLength);
}

void Vector3D::setLengthRound(QInt32 targetLength)
{
    if (lengthSquared() != javaIntMul(targetLength, targetLength)) {
        while (javaIntAdd(javaIntAdd(javaIntAbs(x), javaIntAbs(y)),
                          javaIntAbs(z)) > 30000) {
            x /= 2;
            y /= 2;
            z /= 2;
        }

        const QInt32 square = javaIntAdd(
            javaIntAdd(javaIntMul(x, x), javaIntMul(y, y)),
            javaIntMul(z, z));
        if (square < 0) {
            /* java Math.sqrt yields NaN, and this legacy ceil returns -1. */
            x = y = z = -1;
            return;
        }

        const double root = quantumSqrt(static_cast<double>(square));
        x = MathUtils::ceil((static_cast<double>(x) / root) * targetLength);
        y = MathUtils::ceil((static_cast<double>(y) / root) * targetLength);
        z = MathUtils::ceil((static_cast<double>(z) / root) * targetLength);
    }
}

void Vector3D::interpolation(const Vector3D& value, QInt32 speed)
{
    const QInt32 frameTime = code::utils::QFPS::frameTime == 0
        ? 1 : code::utils::QFPS::frameTime;
    const QInt32 dx = javaIntDiv(javaIntMul(javaIntSub(value.x, x), 50), speed);
    const QInt32 dy = javaIntDiv(javaIntMul(javaIntSub(value.y, y), 50), speed);
    const QInt32 dz = javaIntDiv(javaIntMul(javaIntSub(value.z, z), 50), speed);
    x = javaIntAdd(x, javaIntDiv(dx, frameTime));
    y = javaIntAdd(y, javaIntDiv(dy, frameTime));
    z = javaIntAdd(z, javaIntDiv(dz, frameTime));
}

QInt32 Vector3D::dot(const Vector3D& value) const
{
    const QInt32 xy = javaIntAdd(javaIntMul(x, value.x),
                                 javaIntMul(y, value.y));
    return javaIntAdd(xy, javaIntMul(z, value.z));
}

QInt64 Vector3D::dotLong(const Vector3D& value) const
{
    /* Preserve Java's exact expression: only the first product is widened. */
    QInt64 result = javaLongMulInt(x, value.x);
    result = javaLongAdd(result, javaIntMul(y, value.y));
    return javaLongAdd(result, javaIntMul(z, value.z));
}

void Vector3D::cross(const Vector3D& left, const Vector3D& right)
{
    const QInt32 xTerm = javaIntSub(
        javaIntDiv(javaIntMul(left.y, right.z), 32768),
        javaIntDiv(javaIntMul(right.y, left.z), 32768));
    const QInt32 yTerm = javaIntSub(
        javaIntDiv(javaIntMul(left.z, right.x), 32768),
        javaIntDiv(javaIntMul(right.z, left.x), 32768));
    const QInt32 zTerm = javaIntSub(
        javaIntDiv(javaIntMul(left.x, right.y), 32768),
        javaIntDiv(javaIntMul(right.x, left.y), 32768));

    /* The Java implementation needlessly narrows through float; retain it. */
    x = javaDoubleToInt(static_cast<double>(static_cast<float>(xTerm)));
    y = javaDoubleToInt(static_cast<double>(static_cast<float>(yTerm)));
    z = javaDoubleToInt(static_cast<double>(static_cast<float>(zTerm)));
}

void Vector3D::cross(const Vector3D& left, const Vector3D& right,
                     QInt32 fractionBits)
{
    const unsigned int shift = static_cast<unsigned int>(fractionBits) & 31U;
    x = javaShiftRight(javaIntSub(javaIntMul(left.y, right.z),
                                  javaIntMul(right.y, left.z)), shift);
    y = javaShiftRight(javaIntSub(javaIntMul(left.z, right.x),
                                  javaIntMul(right.z, left.x)), shift);
    z = javaShiftRight(javaIntSub(javaIntMul(left.x, right.y),
                                  javaIntMul(right.x, left.y)), shift);
}

void Vector3D::mul(Matrix& matrix)
{
    const QInt32 m00 = javaShiftRight(matrix.m00, 2);
    const QInt32 m01 = javaShiftRight(matrix.m01, 2);
    const QInt32 m02 = javaShiftRight(matrix.m02, 2);
    const QInt32 m03 = matrix.m03;

    const QInt32 m10 = javaShiftRight(matrix.m10, 2);
    const QInt32 m11 = javaShiftRight(matrix.m11, 2);
    const QInt32 m12 = javaShiftRight(matrix.m12, 2);
    const QInt32 m13 = matrix.m13;

    const QInt32 m20 = javaShiftRight(matrix.m20, 2);
    const QInt32 m21 = javaShiftRight(matrix.m21, 2);
    const QInt32 m22 = javaShiftRight(matrix.m22, 2);
    const QInt32 m23 = matrix.m23;
    const unsigned int shift = Matrix::fp - 2;

    x = m03;
    if (m00 != 0) {
        x = javaIntAdd(x, javaShiftRight(javaIntMul(x, m00), shift));
    }
    if (m01 != 0) {
        x = javaIntAdd(x, javaShiftRight(javaIntMul(y, m01), shift));
    }
    if (m02 != 0) {
        x = javaIntAdd(x, javaShiftRight(javaIntMul(z, m02), shift));
    }

    y = javaIntSub(0, m13);
    if (m10 != 0) {
        y = javaIntSub(y, javaShiftRight(javaIntMul(x, m10), shift));
    }
    if (m11 != 0) {
        y = javaIntSub(y, javaShiftRight(javaIntMul(y, m11), shift));
    }
    if (m12 != 0) {
        y = javaIntSub(y, javaShiftRight(javaIntMul(z, m12), shift));
    }

    z = m23;
    if (m20 != 0) {
        z = javaIntAdd(z, javaShiftRight(javaIntMul(x, m20), shift));
    }
    if (m21 != 0) {
        z = javaIntAdd(z, javaShiftRight(javaIntMul(y, m21), shift));
    }
    if (m22 != 0) {
        z = javaIntAdd(z, javaShiftRight(javaIntMul(z, m22), shift));
    }
}

QInt32 Vector3D::average() const
{
    return javaIntDiv(javaIntAdd(javaIntAdd(x, y), z), 3);
}

bool Vector3D::equals(const Vector3D& value) const
{
    return x == value.x && y == value.y && z == value.z;
}

} // namespace Math
} // namespace code
