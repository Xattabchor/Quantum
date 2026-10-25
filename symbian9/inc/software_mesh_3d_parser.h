#ifndef QUANTUM_SOFTWARE_MESH_3D_PARSER_H
#define QUANTUM_SOFTWARE_MESH_3D_PARSER_H

#include "software_mesh_renderer.h"

namespace code {
namespace Rendering {

/* Exact caller-owned capacities for one Java DataInputStream .3d asset. */
struct SoftwareMesh3DRequirements {
    code::Math::QInt32 meshCount;
    code::Math::QInt32 vertexCount;
    code::Math::QInt32 polygonCount;
    code::Math::QInt32 materialCount;

    SoftwareMesh3DRequirements();
};

/* One Java Mesh section, stored in shared caller-owned arrays. */
struct SoftwareMesh3DPart {
    code::Math::QInt32 vertexOffset;
    code::Math::QInt32 vertexCount;
    code::Math::QInt32 polygonOffset;
    code::Math::QInt32 polygonCount;

    SoftwareMesh3DPart();
};

/*
 * Allocation-free reader for Room.loadMeshes' Java .3d format. It preserves
 * Java float scaling/narrowing, signed-byte material indices, unsigned UVs,
 * triangle-before-quad ordering, reversed polygon winding, and polygon normals.
 * All output belongs to the caller; each part retains its own local vertex
 * indices and can be exposed as a separate SoftwareMeshView.
 */
class SoftwareMesh3DParser {
public:
    static bool measure(const unsigned char* data,
                        code::Math::QInt32 dataLength,
                        SoftwareMesh3DRequirements& requirements);

    static bool parse(const unsigned char* data,
                      code::Math::QInt32 dataLength,
                      float scaleX,
                      float scaleY,
                      float scaleZ,
                      code::Math::Vector3D* outputVertices,
                      code::Math::QInt32 vertexCapacity,
                      SoftwareMeshPolygon* outputPolygons,
                      code::Math::QInt32 polygonCapacity,
                      SoftwareMesh3DPart* outputParts,
                      code::Math::QInt32 partCapacity,
                      SoftwareMesh3DRequirements& requirements);

    /* Apply the grayscale .vla layout used by LightedPolygon3V/4V. */
    static bool applyGrayscaleLightmap(
        const unsigned char* data,
        code::Math::QInt32 dataLength,
        SoftwareMeshPolygon* polygons,
        code::Math::QInt32 polygonCount);

    /*
     * Apply the ColorLightedPolygon .vla layout. If preserveRgb is false, a
     * colored record is converted to Java LightedPolygon brightness by integer
     * channel averaging (the LightMapper fogQ==1 fallback).
     */
    static bool applyColorLightmap(
        const unsigned char* data,
        code::Math::QInt32 dataLength,
        SoftwareMeshPolygon* polygons,
        code::Math::QInt32 polygonCount,
        bool preserveRgb);
};

} // namespace Rendering
} // namespace code

#endif // QUANTUM_SOFTWARE_MESH_3D_PARSER_H
