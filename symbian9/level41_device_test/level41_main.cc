#include "matrix.h"
#include "quantum_integer.h"
#include "screen_copy.h"
#include "software_level_objects.h"
#include "software_mesh_3d_parser.h"
#include "software_mesh_renderer.h"
#include "software_world_sprite_renderer.h"
#include "software_perspective_rasterizer.h"
#include "software_texture16.h"
#include "software_qtx2.h"
#include "software_triangle_renderer.h"
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
#include "collision_mesh_adapter.h"
#include "level41_key_mapping.h"
#include "level41_weapon_selection.h"
#include "software_walk_controller.h"
#include "software_weapon_controller.h"
#include "software_weapon_renderer.h"
#include "software_rain_renderer.h"
#if defined(QUANTUM_LEVEL41_SNDDMA_AUDIO)
#include "quantum_level41_snddma_audio.h"
typedef CQuantumLevel41SndDmaAudio TQuantumLevel41WalkAudio;
#elif defined(QUANTUM_LEVEL41_PCM_ONE_AUDIO)
#include "quantum_level41_pcm_audio.h"
typedef CQuantumLevel41PcmAudio TQuantumLevel41WalkAudio;
#else
#include "quantum_level41_audio.h"
typedef CQuantumLevel41Audio TQuantumLevel41WalkAudio;
#endif
#endif

#include <e32base.h>
#include <e32keys.h>
#include <f32file.h>
#include <bitstd.h>
#include <fbs.h>
#ifndef QUANTUM_LEVEL41_NO_DSA
#include <bitdev.h>
#endif
#include <gdi.h>
#include <new>
#include <w32std.h>

#if defined(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP) && \
    (!defined(QUANTUM_LEVEL41_OMAP1710_DSP) || \
     !defined(QUANTUM_LEVEL41_OMAP1710_ECOLOR64K))
#error "The DSA ARGB bitmap experiment requires OMAP1710 and EColor64K"
#endif

#if defined(QUANTUM_LEVEL41_OMAP1710_ECOLOR64K)
extern "C" void quantum_omap1710_copy_argb8888_to_rgb565(
    const code::Math::QInt32* source,
    unsigned char* destination,
    code::Math::QInt32 pixelPairs);
#endif

namespace {

const TUint KWindowGroupClientHandle = 1;
const TUint KProbeWindowHandle = 2;

using code::Math::Matrix;
using code::Math::QInt32;
using code::Math::QUint16;
using code::Math::QUint32;
using code::Math::Vector3D;
using code::Rendering::SoftwareMesh3DParser;
using code::Rendering::SoftwareMesh3DRequirements;
using code::Rendering::SoftwareMesh3DPart;
using code::Rendering::SoftwareMeshInstance;
using code::Rendering::SoftwareMeshMaterial;
using code::Rendering::SoftwareMeshPolygon;
using code::Rendering::SoftwareMeshTextureFrame;
using code::Rendering::SoftwareLevelMeshAsset;
using code::Rendering::SoftwareLevelObjectLoader;
using code::Rendering::SoftwareLevelObjectLoadStorage;
using code::Rendering::SoftwareLevelObjectRenderWorkspace;
using code::Rendering::SoftwareLevelObjectRenderer;
using code::Rendering::SoftwareLevelObjectRequirements;
using code::Rendering::SoftwareLevelObjectResolvers;
using code::Rendering::SoftwareWorldMeshObjectInstance;
using code::Rendering::SoftwareWorldSprite;
using code::Rendering::SoftwareMeshRenderItem;
using code::Rendering::SoftwareMeshRenderOptions;
#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
using code::Rendering::SoftwareMeshRenderInstrumentation;
using code::Rendering::SoftwareMeshRenderPhase;
using code::Rendering::ESoftwareMeshRenderPhaseValidation;
using code::Rendering::ESoftwareMeshRenderPhaseTransformProjection;
using code::Rendering::ESoftwareMeshRenderPhasePolygonQueue;
using code::Rendering::ESoftwareMeshRenderPhaseSort;
using code::Rendering::ESoftwareMeshRenderPhaseDraw;
using code::Rendering::ESoftwareMeshRenderPhaseCount;
#endif
using code::Rendering::SoftwareMeshRenderStats;
using code::Rendering::SoftwareMeshRenderWorkspace;
using code::Rendering::SoftwareWorldSpriteInstance;
using code::Rendering::SoftwareWorldSpriteRenderOptions;
using code::Rendering::SoftwareWorldSpriteRenderStats;
#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
using code::Rendering::SoftwarePerspectiveRasterProfile;
using code::Rendering::SoftwarePerspectiveRasterizer;
#endif
using code::Rendering::SoftwareMeshRenderer;
using code::Rendering::SoftwareMeshView;
#if defined(QUANTUM_LEVEL41_OMAP1710_ECOLOR64K)
using code::Rendering::storeRgb888ToEColor64K;
#else
using code::Rendering::storeRgb888ToEColor16M;
#endif
using code::Rendering::SoftwareProjectionParameters;
using code::Rendering::SoftwareRenderSurfaceView;
using code::Rendering::SoftwareRenderVertex;
using code::Rendering::SoftwareTextureView;
using code::Rendering::SoftwareQtx2Format;
using code::Rendering::SoftwareQtx2Header;
using code::Rendering::ESoftwareQtx2Rgb565;
using code::Rendering::ESoftwareQtx2Argb4444;
using code::Rendering::ESoftwareQtx2Rgb1555;
using code::Rendering::argb8888From4444;
using code::Rendering::rgb8888From1555;
using code::Rendering::rgb8888From565;
using code::Rendering::SoftwareTexture16;
using code::Rendering::SoftwareTexture16View;
using code::Rendering::ESoftwareMeshProjectionDirectX7;
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
using code::Collision::CollisionMaterialFlags;
using code::Collision::CollisionMeshAdapter;
using code::Collision::CollisionMeshView;
using code::Collision::CollisionPolygon;
using code::Rendering::SoftwareWalkController;
using code::Rendering::SoftwareWalkInput;
using code::Rendering::SoftwareWalkSettings;
using code::Rendering::SoftwareWalkState;
using code::Rendering::SoftwareWeaponController;
using code::Rendering::SoftwareWeaponState;
using code::Rendering::SoftwareWeaponTuning;
using code::Rendering::SoftwareWeaponCreateSettings;
using code::Rendering::SoftwareWeaponDrawSettings;
using code::Rendering::SoftwareWeaponRenderer;
#if defined(QUANTUM_LEVEL41_OMAP1710_EFFECTS_ASM)
using code::Rendering::SoftwareRainRenderer;
#endif
using code::Rendering::SoftwareWeaponSpriteBuffers;
using code::Rendering::SoftwareWeaponSpriteImage;
using code::Rendering::SoftwareWeaponSpriteRequirements;
using code::Rendering::SoftwareWeaponSpriteSet;
using code::Rendering::SoftwareWeaponSpriteSources;
const QInt32 KWalkMaterialCount = 5;
enum TWalkCollisionFailurePoint {
    EWalkCollisionFailureNone = 0,
    EWalkCollisionFailureStorage,
    EWalkCollisionFailureAdapter,
    EWalkCollisionFailureSpawnFloor
};
#endif

#if defined(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
bool gLevel41DirectArgbBitmapMode = false;
bool gLevel41DirectArgbRenderEnabled = false;
bool gLevel41DirectRgb565ConversionVerified = false;
bool gLevel41DirectRgb565ConversionAvailable = false;
bool gLevel41DirectRgb565ConversionMatched = false;
TInt gLevel41DirectRgb565MismatchKind = 0;
TInt gLevel41DirectRgb565MismatchX = -1;
TInt gLevel41DirectRgb565MismatchY = -1;
TUint16 gLevel41DirectRgb565Expected = 0;
TUint16 gLevel41DirectRgb565Actual = 0;
extern TInt gRenderWidth;
extern TInt gRenderHeight;
extern TInt gRenderPixelCount;
#endif

#if defined(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
inline TInt level41PresenterBytesPerPixel()
{
    if (gLevel41DirectArgbBitmapMode) return 4;
#if defined(QUANTUM_LEVEL41_OMAP1710_ECOLOR64K)
    return 2;
#else
    return 3;
#endif
}
#define KLevel41PresenterBytesPerPixel level41PresenterBytesPerPixel()
#elif defined(QUANTUM_LEVEL41_OMAP1710_ECOLOR64K)
const TInt KLevel41PresenterBytesPerPixel = 2;
#else
const TInt KLevel41PresenterBytesPerPixel = 3;
#endif

TDisplayMode level41PresenterDisplayMode()
{
#if defined(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
    if (gLevel41DirectArgbBitmapMode) return EColor16MU;
#endif
#if defined(QUANTUM_LEVEL41_OMAP1710_ECOLOR64K)
    return EColor64K;
#else
    return EColor16M;
#endif
}

void storeLevel41PresenterPixel(QInt32 source, TUint8* destination)
{
#if defined(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
    if (gLevel41DirectArgbBitmapMode) {
        const QUint32 pixel = static_cast<QUint32>(source);
        destination[0] = static_cast<TUint8>(pixel & 0xffU);
        destination[1] = static_cast<TUint8>((pixel >> 8U) & 0xffU);
        destination[2] = static_cast<TUint8>((pixel >> 16U) & 0xffU);
        destination[3] = static_cast<TUint8>((pixel >> 24U) & 0xffU);
        return;
    }
#endif
#if defined(QUANTUM_LEVEL41_OMAP1710_ECOLOR64K)
    storeRgb888ToEColor64K(source, destination);
#else
    storeRgb888ToEColor16M(source, destination);
#endif
}

#if defined(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
enum TLevel41Rgb565MismatchKind {
    ELevel41Rgb565MismatchNone = 0,
    ELevel41Rgb565MismatchBoundaryPattern,
    ELevel41Rgb565MismatchFrame
};

bool level41CompareArgb8888ToRgb565(
    const QInt32* source,
    TInt pixelCount,
    TInt width,
    TInt& mismatchX,
    TInt& mismatchY,
    TUint16& expectedWord,
    TUint16& actualWord)
{
    enum { KPairsPerChunk = 64 };
    TUint32 convertedWords[KPairsPerChunk];
    TUint8* const convertedBytes =
        reinterpret_cast<TUint8*>(convertedWords);

    mismatchX = -1;
    mismatchY = -1;
    expectedWord = 0;
    actualWord = 0;
    if (source == 0 || pixelCount <= 0 || width <= 0 ||
        (pixelCount % width) != 0 ||
        (reinterpret_cast<TUint32>(source) & 3U) != 0) {
        return false;
    }

    const TInt pairCount = pixelCount / 2;
    TInt pairOffset = 0;
    while (pairOffset < pairCount) {
        TInt pairsThisChunk = pairCount - pairOffset;
        if (pairsThisChunk > KPairsPerChunk) {
            pairsThisChunk = KPairsPerChunk;
        }
        const TInt firstPixel = pairOffset * 2;
        quantum_omap1710_copy_argb8888_to_rgb565(
            source + firstPixel, convertedBytes, pairsThisChunk);

        TInt pixelOffset;
        for (pixelOffset = 0;
             pixelOffset < pairsThisChunk * 2;
             ++pixelOffset) {
            TUint8 expectedBytes[2];
            storeRgb888ToEColor64K(
                source[firstPixel + pixelOffset], expectedBytes);
            const TUint8* const actualBytes = convertedBytes + pixelOffset * 2;
            if (actualBytes[0] != expectedBytes[0] ||
                actualBytes[1] != expectedBytes[1]) {
                const TInt pixelIndex = firstPixel + pixelOffset;
                mismatchX = pixelIndex % width;
                mismatchY = pixelIndex / width;
                expectedWord = static_cast<TUint16>(
                    static_cast<TUint16>(expectedBytes[0]) |
                    (static_cast<TUint16>(expectedBytes[1]) << 8));
                actualWord = static_cast<TUint16>(
                    static_cast<TUint16>(actualBytes[0]) |
                    (static_cast<TUint16>(actualBytes[1]) << 8));
                return false;
            }
        }
        pairOffset += pairsThisChunk;
    }

    if ((pixelCount & 1) != 0) {
        const QInt32 lastPixel = source[pixelCount - 1];
        const QInt32 duplicatedPair[2] = {lastPixel, lastPixel};
        TUint32 duplicatedOutput = 0;
        quantum_omap1710_copy_argb8888_to_rgb565(
            duplicatedPair,
            reinterpret_cast<TUint8*>(&duplicatedOutput), 1);
        TInt lane;
        for (lane = 0; lane < 2; ++lane) {
            TUint8 expectedBytes[2];
            storeRgb888ToEColor64K(duplicatedPair[lane], expectedBytes);
            const TUint8* const actualBytes =
                reinterpret_cast<const TUint8*>(&duplicatedOutput) + lane * 2;
            if (actualBytes[0] != expectedBytes[0] ||
                actualBytes[1] != expectedBytes[1]) {
                mismatchX = (pixelCount - 1) % width;
                mismatchY = (pixelCount - 1) / width;
                expectedWord = static_cast<TUint16>(
                    static_cast<TUint16>(expectedBytes[0]) |
                    (static_cast<TUint16>(expectedBytes[1]) << 8));
                actualWord = static_cast<TUint16>(
                    static_cast<TUint16>(actualBytes[0]) |
                    (static_cast<TUint16>(actualBytes[1]) << 8));
                return false;
            }
        }
    }
    return true;
}

void verifyLevel41DirectRgb565Conversion(const QInt32* source)
{
    static const QInt32 boundaryPixels[] = {
        0x00000000, 0x00ffffff, 0x00ff0000, 0x0000ff00, 0x000000ff,
        0x00070000, 0x00080000, 0x00f70000, 0x00f80000,
        0x00000300, 0x00000400, 0x0000fb00, 0x0000fc00,
        0x00000007, 0x00000008, 0x000000f7, 0x000000f8,
        0x00123456, 0x7f123456
    };

    gLevel41DirectRgb565ConversionVerified = true;
    gLevel41DirectRgb565ConversionAvailable = false;
    gLevel41DirectRgb565ConversionMatched = false;
    gLevel41DirectRgb565MismatchKind = ELevel41Rgb565MismatchNone;
    gLevel41DirectRgb565MismatchX = -1;
    gLevel41DirectRgb565MismatchY = -1;
    gLevel41DirectRgb565Expected = 0;
    gLevel41DirectRgb565Actual = 0;

    if (source == 0 || gRenderPixelCount <= 0 || gRenderWidth <= 0 ||
        gRenderHeight <= 0 ||
        gRenderPixelCount != gRenderWidth * gRenderHeight) {
        return;
    }
    gLevel41DirectRgb565ConversionAvailable = true;

    TInt mismatchX = -1;
    TInt mismatchY = -1;
    TUint16 expectedWord = 0;
    TUint16 actualWord = 0;
    const TInt boundaryPixelCount = static_cast<TInt>(
        sizeof(boundaryPixels) / sizeof(boundaryPixels[0]));
    if (!level41CompareArgb8888ToRgb565(
            boundaryPixels, boundaryPixelCount, boundaryPixelCount,
            mismatchX, mismatchY, expectedWord, actualWord)) {
        if (mismatchX < 0 || mismatchY < 0) {
            gLevel41DirectRgb565ConversionAvailable = false;
            return;
        }
        gLevel41DirectRgb565MismatchKind =
            ELevel41Rgb565MismatchBoundaryPattern;
        gLevel41DirectRgb565MismatchX = mismatchX;
        gLevel41DirectRgb565MismatchY = mismatchY;
        gLevel41DirectRgb565Expected = expectedWord;
        gLevel41DirectRgb565Actual = actualWord;
        return;
    }

    mismatchX = -1;
    mismatchY = -1;
    expectedWord = 0;
    actualWord = 0;
    if (!level41CompareArgb8888ToRgb565(
            source, gRenderPixelCount, gRenderWidth,
            mismatchX, mismatchY, expectedWord, actualWord)) {
        if (mismatchX < 0 || mismatchY < 0) {
            gLevel41DirectRgb565ConversionAvailable = false;
            return;
        }
        gLevel41DirectRgb565MismatchKind = ELevel41Rgb565MismatchFrame;
        gLevel41DirectRgb565MismatchX = mismatchX;
        gLevel41DirectRgb565MismatchY = mismatchY;
        gLevel41DirectRgb565Expected = expectedWord;
        gLevel41DirectRgb565Actual = actualWord;
        return;
    }

    gLevel41DirectRgb565ConversionMatched = true;
}
#endif

void closeFileServerSession(RFs& fileServer)
{
    // RFs::Close was added for tracing and is absent from earlier OS versions.
    static_cast<RHandleBase&>(fileServer).Close();
}

const TInt KBaseRenderWidth = 120;
const TInt KBaseRenderHeight = 160;
const TInt KBaseProjectionDistance = 106;
const TInt KProjectionScale = 1000;
const TInt KMaximumRenderPixels = 512 * 1024;
const TInt KMaximumTextureWidth = 256;
const TInt KMaximumTextureHeight = 256;
const TInt KMaximumTexturePixels = KMaximumTextureWidth * KMaximumTextureHeight;
const TInt KMaximumTextureFileBytes = 8 + KMaximumTexturePixels * 4;
const TInt KForestTexturePixels = 256 * 128;
const TInt KMapTexturePixels = 256 * 256;
const QInt32 KRiverTextureFrameCount = 4;
const TInt KRiverTextureWidth = 32;
const TInt KRiverTextureHeight = 32;
const TInt KRiverFramePixelCount =
    KRiverTextureWidth * KRiverTextureHeight;
const TInt KRiverTexturePixels =
    KRiverTextureFrameCount * KRiverFramePixelCount;
const float KRiverTextureAnimationSpeed = 6.0f;
const TInt KSkyTexturePixels = 256 * 256;
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
/* Exact capacities for the two assets referenced by Level 1's object records. */
const TInt KLevel1MeshTexturePixels = 64 * 64;
const TInt KLevel1SpriteTexturePixels = 43 * 64;
const TInt KLevel1ObjectTexturePixels =
    KLevel1MeshTexturePixels + KLevel1SpriteTexturePixels;
const TInt KLevel1ObjectManifestCapacity = 2048;
const TInt KLevel1ObjectModelFileCapacity = 2048;
const QInt32 KLevel1MeshVertexCapacity = 16;
const QInt32 KLevel1MeshPolygonCapacity = 9;
const QInt32 KLevel1ObjectVertexCapacity = KLevel1MeshVertexCapacity;
const QInt32 KLevel1ObjectPolygonCapacity = KLevel1MeshPolygonCapacity;
const QInt32 KLevel1ObjectSpriteCapacity = 1;
#else
const TInt KLevel1ObjectTexturePixels = 0;
const QInt32 KLevel1ObjectVertexCapacity = 0;
const QInt32 KLevel1ObjectPolygonCapacity = 0;
const QInt32 KLevel1ObjectSpriteCapacity = 0;
#endif
const TInt KTextureStoragePixels =
    KForestTexturePixels + KMapTexturePixels + KRiverTexturePixels +
    KSkyTexturePixels + KLevel1ObjectTexturePixels;
const TInt KMaximumWorldFileBytes = 60000;
const TInt KMaximumLightmapBytes = 12000;
const TInt KMaximumSkyboxFileBytes = 2048;
const QInt32 KWorldVertexCapacity = 2729;
const QInt32 KWorldPolygonCapacity = 2641;
// The archive-backed Level 1 walk sky (skies/sky.3d) has 25 vertices and
// 20 polygons. Keep enough room for the original Level 41 24/22 sky too, but
// pass the measured counts to the renderer rather than assuming either mesh.
const QInt32 KSkyVertexCapacity = 25;
const QInt32 KSkyPolygonCapacity = 22;
const QInt32 KTotalVertexCapacity =
    KWorldVertexCapacity + KSkyVertexCapacity + KLevel1ObjectVertexCapacity;
const QInt32 KTotalPolygonCapacity =
    KWorldPolygonCapacity + KSkyPolygonCapacity +
    KLevel1ObjectPolygonCapacity + KLevel1ObjectSpriteCapacity;
const QInt32 KBackground = 0x00101824;
const QInt32 KEyeHeight = 1503;
const QInt32 KFarDistance = 80000;
const QInt32 KClipDistance = 150;

QInt32* gFramebuffer = 0;
TInt gRenderWidth = 0;
TInt gRenderHeight = 0;
TInt gRenderPixelCount = 0;
Vector3D gWorldVertices[KWorldVertexCapacity];
SoftwareMeshPolygon gWorldPolygons[KWorldPolygonCapacity];
SoftwareMesh3DPart gWorldParts[1];
Vector3D gSkyVertices[KSkyVertexCapacity];
SoftwareMeshPolygon gSkyPolygons[KSkyPolygonCapacity];
SoftwareMesh3DPart gSkyParts[1];
SoftwareRenderVertex gCameraVertices[KTotalVertexCapacity];
SoftwareRenderVertex gProjectedVertices[KTotalVertexCapacity];
SoftwareMeshRenderItem gRenderQueue[KTotalPolygonCapacity];

QInt32* gTextureStorage = 0;
QInt32* gForestPixels = 0;
QInt32* gMapPixels = 0;
QInt32* gRiverPixels = 0;
QInt32* gSkyPixels = 0;

/*
 * Parallel 16-bit texture storage for the fast rasterizer path.
 *
 * Mipmapping is intentionally not used: each texture gets exactly one
 * 16-bit level, so this costs one QUint16 per texel (half the 32-bit
 * image). The fast preset also disables mipmapping, so no chain is ever
 * selected here.
 */
QUint16* gTexture16Storage = 0;
QUint16* gForestPixels16 = 0;
QUint16* gMapPixels16 = 0;
QUint16* gRiverPixels16 = 0;
QUint16* gSkyPixels16 = 0;
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
QUint16* gLevel1MeshTexturePixels16 = 0;
#endif
TUint8 gWorldFile[KMaximumWorldFileBytes];
TUint8 gLightmapFile[KMaximumLightmapBytes];
TUint8 gSkyboxFile[KMaximumSkyboxFileBytes];
TUint8 gTextureFile[KMaximumTextureFileBytes];

SoftwareTextureView gWorldMipViews[5];
SoftwareTextureView gRiverAnimationMipViews[KRiverTextureFrameCount];
SoftwareMeshTextureFrame gRiverAnimationFrames[KRiverTextureFrameCount];
SoftwareTextureView gSkyMipView;

/* One 16-bit level per texture; no 16-bit mip chain (see gTexture16Storage). */
SoftwareTexture16View gWorldViews16[5];
SoftwareTexture16View gRiverAnimationViews16[KRiverTextureFrameCount];
SoftwareTexture16View gSkyView16;
bool gTexture16Ready = false;
SoftwareMeshMaterial gWorldMaterials[5];
SoftwareMeshMaterial gSkyMaterial[1];
SoftwareMeshView gWorldView;
SoftwareMeshView gSkyView;
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
CollisionPolygon* gWalkCollisionPolygons = 0;
CollisionMaterialFlags gWalkCollisionMaterials[KWalkMaterialCount];
CollisionMeshView gWalkCollisionView;
QInt32 gWalkCollisionBuiltPolygonCount = 0;
TInt gWalkCollisionFailurePoint = EWalkCollisionFailureNone;
SoftwareWalkState gWalkState;
SoftwareWalkSettings gWalkSettings;
/* Held-key state is sampled once per frame. Movement pulses preserve
   fallback behavior on window servers without key-state events; numeric 0
   generates a one-frame jump pulse on the key-down edge. Numeric 1/3 look
   up/down while held; event-only servers get a one-degree tap. */
bool gWalkForwardHeld = false;
bool gWalkBackwardHeld = false;
bool gWalkForwardStateKnown = false;
bool gWalkBackwardStateKnown = false;
bool gWalkForwardPulse = false;
bool gWalkBackwardPulse = false;
bool gWalkJumpPulse = false;
bool gWalkJumpHeld = false;
bool gWalkJumpStateKnown = false;
bool gWalkLeftHeld = false;
bool gWalkRightHeld = false;
bool gWalkLeftStateKnown = false;
bool gWalkRightStateKnown = false;
bool gCameraLookUpHeld = false;
bool gCameraLookDownHeld = false;
bool gCameraLookUpStateKnown = false;
bool gCameraLookDownStateKnown = false;
bool gCameraLookUpPulse = false;
bool gCameraLookDownPulse = false;
QInt32 gCameraPitchDegrees = 0;
QInt32 gCameraPitchRemainder = 0;
/* ceil(1000 / 60): an event-only key press moves at least one degree. */
const QInt32 KCameraLookPulseElapsedMs = 17;
SoftwareWeaponTuning gWeaponTuning;
SoftwareWeaponState gWeaponState;
SoftwareWeaponSpriteBuffers gWeaponSpriteBuffers;
SoftwareWeaponSpriteSet gWeaponSprites;
/*
 * Which of the shipped weapon bodies is equipped. `#` advances it, wrapping
 * from the last back to the first; see level41_weapon_selection.h. Only one
 * body is ever resident: switching frees the current sprite buffers and loads
 * the next, which is why the arsenal costs no extra heap at rest.
 */
TInt gCurrentWeapon = 0;
/* Per-weapon controller state, so a weapon keeps its recoil and sway across
   a round trip through the others instead of resetting every time. */
SoftwareWeaponState gWeaponSavedStates[code::Rendering::KLevel41WeaponCount];
bool gWeaponStateInitialized[code::Rendering::KLevel41WeaponCount];
/* Held-key edge detection for `#`: a held key must switch only once. */
bool gWeaponSwitchHeld = false;
bool gWeaponSwitchStateKnown = false;
/* Set once the assets have been loaded early enough for input to switch. */
RFs* gWeaponFileServer = 0;
TBuf<8> gWeaponDrive;
/*
 * Allocate the 16-bit twin of one prepared weapon sprite and fill it from
 * the resized 8888 pixels. `format4444` selects ARGB4444 (real per-texel
 * alpha) over RGB1555 (1-bit key).
 *
 * Returns false when the sprite is absent, the image is empty, or the
 * allocation fails; in every such case the sprite keeps drawing from its
 * 32-bit view, so this is never fatal.
 */
bool buildWeaponSixteenImage(SoftwareWeaponSpriteImage& image,
                             QUint16*& storage,
                             bool format4444)
{
    if (image.view.pixels == 0 || image.view.width <= 0 ||
        image.view.height <= 0) {
        return false;
    }
    const TInt pixelCount = image.view.width * image.view.height;
    if (pixelCount <= 0) return false;
    storage = static_cast<QUint16*>(
        User::Alloc(pixelCount * static_cast<TInt>(sizeof(QUint16))));
    if (storage == 0) return false;
    if (!image.buildSixteen(format4444, storage, pixelCount)) {
        User::Free(storage);
        storage = 0;
        return false;
    }
    return true;
}

/*
 * Parallel 16-bit weapon images, one buffer per sprite, allocated only for
 * the images whose format actually fits:
 *
 *   weapon        RGB1555   body art, 69% fully opaque, 0/255 cut
 *   weapon-fire   ARGB4444  48% of its visible texels are genuinely
 *                            translucent, so a 1-bit key would erase the glow
 *   weapon-sight            ARGB4444  soft anti-aliased reticle edges
 *   weapon-sight-weapon     dropped   see below
 *
 * weapon-sight-weapon is not kept at all. Nothing can draw it: the reticle
 * needs `zoom`, which this sample never raises, so the 300x320 sprite cost
 * 384,000 bytes of 32-bit buffer plus 192,000 bytes of 16-bit buffer to sit
 * unused. See loadWeaponAssets().
 *
 * A failed allocation is not fatal: that sprite keeps its 32-bit view.
 */
QUint16* gWeaponSixteenStorage = 0;
QUint16* gWeaponFireSixteenStorage = 0;
QUint16* gWeaponSightSixteenStorage = 0;
bool gWeaponOverlayAvailable = false;
bool gWeaponTuningLoadedFromAssets = false;
TInt64 gLastWeaponUpdateMicros = 0;
QInt32 gLevel41FrameElapsedMs = 1;
TInt gAudioInitializationError = KErrNotReady;
TQuantumLevel41WalkAudio* gLevel41Audio = 0;
#if defined(QUANTUM_LEVEL41_OMAP1710_EFFECTS_ASM)
const TInt KLevel41RainParticleCount = 6;
struct TLevel41RainParticle {
    QInt32 x;
    QInt32 y;
    QInt32 depth;
};
TLevel41RainParticle gLevel41RainParticles[KLevel41RainParticleCount];
bool gLevel41RainInitialized = false;
bool gLevel41RainPausedUnderRoof = false;
TInt gLevel41RainWidth = 0;
TInt gLevel41RainHeight = 0;
TInt64 gLastRainUpdateMicros = 0;
TUint32 gLevel41RainRandomState = 0x41e65a73U;
#endif
#endif

enum TRenderStage {
    ERenderStageNone = 0,
    ERenderStageForestTexture,
    ERenderStageMapTexture,
    ERenderStageRiverTexture,
    ERenderStageSkyTexture,
    ERenderStageTextureDimensions,
    ERenderStageWorldMesh,
    ERenderStageLightmap,
    ERenderStageSkyboxMesh,
    ERenderStageWalkCollision,
    ERenderStageLevelObjects,
    ERenderStageRasterizer,
    ERenderStageNoPolygons
};

enum TAssetFailurePoint {
    EAssetFailureNone = 0,
    EAssetFailureOpen,
    EAssetFailureSize,
    EAssetFailureCapacity,
    EAssetFailureRead,
    EAssetFailureMeshMeasure,
    EAssetFailureMeshLayout,
    EAssetFailureMeshParse,
    EAssetFailureObjectManifest,
    EAssetFailureObjectTexture,
    EAssetFailureObjectMeshMeasure,
    EAssetFailureObjectMeshLayout,
    EAssetFailureObjectMeshParse
};

TInt gRenderStatus = KErrNone;
TInt gRenderStage = ERenderStageNone;
TInt gAssetFailurePoint = EAssetFailureNone;
TInt gAssetFailureCode = KErrNone;
TInt gAssetFileSize = -1;
TInt gAssetBytesRead = -1;
SoftwareMesh3DRequirements gSkyboxRequirements;
bool gMeshContentsPrevalidated = false;
QInt32 gPolygonsDrawn = 0;
QInt32 gTrianglesDrawn = 0;
const TInt64 KBenchmarkDurationMicros = 10000000;
TInt64 gBenchmarkStartMicros = 0;
TInt64 gTotalMeshRenderMicros = 0;
TInt64 gMaximumMeshRenderMicros = 0;
TInt64 gLastMeshRenderMicros = 0;
TInt gBenchmarkFrames = 0;
TInt gDeviceFpsTenths = 0;
TInt gAverageMeshRenderMicros = 0;
TInt gMaximumMeshRenderMillis = 0;
#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
TInt64 gLastMeshPhaseMicros[ESoftwareMeshRenderPhaseCount];
TInt64 gTotalMeshPhaseMicros[ESoftwareMeshRenderPhaseCount];
TInt gAverageMeshPhaseMillis[ESoftwareMeshRenderPhaseCount];
SoftwarePerspectiveRasterProfile gRasterProfileThisFrame;
TInt64 gTotalPerspectiveDivideCalls = 0;
TInt64 gTotalSampledPerspectiveDivideCalls = 0;
TInt64 gTotalSampledPerspectiveDivideTicks = 0;
TInt64 gTotalRasterPixelLoops = 0;
TInt64 gTotalRasterPixels = 0;
TInt64 gTotalSampledRasterPixelLoops = 0;
TInt64 gTotalSampledRasterPixels = 0;
TInt64 gTotalSampledRasterPixelTicks = 0;
TInt64 gTotalMode9TriangleCalls = 0;
TInt64 gTotalSampledMode9TriangleSetupCalls = 0;
TInt64 gTotalSampledMode9TriangleSetupTicks = 0;
TInt64 gTotalMode9EdgeRows = 0;
TInt64 gTotalSampledMode9EdgeRows = 0;
TInt64 gTotalSampledMode9EdgeTicks = 0;
TInt64 gTotalSampledMode9SpanRawTicks = 0;
TInt64 gTotalSampledMode9SpanPerspectiveDivideCalls = 0;
TInt64 gTotalSampledMode9SpanSampledPerspectiveDivideCalls = 0;
TInt64 gTotalSampledMode9SpanPixelCount = 0;
TInt64 gTotalSampledMode9SpanSampledPixelLoopCalls = 0;
TInt64 gTotalRasterFastCounterTicks = 0;
TInt64 gRasterFastCounterTicksPerSecond = 0;
TUint32 gPreviousRasterFastCounter = 0;
TUint32 gRasterCounterReadOverheadTicks = 0;
TInt64 gTotalPresenterFillTicks = 0;
TInt64 gTotalPresenterOverlayTicks = 0;
TInt64 gTotalPresenterBlitTicks = 0;
TInt64 gTotalPresenterFrameTicks = 0;
TInt64 gPresenterFrameSamples = 0;
#endif
bool gBenchmarkComplete = false;
bool gBenchmarkFinalOverlayPending = false;
bool gBitmapGdiPresenterActive = false;
bool gDsaPresenterActive = false;
bool gDsaAttempted = false;
TInt gDsaStatus = KErrNotSupported;
#if defined(QUANTUM_LEVEL41_OMAP1710_ECOLOR64K)
bool gOmapScreenCopyActive = false;
bool gOmapScreenCopyEnabled = true;
bool gOmapScreenCopyVerified = false;
bool gOmapScreenCopyMatched = false;
#endif

struct TextureInfo {
    TInt width;
    TInt height;
    bool hasTransparentTexels;
    bool alphaMixing;
    /*
     * QTX2 assets arrive pre-quantised: the format was chosen at build time
     * and the 16-bit words are stored in the file, so nothing has to be
     * converted at start-up.
     */
    bool isQtx2;
    SoftwareQtx2Format qtx2Format;
    const QUint16* qtx2Pixels;

