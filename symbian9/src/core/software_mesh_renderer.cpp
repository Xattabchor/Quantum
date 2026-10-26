#include "software_mesh_renderer.h"

#include "math_utils.h"
#include "quantum_integer.h"

namespace code {
namespace Rendering {

using code::Math::QInt32;
using code::Math::QInt64;
using code::Math::Vector3D;

namespace {

const QInt32 KJavaFloorPerspectiveStepPixels = 9999999;

bool instrumentationEnabled(const SoftwareMeshRenderOptions& options)
{
    return options.instrumentation != 0 &&
           options.instrumentation->clockMicros != 0 &&
           options.instrumentation->recordPhaseMicros != 0;
}

QInt64 startInstrumentedPhase(const SoftwareMeshRenderOptions& options)
{
    if (!instrumentationEnabled(options)) return 0;
    return options.instrumentation->clockMicros(
        options.instrumentation->context);
}

void finishInstrumentedPhase(const SoftwareMeshRenderOptions& options,
                             SoftwareMeshRenderPhase phase,
                             QInt64 startMicros)
{
    if (!instrumentationEnabled(options)) return;
    const QInt64 endMicros = options.instrumentation->clockMicros(
        options.instrumentation->context);
    if (endMicros >= startMicros) {
        options.instrumentation->recordPhaseMicros(
            options.instrumentation->context, phase,
            endMicros - startMicros);
    }
}

bool isFloorPolygon(const SoftwareMeshPolygon& polygon)
{
    return polygon.normalY > 4000 || polygon.normalY < -4000;
}

bool validTexture(const SoftwareTextureView& texture)
{
    if (texture.pixels == 0 || texture.pixelCount <= 0 ||
        texture.width <= 0 || texture.height <= 0) {
        return false;
    }
    const code::Math::QInt64 texelCount =
        static_cast<code::Math::QInt64>(texture.width) * texture.height;
    return texelCount <= texture.pixelCount;
}

bool validMeshEnvelope(const SoftwareMeshView& mesh,
                       const SoftwareMeshRenderOptions& options,
                       const SoftwareMeshRenderWorkspace& workspace)
{
    if (mesh.vertexCount < 0 || mesh.polygonCount < 0 ||
        mesh.materialCount < 0 || workspace.cameraVertexCapacity < 0 ||
        workspace.renderQueueCapacity < 0 ||
        (options.projectionPath != ESoftwareMeshProjectionNearClipper &&
         options.projectionPath != ESoftwareMeshProjectionDirectX7)) {
        return false;
    }
    if ((mesh.vertexCount > 0 && mesh.vertices == 0) ||
        (mesh.polygonCount > 0 && mesh.polygons == 0) ||
        (mesh.materialCount > 0 && mesh.materials == 0)) {
        return false;
    }
    if (workspace.cameraVertexCapacity < mesh.vertexCount ||
        (mesh.vertexCount > 0 && workspace.cameraVertices == 0) ||
        workspace.renderQueueCapacity < mesh.polygonCount ||
        (mesh.polygonCount > 0 && workspace.renderQueue == 0)) {
        return false;
    }
    if (!options.useSurfaceViewport &&
        (options.viewportX1 > options.viewportX2 ||
         options.viewportY1 > options.viewportY2)) {
        return false;
    }
    if (options.shootIntensity > 0 && !options.flashlightEnabled &&
        options.shootLength == 0) {
        return false;
    }
    return true;
}

bool validMesh(const SoftwareMeshView& mesh,
               const SoftwareMeshRenderOptions& options,
               const SoftwareMeshRenderWorkspace& workspace)
{
    if (!validMeshEnvelope(mesh, options, workspace)) return false;

    QInt32 polygonIndex;
    for (polygonIndex = 0; polygonIndex < mesh.polygonCount; ++polygonIndex) {
        const SoftwareMeshPolygon& polygon = mesh.polygons[polygonIndex];
        if ((polygon.vertexCount != 3 && polygon.vertexCount != 4) ||
            polygon.shading < ESoftwareMeshUnlit ||
            polygon.shading > ESoftwareMeshVertexRgb ||
            polygon.textureIndex < 0 ||
            polygon.textureIndex >= mesh.materialCount) {
            return false;
        }
        QInt32 corner;
        for (corner = 0; corner < polygon.vertexCount; ++corner) {
            if (polygon.vertexIndices[corner] < 0 ||
                polygon.vertexIndices[corner] >= mesh.vertexCount) {
                return false;
            }
        }

        const SoftwareMeshMaterial& material =
            mesh.materials[polygon.textureIndex];
        if (material.mipLevels == 0 || material.mipLevelCount < 1 ||
            material.mipLevelCount > 3) {
            return false;
        }
        QInt32 mip;
        for (mip = 0; mip < material.mipLevelCount; ++mip) {
            if (!validTexture(material.mipLevels[mip])) return false;
        }
        const QInt32 drawMode = RenderModePolicy::resolveTextureDrawMode(
            options.quality, material.hasDrawModeOverride, material.drawMode);
        if (!RenderModePolicy::isSupportedTextureDrawMode(drawMode)) {
            return false;
        }
    }
    return true;
}

bool projectMeshVertices(SoftwareRenderVertex* vertices,
                         QInt32 vertexCount,
                         const SoftwareMeshRenderOptions& options)
{
    if (options.projectionPath == ESoftwareMeshProjectionDirectX7) {
        return SoftwareVertexPipeline::projectDirectX7(
            vertices, vertexCount, options.projection);
    }
    return SoftwareVertexPipeline::project(
        vertices, vertexCount, options.projection);
}

QInt32 minimumDepth(const SoftwareRenderVertex vertices[4], QInt32 count)
{
    QInt32 minimum = vertices[0].z;
    QInt32 i;
    for (i = 1; i < count; ++i) {
        if (vertices[i].z < minimum) minimum = vertices[i].z;
    }
    return minimum;
}

void span(const SoftwareRenderVertex vertices[4],
          QInt32 count,
          bool horizontal,
          QInt32& result)
{
    QInt32 minimum = horizontal ? vertices[0].x : vertices[0].y;
    QInt32 maximum = minimum;
    QInt32 i;
    for (i = 1; i < count; ++i) {
        const QInt32 value = horizontal ? vertices[i].x : vertices[i].y;
        if (value < minimum) minimum = value;
        if (value > maximum) maximum = value;
    }
    result = code::Math::javaIntSub(maximum, minimum);
}

QInt32 coordinateSpan(const QInt32 values[4], QInt32 count)
{
    QInt32 minimum = values[0];
    QInt32 maximum = values[0];
    QInt32 i;
    for (i = 1; i < count; ++i) {
        if (values[i] < minimum) minimum = values[i];
        if (values[i] > maximum) maximum = values[i];
    }
    return code::Math::javaIntSub(maximum, minimum);
}

bool outsideViewport(const SoftwareRenderVertex vertices[4],
                     QInt32 count,
                     QInt32 x1,
                     QInt32 y1,
                     QInt32 x2,
                     QInt32 y2)
{
    bool allLeft = true;
    bool allRight = true;
    bool allAbove = true;
    bool allBelow = true;
    QInt32 i;
    for (i = 0; i < count; ++i) {
        if (vertices[i].x >= x1) allLeft = false;
        if (vertices[i].x <= x2) allRight = false;
        if (vertices[i].y >= y1) allAbove = false;
        if (vertices[i].y <= y2) allBelow = false;
    }
    return allLeft || allRight || allAbove || allBelow;
}

bool backfaceCulled(const SoftwareRenderVertex vertices[4], QInt32 count)
{
    const QInt32 leftX = code::Math::javaIntSub(vertices[0].x,
                                                vertices[1].x);
    const QInt32 leftY = code::Math::javaIntSub(vertices[0].y,
                                                vertices[1].y);
    if (count == 3) {
        const QInt32 edgeY = code::Math::javaIntSub(vertices[1].y,
                                                    vertices[2].y);
        const QInt32 edgeX = code::Math::javaIntSub(vertices[1].x,
                                                    vertices[2].x);
        const QInt32 lhs = code::Math::javaIntMul(
            leftX, edgeY);
        const QInt32 rhs = code::Math::javaIntMul(
            leftY, edgeX);
        return lhs <= rhs;
    }

    const QInt32 firstEdgeY = code::Math::javaIntSub(vertices[1].y,
                                                     vertices[3].y);
    const QInt32 firstEdgeX = code::Math::javaIntSub(vertices[1].x,
                                                     vertices[3].x);
    const QInt32 firstLhs = code::Math::javaIntMul(leftX, firstEdgeY);
    const QInt32 firstRhs = code::Math::javaIntMul(leftY, firstEdgeX);

    const QInt32 secondX = code::Math::javaIntSub(vertices[2].x,
                                                  vertices[3].x);
    const QInt32 secondY = code::Math::javaIntSub(vertices[2].y,
                                                  vertices[3].y);
    const QInt32 reverseEdgeY = code::Math::javaIntSub(vertices[3].y,
                                                       vertices[1].y);
    const QInt32 reverseEdgeX = code::Math::javaIntSub(vertices[3].x,
                                                       vertices[1].x);
    const QInt32 secondLhs = code::Math::javaIntMul(secondX, reverseEdgeY);
    const QInt32 secondRhs = code::Math::javaIntMul(secondY, reverseEdgeX);
    return firstLhs <= firstRhs && secondLhs <= secondRhs;
}

QInt32 depthSortKey(const SoftwareMeshPolygon& polygon,
                    const SoftwareRenderVertex cameraVertices[4],
                    const SoftwareMeshMaterial& material,
                    const SoftwareMeshRenderOptions& options)
{
    QInt32 depth;
    if (polygon.vertexCount == 3) {
        QInt32 sum = code::Math::javaIntAdd(cameraVertices[0].z,
                                            cameraVertices[1].z);
        sum = code::Math::javaIntAdd(sum, cameraVertices[2].z);
        depth = code::Math::javaIntDiv(code::Math::javaIntMul(sum, 4), 3);
    } else {
        depth = cameraVertices[0].z;
        depth = code::Math::javaIntAdd(depth, cameraVertices[1].z);
        depth = code::Math::javaIntAdd(depth, cameraVertices[2].z);
        depth = code::Math::javaIntAdd(depth, cameraVertices[3].z);
    }

    if (polygon.normalY > 4000 || polygon.normalY < -4000) {
        depth = code::Math::javaIntAdd(depth, options.floorOffsetSZ);
    }
    depth = code::Math::javaIntAdd(depth, polygon.depthBias);
    depth = code::Math::javaIntAdd(depth, material.depthBias);
    return depth;
}

QInt32 chooseMipLevel(const SoftwareMeshPolygon& polygon,
                      const SoftwareMeshMaterial& material,
                      QInt32 width,
                      QInt32 height,
                      QInt32 drawMode,
                      QInt32 mipQuality,
                      bool mipMapping)
{
    if (!mipMapping || material.mipLevelCount <= 1) return 0;

    QInt32 level = 0;
    const SoftwareTextureView& base = material.mipLevels[0];
    if (RenderModePolicy::usesUvBasedMipmapSelection(drawMode)) {
        QInt32 uvU[4];
        QInt32 uvV[4];
        QInt32 i;
        for (i = 0; i < polygon.vertexCount; ++i) {
            uvU[i] = polygon.textureU[i];
            uvV[i] = polygon.textureV[i];
        }
        QInt32 sizeU = code::Math::javaShiftRight(
            code::Math::javaIntMul(
                coordinateSpan(uvU, polygon.vertexCount), base.width), 8);
        QInt32 sizeV = code::Math::javaShiftRight(
            code::Math::javaIntMul(
                coordinateSpan(uvV, polygon.vertexCount), base.height), 8);
        const QInt32 screenMetric = code::Math::javaIntMul(
            code::Math::javaShiftRight(
                code::Math::javaIntAdd(width, height), 1),
            mipQuality > 999 ? 2 : 3);
        if (code::Math::javaIntAdd(sizeU, sizeV) > screenMetric) {
            level = 1;
            sizeU = code::Math::javaShiftRight(sizeU, 1);
            sizeV = code::Math::javaShiftRight(sizeV, 1);
            if (material.mipLevelCount > 2 &&
                code::Math::javaIntAdd(sizeU, sizeV) > screenMetric) {
                level = 2;
            }
        }
    } else {
        if (width < base.width || height < base.width) {
            level = 1;
            if (material.mipLevelCount > 2 &&
                (width < material.mipLevels[1].width ||
                 height < material.mipLevels[1].width)) {
                level = 2;
            }
        }
    }
    return level;
}

void sortQueue(SoftwareMeshRenderItem* queue, QInt32 start, QInt32 end)
{
    if (start >= end) return;
    const QInt32 pivot = queue[(start + end) >> 1].sortDepth;
    QInt32 first = start;
    QInt32 second = end;
    while (first <= second) {
        while (queue[first].sortDepth > pivot) ++first;
        while (queue[second].sortDepth < pivot) --second;
        if (first <= second) {
            const SoftwareMeshRenderItem temporary = queue[first];
            queue[first] = queue[second];
            queue[second] = temporary;
            ++first;
            --second;
        }
    }
    if (start < second) sortQueue(queue, start, second);
    if (end > first) sortQueue(queue, first, end);
}

QInt32 getVertexVectorLight(const SoftwareMeshPolygon& polygon,
                            const SoftwareRenderVertex& cameraVertex,
                            const SoftwareMeshRenderOptions& options)
{
    /*
     * DirectX7.transformAndProjectVertices stores the transformed position
     * back in Vertex.x/y/z. RenderObject.getLight then subtracts the camera
     * matrix translation from those values; preserve that source behavior,
     * rather than calculating from the parser's source-space vertex.
     */
    return code::Math::MathUtils::microCalcLight(
        polygon.normalX, polygon.normalY, polygon.normalZ,
        code::Math::javaIntSub(cameraVertex.x, options.cameraWorldX),
        code::Math::javaIntSub(cameraVertex.y, options.cameraWorldY),
        code::Math::javaIntSub(cameraVertex.z, options.cameraWorldZ));
}

bool getSingleVertexLight(QInt32 base,
                          const SoftwareMeshPolygon& polygon,
                          const SoftwareRenderVertex& cameraVertex,
                          QInt32 depth,
                          const SoftwareMeshRenderOptions& options,
                          bool fastPath,
                          QInt32& result)
{
    if (fastPath) {
        result = base;
        return true;
    }

    const QInt32 fogDistance = options.triangleSettings.fogDistance;
    const QInt32 lightLength = options.flashlightEnabled ? 6500 : 5000;
    const QInt32 distanceFromCamera = code::Math::javaIntSub(0, depth);
    QInt32 lit = fogDistance <= 1 || distanceFromCamera < fogDistance
        ? 255 : 0;
    QInt32 flash = 0;
    QInt32 vectorLight = 255;

    if ((fogDistance > 1 && options.cameraVectorLight) ||
        ((options.shootIntensity > 0 || options.flashlightEnabled) &&
         distanceFromCamera < lightLength)) {
        vectorLight = getVertexVectorLight(polygon, cameraVertex, options);
    }

    if (fogDistance > 1 && distanceFromCamera < fogDistance) {
        lit = code::Math::javaIntAdd(
            255, code::Math::javaIntDiv(
                code::Math::javaIntMul(depth, 255), fogDistance));
        if (options.cameraVectorLight) {
            lit = code::Math::javaIntDiv(
                code::Math::javaIntMul(lit, vectorLight), 255);
        }
        if (lit < 0) lit = 0;
        if (lit > 255) lit = 255;
    }

    if ((options.shootIntensity > 0 || options.flashlightEnabled) &&
        distanceFromCamera < lightLength) {
        flash = code::Math::javaIntAdd(
            255, code::Math::javaIntDiv(
                code::Math::javaIntMul(depth, 255), lightLength));
        flash = code::Math::javaIntDiv(
            code::Math::javaIntMul(flash, vectorLight), 255);
        if (!options.flashlightEnabled) {
            flash = code::Math::javaIntDiv(
                code::Math::javaIntMul(flash, options.shootIntensity),
                options.shootLength);
        }
        if (flash < 0) flash = 0;
        else if (flash > 255) flash = 255;
    }

    result = code::Math::javaIntAdd(
        code::Math::javaShiftRight(code::Math::javaIntMul(base, lit), 8),
        flash);
    if (result > 255) result = 255;
    return true;
}

bool getRgbVertexLight(const QInt32 base[3],
                       const SoftwareMeshPolygon& polygon,
                       const SoftwareRenderVertex& cameraVertex,
                       QInt32 depth,
                       const SoftwareMeshRenderOptions& options,
                       bool fastPath,
                       QInt32 result[3])
{
    if (fastPath) {
        result[0] = base[0];
        result[1] = base[1];
        result[2] = base[2];
        return true;
    }

    const QInt32 fogDistance = options.triangleSettings.fogDistance;
    const QInt32 lightLength = options.flashlightEnabled ? 6500 : 5000;
    const QInt32 distanceFromCamera = code::Math::javaIntSub(0, depth);
    QInt32 lit = fogDistance <= 1 || distanceFromCamera < fogDistance
        ? 255 : 0;
    QInt32 flash = 0;
    QInt32 vectorLight = 255;

    if ((fogDistance > 1 && options.cameraVectorLight) ||
        ((options.shootIntensity > 0 || options.flashlightEnabled) &&
         distanceFromCamera < lightLength)) {
        vectorLight = getVertexVectorLight(polygon, cameraVertex, options);
    }

    if (fogDistance > 1 && distanceFromCamera < fogDistance) {
        lit = code::Math::javaIntAdd(
            255, code::Math::javaIntDiv(
                code::Math::javaIntMul(depth, 255), fogDistance));
        if (options.cameraVectorLight) {
            lit = code::Math::javaIntDiv(
                code::Math::javaIntMul(lit, vectorLight), 255);
        }
        if (lit < 0) lit = 0;
        if (lit > 255) lit = 255;
    }

    if ((options.shootIntensity > 0 || options.flashlightEnabled) &&
        distanceFromCamera < lightLength) {
        flash = code::Math::javaIntAdd(
            255, code::Math::javaIntDiv(
                code::Math::javaIntMul(depth, 255), lightLength));
        flash = code::Math::javaIntDiv(
            code::Math::javaIntMul(flash, vectorLight), 255);
        if (!options.flashlightEnabled) {
            flash = code::Math::javaIntDiv(
                code::Math::javaIntMul(flash, options.shootIntensity),
                options.shootLength);
        }
        if (flash < 0) flash = 0;
        else if (flash > 255) flash = 255;
    }

    QInt32 channel;
    for (channel = 0; channel < 3; ++channel) {
        result[channel] = code::Math::javaIntAdd(
            code::Math::javaIntDiv(
                code::Math::javaIntMul(base[channel], lit), 255), flash);
        if (result[channel] > 255) result[channel] = 255;
    }
    return true;
}

void prepareCameraPolygon(const SoftwareMeshPolygon& polygon,
                         QInt32 vertexOffset,
                         const SoftwareMeshRenderWorkspace& workspace,
                         SoftwareRenderVertex result[4])
{
    QInt32 corner;
    for (corner = 0; corner < polygon.vertexCount; ++corner) {
        const SoftwareRenderVertex& position =
            workspace.cameraVertices[vertexOffset +
                                     polygon.vertexIndices[corner]];
        result[corner] = SoftwareRenderVertex(
            position.x, position.y, position.z,
            255, 255, 255,
            polygon.textureU[corner], polygon.textureV[corner]);
    }
}

void prepareDrawVertices(const SoftwareMeshPolygon& polygon,
                         const SoftwareMeshMaterial& material,
                         const SoftwareMeshRenderItem& item,
                         QInt32 vertexOffset,
                         const SoftwareMeshRenderOptions& options,
                         const SoftwareMeshRenderWorkspace& workspace,
                         SoftwareRenderVertex result[4])
{
    const SoftwareTextureView& texture = material.mipLevels[item.mipLevel];
    const bool fastPath = RenderModePolicy::usesFastAffinePath(options.quality);
    QInt32 corner;
    for (corner = 0; corner < polygon.vertexCount; ++corner) {
        const QInt32 vertexIndex = polygon.vertexIndices[corner];
        const SoftwareRenderVertex& position =
            workspace.cameraVertices[vertexOffset + vertexIndex];
        QInt32 red = 255;
        QInt32 green = 255;
        QInt32 blue = 255;

        if (item.drawMode == 6 && options.cameraVectorLight) {
            red = code::Math::MathUtils::calcLight(
                polygon.normalX, polygon.normalY, polygon.normalZ,
                code::Math::javaIntSub(position.x, options.cameraWorldX),
                code::Math::javaIntSub(position.y, options.cameraWorldY),
                code::Math::javaIntSub(position.z, options.cameraWorldZ));
            green = red;
            blue = red;
        } else if (polygon.shading == ESoftwareMeshVertexGray) {
            QInt32 brightness = polygon.vertexColor[corner][0];
            getSingleVertexLight(brightness, polygon, position,
                                 position.z, options, fastPath, brightness);
            red = green = blue = brightness;
        } else if (polygon.shading == ESoftwareMeshVertexRgb) {
            QInt32 base[3];
            QInt32 lit[3];
            base[0] = polygon.vertexColor[corner][0];
            base[1] = polygon.vertexColor[corner][1];
            base[2] = polygon.vertexColor[corner][2];
            getRgbVertexLight(base, polygon, position, position.z,
                              options, fastPath, lit);
            red = lit[0];
            green = lit[1];
            blue = lit[2];
        }

        QInt32 u = polygon.textureU[corner];
        QInt32 v = polygon.textureV[corner];
        const bool hasMipChain = material.mipLevelCount > 1;
        if (texture.width != 256) {
            if (((hasMipChain && material.mipLevels[0].width < 255) ||
                 (!hasMipChain && texture.width < 255)) && u == 255) {
                u = 256;
            }
            u = code::Math::javaShiftRight(
                code::Math::javaIntMul(u, texture.width), 8);
        }
        if (texture.height != 256) {
            if (((hasMipChain && material.mipLevels[0].height < 256) ||
                 (!hasMipChain && texture.height < 256)) && v == 255) {
                v = 256;
            }
            v = code::Math::javaShiftRight(
                code::Math::javaIntMul(v, texture.height), 8);
        }

        result[corner] = SoftwareRenderVertex(
            position.x, position.y, position.z,
            red, green, blue, u, v);
    }
}

} // anonymous namespace

SoftwareMeshPolygon::SoftwareMeshPolygon()
    : vertexCount(0), shading(ESoftwareMeshUnlit),
      normalX(0), normalY(0), normalZ(0), textureIndex(0), depthBias(0)
{
    QInt32 i;
    QInt32 channel;
    for (i = 0; i < 4; ++i) {
        vertexIndices[i] = 0;
        textureU[i] = 0;
        textureV[i] = 0;
        for (channel = 0; channel < 3; ++channel) {
            vertexColor[i][channel] = 255;
        }
    }
}

SoftwareMeshMaterial::SoftwareMeshMaterial()
    : mipLevels(0), mipLevelCount(0), hasDrawModeOverride(false),
      drawMode(0), perspectiveCorrection(false), textureAlphaMixing(true),
      depthBias(0)
{
}

SoftwareMeshView::SoftwareMeshView()
    : vertices(0), vertexCount(0), polygons(0), polygonCount(0),
      materials(0), materialCount(0)
{
}

SoftwareMeshView::SoftwareMeshView(
    const Vector3D* vertexData,
    QInt32 vertexDataCount,
    const SoftwareMeshPolygon* polygonData,
    QInt32 polygonDataCount,
    const SoftwareMeshMaterial* materialData,
    QInt32 materialDataCount)
    : vertices(vertexData), vertexCount(vertexDataCount),
      polygons(polygonData), polygonCount(polygonDataCount),
      materials(materialData), materialCount(materialDataCount)
{
}

SoftwareMeshInstance::SoftwareMeshInstance()
    : mesh(0), modelToCamera(0)
{
}

SoftwareMeshInstance::SoftwareMeshInstance(
    const SoftwareMeshView* meshView,
    const code::Math::Matrix* transform)
    : mesh(meshView), modelToCamera(transform)
{
}

SoftwareMeshRenderInstrumentation::SoftwareMeshRenderInstrumentation()
    : context(0), clockMicros(0), recordPhaseMicros(0)
{
}

SoftwareMeshRenderOptions::SoftwareMeshRenderOptions()
    : modelToCamera(0), projectionPath(ESoftwareMeshProjectionNearClipper),
      clipZ(-1), useSurfaceViewport(true),
      viewportX1(0), viewportY1(0), viewportX2(0), viewportY2(0),
      farDistance(30000000), floorOffsetSZ(0), mipQuality(24),
      cameraWorldX(0), cameraWorldY(0), cameraWorldZ(0),
      cameraVectorLight(false), shootIntensity(0), shootLength(0),
      flashlightEnabled(false), lightDirectionX(0), lightDirectionY(0),
      lightDirectionZ(0), instrumentation(0), meshContentsPrevalidated(false)
{
}

SoftwareMeshRenderItem::SoftwareMeshRenderItem()
    : instanceIndex(0), vertexOffset(0), polygonIndex(0), sortDepth(0),
      mipLevel(0), drawMode(0),
      usePerspectiveCorrection(false), splitQuadAlongAC(false),
      hasPackedFogColor(false), packedFogColor(0), shadeStrength(0)
{
}

SoftwareMeshRenderWorkspace::SoftwareMeshRenderWorkspace()
    : cameraVertices(0), cameraVertexCapacity(0), renderQueue(0),
      renderQueueCapacity(0), projectedVertices(0),
      projectedVertexCapacity(0)
{
}

SoftwareMeshRenderWorkspace::SoftwareMeshRenderWorkspace(
    SoftwareRenderVertex* cameraData,
    QInt32 cameraCapacity,
    SoftwareMeshRenderItem* queueData,
    QInt32 queueCapacity,
    SoftwareRenderVertex* projectedData,
    QInt32 projectedCapacity)
    : cameraVertices(cameraData), cameraVertexCapacity(cameraCapacity),
      renderQueue(queueData), renderQueueCapacity(queueCapacity),
      projectedVertices(projectedData),
      projectedVertexCapacity(projectedCapacity)
{
}

SoftwareMeshRenderStats::SoftwareMeshRenderStats()
    : polygonsVisited(0), polygonsQueued(0), polygonsDrawn(0),
      viewportCulled(0), backfaceCulled(0), depthCulled(0),
      trianglesDrawn(0), perspectivePolygons(0)
{
    mipLevelSelections[0] = 0;
    mipLevelSelections[1] = 0;
    mipLevelSelections[2] = 0;
}

bool SoftwareMeshRenderer::render(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareMeshView& mesh,
    const SoftwareMeshRenderOptions& options,
    const SoftwareMeshRenderWorkspace& workspace,
    SoftwareMeshRenderStats& stats)
{
    const SoftwareMeshInstance instance(&mesh, options.modelToCamera);
    return renderScene(destination, &instance, 1, options, workspace, stats);
}

bool SoftwareMeshRenderer::renderScene(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareMeshInstance* instances,
    QInt32 instanceCount,
    const SoftwareMeshRenderOptions& options,
    const SoftwareMeshRenderWorkspace& workspace,
    SoftwareMeshRenderStats& stats)
{
    stats = SoftwareMeshRenderStats();
    if (instanceCount < 0 || (instanceCount > 0 && instances == 0)) {
        return false;
    }

    const QInt64 validationStartMicros =
        startInstrumentedPhase(options);
    QInt64 totalVertices = 0;
    QInt64 totalPolygons = 0;
    QInt32 instanceIndex;
    for (instanceIndex = 0; instanceIndex < instanceCount; ++instanceIndex) {
        if (instances[instanceIndex].mesh == 0) return false;
        const SoftwareMeshView& mesh = *instances[instanceIndex].mesh;
        const bool meshValid = options.meshContentsPrevalidated
            ? validMeshEnvelope(mesh, options, workspace)
            : validMesh(mesh, options, workspace);
        if (!meshValid) return false;
        totalVertices += mesh.vertexCount;
        totalPolygons += mesh.polygonCount;
        if (totalVertices > 2147483647L ||
            totalPolygons > 2147483647L) {
            return false;
        }
    }
    if (totalVertices > workspace.cameraVertexCapacity ||
        totalPolygons > workspace.renderQueueCapacity ||
        (totalVertices > 0 && workspace.cameraVertices == 0) ||
        (totalPolygons > 0 && workspace.renderQueue == 0) ||
        workspace.projectedVertexCapacity < 0 ||
        (workspace.projectedVertices == 0 &&
         workspace.projectedVertexCapacity != 0) ||
        (workspace.projectedVertices != 0 &&
         (workspace.projectedVertices == workspace.cameraVertices ||
          totalVertices > workspace.projectedVertexCapacity))) {
        return false;
    }

    const QInt32 viewportX1 = options.useSurfaceViewport
        ? 0 : options.viewportX1;
    const QInt32 viewportY1 = options.useSurfaceViewport
        ? 0 : options.viewportY1;
    const QInt32 viewportX2 = options.useSurfaceViewport
        ? destination.width : options.viewportX2;
    const QInt32 viewportY2 = options.useSurfaceViewport
        ? destination.height : options.viewportY2;
    if (viewportX1 > viewportX2 || viewportY1 > viewportY2) return false;
    finishInstrumentedPhase(
        options, ESoftwareMeshRenderPhaseValidation,
        validationStartMicros);

    const QInt64 transformStartMicros =
        startInstrumentedPhase(options);
    QInt32 vertexOffset = 0;
    for (instanceIndex = 0; instanceIndex < instanceCount; ++instanceIndex) {
        const SoftwareMeshView& mesh = *instances[instanceIndex].mesh;
        const code::Math::Matrix* transform =
            instances[instanceIndex].modelToCamera != 0
                ? instances[instanceIndex].modelToCamera
                : options.modelToCamera;
        QInt32 vertexIndex;
        for (vertexIndex = 0; vertexIndex < mesh.vertexCount; ++vertexIndex) {
            const Vector3D& source = mesh.vertices[vertexIndex];
            SoftwareRenderVertex sourceVertex(
                source.x, source.y, source.z, 255, 255, 255, 0, 0);
            SoftwareVertexPipeline::transformVertex(
                sourceVertex, transform,
                workspace.cameraVertices[vertexOffset + vertexIndex]);
            if (workspace.projectedVertices != 0) {
                workspace.projectedVertices[vertexOffset + vertexIndex] =
                    workspace.cameraVertices[vertexOffset + vertexIndex];
            }
        }
        if (workspace.projectedVertices != 0 &&
            !projectMeshVertices(
                workspace.projectedVertices + vertexOffset,
                mesh.vertexCount, options)) {
            return false;
        }
        vertexOffset += mesh.vertexCount;
    }
    finishInstrumentedPhase(
        options, ESoftwareMeshRenderPhaseTransformProjection,
        transformStartMicros);

    const QInt64 queueStartMicros = startInstrumentedPhase(options);
    const bool fastPath = RenderModePolicy::usesFastAffinePath(options.quality);
    QInt32 queueCount = 0;
    vertexOffset = 0;
    for (instanceIndex = 0; instanceIndex < instanceCount; ++instanceIndex) {
        const SoftwareMeshView& mesh = *instances[instanceIndex].mesh;
        QInt32 polygonIndex;
        for (polygonIndex = 0; polygonIndex < mesh.polygonCount;
             ++polygonIndex) {
            ++stats.polygonsVisited;
            const SoftwareMeshPolygon& polygon = mesh.polygons[polygonIndex];
            const SoftwareMeshMaterial& material =
                mesh.materials[polygon.textureIndex];
            SoftwareRenderVertex cameraPolygon[4];
            SoftwareRenderVertex projectedPolygon[4];
            prepareCameraPolygon(polygon, vertexOffset, workspace,
                                 cameraPolygon);
            QInt32 corner;
            for (corner = 0; corner < polygon.vertexCount; ++corner) {
                projectedPolygon[corner] = cameraPolygon[corner];
                if (workspace.projectedVertices != 0) {
                    const SoftwareRenderVertex& cachedProjection =
                        workspace.projectedVertices[
                            vertexOffset + polygon.vertexIndices[corner]];
                    projectedPolygon[corner].x = cachedProjection.x;
                    projectedPolygon[corner].y = cachedProjection.y;
                }
            }
            if (workspace.projectedVertices == 0 &&
                !projectMeshVertices(projectedPolygon,
                                     polygon.vertexCount, options)) {
                return false;
            }

            if (outsideViewport(projectedPolygon, polygon.vertexCount,
                                viewportX1, viewportY1,
                                viewportX2, viewportY2)) {
                ++stats.viewportCulled;
                continue;
            }
            if (backfaceCulled(projectedPolygon, polygon.vertexCount)) {
                ++stats.backfaceCulled;
                continue;
            }

            const QInt32 nearestDepth =
                minimumDepth(cameraPolygon, polygon.vertexCount);
            if (nearestDepth > 0 ||
                code::Math::javaIntSub(0, nearestDepth) >
                    options.farDistance) {
                ++stats.depthCulled;
                continue;
            }

            QInt32 projectedWidth = 0;
            QInt32 projectedHeight = 0;
            span(projectedPolygon, polygon.vertexCount, true, projectedWidth);
            span(projectedPolygon, polygon.vertexCount, false, projectedHeight);
            const QInt32 drawMode = RenderModePolicy::resolveTextureDrawMode(
                options.quality, material.hasDrawModeOverride,
                material.drawMode);
            const bool floorPolygon = isFloorPolygon(polygon);
            const QInt32 polygonMipQuality = floorPolygon
                ? KJavaFloorPerspectiveStepPixels : options.mipQuality;

            SoftwareMeshRenderItem item;
            item.instanceIndex = instanceIndex;
            item.vertexOffset = vertexOffset;
            item.polygonIndex = polygonIndex;
            item.sortDepth = depthSortKey(
                polygon, cameraPolygon, material, options);
            item.drawMode = drawMode;
            item.mipLevel = chooseMipLevel(
                polygon, material, projectedWidth, projectedHeight, drawMode,
                polygonMipQuality, options.quality.mipMapping);
            item.usePerspectiveCorrection =
                RenderModePolicy::usesPerspectiveCorrectionForDrawMode(
                    options.quality, drawMode, polygon.vertexCount,
                    projectedWidth, projectedHeight,
                    material.perspectiveCorrection);
            item.hasPackedFogColor = drawMode == 1;
            if (item.hasPackedFogColor) {
                item.packedFogColor =
                    SoftwareAffineRasterizer::mode1FogAddColor(
                        nearestDepth, options.triangleSettings.fogDistance,
                        options.triangleSettings.fogColor);
            }
            if (drawMode == 5) {
                item.shadeStrength = code::Math::javaIntSub(
                    255, code::Math::MathUtils::calcLight(
                        polygon.normalX, polygon.normalY, polygon.normalZ,
                        options.lightDirectionX, options.lightDirectionY,
                        options.lightDirectionZ));
            }
            if (polygon.vertexCount == 4 &&
                polygon.shading == ESoftwareMeshUnlit && !fastPath &&
                (drawMode == 3 || drawMode == 6) &&
                options.quality.fogQuality != 0) {
                const QInt32 nearestAC =
                    cameraPolygon[0].z < cameraPolygon[2].z
                        ? cameraPolygon[0].z : cameraPolygon[2].z;
                if (nearestDepth != nearestAC) item.splitQuadAlongAC = true;
            }

            workspace.renderQueue[queueCount] = item;
            ++queueCount;
            ++stats.polygonsQueued;
            ++stats.mipLevelSelections[item.mipLevel];
            if (item.usePerspectiveCorrection) {
                ++stats.perspectivePolygons;
            }
        }
        vertexOffset += mesh.vertexCount;
    }
    finishInstrumentedPhase(
        options, ESoftwareMeshRenderPhasePolygonQueue,
        queueStartMicros);

    const QInt64 sortStartMicros = startInstrumentedPhase(options);
    if (queueCount > 1) {
        sortQueue(workspace.renderQueue, 0, queueCount - 1);
    }
    finishInstrumentedPhase(
        options, ESoftwareMeshRenderPhaseSort, sortStartMicros);

    const QInt64 drawStartMicros = startInstrumentedPhase(options);
    QInt32 queueIndex;
    for (queueIndex = queueCount - 1; queueIndex >= 0; --queueIndex) {
        const SoftwareMeshRenderItem& item = workspace.renderQueue[queueIndex];
        const SoftwareMeshView& mesh = *instances[item.instanceIndex].mesh;
        const SoftwareMeshPolygon& polygon = mesh.polygons[item.polygonIndex];
        const SoftwareMeshMaterial& material =
            mesh.materials[polygon.textureIndex];
        const SoftwareTextureView& texture =
            material.mipLevels[item.mipLevel];
        SoftwareRenderVertex drawVertices[4];
        prepareDrawVertices(polygon, material, item,
                            item.vertexOffset, options, workspace,
                            drawVertices);

        SoftwareTriangleRenderSettings triangleSettings(
            options.triangleSettings);
        triangleSettings.drawMode = item.drawMode;
        triangleSettings.fogQuality = options.quality.fogQuality;
        triangleSettings.textureAlphaMixing = material.textureAlphaMixing;
        triangleSettings.mode6UsesVertexLight =
            item.drawMode == 6 && options.cameraVectorLight;
        triangleSettings.usePerspectiveCorrection =
            item.usePerspectiveCorrection;
        if (item.usePerspectiveCorrection && isFloorPolygon(polygon)) {
            /* Java sets q=9999999 once for the whole horizontal polygon. */
            triangleSettings.perspectiveStepPixels =
                KJavaFloorPerspectiveStepPixels;
        }
        triangleSettings.hasPackedFogColor = item.hasPackedFogColor;
        triangleSettings.packedFogColor = item.packedFogColor;
        if (item.drawMode == 5) {
            triangleSettings.shadeStrength = item.shadeStrength;
        }

        QInt32 triangles = 0;
        bool drawn;
        if (options.projectionPath == ESoftwareMeshProjectionDirectX7) {
            // Projection changes only x/y; keep per-corner shading and UVs.
            if (workspace.projectedVertices != 0) {
                QInt32 corner;
                for (corner = 0; corner < polygon.vertexCount; ++corner) {
                    const SoftwareRenderVertex& cachedProjection =
                        workspace.projectedVertices[
                            item.vertexOffset +
                            polygon.vertexIndices[corner]];
                    drawVertices[corner].x = cachedProjection.x;
                    drawVertices[corner].y = cachedProjection.y;
                }
            } else if (!SoftwareVertexPipeline::projectDirectX7(
                           drawVertices, polygon.vertexCount,
                           options.projection)) {
                return false;
            }
            if (polygon.vertexCount == 3) {
                drawn = SoftwarePolygonRenderer::drawProjectedTriangle(
                    destination, texture, drawVertices, triangleSettings,
                    triangles);
            } else {
                drawn = SoftwarePolygonRenderer::drawProjectedQuad(
                    destination, texture, drawVertices, triangleSettings,
                    item.splitQuadAlongAC
                        ? ESoftwareQuadSplitAC : ESoftwareQuadSplitBD,
                    triangles);
            }
        } else if (polygon.vertexCount == 3) {
            drawn = SoftwarePolygonRenderer::drawCameraTriangle(
                destination, texture, drawVertices, options.clipZ,
                options.projection, triangleSettings, triangles);
        } else {
            drawn = SoftwarePolygonRenderer::drawCameraQuad(
                destination, texture, drawVertices, options.clipZ,
                options.projection, triangleSettings,
                item.splitQuadAlongAC
                    ? ESoftwareQuadSplitAC : ESoftwareQuadSplitBD,
                triangles);
        }
        stats.trianglesDrawn += triangles;
        if (!drawn) return false;
        ++stats.polygonsDrawn;
    }
    finishInstrumentedPhase(
        options, ESoftwareMeshRenderPhaseDraw, drawStartMicros);
    return true;
}
} // namespace Rendering
} // namespace code
