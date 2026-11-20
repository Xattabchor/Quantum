#include "collision_mesh_adapter.h"
#include "software_mesh_renderer.h"

namespace code {
namespace Collision {

using code::Math::QInt32;
using code::Math::Vector3D;

bool CollisionMeshAdapter::build(const CollisionMeshDataView& source,
                                 CollisionPolygon* outputPolygons,
                                 QInt32 outputCapacity,
                                 CollisionMeshView& outputView)
{
    outputView = CollisionMeshView();

    if (source.vertexCount < 0 || source.polygonCount < 0 ||
        source.materialCount < 0 || outputCapacity < 0) {
        return false;
    }
    if (source.vertexCount > 0 && source.vertices == 0) {
        return false;
    }
    if (source.polygonCount > 0 && source.polygons == 0) {
        return false;
    }
    if (source.materialCount > 0 && source.materials == 0) {
        return false;
    }
    if (outputCapacity > 0 && outputPolygons == 0) {
        return false;
    }

    QInt32 outputCount = 0;
    QInt32 i;
    for (i = 0; i < source.polygonCount; ++i) {
        const CollisionMeshPolygonData& polygon = source.polygons[i];

        /* Non-polygon render objects are ignored by the Java collision casts. */
        if (polygon.vertexCount == 0) {
            continue;
        }
        if (polygon.vertexCount != 3 && polygon.vertexCount != 4) {
            return false;
        }
        if (polygon.materialIndex < -1 ||
            (polygon.materialIndex >= 0 &&
             polygon.materialIndex >= source.materialCount)) {
            return false;
        }

        QInt32 vertex;
        for (vertex = 0; vertex < polygon.vertexCount; ++vertex) {
            const QInt32 index = polygon.vertexIndices[vertex];
            if (source.vertices == 0 || index < 0 ||
                index >= source.vertexCount) {
                return false;
            }
        }
        ++outputCount;
    }

    if (outputCount > outputCapacity) {
        return false;
    }

    QInt32 outputIndex = 0;
    for (i = 0; i < source.polygonCount; ++i) {
        const CollisionMeshPolygonData& sourcePolygon = source.polygons[i];
        if (sourcePolygon.vertexCount == 0) {
            continue;
        }

        const Vector3D* vertex0 =
            &source.vertices[sourcePolygon.vertexIndices[0]];
        const Vector3D* vertex1 =
            &source.vertices[sourcePolygon.vertexIndices[1]];
        const Vector3D* vertex2 =
            &source.vertices[sourcePolygon.vertexIndices[2]];
        const Vector3D* vertex3 = sourcePolygon.vertexCount == 4
            ? &source.vertices[sourcePolygon.vertexIndices[3]] : 0;

        CollisionPolygon& polygon = outputPolygons[outputIndex++];
        polygon.setVertexPointers(vertex0, vertex1, vertex2, vertex3,
                                  sourcePolygon.vertexCount);
        polygon.normal.set(sourcePolygon.normal);
        polygon.hasMaterial = sourcePolygon.materialIndex >= 0;
        polygon.collidable = true;
        polygon.castsShadow = true;
        polygon.identity = sourcePolygon.identity;

        if (polygon.hasMaterial) {
            const CollisionMaterialFlags& flags =
                source.materials[sourcePolygon.materialIndex];
            polygon.collidable = flags.collidable;
            polygon.castsShadow = flags.castsShadow;
        }
    }

    if (outputCount > 0) {
        outputView = CollisionMeshView(outputPolygons, outputCount);
    }
    return true;
}

bool CollisionMeshAdapter::build(
    const code::Rendering::SoftwareMeshView& source,
    const CollisionMaterialFlags* materials,
    QInt32 materialCount,
    CollisionPolygon* outputPolygons,
    QInt32 outputCapacity,
    CollisionMeshView& outputView)
{
    outputView = CollisionMeshView();

    if (source.vertexCount < 0 || source.polygonCount < 0 ||
        materialCount < 0 || outputCapacity < 0) {
        return false;
    }
    if (source.vertexCount > 0 && source.vertices == 0) return false;
    if (source.polygonCount > 0 && source.polygons == 0) return false;
    if (materialCount > 0 && materials == 0) return false;
    if (outputCapacity > 0 && outputPolygons == 0) return false;

    QInt32 outputCount = 0;
    QInt32 i;
    for (i = 0; i < source.polygonCount; ++i) {
        const code::Rendering::SoftwareMeshPolygon& polygon =
            source.polygons[i];
        if (polygon.vertexCount == 0) continue;
        if (polygon.vertexCount != 3 && polygon.vertexCount != 4) {
            return false;
        }
        if (polygon.textureIndex < -1 ||
            (polygon.textureIndex >= 0 &&
             polygon.textureIndex >= materialCount)) {
            return false;
        }

        QInt32 vertex;
        for (vertex = 0; vertex < polygon.vertexCount; ++vertex) {
            const QInt32 index = polygon.vertexIndices[vertex];
            if (source.vertices == 0 || index < 0 ||
                index >= source.vertexCount) {
                return false;
            }
        }
        ++outputCount;
    }

    if (outputCount > outputCapacity) return false;

    QInt32 outputIndex = 0;
    for (i = 0; i < source.polygonCount; ++i) {
        const code::Rendering::SoftwareMeshPolygon& sourcePolygon =
            source.polygons[i];
        if (sourcePolygon.vertexCount == 0) continue;

        const Vector3D* vertex0 =
            &source.vertices[sourcePolygon.vertexIndices[0]];
        const Vector3D* vertex1 =
            &source.vertices[sourcePolygon.vertexIndices[1]];
        const Vector3D* vertex2 =
            &source.vertices[sourcePolygon.vertexIndices[2]];
        const Vector3D* vertex3 = sourcePolygon.vertexCount == 4
            ? &source.vertices[sourcePolygon.vertexIndices[3]] : 0;

        CollisionPolygon& polygon = outputPolygons[outputIndex++];
        polygon.setVertexPointers(vertex0, vertex1, vertex2, vertex3,
                                  sourcePolygon.vertexCount);
        polygon.normal.set(sourcePolygon.normalX, sourcePolygon.normalY,
                           sourcePolygon.normalZ);
        polygon.hasMaterial = sourcePolygon.textureIndex >= 0;
        polygon.collidable = true;
        polygon.castsShadow = true;
        polygon.identity = &sourcePolygon;
        if (polygon.hasMaterial) {
            const CollisionMaterialFlags& flags =
                materials[sourcePolygon.textureIndex];
            polygon.collidable = flags.collidable;
            polygon.castsShadow = flags.castsShadow;
        }
    }

    if (outputCount > 0) {
        outputView = CollisionMeshView(outputPolygons, outputCount);
    }
    return true;
}

} // namespace Collision
} // namespace code
