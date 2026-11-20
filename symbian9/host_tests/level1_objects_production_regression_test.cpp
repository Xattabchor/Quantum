#include "quantum_integer.h"
#include "software_level_objects.h"
#include "software_mesh_3d_parser.h"
#include "software_mesh_renderer.h"
#include "software_world_sprite_renderer.h"

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

namespace {

using code::Math::Matrix;
using code::Math::QInt32;
using code::Math::Vector3D;
using code::Rendering::ESoftwareMeshProjectionDirectX7;
using code::Rendering::ESoftwareWorldSpriteKeyed;
using code::Rendering::SoftwareLevelMeshAsset;
using code::Rendering::SoftwareLevelObjectLoadStorage;
using code::Rendering::SoftwareLevelObjectLoader;
using code::Rendering::SoftwareLevelObjectRenderWorkspace;
using code::Rendering::SoftwareLevelObjectRenderer;
using code::Rendering::SoftwareLevelObjectRequirements;
using code::Rendering::SoftwareLevelObjectResolvers;
using code::Rendering::SoftwareMesh3DParser;
using code::Rendering::SoftwareMesh3DPart;
using code::Rendering::SoftwareMesh3DRequirements;
using code::Rendering::SoftwareMeshInstance;
using code::Rendering::SoftwareMeshMaterial;
using code::Rendering::SoftwareMeshPolygon;
using code::Rendering::SoftwareMeshRenderItem;
using code::Rendering::SoftwareMeshRenderOptions;
using code::Rendering::SoftwareMeshRenderStats;
using code::Rendering::SoftwareMeshRenderWorkspace;
using code::Rendering::SoftwareProjectionParameters;
using code::Rendering::SoftwareRenderSurfaceView;
using code::Rendering::SoftwareRenderVertex;
using code::Rendering::SoftwareTextureView;
using code::Rendering::SoftwareWorldMeshObjectInstance;
using code::Rendering::SoftwareWorldSpriteInstance;
using code::Rendering::SoftwareWorldSpriteRenderOptions;
using code::Rendering::SoftwareWorldSpriteRenderStats;

void require(bool condition, const char* message)
{
    if (!condition) {
        std::fprintf(stderr, "FAIL: %s\n", message);
        std::exit(1);
    }
}

std::vector<unsigned char> readFile(const std::string& path)
{
    std::ifstream input(path.c_str(), std::ios::binary);
    require(input.good(), "open Level 1 object asset");
    return std::vector<unsigned char>(
        std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>());
}

struct DecodedTexture {
    std::vector<QInt32> pixels;
    SoftwareTextureView view;

    DecodedTexture() : pixels(), view() {}
};

bool decodeQtx(const std::string& path, DecodedTexture& output)
{
    const std::vector<unsigned char> bytes = readFile(path);
    if (bytes.size() < 8 || bytes[0] != 'Q' || bytes[1] != 'T' ||
        bytes[2] != 'X' || bytes[3] != '1') {
        return false;
    }
    const QInt32 width = static_cast<QInt32>(bytes[4]) |
        (static_cast<QInt32>(bytes[5]) << 8);
    const QInt32 height = static_cast<QInt32>(bytes[6]) |
        (static_cast<QInt32>(bytes[7]) << 8);
    if (width <= 0 || height <= 0 ||
        bytes.size() != static_cast<std::size_t>(8 + width * height * 4)) {
        return false;
    }

    const QInt32 pixelCount = width * height;
    output.pixels.resize(static_cast<std::size_t>(pixelCount));
    bool hasTransparentTexels = false;
    bool hasAlphaMixing = false;
    for (QInt32 index = 0; index < pixelCount; ++index) {
        const unsigned char* rgba = &bytes[8 + index * 4];
        const QInt32 alpha = rgba[3];
        const code::Math::QUint32 color = alpha == 0
            ? 0U
            : (static_cast<code::Math::QUint32>(alpha) << 24U) |
              (static_cast<code::Math::QUint32>(rgba[0]) << 16U) |
              (static_cast<code::Math::QUint32>(rgba[1]) << 8U) |
              static_cast<code::Math::QUint32>(rgba[2]);
        output.pixels[static_cast<std::size_t>(index)] =
            code::Math::javaIntFromUnsigned(color);
        if (alpha == 0) hasTransparentTexels = true;
        else if (alpha < 255) hasAlphaMixing = true;
    }
    output.view = SoftwareTextureView(
        output.pixels.data(), pixelCount, width, height,
        hasTransparentTexels, hasTransparentTexels, hasAlphaMixing);
    return true;
}

bool matchesText(const char* value, QInt32 length, const char* expected)
{
    if (value == 0 || expected == 0 || length < 0) return false;
    QInt32 expectedLength = 0;
    while (expected[expectedLength] != '\0') ++expectedLength;
    if (length != expectedLength) return false;
    for (QInt32 index = 0; index < length; ++index) {
        if (value[index] != expected[index]) return false;
    }
    return true;
}

struct Level1ResourceCache {
    std::string profile;
    std::vector<unsigned char> modelBytes;
    SoftwareMesh3DRequirements modelRequirements;
    std::vector<Vector3D> frameVertices;
    std::vector<SoftwareMeshPolygon> polygons;
    std::vector<SoftwareMesh3DPart> frames;
    DecodedTexture meshTexture;
    DecodedTexture spriteTexture;
    SoftwareMeshMaterial meshMaterial;
    QInt32 meshResolveCalls;
    float parsedScale;
    bool meshReady;