    TextureInfo()
        : width(0), height(0), hasTransparentTexels(false), alphaMixing(false),
          isQtx2(false), qtx2Format(ESoftwareQtx2Rgb565), qtx2Pixels(0)
    {
    }
};

#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
TUint8 gLevel1ObjectManifest[KLevel1ObjectManifestCapacity];
TInt gLevel1ObjectManifestLength = 0;
TUint8 gLevel1ObjectMeshFile[KLevel1ObjectModelFileCapacity];
Vector3D gLevel1ObjectMeshVertices[KLevel1MeshVertexCapacity];
Vector3D gLevel1ObjectAnimationVertices[KLevel1MeshVertexCapacity];
SoftwareMeshPolygon gLevel1ObjectMeshPolygons[KLevel1MeshPolygonCapacity];
SoftwareMesh3DPart gLevel1ObjectMeshFrames[1];
SoftwareTextureView gLevel1ObjectMeshMipView;
SoftwareTexture16View gLevel1ObjectMeshView16;
bool gLevel1MeshTexture16Ready = false;
SoftwareMeshMaterial gLevel1ObjectMeshMaterials[1];
SoftwareWorldMeshObjectInstance gLevel1MeshObjects[1];
SoftwareWorldSpriteInstance gLevel1SpriteObjects[1];
SoftwareWorldSprite gLevel1SpriteDefinitions[1];
SoftwareTextureView gLevel1SpriteFrames[1];
SoftwareLevelObjectLoadStorage gLevel1ObjectLoadStorage;
SoftwareLevelObjectRequirements gLevel1ObjectRequirements;
QInt32* gLevel1MeshTexturePixels = 0;
QInt32* gLevel1SpriteTexturePixels = 0;
TextureInfo gLevel1MeshTextureInfo;
TextureInfo gLevel1SpriteTextureInfo;
SoftwareTextureView gLevel1SpriteTextureView;
bool gLevel1MeshTextureLoaded = false;
bool gLevel1SpriteTextureLoaded = false;
bool gLevel1MeshAssetLoaded = false;
float gLevel1MeshScaleLoaded = 0.0f;
#endif

bool readPrivateAsset(RFs& fileServer,
                      const TDesC& drive,
                      const TDesC& filename,
                      TUint8* buffer,
                      TInt capacity,
                      TInt& length)
{
    gAssetFailurePoint = EAssetFailureNone;
    gAssetFailureCode = KErrNone;
    gAssetFileSize = -1;
    gAssetBytesRead = -1;

    TFileName path;
    path.Copy(drive);
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
    // The separate walk package uses SID e000a020.
    path.Append(_L("\\private\\e000a020\\level41\\"));
#elif defined(QUANTUM_LEVEL41_GDI_DIAGNOSTIC)
    // The companion package has SID e000a016; the main app keeps e000a012.
    path.Append(_L("\\private\\e000a016\\level41\\"));
#elif defined(QUANTUM_LEVEL41_FPS_EXPERIMENT_Q48)
    // The separate Q48 package stages assets under its own SID e000a030.
    path.Append(_L("\\private\\e000a030\\level41\\"));
#else
    path.Append(_L("\\private\\e000a012\\level41\\"));
#endif
    path.Append(filename);

    RFile file;
    TInt result = file.Open(fileServer, path, EFileRead | EFileShareReadersOnly);
    if (result != KErrNone) {
        gAssetFailurePoint = EAssetFailureOpen;
        gAssetFailureCode = result;
        return false;
    }

    TInt fileSize = 0;
    result = file.Size(fileSize);
    if (result != KErrNone) {
        file.Close();
        gAssetFailurePoint = EAssetFailureSize;
        gAssetFailureCode = result;
        return false;
    }
    gAssetFileSize = fileSize;
    if (fileSize < 0 || fileSize > capacity) {
        file.Close();
        gAssetFailurePoint = EAssetFailureCapacity;
        gAssetFailureCode = KErrOverflow;
        return false;
    }

    TPtr8 destination(buffer, capacity);
    result = file.Read(destination, fileSize);
    file.Close();
    gAssetBytesRead = destination.Length();
    if (result != KErrNone) {
        gAssetFailurePoint = EAssetFailureRead;
        gAssetFailureCode = result;
        return false;
    }
    if (destination.Length() != fileSize) {
        gAssetFailurePoint = EAssetFailureRead;
        gAssetFailureCode = KErrCorrupt;
        return false;
    }
    length = fileSize;
    return true;
}

TInt littleEndianU16(const TUint8* bytes)
{
    return static_cast<TInt>(bytes[0]) |
           (static_cast<TInt>(bytes[1]) << 8);
}

bool inspectQtxTexture(const TUint8* data, TInt length, TextureInfo& info)
{
    info = TextureInfo();
    if (data == 0 || length < 8) return false;
    /*
     * QTX2 reuses this inspector so the rest of start-up keeps working: the
     * dimensions and transparency flags come from the pre-quantised header.
     */
    if (data[0] == 'Q' && data[1] == 'T' && data[2] == 'X' && data[3] == '2') {
        SoftwareQtx2Header header;
        const QUint16* sixteen = 0;
        const QInt32* rgb8888 = 0;
        if (!code::Rendering::softwareQtx2Inspect(
                reinterpret_cast<const unsigned char*>(data), length,
                KMaximumTextureWidth, KMaximumTextureHeight, header,
                &sixteen, &rgb8888)) {
            return false;
        }
        info.width = header.width;
        info.height = header.height;
        info.isQtx2 = true;
        info.qtx2Format = header.format;
        info.qtx2Pixels = sixteen;
        if (sixteen != 0) {
            const code::Rendering::SoftwareTexture16View view =
                code::Rendering::softwareQtx2TextView(header, sixteen);
            info.hasTransparentTexels = view.hasTransparentTexels;
            if (view.is4444) {
                QInt32 pixel;
                for (pixel = 0; pixel < view.pixelCount; ++pixel) {
                    const QUint32 alpha =
                        (view.pixels[pixel] >> 12U) & 0x0fU;
                    if (alpha != 0U && alpha != 0x0fU) {
                        info.alphaMixing = true;
                        break;
                    }
                }
            }
        }
        return true;
    }
    if (data[0] != 'Q' || data[1] != 'T' ||
        data[2] != 'X' || data[3] != '1') {
        return false;
    }
    info.width = littleEndianU16(data + 4);
    info.height = littleEndianU16(data + 6);
    if (info.width <= 0 || info.height <= 0 ||
        info.width > KMaximumTextureWidth ||
        info.height > KMaximumTextureHeight) {
        return false;
    }
    const TInt pixelCount = info.width * info.height;
    const TInt expectedLength = 8 + pixelCount * 4;
    return length == expectedLength;
}

bool decodeQtxTexture(const TUint8* data,
                      TInt length,
                      QInt32* pixels,
                      TInt pixelCapacity,
                      TextureInfo& info)
{
    if (!inspectQtxTexture(data, length, info) || pixels == 0) return false;
    const TInt pixelCount = info.width * info.height;
    if (pixelCount > pixelCapacity) return false;

    /*
     * QTX2 stores quantised pixels, not RGBA bytes, so the QTX1 loop below
     * would read the file as garbage. Expand the stored block back to 8888:
     * every consumer downstream (world mip views, level objects, weapon
     * sprites) still asks for 8888, and the point of QTX2 is that the file
     * already holds the final format, not that the caller changes shape.
     */
    if (info.isQtx2) {
        return code::Rendering::softwareQtx2ExpandToRgb8888(
            reinterpret_cast<const unsigned char*>(data), length, pixels,
            pixelCapacity);
    }

    TInt pixelIndex;
    for (pixelIndex = 0; pixelIndex < pixelCount; ++pixelIndex) {
        const TUint8* rgba = data + 8 + pixelIndex * 4;
        const TUint32 alpha = rgba[3];
        const QUint32 color = alpha == 0U ? 0U :
            (alpha << 24U) |
            (static_cast<QUint32>(rgba[0]) << 16U) |
            (static_cast<QUint32>(rgba[1]) << 8U) |
            static_cast<QUint32>(rgba[2]);
        pixels[pixelIndex] = code::Math::javaIntFromUnsigned(color);
        if (alpha == 0U) info.hasTransparentTexels = true;
        else if (alpha < 255U) info.alphaMixing = true;
    }
    return true;
}

/*
 * QTX2 assets carry their pixels in the renderer's format already. The device
 * only borrows a pointer into the loaded file image; `gTextureFile` outlives
 * every texture view, so no copy is needed.
 */
bool loadQtx2Texture(const TUint8* data,
                     TInt length,
                     TextureInfo& info,
                     const QUint16*& sixteen)
{
    SoftwareQtx2Header header;
    const QInt32* rgb8888 = 0;
    if (!code::Rendering::softwareQtx2Inspect(
            reinterpret_cast<const unsigned char*>(data), length,
            KMaximumTextureWidth, KMaximumTextureHeight, header,
            &sixteen, &rgb8888)) {
        return false;
    }
    if (sixteen == 0) return false; /* only world textures are streamed */

    info = TextureInfo();
    info.width = header.width;
    info.height = header.height;
    info.isQtx2 = true;
    info.qtx2Format = header.format;
    info.qtx2Pixels = sixteen;

    const code::Rendering::SoftwareTexture16View view =
        code::Rendering::softwareQtx2TextView(header, sixteen);
    info.hasTransparentTexels = view.hasTransparentTexels;
    /*
     * 4444 keeps per-texel alpha, so any partially transparent texel is
     * alpha mixing. 1555 cannot express it: those texels were already
     * resolved into the 1-bit key at build time.
     */
    if (view.is4444) {
        QInt32 pixel;
        for (pixel = 0; pixel < view.pixelCount; ++pixel) {
            const QUint32 alpha = (view.pixels[pixel] >> 12U) & 0x0fU;
            if (alpha != 0U && alpha != 0x0fU) {
                info.alphaMixing = true;
                break;
            }
        }
    }
    return true;
}

bool loadTexture(RFs& fileServer,
                 const TDesC& drive,
                 const TDesC& filename,
                 QInt32* pixels,
                 TInt pixelCapacity,
                 TextureInfo& info)
{
    TInt length = 0;
    return readPrivateAsset(fileServer, drive, filename, gTextureFile,
                            KMaximumTextureFileBytes, length) &&
           decodeQtxTexture(gTextureFile, length, pixels,
                            pixelCapacity, info);
}

#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
struct TLevel1ObjectResolverContext {
    RFs* fileServer;
    const TDesC* drive;
};

bool levelObjectPathEquals(const char* path,
                           QInt32 pathLength,
                           const char* expected)
{
    if (path == 0 || expected == 0 || pathLength < 0) return false;
    QInt32 expectedLength = 0;
    while (expected[expectedLength] != '\0') ++expectedLength;
    if (pathLength != expectedLength) return false;
    QInt32 index;
    for (index = 0; index < pathLength; ++index) {
        if (path[index] != expected[index]) return false;
    }
    return true;
}

bool loadLevel1ObjectTexture(TLevel1ObjectResolverContext& context,
                             const TDesC& filename,
                             QInt32 expectedWidth,
                             QInt32 expectedHeight,
                             QInt32* pixels,
                             TInt pixelCapacity,
                             TextureInfo& info,
                             SoftwareTextureView& view,
                             bool& loaded)
{
    if (loaded) return true;
    if (context.fileServer == 0 || context.drive == 0 || pixels == 0) {
        gAssetFailurePoint = EAssetFailureObjectTexture;
        gAssetFailureCode = KErrNotReady;
        return false;
    }
    if (!loadTexture(*context.fileServer, *context.drive, filename,
                     pixels, pixelCapacity, info)) {
        if (gAssetFailurePoint == EAssetFailureNone) {
            gAssetFailurePoint = EAssetFailureObjectTexture;
        }
        if (gAssetFailureCode == KErrNone) gAssetFailureCode = KErrCorrupt;
        return false;
    }
    if (info.width != expectedWidth || info.height != expectedHeight) {
        gAssetFailurePoint = EAssetFailureObjectTexture;
        gAssetFailureCode = KErrCorrupt;
        return false;
    }
    const TInt pixelCount = info.width * info.height;
    view = SoftwareTextureView(
        pixels, pixelCount, info.width, info.height,
        info.hasTransparentTexels, info.hasTransparentTexels,
        info.alphaMixing);
    loaded = true;
    return true;
}

bool resolveLevel1ObjectTexture(void* rawContext,
                                const char* texturePath,
                                QInt32 texturePathLength,
                                SoftwareTextureView& texture,
                                QInt32& textureScale)
{
    TLevel1ObjectResolverContext& context =
        *static_cast<TLevel1ObjectResolverContext*>(rawContext);
    if (levelObjectPathEquals(texturePath, texturePathLength,
                              "/prop/mdy.png")) {
        if (!loadLevel1ObjectTexture(
                context, _L("prop\\mdy.qtx"), 64, 64,
                gLevel1MeshTexturePixels, KLevel1MeshTexturePixels,
                gLevel1MeshTextureInfo, gLevel1ObjectMeshMipView,
                gLevel1MeshTextureLoaded)) {
            return false;
        }
        texture = gLevel1ObjectMeshMipView;
    } else if (levelObjectPathEquals(texturePath, texturePathLength,
                                     "/prop/skl.png")) {
        if (!loadLevel1ObjectTexture(
                context, _L("prop\\skl.qtx"), 43, 64,
                gLevel1SpriteTexturePixels, KLevel1SpriteTexturePixels,
                gLevel1SpriteTextureInfo, gLevel1SpriteTextureView,
                gLevel1SpriteTextureLoaded)) {
            return false;
        }
        texture = gLevel1SpriteTextureView;
    } else {
        gAssetFailurePoint = EAssetFailureObjectTexture;
        gAssetFailureCode = KErrNotFound;
        return false;
    }
    textureScale = 2;
    return true;
}

bool resolveLevel1ObjectMesh(void* rawContext,
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
    TLevel1ObjectResolverContext& context =
        *static_cast<TLevel1ObjectResolverContext*>(rawContext);
    if (context.fileServer == 0 || context.drive == 0 ||
        !levelObjectPathEquals(modelPath, modelPathLength, "/prop/md.3d") ||
        !levelObjectPathEquals(textureSpec, textureSpecLength,
                               "/prop/mdy.png") ||
        modelScale != 2.0f || realtimeLighting || drawModesLength != 0 ||
        (drawModes != 0 && drawModes[0] != '\0')) {
        gAssetFailurePoint = EAssetFailureObjectMeshLayout;
        gAssetFailureCode = KErrNotSupported;
        return false;
    }

    if (!loadLevel1ObjectTexture(
            context, _L("prop\\mdy.qtx"), 64, 64,
            gLevel1MeshTexturePixels, KLevel1MeshTexturePixels,
            gLevel1MeshTextureInfo, gLevel1ObjectMeshMipView,
            gLevel1MeshTextureLoaded)) {
        return false;
    }

    if (!gLevel1MeshAssetLoaded) {
        TInt modelLength = 0;
        if (!readPrivateAsset(
                *context.fileServer, *context.drive, _L("prop\\md.3d"),
                gLevel1ObjectMeshFile, KLevel1ObjectModelFileCapacity,
                modelLength)) {
            return false;
        }
        SoftwareMesh3DRequirements measured;
        if (!SoftwareMesh3DParser::measure(
                gLevel1ObjectMeshFile, modelLength, measured)) {
            gAssetFailurePoint = EAssetFailureObjectMeshMeasure;
            gAssetFailureCode = KErrCorrupt;
            return false;
        }
        if (measured.meshCount != 1 ||
            measured.vertexCount != KLevel1MeshVertexCapacity ||
            measured.polygonCount != KLevel1MeshPolygonCapacity ||
            measured.materialCount != 1) {
            gAssetFailurePoint = EAssetFailureObjectMeshLayout;
            gAssetFailureCode = KErrCorrupt;
            return false;
        }
        SoftwareMesh3DRequirements parsed;
        if (!SoftwareMesh3DParser::parse(
                gLevel1ObjectMeshFile, modelLength,
                modelScale, modelScale, modelScale,
                gLevel1ObjectMeshVertices, KLevel1MeshVertexCapacity,
                gLevel1ObjectMeshPolygons, KLevel1MeshPolygonCapacity,
                gLevel1ObjectMeshFrames, 1, parsed) ||
            parsed.meshCount != measured.meshCount ||
            parsed.vertexCount != measured.vertexCount ||
            parsed.polygonCount != measured.polygonCount ||
            parsed.materialCount != measured.materialCount) {
            gAssetFailurePoint = EAssetFailureObjectMeshParse;
            gAssetFailureCode = KErrCorrupt;
            return false;
        }

        gLevel1ObjectMeshMaterials[0].mipLevels =
            &gLevel1ObjectMeshMipView;
        gLevel1ObjectMeshMaterials[0].mipLevelCount = 1;
        /*
         * Single 16-bit level for the fast path (drawmode 9). If the
         * conversion fails the material keeps mipLevels16 == 0 and the
         * 32-bit kernel handles this mesh.
         */
        gLevel1MeshTexture16Ready = false;
        if (gLevel1MeshTexturePixels16 != 0) {
            bool is1555 = false;
            bool hasTransparent = false;
            if (SoftwareTexture16::convertLevel8888To16(
                    gLevel1MeshTexturePixels,
                    gLevel1MeshTextureInfo.width,
                    gLevel1MeshTextureInfo.height, true,
                    gLevel1MeshTexturePixels16,
                    KLevel1MeshTexturePixels, is1555, hasTransparent)) {
                gLevel1ObjectMeshView16 = SoftwareTexture16View(
                    gLevel1MeshTexturePixels16,
                    gLevel1MeshTextureInfo.width *
                        gLevel1MeshTextureInfo.height,
                    gLevel1MeshTextureInfo.width,
                    gLevel1MeshTextureInfo.height, is1555,
                    hasTransparent);
                gLevel1MeshTexture16Ready = true;
            }
        }
        gLevel1ObjectMeshMaterials[0].mipLevels16 =
            gLevel1MeshTexture16Ready ? &gLevel1ObjectMeshView16 : 0;
        gLevel1ObjectMeshMaterials[0].hasDrawModeOverride = false;
        gLevel1ObjectMeshMaterials[0].drawMode = 9;
        gLevel1ObjectMeshMaterials[0].perspectiveCorrection =
            perspectiveCorrection;
        gLevel1ObjectMeshMaterials[0].textureAlphaMixing =
            gLevel1MeshTextureInfo.alphaMixing;
        gLevel1ObjectMeshMaterials[0].depthBias = 0;
        gLevel1MeshScaleLoaded = modelScale;
        gLevel1MeshAssetLoaded = true;
    } else if (modelScale != gLevel1MeshScaleLoaded ||
               gLevel1ObjectMeshMaterials[0].perspectiveCorrection !=
                   perspectiveCorrection) {
        gAssetFailurePoint = EAssetFailureObjectMeshLayout;
        gAssetFailureCode = KErrCorrupt;
        return false;
    }

    asset = SoftwareLevelMeshAsset();
    asset.frameVertices = gLevel1ObjectMeshVertices;
    asset.frames = gLevel1ObjectMeshFrames;
    asset.frameCount = 1;
    asset.polygons = gLevel1ObjectMeshPolygons +
        gLevel1ObjectMeshFrames[0].polygonOffset;
    asset.polygonCount = gLevel1ObjectMeshFrames[0].polygonCount;
    asset.materials = gLevel1ObjectMeshMaterials;
    asset.materialCount = 1;
    return true;
}

bool loadLevel1Objects(RFs& fileServer, const TDesC& drive)
{
    gRenderStage = ERenderStageLevelObjects;
    gLevel1ObjectManifestLength = 0;
    gLevel1MeshTextureInfo = TextureInfo();
    gLevel1SpriteTextureInfo = TextureInfo();
    gLevel1ObjectMeshMipView = SoftwareTextureView();
    gLevel1SpriteTextureView = SoftwareTextureView();
    gLevel1MeshTextureLoaded = false;
    gLevel1SpriteTextureLoaded = false;
    gLevel1MeshAssetLoaded = false;
    gLevel1MeshScaleLoaded = 0.0f;
    gLevel1ObjectRequirements = SoftwareLevelObjectRequirements();

    if (!readPrivateAsset(
            fileServer, drive, _L("level1-objects.txt"),
            gLevel1ObjectManifest, KLevel1ObjectManifestCapacity,
            gLevel1ObjectManifestLength)) {
        return false;
    }

    TLevel1ObjectResolverContext context;
    context.fileServer = &fileServer;
    context.drive = &drive;
    SoftwareLevelObjectResolvers resolvers;
    resolvers.context = &context;
    resolvers.resolveMesh = resolveLevel1ObjectMesh;
    resolvers.resolveTexture = resolveLevel1ObjectTexture;
    resolvers.standardDrawMode = 9;

    SoftwareLevelObjectRequirements measured;
    if (!SoftwareLevelObjectLoader::measure(
            gLevel1ObjectManifest, gLevel1ObjectManifestLength,
            resolvers, measured)) {
        if (gAssetFailurePoint == EAssetFailureNone) {
            gAssetFailurePoint = EAssetFailureObjectManifest;
            gAssetFailureCode = KErrCorrupt;
        }
        return false;
    }
    if (measured.meshObjectCount != 1 ||
        measured.spriteObjectCount != 1 ||
        measured.spriteFrameCount != 1 ||
        measured.flickerValueCount != 0 ||
        measured.colorAnimationValueCount != 0 ||
        measured.animationVertexCount != KLevel1MeshVertexCapacity) {
        gAssetFailurePoint = EAssetFailureObjectManifest;
        gAssetFailureCode = KErrCorrupt;
        return false;
    }

    gLevel1ObjectLoadStorage = SoftwareLevelObjectLoadStorage();
    gLevel1ObjectLoadStorage.meshObjects = gLevel1MeshObjects;
    gLevel1ObjectLoadStorage.meshObjectCapacity = 1;
    gLevel1ObjectLoadStorage.spriteInstances = gLevel1SpriteObjects;
    gLevel1ObjectLoadStorage.spriteInstanceCapacity = 1;
    gLevel1ObjectLoadStorage.spriteDefinitions = gLevel1SpriteDefinitions;
    gLevel1ObjectLoadStorage.spriteDefinitionCapacity = 1;
    gLevel1ObjectLoadStorage.spriteFrames = gLevel1SpriteFrames;
    gLevel1ObjectLoadStorage.spriteFrameCapacity = 1;
    gLevel1ObjectLoadStorage.animationVertices =
        gLevel1ObjectAnimationVertices;
    gLevel1ObjectLoadStorage.animationVertexCapacity =
        KLevel1MeshVertexCapacity;

    SoftwareLevelObjectRequirements loaded;
    if (!SoftwareLevelObjectLoader::load(
            gLevel1ObjectManifest, gLevel1ObjectManifestLength,
            resolvers, gLevel1ObjectLoadStorage, loaded) ||
        loaded.meshObjectCount != measured.meshObjectCount ||
        loaded.spriteObjectCount != measured.spriteObjectCount ||
        loaded.spriteFrameCount != measured.spriteFrameCount ||
        loaded.animationVertexCount != measured.animationVertexCount) {
        if (gAssetFailurePoint == EAssetFailureNone) {
            gAssetFailurePoint = EAssetFailureObjectManifest;
            gAssetFailureCode = KErrCorrupt;
        }
        return false;
    }
    gLevel1ObjectRequirements = loaded;
    return true;
}

struct TLoadedWeaponImage {
    QInt32* pixels;
    TextureInfo info;
    SoftwareTextureView view;

