#include "quantum_view.h"

#include <e32keys.h>
#include <eikenv.h>
#include <gdi.h>

using code::Math::QInt32;
using code::Math::QUint32;
using code::Rendering::SoftwareMeshRenderStats;
using code::Rendering::SoftwareMeshView;
using code::Rendering::SoftwareRenderSurfaceView;
using code::Rendering::SoftwareMeshRenderer;
using code::Rendering::SoftwareProjectionParameters;
using code::Rendering::ESoftwareMeshProjectionDirectX7;

CQuantumView::CQuantumView()
    : iRotationDegrees(0), iRenderSucceeded(false)
{
}

CQuantumView* CQuantumView::NewL(const TRect& rect)
{
    CQuantumView* self = new (ELeave) CQuantumView;
    CleanupStack::PushL(self);
    self->ConstructL(rect);
    CleanupStack::Pop(self);
    return self;
}

void CQuantumView::ConstructL(const TRect& rect)
{
    CreateWindowL();
    SetupScene();
    SetRect(rect);
    RenderFrame();
    ActivateL();
    SetFocus(ETrue);
}

void CQuantumView::SetupScene()
{
    TInt y;
    for (y = 0; y < KTextureHeight; ++y) {
        TInt x;
        for (x = 0; x < KTextureWidth; ++x) {
            const bool lightCell = (((x >> 2) ^ (y >> 2)) & 1) != 0;
            iTexturePixels[y * KTextureWidth + x] = lightCell
                ? 0x00f4c65e : 0x001e7cda;
        }
    }
    iTextureView = code::Rendering::SoftwareTextureView(
        iTexturePixels, KTextureWidth * KTextureHeight,
        KTextureWidth, KTextureHeight);

    /* A small, preloaded triangle keeps this first app integration asset-free. */
    iMeshVertices[0].set(-96, 96, -300);
    iMeshVertices[1].set(96, 96, -300);
    iMeshVertices[2].set(0, -96, -300);

    iMeshPolygon.vertexCount = 3;
    iMeshPolygon.vertexIndices[0] = 0;
    iMeshPolygon.vertexIndices[1] = 1;
    iMeshPolygon.vertexIndices[2] = 2;
    iMeshPolygon.textureU[0] = 0;
    iMeshPolygon.textureV[0] = 0;
    iMeshPolygon.textureU[1] = 224;
    iMeshPolygon.textureV[1] = 0;
    iMeshPolygon.textureU[2] = 112;
    iMeshPolygon.textureV[2] = 224;
    iMeshPolygon.textureIndex = 0;

    iMaterial.mipLevels = &iTextureView;
    iMaterial.mipLevelCount = 1;
    iMaterial.hasDrawModeOverride = true;
    iMaterial.drawMode = 0;
    iMaterial.perspectiveCorrection = true;
    iMaterial.textureAlphaMixing = false;

    iRenderOptions.projection = SoftwareProjectionParameters(
        KRenderWidth / 2, KRenderHeight / 2, 100, 100);
    iRenderOptions.projectionPath = ESoftwareMeshProjectionDirectX7;
    iRenderOptions.quality.setStandardDrawMode(0);
    iRenderOptions.quality.setPerspectiveQuality(4);
    iRenderOptions.quality.setFogQuality(0);
    iRenderOptions.quality.setMipMapping(false);
    iRenderOptions.triangleSettings.fogDistance = 1;
    iRenderOptions.mipQuality = 24;
}

void CQuantumView::RenderFrame()
{
    TInt i;
    for (i = 0; i < KRenderWidth * KRenderHeight; ++i) {
        iFrameBuffer[i] = KBackgroundPixel;
    }

    iTextureView = code::Rendering::SoftwareTextureView(
        iTexturePixels, KTextureWidth * KTextureHeight,
        KTextureWidth, KTextureHeight);
    iMaterial.mipLevels = &iTextureView;

    const SoftwareMeshView mesh(
        iMeshVertices, 3, &iMeshPolygon, 1, &iMaterial, 1);
    const SoftwareRenderSurfaceView surface(
        iFrameBuffer, KRenderWidth * KRenderHeight,
        KRenderWidth, KRenderHeight, KRenderWidth);
    iWorkspace = code::Rendering::SoftwareMeshRenderWorkspace(
        iCameraVertices, 3, iRenderQueue, 1);
    iModelToCamera.setRotY(iRotationDegrees);
    iRenderOptions.modelToCamera = &iModelToCamera;

    SoftwareMeshRenderStats stats;
    iRenderSucceeded = SoftwareMeshRenderer::render(
        surface, mesh, iRenderOptions, iWorkspace, stats) &&
        stats.polygonsDrawn == 1 && stats.trianglesDrawn == 1;
}

