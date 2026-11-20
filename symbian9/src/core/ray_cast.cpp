#include "ray_cast.h"

#include "math_utils2.h"
#include "quantum_integer.h"

namespace code {
namespace Collision {

using code::Math::MathUtils2;
using code::Math::QInt32;
using code::Math::QInt64;
using code::Math::QUint64;
using code::Math::Vector3D;
using code::Math::javaIntAdd;
using code::Math::javaIntFromLong;
using code::Math::javaIntMul;
using code::Math::javaIntSub;
using code::Math::javaLongDiv;
using code::Math::javaLongFromUnsigned;
using code::Math::javaLongMulInt;
using code::Math::javaLongSub;
using code::Math::javaShiftRight;
using code::Math::javaShiftRightLong;

namespace {

const QInt32 KIntMax = static_cast<QInt32>(2147483647L);

QInt64 longMaxValue()
{
    const QUint64 signBit = static_cast<QUint64>(1) << 63;
    return javaLongFromUnsigned(~signBit);
}

const void* polygonIdentity(const CollisionPolygon& polygon)
{
    return polygon.identity != 0 ? polygon.identity : &polygon;
}

QInt32 minInt(QInt32 left, QInt32 right)
{
    return left < right ? left : right;
}

QInt32 maxInt(QInt32 left, QInt32 right)
{
    return left > right ? left : right;
}

bool segmentBoundsOverlap(const CollisionPolygon& polygon, const Ray& ray)
{
    const QInt32 count = polygon.vertexCount;
    const QInt32 endX = javaIntAdd(ray.start.x, ray.dir.x);
    const QInt32 endY = javaIntAdd(ray.start.y, ray.dir.y);
    const QInt32 endZ = javaIntAdd(ray.start.z, ray.dir.z);

    QInt32 segmentMinX = minInt(ray.start.x, endX);
    QInt32 segmentMinY = minInt(ray.start.y, endY);
    QInt32 segmentMinZ = minInt(ray.start.z, endZ);
    QInt32 segmentMaxX = maxInt(ray.start.x, endX);
    QInt32 segmentMaxY = maxInt(ray.start.y, endY);
    QInt32 segmentMaxZ = maxInt(ray.start.z, endZ);

    const Vector3D& first = polygon.vertexAt(0);
    QInt32 polygonMinX = first.x;
    QInt32 polygonMinY = first.y;
    QInt32 polygonMinZ = first.z;
    QInt32 polygonMaxX = first.x;
    QInt32 polygonMaxY = first.y;
    QInt32 polygonMaxZ = first.z;

    QInt32 i;
    for (i = 1; i < count; ++i) {
        const Vector3D& vertex = polygon.vertexAt(i);
        polygonMinX = minInt(polygonMinX, vertex.x);
        polygonMinY = minInt(polygonMinY, vertex.y);
        polygonMinZ = minInt(polygonMinZ, vertex.z);
        polygonMaxX = maxInt(polygonMaxX, vertex.x);
        polygonMaxY = maxInt(polygonMaxY, vertex.y);
        polygonMaxZ = maxInt(polygonMaxZ, vertex.z);
    }

    if (polygonMaxX < segmentMinX || polygonMinX > segmentMaxX) {
        return false;
    }
    if (polygonMaxZ < segmentMinZ || polygonMinZ > segmentMaxZ) {
        return false;
    }
    if (polygonMaxY < segmentMinY || polygonMinY > segmentMaxY) {
        return false;
    }
    return true;
}

bool intersectPolygon(const Vector3D* const* vertices, QInt32 count,
                      const Vector3D& normal,
                      const Vector3D& start, const Vector3D& direction,
                      bool infinite, QInt32& parameter,
                      Vector3D& collisionPoint)
{
    if (vertices == 0 || (count != 3 && count != 4)) {
        return false;
    }
    QInt32 i;
    for (i = 0; i < count; ++i) {
        if (vertices[i] == 0) {
            return false;
        }
    }

    const Vector3D offset(javaIntSub(start.x, vertices[0]->x),
                          javaIntSub(start.y, vertices[0]->y),
                          javaIntSub(start.z, vertices[0]->z));
    const QInt32 denominator = javaIntFromLong(
        javaShiftRightLong(direction.dotLong(normal), 12));
    if (denominator <= 0) {
        return false;
    }

    const QInt64 numerator = javaLongSub(0, offset.dotLong(normal));
    const QInt32 t = javaIntFromLong(javaLongDiv(numerator, denominator));
    if (t < -1 || (t > 4096 && !infinite)) {
        return false;
    }

    const Vector3D hit(
        javaIntAdd(start.x, javaShiftRight(
            javaIntMul(direction.x, t), 12)),
        javaIntAdd(start.y, javaShiftRight(
            javaIntMul(direction.y, t), 12)),
        javaIntAdd(start.z, javaShiftRight(
            javaIntMul(direction.z, t), 12)));

    bool inside = false;
    if (count == 3) {
        inside = MathUtils2::isPointOnPolygon(
            hit, *vertices[0], *vertices[1], *vertices[2], normal);
    } else {
        inside = MathUtils2::isPointOnPolygon(
            hit, *vertices[0], *vertices[1], *vertices[2], *vertices[3],
            normal);
    }
    if (!inside) {
        return false;
    }

    parameter = t;
    collisionPoint.set(hit);
    return true;
}

void castInternal(const CollisionPolygon* polygons, QInt32 polygonCount,
                  Ray& ray, bool recalculateNormals, bool useBounds)
{
    if (polygons == 0 || polygonCount <= 0) {
        return;
    }

    const QInt32 directionLength = ray.dir.length();
    QInt32 i;
    for (i = 0; i < polygonCount; ++i) {
        const CollisionPolygon& polygon = polygons[i];
        if (!polygon.hasValidVertices()) {
            continue;
        }
        if (useBounds && !ray.infinity &&
            !segmentBoundsOverlap(polygon, ray)) {
            continue;
        }

        if (polygon.hasMaterial) {
            if (ray.ignoreNonShadowed && !polygon.castsShadow) {
                continue;
            }
            if (ray.onlyCollidable && !polygon.collidable) {
                continue;
            }
        }

        const void* identity = polygonIdentity(polygon);
        if (ray.originalPolygon == identity) {
            continue;
        }

        Vector3D normal(polygon.normal);
        if (recalculateNormals) {
            if (polygon.vertexCount == 3) {
                MathUtils2::calcNormal(normal, polygon.vertexAt(0),
                                       polygon.vertexAt(1), polygon.vertexAt(2));
            } else {
                /* Match the Java quad path, which recomputes from a, b, d. */
                MathUtils2::calcNormal(normal, polygon.vertexAt(0),
                                       polygon.vertexAt(1), polygon.vertexAt(3));
            }
        }

        const Vector3D* vertices[4] = {
            polygon.vertexPointerAt(0), polygon.vertexPointerAt(1),
            polygon.vertexPointerAt(2),
            polygon.vertexCount == 4 ? polygon.vertexPointerAt(3) : 0
        };
        QInt32 parameter = 0;
        Vector3D collisionPoint;
        if (!intersectPolygon(vertices, polygon.vertexCount, normal,
                              ray.start, ray.dir, ray.infinity,
                              parameter, collisionPoint) || parameter < 0) {
            continue;
        }

        QInt64 distance = javaShiftRightLong(
            javaLongMulInt(directionLength, parameter), 12);
        if (distance > KIntMax) {
            distance = KIntMax;
        }
        if (distance < ray.distance) {
            ray.collision = true;
            ray.distance = javaIntFromLong(distance);
            ray.collisionPoint.set(collisionPoint);
            ray.triangle = identity;
            if (!ray.findNearest) {
                return;
            }
        }
    }
}

QInt64 isRayOnPolygon(const Vector3D* const* vertices, QInt32 count,
                      const Vector3D& normal,
                      const Vector3D& start, const Vector3D& direction)
{
    if (vertices == 0 || (count != 3 && count != 4)) {
        return longMaxValue();
    }

    QInt32 parameter = 0;
    Vector3D collisionPoint;
    if (!intersectPolygon(vertices, count, normal, start, direction, true,
                          parameter, collisionPoint)) {
        return longMaxValue();
    }

    /* Java performs this product and shift as int, then widens to long. */
    const QInt32 distance = javaShiftRight(
        javaIntMul(parameter, direction.length()), 12);
    return static_cast<QInt64>(distance);
}

} // anonymous namespace

Ray::Ray()
    : collision(false), distance(KIntMax), triangle(0), originalPolygon(0),
      doubleSide(false), findNearest(false), infinity(false),
      ignoreNonShadowed(false), onlyCollidable(true), numRoom(-1),
      collisionPoint(), start(), dir()
{
    reset();
}

void Ray::reset()
{
    collision = false;
    distance = KIntMax;
    originalPolygon = 0;
}

void RayCast::cast(const CollisionPolygon* polygons,
                   QInt32 polygonCount,
                   Ray& ray,
                   bool recalculateNormals)
{
    castInternal(polygons, polygonCount, ray, recalculateNormals, true);
}

void RayCast::cast(const CollisionMeshView& mesh,
                   Ray& ray,
                   bool recalculateNormals)
{
    cast(mesh.polygons, mesh.polygonCount, ray, recalculateNormals);
}

void RayCast::superFastCast(const CollisionPolygon* polygons,
                            QInt32 polygonCount,
                            Ray& ray)
{
    castInternal(polygons, polygonCount, ray, false, false);
}

void RayCast::superFastCast(const CollisionMeshView& mesh, Ray& ray)
{
    superFastCast(mesh.polygons, mesh.polygonCount, ray);
}

bool RayCast::intersectTriangle(const Vector3D& a,
                                const Vector3D& b,
                                const Vector3D& c,
                                const Vector3D& normal,
                                const Vector3D& start,
                                const Vector3D& direction,
                                bool infinite,
                                QInt32& parameter,
                                Vector3D& collisionPoint)
{
    const Vector3D* vertices[4] = { &a, &b, &c, 0 };
    return intersectPolygon(vertices, 3, normal, start, direction, infinite,
                            parameter, collisionPoint);
}

bool RayCast::intersectQuad(const Vector3D& a,
                            const Vector3D& b,
                            const Vector3D& c,
                            const Vector3D& d,
                            const Vector3D& normal,
                            const Vector3D& start,
                            const Vector3D& direction,
                            bool infinite,
                            QInt32& parameter,
                            Vector3D& collisionPoint)
{
    const Vector3D* vertices[4] = { &a, &b, &c, &d };
    return intersectPolygon(vertices, 4, normal, start, direction, infinite,
                            parameter, collisionPoint);
}

QInt64 RayCast::isRayOnTriangle(const Vector3D& a,
                               const Vector3D& b,
                               const Vector3D& c,
                               const Vector3D& normal,
                               const Vector3D& start,
                               const Vector3D& direction)
{
    const Vector3D* vertices[4] = { &a, &b, &c, 0 };
    return isRayOnPolygon(vertices, 3, normal, start, direction);
}

QInt64 RayCast::isRayOnQuad(const Vector3D& a,
                            const Vector3D& b,
                            const Vector3D& c,
                            const Vector3D& d,
                            const Vector3D& normal,
                            const Vector3D& start,
                            const Vector3D& direction)
{
    const Vector3D* vertices[4] = { &a, &b, &c, &d };
    return isRayOnPolygon(vertices, 4, normal, start, direction);
}

} // namespace Collision
} // namespace code
