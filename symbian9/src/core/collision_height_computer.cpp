#include "collision_height_computer.h"

#include "math_utils2.h"
#include "quantum_integer.h"

namespace code {
namespace Collision {

namespace {

using code::Math::QInt32;
using code::Math::Vector3D;

QInt32 average3(QInt32 a, QInt32 b, QInt32 c)
{
    const QInt32 sum = code::Math::javaIntAdd(
        code::Math::javaIntAdd(a, b), c);
    return code::Math::javaIntDiv(sum, 3);
}

QInt32 average4(QInt32 a, QInt32 b, QInt32 c, QInt32 d)
{
    QInt32 sum = code::Math::javaIntAdd(a, b);
    sum = code::Math::javaIntAdd(sum, c);
    sum = code::Math::javaIntAdd(sum, d);
    return code::Math::javaShiftRight(sum, 2);
}

void polygonCenter(const CollisionPolygon& polygon,
                   QInt32& centerX,
                   QInt32& centerY,
                   QInt32& centerZ)
{
    const Vector3D& a = polygon.vertexAt(0);
    const Vector3D& b = polygon.vertexAt(1);
    const Vector3D& c = polygon.vertexAt(2);
    if (polygon.vertexCount == 4) {
        const Vector3D& d = polygon.vertexAt(3);
        centerX = average4(a.x, b.x, c.x, d.x);
        centerY = average4(a.y, b.y, c.y, d.y);
        centerZ = average4(a.z, b.z, c.z, d.z);
    } else {
        centerX = average3(a.x, b.x, c.x);
        centerY = average3(a.y, b.y, c.y);
        centerZ = average3(a.z, b.z, c.z);
    }
}

bool containsXZ(const CollisionPolygon& polygon,
                QInt32 x,
                QInt32 z)
{
    const Vector3D& a = polygon.vertexAt(0);
    const Vector3D& b = polygon.vertexAt(1);
    const Vector3D& c = polygon.vertexAt(2);
    if (polygon.vertexCount == 4) {
        const Vector3D& d = polygon.vertexAt(3);
        return code::Math::MathUtils2::isPointOnPolygon(
            x, z, a.x, a.z, b.x, b.z, c.x, c.z, d.x, d.z,
            polygon.normal.y);
    }
    return code::Math::MathUtils2::isPointOnPolygon(
        x, z, a.x, a.z, b.x, b.z, c.x, c.z, polygon.normal.y);
}

void polygonBoundsXZ(const CollisionPolygon& polygon,
                     QInt32& minX,
                     QInt32& maxX,
                     QInt32& minZ,
                     QInt32& maxZ)
{
    const Vector3D& first = polygon.vertexAt(0);
    minX = maxX = first.x;
    minZ = maxZ = first.z;

    QInt32 i;
    for (i = 1; i < polygon.vertexCount; ++i) {
        const Vector3D& vertex = polygon.vertexAt(i);
        if (vertex.x < minX) minX = vertex.x;
        if (vertex.x > maxX) maxX = vertex.x;
        if (vertex.z < minZ) minZ = vertex.z;
        if (vertex.z > maxZ) maxZ = vertex.z;
    }
}

} // anonymous namespace

CollisionHeightState::CollisionHeightState()
    : position(), height((-2147483647 - 1)), polygonIdentity(0),
      centerX((-2147483647 - 1)), centerY((-2147483647 - 1)),
      centerZ((-2147483647 - 1)), normalY(0), underRoof(false)
{
}

void CollisionHeightState::reset(QInt32 x, QInt32 y, QInt32 z)
{
    position.set(x, y, z);
    height = (-2147483647 - 1);
    polygonIdentity = 0;
    centerX = (-2147483647 - 1);
    centerY = (-2147483647 - 1);
    centerZ = (-2147483647 - 1);
    normalY = 0;
    underRoof = false;
}

bool CollisionHeightState::hasFloor() const
{
    return polygonIdentity != 0;
}

bool CollisionHeightState::isOnFloor() const
{
    return hasFloor() && normalY <= -2048;
}

bool CollisionHeightComputer::isPointAABBCollision(QInt32 x,
                                                   QInt32 z,
                                                   QInt32 minX,
                                                   QInt32 maxX,
                                                   QInt32 minZ,
                                                   QInt32 maxZ)
{
    return !(x < minX || z < minZ || x > maxX || z > maxZ);
}

void CollisionHeightComputer::computeHeight(const CollisionMeshView& mesh,
                                            CollisionHeightState& state)
{
    if (mesh.polygons == 0 || mesh.polygonCount <= 0) {
        return;
    }

    QInt32 i;
    for (i = 0; i < mesh.polygonCount; ++i) {
        const CollisionPolygon& polygon = mesh.polygons[i];
        if (!polygon.hasValidVertices()) {
            continue;
        }

        QInt32 minX = 0;
        QInt32 maxX = 0;
        QInt32 minZ = 0;
        QInt32 maxZ = 0;
        polygonBoundsXZ(polygon, minX, maxX, minZ, maxZ);
        if (!isPointAABBCollision(state.position.x, state.position.z,
                                  minX, maxX, minZ, maxZ)) {
            continue;
        }

        QInt32 centerX = 0;
        QInt32 centerY = 0;
        QInt32 centerZ = 0;
        polygonCenter(polygon, centerX, centerY, centerZ);

        if (polygon.normal.y >= 0) {
            if (polygon.normal.y > 2048 && centerY > state.position.y) {
                state.underRoof = true;
            }
            continue;
        }
        if ((polygon.hasMaterial && !polygon.collidable) ||
            !containsXZ(polygon, state.position.x, state.position.z)) {
            continue;
        }

        const QInt32 polygonY = code::Math::MathUtils2::computePolygonY(
            polygon.vertexAt(0), polygon.normal.x, polygon.normal.y,
            polygon.normal.z, state.position.x, state.position.y,
            state.position.z);
        if (polygonY < state.position.y && polygonY > state.height) {
            state.height = polygonY;
            state.polygonIdentity = polygon.identity != 0
                ? polygon.identity : static_cast<const void*>(&polygon);
            state.centerX = centerX;
            state.centerY = centerY;
            state.centerZ = centerZ;
            state.normalY = polygon.normal.y;
        }
    }
}

} // namespace Collision
} // namespace code