    explicit Level1ResourceCache(const std::string& path)
        : profile(path), modelBytes(), modelRequirements(), frameVertices(),
          polygons(), frames(), meshTexture(), spriteTexture(), meshMaterial(),
          meshResolveCalls(0), parsedScale(0.0f), meshReady(false)
    {
    }

    bool loadMesh(float scale, bool perspectiveCorrection)
    {
        if (meshReady) {
            return scale == parsedScale &&
                   perspectiveCorrection == meshMaterial.perspectiveCorrection;
        }
        if (scale != 2.0f ||
            !decodeQtx(profile + "/prop/mdy.qtx", meshTexture)) {
            return false;
        }
        modelBytes = readFile(profile + "/prop/md.3d");
        if (modelBytes.size() > 2147483647U ||
            !SoftwareMesh3DParser::measure(
                modelBytes.data(), static_cast<QInt32>(modelBytes.size()),
                modelRequirements) ||
            modelRequirements.meshCount != 1 ||
            modelRequirements.vertexCount != 16 ||
            modelRequirements.polygonCount != 9 ||
            modelRequirements.materialCount != 1) {
            return false;
        }
        frameVertices.resize(static_cast<std::size_t>(modelRequirements.vertexCount));
        polygons.resize(static_cast<std::size_t>(modelRequirements.polygonCount));
        frames.resize(static_cast<std::size_t>(modelRequirements.meshCount));
        SoftwareMesh3DRequirements parsed;
        if (!SoftwareMesh3DParser::parse(
                modelBytes.data(), static_cast<QInt32>(modelBytes.size()),
                scale, scale, scale,
                frameVertices.data(), static_cast<QInt32>(frameVertices.size()),
                polygons.data(), static_cast<QInt32>(polygons.size()),
                frames.data(), static_cast<QInt32>(frames.size()), parsed) ||
            parsed.meshCount != modelRequirements.meshCount ||
            parsed.vertexCount != modelRequirements.vertexCount ||
            parsed.polygonCount != modelRequirements.polygonCount ||
            parsed.materialCount != modelRequirements.materialCount) {
            return false;
        }
        meshMaterial.mipLevels = &meshTexture.view;
        meshMaterial.mipLevelCount = 1;
        meshMaterial.hasDrawModeOverride = false;
        meshMaterial.drawMode = 9;
        meshMaterial.perspectiveCorrection = perspectiveCorrection;
        meshMaterial.textureAlphaMixing = meshTexture.view.hasAlphaMixing;
        meshMaterial.depthBias = 0;
        parsedScale = scale;
        meshReady = true;
        return true;
    }
};

bool resolveLevel1Mesh(void* rawContext,
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
    Level1ResourceCache& cache = *static_cast<Level1ResourceCache*>(rawContext);
    ++cache.meshResolveCalls;
    if (!matchesText(modelPath, modelPathLength, "/prop/md.3d") ||
        !matchesText(textureSpec, textureSpecLength, "/prop/mdy.png") ||
        modelScale != 2.0f || realtimeLighting || drawModesLength != 0 ||
        (drawModes != 0 && drawModes[0] != '\0') ||
        !cache.loadMesh(modelScale, perspectiveCorrection)) {
        return false;
    }
    asset.frameVertices = cache.frameVertices.data();
    asset.frames = cache.frames.data();
    asset.frameCount = static_cast<QInt32>(cache.frames.size());
    asset.polygons = cache.polygons.data() + cache.frames[0].polygonOffset;
    asset.polygonCount = cache.frames[0].polygonCount;
    asset.materials = &cache.meshMaterial;
    asset.materialCount = 1;
    return true;
}

bool resolveLevel1Texture(void* rawContext,
                          const char* texturePath,
                          QInt32 texturePathLength,
                          SoftwareTextureView& texture,
                          QInt32& textureScale)
{
    Level1ResourceCache& cache = *static_cast<Level1ResourceCache*>(rawContext);
    if (!matchesText(texturePath, texturePathLength, "/prop/skl.png") ||
        !decodeQtx(cache.profile + "/prop/skl.qtx", cache.spriteTexture)) {
        return false;
    }
    texture = cache.spriteTexture.view;
    textureScale = 2;
    return true;
}

void checkProductionManifest(const std::vector<unsigned char>& data)
{
    const std::string actual(data.begin(), data.end());
    const std::string expected =
        "[MESH]\n"
        "POS=-73825,-3626,-113803\n"
        "MODEL=/prop/md.3d\n"
        "TEX=/prop/mdy.png\n"
        "MODEL_SCALE=2\n"
        "COLLISION_SCALE_X=0\n"
        "COLLISION_SCALE_Y=0\n"
        "PHYSICS=0\n"
        "\n"
        "[SPRITE]\n"
        "POS=-71400,-3368,-76270\n"
        "TEX=/prop/skl.png\n"
        "SCALE=34\n"
        "FOG=NONE\n"
        "MODE=NORMAL\n";
    require(actual == expected,
            "the runtime manifest contains only Level 1's exact mesh and sprite entries");
}

void checkProductionLevel1Objects(const std::string& profile)
{
    const std::vector<unsigned char> manifest =
        readFile(profile + "/level1-objects.txt");
    checkProductionManifest(manifest);

    Level1ResourceCache cache(profile);
    SoftwareLevelObjectResolvers resolvers;
    resolvers.context = &cache;
    resolvers.resolveMesh = resolveLevel1Mesh;
    resolvers.resolveTexture = resolveLevel1Texture;
    resolvers.standardDrawMode = 9;

    SoftwareLevelObjectRequirements measured;
    require(SoftwareLevelObjectLoader::measure(
                manifest.data(), static_cast<QInt32>(manifest.size()),
                resolvers, measured),
            "production Level 1 manifest and MD mesh can be measured");
    require(measured.meshObjectCount == 1 && measured.spriteObjectCount == 1 &&
                measured.spriteFrameCount == 1 &&
                measured.animationVertexCount == 16 &&
                measured.flickerValueCount == 0 &&
                measured.colorAnimationValueCount == 0,
            "production manifest sizes exactly one mesh, one sprite, and one morph buffer");

    SoftwareWorldMeshObjectInstance meshObjects[1];
    SoftwareWorldSpriteInstance spriteObjects[1];
    code::Rendering::SoftwareWorldSprite spriteDefinitions[1];
    SoftwareTextureView spriteFrames[1];
    Vector3D animationVertices[16];
    SoftwareLevelObjectLoadStorage storage;
    storage.meshObjects = meshObjects;
    storage.meshObjectCapacity = 1;
    storage.spriteInstances = spriteObjects;
    storage.spriteInstanceCapacity = 1;
    storage.spriteDefinitions = spriteDefinitions;
    storage.spriteDefinitionCapacity = 1;
    storage.spriteFrames = spriteFrames;
    storage.spriteFrameCapacity = 1;
    storage.animationVertices = animationVertices;
    storage.animationVertexCapacity = 16;

    SoftwareLevelObjectRequirements loaded;
    require(SoftwareLevelObjectLoader::load(
                manifest.data(), static_cast<QInt32>(manifest.size()),
                resolvers, storage, loaded),
            "production Level 1 resources load through the native resolver/cache path");
    require(loaded.meshObjectCount == 1 && loaded.spriteObjectCount == 1 &&
                cache.meshResolveCalls >= 3,
            "the loader resolves and reuses the MD model for measure and load");
    require(meshObjects[0].objectToWorld.m03 == -73825 &&
                meshObjects[0].objectToWorld.m13 == -3626 &&
                meshObjects[0].objectToWorld.m23 == -113803 &&
                meshObjects[0].mesh.vertexCount == 16 &&
                meshObjects[0].mesh.polygonCount == 9 &&
                meshObjects[0].mesh.materialCount == 1 &&
                meshObjects[0].mesh.materials[0].mipLevels[0].width == 64 &&
                meshObjects[0].mesh.materials[0].mipLevels[0].height == 64,
            "MD object keeps the configured position, 2x model scale, and 64x64 texture");
    require(spriteObjects[0].position.x == -71400 &&
                spriteObjects[0].position.y == -3368 &&
                spriteObjects[0].position.z == -76270 &&
                spriteDefinitions[0].scale == 34 &&
                !spriteDefinitions[0].fog &&
                spriteDefinitions[0].mode == ESoftwareWorldSpriteKeyed &&
                spriteFrames[0].width == 43 && spriteFrames[0].height == 64,
            "skull sprite keeps its configured position, scale, no-fog normal mode, and source texture");

    QInt32 framebuffer[64 * 64];
    for (QInt32 pixel = 0; pixel < 64 * 64; ++pixel) {
        framebuffer[pixel] = 0x00101824;
    }
    SoftwareRenderVertex cameraVertices[16];
    SoftwareRenderVertex projectedVertices[16];
    SoftwareMeshRenderItem queue[10];
    SoftwareMeshRenderWorkspace renderWorkspace(
        cameraVertices, 16, queue, 10, projectedVertices, 16);
    SoftwareMeshInstance sceneInstances[1];
    const SoftwareLevelObjectRenderWorkspace objectWorkspace(sceneInstances, 1);
    SoftwareMeshRenderOptions meshOptions;
    meshOptions.projection = SoftwareProjectionParameters(32, 32, 64, 64);
    meshOptions.projectionPath = ESoftwareMeshProjectionDirectX7;
    meshOptions.farDistance = 80000;
    meshOptions.clipZ = -150;
    meshOptions.quality.setStandardDrawMode(9);
    meshOptions.quality.setPerspectiveQuality(2);
    meshOptions.quality.setTextureQuality(2);
    meshOptions.quality.setFogQuality(2);
    meshOptions.quality.setMipMapping(false);
    SoftwareWorldSpriteRenderOptions spriteOptions;
    spriteOptions.standardDrawMode = 9;
    Matrix worldToCamera;
    SoftwareMeshRenderStats meshStats;
    SoftwareWorldSpriteRenderStats spriteStats;
    require(SoftwareLevelObjectRenderer::render(
                SoftwareRenderSurfaceView(framebuffer, 64 * 64, 64, 64, 64),
                0, 0, meshObjects, 1, spriteObjects, 1, worldToCamera,
                meshOptions, spriteOptions, objectWorkspace, renderWorkspace,
                meshStats, spriteStats),
            "production Level 1 mesh and sprite submit to the combined scene renderer");
    require(meshStats.polygonsVisited == 9 && spriteStats.spritesVisited == 1,
            "the production objects enter the same painter pass exactly once");
}

} // namespace

int main(int argc, char** argv)
{
    require(argc == 2, "pass the sym9 Level 1 asset profile directory");
    checkProductionLevel1Objects(argv[1]);
    std::puts("Production Level 1 object integration regression passed: exact manifest, MD mesh/texture, skull sprite, and combined painter queue.");
    return 0;
}
