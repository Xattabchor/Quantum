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
const TInt KWaveHeaderBytes = 44;
const TInt KWaveDataBytes = 1600; // 100 ms, 8 kHz, mono, signed 16-bit PCM
const TInt KWaveBytes = KWaveHeaderBytes + KWaveDataBytes;
const TInt KOpenTimeoutMicroseconds = 15000000;

enum TMdaOpenStage
{
    EStageNone = 0,
    EStageNewL,
    EStageTimerSetup,
    EStageOpenDesL
};

class TMdaOpenDesResult
{
public:
    TMdaOpenDesResult()
        : iCreateError(KErrNotReady),
          iExitType(EExitPending),
          iExitReason(KErrNotReady),
          iNewLStarted(EFalse),
          iNewLReturned(EFalse),
          iOpenDesStarted(EFalse),
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
    TBool iOpenDesStarted;
    TBool iInitCallback;
    TBool iTimedOut;
    TBool iRunErrorCaptured;
    TInt iRunError;
    TMdaOpenStage iRunErrorStage;
    TInt iInitError;
    TInt iDurationMilliseconds;
};

void writeLe16(TUint8* aBytes, TInt aOffset, TUint16 aValue)
{
    aBytes[aOffset] = static_cast<TUint8>(aValue & 0xff);
    aBytes[aOffset + 1] = static_cast<TUint8>((aValue >> 8) & 0xff);
}

void writeLe32(TUint8* aBytes, TInt aOffset, TUint32 aValue)
{
    aBytes[aOffset] = static_cast<TUint8>(aValue & 0xff);
    aBytes[aOffset + 1] = static_cast<TUint8>((aValue >> 8) & 0xff);
    aBytes[aOffset + 2] = static_cast<TUint8>((aValue >> 16) & 0xff);
    aBytes[aOffset + 3] = static_cast<TUint8>((aValue >> 24) & 0xff);
}

void buildSilentPcmWave(TDes8& aWave)
{
    aWave.SetLength(KWaveBytes);
    TUint8* bytes = const_cast<TUint8*>(aWave.Ptr());
    Mem::FillZ(bytes, KWaveBytes);

    bytes[0] = 'R'; bytes[1] = 'I'; bytes[2] = 'F'; bytes[3] = 'F';
    writeLe32(bytes, 4, KWaveBytes - 8);
    bytes[8] = 'W'; bytes[9] = 'A'; bytes[10] = 'V'; bytes[11] = 'E';
    bytes[12] = 'f'; bytes[13] = 'm'; bytes[14] = 't'; bytes[15] = ' ';
    writeLe32(bytes, 16, 16);
    writeLe16(bytes, 20, 1); // WAVE_FORMAT_PCM
    writeLe16(bytes, 22, 1); // mono
    writeLe32(bytes, 24, 8000);
    writeLe32(bytes, 28, 16000);
    writeLe16(bytes, 32, 2); // block align
    writeLe16(bytes, 34, 16);
    bytes[36] = 'd'; bytes[37] = 'a'; bytes[38] = 't'; bytes[39] = 'a';
    writeLe32(bytes, 40, KWaveDataBytes);
    // The zero-filled PCM payload is silence. This probe never calls Play().
}

class CMdaOpenDesProbeActive : public CActive,
                               public MMdaAudioPlayerCallback
{
public:
    explicit CMdaOpenDesProbeActive(TMdaOpenDesResult& aResult)
        : CActive(CActive::EPriorityStandard),
          iResult(aResult),
          iPlayer(0),
          iRequestStarted(EFalse),
          iWaitingForCallback(EFalse),
          iTimerCreated(EFalse),
          iCurrentStage(EStageNone)
    {
        CActiveScheduler::Add(this);
    }

    ~CMdaOpenDesProbeActive()
    {
        Cancel();
        if (iTimerCreated) iTimer.Close();
        delete iPlayer;
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

            buildSilentPcmWave(iWave);
            iCurrentStage = EStageTimerSetup;
            const TInt timerError = iTimer.CreateLocal();
            if (timerError != KErrNone) User::Leave(timerError);
            iTimerCreated = ETrue;

            iWaitingForCallback = ETrue;
            iTimer.After(iStatus, KOpenTimeoutMicroseconds);
            SetActive();

            iCurrentStage = EStageOpenDesL;
            iResult.iOpenDesStarted = ETrue;
            // Open a short in-memory PCM WAV descriptor: no filesystem path,
            // no OpenFileL, no Play, and no MIDI.
            iPlayer->OpenDesL(iWave);
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
    TMdaOpenDesResult& iResult;
    CMdaAudioPlayerUtility* iPlayer;
    TBuf8<KWaveBytes> iWave;
    RTimer iTimer;
    TBool iRequestStarted;
    TBool iWaitingForCallback;
    TBool iTimerCreated;
    TMdaOpenStage iCurrentStage;
};

TInt mdaOpenDesWorker(TAny* aData)
{
    TMdaOpenDesResult& result = *static_cast<TMdaOpenDesResult*>(aData);
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
        CMdaOpenDesProbeActive probe(result);
        probe.Start();
        CActiveScheduler::Start();
    }

    CActiveScheduler::Install(0);
    delete scheduler;
    delete cleanup;
    return KErrNone;
}

void runMdaOpenDesWorker(TMdaOpenDesResult& aResult)
{
    RThread thread;
    _LIT(KWorkerName, "MdaOpenDesProbe");
    aResult.iCreateError = thread.Create(
        KWorkerName,
        mdaOpenDesWorker,
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
                     const TMdaOpenDesResult& aResult)
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
    aGc.DrawText(_L("MDA OPENDES DIAGNOSTIC"), TPoint(8, 34));

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
        } else if (aResult.iRunErrorStage == EStageOpenDesL) {
            line.Format(_L("OpenDesL leave code: %d"), aResult.iRunError);
        } else {
            line.Format(_L("Timer setup error: %d"), aResult.iRunError);
        }
        aGc.DrawText(line, TPoint(8, 62));
        aGc.DrawText(_L("Leave caught by RunError"), TPoint(8, 88));
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
        aGc.DrawText(_L("OpenDesL callback timed out"), TPoint(8, 62));
        aGc.DrawText(_L("No init callback within 15 sec"), TPoint(8, 88));
    } else if (aResult.iOpenDesStarted) {
        aGc.DrawText(_L("OpenDesL: waiting for callback"), TPoint(8, 62));
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

    aGc.DrawText(_L("100 ms PCM WAV from RAM"), TPoint(8, 140));
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
                     const TMdaOpenDesResult& aResult)
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

    TMdaOpenDesResult workerResult;
    result = drawProbeWindow(
        aSession, window, aScreen, aGc, size, EFalse, workerResult);
    if (result == KErrNone) {
        runMdaOpenDesWorker(workerResult);
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
