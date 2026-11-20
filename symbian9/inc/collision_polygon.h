#ifndef QUANTUM_COLLISION_POLYGON_H
#define QUANTUM_COLLISION_POLYGON_H

#include "quantum_types.h"
#include "vector3d.h"

namespace code {
namespace Collision {

/*
 * A triangle or quad used by the portable collision kernels. Each vertex is
 * referenced independently, so indexed meshes can share one position pool
 * without copying coordinates into per-polygon arrays.
 */
struct CollisionPolygon {
    const code::Math::Vector3D* vertexPointers[4];
    code::Math::QInt32 vertexCount;
    code::Math::Vector3D normal;
    bool hasMaterial;
    bool collidable;
    bool castsShadow;
    const void* identity;

    CollisionPolygon()
        : vertexCount(0), normal(), hasMaterial(false), collidable(true),
          castsShadow(true), identity(0)
    {
        vertexPointers[0] = 0;
        vertexPointers[1] = 0;
        vertexPointers[2] = 0;
        vertexPointers[3] = 0;
    }

    void setVertexPointers(const code::Math::Vector3D* first,
                           const code::Math::Vector3D* second,
                           const code::Math::Vector3D* third,
                           const code::Math::Vector3D* fourth,
                           code::Math::QInt32 count)
    {
        vertexCount = count;
        vertexPointers[0] = first;
        vertexPointers[1] = second;
        vertexPointers[2] = third;
        vertexPointers[3] = fourth;
    }

    void setContiguousVertices(const code::Math::Vector3D* values,
                               code::Math::QInt32 count)
    {
        if (values == 0 || (count != 3 && count != 4)) {
            setVertexPointers(0, 0, 0, 0, count);
            return;
        }
        setVertexPointers(&values[0], &values[1], &values[2],
                          count == 4 ? &values[3] : 0, count);
    }

    const code::Math::Vector3D* vertexPointerAt(
        code::Math::QInt32 index) const
    {
        return vertexPointers[index];
    }

    const code::Math::Vector3D& vertexAt(code::Math::QInt32 index) const
    {
        return *vertexPointers[index];
    }

    bool hasValidVertices() const
    {
        if (vertexCount != 3 && vertexCount != 4) {
            return false;
        }
        code::Math::QInt32 i;
        for (i = 0; i < vertexCount; ++i) {
            if (vertexPointers[i] == 0) {
                return false;
            }
        }
        return true;
    }
};

} // namespace Collision
} // namespace code

#endif // QUANTUM_COLLISION_POLYGON_H