void CQuantumView::Draw(const TRect& rect) const
{
    CWindowGc& gc = SystemGc();
    gc.SetBrushStyle(CGraphicsContext::ESolidBrush);
    gc.SetPenStyle(CGraphicsContext::ENullPen);
    gc.SetBrushColor(TRgb(12, 18, 32));
    gc.Clear(rect);

    const TRect bounds = Rect();
    const TInt width = bounds.Width();
    const TInt height = bounds.Height();
    const TInt contentTop = bounds.iTl.iY + 28;
    const TInt contentHeight = height - 54;
    const TInt scaleX = width > 16 ? (width - 16) / KRenderWidth : 1;
    const TInt scaleY = contentHeight > 0
        ? contentHeight / KRenderHeight : 1;
    TInt scale = scaleX < scaleY ? scaleX : scaleY;
    if (scale < 1) scale = 1;

    const TInt imageWidth = KRenderWidth * scale;
    const TInt imageHeight = KRenderHeight * scale;
    const TInt left = bounds.iTl.iX + (width - imageWidth) / 2;
    const TInt top = contentTop + (contentHeight - imageHeight) / 2;

    if (iRenderSucceeded) {
        TInt y;
        for (y = 0; y < KRenderHeight; ++y) {
            TInt x;
            for (x = 0; x < KRenderWidth; ++x) {
                const QInt32 pixel = iFrameBuffer[y * KRenderWidth + x];
                if (pixel == KBackgroundPixel) continue;
                const QUint32 bits = static_cast<QUint32>(pixel);
                gc.SetBrushColor(TRgb(
                    static_cast<TInt>((bits >> 16U) & 0xffU),
                    static_cast<TInt>((bits >> 8U) & 0xffU),
                    static_cast<TInt>(bits & 0xffU)));
                gc.DrawRect(TRect(left + x * scale, top + y * scale,
                                  left + (x + 1) * scale,
                                  top + (y + 1) * scale));
            }
        }
    }

    gc.SetPenStyle(CGraphicsContext::ESolidPen);
    gc.SetPenColor(TRgb(239, 243, 250));
    const CFont* font = CEikonEnv::Static()->NormalFont();
    gc.UseFont(font);
    gc.DrawText(_L("Quantum native renderer"),
                TPoint(bounds.iTl.iX + 8, bounds.iTl.iY + 20));
    if (iRenderSucceeded) {
        TBuf<40> status;
        status.Format(_L("Left/Right: rotate (%d)"), iRotationDegrees);
        gc.DrawText(status, TPoint(bounds.iTl.iX + 8, bounds.iBr.iY - 8));
    } else {
        gc.DrawText(_L("Native render setup failed"),
                    TPoint(bounds.iTl.iX + 8, bounds.iTl.iY + 44));
    }
    gc.DiscardFont();
}

TKeyResponse CQuantumView::OfferKeyEventL(const TKeyEvent& keyEvent,
                                          TEventCode type)
{
    if (type != EEventKey) return EKeyWasNotConsumed;

    switch (keyEvent.iCode) {
    case EKeyLeftArrow:
        if (iRotationDegrees > KMinimumRotationDegrees) {
            iRotationDegrees -= KRotationStepDegrees;
            if (iRotationDegrees < KMinimumRotationDegrees) {
                iRotationDegrees = KMinimumRotationDegrees;
            }
        }
        break;
    case EKeyRightArrow:
        if (iRotationDegrees < KMaximumRotationDegrees) {
            iRotationDegrees += KRotationStepDegrees;
            if (iRotationDegrees > KMaximumRotationDegrees) {
                iRotationDegrees = KMaximumRotationDegrees;
            }
        }
        break;
    default:
        return EKeyWasNotConsumed;
    }

    RenderFrame();
    DrawDeferred();
    return EKeyWasConsumed;
}

void CQuantumView::SizeChanged()
{
    RenderFrame();
    DrawDeferred();
}
