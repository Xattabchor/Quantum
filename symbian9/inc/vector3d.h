#ifndef QUANTUM_VECTOR3D_H
#define QUANTUM_VECTOR3D_H

#include "quantum_types.h"

namespace code {
namespace Math {

class Matrix;

class Vector3D {
public:
    QInt32 x;
    QInt32 y;
    QInt32 z;

    Vector3D();
    Vector3D(QInt32 xValue, QInt32 yValue, QInt32 zValue);
    Vector3D(const QInt32* values, QInt32 count);

    void set(QInt32 xValue, QInt32 yValue, QInt32 zValue);
    void set(QInt32 value);
    void set(const Vector3D& value);
    void add(QInt32 xValue, QInt32 yValue, QInt32 zValue);
    void div(QInt32 xValue, QInt32 yValue, QInt32 zValue);
    void mul(QInt32 xValue, QInt32 yValue, QInt32 zValue);

    QInt32 length() const;
    QInt32 lengthSquared() const;
    void setLength(QInt32 length);
    void setLength2(QInt32 length);
    void setLengthRound(QInt32 length);
    void interpolation(const Vector3D& value, QInt32 speed);

    QInt32 dot(const Vector3D& value) const;
    QInt64 dotLong(const Vector3D& value) const;
    void cross(const Vector3D& left, const Vector3D& right);
    void cross(const Vector3D& left, const Vector3D& right, QInt32 fractionBits);
    void mul(Matrix& matrix);

    QInt32 average() const;
    bool equals(const Vector3D& value) const;
};

} // namespace Math
} // namespace code

#endif // QUANTUM_VECTOR3D_H
