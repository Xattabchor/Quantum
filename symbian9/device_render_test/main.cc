#include "software_affine_rasterizer.h"
#include "software_perspective_rasterizer.h"

#include <e32base.h>
#include <gdi.h>
#include <w32std.h>

namespace {

const TUint KWindowGroupClientHandle = 1;
const TUint KProbeWindowHandle = 2;

using code::Math::QInt32;
using code::Math::QUint32;
using code::Rendering::AffineTriangleVertex;
using code::Rendering::SoftwareAffineRasterizer;
using code::Rendering::SoftwarePerspectiveRasterizer;
using code::Rendering::SoftwareRenderSurfaceView;
using code::Rendering::SoftwareTextureView;

const TInt KRenderWidth = 64;
const TInt KRenderHeight = 64;
const TInt KTextureSize = 8;
const QInt32 KBackground = 0x00101824;
const QInt32 KPerspectiveStep = 12;

QInt32 gFramebuffer[KRenderWidth * KRenderHeight];
QInt32 gTexture[KTextureSize * KTextureSize];

QInt32 rgb(TInt red, TInt green, TInt blue)
{
    return static_cast<QInt32>(
        ((static_cast<QUint32>(red) & 0xffU) << 16U) |
        ((static_cast<QUint32>(green) & 0xffU) << 8U) |
        (static_cast<QUint32>(blue) & 0xffU));
}

void makeTexture()
{
    static const QInt32 palette[4] = {
        0x00e5a34f, 0x004b9ba5, 0x00d65752, 0x006f80bd
    };
    TInt y;
    for (y = 0; y < KTextureSize; ++y) {
        TInt x;
        for (x = 0; x < KTextureSize; ++x) {
            const TInt tile = ((x >> 1) + 2 * (y >> 1)) & 3;
            QInt32 color = palette[tile];
            if ((x & 3) == 0 || (y & 3) == 0) {
                color = 0x00f0e9d8;
            } else if ((x & 1) == 0 && (y & 1) == 0) {
                color = 0x001d2938;
            }
            gTexture[y * KTextureSize + x] = color;
        }
    }
}

bool drawPanel(const SoftwareRenderSurfaceView& surface,
               const SoftwareTextureView& texture,
               TInt offsetX,
               bool perspective)
{
    const AffineTriangleVertex topLeft(
        offsetX + 4, 10, 0, 0, -1800);
    const AffineTriangleVertex topRight(
        offsetX + 18, 10, 7, 0, -1800);
    const AffineTriangleVertex bottomRight(
        offsetX + 26, 54, 7, 7, -300);
    const AffineTriangleVertex bottomLeft(
        offsetX + 1, 54, 0, 7, -300);

    bool first;
    bool second;
    if (perspective) {
        first = SoftwarePerspectiveRasterizer::drawMode0Copy(
            surface, texture, topLeft, topRight, bottomRight,
            KPerspectiveStep);
        second = SoftwarePerspectiveRasterizer::drawMode0Copy(
            surface, texture, topLeft, bottomRight, bottomLeft,
            KPerspectiveStep);
    } else {
        first = SoftwareAffineRasterizer::drawMode0(
            surface, texture, topLeft, topRight, bottomRight);
        second = SoftwareAffineRasterizer::drawMode0(
            surface, texture, topLeft, bottomRight, bottomLeft);
    }
    return first && second;
}

bool renderFrame()
{
    TInt index;
    for (index = 0; index < KRenderWidth * KRenderHeight; ++index) {
        gFramebuffer[index] = KBackground;
    }
    makeTexture();

    const SoftwareRenderSurfaceView surface(
        gFramebuffer, KRenderWidth * KRenderHeight,
        KRenderWidth, KRenderHeight, KRenderWidth);
    const SoftwareTextureView texture(
        gTexture, KTextureSize * KTextureSize,
        KTextureSize, KTextureSize);

    /* Left panel: affine. Right panel: the same texture with perspective Q12. */
    if (!drawPanel(surface, texture, 0, false) ||
        !drawPanel(surface, texture, 32, true)) {
        return false;
    }

    for (index = 0; index < KRenderHeight; ++index) {
        gFramebuffer[index * KRenderWidth + 31] = 0x00d5dfeb;
    }
    return true;
}

void drawOutput(CWsScreenDevice& screen,
                CWindowGc& gc,
                const TSize& size)
{
    const TInt availableWidth = size.iWidth - 16;
    const TInt availableHeight = size.iHeight - 48;
    TInt scaleX = availableWidth / KRenderWidth;
    TInt scaleY = availableHeight / KRenderHeight;
    TInt scale = scaleX < scaleY ? scaleX : scaleY;
    if (scale < 1) scale = 1;

    const TInt imageWidth = KRenderWidth * scale;
    const TInt imageHeight = KRenderHeight * scale;
    const TInt left = (size.iWidth - imageWidth) / 2;
    const TInt top = (size.iHeight - imageHeight) / 2;

    gc.SetBrushStyle(CGraphicsContext::ESolidBrush);
    gc.SetPenStyle(CGraphicsContext::ENullPen);
    gc.SetBrushColor(TRgb(8, 12, 20));
    gc.Clear(TRect(TPoint(0, 0), size));

    CFont* font = 0;
    const TFontSpec fontSpec(_L("Series 60 Sans"), 11);
    if (screen.GetNearestFontInPixels(font, fontSpec) == KErrNone) {
        gc.UseFont(font);
        gc.SetPenStyle(CGraphicsContext::ESolidPen);
        gc.SetPenColor(TRgb(235, 240, 248));
        gc.DrawText(_L("AFFINE"), TPoint(left + 2 * scale, top - 8));
        gc.DrawText(_L("PERSPECTIVE"), TPoint(left + 34 * scale, top - 8));
        gc.DiscardFont();
        screen.ReleaseFont(font);
    }

    gc.SetPenStyle(CGraphicsContext::ENullPen);
    TInt y;
    for (y = 0; y < KRenderHeight; ++y) {
        TInt x;
        for (x = 0; x < KRenderWidth; ++x) {
            const QInt32 pixel = gFramebuffer[y * KRenderWidth + x];
            if (pixel == KBackground) {
                continue;
            }
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

    gc.SetPenStyle(CGraphicsContext::ESolidPen);
    gc.SetPenColor(TRgb(235, 240, 248));
    if (screen.GetNearestFontInPixels(font, fontSpec) == KErrNone) {
        gc.UseFont(font);
        gc.DrawText(_L("Press any key to exit"),
                    TPoint(8, size.iHeight - 8));
        gc.DiscardFont();
        screen.ReleaseFont(font);
    }
}

TInt runWindow(RWsSession& session,
               CWsScreenDevice& screen,
               CWindowGc& gc)
{
    const TSize size = screen.SizeInPixels();
    if (size.iWidth < 120 || size.iHeight < 120 ||
        size.iWidth > 8192 || size.iHeight > 8192) {
        return KErrNotSupported;
    }

    RWindowGroup group(session);
    TInt result = group.Construct(KWindowGroupClientHandle, ETrue);
    if (result != KErrNone) {
        return result;
    }

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
    TRequestStatus redraws;
    session.EventReady(&events);
    session.RedrawReady(&redraws);
    window.Invalidate();
    session.Flush();

    bool running = true;
    while (running) {
        User::WaitForRequest(events, redraws);
        if (events != KRequestPending) {
            result = events.Int();
            if (result != KErrNone) {
                break;
            }
            TWsEvent event;
            session.GetEvent(event);
            // Key events carry the focused group client handle, not window 2.
            if (event.Handle() == KWindowGroupClientHandle &&
                event.Type() == EEventKey) {
                running = false;
            } else if (running) {
                session.EventReady(&events);
            }
        }
        if (running && redraws != KRequestPending) {
            result = redraws.Int();
            if (result != KErrNone) {
                break;
            }
            TWsRedrawEvent redraw;
            session.GetRedraw(redraw);
            if (redraw.Handle() == KProbeWindowHandle) {
                window.BeginRedraw(redraw.Rect());
                gc.Activate(window);
                drawOutput(screen, gc, size);
                gc.Deactivate();
                window.EndRedraw();
            }
            session.RedrawReady(&redraws);
        }
        session.Flush();
    }

    session.EventReadyCancel();
    session.RedrawReadyCancel();
    if (events == KRequestPending) {
        User::WaitForRequest(events);
    }
    if (redraws == KRequestPending) {
        User::WaitForRequest(redraws);
    }
    window.Close();
    group.Close();
    session.Flush();
    return result;
}

} // namespace

int main()
{
    if (!renderFrame()) {
        return KErrGeneral;
    }

    CTrapCleanup* cleanup = CTrapCleanup::New();
    if (cleanup == 0) {
        return KErrNoMemory;
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
                result = runWindow(session, screen, gc);
            }
        }
        session.Close();
    }

    delete cleanup;
    return result;
}
