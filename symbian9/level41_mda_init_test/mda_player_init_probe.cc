#include <e32base.h>
#include <gdi.h>
#include <mdaaudiosampleplayer.h>
#include <w32std.h>

namespace {

const TUint KWindowGroupClientHandle = 1;
const TUint KProbeWindowHandle = 2;

class TMdaInitObserver : public MMdaAudioPlayerCallback
{
public:
    void MapcInitComplete(
        TInt aError,
        const TTimeIntervalMicroSeconds& aDuration) override
    {
        (void)aError;
        (void)aDuration;
    }

    void MapcPlayComplete(TInt aError) override
    {
        (void)aError;
    }
};

TInt createAndReleaseMdaPlayer(MMdaAudioPlayerCallback& aObserver)
{
    CMdaAudioPlayerUtility* player = 0;
    TRAPD(error, player = CMdaAudioPlayerUtility::NewL(aObserver));
    if (error != KErrNone) return error;
    if (player == 0) return KErrNoMemory;

    delete player;
    return KErrNone;
}

void drawProbeScreen(CWsScreenDevice& aScreen,
                     CWindowGc& aGc,
                     const TSize& aSize,
                     bool aMdaComplete,
                     TInt aNewLError)
{
    aGc.SetBrushStyle(CGraphicsContext::ESolidBrush);
    aGc.SetPenStyle(CGraphicsContext::ENullPen);
    aGc.SetBrushColor(TRgb(12, 20, 32));
    aGc.Clear(TRect(TPoint(0, 0), aSize));

    CFont* font = 0;
    if (aScreen.GetNearestFontInPixels(
            font, TFontSpec(_L("Series 60 Sans"), 13)) == KErrNone) {
        aGc.UseFont(font);
        aGc.SetPenStyle(CGraphicsContext::ESolidPen);
        aGc.SetPenColor(TRgb(240, 244, 250));
        aGc.DrawText(_L("MDA PLAYER FACTORY TEST"), TPoint(8, 34));

        if (!aMdaComplete) {
            aGc.DrawText(_L("NewL: starting..."), TPoint(8, 62));
        } else if (aNewLError == KErrNone) {
            aGc.DrawText(_L("NewL: KErrNone; object returned"),
                         TPoint(8, 62));
        } else {
            TBuf<48> resultLine;
            resultLine.Format(_L("NewL error/result: %d"), aNewLError);
            aGc.DrawText(resultLine, TPoint(8, 62));
        }

        aGc.DrawText(_L("Object released after NewL"), TPoint(8, 88));
        aGc.DrawText(_L("No file opened; no Play call"), TPoint(8, 114));
        aGc.DrawText(_L("MIDI / other audio paths: OFF"), TPoint(8, 140));
        aGc.DrawText(_L("Press any key to exit"),
                     TPoint(8, aSize.iHeight - 12));
        aGc.DiscardFont();
        aScreen.ReleaseFont(font);
    }
}

TInt drawProbeWindow(RWsSession& aSession,
                     RWindow& aWindow,
                     CWsScreenDevice& aScreen,
                     CWindowGc& aGc,
                     const TSize& aSize,
                     bool aMdaComplete,
                     TInt aNewLError)
{
    TRequestStatus redrawStatus;
    aSession.RedrawReady(&redrawStatus);
    aWindow.Invalidate();
    aSession.Flush();
    User::WaitForRequest(redrawStatus);

    TInt result = redrawStatus.Int();
    if (result == KErrNone) {
        TWsRedrawEvent redraw;
        aSession.GetRedraw(redraw);
        if (redraw.Handle() == KProbeWindowHandle) {
            aWindow.BeginRedraw(redraw.Rect());
            aGc.Activate(aWindow);
            drawProbeScreen(aScreen, aGc, aSize, aMdaComplete, aNewLError);
            aGc.Deactivate();
            aWindow.EndRedraw();
        }
    }
    aSession.RedrawReadyCancel();
    aSession.Flush();
    return result;
}

TInt waitForKey(RWsSession& aSession, TUint aGroupClientHandle)
{
    TRequestStatus eventStatus;
    aSession.EventReady(&eventStatus);
    for (;;) {
        User::WaitForRequest(eventStatus);
        const TInt result = eventStatus.Int();
        if (result != KErrNone) return result;

        TWsEvent event;
        aSession.GetEvent(event);
        if (event.Handle() == aGroupClientHandle &&
            event.Type() == EEventKey) {
            return KErrNone;
        }
        aSession.EventReady(&eventStatus);
    }
}

TInt runWindow(RWsSession& aSession,
               CWsScreenDevice& aScreen,
               CWindowGc& aGc,
               MMdaAudioPlayerCallback& aObserver)
{
    const TSize size = aScreen.SizeInPixels();
    if (size.iWidth <= 0 || size.iHeight <= 0 ||
        size.iWidth > 8192 || size.iHeight > 8192) {
        return KErrArgument;
    }

    RWindowGroup group(aSession);
    TInt result = group.Construct(KWindowGroupClientHandle, ETrue);
    if (result != KErrNone) return result;

    RWindow window(aSession);
    result = window.Construct(group, KProbeWindowHandle);
    if (result != KErrNone) {
        group.Close();
        return result;
    }

    group.SetOrdinalPosition(0);
    window.SetExtent(TPoint(0, 0), size);
    window.SetVisible(ETrue);
    window.Activate();

    // Show the foreground UI before exercising MDA. This makes a loader-time
    // launch failure distinguishable from a trapped NewL leave on the E65.
    result = drawProbeWindow(aSession, window, aScreen, aGc, size,
                             false, KErrNotReady);
    if (result == KErrNone) {
        // An active scheduler is installed in main, but no scheduler loop is
        // started: this stage opens no media and starts no async playback.
        const TInt mdaResult = createAndReleaseMdaPlayer(aObserver);
        result = drawProbeWindow(aSession, window, aScreen, aGc, size,
                                 true, mdaResult);
    }

    if (result == KErrNone) {
        result = waitForKey(aSession, KWindowGroupClientHandle);
    }

    window.Close();
    group.Close();
    aSession.Flush();
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

    TMdaInitObserver observer;
    RWsSession session;
    TInt result = session.Connect();
    if (result == KErrNone) {
        CWsScreenDevice screen(session);
        result = screen.Construct();
        if (result == KErrNone) {
            CWindowGc gc(&screen);
            result = gc.Construct();
            if (result == KErrNone) {
                result = runWindow(session, screen, gc, observer);
            }
        }
        session.Close();
    }

    CActiveScheduler::Install(0);
    delete scheduler;
    delete cleanup;
    return result;
}
