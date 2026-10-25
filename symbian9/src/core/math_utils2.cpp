#include "math_utils2.h"

#include "quantum_integer.h"
#include "quantum_math.h"

#include <string.h>

namespace code {
namespace Math {

namespace {

typedef char FloatMustBe32Bits[(sizeof(float) == sizeof(QInt32)) ? 1 : -1];

QInt32 floatToJavaIntBits(float value)
{
    if (value != value) {
        /* Float.floatToIntBits canonicalizes every NaN payload. */
        return static_cast<QInt32>(0x7fc00000UL);
    }

    QInt32 bits = 0;
    memcpy(&bits, &value, sizeof(bits));
    return bits;
}

float floatFromIntBits(QInt32 bits)
{
    float value = 0.0f;
    memcpy(&value, &bits, sizeof(value));
    return value;
}

QInt32 javaAbsForNormalAxis(QInt32 value)
{
    return value > 0 ? value : javaIntSub(0, value);
}

void setNormalFromCoordinates(Vector3D& normal,
                              QInt32 ax, QInt32 ay, QInt32 az,
                              QInt32 bx, QInt32 by, QInt32 bz,
                              QInt32 cx, QInt32 cy, QInt32 cz)
{
    /*
     * Java evaluates each coordinate subtraction as int before widening the
     * left operand to long. Keep those wrap points, then retain long overflow
     * in the determinant subtraction as well.
     */
    const QInt32 ayBy = javaIntSub(ay, by);
    const QInt32 azCz = javaIntSub(az, cz);
    const QInt32 azBz = javaIntSub(az, bz);
    const QInt32 ayCy = javaIntSub(ay, cy);
    const QInt32 axCx = javaIntSub(ax, cx);
    const QInt32 axBx = javaIntSub(ax, bx);

    const QInt64 xWide = javaLongSub(javaLongMulInt(ayBy, azCz),
                                     javaLongMulInt(azBz, ayCy));
    const QInt64 yWide = javaLongSub(javaLongMulInt(azBz, axCx),
                                     javaLongMulInt(axBx, azCz));
    const QInt64 zWide = javaLongSub(javaLongMulInt(axBx, ayCy),
                                     javaLongMulInt(ayBy, axCx));

    const double x = static_cast<double>(xWide);
    const double y = static_cast<double>(yWide);
    const double z = static_cast<double>(zWide);
    const double length = quantumSqrt(x * x + y * y + z * z) / 4096.0;

    normal.set(javaDoubleToInt(x / length),
               javaDoubleToInt(y / length),
               javaDoubleToInt(z / length));
}

QInt32 distanceToSegmentVector(const Vector3D& point,
                               const Vector3D& origin,
                               QInt32 dx, QInt32 dy, QInt32 dz)
{
    const QInt32 wx = javaIntSub(point.x, origin.x);
    const QInt32 wy = javaIntSub(point.y, origin.y);
    const QInt32 wz = javaIntSub(point.z, origin.z);

    /*
     * Match Java's mixed-width expressions: only the first square/dot term is
     * widened before multiplication; the remaining int products wrap first.
     */
    QInt64 denominator = javaLongMulInt(dx, dx);
    denominator = javaLongAdd(denominator, javaIntMul(dy, dy));
    denominator = javaLongAdd(denominator, javaIntMul(dz, dz));

    QInt64 numerator = javaLongMulInt(wx, dx);
    numerator = javaLongAdd(numerator, javaIntMul(wy, dy));
    numerator = javaLongAdd(numerator, javaIntMul(wz, dz));

    QInt64 t = 0;
    if (denominator != 0) {
        t = javaLongDiv(javaShiftLeftLong(numerator, 14), denominator);
    }
    if (t < 0) {
        t = 0;
    }
    if (t > 16384) {
        t = 16384;
    }

    /* t is clamped to [0, 16384], so each widened product is in range. */
    const QInt32 closestX = javaIntAdd(
        origin.x, javaIntFromLong(javaShiftRightLong(
            static_cast<QInt64>(dx) * t, 14)));
    const QInt32 closestY = javaIntAdd(
        origin.y, javaIntFromLong(javaShiftRightLong(
            static_cast<QInt64>(dy) * t, 14)));
    const QInt32 closestZ = javaIntAdd(
        origin.z, javaIntFromLong(javaShiftRightLong(
            static_cast<QInt64>(dz) * t, 14)));

    const QInt32 diffX = javaIntSub(closestX, point.x);
    const QInt32 diffY = javaIntSub(closestY, point.y);
    const QInt32 diffZ = javaIntSub(closestZ, point.z);
    return javaIntAdd(javaIntAdd(javaIntMul(diffX, diffX),
                                 javaIntMul(diffY, diffY)),
                      javaIntMul(diffZ, diffZ));
}

} // anonymous namespace

float MathUtils2::invSqrt(float value)
{
    float xhalf = 0.5f * value;
    QInt32 bits = floatToJavaIntBits(value);
    bits = javaIntSub(1597463007, javaShiftRight(bits, 1));
    value = floatFromIntBits(bits);
    return value * (1.5F - xhalf * value * value);
}

Vector3D MathUtils2::createNormal(const Vector3D& a,
                                 const Vector3D& b,
                                 const Vector3D& c)
{
    return calcNormal(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z);
}

void MathUtils2::calcNormal(Vector3D& normal,
                            const Vector3D& a,
                            const Vector3D& b,
                            const Vector3D& c)
{
    calcNormal(normal, a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z);
}

void MathUtils2::calcNormal(Vector3D& normal,
                            QInt32 ax, QInt32 ay, QInt32 az,
                            QInt32 bx, QInt32 by, QInt32 bz,
                            QInt32 cx, QInt32 cy, QInt32 cz)
{
    setNormalFromCoordinates(normal, ax, ay, az, bx, by, bz, cx, cy, cz);
}

Vector3D MathUtils2::calcNormal(QInt32 ax, QInt32 ay, QInt32 az,
                                QInt32 bx, QInt32 by, QInt32 bz,
                                QInt32 cx, QInt32 cy, QInt32 cz)
{
    Vector3D normal(0, 0, 0);
    setNormalFromCoordinates(normal, ax, ay, az, bx, by, bz, cx, cy, cz);
    return normal;
}

QInt32 MathUtils2::computePolygonY(const Vector3D& pointOnPlane,
                                  QInt32 nx, QInt32 ny, QInt32 nz,
                                  QInt32 px, QInt32 py, QInt32 pz)
{
    return computePolygonY(pointOnPlane.x, pointOnPlane.y, pointOnPlane.z,
                           nx, ny, nz, px, py, pz);
}

QInt32 MathUtils2::computePolygonY(QInt32 ax, QInt32 ay, QInt32 az,
                                  QInt32 nx, QInt32 ny, QInt32 nz,
                                  QInt32 px, QInt32 py, QInt32 pz)
{
    if (ny >= 0) {
        return static_cast<QInt32>(2147483647L);
    }

    QInt32 numerator = javaIntMul(javaIntSub(px, ax), nx);
    numerator = javaIntAdd(numerator,
                           javaIntMul(javaIntSub(py, ay), ny));
    numerator = javaIntAdd(numerator,
                           javaIntMul(javaIntSub(pz, az), nz));
    return javaIntSub(py, javaIntDiv(numerator, ny));
}

QInt64 MathUtils2::side(QInt32 px, QInt32 py,
                        QInt32 ax, QInt32 ay,
                        QInt32 bx, QInt32 by)
{
    const QInt32 first = javaIntMul(javaIntSub(bx, ax), javaIntSub(py, ay));
    const QInt32 second = javaIntMul(javaIntSub(px, ax), javaIntSub(by, ay));
    return static_cast<QInt64>(javaIntSub(first, second));
}

QInt32 MathUtils2::distanceToFace(const Vector3D& pointOnFace,
                                  const Vector3D& normal,
                                  QInt32 px, QInt32 py, QInt32 pz)
{
    const Vector3D delta(javaIntSub(px, pointOnFace.x),
                         javaIntSub(py, pointOnFace.y),
                         javaIntSub(pz, pointOnFace.z));
    return javaShiftRight(delta.dot(normal), 12);
}

QInt32 MathUtils2::distanceToLine(const Vector3D& point,
                                  const Vector3D& a,
                                  const Vector3D& b)
{
    return distanceToSegmentVector(point, a,
                                   javaIntSub(b.x, a.x),
                                   javaIntSub(b.y, a.y),
                                   javaIntSub(b.z, a.z));
}

QInt32 MathUtils2::distanceToRay(const Vector3D& point,
                                 const Vector3D& origin,
                                 const Vector3D& direction)
{
    return distanceToSegmentVector(point, origin,
                                   direction.x, direction.y, direction.z);
}

bool MathUtils2::isPointOnPolygon(const Vector3D& point,
                                  const Vector3D& a,
                                  const Vector3D& b,
                                  const Vector3D& c,
                                  const Vector3D& d,
                                  const Vector3D& normal)
{
    return isPointOnPolygon(point.x, point.y, point.z,
                            a.x, a.y, a.z, b.x, b.y, b.z,
                            c.x, c.y, c.z, d.x, d.y, d.z,
                            normal.x, normal.y, normal.z);
}

bool MathUtils2::isPointOnPolygon(QInt32 pointx, QInt32 pointy, QInt32 pointz,
                                  QInt32 ax, QInt32 ay, QInt32 az,
                                  QInt32 bx, QInt32 by, QInt32 bz,
                                  QInt32 cx, QInt32 cy, QInt32 cz,
                                  QInt32 dx, QInt32 dy, QInt32 dz,
                                  QInt32 normalx, QInt32 normaly, QInt32 normalz)
{
    const QInt32 nx = javaAbsForNormalAxis(normalx);
    const QInt32 ny = javaAbsForNormalAxis(normaly);
    const QInt32 nz = javaAbsForNormalAxis(normalz);

    if (nx >= ny && nx >= nz) {
        if (normalx >= 0) {
            return isPointOnPolygon(pointz, pointy,
                                    az, ay, bz, by, cz, cy, dz, dy);
        }
        return isPointOnPolygon(pointz, pointy,
                                dz, dy, cz, cy, bz, by, az, ay);
    }
    if (ny >= nx && ny >= nz) {
        if (normaly >= 0) {
            return isPointOnPolygon(pointx, pointz,
                                    ax, az, bx, bz, cx, cz, dx, dz);
        }
        return isPointOnPolygon(pointx, pointz,
                                dx, dz, cx, cz, bx, bz, ax, az);
    }
    if (nz >= nx && nz >= ny) {
        if (normalz <= 0) {
            return isPointOnPolygon(pointx, pointy,
                                    ax, ay, bx, by, cx, cy, dx, dy);
        }
        return isPointOnPolygon(pointx, pointy,
                                dx, dy, cx, cy, bx, by, ax, ay);
    }
    return true;
}

bool MathUtils2::isPointOnPolygon(const Vector3D& point,
                                  const Vector3D& a,
                                  const Vector3D& b,
                                  const Vector3D& c,
                                  const Vector3D& normal)
{
    return isPointOnPolygon(point.x, point.y, point.z,
                            a.x, a.y, a.z, b.x, b.y, b.z,
                            c.x, c.y, c.z,
                            normal.x, normal.y, normal.z);
}

bool MathUtils2::isPointOnPolygon(QInt32 pointx, QInt32 pointy, QInt32 pointz,
                                  QInt32 ax, QInt32 ay, QInt32 az,
                                  QInt32 bx, QInt32 by, QInt32 bz,
                                  QInt32 cx, QInt32 cy, QInt32 cz,
                                  QInt32 normalx, QInt32 normaly, QInt32 normalz)
{
    const QInt32 nx = javaAbsForNormalAxis(normalx);
    const QInt32 ny = javaAbsForNormalAxis(normaly);
    const QInt32 nz = javaAbsForNormalAxis(normalz);

    if (nx >= ny && nx >= nz) {
        if (normalx >= 0) {
            return isPointOnPolygon(pointz, pointy,
                                    az, ay, bz, by, cz, cy);
        }
        return isPointOnPolygon(pointz, pointy,
                                cz, cy, bz, by, az, ay);
    }
    if (ny >= nx && ny >= nz) {
        if (normaly >= 0) {
            return isPointOnPolygon(pointx, pointz,
                                    ax, az, bx, bz, cx, cz);
        }
        return isPointOnPolygon(pointx, pointz,
                                cx, cz, bx, bz, ax, az);
    }
    if (nz >= nx && nz >= ny) {
        if (normalz <= 0) {
            return isPointOnPolygon(pointx, pointy,
                                    ax, ay, bx, by, cx, cy);
        }
        return isPointOnPolygon(pointx, pointy,
                                cx, cy, bx, by, ax, ay);
    }
    return true;
}

bool MathUtils2::isPointOnPolygon(QInt32 px, QInt32 pz,
                                  QInt32 ax, QInt32 az,
                                  QInt32 bx, QInt32 bz,
                                  QInt32 cx, QInt32 cz,
                                  QInt32 dx, QInt32 dz,
                                  QInt32 normalY)
{
    if (normalY >= 0) {
        return isPointOnPolygon(px, pz, ax, az, bx, bz, cx, cz, dx, dz);
    }
    if (normalY < 0) {
        return isPointOnPolygon(px, pz, dx, dz, cx, cz, bx, bz, ax, az);
    }
    return false;
}

bool MathUtils2::isPointOnPolygon(QInt32 px, QInt32 pz,
                                  QInt32 ax, QInt32 az,
                                  QInt32 bx, QInt32 bz,
                                  QInt32 cx, QInt32 cz,
                                  QInt32 normalY)
{
    if (normalY >= 0) {
        return isPointOnPolygon(px, pz, ax, az, bx, bz, cx, cz);
    }
    if (normalY < 0) {
        return isPointOnPolygon(px, pz, cx, cz, bx, bz, ax, az);
    }
    return false;
}

bool MathUtils2::isPointOnPolygon(QInt32 px, QInt32 py,
                                  QInt32 x1, QInt32 y1,
                                  QInt32 x2, QInt32 y2,
                                  QInt32 x3, QInt32 y3,
                                  QInt32 x4, QInt32 y4)
{
    return javaIntMul(javaIntSub(x2, x1), javaIntSub(py, y1)) <=
               javaIntMul(javaIntSub(px, x1), javaIntSub(y2, y1)) &&
           javaIntMul(javaIntSub(x3, x2), javaIntSub(py, y2)) <=
               javaIntMul(javaIntSub(px, x2), javaIntSub(y3, y2)) &&
           javaIntMul(javaIntSub(x4, x3), javaIntSub(py, y3)) <=
               javaIntMul(javaIntSub(px, x3), javaIntSub(y4, y3)) &&
           javaIntMul(javaIntSub(x1, x4), javaIntSub(py, y4)) <=
               javaIntMul(javaIntSub(px, x4), javaIntSub(y1, y4));
}

bool MathUtils2::isPointOnPolygon(QInt32 px, QInt32 py,
                                  QInt32 x1, QInt32 y1,
                                  QInt32 x2, QInt32 y2,
                                  QInt32 x3, QInt32 y3)
{
    return javaIntMul(javaIntSub(x2, x1), javaIntSub(py, y1)) <=
               javaIntMul(javaIntSub(px, x1), javaIntSub(y2, y1)) &&
           javaIntMul(javaIntSub(x3, x2), javaIntSub(py, y2)) <=
               javaIntMul(javaIntSub(px, x2), javaIntSub(y3, y2)) &&
           javaIntMul(javaIntSub(x1, x3), javaIntSub(py, y3)) <=
               javaIntMul(javaIntSub(px, x3), javaIntSub(y1, y3));
}

} // namespace Math
} // namespace code
