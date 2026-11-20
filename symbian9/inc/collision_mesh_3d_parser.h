#ifndef QUANTUM_COLLISION_MESH_3D_PARSER_H
#define QUANTUM_COLLISION_MESH_3D_PARSER_H

#include "collision_mesh_adapter.h"

namespace code {
namespace Collision {

/* Required caller-owned storage for one flattened .3d collision mesh. */
struct CollisionMesh3DRequirements {
    code::Math::QInt32 meshCount;
    code::Math::QInt32 vertexCount;
    code::Math::QInt32 polygonCount;
    code::Math::QInt32 materialCount;

    CollisionMesh3DRequirements()
        : meshCount(0), vertexCount(0), polygonCount(0), materialCount(0)
    {
    }
};

/*
 * Collision-only reader for Quantum's Java DataInputStream .3d format.
 *
 * The parser flattens every mesh record into a shared vertex pool and indexed
 * triangle/quad records. It preserves Java's coordinate scaling, signed-byte
 * material index conversion, sentinel/default behavior, and reversed winding.
 * UV bytes are consumed but not retained. Rendering attributes, file I/O,
 * transforms, and renderer Mesh objects are deliberately outside this API.
 *
 * Both methods validate the complete big-endian input, polygon indices and
 * material IDs. parse() writes only into caller-owned buffers and publishes
 * its output view only on success. Input bytes and output buffers must not
 * overlap. Buffers must remain alive and unmoved while that view or any
 * identity pointers copied from its polygon records are used.
 */
class CollisionMesh3DParser {
public:
    /* Validate a complete .3d file and report exact output capacities. */
    static bool measure(const unsigned char* data,
                        code::Math::QInt32 dataLength,
                        CollisionMesh3DRequirements& requirements);

    /*
     * Parse all mesh records. Coordinates use Java's (int)(signedShort *
     * scale) conversion for finite, in-range results; non-finite or
     * out-of-range values are rejected. Material flags are not read from the
     * .3d file; they are provided by the caller and referenced by the
     * polygon material indices in the resulting CollisionMeshDataView.
     */
    static bool parse(const unsigned char* data,
                      code::Math::QInt32 dataLength,
                      float scaleX,
                      float scaleY,
                      float scaleZ,
                      code::Math::Vector3D* outputVertices,
                      code::Math::QInt32 vertexCapacity,
                      CollisionMeshPolygonData* outputPolygons,
                      code::Math::QInt32 polygonCapacity,
                      const CollisionMaterialFlags* materials,
                      code::Math::QInt32 materialCount,
                      CollisionMeshDataView& outputView);
};

} // namespace Collision
} // namespace code

#endif // QUANTUM_COLLISION_MESH_3D_PARSER_H
