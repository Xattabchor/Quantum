#ifndef QUANTUM_MATH_UTILS_H
#define QUANTUM_MATH_UTILS_H

#include "quantum_types.h"

namespace code {
namespace Math {

class MathUtils {
public:
    /* Java MathUtils directional-light helpers used by mesh and texture modes. */
    static QInt32 calcLight(QInt32 nx, QInt32 ny, QInt32 nz,
                            QInt32 sx, QInt32 sy, QInt32 sz);
    static QInt32 calcLight(QInt32 nx, QInt32 ny, QInt32 nz,
                            QInt32 sx, QInt32 sy, QInt32 sz,
                            QInt32 falloff);
    static QInt32 microCalcLight(QInt32 nx, QInt32 ny, QInt32 nz,
                                 QInt32 sx, QInt32 sy, QInt32 sz);

    static QInt32 fixDegree(QInt32 degree);
    static QInt32 fixDegree2(QInt32 degree);
    static QInt32 getAnglez(QInt32 x, QInt32 y, QInt32 x1, QInt32 y1);
    static QInt32 getAnglezHQ(QInt32 x, QInt32 y, QInt32 x1, QInt32 y1);
    static QInt32 round(double value);
    /* Retains the original Java helper's behavior for negative fractions. */
    static QInt32 ceil(double value);
};

} // namespace Math
} // namespace code

#endif // QUANTUM_MATH_UTILS_H
