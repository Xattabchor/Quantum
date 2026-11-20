#include "collision_height_room_computer.h"

#include "quantum_integer.h"

namespace code {
namespace Collision {

using code::Math::QInt32;

namespace {

const QInt32 KRoomHeightBoundsMargin = 500;

bool isInsideExpandedBounds(QInt32 x,
                            QInt32 z,
                            const CollisionHeightXZBounds& bounds)
{
    return CollisionHeightComputer::isPointAABBCollision(
        x, z,
        code::Math::javaIntSub(bounds.minX, KRoomHeightBoundsMargin),
        code::Math::javaIntAdd(bounds.maxX, KRoomHeightBoundsMargin),
        code::Math::javaIntSub(bounds.minZ, KRoomHeightBoundsMargin),
        code::Math::javaIntAdd(bounds.maxZ, KRoomHeightBoundsMargin));
}

} // anonymous namespace

CollisionHeightXZBounds::CollisionHeightXZBounds()
    : minX(0), maxX(0), minZ(0), maxZ(0)
{
}

CollisionHeightXZBounds::CollisionHeightXZBounds(QInt32 minimumX,
                                                 QInt32 maximumX,
                                                 QInt32 minimumZ,
                                                 QInt32 maximumZ)
    : minX(minimumX), maxX(maximumX), minZ(minimumZ), maxZ(maximumZ)
{
}

CollisionHeightChunkView::CollisionHeightChunkView()
    : mesh(), bounds()
{
}

CollisionHeightChunkView::CollisionHeightChunkView(
    const CollisionMeshView& meshView,
    const CollisionHeightXZBounds& meshBounds)
    : mesh(meshView), bounds(meshBounds)
{
}

CollisionHeightRoomView::CollisionHeightRoomView()
    : chunkColumns(0), chunkCountX(0), chunkCountZ(0), chunkSize(0),
      fullMesh(), bounds()
{
}

void CollisionHeightRoomComputer::computeHeight(
    const CollisionHeightRoomView& room,
    CollisionHeightState& state)
{
    if (room.chunkColumns != 0) {
        if (room.chunkCountX <= 0 || room.chunkCountZ <= 0 ||
            room.chunkSize <= 0) {
            return;
        }

        const QInt32 chunkX = code::Math::javaIntDiv(
            code::Math::javaIntSub(state.position.x, room.bounds.minX),
            room.chunkSize);
        const QInt32 chunkZ = code::Math::javaIntDiv(
            code::Math::javaIntSub(state.position.z, room.bounds.minZ),
            room.chunkSize);
        if (chunkX < 0 || chunkX >= room.chunkCountX ||
            chunkZ < 0 || chunkZ >= room.chunkCountZ) {
            return;
        }

        const CollisionHeightChunkView* column = room.chunkColumns[chunkX];
        if (column == 0) {
            return;
        }
        const CollisionHeightChunkView& chunk = column[chunkZ];
        if (!isInsideExpandedBounds(state.position.x, state.position.z,
                                    chunk.bounds)) {
            return;
        }
        CollisionHeightComputer::computeHeight(chunk.mesh, state);
        return;
    }

    if (!isInsideExpandedBounds(state.position.x, state.position.z,
                                room.bounds)) {
        return;
    }
    CollisionHeightComputer::computeHeight(room.fullMesh, state);
}

void CollisionHeightRoomComputer::computeHouseHeight(
    const CollisionHeightRoomView* activeRoom,
    const CollisionHeightRoomView* const* neighbourRooms,
    QInt32 neighbourCount,
    CollisionHeightState& state)
{
    if (activeRoom == 0) {
        return;
    }
    computeHeight(*activeRoom, state);

    if (neighbourRooms == 0 || neighbourCount <= 0) {
        return;
    }
    QInt32 i;
    for (i = 0; i < neighbourCount; ++i) {
        if (neighbourRooms[i] != 0) {
            computeHeight(*neighbourRooms[i], state);
        }
    }
}

} // namespace Collision
} // namespace code
