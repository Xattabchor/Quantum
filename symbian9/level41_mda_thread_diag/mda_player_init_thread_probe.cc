#include <e32base.h>
#include <e32std.h>
#include <gdi.h>
#include <mdaaudiosampleplayer.h>
#include <w32std.h>

namespace {

const TUint KWindowGroupClientHandle = 1;
const TUint KProbeWindowHandle = 2;
const TInt KWorkerStackSize = 0x8000;
const TInt KWorkerHeapMin = 0x10000;
const TInt KWorkerHeapMax = 0x400000;

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

struct TMdaThreadResult
{
    TMdaThreadResult()
        : iCreateError(KErrNotReady),
          iExitType(EExitPending),
          iExitReason(KErrNotReady),
          iNewLStarted(EFalse),
          iNewLError(KErrNotReady)
    {
        iExitCategory.Zero();
    }

    TInt iCreateError;
    TExitType iExitType;
    TInt iExitReason;
    TExitCategoryName iExitCategory;
    TBool iNewLStarted;
    TInt iNewLError;
};

class CMdaNewLProbeActive : public CActive
{
public:
    explicit CMdaNewLProbeActive(TMdaThreadResult& aResult)
        : CActive(CActive::EPriorityStandard), iResult(aResult)
    {
        CActiveScheduler::Add(this);
    }

    void Start()
    {
        iStatus = KRequestPending;
        TRequestStatus* status = &iStatus;
        User::RequestComplete(status, KErrNone);
        SetActive();
    }

private:
    void DoCancel() override
    {
    }

    void RunL() override
    {
        TMdaInitObserver observer;
        iResult.iNewLStarted = ETrue;

        // CActiveScheduler dispatches RunL with leave handling; RunError below
        // records a NewL leave without importing TTrap::Trap/UnTrap directly.
        // This is the only MDA operation: no file open and no Play.
        CMdaAudioPlayerUtility* player =
            CMdaAudioPlayerUtility::NewL(observer);
        delete player;
        iResult.iNewLError = KErrNone;
        CActiveScheduler::Stop();
    }

