#ifndef QUANTUM_RAY_CAST_H
#define QUANTUM_RAY_CAST_H

#include "collision_mesh_view.h"
#include "collision_polygon.h"
#include "vector3d.h"

namespace code {
namespace Collision {

class Ray {
public:
    bool collision;
    code::Math::QInt32 distance;
    const void* triangle;
    const void* originalPolygon;
    bool doubleSide;
    bool findNearest;
    bool infinity;
    bool ignoreNonShadowed;
    bool onlyCollidable;
    code::Math::QInt32 numRoom;

    code::Math::Vector3D collisionPoint;
    code::Math::Vector3D start;
    code::Math::Vector3D dir;

    Ray();
    void reset();
};

class RayCast {
public:
    static void cast(const CollisionPolygon* polygons,
                     code::Math::QInt32 polygonCount,
                     Ray& ray,
                     bool recalculateNormals);
    static void cast(const CollisionMeshView& mesh,
                     Ray& ray,
                     bool recalculateNormals);
    static void superFastCast(const CollisionPolygon* polygons,
                              code::Math::QInt32 polygonCount,
                              Ray& ray);
    static void superFastCast(const CollisionMeshView& mesh, Ray& ray);

    static bool intersectTriangle(const code::Math::Vector3D& a,
                                  const code::Math::Vector3D& b,
                                  const code::Math::Vector3D& c,
                                  const code::Math::Vector3D& normal,
                                  const code::Math::Vector3D& start,
                                  const code::Math::Vector3D& direction,
                                  bool infinite,
                                  code::Math::QInt32& parameter,
                                  code::Math::Vector3D& collisionPoint);
    static bool intersectQuad(const code::Math::Vector3D& a,
                              const code::Math::Vector3D& b,
                              const code::Math::Vector3D& c,
                              const code::Math::Vector3D& d,
                              const code::Math::Vector3D& normal,
                              const code::Math::Vector3D& start,
                              const code::Math::Vector3D& direction,
                              bool infinite,
                              code::Math::QInt32& parameter,
                              code::Math::Vector3D& collisionPoint);

    static code::Math::QInt64 isRayOnTriangle(
        const code::Math::Vector3D& a,
        const code::Math::Vector3D& b,
        const code::Math::Vector3D& c,
        const code::Math::Vector3D& normal,
        const code::Math::Vector3D& start,
        const code::Math::Vector3D& direction);
    static code::Math::QInt64 isRayOnQuad(
        const code::Math::Vector3D& a,
        const code::Math::Vector3D& b,
        const code::Math::Vector3D& c,
        const code::Math::Vector3D& d,
        const code::Math::Vector3D& normal,
        const code::Math::Vector3D& start,
        const code::Math::Vector3D& direction);
};

} // namespace Collision
} // namespace code

#endif // QUANTUM_RAY_CAST_H
