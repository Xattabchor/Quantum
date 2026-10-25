#include "software_polygon_renderer.h"

namespace code {
namespace Rendering {

using code::Math::QInt32;

bool SoftwarePolygonRenderer::drawCameraTriangle(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const SoftwareRenderVertex source[3],
    QInt32 clipZ,
    const SoftwareProjectionParameters& projection,
    const SoftwareTriangleRenderSettings& settings,
    QInt32& trianglesDrawn)
{
    trianglesDrawn = 0;
    if (source == 0) return false;

    SoftwareRenderVertex clipped[SoftwareVertexPipeline::KClipVertexCapacity];
    QInt32 clippedCount = 0;
    if (!SoftwareVertexPipeline::clipTriangle(
            source, clipZ, clipped, clippedCount)) {
        /* A valid triangle wholly behind the near plane is simply invisible. */
        return true;
    }
    if (!SoftwareVertexPipeline::project(
            clipped, clippedCount, projection)) {
        return false;
    }

    QInt32 fanIndex;
    for (fanIndex = 1; fanIndex + 1 < clippedCount; ++fanIndex) {
        const QInt32 indices[3] = { 0, fanIndex, fanIndex + 1 };
        AffineTriangleVertex vertices[3];
        SoftwareTriangleRenderSettings triangleSettings(settings);
        QInt32 vertexIndex;
        for (vertexIndex = 0; vertexIndex < 3; ++vertexIndex) {
            const SoftwareRenderVertex& vertex = clipped[indices[vertexIndex]];
            vertices[vertexIndex] = AffineTriangleVertex(
                vertex.x, vertex.y, vertex.u, vertex.v, vertex.z);
            triangleSettings.brightness[vertexIndex] = vertex.red;
            triangleSettings.ditherGradient[vertexIndex] = vertex.red;
            triangleSettings.color[vertexIndex] = AffineTriangleColor(
                vertex.red, vertex.green, vertex.blue);
        }

        if (!SoftwareTriangleRenderer::draw(
                destination, texture, vertices[0], vertices[1], vertices[2],
                triangleSettings)) {
            return false;
        }
        ++trianglesDrawn;
    }
    return true;
}

bool SoftwarePolygonRenderer::drawCameraQuad(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const SoftwareRenderVertex source[4],
    QInt32 clipZ,
    const SoftwareProjectionParameters& projection,
    const SoftwareTriangleRenderSettings& settings,
    SoftwareQuadSplit split,
    QInt32& trianglesDrawn)
{
    trianglesDrawn = 0;
    if (source == 0 ||
        (split != ESoftwareQuadSplitBD && split != ESoftwareQuadSplitAC)) {
        return false;
    }

    SoftwareRenderVertex first[3];
    SoftwareRenderVertex second[3];
    if (split == ESoftwareQuadSplitAC) {
        first[0] = source[0];
        first[1] = source[1];
        first[2] = source[2];
        second[0] = source[0];
        second[1] = source[2];
        second[2] = source[3];
    } else {
        first[0] = source[0];
        first[1] = source[1];
        first[2] = source[3];
        second[0] = source[1];
        second[1] = source[2];
        second[2] = source[3];
    }

    QInt32 firstTriangles = 0;
    if (!drawCameraTriangle(destination, texture, first, clipZ, projection,
                            settings, firstTriangles)) {
        trianglesDrawn = firstTriangles;
        return false;
    }
    trianglesDrawn = firstTriangles;

    QInt32 secondTriangles = 0;
    if (!drawCameraTriangle(destination, texture, second, clipZ, projection,
                            settings, secondTriangles)) {
        trianglesDrawn += secondTriangles;
        return false;
    }
    trianglesDrawn += secondTriangles;
    return true;
}

bool SoftwarePolygonRenderer::drawProjectedTriangle(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const SoftwareRenderVertex source[3],
    const SoftwareTriangleRenderSettings& settings,
    QInt32& trianglesDrawn)
{
    trianglesDrawn = 0;
    if (source == 0) return false;

    AffineTriangleVertex vertices[3];
    SoftwareTriangleRenderSettings triangleSettings(settings);
    QInt32 vertexIndex;
    for (vertexIndex = 0; vertexIndex < 3; ++vertexIndex) {
        const SoftwareRenderVertex& vertex = source[vertexIndex];
        vertices[vertexIndex] = AffineTriangleVertex(
            vertex.x, vertex.y, vertex.u, vertex.v, vertex.z);
        triangleSettings.brightness[vertexIndex] = vertex.red;
        triangleSettings.ditherGradient[vertexIndex] = vertex.red;
        triangleSettings.color[vertexIndex] = AffineTriangleColor(
            vertex.red, vertex.green, vertex.blue);
    }

    if (!SoftwareTriangleRenderer::draw(
            destination, texture, vertices[0], vertices[1], vertices[2],
            triangleSettings)) {
        return false;
    }
    trianglesDrawn = 1;
    return true;
}

bool SoftwarePolygonRenderer::drawProjectedQuad(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const SoftwareRenderVertex source[4],
    const SoftwareTriangleRenderSettings& settings,
    SoftwareQuadSplit split,
    QInt32& trianglesDrawn)
{
    trianglesDrawn = 0;
    if (source == 0 ||
        (split != ESoftwareQuadSplitBD && split != ESoftwareQuadSplitAC)) {
        return false;
    }

    SoftwareRenderVertex first[3];
    SoftwareRenderVertex second[3];
    if (split == ESoftwareQuadSplitAC) {
        first[0] = source[0];
        first[1] = source[1];
        first[2] = source[2];
        second[0] = source[0];
        second[1] = source[2];
        second[2] = source[3];
    } else {
        first[0] = source[0];
        first[1] = source[1];
        first[2] = source[3];
        second[0] = source[1];
        second[1] = source[2];
        second[2] = source[3];
    }

    QInt32 firstTriangles = 0;
    if (!drawProjectedTriangle(destination, texture, first, settings,
                               firstTriangles)) {
        trianglesDrawn = firstTriangles;
        return false;
    }
    trianglesDrawn = firstTriangles;

    QInt32 secondTriangles = 0;
    if (!drawProjectedTriangle(destination, texture, second, settings,
                               secondTriangles)) {
        trianglesDrawn += secondTriangles;
        return false;
    }
    trianglesDrawn += secondTriangles;
    return true;
}

} // namespace Rendering
} // namespace code
