#ifndef QUANTUM_SPHERE_CAST_H
#define QUANTUM_SPHERE_CAST_H

#include "collision_mesh_view.h"
#include "collision_polygon.h"
#include "vector3d.h"

namespace code {
namespace Collision {

class SphereCast {
public:
    static bool isSphereAABBCollision(const code::Math::Vector3D& position,
                                      code::Math::QInt32 radius,
                                      code::Math::QInt32 minX,
                                      code::Math::QInt32 maxX,
                                      code::Math::QInt32 minZ,
                                      code::Math::QInt32 maxZ);

    static code::Math::QInt32 distanceToTriangle(
        const code::Math::Vector3D& a,
        const code::Math::Vector3D& b,
        const code::Math::Vector3D& c,
        const code::Math::Vector3D& normal,
        const code::Math::Vector3D& center,
        code::Math::QInt32 radius);
    static code::Math::QInt32 distanceToQuad(
        const code::Math::Vector3D& a,
        const code::Math::Vector3D& b,
        const code::Math::Vector3D& c,
        const code::Math::Vector3D& d,
        const code::Math::Vector3D& normal,
        const code::Math::Vector3D& center,
        code::Math::QInt32 radius);

    static bool cast(const CollisionPolygon* polygons,
                     code::Math::QInt32 polygonCount,
                     code::Math::Vector3D& position,
                     code::Math::QInt32 radius,
                     bool recalculateNormals);
    static bool cast(const CollisionMeshView& mesh,
                     code::Math::Vector3D& position,
                     code::Math::QInt32 radius,
                     bool recalculateNormals);
};

} // namespace Collision
} // namespace code

#endif // QUANTUM_SPHERE_CAST_H
