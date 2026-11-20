#include "matrix.h"

#include "math_utils.h"
#include "quantum_integer.h"
#include "quantum_math.h"
#include "vector3d.h"

namespace code {
namespace Math {

namespace {

QInt32 gSinTable[360];
QInt32 gCosTable[360];
bool gTrigTableReady = false;

QInt32 productSum3(QInt32 a0, QInt32 b0,
                   QInt32 a1, QInt32 b1,
                   QInt32 a2, QInt32 b2)
{
    return javaIntAdd(javaIntAdd(javaIntMul(a0, b0), javaIntMul(a1, b1)),
                      javaIntMul(a2, b2));
}

QInt64 wideProductSum4(QInt32 a0, QInt32 b0,
                       QInt32 a1, QInt32 b1,
                       QInt32 a2, QInt32 b2,
                       QInt32 a3, QInt32 b3)
{
    QInt64 result = javaLongMulInt(a0, b0);
    result = javaLongAdd(result, javaLongMulInt(a1, b1));
    result = javaLongAdd(result, javaLongMulInt(a2, b2));
    return javaLongAdd(result, javaLongMulInt(a3, b3));
}

} // anonymous namespace

Matrix::Matrix()
    : m00(FP), m01(0), m02(0), m03(0),
      m10(0), m11(FP), m12(0), m13(0),
      m20(0), m21(0), m22(FP), m23(0),
      m30(0), m31(0), m32(0), m33(FP)
{
    ensureTrigTable();
}

void Matrix::ensureTrigTable()
{
    if (gTrigTableReady) {
        return;
    }

    const double pi = 3.14159265358979323846;
    QInt32 degree;
    for (degree = 0; degree < 360; ++degree) {
        const double radians = static_cast<double>(degree) * pi / 180.0;
        gSinTable[degree] = javaDoubleToInt(quantumSin(radians) * FP);
        gCosTable[degree] = javaDoubleToInt(quantumCos(radians) * FP);
    }
    gTrigTableReady = true;
}

void Matrix::setIdentity()
{
    m00 = FP;
    m01 = 0;
    m02 = 0;
    m03 = 0;
    m10 = 0;
    m11 = FP;
    m12 = 0;
    m13 = 0;
    m20 = 0;
    m21 = 0;
    m22 = FP;
    m23 = 0;
}

void Matrix::set(const Matrix& value)
{
    m00 = value.m00;
    m01 = value.m01;
    m02 = value.m02;
    m03 = value.m03;
    m10 = value.m10;
    m11 = value.m11;
    m12 = value.m12;
    m13 = value.m13;
    m20 = value.m20;
    m21 = value.m21;
    m22 = value.m22;
    m23 = value.m23;
}

void Matrix::set(QInt32 mm00, QInt32 mm01, QInt32 mm02, QInt32 mm03,
                 QInt32 mm10, QInt32 mm11, QInt32 mm12, QInt32 mm13,
                 QInt32 mm20, QInt32 mm21, QInt32 mm22, QInt32 mm23)
{
    m00 = mm00;
    m01 = mm01;
    m02 = mm02;
    m03 = mm03;
    m10 = mm10;
    m11 = mm11;
    m12 = mm12;
    m13 = mm13;
    m20 = mm20;
    m21 = mm21;
    m22 = mm22;
    m23 = mm23;
}

void Matrix::getPosition(Vector3D& value) const
{
    value.set(m03, m13, m23);
}

void Matrix::invert()
{
    QInt64 var1 = javaShiftRightLong(javaLongMulInt(m00, m03), fp);
    var1 = javaLongAdd(var1,
        javaShiftRightLong(javaLongMulInt(m10, m13), fp));
    var1 = javaLongAdd(var1,
        javaShiftRightLong(javaLongMulInt(m20, m23), fp));

    QInt64 var3 = javaShiftRightLong(javaLongMulInt(m01, m03), fp);
    var3 = javaLongAdd(var3,
        javaShiftRightLong(javaLongMulInt(m11, m13), fp));
    var3 = javaLongAdd(var3,
        javaShiftRightLong(javaLongMulInt(m21, m23), fp));

    QInt64 var5 = javaShiftRightLong(javaLongMulInt(m02, m03), fp);
    var5 = javaLongAdd(var5,
        javaShiftRightLong(javaLongMulInt(m12, m13), fp));
    var5 = javaLongAdd(var5,
        javaShiftRightLong(javaLongMulInt(m22, m23), fp));

    m03 = javaIntFromLong(javaLongFromUnsigned(
        static_cast<QUint64>(0) - static_cast<QUint64>(var1)));
    m13 = javaIntFromLong(javaLongFromUnsigned(
        static_cast<QUint64>(0) - static_cast<QUint64>(var3)));
    m23 = javaIntFromLong(javaLongFromUnsigned(
        static_cast<QUint64>(0) - static_cast<QUint64>(var5)));

    QInt32 value = m01;
    m01 = m10;
    m10 = value;
    value = m02;
    m02 = m20;
    m20 = value;
    value = m12;
    m12 = m21;
    m21 = value;
}

QInt32 Matrix::fixDegree(QInt32 degree)
{
    return MathUtils::fixDegree(degree);
}

void Matrix::setRotX(QInt32 degree)
{
    ensureTrigTable();
    degree = fixDegree(degree);
    m00 = FP;
    m01 = 0;
    m02 = 0;
    m03 = 0;
    m10 = 0;
    m11 = gCosTable[degree];
    m12 = -gSinTable[degree];
    m13 = 0;
    m20 = 0;
    m21 = gSinTable[degree];
    m22 = gCosTable[degree];
    m23 = 0;
}

void Matrix::mul(Matrix& value)
{
    mul(*this, value);
}

void Matrix::mul(const Matrix& left, const Matrix& right)
{
    const unsigned int shift = fp;

    /*
     * Preserve the Java source's mixed 32/64-bit evaluation: the first three
     * products in columns 0..2 wrap as int before the final widened product;
     * column 3 is evaluated fully as long arithmetic.
     */
    const QInt32 s00 = productSum3(left.m00, right.m00,
                                   left.m01, right.m10,
                                   left.m02, right.m20);
    const QInt32 s01 = productSum3(left.m00, right.m01,
                                   left.m01, right.m11,
                                   left.m02, right.m21);
    const QInt32 s02 = productSum3(left.m00, right.m02,
                                   left.m01, right.m12,
                                   left.m02, right.m22);
    const QInt64 t00 = javaShiftRightLong(javaLongAdd(
        static_cast<QInt64>(s00), javaLongMulInt(left.m03, right.m30)), shift);
    const QInt64 t01 = javaShiftRightLong(javaLongAdd(
        static_cast<QInt64>(s01), javaLongMulInt(left.m03, right.m31)), shift);
    const QInt64 t02 = javaShiftRightLong(javaLongAdd(
        static_cast<QInt64>(s02), javaLongMulInt(left.m03, right.m32)), shift);
    const QInt64 t03 = javaShiftRightLong(wideProductSum4(
        left.m00, right.m03, left.m01, right.m13,
        left.m02, right.m23, left.m03, right.m33), shift);

    const QInt32 s10 = productSum3(left.m10, right.m00,
                                   left.m11, right.m10,
                                   left.m12, right.m20);
    const QInt32 s11 = productSum3(left.m10, right.m01,
                                   left.m11, right.m11,
                                   left.m12, right.m21);
    const QInt32 s12 = productSum3(left.m10, right.m02,
                                   left.m11, right.m12,
                                   left.m12, right.m22);
    const QInt64 t10 = javaShiftRightLong(javaLongAdd(
        static_cast<QInt64>(s10), javaLongMulInt(left.m13, right.m30)), shift);
    const QInt64 t11 = javaShiftRightLong(javaLongAdd(
        static_cast<QInt64>(s11), javaLongMulInt(left.m13, right.m31)), shift);
    const QInt64 t12 = javaShiftRightLong(javaLongAdd(
        static_cast<QInt64>(s12), javaLongMulInt(left.m13, right.m32)), shift);
    const QInt64 t13 = javaShiftRightLong(wideProductSum4(
        left.m10, right.m03, left.m11, right.m13,
        left.m12, right.m23, left.m13, right.m33), shift);

    const QInt32 s20 = productSum3(left.m20, right.m00,
                                   left.m21, right.m10,
                                   left.m22, right.m20);
    const QInt32 s21 = productSum3(left.m20, right.m01,
                                   left.m21, right.m11,
                                   left.m22, right.m21);
    const QInt32 s22 = productSum3(left.m20, right.m02,
                                   left.m21, right.m12,
                                   left.m22, right.m22);
    const QInt64 t20 = javaShiftRightLong(javaLongAdd(
        static_cast<QInt64>(s20), javaLongMulInt(left.m23, right.m30)), shift);
    const QInt64 t21 = javaShiftRightLong(javaLongAdd(
        static_cast<QInt64>(s21), javaLongMulInt(left.m23, right.m31)), shift);
    const QInt64 t22 = javaShiftRightLong(javaLongAdd(
        static_cast<QInt64>(s22), javaLongMulInt(left.m23, right.m32)), shift);
    const QInt64 t23 = javaShiftRightLong(wideProductSum4(
        left.m20, right.m03, left.m21, right.m13,
        left.m22, right.m23, left.m23, right.m33), shift);

    m00 = javaIntFromLong(t00);
    m01 = javaIntFromLong(t01);
    m02 = javaIntFromLong(t02);
    m03 = javaIntFromLong(t03);
    m10 = javaIntFromLong(t10);
    m11 = javaIntFromLong(t11);
    m12 = javaIntFromLong(t12);
    m13 = javaIntFromLong(t13);
    m20 = javaIntFromLong(t20);
    m21 = javaIntFromLong(t21);
    m22 = javaIntFromLong(t22);
    m23 = javaIntFromLong(t23);
}

void Matrix::rotY(QInt32 degree)
{
    ensureTrigTable();
    degree = fixDegree(degree);
    const QInt32 c = gCosTable[degree];
    const QInt32 s = gSinTable[degree];

    const QInt32 xX = javaShiftRight(javaIntAdd(javaIntMul(m00, c),
                                                 javaIntMul(m20, s)), fp);
    const QInt32 xY = javaShiftRight(javaIntAdd(javaIntMul(m01, c),
                                                 javaIntMul(m21, s)), fp);
    const QInt32 xZ = javaShiftRight(javaIntAdd(javaIntMul(m02, c),
                                                 javaIntMul(m22, s)), fp);
    const QInt32 zX = javaShiftRight(javaIntSub(javaIntMul(m20, c),
                                                 javaIntMul(m00, s)), fp);
    const QInt32 zY = javaShiftRight(javaIntSub(javaIntMul(m21, c),
                                                 javaIntMul(m01, s)), fp);
    const QInt32 zZ = javaShiftRight(javaIntSub(javaIntMul(m22, c),
                                                 javaIntMul(m02, s)), fp);

    m00 = xX;
    m01 = xY;
    m02 = xZ;
    m20 = zX;
    m21 = zY;
    m22 = zZ;
}

void Matrix::setRotY(QInt32 degree)
{
    ensureTrigTable();
    degree = fixDegree(degree);
    m00 = gCosTable[degree];
    m01 = 0;
    m02 = gSinTable[degree];
    m03 = 0;
    m10 = 0;
    m11 = FP;
    m12 = 0;
    m13 = 0;
    m20 = -gSinTable[degree];
    m21 = 0;
    m22 = gCosTable[degree];
    m23 = 0;
}

void Matrix::setRotZ(QInt32 degree)
{
    ensureTrigTable();
    degree = fixDegree(degree);
    m00 = gCosTable[degree];
    m01 = -gSinTable[degree];
    m02 = 0;
    m03 = 0;
    m10 = gSinTable[degree];
    m11 = gCosTable[degree];
    m12 = 0;
    m13 = 0;
    m20 = 0;
    m21 = 0;
    m22 = FP;
    m23 = 0;
}

void Matrix::rotZ(QInt32 degree)
{
    ensureTrigTable();
    degree = fixDegree(degree);
    const QInt32 c = gCosTable[degree];
    const QInt32 s = gSinTable[degree];

    const QInt32 yX = javaShiftRight(javaIntAdd(javaIntMul(m10, c),
                                                 javaIntMul(m00, s)), fp);
    const QInt32 yY = javaShiftRight(javaIntAdd(javaIntMul(m11, c),
                                                 javaIntMul(m01, s)), fp);
    const QInt32 yZ = javaShiftRight(javaIntAdd(javaIntMul(m12, c),
                                                 javaIntMul(m02, s)), fp);
    const QInt32 xX = javaShiftRight(javaIntSub(javaIntMul(m00, c),
                                                 javaIntMul(m10, s)), fp);
    const QInt32 xY = javaShiftRight(javaIntSub(javaIntMul(m01, c),
                                                 javaIntMul(m11, s)), fp);
    const QInt32 xZ = javaShiftRight(javaIntSub(javaIntMul(m02, c),
                                                 javaIntMul(m12, s)), fp);

    m10 = yX;
    m11 = yY;
    m12 = yZ;
    m00 = xX;
    m01 = xY;
    m02 = xZ;
}

void Matrix::setPosition(QInt32 x, QInt32 y, QInt32 z)
{
    m03 = x;
    m13 = y;
    m23 = z;
}

void Matrix::setPosition(const Vector3D& position)
{
    m03 = position.x;
    m13 = position.y;
    m23 = position.z;
}

void Matrix::addPosition(const Vector3D& position)
{
    m03 = javaIntAdd(m03, position.x);
    m13 = javaIntAdd(m13, position.y);
    m23 = javaIntAdd(m23, position.z);
}

void Matrix::divPosition(QInt32 x, QInt32 y, QInt32 z)
{
    m03 = javaIntDiv(m03, x);
    m13 = javaIntDiv(m13, y);
    m23 = javaIntDiv(m23, z);
}

void Matrix::subPosition(QInt32 x, QInt32 y, QInt32 z)
{
    m03 = javaIntSub(m03, x);
    m13 = javaIntSub(m13, y);
    m23 = javaIntSub(m23, z);
}

void Matrix::addPosition(QInt32 x, QInt32 y, QInt32 z)
{
    m03 = javaIntAdd(m03, x);
    m13 = javaIntAdd(m13, y);
    m23 = javaIntAdd(m23, z);
}

void Matrix::setSide(QInt32 x, QInt32 y, QInt32 z)
{
    m00 = x;
    m10 = y;
    m20 = z;
}

void Matrix::scale(QInt32 x, QInt32 y, QInt32 z)
{
    m00 = javaShiftRight(javaIntMul(m00, x), fp);
    m10 = javaShiftRight(javaIntMul(m10, x), fp);
    m20 = javaShiftRight(javaIntMul(m20, x), fp);
    m01 = javaShiftRight(javaIntMul(m01, y), fp);
    m11 = javaShiftRight(javaIntMul(m11, y), fp);
    m21 = javaShiftRight(javaIntMul(m21, y), fp);
    m02 = javaShiftRight(javaIntMul(m02, z), fp);
    m12 = javaShiftRight(javaIntMul(m12, z), fp);
    m22 = javaShiftRight(javaIntMul(m22, z), fp);
}

void Matrix::setUp(QInt32 x, QInt32 y, QInt32 z)
{
    m01 = x;
    m11 = y;
    m21 = z;
}

void Matrix::setDir(QInt32 x, QInt32 y, QInt32 z)
{
    m02 = x;
    m12 = y;
    m22 = z;
}

void Matrix::translate(QInt32 x, QInt32 y, QInt32 z)
{
    if (x != 0) {
        m03 = javaIntAdd(m03, javaShiftRight(javaIntMul(m00, x), fp));
        m13 = javaIntAdd(m13, javaShiftRight(javaIntMul(m10, x), fp));
        m23 = javaIntAdd(m23, javaShiftRight(javaIntMul(m20, x), fp));
    }
    if (y != 0) {
        m03 = javaIntAdd(m03, javaShiftRight(javaIntMul(m01, y), fp));
        m13 = javaIntAdd(m13, javaShiftRight(javaIntMul(m11, y), fp));
        m23 = javaIntAdd(m23, javaShiftRight(javaIntMul(m21, y), fp));
    }
    if (z != 0) {
        m03 = javaIntAdd(m03, javaShiftRight(javaIntMul(m02, z), fp));
        m13 = javaIntAdd(m13, javaShiftRight(javaIntMul(m12, z), fp));
        m23 = javaIntAdd(m23, javaShiftRight(javaIntMul(m22, z), fp));
    }
}

QInt32 Matrix::cos(QInt32 degree)
{
    ensureTrigTable();
    return gCosTable[fixDegree(degree)];
}

QInt32 Matrix::sin(QInt32 degree)
{
    ensureTrigTable();
    return gSinTable[fixDegree(degree)];
}

QInt32 Matrix::getRotZ() const
{
    const QInt32 rotation = MathUtils::getAnglez(0, 0,
                                                  javaIntSub(0, m02),
                                                  javaIntSub(0, m22));
    return MathUtils::fixDegree(rotation);
}

QInt32 Matrix::getRotZHQ() const
{
    return MathUtils::getAnglezHQ(0, 0,
                                  javaIntSub(0, m02),
                                  javaIntSub(0, m22));
}

} // namespace Math
} // namespace code
