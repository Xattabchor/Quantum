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
const TInt KOpenTimeoutMicroseconds = 15000000;
const TInt KOpenFileLOrdinal = 42;

typedef void (*TOpenFileLFunction)(CMdaAudioPlayerUtility*, const TDesC&);

enum TMdaOpenFileStage
{
    EStageNone = 0,
    EStageNewL,
    EStageLibraryLoad,
    EStageOrdinalLookup,
    EStageTimerSetup,
    EStageOpenFileL
};

class TMdaOpenFileResult
{
public:
    TMdaOpenFileResult()
        : iCreateError(KErrNotReady),
          iExitType(EExitPending),
          iExitReason(KErrNotReady),
          iNewLStarted(EFalse),
          iNewLReturned(EFalse),
          iOrdinalLookupCompleted(EFalse),
          iOpenFileOrdinalFound(EFalse),
          iOrdinalLookupError(KErrNotReady),
          iOpenFileStarted(EFalse),
          iInitCallback(EFalse),
          iTimedOut(EFalse),
          iRunErrorCaptured(EFalse),
          iRunError(KErrNotReady),
          iRunErrorStage(EStageNone),
          iInitError(KErrNotReady),
          iDurationMilliseconds(0)
    {
        iExitCategory.Zero();
    }

    TInt iCreateError;
    TExitType iExitType;
    TInt iExitReason;
    TExitCategoryName iExitCategory;
    TBool iNewLStarted;
    TBool iNewLReturned;
    TBool iOrdinalLookupCompleted;
    TBool iOpenFileOrdinalFound;
    TInt iOrdinalLookupError;
    TBool iOpenFileStarted;
    TBool iInitCallback;
    TBool iTimedOut;
    TBool iRunErrorCaptured;
    TInt iRunError;
    TMdaOpenFileStage iRunErrorStage;
    TInt iInitError;
    TInt iDurationMilliseconds;
};

