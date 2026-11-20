#ifndef QUANTUM_COLLISION_MESH_VIEW_H
#define QUANTUM_COLLISION_MESH_VIEW_H

#include "collision_polygon.h"

namespace code {
namespace Collision {

/* Non-owning mesh adapter: the source keeps the polygon descriptors alive. */
struct CollisionMeshView {
    const CollisionPolygon* polygons;
    code::Math::QInt32 polygonCount;

    CollisionMeshView()
        : polygons(0), polygonCount(0)
    {
    }

    CollisionMeshView(const CollisionPolygon* polygonData,
                      code::Math::QInt32 count)
        : polygons(polygonData), polygonCount(count)
    {
    }

    bool isValid() const
    {
        return polygons != 0 && polygonCount > 0;
    }
};

} // namespace Collision
} // namespace code

#endif // QUANTUM_COLLISION_MESH_VIEW_H
