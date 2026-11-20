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

class CMdaNewLProbeActive : public CActive
{
public:
    explicit CMdaNewLProbeActive(MMdaAudioPlayerCallback& aObserver)
        : CActive(CActive::EPriorityStandard),
          iObserver(aObserver),
          iError(KErrNotReady)
    {
        CActiveScheduler::Add(this);
    }

    TInt StartAndWait()
    {
        iStatus = KRequestPending;
        TRequestStatus* status = &iStatus;
        User::RequestComplete(status, KErrNone);
        SetActive();
        CActiveScheduler::Start();
        return iError;
    }

private:
    void DoCancel() override
    {
    }

    void RunL() override
    {
        // Active-scheduler RunL/RunError handles NewL leaves without importing
        // TTrap::Trap/UnTrap directly. No media is opened and no Play is issued.
        CMdaAudioPlayerUtility* player =
            CMdaAudioPlayerUtility::NewL(iObserver);
        delete player;
        iError = KErrNone;
        CActiveScheduler::Stop();
    }

    TInt RunError(TInt aError) override
    {
        iError = aError;
        CActiveScheduler::Stop();
        return KErrNone;
    }

private:
    MMdaAudioPlayerCallback& iObserver;
    TInt iError;
};

TInt createAndReleaseMdaPlayer(MMdaAudioPlayerCallback& aObserver)
{
    CMdaNewLProbeActive probe(aObserver);
    return probe.StartAndWait();
}

void drawProbeScreen(CWsScreenDevice& aScreen,
                     CWindowGc& aGc,
                     const TSize& aSize,
                     bool aMdaComplete,
                     TInt aMdaError)
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
        } else if (aMdaError == KErrNone) {
            aGc.DrawText(_L("NewL returned; player deleted"),
                         TPoint(8, 62));
        } else {
            TBuf<48> line;
            line.Format(_L("NewL leave code: %d"), aMdaError);
            aGc.DrawText(line, TPoint(8, 62));
        }

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
                     TInt aMdaError)
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
            drawProbeScreen(aScreen, aGc, aSize, aMdaComplete, aMdaError);
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
    // launch failure distinguishable from a NewL leave on the E65.
    result = drawProbeWindow(
        aSession, window, aScreen, aGc, size, false, KErrNone);
    if (result == KErrNone) {
        // An active scheduler is installed in main, but no scheduler loop is
        // started: this stage opens no media and starts no async playback.
        const TInt mdaError = createAndReleaseMdaPlayer(aObserver);
        result = drawProbeWindow(
            aSession, window, aScreen, aGc, size, true, mdaError);
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
