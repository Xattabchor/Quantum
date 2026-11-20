#include "sphere_cast.h"

#include "math_utils2.h"
#include "quantum_integer.h"

namespace code {
namespace Collision {

using code::Math::MathUtils2;
using code::Math::QInt32;
using code::Math::Vector3D;
using code::Math::javaDoubleToInt;
using code::Math::javaIntAdd;
using code::Math::javaIntMul;
using code::Math::javaIntSub;
using code::Math::javaShiftRight;

namespace {

const QInt32 KNoCollision = static_cast<QInt32>(2147483647L);

QInt32 minInt(QInt32 left, QInt32 right)
{
    return left < right ? left : right;
}

QInt32 maxInt(QInt32 left, QInt32 right)
{
    return left > right ? left : right;
}

bool polygonOverlapsSphereBounds(const CollisionPolygon& polygon,
                                 const Vector3D& position,
                                 QInt32 radius)
{
    if (!polygon.hasValidVertices()) {
        return false;
    }

    const Vector3D& first = polygon.vertexAt(0);
    QInt32 minX = first.x;
    QInt32 maxX = first.x;
    QInt32 minY = first.y;
    QInt32 maxY = first.y;
    QInt32 minZ = first.z;
    QInt32 maxZ = first.z;

    QInt32 i;
    for (i = 1; i < polygon.vertexCount; ++i) {
        const Vector3D& vertex = polygon.vertexAt(i);
        minX = minInt(minX, vertex.x);
        maxX = maxInt(maxX, vertex.x);
        minY = minInt(minY, vertex.y);
        maxY = maxInt(maxY, vertex.y);
        minZ = minInt(minZ, vertex.z);
        maxZ = maxInt(maxZ, vertex.z);
    }

    const QInt32 sphereMinX = javaIntSub(position.x, radius);
    const QInt32 sphereMaxX = javaIntAdd(position.x, radius);
    const QInt32 sphereMinY = javaIntSub(position.y, radius);
    const QInt32 sphereMaxY = javaIntAdd(position.y, radius);
    const QInt32 sphereMinZ = javaIntSub(position.z, radius);
    const QInt32 sphereMaxZ = javaIntAdd(position.z, radius);

    if (maxX < sphereMinX || minX > sphereMaxX) {
        return false;
    }
    if (maxZ < sphereMinZ || minZ > sphereMaxZ) {
        return false;
    }
    if (maxY < sphereMinY || minY > sphereMaxY) {
        return false;
    }
    return true;
}

QInt32 distanceToPolygon(const Vector3D* const* vertices, QInt32 count,
                         const Vector3D& normal,
                         const Vector3D& center, QInt32 radius)
{
    if (vertices == 0 || (count != 3 && count != 4)) {
        return KNoCollision;
    }
    QInt32 i;
    for (i = 0; i < count; ++i) {
        if (vertices[i] == 0) {
            return KNoCollision;
        }
    }

    const Vector3D delta(javaIntSub(center.x, vertices[0]->x),
                         javaIntSub(center.y, vertices[0]->y),
                         javaIntSub(center.z, vertices[0]->z));
    const QInt32 planeDistance = javaShiftRight(delta.dot(normal), 12);
    if (planeDistance > radius) {
        return KNoCollision;
    }

    const Vector3D projected(
        javaIntSub(center.x, javaShiftRight(
            javaIntMul(normal.x, planeDistance), 12)),
        javaIntSub(center.y, javaShiftRight(
            javaIntMul(normal.y, planeDistance), 12)),
        javaIntSub(center.z, javaShiftRight(
            javaIntMul(normal.z, planeDistance), 12)));

    bool projectionInside = false;
    if (count == 3) {
        projectionInside = MathUtils2::isPointOnPolygon(
            projected, *vertices[0], *vertices[1], *vertices[2], normal);
    } else {
        projectionInside = MathUtils2::isPointOnPolygon(
            projected, *vertices[0], *vertices[1], *vertices[2], *vertices[3],
            normal);
    }

    if (projectionInside) {
        const QInt32 absoluteDistance = planeDistance < 0
            ? javaIntSub(0, planeDistance) : planeDistance;
        return javaIntSub(radius, absoluteDistance);
    }

    QInt32 minimumDistance = MathUtils2::distanceToLine(
        center, *vertices[0], *vertices[1]);
    QInt32 candidate = MathUtils2::distanceToLine(
        center, *vertices[1], *vertices[2]);
    minimumDistance = minInt(minimumDistance, candidate);

    if (count == 3) {
        candidate = MathUtils2::distanceToLine(
            center, *vertices[2], *vertices[0]);
        minimumDistance = minInt(minimumDistance, candidate);
    } else {
        candidate = MathUtils2::distanceToLine(
            center, *vertices[2], *vertices[3]);
        minimumDistance = minInt(minimumDistance, candidate);
        candidate = MathUtils2::distanceToLine(
            center, *vertices[3], *vertices[0]);
        minimumDistance = minInt(minimumDistance, candidate);
    }

    if (minimumDistance <= javaIntMul(radius, radius)) {
        const float inverseLength = MathUtils2::invSqrt(
            static_cast<float>(minimumDistance));
        const QInt32 distance = javaDoubleToInt(
            static_cast<double>(1.0f / inverseLength));
        return javaIntSub(radius, distance);
    }
    return KNoCollision;
}

void moveOutOfPolygon(Vector3D& position, const Vector3D& normal,
                     QInt32 penetration)
{
    const QInt32 moveX = javaShiftRight(
        javaIntMul(javaIntSub(0, normal.x), penetration), 12);
    const QInt32 moveY = javaShiftRight(
        javaIntMul(javaIntSub(0, normal.y), penetration), 12);
    const QInt32 moveZ = javaShiftRight(
        javaIntMul(javaIntSub(0, normal.z), penetration), 12);
    position.add(moveX, moveY, moveZ);
}

} // anonymous namespace

bool SphereCast::isSphereAABBCollision(const Vector3D& position,
                                       QInt32 radius,
                                       QInt32 minX, QInt32 maxX,
                                       QInt32 minZ, QInt32 maxZ)
{
    return !(javaIntAdd(position.x, radius) < minX ||
             javaIntAdd(position.z, radius) < minZ ||
             javaIntSub(position.x, radius) > maxX ||
             javaIntSub(position.z, radius) > maxZ);
}

QInt32 SphereCast::distanceToTriangle(const Vector3D& a,
                                      const Vector3D& b,
                                      const Vector3D& c,
                                      const Vector3D& normal,
                                      const Vector3D& center,
                                      QInt32 radius)
{
    const Vector3D* vertices[4] = { &a, &b, &c, 0 };
    return distanceToPolygon(vertices, 3, normal, center, radius);
}

QInt32 SphereCast::distanceToQuad(const Vector3D& a,
                                  const Vector3D& b,
                                  const Vector3D& c,
                                  const Vector3D& d,
                                  const Vector3D& normal,
                                  const Vector3D& center,
                                  QInt32 radius)
{
    const Vector3D* vertices[4] = { &a, &b, &c, &d };
    return distanceToPolygon(vertices, 4, normal, center, radius);
}

bool SphereCast::cast(const CollisionPolygon* polygons,
                      QInt32 polygonCount,
                      Vector3D& position,
                      QInt32 radius,
                      bool recalculateNormals)
{
    if (polygons == 0 || polygonCount <= 0) {
        return false;
    }

    bool collided = false;
    QInt32 i;
    for (i = 0; i < polygonCount; ++i) {
        const CollisionPolygon& polygon = polygons[i];
        if (!polygon.hasValidVertices()) {
            continue;
        }
        if (!polygonOverlapsSphereBounds(polygon, position, radius)) {
            continue;
        }
        if (polygon.hasMaterial && !polygon.collidable) {
            continue;
        }

        Vector3D normal(polygon.normal);
        if (recalculateNormals) {
            if (polygon.vertexCount == 3) {
                MathUtils2::calcNormal(normal, polygon.vertexAt(0),
                                       polygon.vertexAt(1), polygon.vertexAt(2));
            } else {
                /* Match the original quad recalculation from vertices a, b, d. */
                MathUtils2::calcNormal(normal, polygon.vertexAt(0),
                                       polygon.vertexAt(1), polygon.vertexAt(3));
            }
        }

        const Vector3D* vertices[4] = {
            polygon.vertexPointerAt(0), polygon.vertexPointerAt(1),
            polygon.vertexPointerAt(2),
            polygon.vertexCount == 4 ? polygon.vertexPointerAt(3) : 0
        };
        const QInt32 penetration = distanceToPolygon(
            vertices, polygon.vertexCount, normal, position, radius);
        if (penetration != KNoCollision && penetration > 0) {
            moveOutOfPolygon(position, normal, penetration);
            collided = true;
        }
    }
    return collided;
}

bool SphereCast::cast(const CollisionMeshView& mesh,
                      Vector3D& position,
                      QInt32 radius,
                      bool recalculateNormals)
{
    return cast(mesh.polygons, mesh.polygonCount, position, radius,
                recalculateNormals);
}

} // namespace Collision
} // namespace code
