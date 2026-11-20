#ifndef QUANTUM_SOFTWARE_LEVEL_OBJECTS_H
#define QUANTUM_SOFTWARE_LEVEL_OBJECTS_H

#include "software_mesh_animation.h"
#include "software_world_sprite_renderer.h"

namespace code {
namespace Rendering {

/*
 * Non-owning asset view supplied by the platform's resource/cache layer for
 * one Java LevelLoader [MESH] MODEL/TEX tuple. `frameVertices` and `frames`
 * are the concatenated output of SoftwareMesh3DParser. `polygons` points at
 * frames[0]'s topology (the resolver applies frames[0].polygonOffset before
 * returning it), while `polygonCount` and materials describe the base mesh.
 * A material resolver may populate SoftwareMeshMaterial::animationFrames
 * from Java MultyTexture's animated TEX entry; each frame includes its own
 * decoded mip chain, while the core renderer selects the frame by wall clock.
 */
struct SoftwareLevelMeshAsset {
    const code::Math::Vector3D* frameVertices;
    const SoftwareMesh3DPart* frames;
    code::Math::QInt32 frameCount;
    const SoftwareMeshPolygon* polygons;
    code::Math::QInt32 polygonCount;
    const SoftwareMeshMaterial* materials;
    code::Math::QInt32 materialCount;

    SoftwareLevelMeshAsset();
};

/* Resource callbacks keep E32 file I/O, image decoding, and asset caching out
 * of the allocation-free parser/renderer. String arguments are not
 * NUL-terminated; callbacks must use the explicit lengths. Mesh TEX is passed
 * through in Java MultyTexture syntax; a resolver supporting animated entries
 * maps each speed:frame0:frame1... sequence to material.animationFrames. */
typedef bool (*SoftwareLevelMeshAssetResolver)(
    void* context,
    const char* modelPath,
    code::Math::QInt32 modelPathLength,
    float modelScale,
    const char* textureSpec,
    code::Math::QInt32 textureSpecLength,
    bool perspectiveCorrection,
    bool realtimeLighting,
    const char* drawModes,
    code::Math::QInt32 drawModesLength,
    SoftwareLevelMeshAsset& asset);

typedef bool (*SoftwareLevelTextureResolver)(
    void* context,
    const char* texturePath,
    code::Math::QInt32 texturePathLength,
    SoftwareTextureView& texture,
    code::Math::QInt32& textureScale);

typedef bool (*SoftwareLevelObjectSettingResolver)(
    void* context,
    const char* preset,
    code::Math::QInt32 presetLength,
    const char* key,
    code::Math::QInt32 keyLength,
    char* output,
    code::Math::QInt32 outputCapacity,
    code::Math::QInt32& outputLength);

typedef bool (*SoftwareLevelSettingResolver)(
    void* context,
    const char* key,
    code::Math::QInt32 keyLength,
    char* output,
    code::Math::QInt32 outputCapacity,
    code::Math::QInt32& outputLength);

struct SoftwareLevelObjectResolvers {
    void* context;
    SoftwareLevelMeshAssetResolver resolveMesh;
    SoftwareLevelTextureResolver resolveTexture;
    /* Exact grouped value from Main.settings; global fallback is separate. */
    SoftwareLevelObjectSettingResolver resolvePresetSetting;
    /* Main.settings.get(key), used after any Level.ini fallback. */
    SoftwareLevelSettingResolver resolveGlobalSetting;
    /* Level.ini value, preferred over global settings for Java fields whose
     * LevelLoader overload explicitly accepts lvl (e.g. PERS_CORRECT and
     * REALTIME_LIGHTING). */
    SoftwareLevelSettingResolver resolveLevelSetting;
    /* DirectX7.standartDrawmode captured while LevelLoader creates Sprites. */
    code::Math::QInt32 standardDrawMode;

    SoftwareLevelObjectResolvers();
};

/* One placed MeshObject. The mutable transform and animation clock belong to
 * this placement; keyframes, polygons, materials, and textures may be shared
 * by all placements created from the same LevelLoader record. */
struct SoftwareWorldMeshObjectInstance {
    SoftwareMeshView mesh;
    SoftwareMeshInstance sourceInstance;
    code::Math::Matrix objectToWorld;
    code::Math::Matrix modelToCamera;
    SoftwareMeshAnimatedInstance animation;
    code::Math::QInt32 addSz;
    /* ROOM_ID / Java RoomObject.getNewPart() hint; room partitioning is external. */
    code::Math::QInt32 roomId;
    bool visible;
    bool hideWhenUnusable;
    code::Math::QInt64 lastActivateMillis;
    code::Math::QInt64 timeToResetMillis;

    SoftwareWorldMeshObjectInstance();

