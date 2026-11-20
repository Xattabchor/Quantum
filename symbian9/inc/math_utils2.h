#ifndef QUANTUM_MATH_UTILS2_H
#define QUANTUM_MATH_UTILS2_H

#include "quantum_types.h"
#include "vector3d.h"

namespace code {
namespace Math {

/*
 * Platform-independent port of the geometry helpers in MathUtils2.java.
 * Java int wraparound, arithmetic shifts, and narrowing conversions are
 * preserved explicitly for deterministic behavior across S60v3 targets.
 */
class MathUtils2 {
public:
    static float invSqrt(float value);

    static Vector3D createNormal(const Vector3D& a,
                                 const Vector3D& b,
                                 const Vector3D& c);
    static void calcNormal(Vector3D& normal,
                           const Vector3D& a,
                           const Vector3D& b,
                           const Vector3D& c);
    static void calcNormal(Vector3D& normal,
                           QInt32 ax, QInt32 ay, QInt32 az,
                           QInt32 bx, QInt32 by, QInt32 bz,
                           QInt32 cx, QInt32 cy, QInt32 cz);
    static Vector3D calcNormal(QInt32 ax, QInt32 ay, QInt32 az,
                               QInt32 bx, QInt32 by, QInt32 bz,
                               QInt32 cx, QInt32 cy, QInt32 cz);

    static QInt32 computePolygonY(const Vector3D& pointOnPlane,
                                  QInt32 nx, QInt32 ny, QInt32 nz,
                                  QInt32 px, QInt32 py, QInt32 pz);
    static QInt32 computePolygonY(QInt32 ax, QInt32 ay, QInt32 az,
                                  QInt32 nx, QInt32 ny, QInt32 nz,
                                  QInt32 px, QInt32 py, QInt32 pz);

    /* The Java implementation wraps the 2D expression as an int, then widens. */
    static QInt64 side(QInt32 px, QInt32 py,
                       QInt32 ax, QInt32 ay,
                       QInt32 bx, QInt32 by);

    static QInt32 distanceToFace(const Vector3D& pointOnFace,
                                 const Vector3D& normal,
                                 QInt32 px, QInt32 py, QInt32 pz);
    static QInt32 distanceToLine(const Vector3D& point,
                                 const Vector3D& a,
                                 const Vector3D& b);
    static QInt32 distanceToRay(const Vector3D& point,
                                const Vector3D& origin,
                                const Vector3D& direction);

    static bool isPointOnPolygon(const Vector3D& point,
                                 const Vector3D& a,
                                 const Vector3D& b,
                                 const Vector3D& c,
                                 const Vector3D& d,
                                 const Vector3D& normal);
    static bool isPointOnPolygon(QInt32 pointx, QInt32 pointy, QInt32 pointz,
                                 QInt32 ax, QInt32 ay, QInt32 az,
                                 QInt32 bx, QInt32 by, QInt32 bz,
                                 QInt32 cx, QInt32 cy, QInt32 cz,
                                 QInt32 dx, QInt32 dy, QInt32 dz,
                                 QInt32 normalx, QInt32 normaly, QInt32 normalz);

    static bool isPointOnPolygon(const Vector3D& point,
                                 const Vector3D& a,
                                 const Vector3D& b,
                                 const Vector3D& c,
                                 const Vector3D& normal);
    static bool isPointOnPolygon(QInt32 pointx, QInt32 pointy, QInt32 pointz,
                                 QInt32 ax, QInt32 ay, QInt32 az,
                                 QInt32 bx, QInt32 by, QInt32 bz,
                                 QInt32 cx, QInt32 cy, QInt32 cz,
                                 QInt32 normalx, QInt32 normaly, QInt32 normalz);

    static bool isPointOnPolygon(QInt32 px, QInt32 pz,
                                 QInt32 ax, QInt32 az,
                                 QInt32 bx, QInt32 bz,
                                 QInt32 cx, QInt32 cz,
                                 QInt32 dx, QInt32 dz,
                                 QInt32 normalY);
    static bool isPointOnPolygon(QInt32 px, QInt32 pz,
                                 QInt32 ax, QInt32 az,
                                 QInt32 bx, QInt32 bz,
                                 QInt32 cx, QInt32 cz,
                                 QInt32 normalY);

    static bool isPointOnPolygon(QInt32 px, QInt32 py,
                                 QInt32 x1, QInt32 y1,
                                 QInt32 x2, QInt32 y2,
                                 QInt32 x3, QInt32 y3,
                                 QInt32 x4, QInt32 y4);
    static bool isPointOnPolygon(QInt32 px, QInt32 py,
                                 QInt32 x1, QInt32 y1,
                                 QInt32 x2, QInt32 y2,
                                 QInt32 x3, QInt32 y3);
};

} // namespace Math
} // namespace code

#endif // QUANTUM_MATH_UTILS2_H
