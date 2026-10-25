#include "matrix.h"
#include "quantum_integer.h"
#include "software_mesh_3d_parser.h"
#include "software_mesh_renderer.h"
#include "software_triangle_renderer.h"

#include <e32base.h>
#include <f32file.h>
#ifndef QUANTUM_LEVEL41_NO_DSA
#include <bitdev.h>
#include <bitstd.h>
#include <fbs.h>
#endif
#include <gdi.h>
#include <w32std.h>

namespace {

const TUint KWindowGroupClientHandle = 1;
const TUint KProbeWindowHandle = 2;

using code::Math::Matrix;
using code::Math::QInt32;
using code::Math::QUint32;
using code::Math::Vector3D;
using code::Rendering::SoftwareMesh3DParser;
using code::Rendering::SoftwareMesh3DRequirements;
using code::Rendering::SoftwareMesh3DPart;
using code::Rendering::SoftwareMeshInstance;
using code::Rendering::SoftwareMeshMaterial;
using code::Rendering::SoftwareMeshPolygon;
using code::Rendering::SoftwareMeshRenderItem;
using code::Rendering::SoftwareMeshRenderOptions;
using code::Rendering::SoftwareMeshRenderStats;
using code::Rendering::SoftwareMeshRenderWorkspace;
using code::Rendering::SoftwareMeshRenderer;
using code::Rendering::SoftwareMeshView;
using code::Rendering::SoftwareProjectionParameters;
using code::Rendering::SoftwareRenderSurfaceView;
using code::Rendering::SoftwareRenderVertex;
using code::Rendering::SoftwareTextureView;
using code::Rendering::ESoftwareMeshProjectionDirectX7;

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
const TInt KRiverTexturePixels = 32 * 32;
const TInt KSkyTexturePixels = 256 * 256;
const TInt KTextureStoragePixels =
    KForestTexturePixels + KMapTexturePixels + KRiverTexturePixels +
    KSkyTexturePixels;
const TInt KMaximumWorldFileBytes = 60000;
const TInt KMaximumLightmapBytes = 12000;
const TInt KMaximumSkyboxFileBytes = 2048;
const QInt32 KWorldVertexCapacity = 2729;
const QInt32 KWorldPolygonCapacity = 2641;
const QInt32 KSkyVertexCapacity = 24;
const QInt32 KSkyPolygonCapacity = 22;
const QInt32 KTotalVertexCapacity = KWorldVertexCapacity + KSkyVertexCapacity;
const QInt32 KTotalPolygonCapacity = KWorldPolygonCapacity + KSkyPolygonCapacity;
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
SoftwareMeshRenderItem gRenderQueue[KTotalPolygonCapacity];

QInt32* gTextureStorage = 0;
QInt32* gForestPixels = 0;
QInt32* gMapPixels = 0;
QInt32* gRiverPixels = 0;
QInt32* gSkyPixels = 0;
TUint8 gWorldFile[KMaximumWorldFileBytes];
TUint8 gLightmapFile[KMaximumLightmapBytes];
TUint8 gSkyboxFile[KMaximumSkyboxFileBytes];
TUint8 gTextureFile[KMaximumTextureFileBytes];

SoftwareTextureView gWorldMipViews[5];
SoftwareTextureView gSkyMipView;
SoftwareMeshMaterial gWorldMaterials[5];
SoftwareMeshMaterial gSkyMaterial[1];
SoftwareMeshView gWorldView;
SoftwareMeshView gSkyView;

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
    ERenderStageRasterizer,
    ERenderStageNoPolygons
};

TInt gRenderStatus = KErrNone;
TInt gRenderStage = ERenderStageNone;
QInt32 gPolygonsDrawn = 0;
QInt32 gTrianglesDrawn = 0;

struct TextureInfo {
    TInt width;
    TInt height;
    bool hasTransparentTexels;
    bool alphaMixing;

    TextureInfo()
        : width(0), height(0), hasTransparentTexels(false), alphaMixing(false)
    {
    }
};

