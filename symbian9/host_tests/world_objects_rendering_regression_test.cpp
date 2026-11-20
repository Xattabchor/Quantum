#include "software_level_objects.h"
#include "software_mesh_animation.h"
#include "software_mesh_renderer.h"
#include "software_world_sprite_renderer.h"

#include <cstdio>
#include <cstdlib>
#include <vector>

namespace {

using code::Math::Matrix;
using code::Math::QInt32;
using code::Math::QInt64;
using code::Math::Vector3D;
using code::Rendering::ESoftwareMeshProjectionDirectX7;
using code::Rendering::ESoftwareMeshAnimationOpen;
using code::Rendering::ESoftwareMeshUnlit;
using code::Rendering::ESoftwareWorldSpriteAdd;
using code::Rendering::ESoftwareWorldSpriteAlpha;
using code::Rendering::ESoftwareWorldSpriteKeyed;
using code::Rendering::ESoftwareWorldSpriteMultiply;
using code::Rendering::SoftwareMesh3DPart;
using code::Rendering::SoftwareMeshAnimationClock;
using code::Rendering::SoftwareMeshAnimatedInstance;
using code::Rendering::SoftwareMeshAnimator;
using code::Rendering::SoftwareMeshInstance;
using code::Rendering::SoftwareMeshMaterial;
using code::Rendering::SoftwareMeshTextureFrame;
using code::Rendering::SoftwareMeshPolygon;
using code::Rendering::SoftwareMeshRenderItem;
using code::Rendering::SoftwareMeshRenderOptions;
using code::Rendering::SoftwareMeshRenderStats;
using code::Rendering::SoftwareMeshRenderWorkspace;
using code::Rendering::SoftwareMeshRenderer;
using code::Rendering::SoftwareLevelMeshAsset;
using code::Rendering::SoftwareLevelObjectLoader;
using code::Rendering::SoftwareLevelObjectRenderWorkspace;
using code::Rendering::SoftwareLevelObjectRenderer;
using code::Rendering::SoftwareLevelObjectRequirements;
using code::Rendering::SoftwareLevelObjectLoadStorage;
using code::Rendering::SoftwareLevelObjectResolvers;
using code::Rendering::SoftwareWorldMeshObjectInstance;
using code::Rendering::SoftwareMeshView;
using code::Rendering::SoftwareProjectionParameters;
using code::Rendering::SoftwareRenderSurfaceView;
using code::Rendering::SoftwareRenderVertex;
using code::Rendering::SoftwareTextureView;
using code::Rendering::SoftwareWorldSprite;
using code::Rendering::SoftwareWorldSpriteInstance;
using code::Rendering::SoftwareWorldSpriteRenderOptions;
using code::Rendering::SoftwareWorldSpriteRenderStats;
using code::Rendering::SoftwareWorldSpriteRenderer;

void require(bool condition, const char* message)
{
    if (!condition) {
        std::fprintf(stderr, "FAIL: %s\n", message);
        std::exit(1);
    }
}

bool prepareSprite(SoftwareWorldSpriteInstance& instance,
                   const Matrix& cameraToView,
                   const SoftwareProjectionParameters& projection,
                   const SoftwareWorldSpriteRenderOptions& options,
                   SoftwareWorldSpriteRenderStats& stats,
                   bool& queued)
{
    return SoftwareWorldSpriteRenderer::prepare(
        instance, &cameraToView, projection,
        0, 0, 64, 64, 80000, options, queued, stats);
}

bool matchesText(const char* value, QInt32 length, const char* expected)
{
    if (value == 0 || expected == 0 || length < 0) return false;
    QInt32 expectedLength = 0;
    while (expected[expectedLength] != '\0') ++expectedLength;
    if (length != expectedLength) return false;
    QInt32 index;
    for (index = 0; index < length; ++index) {
        if (value[index] != expected[index]) return false;
    }
    return true;
}

bool returnSetting(const char* value,
                   char* output,
                   QInt32 capacity,
                   QInt32& length)
{
    length = 0;
    while (value[length] != '\0') ++length;
    if (output == 0 || capacity <= length) return false;
    QInt32 index;
    for (index = 0; index < length; ++index) output[index] = value[index];
    output[length] = '\0';
    return true;
}

struct LevelObjectFixture {
    QInt32 meshTexel;
    QInt32 spritePixels0[16];
    QInt32 spritePixels1[16];
    SoftwareTextureView meshTexture;
    SoftwareTextureView spriteFrame0;
    SoftwareTextureView spriteFrame1;
    SoftwareMeshMaterial meshMaterial;
    SoftwareMeshPolygon meshPolygon;
    Vector3D meshVertices[6];
    SoftwareMesh3DPart meshFrames[2];
    QInt32 meshResolveCount;
    QInt32 levelAnimationTypeLookups;
    float resolvedScale;
    bool resolvedPerspectiveCorrection;
    bool resolvedRealtimeLighting;

