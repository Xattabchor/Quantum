#ifndef QUANTUM_COLLISION_HEIGHT_COMPUTER_H
#define QUANTUM_COLLISION_HEIGHT_COMPUTER_H

#include "collision_mesh_view.h"
#include "vector3d.h"

namespace code {
namespace Collision {

/* Mutable floor-query state; it can be reused across several mesh views. */
struct CollisionHeightState {
    code::Math::Vector3D position;
    code::Math::QInt32 height;
    const void* polygonIdentity;
    code::Math::QInt32 centerX;
    code::Math::QInt32 centerY;
    code::Math::QInt32 centerZ;
    code::Math::QInt32 normalY;
    bool underRoof;

    CollisionHeightState();

    /* Clear the previous result and establish the query point. */
    void reset(code::Math::QInt32 x,
               code::Math::QInt32 y,
               code::Math::QInt32 z);

    bool hasFloor() const;
    bool isOnFloor() const;
};

/*
 * Data-only port of the room floor/ceiling query. computeHeight() may be
 * called repeatedly on different mesh views; it keeps the highest collidable
 * polygon below position.y and accumulates underRoof just like the Java House
 * walking all rooms for one query. Dynamic mesh transforms and room chunk/AABB
 * partitioning remain responsibilities of the caller.
 */
class CollisionHeightComputer {
public:
    static bool isPointAABBCollision(code::Math::QInt32 x,
                                     code::Math::QInt32 z,
                                     code::Math::QInt32 minX,
                                     code::Math::QInt32 maxX,
                                     code::Math::QInt32 minZ,
                                     code::Math::QInt32 maxZ);

    static void computeHeight(const CollisionMeshView& mesh,
                              CollisionHeightState& state);
};

} // namespace Collision
} // namespace code

#endif // QUANTUM_COLLISION_HEIGHT_COMPUTER_H
