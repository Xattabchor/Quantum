#ifndef QUANTUM_SOFTWARE_MESH_RENDERER_H
#define QUANTUM_SOFTWARE_MESH_RENDERER_H

#include "matrix.h"
#include "render_mode_policy.h"
#include "software_polygon_renderer.h"
#include "vector3d.h"

namespace code {
namespace Rendering {

enum SoftwareMeshShading {
    ESoftwareMeshUnlit = 0,
    ESoftwareMeshVertexGray = 1,
    ESoftwareMeshVertexRgb = 2
};

/*
 * One indexed source polygon. UVs are Java's unsigned 0..255 coordinates;
 * vertex colors are light multipliers, either one grayscale value or RGB.
 * A normal is supplied in the same world/light space as Java RenderObject's
 * normal. The renderer reads only 3- and 4-corner records.
 */
struct SoftwareMeshPolygon {
    code::Math::QInt32 vertexCount;
    code::Math::QInt32 vertexIndices[4];
    code::Math::QInt32 textureU[4];
    code::Math::QInt32 textureV[4];
    code::Math::QInt32 vertexColor[4][3];
    code::Math::QInt32 shading;
    code::Math::QInt32 normalX;
    code::Math::QInt32 normalY;
    code::Math::QInt32 normalZ;
    code::Math::QInt32 textureIndex;
    code::Math::QInt32 depthBias;

    SoftwareMeshPolygon();
};

/* Non-owning material and up-to-three Java mip levels. */
struct SoftwareMeshMaterial {
    const SoftwareTextureView* mipLevels;
    code::Math::QInt32 mipLevelCount;
    bool hasDrawModeOverride;
    code::Math::QInt32 drawMode;
    bool perspectiveCorrection;
    bool textureAlphaMixing;
    code::Math::QInt32 depthBias;

    SoftwareMeshMaterial();
};

struct SoftwareMeshView {
    const code::Math::Vector3D* vertices;
    code::Math::QInt32 vertexCount;
    const SoftwareMeshPolygon* polygons;
    code::Math::QInt32 polygonCount;
    const SoftwareMeshMaterial* materials;
    code::Math::QInt32 materialCount;

    SoftwareMeshView();
    SoftwareMeshView(const code::Math::Vector3D* vertices,
                     code::Math::QInt32 vertexCount,
                     const SoftwareMeshPolygon* polygons,
                     code::Math::QInt32 polygonCount,
                     const SoftwareMeshMaterial* materials,
                     code::Math::QInt32 materialCount);
};

/* One runtime mesh and its object-to-camera transform in a render batch. */
struct SoftwareMeshInstance {
    const SoftwareMeshView* mesh;
    const code::Math::Matrix* modelToCamera;

    SoftwareMeshInstance();
    SoftwareMeshInstance(const SoftwareMeshView* mesh,
                         const code::Math::Matrix* modelToCamera);
};

enum SoftwareMeshProjectionPath {
    ESoftwareMeshProjectionNearClipper = 0,
    ESoftwareMeshProjectionDirectX7 = 1
};

/* renderScene timing boundaries; projection is part of transform when the
 * optional projected-vertex cache is enabled, otherwise it is in polygon
 * queue. Draw includes per-polygon setup and rasterization. */
enum SoftwareMeshRenderPhase {
    ESoftwareMeshRenderPhaseValidation = 0,
    ESoftwareMeshRenderPhaseTransformProjection,
    ESoftwareMeshRenderPhasePolygonQueue,
    ESoftwareMeshRenderPhaseSort,
    ESoftwareMeshRenderPhaseDraw,
    ESoftwareMeshRenderPhaseCount
};

/* Optional clock and phase-duration sink for diagnostic builds. */
struct SoftwareMeshRenderInstrumentation {
    void* context;
    code::Math::QInt64 (*clockMicros)(void* context);
    void (*recordPhaseMicros)(void* context,
                              SoftwareMeshRenderPhase phase,
                              code::Math::QInt64 elapsedMicros);