    LevelObjectFixture()
        : meshTexel(0x0000ff00), meshTexture(), spriteFrame0(),
          spriteFrame1(), meshMaterial(), meshPolygon(),
          meshResolveCount(0), levelAnimationTypeLookups(0),
          resolvedScale(0.0f), resolvedPerspectiveCorrection(false),
          resolvedRealtimeLighting(false)
    {
        QInt32 index;
        for (index = 0; index < 16; ++index) {
            spritePixels0[index] = static_cast<QInt32>(0x80ff0000U);
            spritePixels1[index] = static_cast<QInt32>(0x800000ffU);
        }
        meshTexture = SoftwareTextureView(
            &meshTexel, 1, 1, 1, false, false, false);
        spriteFrame0 = SoftwareTextureView(
            spritePixels0, 16, 4, 4, false, false, true);
        spriteFrame1 = SoftwareTextureView(
            spritePixels1, 16, 4, 4, false, false, true);

        meshVertices[0].set(-100, 100, 0);
        meshVertices[1].set(100, 100, 0);
        meshVertices[2].set(0, -100, 0);
        meshVertices[3].set(-120, 100, 0);
        meshVertices[4].set(80, 100, 0);
        meshVertices[5].set(-20, -100, 0);
        meshFrames[0].vertexOffset = 0;
        meshFrames[0].vertexCount = 3;
        meshFrames[0].polygonOffset = 0;
        meshFrames[0].polygonCount = 1;
        meshFrames[1].vertexOffset = 3;
        meshFrames[1].vertexCount = 3;
        meshFrames[1].polygonOffset = 1;
        meshFrames[1].polygonCount = 1;

        meshPolygon.vertexCount = 3;
        meshPolygon.vertexIndices[0] = 0;
        meshPolygon.vertexIndices[1] = 1;
        meshPolygon.vertexIndices[2] = 2;
        meshPolygon.shading = ESoftwareMeshUnlit;
        meshPolygon.textureIndex = 0;
        meshMaterial.mipLevels = &meshTexture;
        meshMaterial.mipLevelCount = 1;
        meshMaterial.hasDrawModeOverride = true;
        meshMaterial.drawMode = 0;
        meshMaterial.textureAlphaMixing = false;
    }
};

bool resolveFixtureMesh(void* rawContext,
                        const char* modelPath,
                        QInt32 modelPathLength,
                        float modelScale,
                        const char* textureSpec,
                        QInt32 textureSpecLength,
                        bool perspectiveCorrection,
                        bool realtimeLighting,
                        const char* drawModes,
                        QInt32 drawModesLength,
                        SoftwareLevelMeshAsset& asset)
{
    LevelObjectFixture& fixture =
        *static_cast<LevelObjectFixture*>(rawContext);
    ++fixture.meshResolveCount;
    if (!matchesText(modelPath, modelPathLength, "test.mesh") ||
        !matchesText(textureSpec, textureSpecLength, "mesh.tex") ||
        drawModesLength != 0 || drawModes == 0) {
        return false;
    }
    fixture.resolvedScale = modelScale;
    fixture.resolvedPerspectiveCorrection = perspectiveCorrection;
    fixture.resolvedRealtimeLighting = realtimeLighting;
    asset.frameVertices = fixture.meshVertices;
    asset.frames = fixture.meshFrames;
    asset.frameCount = 2;
    asset.polygons = &fixture.meshPolygon;
    asset.polygonCount = 1;
    asset.materials = &fixture.meshMaterial;
    asset.materialCount = 1;
    return true;
}

bool resolveFixtureTexture(void* rawContext,
                           const char* texturePath,
                           QInt32 texturePathLength,
                           SoftwareTextureView& texture,
                           QInt32& textureScale)
{
    LevelObjectFixture& fixture =
        *static_cast<LevelObjectFixture*>(rawContext);
    if (matchesText(texturePath, texturePathLength, "red.qct")) {
        texture = fixture.spriteFrame0;
    } else if (matchesText(texturePath, texturePathLength, "blue.qct")) {
        texture = fixture.spriteFrame1;
    } else {
        return false;
    }
    textureScale = 2;
    return true;
}

bool resolveFixturePreset(void* rawContext,
                          const char* preset,
                          QInt32 presetLength,
                          const char* key,
                          QInt32 keyLength,
                          char* output,
                          QInt32 outputCapacity,
                          QInt32& outputLength)
{
    (void)rawContext;
    if (matchesText(preset, presetLength, "mesh-style")) {
        if (matchesText(key, keyLength, "MODEL_SCALE")) {
            return returnSetting("1.25", output, outputCapacity,
                                 outputLength);
        }
        if (matchesText(key, keyLength, "SCALE")) {
            return returnSetting("2.5", output, outputCapacity,
                                 outputLength);
        }
    } else if (matchesText(preset, presetLength, "sprite-style") &&
               matchesText(key, keyLength, "SCALE")) {
        return returnSetting("10", output, outputCapacity, outputLength);
    }
    return false;
}

bool resolveFixtureGlobal(void* rawContext,
                          const char* key,
                          QInt32 keyLength,
                          char* output,
                          QInt32 outputCapacity,
                          QInt32& outputLength)
{
    (void)rawContext;
    if (matchesText(key, keyLength, "PERS_CORRECT") ||
        matchesText(key, keyLength, "REALTIME_LIGHTING")) {
        return returnSetting("0", output, outputCapacity, outputLength);
    }
    if (matchesText(key, keyLength, "ANIMATION_TYPE")) {
        return returnSetting("2", output, outputCapacity, outputLength);
    }
    return false;
}

bool resolveFixtureLevel(void* rawContext,
                         const char* key,
                         QInt32 keyLength,
                         char* output,
                         QInt32 outputCapacity,
                         QInt32& outputLength)
{
    LevelObjectFixture& fixture =
        *static_cast<LevelObjectFixture*>(rawContext);
    if (matchesText(key, keyLength, "ANIMATION_TYPE")) {
        ++fixture.levelAnimationTypeLookups;
        return returnSetting("0", output, outputCapacity, outputLength);
    }
    if (matchesText(key, keyLength, "PERS_CORRECT") ||
        matchesText(key, keyLength, "REALTIME_LIGHTING")) {
        return returnSetting("1", output, outputCapacity, outputLength);
    }
    return false;
}

void checkSpriteProjectionAnimationAndJavaModes()
{
    const QInt32 red = static_cast<QInt32>(0xffff0000U);
    const QInt32 green = static_cast<QInt32>(0xff00ff00U);
    const SoftwareTextureView frames[2] = {
        SoftwareTextureView(&red, 1, 1, 1, false, false, false),
        SoftwareTextureView(&green, 1, 1, 1, false, false, false)
    };
    SoftwareWorldSprite sprite;
    sprite.frames = frames;
    sprite.frameCount = 2;
    sprite.scale = 145;
    sprite.textureScale = 2;
    sprite.animationSpeed = 1.0f;

    SoftwareWorldSpriteInstance instance;
    instance.sprite = &sprite;
    instance.animationBeginMillis = 1000;
    instance.position.set(0, 0, -400);
    Matrix cameraToView;
    const SoftwareProjectionParameters projection(32, 32, 64, 64);
    SoftwareWorldSpriteRenderOptions options;
    options.currentTimeMillis = 1000;
    options.gameTimeMillis = 1000;
    options.frameTime = 50;
    options.fogDistance = 800;
    options.fogColor = 0x000000ff;
    options.standardDrawMode = 0;
    SoftwareWorldSpriteRenderStats stats;
    bool queued = false;

    require(prepareSprite(instance, cameraToView, projection, options,
                          stats, queued) && queued,
            "visible world sprites enter the scene render queue");
    require(instance.frameIndex == 0 && instance.screenX1 == 23 &&
                instance.screenY1 == 12 && instance.screenX2 == 42 &&
                instance.screenY2 == 32,
            "world sprite projection matches Java DirectX7 Vertex.project");

    std::vector<QInt32> pixels(64 * 64, 0x000000ff);
    const SoftwareRenderSurfaceView surface(
        pixels.data(), static_cast<QInt32>(pixels.size()), 64, 64, 64);
    require(SoftwareWorldSpriteRenderer::draw(surface, instance, options),
            "projected world sprites render through the native compositor");
    require(pixels[22 * 64 + 32] == 0x00ff0000 &&
                pixels[40 * 64 + 32] == 0x000000ff,
            "the projected sprite rectangle writes only its visible area");

    options.currentTimeMillis = 2000;
    require(prepareSprite(instance, cameraToView, projection, options,
                          stats, queued) && queued && instance.frameIndex == 1,
            "animated SpriteObject frames follow Java's wall-clock selection");

    const bool flicker[3] = {true, true, false};
    const QInt32 animatedColors[3] = {1, 2, 3};
    sprite.flicker = flicker;
    sprite.flickerCount = 3;
    sprite.colorAnimation = animatedColors;
    sprite.colorAnimationCount = 3;
    instance.frame100 = 0;
    instance.colorInitialized = false;
    options.currentTimeMillis = 1000;
    require(prepareSprite(instance, cameraToView, projection, options,
                          stats, queued) && queued &&
                instance.currentColor == 1 && instance.frame100 == 100,
            "sprite color/flicker animation begins at Java's frame-zero phase");
    require(prepareSprite(instance, cameraToView, projection, options,
                          stats, queued) && queued &&
                instance.currentColor == 1 && instance.frame100 == 200,
            "sprite phase uses Java's 100 times frameTime over 50 update");
    require(prepareSprite(instance, cameraToView, projection, options,
                          stats, queued) && queued &&
                instance.currentColor == 2 && instance.frame100 == 300,
            "flicker and color arrays use the Java 125-tick cadence");
    require(prepareSprite(instance, cameraToView, projection, options,
                          stats, queued) && !queued &&
                instance.currentColor == 2 && instance.frame100 == 400 &&
                stats.spritesFlickered == 1,
            "a disabled flicker phase suppresses only the sprite draw");

    sprite.flicker = 0;
    sprite.flickerCount = 0;
    sprite.colorAnimation = 0;
    sprite.colorAnimationCount = 0;
    sprite.frameCount = 1;
    sprite.mode = ESoftwareWorldSpriteAlpha;
    const QInt32 halfAlphaRed = static_cast<QInt32>(0x80ff0000U);
    const SoftwareTextureView alphaTexture(
        &halfAlphaRed, 1, 1, 1, false, false, true);
    sprite.frames = &alphaTexture;
    instance.colorInitialized = false;
    instance.frame100 = 0;
    pixels.assign(64 * 64, 0x000000ff);
    require(prepareSprite(instance, cameraToView, projection, options,
                          stats, queued) && queued &&
                SoftwareWorldSpriteRenderer::draw(surface, instance, options),
            "alpha-mixed world sprites composite over the framebuffer");
    require(pixels[22 * 64 + 32] == 0x007f007e,
            "Sprite MODE=OPAQUE follows Java's packed ARGB blend");

    sprite.mode = ESoftwareWorldSpriteKeyed;
    sprite.fog = true;
    const QInt32 opaqueRed = static_cast<QInt32>(0xffff0000U);
    const SoftwareTextureView opaqueTexture(
        &opaqueRed, 1, 1, 1, false, false, false);
    sprite.frames = &opaqueTexture;
    pixels.assign(64 * 64, 0);
    require(prepareSprite(instance, cameraToView, projection, options,
                          stats, queued) && queued &&
                SoftwareWorldSpriteRenderer::draw(surface, instance, options),
            "world Sprite FOG mode draws through the sprite compositor");
    require(pixels[22 * 64 + 32] == 0x007f007e,
            "sprite fog strength follows the Java camera-depth formula");

    sprite.fog = false;
    sprite.mode = ESoftwareWorldSpriteAdd;
    const QInt32 addRed = 0x00ff0000;
    const SoftwareTextureView additiveTexture(
        &addRed, 1, 1, 1, false, false, false);
    sprite.frames = &additiveTexture;
    pixels.assign(64 * 64, 0x00000080);
    require(prepareSprite(instance, cameraToView, projection, options,
                          stats, queued) && queued &&
                SoftwareWorldSpriteRenderer::draw(surface, instance, options),
            "Sprite MODE=ADD uses the shared world compositor");
    require(pixels[22 * 64 + 32] == 0x00fe0080,
            "sprite additive mode preserves Java's packed saturating add");

    sprite.mode = ESoftwareWorldSpriteMultiply;
    sprite.baseColor = 1;
    const QInt32 brightness128 = 0x00000080;
    const SoftwareTextureView multiplyTexture(
        &brightness128, 1, 1, 1, false, false, false);
    sprite.frames = &multiplyTexture;
    instance.colorInitialized = false;
    pixels.assign(64 * 64, 0x00c83214);
    require(prepareSprite(instance, cameraToView, projection, options,
                          stats, queued) && queued &&
                SoftwareWorldSpriteRenderer::draw(surface, instance, options),
            "Sprite MODE=MUL supports Java's color-channel modes");
    require(pixels[22 * 64 + 32] == 0x00ff3214,
            "red multiply mode changes only the selected Java color channel");
}

void checkSharedPainterSortAndDepthOffsets()
{
    const QInt32 greenPixel = 0x0000ff00;
    const QInt32 redPixel = static_cast<QInt32>(0xffff0000U);
    const SoftwareTextureView meshTexture(
        &greenPixel, 1, 1, 1, false, false, false);
    const SoftwareTextureView spriteTexture(
        &redPixel, 1, 1, 1, false, false, false);
    const Vector3D vertices[3] = {
        Vector3D(-160, 160, -400),
        Vector3D(160, 160, -400),
        Vector3D(0, -160, -400)
    };
    SoftwareMeshPolygon polygon;
    polygon.vertexCount = 3;
    polygon.vertexIndices[0] = 0;
    polygon.vertexIndices[1] = 1;
    polygon.vertexIndices[2] = 2;
    polygon.shading = ESoftwareMeshUnlit;
    polygon.textureIndex = 0;
    SoftwareMeshMaterial material;
    material.mipLevels = &meshTexture;
    material.mipLevelCount = 1;
    material.hasDrawModeOverride = true;
    material.drawMode = 0;
    material.textureAlphaMixing = false;
    const SoftwareMeshView mesh(vertices, 3, &polygon, 1, &material, 1);

    Matrix cameraToView;
    const SoftwareMeshInstance meshInstance(&mesh, &cameraToView);
    SoftwareWorldSprite sprite;
    sprite.frames = &spriteTexture;
    sprite.frameCount = 1;
    sprite.scale = 145;
    sprite.textureScale = 2;
    sprite.mode = ESoftwareWorldSpriteKeyed;
    SoftwareWorldSpriteInstance spriteInstance;
    spriteInstance.sprite = &sprite;
    spriteInstance.position.set(0, 0, -600);

    SoftwareMeshRenderOptions meshOptions;
    meshOptions.projection = SoftwareProjectionParameters(32, 32, 64, 64);
    meshOptions.projectionPath = ESoftwareMeshProjectionDirectX7;
    meshOptions.farDistance = 80000;
    meshOptions.quality.setStandardDrawMode(0);
    meshOptions.quality.setPerspectiveQuality(0);
    meshOptions.quality.setTextureQuality(0);
    meshOptions.quality.setFogQuality(0);
    meshOptions.quality.setMipMapping(false);
    meshOptions.triangleSettings.fogDistance = 1;
    SoftwareWorldSpriteRenderOptions spriteOptions;
    spriteOptions.worldToCamera = &cameraToView;
    spriteOptions.currentTimeMillis = 1000;
    spriteOptions.gameTimeMillis = 1000;
    spriteOptions.frameTime = 50;

    SoftwareRenderVertex cameraVertices[3];
    SoftwareMeshRenderItem queue[2];
    const SoftwareMeshRenderWorkspace workspace(
        cameraVertices, 3, queue, 2);
    std::vector<QInt32> pixels(64 * 64, 0);
    const SoftwareRenderSurfaceView surface(
        pixels.data(), static_cast<QInt32>(pixels.size()), 64, 64, 64);
    SoftwareMeshRenderStats meshStats;
    SoftwareWorldSpriteRenderStats spriteStats;

    require(SoftwareMeshRenderer::renderSceneWithSprites(
                surface, &meshInstance, 1, &spriteInstance, 1,
                spriteOptions, meshOptions, workspace,
                meshStats, spriteStats),
            "mesh polygons and world sprites share one room render pass");
    require(meshStats.polygonsDrawn == 1 && spriteStats.spritesDrawn == 1 &&
                pixels[24 * 64 + 32] == greenPixel,
            "the nearer mesh polygon draws after a farther world sprite");

    spriteInstance.addSz = 5000;
    pixels.assign(64 * 64, 0);
    require(SoftwareMeshRenderer::renderSceneWithSprites(
                surface, &meshInstance, 1, &spriteInstance, 1,
                spriteOptions, meshOptions, workspace,
                meshStats, spriteStats),
            "SpriteObject ADDSZ participates in the shared painter sort");
    require(pixels[24 * 64 + 32] == 0x00ff0000,
            "sprite depth bias can place the billboard over a mesh polygon");

    const SoftwareMeshInstance biasedMesh(&mesh, &cameraToView, 5000);
    spriteInstance.addSz = 0;
    pixels.assign(64 * 64, 0);
    require(SoftwareMeshRenderer::renderSceneWithSprites(
                surface, &biasedMesh, 1, &spriteInstance, 1,
                spriteOptions, meshOptions, workspace,
                meshStats, spriteStats) &&
                pixels[24 * 64 + 32] == greenPixel,
            "MeshObject ADDSZ is preserved per mesh instance");

    const SoftwareMeshRenderWorkspace tooSmallWorkspace(
        cameraVertices, 3, queue, 1);
    require(!SoftwareMeshRenderer::renderSceneWithSprites(
                surface, &meshInstance, 1, &spriteInstance, 1,
                spriteOptions, meshOptions, tooSmallWorkspace,
                meshStats, spriteStats),
            "combined render queue rejects insufficient mesh-plus-sprite capacity");
}

void checkLevelObjectLoadingLifecycleAndSceneBridge()
{
    const char objectData[] =
        "[MESH]\n"
        "PRESET=mesh-style\n"
        "MODEL=test.mesh\n"
        "TEX=mesh.tex\n"
        "POS=0,0,-400;100,0,-400\n"
        "MODEL_SCALE=1.0\n"
        "ROTATE_Y=30\n"
        "ROT=0\n"
        "ANIMATION_SPEED=50\n"
        "ROOM_ID=3\n"
        "REUSETIMER=800\n"
        "HIDEREUSE=1\n"
        "VISIBLE=1\n"
        "ADDSZ=1500\n"
        "[MESH_ON_ACTIVATE]\n"
        "POS=0,0,-300\n"
        "[OBJECT_MESH]\n"
        "POS=0,0,-300\n"
        "[SPRITE_TELEPORT]\n"
        "POS=0,0,-300\n"
        "[SPRITE]\n"
        "PRESET=sprite-style\n"
        "POS=0,0,-600;100,0,-600\n"
        "TEX=red.qct,blue.qct\n"
        "OFFSET=MID\n"
        "MODE=OPAQUE\n"
        "FOG=MIX\n"
        "COLOR=0\n"
        "CUTOFF=UP\n"
        "ANIMATION_SPEED=1\n"
        "FLICKER=1,0\n"
        "COLOR_ANIM=1,2\n"
        "ROOM_ID=4\n"
        "REUSETIMER=500\n"
        "HIDEREUSE=1\n"
        "VISIBLE=1\n"
        "ADDSZ=2000\n";

    LevelObjectFixture fixture;
    SoftwareLevelObjectResolvers resolvers;
    resolvers.context = &fixture;
    resolvers.resolveMesh = resolveFixtureMesh;
    resolvers.resolveTexture = resolveFixtureTexture;
    resolvers.resolvePresetSetting = resolveFixturePreset;
    resolvers.resolveGlobalSetting = resolveFixtureGlobal;
    resolvers.resolveLevelSetting = resolveFixtureLevel;
    resolvers.standardDrawMode = 9;

    const unsigned char* const data =
        reinterpret_cast<const unsigned char*>(objectData);
    const QInt32 dataLength = static_cast<QInt32>(sizeof(objectData) - 1);
    SoftwareLevelObjectRequirements measured;
    require(SoftwareLevelObjectLoader::measure(
                data, dataLength, resolvers, measured),
            "LevelLoader object records can be measured with caller-owned assets");
    require(measured.meshObjectCount == 2 &&
                measured.spriteObjectCount == 2 &&
                measured.spriteFrameCount == 2 &&
                measured.flickerValueCount == 2 &&
                measured.colorAnimationValueCount == 2 &&
                measured.animationVertexCount == 6,
            "measure counts placements, shared sprite arrays and per-mesh morph storage");

    SoftwareWorldMeshObjectInstance meshObjects[2];
    SoftwareWorldSpriteInstance spriteObjects[2];
    SoftwareWorldSprite spriteDefinitions[2];
    SoftwareTextureView spriteFrames[2];
    bool flickerValues[2] = {false, false};
    QInt32 colorAnimationValues[2] = {0, 0};
    Vector3D animationVertices[6];
    SoftwareLevelObjectLoadStorage storage;
    storage.meshObjects = meshObjects;
    storage.meshObjectCapacity = 2;
    storage.spriteInstances = spriteObjects;
    storage.spriteInstanceCapacity = 2;
    storage.spriteDefinitions = spriteDefinitions;
    storage.spriteDefinitionCapacity = 2;
    storage.spriteFrames = spriteFrames;
    storage.spriteFrameCapacity = 2;
    storage.flickerValues = flickerValues;
    storage.flickerValueCapacity = 2;
    storage.colorAnimationValues = colorAnimationValues;
    storage.colorAnimationValueCapacity = 2;
    storage.animationVertices = animationVertices;
    storage.animationVertexCapacity = 6;

    SoftwareLevelObjectRequirements loaded;
    require(SoftwareLevelObjectLoader::load(
                data, dataLength, resolvers, storage, loaded),
            "native LevelLoader bridge materializes [MESH] and [SPRITE] objects");
    require(loaded.meshObjectCount == 2 && loaded.spriteObjectCount == 2 &&
                loaded.animationVertexCount == 6 &&
                fixture.meshResolveCount >= 3,
            "load returns exact per-placement counts after resolving model frames");
    require(fixture.resolvedScale == 2.5f &&
                fixture.resolvedPerspectiveCorrection &&
                fixture.resolvedRealtimeLighting &&
                fixture.levelAnimationTypeLookups == 0,
            "object, preset, level and global settings follow LevelLoader precedence");
    require(meshObjects[0].roomId == 3 && meshObjects[0].visible &&
                meshObjects[0].hideWhenUnusable &&
                meshObjects[0].timeToResetMillis == 800 &&
                meshObjects[0].objectToWorld.m03 == 0 &&
                meshObjects[0].objectToWorld.m23 == -400 &&
                meshObjects[0].objectToWorld.m00 == Matrix::FP &&
                meshObjects[0].animation.clock.state ==
                    code::Rendering::ESoftwareMeshAnimationClose &&
                meshObjects[0].animation.clock.animationSpeed == 50.0f,
            "MESH placement, room hint, reuse timer, transform and animation are loaded");
    require(meshObjects[0].animation.animatedVertices == animationVertices &&
                meshObjects[1].animation.animatedVertices == animationVertices + 3 &&
                meshObjects[0].mesh.vertices == meshObjects[1].mesh.vertices,
            "multi-POS meshes share immutable assets but own morph buffers");
    require(spriteDefinitions[0].scale == 10 &&
                spriteDefinitions[0].frameCount == 2 &&
                spriteDefinitions[0].mode == ESoftwareWorldSpriteAlpha &&
                spriteDefinitions[0].offsetY == -20 &&
                spriteDefinitions[0].fog &&
                spriteDefinitions[0].flicker == flickerValues &&
                spriteDefinitions[1].flicker == flickerValues &&
                flickerValues[0] && !flickerValues[1] &&
                colorAnimationValues[0] == 1 && colorAnimationValues[1] == 2,
            "SPRITE texture, preset, mode, offset, fog and shared animation data load");
    require(spriteObjects[0].roomId == 4 && spriteObjects[0].visible &&
                spriteObjects[0].hideWhenUnusable &&
                spriteObjects[0].timeToResetMillis == 500 &&
                spriteObjects[0].sprite == &spriteDefinitions[0],
            "sprite placements preserve RoomObject lifecycle and texture bindings");

    require(meshObjects[0].toggleClickAnimation(1000) &&
                meshObjects[0].animation.clock.state ==
                    code::Rendering::ESoftwareMeshAnimationOpen &&
                meshObjects[0].animation.clock.clickTimeMillis == 1000,
            "native activation preserves the current click frame and opens the mesh");
    SoftwareLevelObjectRenderer::advanceMeshAnimationClocks(
        meshObjects, 2, 50);
    require(meshObjects[0].animation.clock.frameInter == 100 &&
                meshObjects[1].animation.clock.frameInter == 100,
            "mesh frame interpolation advances on the gameplay update path");

    Matrix worldToCamera;
    Matrix staticTransform;
    staticTransform.setPosition(-200, 0, -700);
    SoftwareMeshInstance staticSceneMesh(
        &meshObjects[0].mesh, &staticTransform);
    SoftwareMeshInstance combinedMeshInstances[3];
    const SoftwareLevelObjectRenderWorkspace objectWorkspace(
        combinedMeshInstances, 3);
    SoftwareRenderVertex cameraVertices[9];
    SoftwareMeshRenderItem renderQueue[5];
    const SoftwareMeshRenderWorkspace renderWorkspace(
        cameraVertices, 9, renderQueue, 5);
    SoftwareMeshRenderOptions meshOptions;
    meshOptions.modelToCamera = &worldToCamera;
    meshOptions.projection = SoftwareProjectionParameters(32, 32, 64, 64);
    meshOptions.projectionPath = ESoftwareMeshProjectionDirectX7;
    meshOptions.farDistance = 80000;
    meshOptions.clipZ = -150;
    meshOptions.quality.setStandardDrawMode(0);
    meshOptions.quality.setPerspectiveQuality(0);
    meshOptions.quality.setTextureQuality(0);
    meshOptions.quality.setFogQuality(0);
    meshOptions.quality.setMipMapping(false);
    meshOptions.triangleSettings.fogDistance = 800;
    SoftwareWorldSpriteRenderOptions spriteOptions;
    spriteOptions.worldToCamera = &worldToCamera;
    spriteOptions.currentTimeMillis = 3000;
    spriteOptions.gameTimeMillis = 1100;
    spriteOptions.frameTime = 50;
    spriteOptions.fogDistance = 800;
    spriteOptions.fogColor = 0x000000ff;
    spriteOptions.standardDrawMode = 9;

    /* Two placements share the pattern data but retain independent Java clocks. */
    spriteObjects[1].frame100 = 125;
    std::vector<QInt32> pixels(64 * 64, 0);
    const SoftwareRenderSurfaceView surface(
        pixels.data(), static_cast<QInt32>(pixels.size()), 64, 64, 64);
    SoftwareMeshRenderStats meshStats;
    SoftwareWorldSpriteRenderStats spriteStats;
    require(SoftwareLevelObjectRenderer::render(
                surface, &staticSceneMesh, 1, meshObjects, 2,
                spriteObjects, 2, worldToCamera, meshOptions, spriteOptions,
                objectWorkspace, renderWorkspace, meshStats, spriteStats),
            "scene bridge composes static meshes, placed objects and world sprites");
    require(meshStats.polygonsDrawn >= 2 && spriteStats.spritesDrawn == 1 &&
                spriteStats.spritesFlickered == 1 &&
                meshObjects[0].animation.frameQ10 == 100 &&
                spriteObjects[0].frameIndex == 1 &&
                spriteObjects[0].frame100 == 100 &&
                spriteObjects[0].currentColor == 1 &&
                spriteObjects[1].frame100 == 225 &&
                spriteObjects[1].currentColor == 0,
            "render consumes prepared mesh animation and advances sprite frame/flicker lifecycle");

    meshObjects[0].lastActivateMillis = 1000;
    spriteObjects[0].lastActivateMillis = 1000;
    pixels.assign(64 * 64, 0);
    require(SoftwareLevelObjectRenderer::render(
                surface, &staticSceneMesh, 1, meshObjects, 2,
                spriteObjects, 2, worldToCamera, meshOptions, spriteOptions,
                objectWorkspace, renderWorkspace, meshStats, spriteStats) &&
                spriteStats.spritesHidden == 1,
            "reuse timers hide loaded scene objects through the render lifecycle");
}

void checkLevelObjectTypeBranchOrder()
{
    const char objectData[] =
        "[MESH_SHOP]\n"
        "MODEL=test.mesh\n"
        "TEX=mesh.tex\n"
        "POS=0,0,-400\n"
        "[SHOP_SPRITE]\n"
        "TEX=red.qct\n"
        "POS=0,0,-600\n";
    LevelObjectFixture fixture;
    SoftwareLevelObjectResolvers resolvers;
    resolvers.context = &fixture;
    resolvers.resolveMesh = resolveFixtureMesh;
    resolvers.resolveTexture = resolveFixtureTexture;
    resolvers.resolvePresetSetting = resolveFixturePreset;
    resolvers.resolveGlobalSetting = resolveFixtureGlobal;
    resolvers.resolveLevelSetting = resolveFixtureLevel;
    SoftwareLevelObjectRequirements requirements;
    require(SoftwareLevelObjectLoader::measure(
                reinterpret_cast<const unsigned char*>(objectData),
                static_cast<QInt32>(sizeof(objectData) - 1),
                resolvers, requirements) &&
                requirements.meshObjectCount == 1 &&
                requirements.spriteObjectCount == 1,
            "native classification follows LevelLoader order before SHOP branches");
}

void checkMeshObjectAnimation()
{
    SoftwareMesh3DPart frames[2];
    frames[0].vertexOffset = 0;
    frames[0].vertexCount = 2;
    frames[1].vertexOffset = 2;
    frames[1].vertexCount = 2;
    const Vector3D sourceVertices[4] = {
        Vector3D(32768, -20, 30), Vector3D(100, 200, 300),
        Vector3D(0, 20, -30), Vector3D(300, 400, 500)
    };
    Vector3D output[2];
    require(SoftwareMeshAnimator::interpolate(
                sourceVertices, frames, 2, 2, 512, true, output, 2),
            "multi-section .3d object keyframes interpolate without allocation");
    require(output[0].x == -16384 && output[0].y == 0 &&
                output[0].z == 0 && output[1].x == 200 &&
                output[1].y == 300 && output[1].z == 400,
            "MeshObject morphing uses signed-short frames and 10-bit blending");
    require(SoftwareMeshAnimator::interpolate(
                sourceVertices, frames, 2, 2, 512, false, output, 2) &&
                output[0].x == -32768 && output[0].y == -20,
            "disabled morphing selects the current Java keyframe directly");

    SoftwareMeshAnimationClock clock;
    clock.frameInter = 1000;
    clock.animationSpeed = 50.0f;
    QInt32 frameQ10 = -1;
    require(SoftwareMeshAnimator::frameForState(clock, 3, frameQ10) &&
                frameQ10 == 500,
            "loop animation uses GameObject.frameInter and MeshObject speed");
    clock.state = code::Rendering::ESoftwareMeshAnimationOpen;
    clock.clickAnim = 1900;
    clock.clickTimeMillis = 1000;
    clock.gameTimeMillis = 1100;
    clock.animationSpeed = 100.0f;
    require(SoftwareMeshAnimator::frameForState(clock, 3, frameQ10) &&
                frameQ10 == 2048,
            "open animation clamps to the last Morphing frame");
    clock.state = code::Rendering::ESoftwareMeshAnimationClose;
    clock.clickAnim = 100;
    clock.gameTimeMillis = 2000;
    require(SoftwareMeshAnimator::frameForState(clock, 3, frameQ10) &&
                frameQ10 == 0,
            "close animation clamps to frame zero like MeshObject.setFrame");

    const QInt32 animatedTexel = 0x0000ff00;
    const SoftwareTextureView animatedTexture(
        &animatedTexel, 1, 1, 1, false, false, false);
    SoftwareMeshMaterial animatedMaterial;
    animatedMaterial.mipLevels = &animatedTexture;
    animatedMaterial.mipLevelCount = 1;
    animatedMaterial.hasDrawModeOverride = true;
    animatedMaterial.drawMode = 0;
    animatedMaterial.textureAlphaMixing = false;
    SoftwareMeshPolygon animatedPolygon;
    animatedPolygon.vertexCount = 3;
    animatedPolygon.vertexIndices[0] = 0;
    animatedPolygon.vertexIndices[1] = 1;
    animatedPolygon.vertexIndices[2] = 2;
    animatedPolygon.shading = ESoftwareMeshUnlit;
    animatedPolygon.textureIndex = 0;
    SoftwareMesh3DPart mutableRenderFrames[2];
    mutableRenderFrames[0].vertexOffset = 0;
    mutableRenderFrames[0].vertexCount = 3;
    mutableRenderFrames[1].vertexOffset = 3;
    mutableRenderFrames[1].vertexCount = 3;
    const Vector3D renderFrameVertices[6] = {
        Vector3D(-160, 160, -400), Vector3D(160, 160, -400),
        Vector3D(0, -160, -400), Vector3D(-96, 160, -400),
        Vector3D(224, 160, -400), Vector3D(64, -160, -400)
    };
    const SoftwareMeshView sourceView(
        renderFrameVertices, 3, &animatedPolygon, 1,
        &animatedMaterial, 1);
    Matrix animatedTransform;
    const SoftwareMeshInstance sourceInstance(
        &sourceView, &animatedTransform, 1200);
    SoftwareMeshAnimatedInstance animatedInstance;
    animatedInstance.sourceInstance = &sourceInstance;
    animatedInstance.frameVertices = renderFrameVertices;
    animatedInstance.frames = mutableRenderFrames;
    animatedInstance.frameCount = 2;
    animatedInstance.clock.frameInter = 1024;
    animatedInstance.clock.animationSpeed = 50.0f;
    Vector3D animatedVertexBuffer[3];
    animatedInstance.animatedVertices = animatedVertexBuffer;
    animatedInstance.animatedVertexCapacity = 3;
    require(SoftwareMeshAnimator::prepareInstance(animatedInstance) &&
                animatedInstance.frameQ10 == 512 &&
                animatedInstance.animatedVertices[0].x == -128 &&
                animatedInstance.renderInstance.mesh ==
                    &animatedInstance.animatedMesh &&
                animatedInstance.renderInstance.depthBias == 1200,
            "per-placement Morphing prepares a renderable animated mesh instance");

    animatedInstance.clock.state = ESoftwareMeshAnimationOpen;
    animatedInstance.clock.clickAnim = 1900;
    animatedInstance.clock.clickTimeMillis = 100;
    animatedInstance.clock.gameTimeMillis = 200;
    animatedInstance.clock.animationSpeed = 100.0f;
    require(SoftwareMeshAnimator::prepareInstance(animatedInstance) &&
                animatedInstance.frameQ10 == 1024,
            "two-frame open animation clamps to Java's final Morphing frame");
    animatedInstance.clock = SoftwareMeshAnimationClock();
    animatedInstance.clock.frameInter = 1024;
    animatedInstance.clock.animationSpeed = 50.0f;
    require(SoftwareMeshAnimator::prepareInstance(animatedInstance),
            "loop state can continue after a trigger animation");
    require(renderFrameVertices[0].x == -160 &&
                animatedInstance.animatedMesh.vertices == animatedVertexBuffer,
            "animation writes caller-owned vertices without mutating keyframes");

    SoftwareMeshRenderOptions animatedOptions;
    animatedOptions.projection = SoftwareProjectionParameters(32, 32, 64, 64);
    animatedOptions.projectionPath = ESoftwareMeshProjectionDirectX7;
    animatedOptions.farDistance = 80000;
    animatedOptions.quality.setStandardDrawMode(0);
    animatedOptions.quality.setPerspectiveQuality(0);
    animatedOptions.quality.setTextureQuality(0);
    animatedOptions.quality.setFogQuality(0);
    animatedOptions.quality.setMipMapping(false);
    animatedOptions.triangleSettings.fogDistance = 1;
    SoftwareRenderVertex animatedCameraVertices[3];
    SoftwareMeshRenderItem animatedQueue[1];
    const SoftwareMeshRenderWorkspace animatedWorkspace(
        animatedCameraVertices, 3, animatedQueue, 1);
    std::vector<QInt32> referencePixels(64 * 64, 0);
    const SoftwareRenderSurfaceView referenceSurface(
        referencePixels.data(), static_cast<QInt32>(referencePixels.size()),
        64, 64, 64);
    SoftwareMeshRenderStats animatedStats;
    require(SoftwareMeshRenderer::renderScene(
                referenceSurface, &sourceInstance, 1, animatedOptions,
                animatedWorkspace, animatedStats) &&
                animatedStats.polygonsDrawn == 1,
            "the unanimated Java keyframe renders through the mesh batch");

    std::vector<QInt32> animatedPixels(64 * 64, 0);
    const SoftwareRenderSurfaceView animatedSurface(
        animatedPixels.data(), static_cast<QInt32>(animatedPixels.size()),
        64, 64, 64);
    require(SoftwareMeshRenderer::renderScene(
                animatedSurface, &animatedInstance.renderInstance, 1,
                animatedOptions, animatedWorkspace, animatedStats) &&
                animatedStats.polygonsDrawn == 1 &&
                animatedPixels != referencePixels,
            "the prepared MeshObject keyframe is consumed by the software renderer");
}

void checkMeshTextureAnimation()
{
    const QInt32 baseColors[3] = {
        static_cast<QInt32>(0x00010101U),
        static_cast<QInt32>(0x00020202U),
        static_cast<QInt32>(0x00030303U)
    };
    const QInt32 mipColors[3] = {
        static_cast<QInt32>(0x00ff0000U),
        static_cast<QInt32>(0x0000ff00U),
        static_cast<QInt32>(0x000000ffU)
    };
    std::vector<QInt32> basePixels[3];
    std::vector<QInt32> mipPixels[3];
    SoftwareTextureView frameMipLevels[3][2];
    SoftwareMeshTextureFrame animationFrames[3];
    QInt32 frameIndex;
    for (frameIndex = 0; frameIndex < 3; ++frameIndex) {
        basePixels[frameIndex].assign(128 * 128, baseColors[frameIndex]);
        mipPixels[frameIndex].assign(64 * 64, mipColors[frameIndex]);
        frameMipLevels[frameIndex][0] = SoftwareTextureView(
            basePixels[frameIndex].data(), 128 * 128, 128, 128,
            false, false, false);
        frameMipLevels[frameIndex][1] = SoftwareTextureView(
            mipPixels[frameIndex].data(), 64 * 64, 64, 64,
            false, false, false);
        animationFrames[frameIndex] = SoftwareMeshTextureFrame(
            frameMipLevels[frameIndex], 2);
    }

    const Vector3D vertices[3] = {
        Vector3D(-160, 160, -400),
        Vector3D(160, 160, -400),
        Vector3D(0, -160, -400)
    };
    SoftwareMeshPolygon polygon;
    polygon.vertexCount = 3;
    polygon.vertexIndices[0] = 0;
    polygon.vertexIndices[1] = 1;
    polygon.vertexIndices[2] = 2;
    polygon.textureU[0] = 0;
    polygon.textureU[1] = 255;
    polygon.textureU[2] = 128;
    polygon.textureV[0] = 0;
    polygon.textureV[1] = 0;
    polygon.textureV[2] = 255;
    polygon.shading = ESoftwareMeshUnlit;
    polygon.textureIndex = 0;

    SoftwareMeshMaterial material;
    material.animationFrames = animationFrames;
    material.animationFrameCount = 3;
    material.animationSpeed = 2.0f;
    material.hasDrawModeOverride = true;
    material.drawMode = 0;
    material.textureAlphaMixing = false;
    const SoftwareMeshView mesh(
        vertices, 3, &polygon, 1, &material, 1);
    Matrix worldToCamera;
    const SoftwareMeshInstance instance(&mesh, &worldToCamera);

    SoftwareMeshRenderOptions options;
    options.projection = SoftwareProjectionParameters(32, 32, 64, 64);
    options.projectionPath = ESoftwareMeshProjectionDirectX7;
    options.farDistance = 80000;
    options.quality.setStandardDrawMode(0);
    options.quality.setPerspectiveQuality(0);
    options.quality.setTextureQuality(0);
    options.quality.setFogQuality(0);
    options.quality.setMipMapping(true);
    options.triangleSettings.fogDistance = 1;

    SoftwareRenderVertex cameraVertices[3];
    SoftwareMeshRenderItem queue[1];
    const SoftwareMeshRenderWorkspace workspace(
        cameraVertices, 3, queue, 1);
    std::vector<QInt32> pixels(64 * 64, 0);
    const SoftwareRenderSurfaceView surface(
        pixels.data(), static_cast<QInt32>(pixels.size()), 64, 64, 64);
    SoftwareMeshRenderStats stats;
    const QInt64 times[5] = {0, 500, 1000, 1500, 2000};
    const QInt32 expectedMipColors[5] = {
        mipColors[0], mipColors[1], mipColors[2],
        mipColors[0], mipColors[1]
    };
    QInt32 timeIndex;
    for (timeIndex = 0; timeIndex < 5; ++timeIndex) {
        QInt32 pixel;
        for (pixel = 0; pixel < 64 * 64; ++pixel) pixels[pixel] = 0;
        options.textureAnimationTimeMillis = times[timeIndex];
        require(SoftwareMeshRenderer::renderScene(
                    surface, &instance, 1, options, workspace, stats) &&
                    stats.polygonsDrawn == 1 &&
                    stats.mipLevelSelections[1] == 1,
                "animated mesh selects and draws the current frame's mip chain");
        require(pixels[32 * 64 + 32] == expectedMipColors[timeIndex],
                "mesh texture animation matches Java's absolute-time frame cycle");
    }

    QInt32 riverTexels[4];
    SoftwareTextureView riverMipLevels[4];
    SoftwareMeshTextureFrame riverAnimationFrames[4];
    const QInt64 riverTimes[8] = { 0, 166, 167, 333, 334, 500, 665, 666 };
    const QInt32 riverFrameAtTime[8] = { 0, 0, 1, 1, 2, 3, 3, 0 };
    const QInt32 riverColors[4] = {
        static_cast<QInt32>(0x00112233U),
        static_cast<QInt32>(0x00445566U),
        static_cast<QInt32>(0x00778899U),
        static_cast<QInt32>(0x00aabbccU)
    };
    QInt32 riverIndex;
    for (riverIndex = 0; riverIndex < 4; ++riverIndex) {
        riverTexels[riverIndex] = riverColors[riverIndex];
        riverMipLevels[riverIndex] = SoftwareTextureView(
            &riverTexels[riverIndex], 1, 1, 1, false, false, false);
        riverAnimationFrames[riverIndex] = SoftwareMeshTextureFrame(
            &riverMipLevels[riverIndex], 1);
    }
    SoftwareMeshMaterial riverMaterial;
    riverMaterial.animationFrames = riverAnimationFrames;
    riverMaterial.animationFrameCount = 4;
    riverMaterial.animationSpeed = 6.0f;
    riverMaterial.hasDrawModeOverride = true;
    riverMaterial.drawMode = 0;
    riverMaterial.textureAlphaMixing = false;
    const SoftwareMeshView riverMesh(
        vertices, 3, &polygon, 1, &riverMaterial, 1);
    const SoftwareMeshInstance riverInstance(&riverMesh, &worldToCamera);
    QInt32 riverTimeIndex;
    for (riverTimeIndex = 0; riverTimeIndex < 8; ++riverTimeIndex) {
        QInt32 pixel;
        for (pixel = 0; pixel < 64 * 64; ++pixel) pixels[pixel] = 0;
        options.textureAnimationTimeMillis = riverTimes[riverTimeIndex];
        require(SoftwareMeshRenderer::renderScene(
                    surface, &riverInstance, 1, options,
                    workspace, stats) && stats.polygonsDrawn == 1,
                "four-frame river material renders at the configured six FPS");
        require(pixels[32 * 64 + 32] ==
                    riverColors[riverFrameAtTime[riverTimeIndex]],
                "six-FPS river animation follows Java's four-frame cycle and wrap");
    }

    SoftwareWorldSpriteRenderOptions spriteClock;
    spriteClock.currentTimeMillis = 1000;
    spriteClock.gameTimeMillis = 1000;
    SoftwareWorldSpriteRenderStats spriteStats;
    options.textureAnimationTimeMillis = -1;
    QInt32 pixel;
    for (pixel = 0; pixel < 64 * 64; ++pixel) pixels[pixel] = 0;
    require(SoftwareMeshRenderer::renderSceneWithSprites(
                surface, &instance, 1, 0, 0, spriteClock, options,
                workspace, stats, spriteStats) &&
                pixels[32 * 64 + 32] == mipColors[2],
            "combined Level-object rendering shares the sprite wall clock for mesh textures");

    QInt32 opaquePixel = static_cast<QInt32>(0xffff0000U);
    QInt32 alphaPixel = static_cast<QInt32>(0x80ff0000U);
    SoftwareTextureView alphaTextureViews[2];
    alphaTextureViews[0] = SoftwareTextureView(
        &opaquePixel, 1, 1, 1, false, false, false);
    alphaTextureViews[1] = SoftwareTextureView(
        &alphaPixel, 1, 1, 1, false, false, true);
    SoftwareMeshTextureFrame alphaFrames[2];
    alphaFrames[0] = SoftwareMeshTextureFrame(&alphaTextureViews[0], 1);
    alphaFrames[1] = SoftwareMeshTextureFrame(&alphaTextureViews[1], 1);
    SoftwareMeshMaterial alphaMaterial;
    alphaMaterial.animationFrames = alphaFrames;
    alphaMaterial.animationFrameCount = 2;
    alphaMaterial.animationSpeed = 1.0f;
    alphaMaterial.hasDrawModeOverride = true;
    alphaMaterial.drawMode = 4;
    alphaMaterial.textureAlphaMixing = false;
    const SoftwareMeshView alphaMesh(
        vertices, 3, &polygon, 1, &alphaMaterial, 1);
    const SoftwareMeshInstance alphaInstance(&alphaMesh, &worldToCamera);
    options.quality.setMipMapping(false);
    options.textureAnimationTimeMillis = 0;
    for (pixel = 0; pixel < 64 * 64; ++pixel) {
        pixels[pixel] = static_cast<QInt32>(0x000000ffU);
    }
    require(SoftwareMeshRenderer::renderScene(
                surface, &alphaInstance, 1, options, workspace, stats),
            "opaque animated texture frame renders");
    const QInt32 opaqueResult = pixels[32 * 64 + 32];
    require(((static_cast<code::Math::QUint32>(opaqueResult) >> 16) & 255U) == 255U &&
                (static_cast<code::Math::QUint32>(opaqueResult) & 255U) == 0U,
            "texture frame without alpha mixing keeps drawmode 4's Java opaque fallback");

    options.textureAnimationTimeMillis = 1000;
    for (pixel = 0; pixel < 64 * 64; ++pixel) {
        pixels[pixel] = static_cast<QInt32>(0x000000ffU);
    }
    require(SoftwareMeshRenderer::renderScene(
                surface, &alphaInstance, 1, options, workspace, stats),
            "alpha-mixing animated texture frame renders");
    const code::Math::QUint32 mixedResult =
        static_cast<code::Math::QUint32>(pixels[32 * 64 + 32]);
    require(((mixedResult >> 16) & 255U) < 255U &&
                (mixedResult & 255U) > 0U,
            "selected texture frame supplies its own Java RawImage alphaMixing flag");
}

} // namespace

int main()
{
    checkSpriteProjectionAnimationAndJavaModes();
    checkSharedPainterSortAndDepthOffsets();
    checkLevelObjectLoadingLifecycleAndSceneBridge();
    checkLevelObjectTypeBranchOrder();
    checkMeshObjectAnimation();
    checkMeshTextureAnimation();
    std::puts("World-object rendering regressions passed: sprite projection/animation, mesh texture animation/mips, fog/modes, shared mesh/sprite painter sort, depth offsets, morphing.");
    return 0;
}
