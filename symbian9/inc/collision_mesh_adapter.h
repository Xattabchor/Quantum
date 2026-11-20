#ifndef QUANTUM_COLLISION_MESH_ADAPTER_H
#define QUANTUM_COLLISION_MESH_ADAPTER_H

#include "collision_mesh_view.h"

namespace code {
namespace Rendering {
struct SoftwareMeshView;
}
namespace Collision {

/* Indexed collision polygon in the renderer-independent mesh data model. */
struct CollisionMeshPolygonData {
    code::Math::QInt32 vertexIndices[4];
    code::Math::QInt32 vertexCount;
    code::Math::Vector3D normal;
    code::Math::QInt32 materialIndex;
    const void* identity;

    CollisionMeshPolygonData()
        : vertexCount(0), normal(), materialIndex(-1), identity(0)
    {
        vertexIndices[0] = 0;
        vertexIndices[1] = 0;
        vertexIndices[2] = 0;
        vertexIndices[3] = 0;
    }
};

/* Collision-relevant fields copied from a mesh material/texture. */
struct CollisionMaterialFlags {
    bool collidable;
    bool castsShadow;

    CollisionMaterialFlags()
        : collidable(true), castsShadow(true)
    {
    }

    CollisionMaterialFlags(bool canCollide, bool castsShadows)
        : collidable(canCollide), castsShadow(castsShadows)
    {
    }
};

/*
 * Non-owning data-only collision mesh: a shared position pool, indexed
 * triangle/quad records, and the collision flags for each material.
 */
struct CollisionMeshDataView {
    const code::Math::Vector3D* vertices;
    code::Math::QInt32 vertexCount;
    const CollisionMeshPolygonData* polygons;
    code::Math::QInt32 polygonCount;
    const CollisionMaterialFlags* materials;
    code::Math::QInt32 materialCount;

    CollisionMeshDataView()
        : vertices(0), vertexCount(0), polygons(0), polygonCount(0),
          materials(0), materialCount(0)
    {
    }

    CollisionMeshDataView(const code::Math::Vector3D* vertexData,
                          code::Math::QInt32 vertexDataCount,
                          const CollisionMeshPolygonData* polygonData,
                          code::Math::QInt32 polygonDataCount,
                          const CollisionMaterialFlags* materialData,
                          code::Math::QInt32 materialDataCount)
        : vertices(vertexData), vertexCount(vertexDataCount),
          polygons(polygonData), polygonCount(polygonDataCount),
          materials(materialData), materialCount(materialDataCount)
    {
    }
};

class CollisionMeshAdapter {
public:
    /*
     * Build a non-owning CollisionMeshView in caller-provided descriptor
     * storage. The adapter validates all counts, indices, material references,
     * and capacity before publishing the output view. It copies descriptors
     * and material flags, but neither allocates memory nor copies coordinates:
     * output vertices point into the source mesh's shared vertex pool.
     *
     * materialIndex == -1 means no material and uses the original collision
     * defaults (collidable and castsShadow). Other indices must refer to the
     * source material table. Zero-vertex records are skipped. Source vertex
     * arrays and outputPolygons must remain valid for as long as the output
     * view is used; any supplied identity pointers must also remain valid if
     * callers compare them with Ray::originalPolygon. The output view is
     * cleared on invalid input or insufficient capacity. Returns true for
     * valid input, including a valid empty mesh.
     */
    static bool build(const CollisionMeshDataView& source,
                      CollisionPolygon* outputPolygons,
                      code::Math::QInt32 outputCapacity,
                      CollisionMeshView& outputView);

    /*
     * Adapt indexed software-renderer polygons directly without allocating a
     * CollisionMeshPolygonData copy. The separate collision material table
     * has the same semantics as build(CollisionMeshDataView): materialIndex
     * -1 uses the default collidable/shadow-casting flags.
     */
    static bool build(const code::Rendering::SoftwareMeshView& source,
                      const CollisionMaterialFlags* materials,
                      code::Math::QInt32 materialCount,
                      CollisionPolygon* outputPolygons,
                      code::Math::QInt32 outputCapacity,
                      CollisionMeshView& outputView);
};

} // namespace Collision
} // namespace code

#endif // QUANTUM_COLLISION_MESH_ADAPTER_H