class CMdaOpenFileProbeActive : public CActive,
                               public MMdaAudioPlayerCallback
{
public:
    explicit CMdaOpenFileProbeActive(TMdaOpenFileResult& aResult)
        : CActive(CActive::EPriorityStandard),
          iResult(aResult),
          iPlayer(0),
          iOpenFileFunction(0),
          iLibraryLoaded(EFalse),
          iRequestStarted(EFalse),
          iWaitingForCallback(EFalse),
          iTimerCreated(EFalse),
          iCurrentStage(EStageNone)
    {
        CActiveScheduler::Add(this);
    }

    ~CMdaOpenFileProbeActive()
    {
        Cancel();
        if (iTimerCreated) iTimer.Close();
        delete iPlayer;
        if (iLibraryLoaded) iMdaLibrary.Close();
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
        if (iTimerCreated) iTimer.Cancel();
    }

    void RunL() override
    {
        if (!iRequestStarted) {
            iRequestStarted = ETrue;
            iCurrentStage = EStageNewL;
            iResult.iNewLStarted = ETrue;

            // This NewL call runs in the worker's CActive::RunL leave context.
            iPlayer = CMdaAudioPlayerUtility::NewL(*this);
            iResult.iNewLReturned = ETrue;

            // Resolve the MDA DLL and ordinal at runtime. The source must not
            // contain a static OpenFileL import: absence of ordinal 42 is
            // reported cleanly before any attempt to open the WAV.
            iCurrentStage = EStageLibraryLoad;
            const TInt libraryError = iMdaLibrary.Load(_L("mediaclientaudio.dll"));
            if (libraryError != KErrNone) User::Leave(libraryError);
            iLibraryLoaded = ETrue;

            iCurrentStage = EStageOrdinalLookup;
            iOpenFileFunction = reinterpret_cast<TOpenFileLFunction>(
                iMdaLibrary.Lookup(KOpenFileLOrdinal));
            iResult.iOrdinalLookupCompleted = ETrue;
            if (iOpenFileFunction == 0) {
                iResult.iOpenFileOrdinalFound = EFalse;
                iResult.iOrdinalLookupError = KErrNotFound;
                CActiveScheduler::Stop();
                return;
            }
            iResult.iOpenFileOrdinalFound = ETrue;
            iResult.iOrdinalLookupError = KErrNone;

            iCurrentStage = EStageTimerSetup;
            const TInt timerError = iTimer.CreateLocal();
            if (timerError != KErrNone) User::Leave(timerError);
            iTimerCreated = ETrue;

            iWaitingForCallback = ETrue;
            iTimer.After(iStatus, KOpenTimeoutMicroseconds);
            SetActive();

            iCurrentStage = EStageOpenFileL;
            iResult.iOpenFileStarted = ETrue;
            // Call only the dynamically resolved OpenFileL export on the
            // packaged WAV; no Play or MIDI path is present in this probe.
            iOpenFileFunction(
                iPlayer, _L("C:\\data\\mda_open_file_probe.wav"));
            return;
        }

        if (iWaitingForCallback) {
            iWaitingForCallback = EFalse;
            iResult.iTimedOut = ETrue;
            CActiveScheduler::Stop();
            return;
        }

        CActiveScheduler::Stop();
    }

    TInt RunError(TInt aError) override
    {
        iResult.iRunErrorCaptured = ETrue;
        iResult.iRunError = aError;
        iResult.iRunErrorStage = iCurrentStage;
        iWaitingForCallback = EFalse;
        if (IsActive()) Cancel();
        CActiveScheduler::Stop();
        return KErrNone;
    }

    void MapcInitComplete(
        TInt aError,
        const TTimeIntervalMicroSeconds& aDuration) override
    {
        iResult.iInitCallback = ETrue;
        iResult.iInitError = aError;
        iResult.iDurationMilliseconds = static_cast<TInt>(
            aDuration.Int64() / 1000);
        iWaitingForCallback = EFalse;
        if (IsActive()) Cancel();
        CActiveScheduler::Stop();
    }

    void MapcPlayComplete(TInt aError) override
    {
        (void)aError;
        // No Play call is made in this diagnostic.
    }

private:
    TMdaOpenFileResult& iResult;
    CMdaAudioPlayerUtility* iPlayer;
    TOpenFileLFunction iOpenFileFunction;
    TBool iLibraryLoaded;
    RLibrary iMdaLibrary;
    RTimer iTimer;
    TBool iRequestStarted;
    TBool iWaitingForCallback;
    TBool iTimerCreated;
    TMdaOpenFileStage iCurrentStage;
};

TInt mdaOpenFileWorker(TAny* aData)
{
    TMdaOpenFileResult& result = *static_cast<TMdaOpenFileResult*>(aData);
    CTrapCleanup* cleanup = CTrapCleanup::New();
    if (cleanup == 0) return KErrNoMemory;

    // Isolate an explicit panic in this worker from the UI thread.
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

    {
        CMdaOpenFileProbeActive probe(result);
        probe.Start();
        CActiveScheduler::Start();
    }

    CActiveScheduler::Install(0);
    delete scheduler;
    delete cleanup;
    return KErrNone;
}

