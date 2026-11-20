#ifndef QUANTUM_MATRIX_H
#define QUANTUM_MATRIX_H

#include "quantum_types.h"

namespace code {
namespace Math {

class Vector3D;

class Matrix {
public:
    enum { fp = 14, FP = 1 << fp };

    QInt32 m00, m01, m02, m03;
    QInt32 m10, m11, m12, m13;
    QInt32 m20, m21, m22, m23;
    QInt32 m30, m31, m32, m33;

    Matrix();

    void setIdentity();
    void set(const Matrix& value);
    void set(QInt32 mm00, QInt32 mm01, QInt32 mm02, QInt32 mm03,
             QInt32 mm10, QInt32 mm11, QInt32 mm12, QInt32 mm13,
             QInt32 mm20, QInt32 mm21, QInt32 mm22, QInt32 mm23);
    void getPosition(Vector3D& value) const;
    void invert();

    void setRotX(QInt32 degree);
    void mul(Matrix& value);
    void mul(const Matrix& left, const Matrix& right);
    void rotY(QInt32 degree);
    void setRotY(QInt32 degree);
    void setRotZ(QInt32 degree);
    void rotZ(QInt32 degree);

    void setPosition(QInt32 x, QInt32 y, QInt32 z);
    void setPosition(const Vector3D& position);
    void addPosition(const Vector3D& position);
    void divPosition(QInt32 x, QInt32 y, QInt32 z);
    void subPosition(QInt32 x, QInt32 y, QInt32 z);
    void addPosition(QInt32 x, QInt32 y, QInt32 z);
    void setSide(QInt32 x, QInt32 y, QInt32 z);
    void scale(QInt32 x, QInt32 y, QInt32 z);
    void setUp(QInt32 x, QInt32 y, QInt32 z);
    void setDir(QInt32 x, QInt32 y, QInt32 z);
    void translate(QInt32 x, QInt32 y, QInt32 z);

    static QInt32 cos(QInt32 degree);
    static QInt32 sin(QInt32 degree);
    QInt32 getRotZ() const;
    QInt32 getRotZHQ() const;

private:
    static QInt32 fixDegree(QInt32 degree);
    static void ensureTrigTable();
};

} // namespace Math
} // namespace code

#endif // QUANTUM_MATRIX_H