bool readPrivateAsset(RFs& fileServer,
                      const TDesC& drive,
                      const TDesC& filename,
                      TUint8* buffer,
                      TInt capacity,
                      TInt& length)
{
    TFileName path;
    path.Copy(drive);
#ifdef QUANTUM_LEVEL41_NO_DSA
    path.Append(_L("\\private\\e000a016\\level41\\"));
#else
    path.Append(_L("\\private\\e000a012\\level41\\"));
#endif
    path.Append(filename);

    RFile file;
    TInt result = file.Open(fileServer, path, EFileRead | EFileShareReadersOnly);
    if (result != KErrNone) return false;

    TInt fileSize = 0;
    result = file.Size(fileSize);
    if (result != KErrNone || fileSize < 0 || fileSize > capacity) {
        file.Close();
        return false;
    }

    TPtr8 destination(buffer, capacity);
    result = file.Read(destination, fileSize);
    file.Close();
    if (result != KErrNone || destination.Length() != fileSize) return false;
    length = fileSize;
    return true;
}

TInt littleEndianU16(const TUint8* bytes)
{
    return static_cast<TInt>(bytes[0]) |
           (static_cast<TInt>(bytes[1]) << 8);
}

bool loadTexture(RFs& fileServer,
                 const TDesC& drive,
                 const TDesC& filename,
                 QInt32* pixels,
                 TInt pixelCapacity,
                 TextureInfo& info)
{
    TInt length = 0;
    if (!readPrivateAsset(fileServer, drive, filename, gTextureFile,
                          KMaximumTextureFileBytes, length) || length < 8 ||
        gTextureFile[0] != 'Q' || gTextureFile[1] != 'T' ||
        gTextureFile[2] != 'X' || gTextureFile[3] != '1') {
        return false;
    }

    info.width = littleEndianU16(gTextureFile + 4);
    info.height = littleEndianU16(gTextureFile + 6);
    if (info.width <= 0 || info.height <= 0 ||
        info.width > KMaximumTextureWidth ||
        info.height > KMaximumTextureHeight) {
        return false;
    }

    const TInt pixelCount = info.width * info.height;
    const TInt expectedLength = 8 + pixelCount * 4;
    if (pixelCount > pixelCapacity || length != expectedLength) return false;

    TInt pixelIndex;
    for (pixelIndex = 0; pixelIndex < pixelCount; ++pixelIndex) {
        const TUint8* rgba = gTextureFile + 8 + pixelIndex * 4;
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

void setupWorldMaterials(const TextureInfo& forest,
                         const TextureInfo& map,
                         const TextureInfo& river)
{
    gWorldMipViews[0] = SoftwareTextureView(
        gForestPixels, forest.width * forest.height,
        forest.width, forest.height, forest.hasTransparentTexels);
    gWorldMipViews[1] = SoftwareTextureView(
        gMapPixels, map.width * map.height,
        map.width, map.height, map.hasTransparentTexels);
    gWorldMipViews[2] = gWorldMipViews[1];
    gWorldMipViews[3] = gWorldMipViews[1];
    gWorldMipViews[4] = SoftwareTextureView(
        gRiverPixels, river.width * river.height,
        river.width, river.height, river.hasTransparentTexels);

    const QInt32 biases[5] = { -20000, 0, -3500, -7000, 0 };
    const bool alphaMixing[5] = {
        forest.alphaMixing, map.alphaMixing, map.alphaMixing,
        map.alphaMixing, river.alphaMixing
    };
    TInt index;
    for (index = 0; index < 5; ++index) {
        gWorldMaterials[index].mipLevels = &gWorldMipViews[index];
        gWorldMaterials[index].mipLevelCount = 1;
        gWorldMaterials[index].hasDrawModeOverride = false;
        gWorldMaterials[index].drawMode = 9;
        gWorldMaterials[index].perspectiveCorrection = true;
        gWorldMaterials[index].textureAlphaMixing = alphaMixing[index];
        gWorldMaterials[index].depthBias = biases[index];
    }

    gSkyMipView = SoftwareTextureView(
        gSkyPixels, KMaximumTexturePixels,
        KMaximumTextureWidth, KMaximumTextureHeight, false);
    gSkyMaterial[0].mipLevels = &gSkyMipView;
    gSkyMaterial[0].mipLevelCount = 1;
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

bool loadSkyboxModel(RFs& fileServer,
                     const TDesC& drive,
                     SoftwareMesh3DRequirements& requirements)
{
    gRenderStage = ERenderStageSkyboxMesh;
    TInt length = 0;
    if (!readPrivateAsset(fileServer, drive, _L("skybox8.3d"),
                          gSkyboxFile, KMaximumSkyboxFileBytes, length) ||
        !SoftwareMesh3DParser::measure(
            gSkyboxFile, length, requirements) ||
        requirements.meshCount != 1 ||
        requirements.vertexCount != KSkyVertexCapacity ||
        requirements.polygonCount != KSkyPolygonCapacity ||
        requirements.materialCount != 1) {
        return false;
    }

    SoftwareMesh3DRequirements parsed;
    if (!SoftwareMesh3DParser::parse(
            gSkyboxFile, length, 7.0f, 7.0f, 7.0f,
            gSkyVertices, KSkyVertexCapacity,
            gSkyPolygons, KSkyPolygonCapacity,
            gSkyParts, 1, parsed)) {
        return false;
    }

    gSkyView = SoftwareMeshView(
        gSkyVertices, KSkyVertexCapacity,
        gSkyPolygons, KSkyPolygonCapacity,
        gSkyMaterial, 1);
    return true;
}

bool renderLevel41(RFs& fileServer, const TDesC& drive)
{
    TextureInfo forest;
    TextureInfo map;
    TextureInfo river;
    TextureInfo sky;
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
    if (!loadTexture(fileServer, drive, _L("river.qtx"),
                     gRiverPixels, KRiverTexturePixels, river)) {
        return false;
    }
    gRenderStage = ERenderStageSkyTexture;
    if (!loadTexture(fileServer, drive, _L("sky.qtx"),
                     gSkyPixels, KSkyTexturePixels, sky)) {
        return false;
    }
    gRenderStage = ERenderStageTextureDimensions;
    if (forest.width != 256 || forest.height != 128 ||
        map.width != 256 || map.height != 256 ||
        river.width != 32 || river.height != 32 ||
        sky.width != 256 || sky.height != 256) {
        return false;
    }
    setupWorldMaterials(forest, map, river);
    gSkyMipView = SoftwareTextureView(
        gSkyPixels, sky.width * sky.height,
        sky.width, sky.height, sky.hasTransparentTexels);
    gSkyMaterial[0].textureAlphaMixing = sky.alphaMixing;

    SoftwareMesh3DRequirements worldRequirements;
    SoftwareMesh3DRequirements skyRequirements;
    if (!loadWorldModel(fileServer, drive, worldRequirements) ||
        !loadSkyboxModel(fileServer, drive, skyRequirements)) {
        return false;
    }

    if (gFramebuffer == 0 || gRenderWidth <= 0 || gRenderHeight <= 0 ||
        gRenderPixelCount != gRenderWidth * gRenderHeight) {
        return false;
    }

    TInt pixelIndex;
    for (pixelIndex = 0; pixelIndex < gRenderPixelCount; ++pixelIndex) {
        gFramebuffer[pixelIndex] = KBackground;
    }

    Matrix cameraToView;
    cameraToView.setRotY(0);
    cameraToView.setPosition(0, -KEyeHeight, 0);
    Matrix skyboxToView;
    skyboxToView.setRotY(0);

    SoftwareMeshView* worldView = &gWorldView;
    SoftwareMeshView* skyView = &gSkyView;
    SoftwareMeshInstance instances[2];
    instances[0] = SoftwareMeshInstance(skyView, &skyboxToView);
    instances[1] = SoftwareMeshInstance(worldView, &cameraToView);

    SoftwareMeshRenderWorkspace workspace(
        gCameraVertices, KTotalVertexCapacity,
        gRenderQueue, KTotalPolygonCapacity);
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
    options.cameraWorldY = KEyeHeight;
    options.quality.setStandardDrawMode(9);
    options.quality.setPerspectiveQuality(2);
    options.quality.setTextureQuality(2);
    options.quality.setFogQuality(2);
    options.quality.setMipMapping(true);
    options.triangleSettings.fogDistance = 1;
    options.triangleSettings.fogColor = 0x005500ff;
    options.farDistance = KFarDistance;
    options.clipZ = -KClipDistance;
    options.mipQuality = 24;

    const SoftwareRenderSurfaceView surface(
        gFramebuffer, gRenderPixelCount,
        gRenderWidth, gRenderHeight, gRenderWidth);
    SoftwareMeshRenderStats stats;
    gRenderStage = ERenderStageRasterizer;
    if (!SoftwareMeshRenderer::renderScene(
            surface, instances, 2, options, workspace, stats)) {
        return false;
    }
    if (stats.polygonsDrawn <= 0) {
        gRenderStage = ERenderStageNoPolygons;
        return false;
    }
    gPolygonsDrawn = stats.polygonsDrawn;
    gTrianglesDrawn = stats.trianglesDrawn;
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
        TBuf<48> line;
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
        gc.DrawText(_L("Check package assets and retry"), TPoint(8, 100));
        gc.DrawText(_L("Press any key to exit"), TPoint(8, size.iHeight - 8));
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

    // DSA drawing must not make Window Server calls while direct access is
    // active; keep all font and status text in the WindowServer fallback.
    if (session != 0) {
        CFont* font = 0;
        if (screen.GetNearestFontInPixels(font, TFontSpec(_L("Series 60 Sans"), 10)) == KErrNone) {
            gc.UseFont(font);
            gc.SetPenStyle(CGraphicsContext::ESolidPen);
            gc.SetPenColor(TRgb(255, 255, 255));
            gc.DrawText(_L("LEVEL 41 / DMODE 9"), TPoint(4, 14));
            TBuf<48> status;
            status.Format(_L("Polygons %d  Triangles %d"),
                          gPolygonsDrawn, gTrianglesDrawn);
            gc.DrawText(status, TPoint(4, size.iHeight - 22));
            gc.DrawText(_L("Press any key to exit"),
                        TPoint(4, size.iHeight - 6));
            gc.DiscardFont();
            screen.ReleaseFont(font);
        }
    }
}

#ifndef QUANTUM_LEVEL41_NO_DSA
class CDsaStarter;

class TLevel41DsaObserver : public MDirectScreenAccess
{
public:
    TLevel41DsaObserver(RWsSession& session,
                        CWsScreenDevice& screen,
                        const TSize& size)
        : iSession(session), iScreen(screen), iSize(size), iAccess(0),
          iStarter(0), iPixmap(0), iPixmapDevice(0), iPixmapGc(0)
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

    void PreparePixmapL()
    {
        if (iPixmap != 0 && iPixmapDevice != 0 && iPixmapGc != 0) return;
        CleanupPixmap();

        iPixmap = new(ELeave) CWsBitmap(iSession);
        TDisplayMode mode = iScreen.DisplayMode();
#ifdef EKA2
        if (mode == EColor16MA) mode = EColor16MU;
#endif
        User::LeaveIfError(iPixmap->Create(iSize, mode));
        iPixmapDevice = CFbsBitmapDevice::NewL(iPixmap);
        User::LeaveIfError(iPixmapDevice->CreateContext(iPixmapGc));
    }

    void CleanupPixmap()
    {
        delete iPixmapGc;
        iPixmapGc = 0;
        delete iPixmapDevice;
        iPixmapDevice = 0;
        delete iPixmap;
        iPixmap = 0;
    }

    bool DrawFrame()
    {
        if (iAccess == 0 || iAccess->Gc() == 0 ||
            iPixmap == 0 || iPixmapGc == 0) return false;

        // Render the software frame into an FBS bitmap first, then transfer it
        // to the display with one DSA blit instead of thousands of screen GDI
        // drawing requests.
        drawOutput(iScreen, *iPixmapGc, 0, iSize);
        iAccess->Gc()->BitBlt(TPoint(0, 0), iPixmap);

        // SetAutoUpdate(ETrue) is configured after DSA starts. The explicit
        // CFbsScreenDevice::Update() experiment was removed in v1.0.6; the
        // physical E65 launch failure is still unresolved.
        return true;
    }

    void AbortNow(RDirectScreenAccess::TTerminationReasons reason)
    {
        // The frame is static; stop drawing immediately and make no WSERV call.
        (void)reason;
    }

    void Restart(RDirectScreenAccess::TTerminationReasons reason);

private:
    RWsSession& iSession;
    CWsScreenDevice& iScreen;
    TSize iSize;
    CDirectScreenAccess* iAccess;
    CDsaStarter* iStarter;
    CWsBitmap* iPixmap;
    CFbsBitmapDevice* iPixmapDevice;
    CFbsBitGc* iPixmapGc;
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
          iObserver(observer), iAccess(0), iResult(KErrNotReady),
          iStartRequested(false)
    {
        CActiveScheduler::Add(this);
        iObserver.SetStarter(this);
    }

    ~CDsaStarter()
    {
        Stop();
        iObserver.SetStarter(0);
    }

    void Start()
    {
        ScheduleStart();
    }

    void Stop()
    {
        Cancel();
        StopAccess();
    }

    TInt Result() const
    {
        return iResult;
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
        if (!iObserver.DrawFrame()) {
            iResult = KErrNotSupported;
            StopAccess();
            CActiveScheduler::Stop();
            return;
        }
        iResult = KErrNone;
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
    TInt iResult;
    bool iStartRequested;
};

void TLevel41DsaObserver::Restart(
    RDirectScreenAccess::TTerminationReasons reason)
{
    (void)reason;
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
        if (event.Handle() == iGroupClientHandle && event.Type() == EEventKey) {
            CActiveScheduler::Stop();
            return;
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
    const TInt result = starter.Result();
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

    // Keep the earlier low-memory path available on devices that cannot hold
    // a native-resolution framebuffer.
    gRenderWidth = KBaseRenderWidth;
    gRenderHeight = KBaseRenderHeight;
    gRenderPixelCount = KBaseRenderWidth * KBaseRenderHeight;
    gFramebuffer = static_cast<QInt32*>(User::Alloc(
        gRenderPixelCount * static_cast<TInt>(sizeof(QInt32))));
    return gFramebuffer != 0;
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
            !renderLevel41(fileServer, drive)) {
            gRenderStatus = KErrCorrupt;
        }

#ifndef QUANTUM_LEVEL41_NO_DSA
        if (gRenderStatus == KErrNone) {
            bool usedDirectScreenAccess = false;
            (void)runDirectScreenAccess(
                session, screen, window, redraws, size,
                usedDirectScreenAccess);
            if (usedDirectScreenAccess) {
                window.Close();
                group.Close();
                session.Flush();
                return KErrNone;
            }
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
            if (event.Handle() == KWindowGroupClientHandle &&
                event.Type() == EEventKey) {
                running = false;
            } else if (running) {
                session.EventReady(&events);
            }
        }
        if (running && redraws != KRequestPending) {
            result = redraws.Int();
            if (result != KErrNone) break;
            TWsRedrawEvent redraw;
            session.GetRedraw(redraw);
            if (redraw.Handle() == KProbeWindowHandle) {
                window.BeginRedraw(redraw.Rect());
                gc.Activate(window);
                if (gRenderStatus == KErrNone) {
                    drawOutput(screen, gc, &session, size);
                } else {
                    drawFailure(screen, gc, size, gRenderStatus);
                }
                gc.Deactivate();
                window.EndRedraw();
            }
            session.RedrawReady(&redraws);
        }
        session.Flush();
    }

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
    if (gTextureStorage == 0) {
        gRenderStatus = KErrNoMemory;
    } else {
        gForestPixels = gTextureStorage;
        gMapPixels = gForestPixels + KForestTexturePixels;
        gRiverPixels = gMapPixels + KMapTexturePixels;
        gSkyPixels = gRiverPixels + KRiverTexturePixels;
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

    if (fileServerConnected) closeFileServerSession(fileServer);
    if (gFramebuffer != 0) User::Free(gFramebuffer);
    if (gTextureStorage != 0) User::Free(gTextureStorage);
    CActiveScheduler::Install(0);
    delete scheduler;
    delete cleanup;
    return result;
}
