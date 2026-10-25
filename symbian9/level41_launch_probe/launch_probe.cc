#include <e32base.h>
#include <gdi.h>
#include <w32std.h>

namespace {

void drawProbeScreen(CWsScreenDevice& screen,
                     CWindowGc& gc,
                     const TSize& size)
{
    gc.SetBrushStyle(CGraphicsContext::ESolidBrush);
    gc.SetPenStyle(CGraphicsContext::ENullPen);
    gc.SetBrushColor(TRgb(0, 72, 120));
    gc.DrawRect(TRect(TPoint(0, 0), size));

    gc.SetPenStyle(CGraphicsContext::ESolidPen);
    gc.SetPenColor(TRgb(255, 255, 255));
    CFont* font = 0;
    if (screen.GetNearestFontInPixels(
            font, TFontSpec(_L("Series 60 Sans"), 14)) == KErrNone) {
        gc.UseFont(font);
        gc.DrawText(_L("RAW E32 LAUNCH OK"), TPoint(12, 36));
        gc.DrawText(_L("WindowServer + GDI only"), TPoint(12, 62));
        gc.DrawText(_L("Press any key to exit"),
                    TPoint(12, size.iHeight - 12));
        gc.DiscardFont();
        screen.ReleaseFont(font);
    }
}

TInt waitForKey(RWsSession& session, TUint windowHandle)
{
    TRequestStatus eventStatus;
    session.EventReady(&eventStatus);
    for (;;) {
        User::WaitForRequest(eventStatus);
        const TInt result = eventStatus.Int();
        if (result != KErrNone) return result;

        TWsEvent event;
        session.GetEvent(event);
        if (event.Handle() == windowHandle && event.Type() == EEventKey) {
            return KErrNone;
        }
        session.EventReady(&eventStatus);
    }
}

} // namespace

int main()
{
    RWsSession session;
    TInt result = session.Connect();
    if (result != KErrNone) return result;

    CWsScreenDevice screen(session);
    result = screen.Construct();
    if (result != KErrNone) {
        session.Close();
        return result;
    }

    const TSize size = screen.SizeInPixels();
    if (size.iWidth <= 0 || size.iHeight <= 0) {
        session.Close();
        return KErrArgument;
    }

    RWindowGroup group(session);
    result = group.Construct(1, ETrue);
    if (result != KErrNone) {
        session.Close();
        return result;
    }

    RWindow window(session);
    result = window.Construct(group, 2);
    if (result != KErrNone) {
        group.Close();
        session.Close();
        return result;
    }

    group.SetOrdinalPosition(0);
    window.SetExtent(TPoint(0, 0), size);
    window.SetVisible(ETrue);
    window.Activate();

    TRequestStatus redrawStatus;
    session.RedrawReady(&redrawStatus);
    window.Invalidate();
    session.Flush();
    User::WaitForRequest(redrawStatus);
    result = redrawStatus.Int();
    if (result == KErrNone) {
        TWsRedrawEvent redraw;
        session.GetRedraw(redraw);
        if (redraw.Handle() == window.Handle()) {
            window.BeginRedraw(redraw.Rect());
            CWindowGc gc(&screen);
            result = gc.Construct();
            if (result == KErrNone) {
                gc.Activate(window);
                drawProbeScreen(screen, gc, size);
                gc.Deactivate();
                window.EndRedraw();
            }
        }
    }
    session.RedrawReadyCancel();
    session.Flush();

    if (result == KErrNone) result = waitForKey(session, window.Handle());
    window.Close();
    group.Close();
    session.Flush();
    session.Close();
    return result;
}