    TLoadedWeaponImage() : pixels(0), info(), view() {}
};

bool loadWeaponImage(RFs& fileServer,
                     const TDesC& drive,
                     const TDesC& filename,
                     TLoadedWeaponImage& image)
{
    TInt length = 0;
    if (!readPrivateAsset(fileServer, drive, filename, gTextureFile,
                          KMaximumTextureFileBytes, length) ||
        !inspectQtxTexture(gTextureFile, length, image.info)) {
        return false;
    }

    const TInt pixelCount = image.info.width * image.info.height;
    image.pixels = static_cast<QInt32*>(User::Alloc(
        pixelCount * static_cast<TInt>(sizeof(QInt32))));
    if (image.pixels == 0) return false;
    if (!decodeQtxTexture(gTextureFile, length, image.pixels,
                          pixelCount, image.info)) {
        User::Free(image.pixels);
        image.pixels = 0;
        image.info = TextureInfo();
        return false;
    }
    image.view = SoftwareTextureView(
        image.pixels, pixelCount, image.info.width, image.info.height,
        image.info.hasTransparentTexels,
        image.info.hasTransparentTexels || image.info.alphaMixing,
        image.info.alphaMixing);
    return true;
}

void releaseLoadedWeaponImage(TLoadedWeaponImage& image)
{
    if (image.pixels != 0) User::Free(image.pixels);
    image.pixels = 0;
    image.info = TextureInfo();
    image.view = SoftwareTextureView();
}

void releaseWeaponSpriteBuffers()
{
    if (gWeaponSpriteBuffers.weaponPixels != 0) {
        User::Free(gWeaponSpriteBuffers.weaponPixels);
    }
    if (gWeaponSpriteBuffers.firePixels != 0) {
        User::Free(gWeaponSpriteBuffers.firePixels);
    }
    if (gWeaponSpriteBuffers.sightPixels != 0) {
        User::Free(gWeaponSpriteBuffers.sightPixels);
    }
    if (gWeaponSixteenStorage != 0) {
        User::Free(gWeaponSixteenStorage);
        gWeaponSixteenStorage = 0;
    }
    if (gWeaponFireSixteenStorage != 0) {
        User::Free(gWeaponFireSixteenStorage);
        gWeaponFireSixteenStorage = 0;
    }
    if (gWeaponSightSixteenStorage != 0) {
        User::Free(gWeaponSightSixteenStorage);
        gWeaponSightSixteenStorage = 0;
    }
    gWeaponSpriteBuffers = SoftwareWeaponSpriteBuffers();
    gWeaponSprites = SoftwareWeaponSpriteSet();
    gWeaponOverlayAvailable = false;
}

bool allocateWeaponSpriteBuffer(QInt32 pixelCount,
                                QInt32*& pixels,
                                QInt32& capacity)
{
    pixels = 0;
    capacity = 0;
    if (pixelCount <= 0) return true;
    const TInt byteCount = pixelCount * static_cast<TInt>(sizeof(QInt32));
    pixels = static_cast<QInt32*>(User::Alloc(byteCount));
    if (pixels == 0) return false;
    capacity = pixelCount;
    return true;
}

/*
 * Index 0 keeps the original FN2000 tuning. The other three bodies reuse it
 * until a per-weapon QWG is shipped for them: a missing file must not make a
 * weapon unselectable, and the tuning is a gameplay profile, not art.
 */
bool loadWeaponTuning(RFs& fileServer, const TDesC& drive, TInt weaponIndex)
{
    const TInt savedFailurePoint = gAssetFailurePoint;
    const TInt savedFailureCode = gAssetFailureCode;
    const TInt savedFileSize = gAssetFileSize;
    const TInt savedBytesRead = gAssetBytesRead;
    unsigned char config[26];
    TInt length = 0;
    TFileName configName;
    if (weaponIndex == 0) {
        configName.Copy(_L("weapon-gameplay.qwg"));
    } else {
        configName.Format(_L("weapon%d.qwg"), weaponIndex);
    }
    bool loaded = readPrivateAsset(
        fileServer, drive, configName, config,
        sizeof(config), length) &&
        SoftwareWeaponController::loadTuning(config, length, gWeaponTuning);
    if (!loaded && weaponIndex != 0) {
        /* Fall back to the original profile rather than to the built-in
           defaults, so a weapon without its own QWG still fires and sways
           like the FN2000 instead of silently losing its tuning. */
        length = 0;
        loaded = readPrivateAsset(
            fileServer, drive, _L("weapon-gameplay.qwg"), config,
            sizeof(config), length) &&
            SoftwareWeaponController::loadTuning(config, length, gWeaponTuning);
    }
    gAssetFailurePoint = savedFailurePoint;
    gAssetFailureCode = savedFailureCode;
    gAssetFileSize = savedFileSize;
    gAssetBytesRead = savedBytesRead;
    return loaded;
}

bool loadWeaponAssets(RFs& fileServer, const TDesC& drive, TInt weaponIndex)
{
    const TInt savedFailurePoint = gAssetFailurePoint;
    const TInt savedFailureCode = gAssetFailureCode;
    const TInt savedFileSize = gAssetFileSize;
    const TInt savedBytesRead = gAssetBytesRead;
    releaseWeaponSpriteBuffers();
    gWeaponTuning = SoftwareWeaponTuning();
    gWeaponTuningLoadedFromAssets =
        loadWeaponTuning(fileServer, drive, weaponIndex);
    TLoadedWeaponImage weapon;
    TLoadedWeaponImage fire;
    TLoadedWeaponImage sight;

    /* Index zero in a local Quantum JAR is the default USP. Its art and
       QWG1/QWG2 gameplay tuning are optional; the sample still uses the test
       profile when the private weapon assets are not installed.

       The other three bodies are named weapon<N>.qtx, matching the files the
       converter produced from the legacy QTX1 arsenal sprites. */
    TFileName weaponName;
    if (weaponIndex == 0) {
        weaponName.Copy(_L("weapon.qtx"));
    } else {
        weaponName.Format(_L("weapon%d.qtx"), weaponIndex);
    }
    const bool hasWeapon = loadWeaponImage(
        fileServer, drive, weaponName, weapon);
    if (!hasWeapon) {
        gAssetFailurePoint = savedFailurePoint;
        gAssetFailureCode = savedFailureCode;
        gAssetFileSize = savedFileSize;
        gAssetBytesRead = savedBytesRead;
        return false;
    }
    (void)loadWeaponImage(fileServer, drive, _L("weapon-fire.qtx"), fire);
    (void)loadWeaponImage(fileServer, drive, _L("weapon-sight.qtx"), sight);
    /*
     * weapon-sight-weapon.qtx is deliberately not loaded. It is the zoomed
     * reticle, and the reticle is drawn only when `settings.zoom` is set,
     * which never happens in this sample (the QWG has hasZoom, but nothing
     * raises zoom). Loading it cost a 240x256 decode plus a 300x320 resize
     * buffer -- 384,000 bytes of 32-bit pixels -- to sit unread, and it was
     * the last allocation that pushed the peak past the 2 MiB heap ceiling.
     * Leaving the source view empty makes measureSprites() report a zero
     * pixel count, so prepareSprites() and draw() both skip it by design.
     */

    SoftwareWeaponSpriteSources sources;
    sources.weapon = weapon.view;
    sources.fire = fire.view;
    sources.sight = sight.view;
    SoftwareWeaponCreateSettings createSettings;
    createSettings.viewportWidth = gRenderWidth;
    createSettings.viewportHeight = gRenderHeight;
    createSettings.resizeWeapons = false;
    createSettings.customPosition = gWeaponTuning.customPosition;
    createSettings.muzzleXRatio = gWeaponTuning.muzzleXRatio;
    createSettings.muzzleYRatio = gWeaponTuning.muzzleYRatio;
    createSettings.attackIntensity =
        static_cast<float>(gWeaponTuning.attackIntensityQ8) / 256.0f;

    SoftwareWeaponSpriteRequirements requirements;
    bool ready = SoftwareWeaponRenderer::measureSprites(
        sources, createSettings, requirements);
    if (ready) {
        ready = allocateWeaponSpriteBuffer(
                    requirements.weapon.pixelCount,
                    gWeaponSpriteBuffers.weaponPixels,
                    gWeaponSpriteBuffers.weaponCapacity) &&
                allocateWeaponSpriteBuffer(
                    requirements.fire.pixelCount,
                    gWeaponSpriteBuffers.firePixels,
                    gWeaponSpriteBuffers.fireCapacity) &&
                allocateWeaponSpriteBuffer(
                    requirements.sight.pixelCount,
                    gWeaponSpriteBuffers.sightPixels,
                    gWeaponSpriteBuffers.sightCapacity);
    }
    if (ready) {
        ready = SoftwareWeaponRenderer::prepareSprites(
            sources, createSettings, requirements, gWeaponSpriteBuffers,
            gWeaponSprites);
    }
    /*
     * Build the 16-bit weapon images from the resized 32-bit result, so the
     * bilinear ImageResize path is not duplicated.
     */
    if (ready) {
        /* Weapon body in RGB1555 (5-5-5-1) and the muzzle flash in ARGB4444,
           per the required 16-bit layout. */
        (void)buildWeaponSixteenImage(
            gWeaponSprites.weapon, gWeaponSixteenStorage, false);
        (void)buildWeaponSixteenImage(
            gWeaponSprites.fire, gWeaponFireSixteenStorage, true);
        (void)buildWeaponSixteenImage(
            gWeaponSprites.sight, gWeaponSightSixteenStorage, true);
    }

    releaseLoadedWeaponImage(weapon);
    releaseLoadedWeaponImage(fire);
    releaseLoadedWeaponImage(sight);
    gAssetFailurePoint = savedFailurePoint;
    gAssetFailureCode = savedFailureCode;
    gAssetFileSize = savedFileSize;
    gAssetBytesRead = savedBytesRead;
    if (!ready || gWeaponSprites.weapon.view.pixels == 0) {
        releaseWeaponSpriteBuffers();
        return false;
    }
    gWeaponOverlayAvailable = true;
    return true;
}
#endif

/*
 * Build the single 16-bit level for each texture that the fast path can
 * rasterize (drawmodes 0 and 9 only). Textures without one keep using the
 * 32-bit reference kernel, so a failed conversion is not fatal.
 *
 * Mipmapping is not used here: exactly one level per texture, built from
 * the already-decoded 8888 image. Textures with transparent texels become
 * RGB1555 so the zero-texel key survives; the rest stay RGB565.
 */
/*
 * Produce the single 16-bit level for one texture.
 *
 * A QTX2 asset already holds it: the format was chosen at build time, so the
 * view points straight at the file image and no conversion runs at start-up.
 * A QTX1 asset still has to be converted here, which is the path this change
 * is retiring.
 *
 * Mipmaps are not involved: exactly one level per texture.
 */
bool texture16ViewFor(const TextureInfo& info,
                      const QInt32* pixels8888,
                      QUint16* conversionStorage,
                      QInt32 conversionCapacity,
                      SoftwareTexture16View& view)
{
    if (info.isQtx2 && info.qtx2Pixels != 0) {
        SoftwareQtx2Header header;
        header.format = info.qtx2Format;
        header.flags = code::Rendering::SoftwareQtx2FlagHas16;
        header.width = info.width;
        header.height = info.height;
        view = code::Rendering::softwareQtx2TextView(header, info.qtx2Pixels);
        return view.pixels != 0 && view.pixelCount > 0;
    }

    bool is1555 = false;
    bool hasTransparent = false;
    if (!SoftwareTexture16::convertLevel8888To16(
            pixels8888, info.width, info.height, true,
            conversionStorage, conversionCapacity, is1555, hasTransparent)) {
        return false;
    }
    view = SoftwareTexture16View(
        conversionStorage, info.width * info.height,
        info.width, info.height, is1555, hasTransparent);
    return true;
}

bool buildTexture16Levels(const TextureInfo& forest,
                          const TextureInfo& map,
                          const TextureInfo* riverFrames,
                          QInt32 riverFrameCount,
                          const TextureInfo& sky)
{
    if (gForestPixels16 == 0 || gMapPixels16 == 0 || gRiverPixels16 == 0 ||
        gSkyPixels16 == 0) {
        return false;
    }

    if (!texture16ViewFor(forest, gForestPixels, gForestPixels16,
                          KForestTexturePixels, gWorldViews16[0]) ||
        !texture16ViewFor(map, gMapPixels, gMapPixels16,
                          KMapTexturePixels, gWorldViews16[1])) {
        return false;
    }
    gWorldViews16[2] = gWorldViews16[1];
    gWorldViews16[3] = gWorldViews16[1];

    QInt32 frameIndex;
    for (frameIndex = 0; frameIndex < KRiverTextureFrameCount; ++frameIndex) {
        if (frameIndex < riverFrameCount) {
            const TextureInfo& frame = riverFrames[frameIndex];
            QUint16* const pixels =
                gRiverPixels16 + frameIndex * KRiverFramePixelCount;
            if (!texture16ViewFor(
                    frame, gRiverPixels + frameIndex * KRiverFramePixelCount,
                    pixels, KRiverFramePixelCount,
                    gRiverAnimationViews16[frameIndex])) {
                return false;
            }
        } else {
            gRiverAnimationViews16[frameIndex] =
                gRiverAnimationViews16[0];
        }
    }
    gWorldViews16[4] = gRiverAnimationViews16[0];

    return texture16ViewFor(sky, gSkyPixels, gSkyPixels16,
                            KSkyTexturePixels, gSkyView16);
}

void setupWorldMaterials(const TextureInfo& forest,
                         const TextureInfo& map,
                         const TextureInfo* riverFrames,
                         QInt32 riverFrameCount)
{
    gWorldMipViews[0] = SoftwareTextureView(
        gForestPixels, forest.width * forest.height,
        forest.width, forest.height, forest.hasTransparentTexels,
        forest.hasTransparentTexels, forest.alphaMixing);
    gWorldMipViews[1] = SoftwareTextureView(
        gMapPixels, map.width * map.height,
        map.width, map.height, map.hasTransparentTexels,
        map.hasTransparentTexels, map.alphaMixing);
    gWorldMipViews[2] = gWorldMipViews[1];
    gWorldMipViews[3] = gWorldMipViews[1];

    QInt32 frameIndex;
    for (frameIndex = 0; frameIndex < KRiverTextureFrameCount; ++frameIndex) {
        if (frameIndex < riverFrameCount) {
            const TextureInfo& frame = riverFrames[frameIndex];
            const QInt32* const pixels =
                gRiverPixels + frameIndex * KRiverFramePixelCount;
            gRiverAnimationMipViews[frameIndex] = SoftwareTextureView(
                pixels, frame.width * frame.height,
                frame.width, frame.height, frame.hasTransparentTexels,
                frame.hasTransparentTexels, frame.alphaMixing);
        } else {
            gRiverAnimationMipViews[frameIndex] =
                gRiverAnimationMipViews[0];
        }
        gRiverAnimationFrames[frameIndex] = SoftwareMeshTextureFrame(
            &gRiverAnimationMipViews[frameIndex], 1);
        gRiverAnimationFrames[frameIndex].mipLevels16 =
            gTexture16Ready ? &gRiverAnimationViews16[frameIndex] : 0;
    }
    gWorldMipViews[4] = gRiverAnimationMipViews[0];

    const QInt32 biases[5] = { -20000, 0, -3500, -7000, 0 };
    const bool alphaMixing[5] = {
        forest.alphaMixing, map.alphaMixing, map.alphaMixing,
        map.alphaMixing, riverFrames[0].alphaMixing
    };
    TInt index;
    for (index = 0; index < 5; ++index) {
        gWorldMaterials[index].mipLevels = &gWorldMipViews[index];
        gWorldMaterials[index].mipLevelCount = 1;
        gWorldMaterials[index].mipLevels16 =
            gTexture16Ready ? &gWorldViews16[index] : 0;
        gWorldMaterials[index].hasDrawModeOverride = false;
        gWorldMaterials[index].drawMode = 9;
        gWorldMaterials[index].perspectiveCorrection = true;
        gWorldMaterials[index].textureAlphaMixing = alphaMixing[index];
        gWorldMaterials[index].depthBias = biases[index];
        gWorldMaterials[index].animationFrames = 0;
        gWorldMaterials[index].animationFrameCount = 0;
        gWorldMaterials[index].animationSpeed = 1.0f;
    }
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
    // Level 1's WTEX entry is 6 FPS followed by the four river frames.
    // Keep this only on the sym9 walk target; the older fixed-scene package
    // retains its one-file static river texture path.
    if (riverFrameCount > 1) {
        gWorldMaterials[4].animationFrames = gRiverAnimationFrames;
        gWorldMaterials[4].animationFrameCount = riverFrameCount;
        gWorldMaterials[4].animationSpeed = KRiverTextureAnimationSpeed;
    }
    // The river is material slot 4; keep its refraction mode local to the walk
    // candidate while the other world materials use the standard mode 9.
    gWorldMaterials[4].hasDrawModeOverride = true;
    gWorldMaterials[4].drawMode = 7;
#endif

    gSkyMipView = SoftwareTextureView(
        gSkyPixels, KMaximumTexturePixels,
        KMaximumTextureWidth, KMaximumTextureHeight, false);
    gSkyMaterial[0].mipLevels = &gSkyMipView;
    gSkyMaterial[0].mipLevelCount = 1;
    gSkyMaterial[0].mipLevels16 = gTexture16Ready ? &gSkyView16 : 0;
    gSkyMaterial[0].hasDrawModeOverride = true;
    gSkyMaterial[0].drawMode = 0;
    gSkyMaterial[0].perspectiveCorrection = false;
    gSkyMaterial[0].textureAlphaMixing = false;
    gSkyMaterial[0].depthBias = static_cast<QInt32>(-1000000000);
}

bool loadWorldModel(RFs& fileServer,
                    const TDesC& drive,
                    SoftwareMesh3DRequirements& requirements)
{
    gRenderStage = ERenderStageWorldMesh;
    TInt length = 0;
    if (!readPrivateAsset(fileServer, drive, _L("L3D_new.3d"),
                          gWorldFile, KMaximumWorldFileBytes, length) ||
        !SoftwareMesh3DParser::measure(
            gWorldFile, length, requirements) ||
        requirements.meshCount != 1 ||
        requirements.vertexCount != KWorldVertexCapacity ||
        requirements.polygonCount != KWorldPolygonCapacity ||
        requirements.materialCount != 5) {
        return false;
    }

    SoftwareMesh3DRequirements parsed;
    if (!SoftwareMesh3DParser::parse(
            gWorldFile, length, 6.0f, 6.0f, 6.0f,
            gWorldVertices, KWorldVertexCapacity,
            gWorldPolygons, KWorldPolygonCapacity,
            gWorldParts, 1, parsed)) {
        return false;
    }

    gRenderStage = ERenderStageLightmap;
    TInt lightmapLength = 0;
    if (!readPrivateAsset(fileServer, drive, _L("lightmap.vla"),
                          gLightmapFile, KMaximumLightmapBytes,
                          lightmapLength) ||
        !SoftwareMesh3DParser::applyGrayscaleLightmap(
            gLightmapFile, lightmapLength, gWorldPolygons,
            KWorldPolygonCapacity)) {
        return false;
    }

    gWorldView = SoftwareMeshView(
        gWorldVertices, KWorldVertexCapacity,
        gWorldPolygons, KWorldPolygonCapacity,
        gWorldMaterials, 5);
    return true;
}

#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
void releaseWalkCollisionStorage()
{
    if (gWalkCollisionPolygons == 0) return;
    QInt32 polygonIndex;
    for (polygonIndex = 0; polygonIndex < KWorldPolygonCapacity;
         ++polygonIndex) {
        gWalkCollisionPolygons[polygonIndex].~CollisionPolygon();
    }
    User::Free(gWalkCollisionPolygons);
    gWalkCollisionPolygons = 0;
    gWalkCollisionView = CollisionMeshView();
}

bool buildWalkCollisionView()
{
    QInt32 materialIndex;
    QInt32 polygonIndex;
    const TInt collisionPolygonBytes =
        KWorldPolygonCapacity * static_cast<TInt>(sizeof(CollisionPolygon));
    gWalkCollisionFailurePoint = EWalkCollisionFailureStorage;
    gWalkCollisionView = CollisionMeshView();
    gWalkCollisionBuiltPolygonCount = 0;
    gAssetFailureCode = KErrNone;
    if (gWalkCollisionPolygons == 0) {
        gWalkCollisionPolygons = static_cast<CollisionPolygon*>(
            User::Alloc(collisionPolygonBytes));
        if (gWalkCollisionPolygons == 0) {
            gAssetFailureCode = KErrNoMemory;
            return false;
        }
        for (polygonIndex = 0; polygonIndex < KWorldPolygonCapacity;
             ++polygonIndex) {
            new (&gWalkCollisionPolygons[polygonIndex]) CollisionPolygon();
        }
    }

    for (materialIndex = 0; materialIndex < KWalkMaterialCount;
         ++materialIndex) {
        /* Java Texture defaults to collidable and castsShadow. */
        gWalkCollisionMaterials[materialIndex] =
            CollisionMaterialFlags(true, true);
    }

    gWalkCollisionFailurePoint = EWalkCollisionFailureAdapter;
    const bool meshBuilt = CollisionMeshAdapter::build(
        gWorldView, gWalkCollisionMaterials, KWalkMaterialCount,
        gWalkCollisionPolygons, KWorldPolygonCapacity,
        gWalkCollisionView);
    if (!meshBuilt) {
        gAssetFailureCode = KErrCorrupt;
        releaseWalkCollisionStorage();
        return false;
    }
    gWalkCollisionBuiltPolygonCount = gWalkCollisionView.polygonCount;

    /* Snap the origin spawn to nearby Level 41 floor before the first frame. */
    gWalkCollisionFailurePoint = EWalkCollisionFailureSpawnFloor;
    gWalkState = SoftwareWalkState(0, 0, 0, 0);
    if (!SoftwareWalkController::update(
            gWalkCollisionView, SoftwareWalkInput(), 1,
            gWalkSettings, gWalkState)) {
        gAssetFailureCode = KErrCorrupt;
        releaseWalkCollisionStorage();
        return false;
    }

    gWalkCollisionFailurePoint = EWalkCollisionFailureNone;
    return true;
}

bool fireLevel41Weapon()
{
    const bool wasReloading =
        SoftwareWeaponController::isReloading(gWeaponState);
    if (!SoftwareWeaponController::fire(gWeaponTuning, gWeaponState)) {
        if (!wasReloading &&
            SoftwareWeaponController::isReloading(gWeaponState) &&
            gLevel41Audio != 0) {
            gLevel41Audio->PlayReload();
        }
        return false;
    }
    if (gLevel41Audio != 0) gLevel41Audio->PlayShot();

    /*
     * This walk sample has no enemy or damage simulation. Skip the optional
     * world-only raycast in the firing path until the E65 firing regression is
     * isolated; it supplied only WALL/CLEAR HUD feedback and is not required
     * to animate or play a shot.
     */
    SoftwareWeaponController::recordShotResult(
        gWeaponState, false, -1, 0, 0, 0);
    return true;
}

/*
 * Equip the next weapon body. The previous body's controller state is saved
 * first so it survives a full cycle, then the sprite buffers are released and
 * the next body is loaded from scratch. A failed load rolls back to the body
 * that was already working: a missing or corrupt sprite must never leave the
 * player holding nothing.
 *
 * Loading is the same code path start-up uses, so the RGB1555 16-bit twin and
 * the ARGB4444 muzzle flash are rebuilt for the new body exactly as they are
 * for the first one.
 */
bool switchLevel41Weapon()
{
    if (gWeaponFileServer == 0) return false;
    const TInt previous = gCurrentWeapon;
    const TInt next = code::Rendering::level41NextWeapon(previous);
    if (next == previous) return false;

    if (code::Rendering::isValidLevel41Weapon(previous)) {
        gWeaponSavedStates[previous] = gWeaponState;
        gWeaponStateInitialized[previous] = true;
    }

    if (!loadWeaponAssets(*gWeaponFileServer, gWeaponDrive, next) ||
        !gWeaponTuningLoadedFromAssets) {
        if (code::Rendering::isValidLevel41Weapon(previous)) {
            (void)loadWeaponAssets(*gWeaponFileServer, gWeaponDrive, previous);
            gWeaponState = gWeaponSavedStates[previous];
            gWeaponState.dxMax = gWeaponSprites.maxOffsetX;
            gWeaponState.dyMax = gWeaponSprites.maxOffsetY;
        }
        return false;
    }

    if (gWeaponStateInitialized[next]) {
        /* Restore this weapon's own recoil/sway, but take the sway limits from
           the sprite set that was just rebuilt for the current viewport. */
        gWeaponState = gWeaponSavedStates[next];
        gWeaponState.dxMax = gWeaponSprites.maxOffsetX;
        gWeaponState.dyMax = gWeaponSprites.maxOffsetY;
    } else {
        if (!SoftwareWeaponController::initialize(
                gWeaponTuning, gWeaponSprites.maxOffsetX,
                gWeaponSprites.maxOffsetY, gWeaponState)) {
            return false;
        }
        gWeaponStateInitialized[next] = true;
    }
    gCurrentWeapon = next;
    /* The muzzle frame belongs to the weapon that fired it. */
    gLastWeaponUpdateMicros = 0;
    return true;
}

bool isLevel41ArrowKey(TInt code)
{
    return code == EKeyUpArrow || code == EKeyDownArrow ||
           code == EKeyLeftArrow || code == EKeyRightArrow;
}

/* Normalize either Symbian scan codes or character/key codes to EKey* values.
   The SIS v1.0.33 handler indexes arrows by TKeyEvent::iScanCode; do not compare
   EStdScanCode values directly with the distinct EKey* code range. */
TInt level41WalkArrowCode(const TKeyEvent& keyEvent)
{
    switch (keyEvent.iScanCode) {
    case EStdKeyUpArrow: return EKeyUpArrow;
    case EStdKeyDownArrow: return EKeyDownArrow;
    case EStdKeyLeftArrow: return EKeyLeftArrow;
    case EStdKeyRightArrow: return EKeyRightArrow;
    default:
        return isLevel41ArrowKey(keyEvent.iCode) ? keyEvent.iCode : EKeyNull;
    }
}

/* Keep explicit key-down/up state where available. Event-only Window Servers
   use one-frame pulses for movement, jump, and camera-look keys. */
bool setLevel41WalkKeyState(const TKeyEvent& keyEvent, bool isDown)
{
    if (code::Rendering::isLevel41WeaponSwitchKey(
            keyEvent.iCode, keyEvent.iScanCode)) {
        /*
         * Switch on the transition into the held state, not on every repeat.
         * Event-only Window Servers deliver no key-up, so the "unknown" flag
         * makes the first delivery count as a fresh press instead of being
         * swallowed as a repeat of the previous one.
         */
        if (isDown && (!gWeaponSwitchStateKnown || !gWeaponSwitchHeld)) {
            (void)switchLevel41Weapon();
        }
        gWeaponSwitchHeld = isDown;
        gWeaponSwitchStateKnown = true;
        return true;
    }

    if (code::Rendering::isLevel41JumpKey(
            keyEvent.iCode, keyEvent.iScanCode)) {
        if (isDown && (!gWalkJumpStateKnown || !gWalkJumpHeld)) {
            gWalkJumpPulse = true;
        }
        gWalkJumpHeld = isDown;
        gWalkJumpStateKnown = true;
        return true;
    }

    if (code::Rendering::isLevel41CameraLookUpKey(
            keyEvent.iCode, keyEvent.iScanCode)) {
        gCameraLookUpHeld = isDown;
        gCameraLookUpStateKnown = true;
        return true;
    }
    if (code::Rendering::isLevel41CameraLookDownKey(
            keyEvent.iCode, keyEvent.iScanCode)) {
        gCameraLookDownHeld = isDown;
        gCameraLookDownStateKnown = true;
        return true;
    }

    const TInt code = level41WalkArrowCode(keyEvent);
    if (code == EKeyUpArrow) {
        gWalkForwardHeld = isDown;
        gWalkForwardStateKnown = true;
    } else if (code == EKeyDownArrow) {
        gWalkBackwardHeld = isDown;
        gWalkBackwardStateKnown = true;
    } else if (code == EKeyLeftArrow) {
        gWalkLeftHeld = isDown;
        gWalkLeftStateKnown = true;
    } else if (code == EKeyRightArrow) {
        gWalkRightHeld = isDown;
        gWalkRightStateKnown = true;
    } else {
        return false;
    }
    return true;
}

void clearLevel41WalkInput()
{
    gWalkForwardHeld = false;
    gWalkBackwardHeld = false;
    gWalkForwardStateKnown = false;
    gWalkBackwardStateKnown = false;
    gWalkForwardPulse = false;
    gWalkBackwardPulse = false;
    gWalkJumpPulse = false;
    gWalkJumpHeld = false;
    gWalkJumpStateKnown = false;
    gWalkLeftHeld = false;
    gWalkRightHeld = false;
    gWalkLeftStateKnown = false;
    gWalkRightStateKnown = false;
    gCameraLookUpHeld = false;
    gCameraLookDownHeld = false;
    gCameraLookUpStateKnown = false;
    gCameraLookDownStateKnown = false;
    gCameraLookUpPulse = false;
    gCameraLookDownPulse = false;
    gCameraPitchRemainder = 0;
    gWalkState.turnVelocityDegreesPerSecondQ16 = 0;
    gWalkState.turnRemainderQ16 = 0;
}

bool handleLevel41WalkKey(const TKeyEvent& keyEvent)
{
    if (keyEvent.iCode == EKeyEscape || keyEvent.iScanCode == EStdKeyEscape) {
        return false;
    }
    if (keyEvent.iCode == '5' || keyEvent.iCode == EKeyDevice3 ||
        keyEvent.iCode == EKeyEnter) {
        (void)fireLevel41Weapon();
        return true;
    }
    if (code::Rendering::isLevel41JumpKey(
            keyEvent.iCode, keyEvent.iScanCode)) {
        /* Key-down/up capable servers pulse on the first down edge; servers
           that only send EEventKey use this as a one-frame press pulse. */
        if (!gWalkJumpStateKnown) gWalkJumpPulse = true;
        return true;
    }
    if (code::Rendering::isLevel41CameraLookUpKey(
            keyEvent.iCode, keyEvent.iScanCode)) {
        if (!gCameraLookUpStateKnown) gCameraLookUpPulse = true;
        return true;
    }
    if (code::Rendering::isLevel41CameraLookDownKey(
            keyEvent.iCode, keyEvent.iScanCode)) {
        if (!gCameraLookDownStateKnown) gCameraLookDownPulse = true;
        return true;
    }

    const TInt arrowCode = level41WalkArrowCode(keyEvent);
    if (arrowCode == EKeyUpArrow) {
        if (!gWalkForwardStateKnown) gWalkForwardPulse = true;
        return true;
    }
    if (arrowCode == EKeyDownArrow) {
        if (!gWalkBackwardStateKnown) gWalkBackwardPulse = true;
        return true;
    }
    if (arrowCode == EKeyLeftArrow || arrowCode == EKeyRightArrow) {
        const QInt32 direction = arrowCode == EKeyLeftArrow ? 1 : -1;
        const bool heldStateKnown = arrowCode == EKeyLeftArrow
            ? gWalkLeftStateKnown : gWalkRightStateKnown;
        if (heldStateKnown) return true;
        /* Event-only servers use the controller's discrete/impulse fallback. */
        if (!SoftwareWalkController::turn(direction, gWalkSettings, gWalkState)) {
            gAssetFailureCode = KErrCorrupt;
            return false;
        }
        return true;
    }

    /* Ignore unrelated keys; only Escape is an explicit exit command. */
    return true;
}

bool handleLevel41WalkEvent(const TWsEvent& event)
{
    if (event.Type() == EEventKeyDown || event.Type() == EEventKeyUp) {
        const TKeyEvent* keyEvent = event.Key();
        if (keyEvent != 0) {
            (void)setLevel41WalkKeyState(
                *keyEvent, event.Type() == EEventKeyDown);
        }
        return true;
    }
    if (event.Type() == EEventKey) {
        const TKeyEvent* keyEvent = event.Key();
        return keyEvent != 0 && handleLevel41WalkKey(*keyEvent);
    }
    return true;
}
#endif

bool loadSkyboxModel(RFs& fileServer,
                     const TDesC& drive,
                     SoftwareMesh3DRequirements& requirements)
{
    gRenderStage = ERenderStageSkyboxMesh;
    gSkyboxRequirements = SoftwareMesh3DRequirements();
    TInt length = 0;
    if (!readPrivateAsset(fileServer, drive, _L("skybox8.3d"),
                          gSkyboxFile, KMaximumSkyboxFileBytes, length)) {
        return false;
    }
    if (!SoftwareMesh3DParser::measure(
            gSkyboxFile, length, gSkyboxRequirements)) {
        gAssetFailurePoint = EAssetFailureMeshMeasure;
        gAssetFailureCode = KErrCorrupt;
        return false;
    }
    const bool level1ArchiveSky =
        gSkyboxRequirements.vertexCount == 25 &&
        gSkyboxRequirements.polygonCount == 20;
    const bool legacyLevel41Sky =
        gSkyboxRequirements.vertexCount == 24 &&
        gSkyboxRequirements.polygonCount == 22;
    if (gSkyboxRequirements.meshCount != 1 ||
        (!level1ArchiveSky && !legacyLevel41Sky) ||
        gSkyboxRequirements.materialCount != 1) {
        gAssetFailurePoint = EAssetFailureMeshLayout;
        gAssetFailureCode = KErrCorrupt;
        return false;
    }
    requirements = gSkyboxRequirements;

    SoftwareMesh3DRequirements parsed;
    if (!SoftwareMesh3DParser::parse(
            gSkyboxFile, length, 7.0f, 7.0f, 7.0f,
            gSkyVertices, KSkyVertexCapacity,
            gSkyPolygons, KSkyPolygonCapacity,
            gSkyParts, 1, parsed)) {
        gAssetFailurePoint = EAssetFailureMeshParse;
        gAssetFailureCode = KErrCorrupt;
        return false;
    }

    gSkyView = SoftwareMeshView(
        gSkyVertices, parsed.vertexCount,
        gSkyPolygons, parsed.polygonCount,
        gSkyMaterial, parsed.materialCount);
    return true;
}

bool loadLevel41(RFs& fileServer, const TDesC& drive)
{
    TextureInfo forest;
    TextureInfo map;
    TextureInfo riverFrames[KRiverTextureFrameCount];
    TextureInfo sky;
    QInt32 riverFrameCount = 1;
    gRenderStage = ERenderStageForestTexture;
    if (!loadTexture(fileServer, drive, _L("forest.qtx"),
                     gForestPixels, KForestTexturePixels, forest)) {
        return false;
    }
    gRenderStage = ERenderStageMapTexture;
    if (!loadTexture(fileServer, drive, _L("map.qtx"), gMapPixels,
                     KMapTexturePixels, map)) {
        return false;
    }
    gRenderStage = ERenderStageRiverTexture;
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
    // level1.txt declares `6:river0.bmp:river1.bmp:river2.bmp:river3.bmp`.
    for (QInt32 frameIndex = 0;
         frameIndex < KRiverTextureFrameCount;
         ++frameIndex) {
        TBuf<12> filename;
        filename.Copy(_L("river"));
        filename.AppendNum(frameIndex);
        filename.Append(_L(".qtx"));
        if (!loadTexture(
                fileServer, drive, filename,
                gRiverPixels + frameIndex * KRiverFramePixelCount,
                KRiverFramePixelCount, riverFrames[frameIndex])) {
            return false;
        }
    }
    riverFrameCount = KRiverTextureFrameCount;
#else
    // The fixed-scene Level 41 target keeps its original one-frame asset name.
    if (!loadTexture(fileServer, drive, _L("river.qtx"),
                     gRiverPixels, KRiverFramePixelCount, riverFrames[0])) {
        return false;
    }
#endif
    gRenderStage = ERenderStageSkyTexture;
    if (!loadTexture(fileServer, drive, _L("sky.qtx"),
                     gSkyPixels, KSkyTexturePixels, sky)) {
        return false;
    }
    gRenderStage = ERenderStageTextureDimensions;
    // Keep the existing 256x128 forest profile and accept sym9.zip's exact
    // Level 1 forestOrigy.png dimensions (128x64) without resampling it.
    const bool supportedForestSize =
        (forest.width == 256 && forest.height == 128) ||
        (forest.width == 128 && forest.height == 64);
    if (!supportedForestSize || map.width != 256 || map.height != 256 ||
        sky.width != 256 || sky.height != 256) {
        return false;
    }
    QInt32 frameIndex;
    for (frameIndex = 0; frameIndex < riverFrameCount; ++frameIndex) {
        if (riverFrames[frameIndex].width != KRiverTextureWidth ||
            riverFrames[frameIndex].height != KRiverTextureHeight) {
            return false;
        }
    }
    /*
     * Build the 16-bit levels before the materials take their 16-bit view
     * pointers. Failure leaves gTexture16Ready false, which keeps every
     * material on the 32-bit reference kernels.
     */
    gTexture16Ready = buildTexture16Levels(
        forest, map, riverFrames, riverFrameCount, sky);
    setupWorldMaterials(forest, map, riverFrames, riverFrameCount);
    gSkyMipView = SoftwareTextureView(
        gSkyPixels, sky.width * sky.height,
        sky.width, sky.height, sky.hasTransparentTexels,
        sky.hasTransparentTexels, sky.alphaMixing);
    gSkyMaterial[0].textureAlphaMixing = sky.alphaMixing;

    SoftwareMesh3DRequirements worldRequirements;
    SoftwareMesh3DRequirements skyRequirements;
    if (!loadWorldModel(fileServer, drive, worldRequirements) ||
        !loadSkyboxModel(fileServer, drive, skyRequirements)) {
        return false;
    }
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
    if (!loadLevel1Objects(fileServer, drive)) return false;
    gRenderStage = ERenderStageWalkCollision;
    if (!buildWalkCollisionView()) return false;
    /* Art and gameplay config are optional; missing files must not stop the
       already-working Level 41 world. The test profile keeps input testable. */
    gWeaponFileServer = &fileServer;
    gWeaponDrive.Copy(drive);
    gCurrentWeapon = 0;
    gWeaponStateInitialized[0] = false;
    (void)loadWeaponAssets(fileServer, drive, gCurrentWeapon);
    if (!SoftwareWeaponController::initialize(
            gWeaponTuning, gWeaponSprites.maxOffsetX,
            gWeaponSprites.maxOffsetY, gWeaponState)) {
        gAssetFailureCode = KErrCorrupt;
        return false;
    }
    gWeaponStateInitialized[gCurrentWeapon] = true;
    gLastWeaponUpdateMicros = 0;
    TInt audioCreateError = KErrNone;
    gLevel41Audio = TQuantumLevel41WalkAudio::New(drive, audioCreateError);
    gAudioInitializationError = audioCreateError;
    if (audioCreateError == KErrNone && gLevel41Audio != 0) {
        gLevel41Audio->StartLevelMusic();
    }
    return true;
#else
    return true;
#endif
}

TInt64 currentTimeMicros()
{
    TTime now;
    now.UniversalTime();
    return static_cast<TInt64>(now.Int64());
}

TInt64 javaCurrentTimeMillis()
{
    // Symbian TTime is measured from 0000-01-01; Java's
    // System.currentTimeMillis() is measured from 1970-01-01.
    const TInt64 KJavaUnixEpochOffsetMillis = 62167219200000LL;
    return currentTimeMicros() / 1000 - KJavaUnixEpochOffsetMillis;
}

#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
bool updateLevel41GameplayFrame()
{
    const TInt64 now = currentTimeMicros();
    QInt32 elapsedMs = 1;
    if (gLastWeaponUpdateMicros > 0 && now > gLastWeaponUpdateMicros) {
        TInt64 elapsed = (now - gLastWeaponUpdateMicros) / 1000;
        if (elapsed < 1) elapsed = 1;
        if (elapsed > 200) elapsed = 200;
        elapsedMs = static_cast<QInt32>(elapsed);
    }
    gLastWeaponUpdateMicros = now;
    gLevel41FrameElapsedMs = elapsedMs;

    QInt32 forward = (gWalkForwardHeld ? 1 : 0) -
                     (gWalkBackwardHeld ? 1 : 0);
    if (!gWalkForwardStateKnown && gWalkForwardPulse) ++forward;
    if (!gWalkBackwardStateKnown && gWalkBackwardPulse) --forward;
    gWalkForwardPulse = false;
    gWalkBackwardPulse = false;
    const QInt32 jump = gWalkJumpPulse ? 1 : 0;
    gWalkJumpPulse = false;
    const bool cameraLookPulse =
        (!gCameraLookUpStateKnown && gCameraLookUpPulse) ||
        (!gCameraLookDownStateKnown && gCameraLookDownPulse);
    QInt32 cameraLook = (gCameraLookUpHeld ? 1 : 0) -
                        (gCameraLookDownHeld ? 1 : 0);
    if (!gCameraLookUpStateKnown && gCameraLookUpPulse) ++cameraLook;
    if (!gCameraLookDownStateKnown && gCameraLookDownPulse) --cameraLook;
    gCameraLookUpPulse = false;
    gCameraLookDownPulse = false;
    const QInt32 cameraLookElapsedMs = cameraLookPulse
        ? KCameraLookPulseElapsedMs : gLevel41FrameElapsedMs;
    if (!SoftwareWalkController::updateCameraPitch(
            cameraLook, cameraLookElapsedMs,
            gCameraPitchDegrees, gCameraPitchRemainder)) {
        gAssetFailureCode = KErrCorrupt;
        return false;
    }

    /* SIS v1.0.33 polls held arrows: Up-Down is forward and Left-Right is
       signed turn. Button 0 jumps; numeric 1/3 control camera pitch. */
    const QInt32 turn = (gWalkLeftHeld ? 1 : 0) -
                        (gWalkRightHeld ? 1 : 0);
    const SoftwareWalkInput walkInput(forward, 0, turn, jump);
    if (!SoftwareWalkController::update(
            gWalkCollisionView, walkInput, gLevel41FrameElapsedMs,
            gWalkSettings, gWalkState)) {
        gAssetFailureCode = KErrCorrupt;
        return false;
    }
    SoftwareLevelObjectRenderer::advanceMeshAnimationClocks(
        gLevel1MeshObjects, gLevel1ObjectRequirements.meshObjectCount,
        gLevel41FrameElapsedMs);
    /* SIS v1.0.33 sets the one-shot weapon marker only when its held
       Up/Down bytes differ; the separate Left/Right turn axis is ignored. */
    SoftwareWeaponController::requestSis33WalkSwayForInput(
        gWeaponState, walkInput.turn, walkInput.forward);

    /* Set the marker before Weapon.update, matching the SIS sub_9489 ->
       sub_29885 -> sub_29a3d order; Java Weapon.update cross-checks the math. */
    SoftwareWeaponController::update(
        gWeaponTuning, gLevel41FrameElapsedMs, gWeaponState);
    return true;
}

#if defined(QUANTUM_LEVEL41_OMAP1710_EFFECTS_ASM)
TUint32 nextLevel41RainRandom()
{
    gLevel41RainRandomState =
        gLevel41RainRandomState * 1664525U + 1013904223U;
    return gLevel41RainRandomState;
}

void initializeLevel41Rain()
{
    TInt particle;
    for (particle = 0; particle < KLevel41RainParticleCount; ++particle) {
        gLevel41RainParticles[particle].x = static_cast<QInt32>(
            nextLevel41RainRandom() % static_cast<TUint32>(gRenderWidth));
        gLevel41RainParticles[particle].y = static_cast<QInt32>(
            nextLevel41RainRandom() % static_cast<TUint32>(gRenderHeight));
        gLevel41RainParticles[particle].depth = static_cast<QInt32>(
            nextLevel41RainRandom() % 100U);
    }
    gLevel41RainWidth = gRenderWidth;
    gLevel41RainHeight = gRenderHeight;
    gLastRainUpdateMicros = 0;
    gLevel41RainPausedUnderRoof = false;
    gLevel41RainInitialized = true;
}

QInt32 level41RainColor(QInt32 depth)
{
    const QInt32 nearRed = 0xaa;
    const QInt32 nearGreen = 0xbb;
    const QInt32 nearBlue = 0xdd;
    const QInt32 farRed = 0x66;
    const QInt32 farGreen = 0x66;
    const QInt32 farBlue = 0x66;
    if (depth < 0) depth = 0;
    if (depth > 100) depth = 100;
    const QInt32 inverseDepth = 100 - depth;
    const QInt32 red =
        (nearRed * inverseDepth + farRed * depth) / 100;
    const QInt32 green =
        (nearGreen * inverseDepth + farGreen * depth) / 100;
    const QInt32 blue =
        (nearBlue * inverseDepth + farBlue * depth) / 100;
    return (red << 16) | (green << 8) | blue;
}

void drawLevel41Rain(const SoftwareRenderSurfaceView& surface)
{
    if (gRenderWidth <= 0 || gRenderHeight <= 0) return;
    if (!gLevel41RainInitialized || gLevel41RainWidth != gRenderWidth ||
        gLevel41RainHeight != gRenderHeight) {
        initializeLevel41Rain();
    }

    if (gWalkState.underRoof) {
        /* Rain is a screen-space overlay: suppress both movement and drawing
           while the collision-height query says the camera is under a roof. */
        gLevel41RainPausedUnderRoof = true;
        gLastRainUpdateMicros = 0;
        return;
    }
    if (gLevel41RainPausedUnderRoof) {
        /* Resume with fresh drops at the top, not stale drops from indoors. */
        for (TInt particle = 0;
             particle < KLevel41RainParticleCount; ++particle) {
            gLevel41RainParticles[particle].x = static_cast<QInt32>(
                nextLevel41RainRandom() % static_cast<TUint32>(gRenderWidth));
            gLevel41RainParticles[particle].y = 0;
            gLevel41RainParticles[particle].depth = static_cast<QInt32>(
                nextLevel41RainRandom() % 100U);
        }
        gLevel41RainPausedUnderRoof = false;
        gLastRainUpdateMicros = 0;
    }

    const TInt64 now = currentTimeMicros();
    TInt frameTimeMs = 1;
    if (gLastRainUpdateMicros > 0 && now > gLastRainUpdateMicros) {
        TInt64 elapsedMs = (now - gLastRainUpdateMicros) / 1000;
        if (elapsedMs > 200) elapsedMs = 200;
        if (elapsedMs > 0) frameTimeMs = static_cast<TInt>(elapsedMs);
    }
    gLastRainUpdateMicros = now;

    TInt particle;
    for (particle = 0; particle < KLevel41RainParticleCount; ++particle) {
        TLevel41RainParticle& drop = gLevel41RainParticles[particle];
        const QInt32 depthForSpeed = 100 - drop.depth;
        const QInt32 speed =
            (10 * (depthForSpeed / 40 + 2) / 2 * frameTimeMs) / 50;
        drop.x -= speed / 4;
        drop.y += speed;
        if (drop.y > gRenderHeight + 7 || drop.x < -12 ||
            drop.x > gRenderWidth + 12) {
            drop.x = static_cast<QInt32>(
                nextLevel41RainRandom() % static_cast<TUint32>(gRenderWidth));
            drop.y = 0;
            drop.depth = static_cast<QInt32>(
                nextLevel41RainRandom() % 100U);
        }

        QInt32 size = (7 * (100 - drop.depth) + drop.depth) / 100;
        if (size < 1) size = 1;
        (void)SoftwareRainRenderer::drawStreak(
            surface, drop.x, drop.y, size, level41RainColor(drop.depth));
    }
}
#endif
#endif

#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
code::Math::QInt64 meshProfileClockMicros(void* context)
{
    (void)context;
    return static_cast<code::Math::QInt64>(currentTimeMicros());
}

void recordMeshProfilePhase(void* context,
                            SoftwareMeshRenderPhase phase,
                            code::Math::QInt64 elapsedMicros)
{
    const TInt phaseIndex = static_cast<TInt>(phase);
    if (context == 0 || phaseIndex < 0 ||
        phaseIndex >= ESoftwareMeshRenderPhaseCount || elapsedMicros < 0) {
        return;
    }
    TInt64* phaseTimes = static_cast<TInt64*>(context);
    phaseTimes[phaseIndex] = static_cast<TInt64>(elapsedMicros);
}
#endif

void resetBenchmark()
{
    gBenchmarkFrames = 0;
    gDeviceFpsTenths = 0;
    gTotalMeshRenderMicros = 0;
    gMaximumMeshRenderMicros = 0;
    gLastMeshRenderMicros = 0;
    gAverageMeshRenderMicros = 0;
    gMaximumMeshRenderMillis = 0;
    gBenchmarkComplete = false;
    gBenchmarkFinalOverlayPending = false;
#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
    TInt phase;
    for (phase = 0; phase < ESoftwareMeshRenderPhaseCount; ++phase) {
        gLastMeshPhaseMicros[phase] = 0;
        gTotalMeshPhaseMicros[phase] = 0;
        gAverageMeshPhaseMillis[phase] = 0;
    }
    gRasterProfileThisFrame.reset();
    gTotalPerspectiveDivideCalls = 0;
    gTotalSampledPerspectiveDivideCalls = 0;
    gTotalSampledPerspectiveDivideTicks = 0;
    gTotalRasterPixelLoops = 0;
    gTotalRasterPixels = 0;
    gTotalSampledRasterPixelLoops = 0;
    gTotalSampledRasterPixels = 0;
    gTotalSampledRasterPixelTicks = 0;
    gTotalMode9TriangleCalls = 0;
    gTotalSampledMode9TriangleSetupCalls = 0;
    gTotalSampledMode9TriangleSetupTicks = 0;
    gTotalMode9EdgeRows = 0;
    gTotalSampledMode9EdgeRows = 0;
    gTotalSampledMode9EdgeTicks = 0;
    gTotalSampledMode9SpanRawTicks = 0;
    gTotalSampledMode9SpanPerspectiveDivideCalls = 0;
    gTotalSampledMode9SpanSampledPerspectiveDivideCalls = 0;
    gTotalSampledMode9SpanPixelCount = 0;
    gTotalSampledMode9SpanSampledPixelLoopCalls = 0;
    gTotalRasterFastCounterTicks = 0;
    gRasterFastCounterTicksPerSecond = 0;
    gTotalPresenterFillTicks = 0;
    gTotalPresenterOverlayTicks = 0;
    gTotalPresenterBlitTicks = 0;
    gTotalPresenterFrameTicks = 0;
    gPresenterFrameSamples = 0;
    TUint64 counterReadOverheadTotal = 0;
    TInt counterRead;
    for (counterRead = 0; counterRead < 64; ++counterRead) {
        const TUint32 before = User::FastCounter();
        const TUint32 after = User::FastCounter();
        counterReadOverheadTotal += static_cast<TUint32>(after - before);
    }
    gRasterCounterReadOverheadTicks = static_cast<TUint32>(
        counterReadOverheadTotal / 64);
    gPreviousRasterFastCounter = User::FastCounter();
#endif
    gBenchmarkStartMicros = currentTimeMicros();
}

void recordBenchmarkFrame(TInt64 meshRenderMicros)
{
    if (meshRenderMicros < 0) meshRenderMicros = 0;
    ++gBenchmarkFrames;
    gTotalMeshRenderMicros += meshRenderMicros;
    if (meshRenderMicros > gMaximumMeshRenderMicros) {
        gMaximumMeshRenderMicros = meshRenderMicros;
    }

#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
    const TUint32 fastCounterNow = User::FastCounter();
    gTotalRasterFastCounterTicks += static_cast<TUint32>(
        fastCounterNow - gPreviousRasterFastCounter);
    gPreviousRasterFastCounter = fastCounterNow;
    gTotalPerspectiveDivideCalls +=
        gRasterProfileThisFrame.perspectiveDivideCalls;
    gTotalSampledPerspectiveDivideCalls +=
        gRasterProfileThisFrame.sampledPerspectiveDivideCalls;
    gTotalSampledPerspectiveDivideTicks +=
        gRasterProfileThisFrame.sampledPerspectiveDivideTicks;
    gTotalRasterPixelLoops += gRasterProfileThisFrame.pixelLoopCalls;
    gTotalRasterPixels += gRasterProfileThisFrame.pixelCount;
    gTotalSampledRasterPixelLoops +=
        gRasterProfileThisFrame.sampledPixelLoopCalls;
    gTotalSampledRasterPixels += gRasterProfileThisFrame.sampledPixelCount;
    gTotalSampledRasterPixelTicks +=
        gRasterProfileThisFrame.sampledPixelLoopTicks;
    gTotalMode9TriangleCalls +=
        gRasterProfileThisFrame.mode9TriangleCalls;
    gTotalSampledMode9TriangleSetupCalls +=
        gRasterProfileThisFrame.sampledMode9TriangleSetupCalls;
    gTotalSampledMode9TriangleSetupTicks +=
        gRasterProfileThisFrame.sampledMode9TriangleSetupTicks;
    gTotalMode9EdgeRows += gRasterProfileThisFrame.mode9EdgeRows;
    gTotalSampledMode9EdgeRows +=
        gRasterProfileThisFrame.sampledMode9EdgeRows;
    gTotalSampledMode9EdgeTicks +=
        gRasterProfileThisFrame.sampledMode9EdgeTicks;
    gTotalSampledMode9SpanRawTicks +=
        gRasterProfileThisFrame.sampledMode9SpanRawTicks;
    gTotalSampledMode9SpanPerspectiveDivideCalls +=
        gRasterProfileThisFrame.sampledMode9SpanPerspectiveDivideCalls;
    gTotalSampledMode9SpanSampledPerspectiveDivideCalls +=
        gRasterProfileThisFrame.sampledMode9SpanSampledPerspectiveDivideCalls;
    gTotalSampledMode9SpanPixelCount +=
        gRasterProfileThisFrame.sampledMode9SpanPixelCount;
    gTotalSampledMode9SpanSampledPixelLoopCalls +=
        gRasterProfileThisFrame.sampledMode9SpanSampledPixelLoopCalls;
#endif

    const TInt64 elapsedMicros = currentTimeMicros() - gBenchmarkStartMicros;
    if (elapsedMicros <= 0) return;
#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
    if (gTotalRasterFastCounterTicks > 0) {
        gRasterFastCounterTicksPerSecond =
            (gTotalRasterFastCounterTicks * 1000000) / elapsedMicros;
    }
#endif
    gDeviceFpsTenths = static_cast<TInt>(
        (static_cast<TInt64>(gBenchmarkFrames) * 10000000) / elapsedMicros);
    gAverageMeshRenderMicros = static_cast<TInt>(
        gTotalMeshRenderMicros / gBenchmarkFrames);
    gMaximumMeshRenderMillis = static_cast<TInt>(
        gMaximumMeshRenderMicros / 1000);
#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
    TInt phase;
    for (phase = 0; phase < ESoftwareMeshRenderPhaseCount; ++phase) {
        gTotalMeshPhaseMicros[phase] += gLastMeshPhaseMicros[phase];
        gAverageMeshPhaseMillis[phase] = static_cast<TInt>(
            gTotalMeshPhaseMicros[phase] / gBenchmarkFrames / 1000);
    }
#endif
#if !defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
    if (elapsedMicros >= KBenchmarkDurationMicros) {
        gBenchmarkComplete = true;
        gBenchmarkFinalOverlayPending = true;
    }
#endif
}

#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
TInt estimatedRasterKernelTenthsMillis(TInt64 totalOperations,
                                      TInt64 sampledOperations,
                                      TInt64 sampledTicks)
{
    if (gBenchmarkFrames <= 0 || sampledOperations <= 0 ||
        gRasterFastCounterTicksPerSecond <= 0) {
        return -1;
    }
    /* Keep fractional ticks through the estimate; integer ticks/op rounded
       to zero for both the divider and 24-pixel samples on the first build. */
    const TInt64 numerator =
        totalOperations * sampledTicks * 10000;
    const TInt64 denominator =
        sampledOperations * gBenchmarkFrames *
        gRasterFastCounterTicksPerSecond;
    if (denominator <= 0) return -1;
    return static_cast<TInt>(numerator / denominator);
}

TInt averageRasterProfileValue(TInt64 totalValue)
{
    if (gBenchmarkFrames <= 0) return 0;
    return static_cast<TInt>(totalValue / gBenchmarkFrames);
}

TUint32 correctedPresenterElapsedTicks(TUint32 startTicks,
                                       TUint32 endTicks)
{
    TUint32 elapsedTicks = static_cast<TUint32>(endTicks - startTicks);
    if (elapsedTicks > gRasterCounterReadOverheadTicks) {
        elapsedTicks -= gRasterCounterReadOverheadTicks;
    } else {
        elapsedTicks = 0;
    }
    return elapsedTicks;
}

TInt estimatedPresenterTenthsMillis(TInt64 totalTicks)
{
    if (gPresenterFrameSamples <= 0 ||
        gRasterFastCounterTicksPerSecond <= 0) {
        return -1;
    }
    const TInt64 denominator =
        gPresenterFrameSamples * gRasterFastCounterTicksPerSecond;
    if (denominator <= 0) return -1;
    return static_cast<TInt>((totalTicks * 10000) / denominator);
}

TInt estimatedMode9TriangleSetupTenthsMillis()
{
    return estimatedRasterKernelTenthsMillis(
        gTotalMode9TriangleCalls,
        gTotalSampledMode9TriangleSetupCalls,
        gTotalSampledMode9TriangleSetupTicks);
}

TInt estimatedMode9EdgeTenthsMillis()
{
    return estimatedRasterKernelTenthsMillis(
        gTotalMode9EdgeRows, gTotalSampledMode9EdgeRows,
        gTotalSampledMode9EdgeTicks);
}

TInt estimatedMode9SpanSetupTenthsMillis()
{
    TInt64 nestedPerspectiveDivideTicks = 0;
    if (gTotalSampledPerspectiveDivideCalls > 0) {
        nestedPerspectiveDivideTicks =
            (gTotalSampledMode9SpanPerspectiveDivideCalls *
             gTotalSampledPerspectiveDivideTicks) /
            gTotalSampledPerspectiveDivideCalls;
    }

    TInt64 nestedPixelLoopTicks = 0;
    if (gTotalSampledRasterPixels > 0) {
        nestedPixelLoopTicks =
            (gTotalSampledMode9SpanPixelCount *
             gTotalSampledRasterPixelTicks) /
            gTotalSampledRasterPixels;
    }

    const TInt64 nestedCounterReadPairs =
        gTotalSampledMode9SpanSampledPerspectiveDivideCalls +
        gTotalSampledMode9SpanSampledPixelLoopCalls;
    TInt64 setupTicks = gTotalSampledMode9SpanRawTicks -
        nestedPerspectiveDivideTicks - nestedPixelLoopTicks -
        gTotalSampledMode9EdgeRows * gRasterCounterReadOverheadTicks -
        nestedCounterReadPairs * gRasterCounterReadOverheadTicks;
    if (setupTicks < 0) setupTicks = 0;
    return estimatedRasterKernelTenthsMillis(
        gTotalMode9EdgeRows, gTotalSampledMode9EdgeRows, setupTicks);
}
#endif

bool renderLevel41Frame()
{
    if (gFramebuffer == 0 || gRenderWidth <= 0 || gRenderHeight <= 0 ||
        gRenderPixelCount != gRenderWidth * gRenderHeight) {
        return false;
    }
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
    if (!updateLevel41GameplayFrame()) return false;
#endif

    TInt pixelIndex;
    for (pixelIndex = 0; pixelIndex < gRenderPixelCount; ++pixelIndex) {
        gFramebuffer[pixelIndex] = KBackground;
    }

    Matrix cameraToView;
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
    SoftwareWalkController::buildWorldToCamera(
        gWalkState, gWalkSettings, cameraToView, gCameraPitchDegrees);
#else
    cameraToView.setRotY(0);
    cameraToView.setPosition(0, -KEyeHeight, 0);
#endif
    Matrix skyboxToView;
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
    skyboxToView.set(cameraToView);
    skyboxToView.setPosition(0, 0, 0);
#else
    skyboxToView.setRotY(0);
#endif

    /* Two static room meshes plus the one Level 1 [MESH] placement. */
    SoftwareMeshInstance instances[3];
    instances[0] = SoftwareMeshInstance(&gSkyView, &skyboxToView);
    instances[1] = SoftwareMeshInstance(&gWorldView, &cameraToView);

    SoftwareMeshRenderWorkspace workspace(
        gCameraVertices, KTotalVertexCapacity,
        gRenderQueue, KTotalPolygonCapacity,
        gProjectedVertices, KTotalVertexCapacity);
    SoftwareMeshRenderOptions options;
    TInt projectionScaleX =
        (gRenderWidth * KProjectionScale) / KBaseRenderWidth;
    TInt projectionScaleY =
        (gRenderHeight * KProjectionScale) / KBaseRenderHeight;
    TInt projectionScale = projectionScaleX < projectionScaleY
        ? projectionScaleX : projectionScaleY;
    TInt projectionDistance =
        (KBaseProjectionDistance * projectionScale + KProjectionScale / 2) /
        KProjectionScale;
    if (projectionDistance < 1) projectionDistance = 1;
    options.projection = SoftwareProjectionParameters(
        gRenderWidth / 2, gRenderHeight / 2,
        projectionDistance, projectionDistance);
    options.projectionPath = ESoftwareMeshProjectionDirectX7;
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
    options.cameraWorldX = gWalkState.x;
    options.cameraWorldY = code::Math::javaIntAdd(
        gWalkState.feetY, gWalkSettings.eyeHeight);
    options.cameraWorldZ = gWalkState.z;
    options.shootIntensity = gWeaponState.shootIntensity;
    options.shootLength = gWeaponState.shootLength;
#else
    options.cameraWorldY = KEyeHeight;
#endif
    options.quality.setStandardDrawMode(9);
    options.quality.setPerspectiveQuality(2);
    options.quality.setTextureQuality(2);
    options.quality.setFogQuality(2);
    options.quality.setMipMapping(true);
#if defined(QUANTUM_LEVEL41_FAST16)
    /*
     * Opt-in 16-bit fast path: keep perspective correction and full fog (the
     * preset's affine-only tuning is a separate policy choice), just switch
     * the texture storage to 16 bits and allow the fast kernels. Mipmapping
     * stays off because no 16-bit mip chain is built.
     */
    options.quality.use16BitTextures = true;
    options.quality.useFastRasterizer = true;
    options.quality.mipMapping = false;
#endif
    options.triangleSettings.fogDistance = 1;
    options.triangleSettings.fogColor = 0x005500ff;
    options.farDistance = KFarDistance;
    options.clipZ = -KClipDistance;
    options.mipQuality = 24;
    /*
     * Perspective segment length. Java calls this `q` and exposes it as the
     * "PERSQ" quality setting (5 steps, q = 24 at persQ 0..2 and 12 above).
     * 96 halves the number of perspective divides per scanline against 48,
     * which buys 3.1x over the old default of 24 at the cost of noticeably
     * coarser perspective on steep floors; see the measured table in
     * symbian9/host_tests/README.md. Q48, if defined, still wins so the two
     * experiments cannot silently stack.
     */
#if defined(QUANTUM_LEVEL41_FPS_EXPERIMENT_Q48)
    /* Separate E65 test app: trade perspective accuracy for fewer divides. */
    options.triangleSettings.perspectiveStepPixels = 48;
#else
    options.triangleSettings.perspectiveStepPixels = 96;
#endif
    options.meshContentsPrevalidated = gMeshContentsPrevalidated;

    const SoftwareRenderSurfaceView surface(
        gFramebuffer, gRenderPixelCount,
        gRenderWidth, gRenderHeight, gRenderWidth);
    SoftwareMeshRenderStats stats;
#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
    TInt phase;
    for (phase = 0; phase < ESoftwareMeshRenderPhaseCount; ++phase) {
        gLastMeshPhaseMicros[phase] = 0;
    }
    SoftwareMeshRenderInstrumentation instrumentation;
    instrumentation.context = gLastMeshPhaseMicros;
    instrumentation.clockMicros = meshProfileClockMicros;
    instrumentation.recordPhaseMicros = recordMeshProfilePhase;
    options.instrumentation = &instrumentation;
#endif
    gRenderStage = ERenderStageRasterizer;
    const TInt64 renderStartMicros = currentTimeMicros();
#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
    gRasterProfileThisFrame.reset();
    gRasterProfileThisFrame.counterReadOverheadTicks =
        gRasterCounterReadOverheadTicks;
    SoftwarePerspectiveRasterizer::setProfile(&gRasterProfileThisFrame);
#endif
    /* The interactive Level 1 target loads the two records from the pinned
       level1-objects.txt and resolves their prop assets from the SIS private
       resource cache. The fixed-camera smoke target retains empty arrays. */
    SoftwareWorldSpriteRenderOptions spriteOptions;
    spriteOptions.worldToCamera = &cameraToView;
    const TInt64 spriteClockMillis = javaCurrentTimeMillis();
    spriteOptions.currentTimeMillis = spriteClockMillis;
    spriteOptions.gameTimeMillis = spriteClockMillis;
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
    spriteOptions.frameTime = gLevel41FrameElapsedMs;
#else
    spriteOptions.frameTime = 1;
#endif
    spriteOptions.fogDistance = options.triangleSettings.fogDistance;
    spriteOptions.fogColor = options.triangleSettings.fogColor;
    spriteOptions.standardDrawMode = 9;
    spriteOptions.useAutoWMove = true;
    SoftwareWorldSpriteRenderStats spriteStats;
    const SoftwareLevelObjectRenderWorkspace objectWorkspace(instances, 3);
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
    const bool rendered = SoftwareLevelObjectRenderer::render(
        surface, instances, 2,
        gLevel1MeshObjects, gLevel1ObjectRequirements.meshObjectCount,
        gLevel1SpriteObjects, gLevel1ObjectRequirements.spriteObjectCount,
        cameraToView, options, spriteOptions, objectWorkspace, workspace,
        stats, spriteStats);
#else
    const bool rendered = SoftwareLevelObjectRenderer::render(
        surface, instances, 2, 0, 0, 0, 0, cameraToView,
        options, spriteOptions, objectWorkspace, workspace,
        stats, spriteStats);
#endif
#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
    SoftwarePerspectiveRasterizer::setProfile(0);
#endif
    const TInt64 renderEndMicros = currentTimeMicros();
    if (!rendered) return false;
    if (stats.polygonsDrawn <= 0 && spriteStats.spritesDrawn <= 0) {
        gRenderStage = ERenderStageNoPolygons;
        return false;
    }
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
    SoftwareWeaponController::decayShotLight(
        gWeaponState, gLevel41FrameElapsedMs);
#endif
    gPolygonsDrawn = stats.polygonsDrawn;
    gTrianglesDrawn = stats.trianglesDrawn;
    gLastMeshRenderMicros = renderEndMicros - renderStartMicros;
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
#if defined(QUANTUM_LEVEL41_OMAP1710_EFFECTS_ASM)
    drawLevel41Rain(surface);
#endif
    if (gWeaponOverlayAvailable) {
        SoftwareWeaponDrawSettings weaponSettings;
        weaponSettings.viewportWidth = gRenderWidth;
        weaponSettings.viewportHeight = gRenderHeight;
        weaponSettings.screenY = 0;
        weaponSettings.hasCurrentWeapon = true;
        weaponSettings.playerAlive = true;
        weaponSettings.show2D = true;
        weaponSettings.hudVisible = true;
        weaponSettings.dx = gWeaponState.dx;
        weaponSettings.dy = gWeaponState.dy;
        weaponSettings.firing =
            SoftwareWeaponController::isFiring(gWeaponState);
        weaponSettings.newFireAnimation = gWeaponTuning.newFireAnimation;
        weaponSettings.twoHands = gWeaponTuning.twoHands;
        weaponSettings.leftHand = gWeaponTuning.leftHand;
        weaponSettings.meleeAnimation = gWeaponTuning.meleeAnimation;
        weaponSettings.centreAlign = gWeaponTuning.centreAlign;
        weaponSettings.ignoreSightOnDraw =
            gWeaponTuning.ignoreSightOnDraw;
        weaponSettings.hasZoom = gWeaponTuning.hasZoom;
        weaponSettings.resizeWeapons = false;
        weaponSettings.customPosition = gWeaponTuning.customPosition;
        weaponSettings.customPositionX = gWeaponTuning.customPositionX;
        if (!SoftwareWeaponRenderer::draw(
                surface, gWeaponSprites, weaponSettings)) {
            gWeaponOverlayAvailable = false;
        }
    }
    if (gWeaponState.shotFeedbackRemainingMs > 0) {
        const QInt32 centerX = gRenderWidth / 2;
        const QInt32 centerY = gRenderHeight / 2;
        const QInt32 markerColor = gWeaponState.lastShotHitWorld
            ? 0x00ffc040 : 0x00ffffff;
        QInt32 markerOffset;
        for (markerOffset = -4; markerOffset <= 4; ++markerOffset) {
            if (markerOffset < -1 || markerOffset > 1) {
                const QInt32 verticalY = centerY + markerOffset;
                const QInt32 horizontalX = centerX + markerOffset;
                if (verticalY >= 0 && verticalY < gRenderHeight) {
                    gFramebuffer[verticalY * gRenderWidth + centerX] =
                        markerColor;
                }
                if (horizontalX >= 0 && horizontalX < gRenderWidth) {
                    gFramebuffer[centerY * gRenderWidth + horizontalX] =
                        markerColor;
                }
            }
        }
    }
#endif
    return true;
}

void drawStartup(CWsScreenDevice& screen, CWindowGc& gc,
                 const TSize& size)
{
    gc.SetBrushStyle(CGraphicsContext::ESolidBrush);
    gc.SetPenStyle(CGraphicsContext::ENullPen);
    gc.SetBrushColor(TRgb(8, 12, 20));
    gc.Clear(TRect(TPoint(0, 0), size));
    gc.SetPenStyle(CGraphicsContext::ESolidPen);
    gc.SetPenColor(TRgb(255, 255, 255));
    CFont* font = 0;
    if (screen.GetNearestFontInPixels(font, TFontSpec(_L("Series 60 Sans"), 12)) == KErrNone) {
        gc.UseFont(font);
        gc.DrawText(_L("QUANTUM LEVEL 41"), TPoint(8, 32));
        gc.DrawText(_L("Starting native renderer..."), TPoint(8, 58));
        TBuf<40> display;
        display.Format(_L("Display %d x %d"), size.iWidth, size.iHeight);
        gc.DrawText(display, TPoint(8, 84));
        gc.DiscardFont();
        screen.ReleaseFont(font);
    }
}

void drawFailure(CWsScreenDevice& screen, CWindowGc& gc,
                 const TSize& size, TInt error)
{
    gc.SetBrushStyle(CGraphicsContext::ESolidBrush);
    gc.SetPenStyle(CGraphicsContext::ENullPen);
    gc.SetBrushColor(TRgb(24, 5, 8));
    gc.Clear(TRect(TPoint(0, 0), size));
    gc.SetPenStyle(CGraphicsContext::ESolidPen);
    gc.SetPenColor(TRgb(255, 230, 230));
    CFont* font = 0;
    if (screen.GetNearestFontInPixels(font, TFontSpec(_L("Series 60 Sans"), 11)) == KErrNone) {
        gc.UseFont(font);
        gc.DrawText(_L("LEVEL 41 TEST FAILED"), TPoint(8, 28));
        TBuf<64> line;
        line.Format(_L("Asset/render error: %d"), error);
        gc.DrawText(line, TPoint(8, 52));
        TBuf<40> stage;
        switch (gRenderStage) {
        case ERenderStageForestTexture:
            stage.Copy(_L("Failed at: forest.qtx"));
            break;
        case ERenderStageMapTexture:
            stage.Copy(_L("Failed at: map.qtx"));
            break;
        case ERenderStageRiverTexture:
            stage.Copy(_L("Failed at: river.qtx"));
            break;
        case ERenderStageSkyTexture:
            stage.Copy(_L("Failed at: sky.qtx"));
            break;
        case ERenderStageTextureDimensions:
            stage.Copy(_L("Failed at: texture size"));
            break;
        case ERenderStageWorldMesh:
            stage.Copy(_L("Failed at: L3D_new.3d"));
            break;
        case ERenderStageLightmap:
            stage.Copy(_L("Failed at: lightmap.vla"));
            break;
        case ERenderStageSkyboxMesh:
            stage.Copy(_L("Failed at: skybox8.3d"));
            break;
        case ERenderStageWalkCollision:
            stage.Copy(_L("Failed at: walk collision"));
            break;
        case ERenderStageLevelObjects:
            stage.Copy(_L("Failed at: Level 1 objects"));
            break;
        case ERenderStageRasterizer:
            stage.Copy(_L("Failed at: renderer"));
            break;
        case ERenderStageNoPolygons:
            stage.Copy(_L("Failed: no polygons drawn"));
            break;
        default:
            stage.Copy(_L("Failed at: startup"));
            break;
        }
        gc.DrawText(stage, TPoint(8, 76));
        if (gRenderStage == ERenderStageSkyboxMesh) {
            TBuf<40> detail;
            switch (gAssetFailurePoint) {
            case EAssetFailureOpen:
                detail.Copy(_L("Asset open failed"));
                break;
            case EAssetFailureSize:
                detail.Copy(_L("Asset size query failed"));
                break;
            case EAssetFailureCapacity:
                detail.Copy(_L("Asset exceeds buffer"));
                break;
            case EAssetFailureRead:
                detail.Copy(_L("Asset read failed"));
                break;
            case EAssetFailureMeshMeasure:
                detail.Copy(_L("3D measure failed"));
                break;
            case EAssetFailureMeshLayout:
                detail.Copy(_L("3D counts mismatch"));
                break;
            case EAssetFailureMeshParse:
                detail.Copy(_L("3D parse failed"));
                break;
            default:
                detail.Copy(_L("Unknown skybox failure"));
                break;
            }
            gc.DrawText(detail, TPoint(8, 100));
            TBuf<40> fileInfo;
            fileInfo.Format(_L("Read %d / size %d"),
                            gAssetBytesRead, gAssetFileSize);
            gc.DrawText(fileInfo, TPoint(8, 124));
            TBuf<48> meshInfo;
            meshInfo.Format(_L("Mesh/V/P/M: %d/%d/%d/%d"),
                            gSkyboxRequirements.meshCount,
                            gSkyboxRequirements.vertexCount,
                            gSkyboxRequirements.polygonCount,
                            gSkyboxRequirements.materialCount);
            gc.DrawText(meshInfo, TPoint(8, 148));
            gc.DrawText(_L("Check skybox asset/package"), TPoint(8, 172));
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
        } else if (gRenderStage == ERenderStageWalkCollision) {
            TBuf<48> detail;
            switch (gWalkCollisionFailurePoint) {
            case EWalkCollisionFailureStorage:
                detail.Copy(_L("Collision buffer allocation failed"));
                break;
            case EWalkCollisionFailureAdapter:
                detail.Copy(_L("World mesh adaptation failed"));
                break;
            case EWalkCollisionFailureSpawnFloor:
                detail.Copy(_L("Start floor snap failed"));
                break;
            default:
                detail.Copy(_L("Unknown collision setup failure"));
                break;
            }
            gc.DrawText(detail, TPoint(8, 100));
            TBuf<48> collisionInfo;
            collisionInfo.Format(_L("World polygons: %d"),
                                 KWorldPolygonCapacity);
            gc.DrawText(collisionInfo, TPoint(8, 124));
            collisionInfo.Format(_L("Built collisions: %d"),
                                 gWalkCollisionBuiltPolygonCount);
            gc.DrawText(collisionInfo, TPoint(8, 148));
            gc.DrawText(_L("Report these diagnostics"), TPoint(8, 172));
        } else if (gRenderStage == ERenderStageLevelObjects) {
            TBuf<48> detail;
            switch (gAssetFailurePoint) {
            case EAssetFailureOpen:
                detail.Copy(_L("Object asset open failed"));
                break;
            case EAssetFailureSize:
                detail.Copy(_L("Object asset size failed"));
                break;
            case EAssetFailureCapacity:
                detail.Copy(_L("Object asset too large"));
                break;
            case EAssetFailureRead:
                detail.Copy(_L("Object asset read failed"));
                break;
            case EAssetFailureObjectManifest:
                detail.Copy(_L("Level object manifest invalid"));
                break;
            case EAssetFailureObjectTexture:
                detail.Copy(_L("Object texture decode failed"));
                break;
            case EAssetFailureObjectMeshMeasure:
                detail.Copy(_L("Object mesh measure failed"));
                break;
            case EAssetFailureObjectMeshLayout:
                detail.Copy(_L("Object mesh layout mismatch"));
                break;
            case EAssetFailureObjectMeshParse:
                detail.Copy(_L("Object mesh parse failed"));
                break;
            default:
                detail.Copy(_L("Unknown Level 1 object failure"));
                break;
            }
            gc.DrawText(detail, TPoint(8, 100));
            TBuf<48> objectInfo;
            objectInfo.Format(_L("M:%d S:%d F:%d V:%d"),
                             gLevel1ObjectRequirements.meshObjectCount,
                             gLevel1ObjectRequirements.spriteObjectCount,
                             gLevel1ObjectRequirements.spriteFrameCount,
                             gLevel1ObjectRequirements.animationVertexCount);
            gc.DrawText(objectInfo, TPoint(8, 124));
            gc.DrawText(_L("Check Level 1 object payloads"),
                        TPoint(8, 148));
            gc.DrawText(_L("Only the listed mesh and sprite are loaded"),
                        TPoint(8, 172));
#endif
        } else {
            gc.DrawText(_L("Check package assets and retry"),
                        TPoint(8, 100));
        }
        gc.DrawText(_L("Press any key to exit"), TPoint(8, size.iHeight - 8));
        gc.DiscardFont();
        screen.ReleaseFont(font);
    }
}

void drawStatusOverlay(CWsScreenDevice& screen,
                       CGraphicsContext& gc,
                       const TSize& size)
{
    CFont* font = 0;
    if (screen.GetNearestFontInPixels(font, TFontSpec(_L("Series 60 Sans"), 10)) == KErrNone) {
        gc.UseFont(font);
        gc.SetPenStyle(CGraphicsContext::ESolidPen);
        gc.SetPenColor(TRgb(255, 255, 255));
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
        gc.DrawText(_L("LEVEL 41 / RIVER DMODE 7"), TPoint(4, 14));
#else
        gc.DrawText(_L("LEVEL 41 / DMODE 9"), TPoint(4, 14));
#endif
        TBuf<48> benchmarkStatus;
        if (gBenchmarkComplete) {
            benchmarkStatus.Format(
                _L("10s average FPS: %d.%d"),
                gDeviceFpsTenths / 10, gDeviceFpsTenths % 10);
        } else {
            benchmarkStatus.Format(
                _L("Live FPS: %d.%d"),
                gDeviceFpsTenths / 10, gDeviceFpsTenths % 10);
        }
        gc.DrawText(benchmarkStatus, TPoint(4, 32));
        TBuf<48> renderTime;
        renderTime.Format(_L("Mesh avg/max: %d/%d ms"),
                          gAverageMeshRenderMicros / 1000,
                          gMaximumMeshRenderMillis);
        gc.DrawText(renderTime, TPoint(4, 48));
        TBuf<48> presenterStatus;
        if (gDsaPresenterActive) {
#if defined(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
            if (gLevel41DirectArgbBitmapMode) {
                presenterStatus.Copy(_L("Presenter: DSA ARGB32"));
            } else
#endif
            {
#if defined(QUANTUM_LEVEL41_OMAP1710_ECOLOR64K)
                presenterStatus.Copy(_L("Presenter: DSA EColor64K"));
#else
                presenterStatus.Copy(_L("Presenter: DSA 24-bit"));
#endif
            }
        } else if (gDsaAttempted && gDsaStatus != KErrNone) {
            presenterStatus.Format(
                _L("Presenter: DSA error %d"), gDsaStatus);
        } else if (gBitmapGdiPresenterActive) {
#if defined(QUANTUM_LEVEL41_OMAP1710_ECOLOR64K)
            presenterStatus.Copy(_L("Presenter: EColor64K BitBlt"));
#else
            presenterStatus.Copy(_L("Presenter: EColor16M BitBlt"));
#endif
        } else {
#if defined(QUANTUM_LEVEL41_OMAP1710_ECOLOR64K)
            presenterStatus.Copy(_L("Presenter: GDI fallback"));
#else
            presenterStatus.Copy(_L("Presenter: GDI 24-bit"));
#endif
        }
        gc.DrawText(presenterStatus, TPoint(4, 64));
#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
        TBuf<64> phaseTime;
        phaseTime.Format(_L("Validation: %d ms"),
                         gAverageMeshPhaseMillis[
                             ESoftwareMeshRenderPhaseValidation]);
        gc.DrawText(phaseTime, TPoint(4, 80));
        phaseTime.Format(_L("Transform/proj: %d ms"),
                         gAverageMeshPhaseMillis[
                             ESoftwareMeshRenderPhaseTransformProjection]);
        gc.DrawText(phaseTime, TPoint(4, 96));
        phaseTime.Format(_L("Cull/queue: %d ms"),
                         gAverageMeshPhaseMillis[
                             ESoftwareMeshRenderPhasePolygonQueue]);
        gc.DrawText(phaseTime, TPoint(4, 112));
        phaseTime.Format(_L("Sort: %d ms"),
                         gAverageMeshPhaseMillis[
                             ESoftwareMeshRenderPhaseSort]);
        gc.DrawText(phaseTime, TPoint(4, 128));
        phaseTime.Format(_L("Draw/raster: %d ms"),
                         gAverageMeshPhaseMillis[
                             ESoftwareMeshRenderPhaseDraw]);
        gc.DrawText(phaseTime, TPoint(4, 144));
#else
        gc.DrawText(_L("Phase profiling: OFF"), TPoint(4, 80));
#endif
#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
        if (gBenchmarkComplete) {
            const TInt divideCalls = averageRasterProfileValue(
                gTotalPerspectiveDivideCalls);
            const TInt divideSamples = averageRasterProfileValue(
                gTotalSampledPerspectiveDivideCalls);
            const TInt divideTenths = estimatedRasterKernelTenthsMillis(
                gTotalPerspectiveDivideCalls,
                gTotalSampledPerspectiveDivideCalls,
                gTotalSampledPerspectiveDivideTicks);
            if (divideTenths >= 0) {
                phaseTime.Format(_L("PDiv %d/f ~%d.%dms s%d"),
                                 divideCalls, divideTenths / 10,
                                 divideTenths % 10, divideSamples);
            } else {
                phaseTime.Copy(_L("PDiv timing unavailable"));
            }
            gc.DrawText(phaseTime, TPoint(4, 160));
            const TInt pixelCount = averageRasterProfileValue(
                gTotalRasterPixels);
            const TInt pixelSamples = averageRasterProfileValue(
                gTotalSampledRasterPixelLoops);
            const TInt pixelTenths = estimatedRasterKernelTenthsMillis(
                gTotalRasterPixels, gTotalSampledRasterPixels,
                gTotalSampledRasterPixelTicks);
            if (pixelTenths >= 0) {
                phaseTime.Format(_L("Pixels %d/f ~%d.%dms s%d"),
                                 pixelCount, pixelTenths / 10,
                                 pixelTenths % 10, pixelSamples);
            } else {
                phaseTime.Copy(_L("Pixel timing unavailable"));
            }
            gc.DrawText(phaseTime, TPoint(4, 176));
            TInt otherTenths = static_cast<TInt>(
                gTotalMeshPhaseMicros[ESoftwareMeshRenderPhaseDraw] /
                gBenchmarkFrames / 100) -
                (divideTenths < 0 ? 0 : divideTenths) -
                (pixelTenths < 0 ? 0 : pixelTenths);
            if (otherTenths < 0) otherTenths = 0;
            phaseTime.Format(_L("Other raster ~%d.%dms"),
                             otherTenths / 10, otherTenths % 10);
            gc.DrawText(phaseTime, TPoint(4, 192));
            const TInt setupTenths =
                estimatedMode9TriangleSetupTenthsMillis();
            const TInt edgeTenths = estimatedMode9EdgeTenthsMillis();
            const TInt spanTenths = estimatedMode9SpanSetupTenthsMillis();
            if (setupTenths >= 0 && edgeTenths >= 0 && spanTenths >= 0) {
                phaseTime.Format(
                    _L("M9 T/E/S ~%d.%d/%d.%d/%d.%dms"),
                    setupTenths / 10, setupTenths % 10,
                    edgeTenths / 10, edgeTenths % 10,
                    spanTenths / 10, spanTenths % 10);
            } else {
                phaseTime.Copy(_L("Mode-9 breakdown unavailable"));
            }
            gc.DrawText(phaseTime, TPoint(4, 208));
        } else {
            gc.DrawText(_L("Raster details: profiling"), TPoint(4, 160));
        }
#endif
        TBuf<48> status;
        status.Format(_L("Polygons %d  Triangles %d"),
                      gPolygonsDrawn, gTrianglesDrawn);
        gc.DrawText(status, TPoint(4, size.iHeight - 22));
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
        TBuf<48> weaponStatus;
        if (SoftwareWeaponController::isReloading(gWeaponState)) {
            weaponStatus.Format(_L("USP %d/%d RELOAD %d"),
                                gWeaponState.rounds, gWeaponState.reserveAmmo,
                                SoftwareWeaponController::reloadPercentage(
                                    gWeaponTuning, gWeaponState));
        } else if (gWeaponState.shotFeedbackRemainingMs > 0 &&
                   gWeaponState.hasShot) {
            if (gWeaponState.lastShotHitWorld) {
                weaponStatus.Format(_L("USP %d/%d WALL"),
                                    gWeaponState.rounds,
                                    gWeaponState.reserveAmmo);
            } else {
                weaponStatus.Format(_L("USP %d/%d SHOT"),
                                    gWeaponState.rounds,
                                    gWeaponState.reserveAmmo);
            }
        } else if (gWeaponTuningLoadedFromAssets) {
            weaponStatus.Format(_L("USP %d/%d CFG"),
                                gWeaponState.rounds,
                                gWeaponState.reserveAmmo);
        } else {
            weaponStatus.Format(_L("USP %d/%d TEST"),
                                gWeaponState.rounds,
                                gWeaponState.reserveAmmo);
        }
        gc.DrawText(weaponStatus, TPoint(4, size.iHeight - 70));
        TBuf<64> walkPosition;
        walkPosition.Format(_L("X:%d Y:%d Z:%d Yaw:%d"),
                            gWalkState.x, gWalkState.feetY, gWalkState.z,
                            gWalkState.yawDegrees);
        gc.DrawText(walkPosition, TPoint(4, size.iHeight - 54));
        gc.DrawText(_L("UP/DOWN WALK L/R TURN 0 JUMP 5 FIRE"),
                    TPoint(4, size.iHeight - 38));
        TBuf<48> audioStatus;
        if (gLevel41Audio != 0) {
            gLevel41Audio->StatusLine(audioStatus);
        } else {
            audioStatus.Format(_L("AUDIO INIT E%d"),
                               gAudioInitializationError);
        }
        gc.DrawText(audioStatus, TPoint(4, size.iHeight - 6));
#else
        gc.DrawText(_L("Press any key to exit"),
                    TPoint(4, size.iHeight - 6));
#endif
        gc.DiscardFont();
        screen.ReleaseFont(font);
    }
}

void drawOutput(CWsScreenDevice& screen,
                CGraphicsContext& gc,
                RWsSession* session,
                const TSize& size)
{
    const TInt scaleX = size.iWidth / gRenderWidth;
    const TInt scaleY = size.iHeight / gRenderHeight;
    TInt scale = scaleX < scaleY ? scaleX : scaleY;
    if (scale < 1) scale = 1;
    const TInt imageWidth = gRenderWidth * scale;
    const TInt imageHeight = gRenderHeight * scale;
    const TInt left = (size.iWidth - imageWidth) / 2;
    const TInt top = (size.iHeight - imageHeight) / 2;

    gc.SetBrushStyle(CGraphicsContext::ESolidBrush);
    gc.SetPenStyle(CGraphicsContext::ENullPen);
    gc.SetBrushColor(TRgb(8, 12, 20));
    gc.DrawRect(TRect(TPoint(0, 0), size));

    TInt y;
    for (y = 0; y < gRenderHeight; ++y) {
        TInt x;
        for (x = 0; x < gRenderWidth; ++x) {
            const QInt32 pixel = gFramebuffer[y * gRenderWidth + x];
            if (pixel == KBackground) continue;
            const QUint32 bits = static_cast<QUint32>(pixel);
            gc.SetBrushColor(TRgb(
                static_cast<TInt>((bits >> 16U) & 0xffU),
                static_cast<TInt>((bits >> 8U) & 0xffU),
                static_cast<TInt>(bits & 0xffU)));
            gc.DrawRect(TRect(left + x * scale, top + y * scale,
                              left + (x + 1) * scale,
                              top + (y + 1) * scale));
        }
        if (session != 0 && (y & 7) == 7) {
            // Keep the WindowServer fallback's GDI request queue bounded.
            session->Flush();
        }
    }

    if (session != 0) drawStatusOverlay(screen, gc, size);
}

#if defined(QUANTUM_LEVEL41_OMAP1710_ECOLOR64K)
bool level41OmapScreenCopyMatchesCpp(const TUint8* pixels,
                                     const TSize& size,
                                     TInt strideBytes)
{
#if defined(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
    if (gLevel41DirectArgbBitmapMode) return false;
#endif
    if (pixels == 0 || size.iWidth != gRenderWidth ||
        size.iHeight != gRenderHeight ||
        gRenderPixelCount != gRenderWidth * gRenderHeight ||
        strideBytes < size.iWidth * 2) {
        return false;
    }

    TInt y;
    for (y = 0; y < gRenderHeight; ++y) {
        const QInt32* sourceRow = gFramebuffer + y * gRenderWidth;
        const TUint8* destinationRow = pixels + y * strideBytes;
        TInt x;
        for (x = 0; x < gRenderWidth; ++x) {
            TUint8 expected[2];
            storeRgb888ToEColor64K(sourceRow[x], expected);
            const TUint8* actual = destinationRow + x * 2;
            if (actual[0] != expected[0] || actual[1] != expected[1]) {
                return false;
            }
        }
    }
    return true;
}
#endif

bool fillLevel41Bitmap(CFbsBitmap& bitmap,
                       const TSize& size,
                       TInt strideBytes)
{
#if defined(QUANTUM_LEVEL41_OMAP1710_ECOLOR64K)
    gOmapScreenCopyActive = false;
#endif
    if (gFramebuffer == 0 || gRenderWidth <= 0 || gRenderHeight <= 0 ||
        size.iWidth <= 0 || size.iHeight <= 0 || size.iWidth > 8192 ||
        strideBytes < size.iWidth * KLevel41PresenterBytesPerPixel) {
        return false;
    }

    TInt scaleX = size.iWidth / gRenderWidth;
    TInt scaleY = size.iHeight / gRenderHeight;
    TInt scale = scaleX < scaleY ? scaleX : scaleY;
    if (scale < 1) scale = 1;
    const TInt imageWidth = gRenderWidth * scale;
    const TInt imageHeight = gRenderHeight * scale;
    const TInt left = (size.iWidth - imageWidth) / 2;
    const TInt top = (size.iHeight - imageHeight) / 2;

    bitmap.LockHeap();
    TUint32* address = bitmap.DataAddress();
    if (address == 0) {
        bitmap.UnlockHeap();
        return false;
    }

    TUint8* pixels = reinterpret_cast<TUint8*>(address);
    TInt y;
    if (scale == 1 && left == 0 && top == 0 &&
        gRenderWidth == size.iWidth && gRenderHeight == size.iHeight) {
#if defined(QUANTUM_LEVEL41_OMAP1710_ECOLOR64K)
        const TInt pixelCount = gRenderPixelCount;
        const bool sourceWordAligned =
            (reinterpret_cast<TUint32>(gFramebuffer) & 3U) == 0;
        const bool destinationWordAligned =
            (reinterpret_cast<TUint32>(pixels) & 3U) == 0;
#if defined(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
        if (!gLevel41DirectArgbBitmapMode && gOmapScreenCopyEnabled &&
#else
        if (gOmapScreenCopyEnabled &&
#endif
            pixelCount > 0 &&
            pixelCount == gRenderWidth * gRenderHeight &&
            (pixelCount & 1) == 0 && (size.iWidth & 1) == 0 &&
            sourceWordAligned && destinationWordAligned &&
            strideBytes == size.iWidth * 2) {
            quantum_omap1710_copy_argb8888_to_rgb565(
                gFramebuffer, pixels, pixelCount / 2);
            if (!gOmapScreenCopyVerified) {
                gOmapScreenCopyMatched = level41OmapScreenCopyMatchesCpp(
                    pixels, size, strideBytes);
                gOmapScreenCopyVerified = true;
                if (!gOmapScreenCopyMatched) {
                    gOmapScreenCopyEnabled = false;
                }
            }
            if (gOmapScreenCopyEnabled) {
                gOmapScreenCopyActive = true;
                bitmap.UnlockHeap();
                return true;
            }
        }
#endif
        for (y = 0; y < size.iHeight; ++y) {
            TUint8* row = pixels + y * strideBytes;
            const QInt32* sourceRow = gFramebuffer + y * gRenderWidth;
            TInt x;
            for (x = 0; x < size.iWidth; ++x) {
                storeLevel41PresenterPixel(
                    sourceRow[x], row + x * KLevel41PresenterBytesPerPixel);
            }
        }
    } else {
        for (y = 0; y < size.iHeight; ++y) {
            TUint8* row = pixels + y * strideBytes;
            const TInt imageY = y - top;
            const bool insideImageY = imageY >= 0 && imageY < imageHeight;
            const TInt sourceY = insideImageY ? imageY / scale : -1;
            TInt x;
            for (x = 0; x < size.iWidth; ++x) {
                const TInt imageX = x - left;
                TUint8* destination =
                    row + x * KLevel41PresenterBytesPerPixel;
                if (insideImageY && imageX >= 0 && imageX < imageWidth) {
                    const TInt sourceX = imageX / scale;
                    storeLevel41PresenterPixel(
                        gFramebuffer[sourceY * gRenderWidth + sourceX],
                        destination);
                } else {
                    storeLevel41PresenterPixel(0x00080c14, destination);
                }
            }
        }
    }
    bitmap.UnlockHeap();
    return true;
}


const TUint8* level41StatusGlyph(TUint16 character)
{
    static const TUint8 glyphs[41][7] = {
        {0x0e,0x11,0x11,0x1f,0x11,0x11,0x11}, /* A */
        {0x1e,0x11,0x11,0x1e,0x11,0x11,0x1e}, /* B */
        {0x0e,0x11,0x10,0x10,0x10,0x11,0x0e}, /* C */
        {0x1e,0x11,0x11,0x11,0x11,0x11,0x1e}, /* D */
        {0x1f,0x10,0x10,0x1e,0x10,0x10,0x1f}, /* E */
        {0x1f,0x10,0x10,0x1e,0x10,0x10,0x10}, /* F */
        {0x0e,0x11,0x10,0x17,0x11,0x11,0x0f}, /* G */
        {0x11,0x11,0x11,0x1f,0x11,0x11,0x11}, /* H */
        {0x0e,0x04,0x04,0x04,0x04,0x04,0x0e}, /* I */
        {0x07,0x02,0x02,0x02,0x12,0x12,0x0c}, /* J */
        {0x11,0x12,0x14,0x18,0x14,0x12,0x11}, /* K */
        {0x10,0x10,0x10,0x10,0x10,0x10,0x1f}, /* L */
        {0x11,0x1b,0x15,0x15,0x11,0x11,0x11}, /* M */
        {0x11,0x19,0x15,0x13,0x11,0x11,0x11}, /* N */
        {0x0e,0x11,0x11,0x11,0x11,0x11,0x0e}, /* O */
        {0x1e,0x11,0x11,0x1e,0x10,0x10,0x10}, /* P */
        {0x0e,0x11,0x11,0x11,0x15,0x12,0x0d}, /* Q */
        {0x1e,0x11,0x11,0x1e,0x14,0x12,0x11}, /* R */
        {0x0f,0x10,0x10,0x0e,0x01,0x01,0x1e}, /* S */
        {0x1f,0x04,0x04,0x04,0x04,0x04,0x04}, /* T */
        {0x11,0x11,0x11,0x11,0x11,0x11,0x0e}, /* U */
        {0x11,0x11,0x11,0x11,0x11,0x0a,0x04}, /* V */
        {0x11,0x11,0x11,0x15,0x15,0x15,0x0a}, /* W */
        {0x11,0x11,0x0a,0x04,0x0a,0x11,0x11}, /* X */
        {0x11,0x11,0x0a,0x04,0x04,0x04,0x04}, /* Y */
        {0x1f,0x01,0x02,0x04,0x08,0x10,0x1f}, /* Z */
        {0x0e,0x11,0x13,0x15,0x19,0x11,0x0e}, /* 0 */
        {0x04,0x0c,0x04,0x04,0x04,0x04,0x0e}, /* 1 */
        {0x0e,0x11,0x01,0x02,0x04,0x08,0x1f}, /* 2 */
        {0x1e,0x01,0x01,0x0e,0x01,0x01,0x1e}, /* 3 */
        {0x02,0x06,0x0a,0x12,0x1f,0x02,0x02}, /* 4 */
        {0x1f,0x10,0x10,0x1e,0x01,0x01,0x1e}, /* 5 */
        {0x0e,0x10,0x10,0x1e,0x11,0x11,0x0e}, /* 6 */
        {0x1f,0x01,0x02,0x04,0x08,0x08,0x08}, /* 7 */
        {0x0e,0x11,0x11,0x0e,0x11,0x11,0x0e}, /* 8 */
        {0x0e,0x11,0x11,0x0f,0x01,0x01,0x0e}, /* 9 */
        {0x00,0x04,0x04,0x00,0x04,0x04,0x00}, /* : */
        {0x00,0x00,0x00,0x00,0x00,0x0c,0x0c}, /* . */
        {0x01,0x02,0x02,0x04,0x08,0x08,0x10}, /* / */
        {0x00,0x00,0x00,0x1f,0x00,0x00,0x00}, /* - */
        {0x00,0x00,0x00,0x00,0x06,0x04,0x08}  /* , */
    };

    if (character >= 'a' && character <= 'z') {
        character = static_cast<TUint16>(character - 'a' + 'A');
    }
    if (character >= 'A' && character <= 'Z') {
        return glyphs[character - 'A'];
    }
    if (character >= '0' && character <= '9') {
        return glyphs[26 + character - '0'];
    }
    switch (character) {
    case ':': return glyphs[36];
    case '.': return glyphs[37];
    case '/': return glyphs[38];
    case '-': return glyphs[39];
    case ',': return glyphs[40];
    default: return 0;
    }
}

void drawLevel41StatusText(TUint8* pixels,
                           const TSize& size,
                           TInt strideBytes,
                           TInt left,
                           TInt baseline,
                           const TDesC& text)
{
    const TInt top = baseline - 7;
    TInt x = left;
    TInt index;
    for (index = 0; index < text.Length(); ++index) {
        const TUint16 character = static_cast<TUint16>(text[index]);
        const TUint8* glyph = level41StatusGlyph(character);
        if (glyph != 0) {
            TInt row;
            for (row = 0; row < 7; ++row) {
                const TInt y = top + row;
                if (y < 0 || y >= size.iHeight) continue;
                TInt column;
                for (column = 0; column < 5; ++column) {
                    if ((glyph[row] & (1U << (4 - column))) == 0) continue;
                    const TInt pixelX = x + column;
                    if (pixelX >= 0 && pixelX < size.iWidth) {
                        storeLevel41PresenterPixel(
                            0x00ffffff,
                            pixels + y * strideBytes +
                                pixelX * KLevel41PresenterBytesPerPixel);
                    }
                }
            }
        }
        x += 6;
        if (x >= size.iWidth) break;
    }
}

bool drawLevel41StatusOverlay(CFbsBitmap& bitmap,
                              const TSize& size,
                              TInt strideBytes)
{
    if (size.iWidth <= 0 || size.iHeight <= 0 || size.iWidth > 8192 ||
        strideBytes < size.iWidth * KLevel41PresenterBytesPerPixel) {
        return false;
    }

    bitmap.LockHeap();
    TUint32* address = bitmap.DataAddress();
    if (address == 0) {
        bitmap.UnlockHeap();
        return false;
    }
    TUint8* pixels = reinterpret_cast<TUint8*>(address);
    TBuf<64> line;

#if defined(QUANTUM_LEVEL41_FPS_EXPERIMENT_Q48)
    line.Copy(_L("LEVEL 41 / FPS EXP Q48"));
#elif defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
    line.Copy(_L("LEVEL 41 / RIVER DMODE 7"));
#else
    line.Copy(_L("LEVEL 41 / DMODE 9"));
#endif
    drawLevel41StatusText(pixels, size, strideBytes, 4, 14, line);

    if (gBenchmarkComplete) {
        line.Format(_L("10s average FPS: %d.%d"),
                    gDeviceFpsTenths / 10, gDeviceFpsTenths % 10);
    } else {
        line.Format(_L("Live FPS: %d.%d"),
                    gDeviceFpsTenths / 10, gDeviceFpsTenths % 10);
    }
    drawLevel41StatusText(pixels, size, strideBytes, 4, 32, line);

    line.Format(_L("Mesh avg/max: %d/%d ms"),
                gAverageMeshRenderMicros / 1000, gMaximumMeshRenderMillis);
    drawLevel41StatusText(pixels, size, strideBytes, 4, 48, line);

    if (gDsaPresenterActive) {
#if defined(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
        if (gLevel41DirectArgbBitmapMode) {
            line.Copy(_L("Presenter: DSA ARGB32"));
        } else
#endif
        {
#if defined(QUANTUM_LEVEL41_OMAP1710_ECOLOR64K)
            line.Copy(_L("Presenter: DSA EColor64K"));
#else
            line.Copy(_L("Presenter: DSA 24-bit"));
#endif
        }
    } else if (gDsaAttempted && gDsaStatus != KErrNone) {
        line.Format(_L("Presenter: DSA error %d"), gDsaStatus);
    } else if (gBitmapGdiPresenterActive) {
#if defined(QUANTUM_LEVEL41_OMAP1710_ECOLOR64K)
        line.Copy(_L("Presenter: EColor64K BitBlt"));
#else
        line.Copy(_L("Presenter: EColor16M BitBlt"));
#endif
    } else {
#if defined(QUANTUM_LEVEL41_OMAP1710_ECOLOR64K)
        line.Copy(_L("Presenter: GDI fallback"));
#else
        line.Copy(_L("Presenter: GDI 24-bit"));
#endif
    }
    drawLevel41StatusText(pixels, size, strideBytes, 4, 64, line);

#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
    line.Format(_L("Validation: %d ms"),
                gAverageMeshPhaseMillis[ESoftwareMeshRenderPhaseValidation]);
    drawLevel41StatusText(pixels, size, strideBytes, 4, 80, line);
    line.Format(_L("Transform/proj: %d ms"),
                gAverageMeshPhaseMillis[
                    ESoftwareMeshRenderPhaseTransformProjection]);
    drawLevel41StatusText(pixels, size, strideBytes, 4, 96, line);
    line.Format(_L("Cull/queue: %d ms"),
                gAverageMeshPhaseMillis[ESoftwareMeshRenderPhasePolygonQueue]);
    drawLevel41StatusText(pixels, size, strideBytes, 4, 112, line);
    line.Format(_L("Sort: %d ms"),
                gAverageMeshPhaseMillis[ESoftwareMeshRenderPhaseSort]);
    drawLevel41StatusText(pixels, size, strideBytes, 4, 128, line);
    line.Format(_L("Draw/raster: %d ms"),
                gAverageMeshPhaseMillis[ESoftwareMeshRenderPhaseDraw]);
    drawLevel41StatusText(pixels, size, strideBytes, 4, 144, line);
#else
    line.Copy(_L("Phase profiling: OFF"));
    drawLevel41StatusText(pixels, size, strideBytes, 4, 80, line);
#endif
#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
    if (gBenchmarkComplete) {
        const TInt divideCalls = averageRasterProfileValue(
            gTotalPerspectiveDivideCalls);
        const TInt divideSamples = averageRasterProfileValue(
            gTotalSampledPerspectiveDivideCalls);
        const TInt divideTenths = estimatedRasterKernelTenthsMillis(
            gTotalPerspectiveDivideCalls,
            gTotalSampledPerspectiveDivideCalls,
            gTotalSampledPerspectiveDivideTicks);
        if (divideTenths >= 0) {
            line.Format(_L("PDiv %d/f ~%d.%dms s%d"),
                        divideCalls, divideTenths / 10,
                        divideTenths % 10, divideSamples);
        } else {
            line.Copy(_L("PDiv timing unavailable"));
        }
        drawLevel41StatusText(pixels, size, strideBytes, 4, 160, line);
        const TInt pixelCount = averageRasterProfileValue(gTotalRasterPixels);
        const TInt pixelSamples = averageRasterProfileValue(
            gTotalSampledRasterPixelLoops);
        const TInt pixelTenths = estimatedRasterKernelTenthsMillis(
            gTotalRasterPixels, gTotalSampledRasterPixels,
            gTotalSampledRasterPixelTicks);
        if (pixelTenths >= 0) {
            line.Format(_L("Pixels %d/f ~%d.%dms s%d"),
                        pixelCount, pixelTenths / 10,
                        pixelTenths % 10, pixelSamples);
        } else {
            line.Copy(_L("Pixel timing unavailable"));
        }
        drawLevel41StatusText(pixels, size, strideBytes, 4, 176, line);
        TInt otherTenths = static_cast<TInt>(
            gTotalMeshPhaseMicros[ESoftwareMeshRenderPhaseDraw] /
            gBenchmarkFrames / 100) -
            (divideTenths < 0 ? 0 : divideTenths) -
            (pixelTenths < 0 ? 0 : pixelTenths);
        if (otherTenths < 0) otherTenths = 0;
        line.Format(_L("Other raster ~%d.%dms"),
                    otherTenths / 10, otherTenths % 10);
        drawLevel41StatusText(pixels, size, strideBytes, 4, 192, line);
        const TInt setupTenths =
            estimatedMode9TriangleSetupTenthsMillis();
        if (setupTenths >= 0) {
            line.Format(_L("M9 triangle setup ~%d.%dms s%d"),
                        setupTenths / 10, setupTenths % 10,
                        averageRasterProfileValue(
                            gTotalSampledMode9TriangleSetupCalls));
        } else {
            line.Copy(_L("M9 triangle setup unavailable"));
        }
        drawLevel41StatusText(pixels, size, strideBytes, 4, 208, line);
        const TInt edgeTenths = estimatedMode9EdgeTenthsMillis();
        if (edgeTenths >= 0) {
            line.Format(_L("M9 edge rows ~%d.%dms s%d"),
                        edgeTenths / 10, edgeTenths % 10,
                        averageRasterProfileValue(
                            gTotalSampledMode9EdgeRows));
        } else {
            line.Copy(_L("M9 edge rows unavailable"));
        }
        drawLevel41StatusText(pixels, size, strideBytes, 4, 224, line);
        const TInt spanTenths = estimatedMode9SpanSetupTenthsMillis();
        if (spanTenths >= 0) {
            line.Format(_L("M9 span setup ~%d.%dms s%d"),
                        spanTenths / 10, spanTenths % 10,
                        averageRasterProfileValue(
                            gTotalSampledMode9EdgeRows));
        } else {
            line.Copy(_L("M9 span setup unavailable"));
        }
        drawLevel41StatusText(pixels, size, strideBytes, 4, 240, line);
        line.Format(_L("Samples T/E/S %d/%d/%d FC %dkHz"),
                    averageRasterProfileValue(
                        gTotalSampledMode9TriangleSetupCalls),
                    averageRasterProfileValue(gTotalSampledMode9EdgeRows),
                    averageRasterProfileValue(gTotalSampledMode9EdgeRows),
                    static_cast<TInt>(gRasterFastCounterTicksPerSecond / 1000));
        drawLevel41StatusText(pixels, size, strideBytes, 4, 256, line);
        const TInt fillTenths = estimatedPresenterTenthsMillis(
            gTotalPresenterFillTicks);
        const TInt overlayTenths = estimatedPresenterTenthsMillis(
            gTotalPresenterOverlayTicks);
        const TInt blitTenths = estimatedPresenterTenthsMillis(
            gTotalPresenterBlitTicks);
        const TInt frameTenths = estimatedPresenterTenthsMillis(
            gTotalPresenterFrameTicks);
        if (fillTenths >= 0 && overlayTenths >= 0 && blitTenths >= 0 &&
            frameTenths >= 0) {
            line.Format(_L("F/O/B/P %d.%d/%d.%d/%d.%d/%d.%dms"),
                        fillTenths / 10, fillTenths % 10,
                        overlayTenths / 10, overlayTenths % 10,
                        blitTenths / 10, blitTenths % 10,
                        frameTenths / 10, frameTenths % 10);
        } else {
            line.Copy(_L("Presenter timing unavailable"));
        }
        drawLevel41StatusText(pixels, size, strideBytes, 4, 272, line);
    } else {
        line.Copy(_L("Raster details: profiling"));
        drawLevel41StatusText(pixels, size, strideBytes, 4, 160, line);
    }
#endif
#if defined(QUANTUM_LEVEL41_OMAP1710_ECOLOR64K)
#if defined(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
    if (gLevel41DirectArgbBitmapMode) {
        line.Copy(_L("DSA ARGB32 active"));
    } else if (gLevel41DirectArgbRenderEnabled) {
        line.Copy(_L("ARGB32 inactive; fallback"));
    } else {
        line.Copy(_L("DSA ARGB32 unavailable"));
    }
    drawLevel41StatusText(pixels, size, strideBytes, 4, 288, line);

    if (!gLevel41DirectArgbRenderEnabled ||
        (!gLevel41DirectArgbBitmapMode &&
         !gLevel41DirectRgb565ConversionVerified)) {
        line.Copy(_L("RGB565 compare unavailable"));
    } else if (!gLevel41DirectRgb565ConversionVerified) {
        line.Copy(_L("RGB565 check pending"));
    } else if (!gLevel41DirectRgb565ConversionAvailable) {
        line.Copy(_L("RGB565 check unavailable"));
    } else if (gLevel41DirectRgb565ConversionMatched) {
        line.Copy(_L("RGB565 C/ASM match (memory)"));
    } else if (gLevel41DirectRgb565MismatchKind ==
               ELevel41Rgb565MismatchBoundaryPattern) {
        line.Format(_L("RGB565 boundary @%d %d/%d"),
                    gLevel41DirectRgb565MismatchX,
                    static_cast<TInt>(gLevel41DirectRgb565Expected),
                    static_cast<TInt>(gLevel41DirectRgb565Actual));
    } else if (gLevel41DirectRgb565MismatchKind ==
               ELevel41Rgb565MismatchFrame) {
        line.Format(_L("RGB565 frame @%d,%d %d/%d"),
                    gLevel41DirectRgb565MismatchX,
                    gLevel41DirectRgb565MismatchY,
                    static_cast<TInt>(gLevel41DirectRgb565Expected),
                    static_cast<TInt>(gLevel41DirectRgb565Actual));
    } else {
        line.Copy(_L("RGB565 converter mismatch"));
    }
    drawLevel41StatusText(pixels, size, strideBytes, 4, 304, line);
#else
    if (gOmapScreenCopyActive && gOmapScreenCopyVerified &&
        gOmapScreenCopyMatched) {
        line.Copy(_L("Copy: OMAP ASM verified"));
    } else if (gOmapScreenCopyVerified && !gOmapScreenCopyMatched) {
        line.Copy(_L("Copy: CXX fallback - ASM mismatch"));
    } else {
        line.Copy(_L("Copy: CXX fallback"));
    }
    drawLevel41StatusText(pixels, size, strideBytes, 4, 288, line);
#endif
#endif
    line.Format(_L("Polygons %d Triangles %d"),
                gPolygonsDrawn, gTrianglesDrawn);
#if defined(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
    drawLevel41StatusText(pixels, size, strideBytes, 4, size.iHeight - 6, line);
#else
    drawLevel41StatusText(
        pixels, size, strideBytes, 4, size.iHeight - 22, line);
#endif
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
    if (SoftwareWeaponController::isReloading(gWeaponState)) {
        line.Format(_L("USP %d/%d RELOAD %d"),
                    gWeaponState.rounds, gWeaponState.reserveAmmo,
                    SoftwareWeaponController::reloadPercentage(
                        gWeaponTuning, gWeaponState));
    } else if (gWeaponState.shotFeedbackRemainingMs > 0 &&
               gWeaponState.hasShot) {
        if (gWeaponState.lastShotHitWorld) {
            line.Format(_L("USP %d/%d WALL"),
                        gWeaponState.rounds, gWeaponState.reserveAmmo);
        } else {
            line.Format(_L("USP %d/%d CLEAR"),
                        gWeaponState.rounds, gWeaponState.reserveAmmo);
        }
    } else if (gWeaponTuningLoadedFromAssets) {
        line.Format(_L("USP %d/%d CFG"),
                    gWeaponState.rounds, gWeaponState.reserveAmmo);
    } else {
        line.Format(_L("USP %d/%d TEST"),
                    gWeaponState.rounds, gWeaponState.reserveAmmo);
    }
    drawLevel41StatusText(
        pixels, size, strideBytes, 4, size.iHeight - 70, line);
    line.Format(_L("X:%d Z:%d YAW:%d"),
                gWalkState.x, gWalkState.z, gWalkState.yawDegrees);
    drawLevel41StatusText(
        pixels, size, strideBytes, 4, size.iHeight - 54, line);
    line.Copy(_L("UP/DOWN WALK L/R TURN 5 FIRE"));
    drawLevel41StatusText(
        pixels, size, strideBytes, 4, size.iHeight - 38, line);
    if (gLevel41Audio != 0) {
        gLevel41Audio->StatusLine(line);
    } else {
        line.Format(_L("AUDIO INIT E%d"), gAudioInitializationError);
    }
#else
    line.Copy(_L("Press any key to exit"));
#endif
    drawLevel41StatusText(
        pixels, size, strideBytes, 4, size.iHeight - 6, line);

    bitmap.UnlockHeap();
    return true;
}

class TLevel41BitmapGdiPresenter
{
public:
    TLevel41BitmapGdiPresenter()
        : iBitmap(0), iSize(0, 0), iStrideBytes(0)
    {
    }

    ~TLevel41BitmapGdiPresenter()
    {
        Close();
    }

    bool Create(RWsSession& session, const TSize& size)
    {
        Close();
        if (size.iWidth <= 0 || size.iHeight <= 0) return false;

        iBitmap = new CWsBitmap(session);
        if (iBitmap == 0) return false;
        const TDisplayMode displayMode = level41PresenterDisplayMode();
        if (iBitmap->Create(size, displayMode) != KErrNone) {
            Close();
            return false;
        }

        const TInt scanLineBytes =
            CFbsBitmap::ScanLineLength(size.iWidth, displayMode);
        if (scanLineBytes <
            size.iWidth * KLevel41PresenterBytesPerPixel) {
            Close();
            return false;
        }

        iSize = size;
        iStrideBytes = scanLineBytes;
        return true;
    }

    void Close()
    {
        delete iBitmap;
        iBitmap = 0;
        iSize = TSize(0, 0);
        iStrideBytes = 0;
    }

    bool DrawFrame(CWindowGc& gc)
    {
        if (iBitmap == 0 ||
            !fillLevel41Bitmap(*iBitmap, iSize, iStrideBytes)) {
            return false;
        }
        gc.BitBlt(TPoint(0, 0), iBitmap);
        return true;
    }

private:
    CWsBitmap* iBitmap;
    TSize iSize;
    TInt iStrideBytes;
};

#ifndef QUANTUM_LEVEL41_NO_DSA
class CDsaStarter;

class TLevel41DsaObserver : public MDirectScreenAccess
{
public:
    TLevel41DsaObserver(RWsSession& session,
                        CWsScreenDevice& screen,
                        const TSize& size)
        : iSession(session), iScreen(screen), iSize(size), iAccess(0),
          iStarter(0), iPixmap(0), iStrideBytes(0), iDsaActive(false),
          iDirectArgbActive(false), iDirectArgbFrameReady(false)
    {
    }

    ~TLevel41DsaObserver()
    {
        CleanupPixmap();
    }

    void SetAccess(CDirectScreenAccess* access)
    {
        iAccess = access;
    }

    void SetStarter(CDsaStarter* starter)
    {
        iStarter = starter;
    }

    void SetDsaActive(bool active)
    {
        iDsaActive = active;
        gDsaPresenterActive = active;
    }

    bool IsDsaActive() const
    {
        return iDsaActive;
    }

    void PreparePixmapL()
    {
        if (iPixmap != 0 &&
            iStrideBytes >=
                iSize.iWidth * KLevel41PresenterBytesPerPixel) return;
        CleanupPixmap();

#if defined(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
        const bool directGeometryMatches =
            gFramebuffer != 0 && iSize.iWidth == gRenderWidth &&
            iSize.iHeight == gRenderHeight &&
            gRenderPixelCount == gRenderWidth * gRenderHeight;
        if (directGeometryMatches) {
            iPixmap = new(ELeave) CWsBitmap(iSession);
            const TInt directError = iPixmap->Create(iSize, EColor16MU);
            const TInt directStride = directError == KErrNone
                ? CFbsBitmap::ScanLineLength(iSize.iWidth, EColor16MU) : 0;
            if (directError == KErrNone &&
                directStride == iSize.iWidth * 4) {
                iStrideBytes = directStride;
                iDirectArgbActive = true;
                iDirectArgbFrameReady = false;
                gLevel41DirectArgbBitmapMode = true;
                gLevel41DirectArgbRenderEnabled = true;
                gLevel41DirectRgb565ConversionVerified = false;
                gLevel41DirectRgb565ConversionAvailable = false;
                gLevel41DirectRgb565ConversionMatched = false;
                gLevel41DirectRgb565MismatchKind = ELevel41Rgb565MismatchNone;
                gLevel41DirectRgb565MismatchX = -1;
                gLevel41DirectRgb565MismatchY = -1;
                gLevel41DirectRgb565Expected = 0;
                gLevel41DirectRgb565Actual = 0;
                return;
            }
            delete iPixmap;
            iPixmap = 0;
            iStrideBytes = 0;
#if defined(QUANTUM_LEVEL41_DSA_ARGB32_REQUIRED)
            User::Leave(directError != KErrNone
                ? directError : KErrNotSupported);
#endif
        }
#if defined(QUANTUM_LEVEL41_DSA_ARGB32_REQUIRED)
        if (!directGeometryMatches) User::Leave(KErrNotSupported);
#endif
#endif

#if defined(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
        gLevel41DirectArgbBitmapMode = false;
#endif
        iPixmap = new(ELeave) CWsBitmap(iSession);
        const TDisplayMode displayMode = level41PresenterDisplayMode();
        User::LeaveIfError(iPixmap->Create(iSize, displayMode));
        const TInt scanLineBytes =
            CFbsBitmap::ScanLineLength(iSize.iWidth, displayMode);
        if (scanLineBytes <
            iSize.iWidth * KLevel41PresenterBytesPerPixel) {
            User::Leave(KErrNotSupported);
        }
        iStrideBytes = scanLineBytes;
    }

    void CleanupPixmap()
    {
        delete iPixmap;
        iPixmap = 0;
        iStrideBytes = 0;
        iDirectArgbActive = false;
        iDirectArgbFrameReady = false;
#if defined(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
        gLevel41DirectArgbBitmapMode = false;
        gLevel41DirectArgbRenderEnabled = false;
        gLevel41DirectRgb565ConversionVerified = false;
        gLevel41DirectRgb565ConversionAvailable = false;
        gLevel41DirectRgb565ConversionMatched = false;
        gLevel41DirectRgb565MismatchKind = ELevel41Rgb565MismatchNone;
        gLevel41DirectRgb565MismatchX = -1;
        gLevel41DirectRgb565MismatchY = -1;
        gLevel41DirectRgb565Expected = 0;
        gLevel41DirectRgb565Actual = 0;
#endif
    }

#if defined(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
    bool fallbackToRgb565Pixmap()
    {
#if defined(QUANTUM_LEVEL41_DSA_ARGB32_REQUIRED)
        return false;
#else
        delete iPixmap;
        iPixmap = 0;
        iStrideBytes = 0;
        iDirectArgbActive = false;
        iDirectArgbFrameReady = false;
        gLevel41DirectArgbBitmapMode = false;

        iPixmap = new CWsBitmap(iSession);
        if (iPixmap == 0) return false;
        const TInt createError = iPixmap->Create(iSize, EColor64K);
        if (createError != KErrNone) {
            delete iPixmap;
            iPixmap = 0;
            return false;
        }
        iStrideBytes = CFbsBitmap::ScanLineLength(iSize.iWidth, EColor64K);
        if (iStrideBytes < iSize.iWidth * 2) {
            delete iPixmap;
            iPixmap = 0;
            iStrideBytes = 0;
            return false;
        }
        return true;
#endif
    }
#endif

    bool RenderSceneFrame()
    {
#if defined(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
        if (iDirectArgbActive) {
            if (iPixmap == 0 || gFramebuffer == 0 ||
                iSize.iWidth != gRenderWidth ||
                iSize.iHeight != gRenderHeight ||
                iStrideBytes != iSize.iWidth * 4) {
                if (!fallbackToRgb565Pixmap()) return false;
                return renderLevel41Frame();
            }

            iPixmap->LockHeap();
            TUint32* address = iPixmap->DataAddress();
            if (address == 0 ||
                (reinterpret_cast<TUint32>(address) & 3U) != 0) {
                iPixmap->UnlockHeap();
                if (!fallbackToRgb565Pixmap()) return false;
                return renderLevel41Frame();
            }

            // Render this state directly into the DSA bitmap. The saved
            // framebuffer contains a prior frame, so comparing the two would
            // falsely trigger the RGB565 fallback as the scene changes.
            QInt32* const savedFramebuffer = gFramebuffer;
            QInt32* const directFramebuffer =
                reinterpret_cast<QInt32*>(address);
            gFramebuffer = directFramebuffer;
            const bool rendered = renderLevel41Frame();
            if (rendered &&
                !gLevel41DirectRgb565ConversionVerified) {
                verifyLevel41DirectRgb565Conversion(directFramebuffer);
            }
            gFramebuffer = savedFramebuffer;
            iPixmap->UnlockHeap();
            if (!rendered) return false;
            iDirectArgbFrameReady = true;
            return true;
        }
#endif
#if defined(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
        iDirectArgbFrameReady = false;
#endif
        return renderLevel41Frame();
    }

    bool DrawFrame()
    {
        if (!iDsaActive || iAccess == 0 || iAccess->Gc() == 0 ||
            iPixmap == 0) {
            return false;
        }
#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
        const TUint32 frameStartTicks = User::FastCounter();
#endif
#if defined(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
        if (!iDirectArgbFrameReady &&
            !fillLevel41Bitmap(*iPixmap, iSize, iStrideBytes)) {
            return false;
        }
        iDirectArgbFrameReady = false;
#else
        if (!fillLevel41Bitmap(*iPixmap, iSize, iStrideBytes)) {
            return false;
        }
#endif
#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
        const TUint32 fillEndTicks = User::FastCounter();
        gTotalPresenterFillTicks += correctedPresenterElapsedTicks(
            frameStartTicks, fillEndTicks);
#endif
        if (!drawLevel41StatusOverlay(*iPixmap, iSize, iStrideBytes)) {
            return false;
        }
#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
        const TUint32 overlayEndTicks = User::FastCounter();
        gTotalPresenterOverlayTicks += correctedPresenterElapsedTicks(
            fillEndTicks, overlayEndTicks);
#endif
        iAccess->Gc()->BitBlt(TPoint(0, 0), iPixmap);
#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
        const TUint32 frameEndTicks = User::FastCounter();
        gTotalPresenterBlitTicks += correctedPresenterElapsedTicks(
            overlayEndTicks, frameEndTicks);
        gTotalPresenterFrameTicks += correctedPresenterElapsedTicks(
            frameStartTicks, frameEndTicks);
        ++gPresenterFrameSamples;
#endif
        return true;
    }

    void AbortNow(RDirectScreenAccess::TTerminationReasons reason)
    {
        (void)reason;
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
        clearLevel41WalkInput();
#endif
        // Do not issue screen or WindowServer calls while access is being revoked.
        SetDsaActive(false);
    }

    void Restart(RDirectScreenAccess::TTerminationReasons reason);

private:
    RWsSession& iSession;
    CWsScreenDevice& iScreen;
    TSize iSize;
    CDirectScreenAccess* iAccess;
    CDsaStarter* iStarter;
    CWsBitmap* iPixmap;
    TInt iStrideBytes;
    bool iDsaActive;
    bool iDirectArgbActive;
    bool iDirectArgbFrameReady;
};

class CDsaFrameRunner : public CActive
{
public:
    static CDsaFrameRunner* NewL(TLevel41DsaObserver& observer)
    {
        CDsaFrameRunner* self = new(ELeave) CDsaFrameRunner(observer);
        CleanupStack::PushL(self);
        self->ConstructL();
        CleanupStack::Pop(self);
        return self;
    }

    ~CDsaFrameRunner()
    {
        Cancel();
        if (iTimerCreated) iTimer.Close();
    }

    void Start()
    {
        ScheduleNext();
    }

    void Stop()
    {
        Cancel();
    }

    TInt Result() const
    {
        return iResult;
    }

private:
    CDsaFrameRunner(TLevel41DsaObserver& observer)
        : CActive(CActive::EPriorityStandard), iObserver(observer),
          iTimerCreated(false), iResult(KErrNone)
    {
        CActiveScheduler::Add(this);
    }

    void ConstructL()
    {
        User::LeaveIfError(iTimer.CreateLocal());
        iTimerCreated = true;
    }

    void ScheduleNext()
    {
        if (IsActive() || gBenchmarkComplete || iResult != KErrNone) return;
        iTimer.After(iStatus, TTimeIntervalMicroSeconds32(1000));
        SetActive();
    }

    void DoCancel()
    {
        if (iTimerCreated) iTimer.Cancel();
    }

    void RunL()
    {
        if (iStatus.Int() != KErrNone) {
            iResult = iStatus.Int();
            CActiveScheduler::Stop();
            return;
        }

        if (iObserver.IsDsaActive()) {
#if defined(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
            const bool rendered = iObserver.RenderSceneFrame();
#else
            const bool rendered = renderLevel41Frame();
#endif
            if (!rendered) {
                iResult = KErrCorrupt;
                CActiveScheduler::Stop();
                return;
            }
            recordBenchmarkFrame(gLastMeshRenderMicros);
            if (!iObserver.DrawFrame()) {
                iResult = KErrNotReady;
                CActiveScheduler::Stop();
                return;
            }
        }

        if (!gBenchmarkComplete) ScheduleNext();
    }

private:
    TLevel41DsaObserver& iObserver;
    RTimer iTimer;
    bool iTimerCreated;
    TInt iResult;
};

// Run leave-capable DSA setup from an active object so RunError can turn a
// failed NewL/StartL into the existing WindowServer fallback without a direct
// TTrap import in the E32 image.
class CDsaStarter : public CActive
{
public:
    CDsaStarter(RWsSession& session,
                CWsScreenDevice& screen,
                RWindow& window,
                TLevel41DsaObserver& observer)
        : CActive(CActive::EPriorityStandard),
          iSession(session), iScreen(screen), iWindow(window),
          iObserver(observer), iAccess(0), iFrameRunner(0),
          iResult(KErrNotReady), iStartRequested(false),
          iBenchmarkStarted(false)
    {
        CActiveScheduler::Add(this);
        iObserver.SetStarter(this);
    }

    ~CDsaStarter()
    {
        Stop();
        delete iFrameRunner;
        iObserver.SetStarter(0);
    }

    void Start()
    {
        ScheduleStart();
    }

    void Stop()
    {
        Cancel();
        if (iFrameRunner != 0) iFrameRunner->Stop();
        StopAccess();
    }

    TInt Result() const
    {
        return iResult;
    }

    TInt FrameResult() const
    {
        return iFrameRunner != 0 ? iFrameRunner->Result() : KErrNone;
    }

    void ScheduleStart()
    {
        if (IsActive() || (iStartRequested && iResult != KErrNone)) return;
        iStartRequested = true;
        iStatus = KRequestPending;
        TRequestStatus* status = &iStatus;
        User::RequestComplete(status, KErrNone);
        SetActive();
    }

private:
    void DoCancel()
    {
    }

    void RunL()
    {
        iObserver.PreparePixmapL();
        if (iAccess == 0) {
            iAccess = CDirectScreenAccess::NewL(
                iSession, iScreen, iWindow, iObserver);
            iObserver.SetAccess(iAccess);
        }
        iSession.Flush();
        iAccess->StartL();
        CFbsScreenDevice* directScreen = iAccess->ScreenDevice();
        if (directScreen == 0) {
            iResult = KErrNotSupported;
            StopAccess();
            CActiveScheduler::Stop();
            return;
        }
        directScreen->SetAutoUpdate(ETrue);
        iObserver.SetDsaActive(true);
#if defined(QUANTUM_LEVEL41_OMAP1710_DSA_ARGB_BITMAP)
        if (!iObserver.RenderSceneFrame()) {
            iResult = KErrCorrupt;
            StopAccess();
            CActiveScheduler::Stop();
            return;
        }
#endif
        if (!iObserver.DrawFrame()) {
            iResult = KErrNotSupported;
            StopAccess();
            CActiveScheduler::Stop();
            return;
        }

        iResult = KErrNone;
        if (!iBenchmarkStarted) {
            iFrameRunner = CDsaFrameRunner::NewL(iObserver);
            resetBenchmark();
            iBenchmarkStarted = true;
            iFrameRunner->Start();
        }
    }

    TInt RunError(TInt error)
    {
        iResult = error;
        StopAccess();
        CActiveScheduler::Stop();
        return KErrNone;
    }

    void StopAccess()
    {
        if (iFrameRunner != 0) iFrameRunner->Stop();
        iObserver.SetDsaActive(false);
        if (iAccess != 0) {
            iObserver.SetAccess(0);
            iAccess->Cancel();
            delete iAccess;
            iAccess = 0;
        }
        iObserver.CleanupPixmap();
    }

private:
    RWsSession& iSession;
    CWsScreenDevice& iScreen;
    RWindow& iWindow;
    TLevel41DsaObserver& iObserver;
    CDirectScreenAccess* iAccess;
    CDsaFrameRunner* iFrameRunner;
    TInt iResult;
    bool iStartRequested;
    bool iBenchmarkStarted;
};

void TLevel41DsaObserver::Restart(
    RDirectScreenAccess::TTerminationReasons reason)
{
    (void)reason;
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
    clearLevel41WalkInput();
#endif
    if (iStarter != 0) iStarter->ScheduleStart();
}

class CWindowEventWaiter : public CActive
{
public:
    CWindowEventWaiter(RWsSession& session, TUint groupClientHandle)
        : CActive(CActive::EPriorityStandard),
          iSession(session), iGroupClientHandle(groupClientHandle)
    {
        CActiveScheduler::Add(this);
    }

    void Start()
    {
        iSession.EventReady(&iStatus);
        SetActive();
    }

    void Stop()
    {
        Cancel();
        if (iStatus == KRequestPending) User::WaitForRequest(iStatus);
    }

private:
    void DoCancel()
    {
        iSession.EventReadyCancel();
    }

    void RunL()
    {
        if (iStatus.Int() != KErrNone) {
            CActiveScheduler::Stop();
            return;
        }

        TWsEvent event;
        iSession.GetEvent(event);
        // Key events carry the focused group client handle, not the window handle.
        const TBool isKeyEvent = event.Type() == EEventKey ||
                                event.Type() == EEventKeyDown ||
                                event.Type() == EEventKeyUp;
        if (event.Handle() == iGroupClientHandle && isKeyEvent) {
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
            if (!handleLevel41WalkEvent(event)) {
                clearLevel41WalkInput();
                CActiveScheduler::Stop();
                return;
            }
#else
            if (event.Type() == EEventKey) {
                CActiveScheduler::Stop();
                return;
            }
#endif
        }

        iSession.EventReady(&iStatus);
        SetActive();
    }

private:
    RWsSession& iSession;
    TUint iGroupClientHandle;
};

TInt runDirectScreenAccess(RWsSession& session,
                           CWsScreenDevice& screen,
                           RWindow& window,
                           TRequestStatus& redraws,
                           const TSize& size,
                           bool& usedDirectScreenAccess)
{
    usedDirectScreenAccess = false;

    // Remove any pending WindowServer redraw request before taking DSA access.
    if (redraws == KRequestPending) {
        session.RedrawReadyCancel();
        User::WaitForRequest(redraws);
    } else if (redraws.Int() == KErrNone) {
        TWsRedrawEvent pendingRedraw;
        session.GetRedraw(pendingRedraw);
    }

    TLevel41DsaObserver observer(session, screen, size);
    CDsaStarter starter(session, screen, window, observer);
    CWindowEventWaiter eventWaiter(session, KWindowGroupClientHandle);
    eventWaiter.Start();
    starter.Start();
    CActiveScheduler::Start();
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
    clearLevel41WalkInput();
#endif

    TInt result = starter.Result();
    const TInt frameResult = starter.FrameResult();
    if (result == KErrNone && frameResult != KErrNone) result = frameResult;
    eventWaiter.Stop();
    starter.Stop();
    usedDirectScreenAccess = (result == KErrNone);
    return result;
}
#endif // QUANTUM_LEVEL41_NO_DSA

bool allocateRenderBuffer(const TSize& size)
{
    const TInt nativePixelCount = size.iWidth * size.iHeight;
    if (nativePixelCount <= KMaximumRenderPixels) {
        gFramebuffer = static_cast<QInt32*>(User::Alloc(
            nativePixelCount * static_cast<TInt>(sizeof(QInt32))));
        if (gFramebuffer != 0) {
            gRenderWidth = size.iWidth;
            gRenderHeight = size.iHeight;
            gRenderPixelCount = nativePixelCount;
            return true;
        }
    }

#if defined(QUANTUM_LEVEL41_DSA_ARGB32_REQUIRED)
    // The direct ARGB32 DSA bitmap must alias the native-resolution renderer.
    // A scaled low-memory framebuffer would silently violate the requested
    // output mode, so fail instead of changing the candidate's format.
    return false;
#else
    // Keep the earlier low-memory path available on devices that cannot hold
    // a native-resolution framebuffer.
    gRenderWidth = KBaseRenderWidth;
    gRenderHeight = KBaseRenderHeight;
    gRenderPixelCount = KBaseRenderWidth * KBaseRenderHeight;
    gFramebuffer = static_cast<QInt32*>(User::Alloc(
        gRenderPixelCount * static_cast<TInt>(sizeof(QInt32))));
    return gFramebuffer != 0;
#endif
}

TInt runWindow(RFs& fileServer,
               const TDesC& drive,
               RWsSession& session,
               CWsScreenDevice& screen,
               CWindowGc& gc)
{
    const TSize size = screen.SizeInPixels();
    if (size.iWidth <= 0 || size.iHeight <= 0 ||
        size.iWidth > 8192 || size.iHeight > 8192) {
        return KErrArgument;
    }

    TLevel41BitmapGdiPresenter bitmapPresenter;
    RWindowGroup group(session);
    TInt result = group.Construct(KWindowGroupClientHandle, ETrue);
    if (result != KErrNone) return result;

    RWindow window(session);
    result = window.Construct(group, KProbeWindowHandle);
    if (result != KErrNone) {
        group.Close();
        return result;
    }

    group.SetOrdinalPosition(0);
    window.SetExtent(TPoint(0, 0), size);
    window.SetVisible(ETrue);
    window.Activate();

    TRequestStatus events;
    events = KRequestPending;
    TRequestStatus redraws;
    redraws = KRequestPending;
    bool eventRequestStarted = false;
    session.RedrawReady(&redraws);
    window.Invalidate();
    session.Flush();

    // Publish an active window before loading and rendering the scene. This
    // keeps the S60 launcher responsive while the fixed view is prepared.
    User::WaitForRequest(redraws);
    result = redraws.Int();
    if (result == KErrNone) {
        TWsRedrawEvent redraw;
        session.GetRedraw(redraw);
        if (redraw.Handle() == KProbeWindowHandle) {
            window.BeginRedraw(redraw.Rect());
            gc.Activate(window);
            drawStartup(screen, gc, size);
            gc.Deactivate();
            window.EndRedraw();
        }
        session.RedrawReady(&redraws);
        session.Flush();
        User::After(250000);

        if (gRenderStatus == KErrNone && !allocateRenderBuffer(size)) {
            gRenderStatus = KErrNoMemory;
        }
        if (gRenderStatus == KErrNone &&
            !loadLevel41(fileServer, drive)) {
            gRenderStatus = gAssetFailureCode != KErrNone
                ? gAssetFailureCode : KErrCorrupt;
        }
        if (gRenderStatus == KErrNone) {
            if (!renderLevel41Frame()) {
                gRenderStatus = KErrCorrupt;
            } else {
                // The warm-up render performed the full mesh/material checks.
                // Level 41's loaded assets remain immutable while benchmarking.
                gMeshContentsPrevalidated = true;
#ifdef QUANTUM_LEVEL41_NO_DSA
                gBitmapGdiPresenterActive = bitmapPresenter.Create(session, size);
#endif
                resetBenchmark();
            }
        }

#ifndef QUANTUM_LEVEL41_NO_DSA
        if (gRenderStatus == KErrNone) {
            bool usedDirectScreenAccess = false;
            gDsaAttempted = true;
            gDsaStatus = runDirectScreenAccess(
                session, screen, window, redraws, size,
                usedDirectScreenAccess);
            if (usedDirectScreenAccess) {
                window.Close();
                group.Close();
                session.Flush();
                return KErrNone;
            }

#if defined(QUANTUM_LEVEL41_DSA_ARGB32_REQUIRED)
            // This candidate is specifically a DSA ARGB32 profile. Do not hide
            // DSA/ARGB startup failures behind a different WindowServer format.
            gRenderStatus = gDsaStatus != KErrNone
                ? gDsaStatus : KErrNotSupported;
#else
            // Keep the known-tested bulk WindowServer path as a runtime
            // fallback, and exclude failed DSA startup time from its FPS.
            gBitmapGdiPresenterActive = bitmapPresenter.Create(session, size);
            resetBenchmark();
#endif
        }
#endif

        if (redraws != KRequestPending) {
            if (redraws.Int() == KErrNone) {
                TWsRedrawEvent pendingRedraw;
                session.GetRedraw(pendingRedraw);
            }
            session.RedrawReady(&redraws);
        }
        window.Invalidate();
        session.Flush();
        session.EventReady(&events);
        eventRequestStarted = true;
    }

    bool running = result == KErrNone;
    while (running) {
        User::WaitForRequest(events, redraws);
        if (events != KRequestPending) {
            result = events.Int();
            if (result != KErrNone) break;
            TWsEvent event;
            session.GetEvent(event);
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
            const TBool isWalkKeyEvent = event.Type() == EEventKey ||
                                         event.Type() == EEventKeyDown ||
                                         event.Type() == EEventKeyUp;
#else
            const TBool isWalkKeyEvent = event.Type() == EEventKey;
#endif
            if (event.Handle() == KWindowGroupClientHandle &&
                isWalkKeyEvent) {
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
                if (handleLevel41WalkEvent(event)) {
                    window.Invalidate();
                    session.Flush();
                    session.EventReady(&events);
                } else {
                    clearLevel41WalkInput();
                    running = false;
                }
#else
                running = false;
#endif
            } else if (running) {
                session.EventReady(&events);
            }
        }
        if (running && redraws != KRequestPending) {
            result = redraws.Int();
            if (result != KErrNone) break;
            TWsRedrawEvent redraw;
            session.GetRedraw(redraw);
            bool renderedFrame = false;
            if (redraw.Handle() == KProbeWindowHandle) {
                if (gRenderStatus == KErrNone && !gBenchmarkComplete) {
                    if (renderLevel41Frame()) {
                        renderedFrame = true;
                    } else {
                        gRenderStatus = KErrCorrupt;
                    }
                }
                window.BeginRedraw(redraw.Rect());
                gc.Activate(window);
                if (gRenderStatus == KErrNone) {
                    if (gBitmapGdiPresenterActive &&
                        bitmapPresenter.DrawFrame(gc)) {
                        drawStatusOverlay(screen, gc, size);
                    } else {
                        if (gBitmapGdiPresenterActive) {
                            bitmapPresenter.Close();
                            gBitmapGdiPresenterActive = false;
                        }
                        drawOutput(screen, gc, &session, size);
                    }
                } else {
                    drawFailure(screen, gc, size, gRenderStatus);
                }
                gc.Deactivate();
                window.EndRedraw();
            }
            session.RedrawReady(&redraws);
            session.Flush();
            if (gRenderStatus == KErrNone && renderedFrame) {
                recordBenchmarkFrame(gLastMeshRenderMicros);
            }
            if (gRenderStatus == KErrNone &&
                (!gBenchmarkComplete || gBenchmarkFinalOverlayPending)) {
                gBenchmarkFinalOverlayPending = false;
                window.Invalidate();
            }
        }
        session.Flush();
    }

#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
    clearLevel41WalkInput();
#endif
    if (eventRequestStarted) session.EventReadyCancel();
    session.RedrawReadyCancel();
    if (events == KRequestPending) User::WaitForRequest(events);
    if (redraws == KRequestPending) User::WaitForRequest(redraws);
    window.Close();
    group.Close();
    session.Flush();
    return result;
}

} // namespace

int main()
{
    CTrapCleanup* cleanup = CTrapCleanup::New();
    if (cleanup == 0) return KErrNoMemory;

    CActiveScheduler* scheduler = new CActiveScheduler;
    if (scheduler == 0) {
        delete cleanup;
        return KErrNoMemory;
    }
    CActiveScheduler::Install(scheduler);

    const TInt textureStorageBytes =
        KTextureStoragePixels * static_cast<TInt>(sizeof(QInt32));
    gTextureStorage = static_cast<QInt32*>(User::Alloc(textureStorageBytes));
    /*
     * One 16-bit level per texture (no mip chain), so this is half the size
     * of the 32-bit storage. A failure here degrades to the 32-bit path
     * rather than stopping the app.
     */
    const TInt texture16StorageBytes =
        KTextureStoragePixels * static_cast<TInt>(sizeof(QUint16));
    gTexture16Storage =
        static_cast<QUint16*>(User::Alloc(texture16StorageBytes));
    if (gTexture16Storage != 0) {
        gForestPixels16 = gTexture16Storage;
        gMapPixels16 = gForestPixels16 + KForestTexturePixels;
        gRiverPixels16 = gMapPixels16 + KMapTexturePixels;
        gSkyPixels16 = gRiverPixels16 + KRiverTexturePixels;
    }
    if (gTextureStorage == 0) {
        gRenderStatus = KErrNoMemory;
    } else {
        gForestPixels = gTextureStorage;
        gMapPixels = gForestPixels + KForestTexturePixels;
        gRiverPixels = gMapPixels + KMapTexturePixels;
        gSkyPixels = gRiverPixels + KRiverTexturePixels;
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
        gLevel1MeshTexturePixels = gSkyPixels + KSkyTexturePixels;
        gLevel1SpriteTexturePixels =
            gLevel1MeshTexturePixels + KLevel1MeshTexturePixels;
        if (gTexture16Storage != 0) {
            gLevel1MeshTexturePixels16 =
                gSkyPixels16 + KSkyTexturePixels;
        }
#endif
    }

    RFs fileServer;
    bool fileServerConnected = false;
    TBuf<2> drive;
    if (gRenderStatus == KErrNone) {
        gRenderStatus = fileServer.Connect();
        if (gRenderStatus == KErrNone) {
            fileServerConnected = true;
            const TFileName executable = RProcess().FileName();
            if (executable.Length() < 2 || executable[1] != ':') {
                gRenderStatus = KErrNotFound;
            } else {
                drive.Copy(executable.Left(2));
            }
        }
    }

    TInt result = KErrNone;
    RWsSession session;
    result = session.Connect();
    if (result == KErrNone) {
        CWsScreenDevice screen(session);
        result = screen.Construct();
        if (result == KErrNone) {
            CWindowGc gc(&screen);
            result = gc.Construct();
            if (result == KErrNone) {
                result = runWindow(fileServer, drive, session, screen, gc);
            }
        }
        session.Close();
    }

#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
    if (gLevel41Audio != 0) {
        delete gLevel41Audio;
        gLevel41Audio = 0;
    }
#endif
    if (fileServerConnected) closeFileServerSession(fileServer);
#if defined(QUANTUM_LEVEL41_INTERACTIVE_WALK)
    releaseWalkCollisionStorage();
    releaseWeaponSpriteBuffers();
#endif
    if (gFramebuffer != 0) User::Free(gFramebuffer);
    if (gTextureStorage != 0) User::Free(gTextureStorage);
    CActiveScheduler::Install(0);
    delete scheduler;
    delete cleanup;
    return result;
}
