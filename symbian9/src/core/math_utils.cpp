#include "math_utils.h"

#include "quantum_integer.h"

#include <math.h>

namespace code {
namespace Math {

namespace {

void reduceLightVector(QInt32& x, QInt32& y, QInt32& z)
{
    while (javaIntAbs(x) > 30000 || javaIntAbs(y) > 30000 ||
           javaIntAbs(z) > 30000) {
        x = javaIntDiv(x, 2);
        y = javaIntDiv(y, 2);
        z = javaIntDiv(z, 2);
    }
}

double javaLightVectorLength(QInt32 x, QInt32 y, QInt32 z)
{
    QInt32 squared = javaIntMul(x, x);
    squared = javaIntAdd(squared, javaIntMul(y, y));
    squared = javaIntAdd(squared, javaIntMul(z, z));
    return ::sqrt(static_cast<double>(squared));
}

} // anonymous namespace

QInt32 MathUtils::calcLight(QInt32 nx, QInt32 ny, QInt32 nz,
                            QInt32 sx, QInt32 sy, QInt32 sz)
{
    reduceLightVector(sx, sy, sz);
    reduceLightVector(nx, ny, nz);

    double normalLength = javaLightVectorLength(nx, ny, nz);
    double lightLength = javaLightVectorLength(sx, sy, sz);
    if (normalLength < 1.0) normalLength = 1.0;
    if (lightLength < 1.0) lightLength = 1.0;

    QInt32 dot = javaIntMul(nx, sx);
    dot = javaIntAdd(dot, javaIntMul(ny, sy));
    dot = javaIntAdd(dot, javaIntMul(nz, sz));
    const double cosine = static_cast<double>(dot) /
                          (normalLength * lightLength);
    QInt32 result = javaDoubleToInt(cosine * 256.0);
    if (result < 0) result = 0;
    if (result > 255) result = 255;
    return result;
}

QInt32 MathUtils::calcLight(QInt32 nx, QInt32 ny, QInt32 nz,
                            QInt32 sx, QInt32 sy, QInt32 sz,
                            QInt32 falloff)
{
    reduceLightVector(sx, sy, sz);
    reduceLightVector(nx, ny, nz);

    double normalLength = javaLightVectorLength(nx, ny, nz);
    double lightLength = javaLightVectorLength(sx, sy, sz);
    if (normalLength < 1.0) normalLength = 1.0;
    if (lightLength < 1.0) lightLength = 1.0;

    QInt32 dot = javaIntMul(nx, sx);
    dot = javaIntAdd(dot, javaIntMul(ny, sy));
    dot = javaIntAdd(dot, javaIntMul(nz, sz));
    const double cosine = static_cast<double>(dot) /
                          (normalLength * lightLength);
    QInt32 brightness = javaDoubleToInt(cosine * 256.0 + falloff);
    if (brightness < 0) brightness = 0;
    QInt32 result = javaIntDiv(javaIntMul(brightness, 255),
                               javaIntAdd(255, falloff));
    return result > 255 ? 255 : result;
}

QInt32 MathUtils::microCalcLight(QInt32 nx, QInt32 ny, QInt32 nz,
                                 QInt32 sx, QInt32 sy, QInt32 sz)
{
    while (sx > 30000 || sy > 30000 || sz > 30000 ||
           sx < -30000 || sy < -30000 || sz < -30000) {
        sx = javaShiftRight(sx, 1);
        sy = javaShiftRight(sy, 1);
        sz = javaShiftRight(sz, 1);
    }

    double lightLength = javaLightVectorLength(sx, sy, sz);
    if (lightLength < 1.0) lightLength = 1.0;

    QInt32 dot = javaIntMul(nx, sx);
    dot = javaIntAdd(dot, javaIntMul(ny, sy));
    dot = javaIntAdd(dot, javaIntMul(nz, sz));
    const double value = (static_cast<double>(dot) /
                          (lightLength * 4096.0)) * 255.0;
    QInt32 result = javaDoubleToInt(value);
    return result > 255 ? 255 : result;
}

QInt32 MathUtils::fixDegree(QInt32 degree)
{
    degree %= 360;
    if (degree < 0) {
        degree += 360;
    }
    return degree;
}

QInt32 MathUtils::fixDegree2(QInt32 degree)
{
    degree %= 2048;
    if (degree < 0) {
        degree += 2048;
    }
    return degree;
}

QInt32 MathUtils::getAnglez(QInt32 x, QInt32 y, QInt32 x1, QInt32 y1)
{
    QInt32 anglez = 0;
    const QInt32 dx = javaIntSub(x, x1);
    const QInt32 dy = javaIntSub(y, y1);
    const QInt32 distance = javaIntAdd(javaIntAbs(dx), javaIntAbs(dy));
    if (distance == 0) {
        return 0;
    }

    QInt32 xx = javaIntDiv(javaIntMul(dx, 4000), distance);
    const QInt32 yy = javaIntDiv(javaIntMul(dy, 4000), distance);

    if (xx < 4000 && xx > 0 && yy > 0) {
        xx = 0;
    }

    if (xx > 0) {
        anglez = javaIntAdd(anglez, javaIntMul(xx, 360));
    }
    if (xx < 0) {
        anglez = javaIntSub(anglez, javaIntMul(xx, 180));
    }
    if (yy > 0) {
        anglez = javaIntAdd(anglez, javaIntMul(yy, 90));
    }
    if (yy < 0) {
        anglez = javaIntSub(anglez, javaIntMul(yy, 270));
    }

    anglez = javaIntDiv(anglez, 4000);
    anglez = javaIntSub(anglez, 90);
    return fixDegree(anglez);
}

QInt32 MathUtils::getAnglezHQ(QInt32 x, QInt32 y, QInt32 x1, QInt32 y1)
{
    QInt32 anglez = 0;
    const QInt32 dx = javaIntSub(x, x1);
    const QInt32 dy = javaIntSub(y, y1);
    const QInt32 distance = javaIntAdd(javaIntAbs(dx), javaIntAbs(dy));
    if (distance == 0) {
        return 0;
    }

    QInt32 xx = javaIntDiv(javaIntMul(dx, 4000), distance);
    const QInt32 yy = javaIntDiv(javaIntMul(dy, 4000), distance);

    if (xx < 4000 && xx > 0 && yy > 0) {
        xx = 0;
    }

    if (xx > 0) {
        anglez = javaIntAdd(anglez, javaIntMul(xx, 2048));
    }
    if (xx < 0) {
        anglez = javaIntSub(anglez, javaIntMul(xx, 1024));
    }
    if (yy > 0) {
        anglez = javaIntAdd(anglez, javaIntMul(yy, 512));
    }
    if (yy < 0) {
        anglez = javaIntSub(anglez, javaIntMul(yy, 1536));
    }

    anglez = javaIntDiv(anglez, 4000);
    anglez = javaIntSub(anglez, 512);
    return fixDegree2(anglez);
}

QInt32 MathUtils::round(double value)
{
    const QInt32 truncated = javaDoubleToInt(value);
    const QInt32 absoluteTruncated = javaIntAbs(truncated);
    const double absoluteValue = value < 0.0 ? -value : value;
    const double remainder = absoluteValue - static_cast<double>(absoluteTruncated);
    const QInt32 adjustment = value > 0.0 ? 1 : -1;
    return javaIntAdd(truncated, remainder >= 0.5 ? adjustment : 0);
}

QInt32 MathUtils::ceil(double value)
{
    const QInt32 truncated = javaDoubleToInt(value);
    const QInt32 adjustment = value > 0.0 ? 1 : -1;

    /*
     * The original helper adds +1 for positive fractions and -1 for negative
     * fractions (so negative values behave like floor, not std::ceil). Keep
     * that quirk for parity. All engine callers pass bounded vector values;
     * NaN is handled the same way as Java's cast-and-compare sequence.
     */
    bool hasFraction = false;
    if (value != value) {
        hasFraction = true;
    } else if (value >= -9223372036854775808.0 &&
               value < 9223372036854775808.0) {
        const QInt64 truncatedLong = static_cast<QInt64>(value);
        hasFraction = value != static_cast<double>(truncatedLong);
    }

    return javaIntAdd(truncated, hasFraction ? adjustment : 0);
}

} // namespace Math
} // namespace code