    void setPosition(code::Math::QInt32 x,
                     code::Math::QInt32 y,
                     code::Math::QInt32 z);
    void setRotationY(code::Math::QInt32 degrees);
    /* Mimic MeshObject.activate(): preserve the current frame and toggle OPEN/CLOSE. */
    bool toggleClickAnimation(code::Math::QInt64 gameTimeMillis);

private:
    SoftwareWorldMeshObjectInstance(
        const SoftwareWorldMeshObjectInstance&);
    SoftwareWorldMeshObjectInstance& operator=(
        const SoftwareWorldMeshObjectInstance&);
};

/* The Java LevelLoader fields the native loader materializes. Counts for
 * sprite frames/flicker/color arrays are per source record; placements from a
 * multi-POS record share those immutable arrays, but keep independent timing
 * and color state. animationVertexCount is the exact per-placement morph-buffer
 * size; measure() resolves mesh metadata to provide it before allocation. */
struct SoftwareLevelObjectRequirements {
    code::Math::QInt32 meshObjectCount;
    code::Math::QInt32 spriteObjectCount;
    code::Math::QInt32 spriteFrameCount;
    code::Math::QInt32 flickerValueCount;
    code::Math::QInt32 colorAnimationValueCount;
    code::Math::QInt32 animationVertexCount;

    SoftwareLevelObjectRequirements();
};

/* Caller-owned storage. All arrays and buffers must remain alive while the
 * loaded objects are rendered. meshObjects/spriteInstances are output arrays;
 * spriteDefinitions has one element per sprite placement. */
struct SoftwareLevelObjectLoadStorage {
    SoftwareWorldMeshObjectInstance* meshObjects;
    code::Math::QInt32 meshObjectCapacity;
    SoftwareWorldSpriteInstance* spriteInstances;
    code::Math::QInt32 spriteInstanceCapacity;
    SoftwareWorldSprite* spriteDefinitions;
    code::Math::QInt32 spriteDefinitionCapacity;
    SoftwareTextureView* spriteFrames;
    code::Math::QInt32 spriteFrameCapacity;
    bool* flickerValues;
    code::Math::QInt32 flickerValueCapacity;
    code::Math::QInt32* colorAnimationValues;
    code::Math::QInt32 colorAnimationValueCapacity;
    code::Math::Vector3D* animationVertices;
    code::Math::QInt32 animationVertexCapacity;

    SoftwareLevelObjectLoadStorage();
};

/* Counts only LevelLoader's [SPRITE]/[MESH] object branches. Script records
 * such as [MESH_ON_ACTIVATE] and higher-priority Java types (NPC, OBJECT,
 * TELEPORT, etc.) are deliberately ignored; SPRITE is tested before MESH, as
 * in LevelLoader. No allocation or file I/O is performed here. measure() calls
 * the mesh resolver to obtain exact morph-buffer sizes; resolvers should cache
 * and return stable asset views for the subsequent load(). */
class SoftwareLevelObjectLoader {
public:
    static bool measure(const unsigned char* data,
                        code::Math::QInt32 dataLength,
                        const SoftwareLevelObjectResolvers& resolvers,
                        SoftwareLevelObjectRequirements& requirements);

    /* Build per-placement instances. The asset callbacks resolve resources;
     * this function owns no memory and performs no Symbian file operations. */
    static bool load(const unsigned char* data,
                     code::Math::QInt32 dataLength,
                     const SoftwareLevelObjectResolvers& resolvers,
                     const SoftwareLevelObjectLoadStorage& storage,
                     SoftwareLevelObjectRequirements& requirements);
};

/* Combined temporary batch: static scene meshes followed by prepared objects. */
struct SoftwareLevelObjectRenderWorkspace {
    SoftwareMeshInstance* meshInstances;
    code::Math::QInt32 meshInstanceCapacity;

    SoftwareLevelObjectRenderWorkspace();
    SoftwareLevelObjectRenderWorkspace(
        SoftwareMeshInstance* meshInstances,
        code::Math::QInt32 meshInstanceCapacity);
};

/* Scene bridge used once per camera render. Mesh animation clocks are updated
 * by game/update code via advanceMeshAnimationClocks(), not by render(),
 * matching GameObject.update() versus MeshObject.render(). Sprite frame and
 * flicker clocks are advanced by SoftwareWorldSpriteRenderer in render(). */
class SoftwareLevelObjectRenderer {
public:
    static void advanceMeshAnimationClocks(
        SoftwareWorldMeshObjectInstance* objects,
        code::Math::QInt32 objectCount,
        code::Math::QInt32 frameTime);

    static bool render(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareMeshInstance* sceneMeshInstances,
        code::Math::QInt32 sceneMeshInstanceCount,
        SoftwareWorldMeshObjectInstance* meshObjects,
        code::Math::QInt32 meshObjectCount,
        SoftwareWorldSpriteInstance* spriteObjects,
        code::Math::QInt32 spriteObjectCount,
        const code::Math::Matrix& worldToCamera,
        const SoftwareMeshRenderOptions& meshOptions,
        const SoftwareWorldSpriteRenderOptions& spriteOptions,
        const SoftwareLevelObjectRenderWorkspace& objectWorkspace,
        const SoftwareMeshRenderWorkspace& renderWorkspace,
        SoftwareMeshRenderStats& meshStats,
        SoftwareWorldSpriteRenderStats& spriteStats);
};

} // namespace Rendering
} // namespace code

#endif // QUANTUM_SOFTWARE_LEVEL_OBJECTS_H
