#ifndef QUANTUM_COLLISION_HEIGHT_ROOM_COMPUTER_H
#define QUANTUM_COLLISION_HEIGHT_ROOM_COMPUTER_H

#include "collision_height_computer.h"

namespace code {
namespace Collision {

/* X/Z bounds used by Java Room's height-query broad phase. */
struct CollisionHeightXZBounds {
    code::Math::QInt32 minX;
    code::Math::QInt32 maxX;
    code::Math::QInt32 minZ;
    code::Math::QInt32 maxZ;

    CollisionHeightXZBounds();
    CollisionHeightXZBounds(code::Math::QInt32 minimumX,
                            code::Math::QInt32 maximumX,
                            code::Math::QInt32 minimumZ,
                            code::Math::QInt32 maximumZ);
};

/* One non-owning chunk entry, matching Room.mesh[x][z] and Room.bbmesh[x][z]. */
struct CollisionHeightChunkView {
    CollisionMeshView mesh;
    CollisionHeightXZBounds bounds;

    CollisionHeightChunkView();
    CollisionHeightChunkView(const CollisionMeshView& meshView,
                             const CollisionHeightXZBounds& meshBounds);
};

/*
 * Non-owning height-query view of one Java Room. When chunkColumns is non-null,
 * it points to chunkCountX column pointers, each of which addresses an array of
 * chunkCountZ entries. Otherwise fullMesh and bounds are used. All referenced
 * views and arrays must outlive the query.
 */
struct CollisionHeightRoomView {
    const CollisionHeightChunkView* const* chunkColumns;
    code::Math::QInt32 chunkCountX;
    code::Math::QInt32 chunkCountZ;
    code::Math::QInt32 chunkSize;
    CollisionMeshView fullMesh;
    CollisionHeightXZBounds bounds;

    CollisionHeightRoomView();
};

/* Host-testable port of Room.computeHeight()'s room/chunk broad phase. */
class CollisionHeightRoomComputer {
public:
    static void computeHeight(const CollisionHeightRoomView& room,
                              CollisionHeightState& state);

    /*
     * Port of House.computeHeight(): query the active room, then each non-null
     * neighbour, accumulating into the same state. A null activeRoom models
     * part == -1. Dynamic mesh objects are queried separately by the caller.
     */
    static void computeHouseHeight(
        const CollisionHeightRoomView* activeRoom,
        const CollisionHeightRoomView* const* neighbourRooms,
        code::Math::QInt32 neighbourCount,
        CollisionHeightState& state);
};

} // namespace Collision
} // namespace code

#endif // QUANTUM_COLLISION_HEIGHT_ROOM_COMPUTER_H