    TInt RunError(TInt aError) override
    {
        iResult.iNewLError = aError;
        CActiveScheduler::Stop();
        return KErrNone;
    }

private:
    TMdaThreadResult& iResult;
};

TInt mdaInitWorker(TAny* aData)
{
    TMdaThreadResult& result = *static_cast<TMdaThreadResult*>(aData);
    CTrapCleanup* cleanup = CTrapCleanup::New();
    if (cleanup == 0) return KErrNoMemory;

    // A panic in this probe thread must not kill the UI/process thread.
    const TInt criticalError = User::SetCritical(User::ENotCritical);
    if (criticalError != KErrNone) {
        delete cleanup;
        return criticalError;
    }

    CActiveScheduler* scheduler = new CActiveScheduler;
    if (scheduler == 0) {
        delete cleanup;
        return KErrNoMemory;
    }
    CActiveScheduler::Install(scheduler);

    // RunL supplies the scheduler's native leave context; RunError records a
    // leave code, while any explicit panic is still captured by RThread::Logon.
    CMdaNewLProbeActive probe(result);
    probe.Start();
    CActiveScheduler::Start();

    CActiveScheduler::Install(0);
    delete scheduler;
    delete cleanup;
    return KErrNone;
}

void runMdaInitWorker(TMdaThreadResult& aResult)
{
    RThread thread;
    _LIT(KWorkerName, "MdaPlayerNewL");
    aResult.iCreateError = thread.Create(
        KWorkerName,
        mdaInitWorker,
        KWorkerStackSize,
        KWorkerHeapMin,
        KWorkerHeapMax,
        &aResult);
    if (aResult.iCreateError != KErrNone) return;

    TRequestStatus threadStatus;
    thread.Logon(threadStatus);
    thread.Resume();
    User::WaitForRequest(threadStatus);

    aResult.iExitType = thread.ExitType();
    aResult.iExitReason = thread.ExitReason();
    aResult.iExitCategory = thread.ExitCategory();
    thread.Close();
}

void drawProbeScreen(CWsScreenDevice& aScreen,
                     CWindowGc& aGc,
                     const TSize& aSize,
                     TBool aComplete,
                     const TMdaThreadResult& aResult)
{
    aGc.SetBrushStyle(CGraphicsContext::ESolidBrush);
    aGc.SetPenStyle(CGraphicsContext::ENullPen);
    aGc.SetBrushColor(TRgb(12, 20, 32));
    aGc.Clear(TRect(TPoint(0, 0), aSize));

    CFont* font = 0;
    if (aScreen.GetNearestFontInPixels(
            font, TFontSpec(_L("Series 60 Sans"), 13)) != KErrNone) {
        return;
    }

    aGc.UseFont(font);
    aGc.SetPenStyle(CGraphicsContext::ESolidPen);
    aGc.SetPenColor(TRgb(240, 244, 250));
    aGc.DrawText(_L("MDA NEWL THREAD DIAGNOSTIC"), TPoint(8, 34));

    if (!aComplete) {
        aGc.DrawText(_L("NewL: starting in worker"), TPoint(8, 62));
    } else if (aResult.iCreateError != KErrNone) {
        TBuf<48> line;
        line.Format(_L("RThread::Create error: %d"), aResult.iCreateError);
        aGc.DrawText(line, TPoint(8, 62));
    } else if (aResult.iExitType == EExitPanic) {
        aGc.DrawText(_L("Worker panic captured"), TPoint(8, 62));
        aGc.DrawText(aResult.iExitCategory, TPoint(8, 88));
        TBuf<48> line;
        line.Format(_L("Panic reason: %d"), aResult.iExitReason);
        aGc.DrawText(line, TPoint(8, 114));
    } else if (!aResult.iNewLStarted &&
               aResult.iExitType == EExitKill &&
               aResult.iExitReason != KErrNone) {
        TBuf<48> line;
        line.Format(_L("Worker setup error: %d"), aResult.iExitReason);
        aGc.DrawText(line, TPoint(8, 62));
    } else if (aResult.iNewLStarted &&
               aResult.iNewLError != KErrNone) {
        TBuf<48> line;
        line.Format(_L("NewL leave code: %d"), aResult.iNewLError);
        aGc.DrawText(line, TPoint(8, 62));
        aGc.DrawText(_L("Leave caught by RunError"), TPoint(8, 88));
    } else if (aResult.iNewLStarted &&
               aResult.iNewLError == KErrNone &&
               aResult.iExitType == EExitKill &&
               aResult.iExitReason == KErrNone) {
        aGc.DrawText(_L("NewL returned; player deleted"), TPoint(8, 62));
    } else {
        TBuf<48> line;
        line.Format(_L("Worker exit type: %d"), (TInt)aResult.iExitType);
        aGc.DrawText(line, TPoint(8, 62));
        line.Format(_L("Worker exit reason: %d"), aResult.iExitReason);
        aGc.DrawText(line, TPoint(8, 88));
        if (aResult.iExitCategory.Length() > 0) {
            aGc.DrawText(aResult.iExitCategory, TPoint(8, 114));
        }
    }

    aGc.DrawText(_L("NewL via CActive::RunL"), TPoint(8, 140));
    aGc.DrawText(_L("No file opened; no Play call"), TPoint(8, 158));
    aGc.DrawText(_L("MIDI / other audio paths: OFF"), TPoint(8, 184));
    aGc.DrawText(_L("Press any key to exit"),
                 TPoint(8, aSize.iHeight - 12));
    aGc.DiscardFont();
    aScreen.ReleaseFont(font);
}

TInt drawProbeWindow(RWsSession& aSession,
                     RWindow& aWindow,
                     CWsScreenDevice& aScreen,
                     CWindowGc& aGc,
                     const TSize& aSize,
                     TBool aComplete,
                     const TMdaThreadResult& aResult)
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
            drawProbeScreen(aScreen, aGc, aSize, aComplete, aResult);
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
               CWindowGc& aGc)
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

    // Keep the UI thread alive if NewL raises an explicit panic in the worker.
    // Leaves are reported by the active object's RunError. The only MDA call
    // in that worker is CMdaAudioPlayerUtility::NewL.
    TMdaThreadResult threadResult;
    result = drawProbeWindow(
        aSession, window, aScreen, aGc, size, EFalse, threadResult);
    if (result == KErrNone) {
        runMdaInitWorker(threadResult);
        result = drawProbeWindow(
            aSession, window, aScreen, aGc, size, ETrue, threadResult);
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

    RWsSession session;
    TInt result = session.Connect();
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

    CActiveScheduler::Install(0);
    delete scheduler;
    delete cleanup;
    return result;
}
