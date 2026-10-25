#ifndef QUANTUM_SOFTWARE_POLYGON_RENDERER_H
#define QUANTUM_SOFTWARE_POLYGON_RENDERER_H

#include "software_triangle_renderer.h"
#include "software_vertex_pipeline.h"

namespace code {
namespace Rendering {

/* The source quad diagonal used by Java's polygon classes. */
enum SoftwareQuadSplit {
    ESoftwareQuadSplitBD = 0, /* (a,b,d) + (b,c,d) */
    ESoftwareQuadSplitAC      /* (a,b,c) + (a,c,d) */
};

/*
 * Camera-space polygon front end for Java triangles and quads. It applies the
 * Java near clipper, projection, fan split, per-vertex RGB/UV interpolation,
 * and then routes each projected triangle through SoftwareTriangleRenderer.
 * A quad caller resolves RenderModePolicy once from all four original corners
 * and passes one shared settings object to drawCameraQuad(). The projected
 * entry points bypass clipping/projection for Java's DirectX7 preprojected
 * mesh route and send screen-space vertices directly to the triangle kernels.
 */
class SoftwarePolygonRenderer {
public:
    /*
     * Returns false for invalid arguments or a rejected rasterizer operation.
     * A fully clipped polygon is a successful no-op with trianglesDrawn=0.
     * The caller owns all vertex, framebuffer, and texture storage.
     */
    static bool drawCameraTriangle(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTextureView& texture,
        const SoftwareRenderVertex source[3],
        code::Math::QInt32 clipZ,
        const SoftwareProjectionParameters& projection,
        const SoftwareTriangleRenderSettings& settings,
        code::Math::QInt32& trianglesDrawn);

    /*
     * Render an original camera-space quad. The exact same already-resolved
     * settings (especially usePerspectiveCorrection) are supplied to both
     * Java triangle halves. `split` selects BD or AC; mesh callers choose the
     * Java Polygon4V fog-quality diagonal when required.
     */
    static bool drawCameraQuad(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTextureView& texture,
        const SoftwareRenderVertex source[4],
        code::Math::QInt32 clipZ,
        const SoftwareProjectionParameters& projection,
        const SoftwareTriangleRenderSettings& settings,
        SoftwareQuadSplit split,
        code::Math::QInt32& trianglesDrawn);

    /*
     * Draw vertices already projected by DirectX7.transformAndProjectVertices.
     * These entry points deliberately do not apply NearClipper clipping or
     * projection; `x/y` are screen coordinates and `z` is camera rz.
     */
    static bool drawProjectedTriangle(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTextureView& texture,
        const SoftwareRenderVertex source[3],
        const SoftwareTriangleRenderSettings& settings,
        code::Math::QInt32& trianglesDrawn);

    static bool drawProjectedQuad(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTextureView& texture,
        const SoftwareRenderVertex source[4],
        const SoftwareTriangleRenderSettings& settings,
        SoftwareQuadSplit split,
        code::Math::QInt32& trianglesDrawn);
};

} // namespace Rendering
} // namespace code

#endif // QUANTUM_SOFTWARE_POLYGON_RENDERER_H