void runMdaOpenFileWorker(TMdaOpenFileResult& aResult)
{
    RThread thread;
    _LIT(KWorkerName, "MdaOpenFileProbe");
    aResult.iCreateError = thread.Create(
        KWorkerName,
        mdaOpenFileWorker,
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
                     const TMdaOpenFileResult& aResult)
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
    aGc.DrawText(_L("MDA OPENFILE DYNAMIC DIAGNOSTIC"), TPoint(8, 34));

    if (!aComplete) {
        aGc.DrawText(_L("Starting in worker thread"), TPoint(8, 62));
    } else if (aResult.iCreateError != KErrNone) {
        TBuf<56> line;
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
        TBuf<56> line;
        line.Format(_L("Worker setup error: %d"), aResult.iExitReason);
        aGc.DrawText(line, TPoint(8, 62));
    } else if (aResult.iRunErrorCaptured) {
        TBuf<56> line;
        if (aResult.iRunErrorStage == EStageNewL) {
            line.Format(_L("NewL leave code: %d"), aResult.iRunError);
        } else if (aResult.iRunErrorStage == EStageLibraryLoad) {
            line.Format(_L("MDA DLL load leave: %d"), aResult.iRunError);
        } else if (aResult.iRunErrorStage == EStageOrdinalLookup) {
            line.Format(_L("Ordinal lookup leave: %d"), aResult.iRunError);
        } else if (aResult.iRunErrorStage == EStageOpenFileL) {
            line.Format(_L("OpenFileL leave code: %d"), aResult.iRunError);
        } else {
            line.Format(_L("Timer setup error: %d"), aResult.iRunError);
        }
        aGc.DrawText(line, TPoint(8, 62));
        aGc.DrawText(_L("Leave caught by RunError"), TPoint(8, 88));
    } else if (aResult.iOrdinalLookupCompleted &&
               !aResult.iOpenFileOrdinalFound) {
        aGc.DrawText(_L("OpenFileL ordinal 42 not found"), TPoint(8, 62));
        aGc.DrawText(_L("No OpenFileL call was made"), TPoint(8, 88));
        TBuf<48> line;
        line.Format(_L("Lookup result code: %d"),
                    aResult.iOrdinalLookupError);
        aGc.DrawText(line, TPoint(8, 114));
    } else if (aResult.iInitCallback) {
        if (aResult.iInitError == KErrNone) {
            aGc.DrawText(_L("MapcInitComplete: success"), TPoint(8, 62));
        } else {
            TBuf<56> line;
            line.Format(_L("MapcInitComplete error: %d"), aResult.iInitError);
            aGc.DrawText(line, TPoint(8, 62));
        }
        TBuf<56> line;
        line.Format(_L("Duration: %d ms"), aResult.iDurationMilliseconds);
        aGc.DrawText(line, TPoint(8, 88));
    } else if (aResult.iTimedOut) {
        aGc.DrawText(_L("OpenFileL callback timed out"), TPoint(8, 62));
        aGc.DrawText(_L("No init callback within 15 sec"), TPoint(8, 88));
    } else if (aResult.iOpenFileStarted) {
        aGc.DrawText(_L("OpenFileL: waiting for callback"), TPoint(8, 62));
    } else if (aResult.iNewLReturned) {
        aGc.DrawText(_L("NewL returned; player created"), TPoint(8, 62));
    } else {
        TBuf<56> line;
        line.Format(_L("Worker exit type: %d"), (TInt)aResult.iExitType);
        aGc.DrawText(line, TPoint(8, 62));
        line.Format(_L("Worker exit reason: %d"), aResult.iExitReason);
        aGc.DrawText(line, TPoint(8, 88));
        if (aResult.iExitCategory.Length() > 0) {
            aGc.DrawText(aResult.iExitCategory, TPoint(8, 114));
        }
    }

    aGc.DrawText(_L("Packaged 100 ms PCM WAV"), TPoint(8, 140));
    aGc.DrawText(_L("C:\\data\\mda_open_file_probe.wav"), TPoint(8, 158));
    aGc.DrawText(_L("No Play; MIDI / other audio paths OFF"), TPoint(8, 184));
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
                     const TMdaOpenFileResult& aResult)
{
    TRequestStatus redrawStatus;
    aSession.RedrawReady(&redrawStatus);
    aWindow.Invalidate();
    aSession.Flush();
    User::WaitForRequest(redrawStatus);

    const TInt result = redrawStatus.Int();
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

    TMdaOpenFileResult workerResult;
    result = drawProbeWindow(
        aSession, window, aScreen, aGc, size, EFalse, workerResult);
    if (result == KErrNone) {
        runMdaOpenFileWorker(workerResult);
        result = drawProbeWindow(
            aSession, window, aScreen, aGc, size, ETrue, workerResult);
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
