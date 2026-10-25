#ifndef QUANTUM_SOFTWARE_VERTEX_PIPELINE_H
#define QUANTUM_SOFTWARE_VERTEX_PIPELINE_H

#include "matrix.h"
#include "quantum_types.h"

namespace code {
namespace Rendering {

/* Camera-space or projected vertex data with Java-compatible integer fields. */
struct SoftwareRenderVertex {
    code::Math::QInt32 x;
    code::Math::QInt32 y;
    code::Math::QInt32 z;
    code::Math::QInt32 red;
    code::Math::QInt32 green;
    code::Math::QInt32 blue;
    code::Math::QInt32 u;
    code::Math::QInt32 v;

    SoftwareRenderVertex();
    SoftwareRenderVertex(code::Math::QInt32 x,
                         code::Math::QInt32 y,
                         code::Math::QInt32 z,
                         code::Math::QInt32 red,
                         code::Math::QInt32 green,
                         code::Math::QInt32 blue,
                         code::Math::QInt32 u,
                         code::Math::QInt32 v);
};

struct SoftwareProjectionParameters {
    code::Math::QInt32 centreX;
    code::Math::QInt32 centreY;
    code::Math::QInt32 distX;
    code::Math::QInt32 distY;

    SoftwareProjectionParameters();
    SoftwareProjectionParameters(code::Math::QInt32 centreX,
                                 code::Math::QInt32 centreY,
                                 code::Math::QInt32 distX,
                                 code::Math::QInt32 distY);
};

/*
 * Host-tested port of DirectX7's fixed-point camera transform, its distinct
 * screen projection, and the Java NearClipper triangle path. Clipping uses
 * caller-owned storage and preserves Java's insertion order, strict plane
 * crossing, integer wrap, and division.
 * A clipped triangle needs at most four live vertices; capacity 9 mirrors the
 * original scratch array and leaves room for the exact insertion algorithm.
 */
class SoftwareVertexPipeline {
public:
    enum { KClipVertexCapacity = 9 };

    static void transformVertex(const SoftwareRenderVertex& source,
                                const code::Math::Matrix* matrix,
                                SoftwareRenderVertex& destination);

    static bool clipTriangle(const SoftwareRenderVertex source[3],
                             code::Math::QInt32 clipZ,
                             SoftwareRenderVertex destination[KClipVertexCapacity],
                             code::Math::QInt32& destinationCount);

    /* Project camera-space vertices in place, matching NearClipper.project. */
    static bool project(SoftwareRenderVertex* vertices,
                        code::Math::QInt32 vertexCount,
                        const SoftwareProjectionParameters& parameters);

    /*
     * Project already transformed camera-space vertices in place, matching
     * DirectX7.transformAndProjectVertices. This is intentionally distinct
     * from NearClipper.project: DirectX7 scales by dist / (-rz + dist), and
     * leaves vertices with rz >= 0 unprojected before adding the screen center.
     * It does not clip or alter the camera-space rz value.
     */
    static bool projectDirectX7(SoftwareRenderVertex* vertices,
                                code::Math::QInt32 vertexCount,
                                const SoftwareProjectionParameters& parameters);
};

} // namespace Rendering
} // namespace code

#endif // QUANTUM_SOFTWARE_VERTEX_PIPELINE_H