    SoftwareMeshRenderInstrumentation();
};

/*
 * Per-frame state. `modelToCamera` maps source world vertices into camera
 * coordinates. `projectionPath` selects either NearClipper.project with
 * triangle near clipping or DirectX7.transformAndProjectVertices with its
 * distinct denominator and already-projected polygon draw path. The triangle
 * settings carry the game's fog/dither settings; draw mode, mip level, polygon
 * shade and the shared quad perspective decision are resolved here. A
 * horizontal floor/ceiling normal selects Java's long q=9999999 interval for
 * perspective rendering, shared by both halves of a quad.
 * The default viewport is the whole destination, using Java's inclusive
 * visibility bounds (width/height; raster kernels perform final clipping).
 */
struct SoftwareMeshRenderOptions {
    const code::Math::Matrix* modelToCamera;
    SoftwareProjectionParameters projection;
    SoftwareMeshProjectionPath projectionPath;
    RenderModeSettings quality;
    SoftwareTriangleRenderSettings triangleSettings;
    code::Math::QInt32 clipZ;
    bool useSurfaceViewport;
    code::Math::QInt32 viewportX1;
    code::Math::QInt32 viewportY1;
    code::Math::QInt32 viewportX2;
    code::Math::QInt32 viewportY2;
    code::Math::QInt32 farDistance;
    code::Math::QInt32 floorOffsetSZ;
    code::Math::QInt32 mipQuality;
    /*
     * DirectX7.camera.m03/m13/m23. Java subtracts these from camera-space
     * transformed vertex coordinates in its camera-vector-light paths.
     */
    code::Math::QInt32 cameraWorldX;
    code::Math::QInt32 cameraWorldY;
    code::Math::QInt32 cameraWorldZ;
    bool cameraVectorLight;
    code::Math::QInt32 shootIntensity;
    code::Math::QInt32 shootLength;
    bool flashlightEnabled;
    code::Math::QInt32 lightDirectionX;
    code::Math::QInt32 lightDirectionY;
    code::Math::QInt32 lightDirectionZ;
    /* Null disables all timing callbacks. */
    const SoftwareMeshRenderInstrumentation* instrumentation;
    /*
     * Set only after one full successful validation; use only with immutable
     * mesh/material/texture data for subsequent frames.
     */
    bool meshContentsPrevalidated;

    SoftwareMeshRenderOptions();
};

/* Caller-owned scratch. Capacities are counts, not byte sizes. */
struct SoftwareMeshRenderItem {
    code::Math::QInt32 instanceIndex;
    code::Math::QInt32 vertexOffset;
    code::Math::QInt32 polygonIndex;
    code::Math::QInt32 sortDepth;
    code::Math::QInt32 mipLevel;
    code::Math::QInt32 drawMode;
    bool usePerspectiveCorrection;
    bool splitQuadAlongAC;
    bool hasPackedFogColor;
    code::Math::QInt32 packedFogColor;
    code::Math::QInt32 shadeStrength;

    SoftwareMeshRenderItem();
};

struct SoftwareMeshRenderWorkspace {
    SoftwareRenderVertex* cameraVertices;
    code::Math::QInt32 cameraVertexCapacity;
    SoftwareMeshRenderItem* renderQueue;
    code::Math::QInt32 renderQueueCapacity;
    /*
     * Optional cache of projected x/y for each transformed camera vertex.
     * When supplied, it must have capacity for all scene vertices and must
     * not overlap cameraVertices; a null pointer disables the cache.
     */
    SoftwareRenderVertex* projectedVertices;
    code::Math::QInt32 projectedVertexCapacity;

    SoftwareMeshRenderWorkspace();
    SoftwareMeshRenderWorkspace(SoftwareRenderVertex* cameraVertices,
                                code::Math::QInt32 cameraVertexCapacity,
                                SoftwareMeshRenderItem* renderQueue,
                                code::Math::QInt32 renderQueueCapacity,
                                SoftwareRenderVertex* projectedVertices = 0,
                                code::Math::QInt32 projectedVertexCapacity = 0);
};

struct SoftwareMeshRenderStats {
    code::Math::QInt32 polygonsVisited;
    code::Math::QInt32 polygonsQueued;
    code::Math::QInt32 polygonsDrawn;
    code::Math::QInt32 viewportCulled;
    code::Math::QInt32 backfaceCulled;
    code::Math::QInt32 depthCulled;
    code::Math::QInt32 trianglesDrawn;
    code::Math::QInt32 perspectivePolygons;
    code::Math::QInt32 mipLevelSelections[3];

    SoftwareMeshRenderStats();
};

/*
 * Host-tested C++98 mesh traversal corresponding to DirectX7.addMesh/render
 * and RenderObjectBuffer. A batch transforms each shared vertex once and can
 * project it once when the optional projected-vertex workspace is supplied.
 * It applies Java's screen/backface/far visibility tests (plus near clipping
 * only on the NearClipper path), chooses material and mip level, and queues
 * all instances in one Java depth key and far-to-near order,
 * resolves each original polygon's perspective policy once, computes Java
 * vertex/normal lighting, then follows the selected projection path: clipped
 * camera-space NearClipper polygons or direct screen-space DirectX7 polygons.
 *
 * No allocation or ownership transfer occurs. The input, texture/mip arrays,
 * framebuffer, camera vertex scratch and queue must remain valid throughout
 * the call. Scratch capacities must cover the sum of instance vertices and
 * polygons. `render()` is the one-mesh convenience entry; `renderScene()` keeps
 * painter ordering global across the supplied instances.
 */
class SoftwareMeshRenderer {
public:
    static bool render(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareMeshView& mesh,
        const SoftwareMeshRenderOptions& options,
        const SoftwareMeshRenderWorkspace& workspace,
        SoftwareMeshRenderStats& stats);

    static bool renderScene(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareMeshInstance* instances,
        code::Math::QInt32 instanceCount,
        const SoftwareMeshRenderOptions& options,
        const SoftwareMeshRenderWorkspace& workspace,
        SoftwareMeshRenderStats& stats);
};

} // namespace Rendering
} // namespace code

#endif // QUANTUM_SOFTWARE_MESH_RENDERER_H
