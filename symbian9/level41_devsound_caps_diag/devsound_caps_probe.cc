#include <e32base.h>
#include <e32std.h>
#include <gdi.h>
#include <mda/common/audio.h>
#include <mdaaudiooutputstream.h>
#include <mmf/server/sounddevice.h>
#include <w32std.h>

namespace {

const TUint KWindowGroupClientHandle = 1;
const TUint KProbeWindowHandle = 2;
const TInt KWorkerStackSize = 0x8000;
const TInt KWorkerHeapMin = 0x10000;
const TInt KWorkerHeapMax = 0x400000;
const TInt KCallbackTimeoutMicroseconds = 15000000;
const TInt KOutputStreamNewLOrdinal = 3;

_LIT(KHexDigits, "0123456789ABCDEF");
_LIT(KOutputStreamLibraryName, "mediaclientaudiostream.dll");

struct TSampleRateEntry
{
    TUint iFlag;
    TInt iHertz;
};

const TSampleRateEntry KSampleRates[] = {
    {EMMFSampleRate8000Hz, 8000},
    {EMMFSampleRate11025Hz, 11025},
    {EMMFSampleRate12000Hz, 12000},
    {EMMFSampleRate16000Hz, 16000},
    {EMMFSampleRate22050Hz, 22050},
    {EMMFSampleRate24000Hz, 24000},
    {EMMFSampleRate32000Hz, 32000},
    {EMMFSampleRate44100Hz, 44100},
    {EMMFSampleRate48000Hz, 48000},
    {EMMFSampleRate64000Hz, 64000},
    {EMMFSampleRate88200Hz, 88200},
    {EMMFSampleRate96000Hz, 96000}
};

enum TProbeStage
{
    EStageNone = 0,
    EStageDevSoundNewL,
    EStageTimerSetup,
    EStageDevSoundInitialize,
    EStageCapabilities,
    EStageStreamLibraryLoad,
    EStageStreamOrdinalLookup,
    EStageOutputStreamNewL,
    EStageOpen8000Mono,
    EStageOpen11025Stereo
};

enum TProbeState
{
    EStateStart = 0,
    EStateWaitInitialize,
    EStateOpen8000Mono,
    EStateWaitOpen8000Mono,
    EStateOpen11025Stereo,
    EStateWaitOpen11025Stereo,
    EStateDone
};

enum TOutputTest
{
    EOutputTestNone = 0,
    EOutputTest8000Mono,
    EOutputTest11025Stereo
};

enum TOpenProbeStatus
{
    EOpenNotRun = 0,
    EOpenSkippedByCapabilities,
    EOpenApiUnavailable,
    EOpenPending,
    EOpenSucceeded,
    EOpenFailed
};

class TDevSoundProbeResult
{
public:
    TDevSoundProbeResult()
        : iThreadCreateError(KErrNotReady),
          iThreadExitType(EExitPending),
          iThreadExitReason(KErrNotReady),
          iDevSoundNewLStarted(EFalse),
          iDevSoundNewLReturned(EFalse),
          iInitializeStarted(EFalse),
          iInitializeCallback(EFalse),
          iInitializeError(KErrNotReady),
          iCapabilitiesRead(EFalse),
          iCapabilityRate(0),
          iCapabilityEncoding(0),
          iCapabilityChannels(0),
          iCapabilityBufferSize(0),
          iConfigRate(0),
          iConfigEncoding(0),
          iConfigChannels(0),
          iConfigBufferSize(0),
          iStreamLibraryCheckComplete(EFalse),
          iStreamLibraryError(KErrNotReady),
          iStreamOrdinalLookupComplete(EFalse),
          iStreamOrdinalLookupError(KErrNotReady),
          iOpen8000Status(EOpenNotRun),
          iOpen8000Error(KErrNotReady),
          iOpen11025Status(EOpenNotRun),
          iOpen11025Error(KErrNotReady),
          iTimedOut(EFalse),
          iTimeoutStage(EStageNone),
          iRunErrorCaptured(EFalse),
          iRunError(KErrNotReady),
          iRunErrorStage(EStageNone)
    {
        iThreadExitCategory.Zero();
    }

    TInt iThreadCreateError;
    TExitType iThreadExitType;
    TInt iThreadExitReason;
    TExitCategoryName iThreadExitCategory;
    TBool iDevSoundNewLStarted;
    TBool iDevSoundNewLReturned;
    TBool iInitializeStarted;
    TBool iInitializeCallback;
    TInt iInitializeError;
    TBool iCapabilitiesRead;
    TUint iCapabilityRate;
    TUint iCapabilityEncoding;
    TUint iCapabilityChannels;
    TInt iCapabilityBufferSize;
    TUint iConfigRate;
    TUint iConfigEncoding;
    TUint iConfigChannels;
    TInt iConfigBufferSize;
    TBool iStreamLibraryCheckComplete;
    TInt iStreamLibraryError;
    TBool iStreamOrdinalLookupComplete;
    TInt iStreamOrdinalLookupError;
    TOpenProbeStatus iOpen8000Status;
    TInt iOpen8000Error;
    TOpenProbeStatus iOpen11025Status;
    TInt iOpen11025Error;
    TBool iTimedOut;
    TProbeStage iTimeoutStage;
    TBool iRunErrorCaptured;
    TInt iRunError;
    TProbeStage iRunErrorStage;
};

typedef CMdaAudioOutputStream* (*TOutputStreamNewLFunction)(
    MMdaAudioOutputStreamCallback&, CMdaServer*);

void appendHex32(TDes& aText, TUint aValue)
{
    for (TInt shift = 28; shift >= 0; shift -= 4) {
        const TInt digit = static_cast<TInt>((aValue >> shift) & 0x0f);
        aText.Append(TChar(KHexDigits()[digit]));
    }
}

void appendStageName(TDes& aText, TProbeStage aStage)
{
    switch (aStage) {
    case EStageDevSoundNewL:
        aText.Append(_L("DevSound NewL"));
        break;
    case EStageTimerSetup:
        aText.Append(_L("timer setup"));
        break;
    case EStageDevSoundInitialize:
        aText.Append(_L("DevSound InitializeL"));
        break;
    case EStageCapabilities:
        aText.Append(_L("Capabilities/Config"));
        break;
    case EStageStreamLibraryLoad:
        aText.Append(_L("MDA stream DLL load"));
        break;
    case EStageStreamOrdinalLookup:
        aText.Append(_L("MDA NewL ordinal lookup"));
        break;
    case EStageOutputStreamNewL:
        aText.Append(_L("MDA output stream NewL"));
        break;
    case EStageOpen8000Mono:
        aText.Append(_L("Open 8000 Hz mono"));
        break;
    case EStageOpen11025Stereo:
        aText.Append(_L("Open 11025 Hz stereo"));
        break;
    default:
        aText.Append(_L("unknown stage"));
        break;
    }
}

void drawLine(CWindowGc& aGc, const TDesC& aText, TInt aRow)
{
    aGc.DrawText(aText, TPoint(5, 19 + aRow * 15));
}

void drawMaskLine(CWindowGc& aGc,
                  const TDesC& aLabel,
                  TUint aMask,
                  TInt aRow)
{
    TBuf<64> text;
    text.Copy(aLabel);
    text.Append(_L("0x"));
    appendHex32(text, aMask);
    drawLine(aGc, text, aRow);
}

void drawTwoMasksLine(CWindowGc& aGc,
                      const TDesC& aLabel,
                      TUint aFirstMask,
                      const TDesC& aSecondLabel,
                      TUint aSecondMask,
                      TInt aRow)
{
    TBuf<64> text;
    text.Copy(aLabel);
    text.Append(_L("0x"));
    appendHex32(text, aFirstMask);
    text.Append(aSecondLabel);
    text.Append(_L("0x"));
    appendHex32(text, aSecondMask);
    drawLine(aGc, text, aRow);
}

TInt drawRateLines(CWindowGc& aGc, TUint aRateMask, TInt aFirstRow)
{
    TBuf<64> line;
    line.Copy(_L("Rates Hz: "));
    TBool hasRate = EFalse;

    for (TUint i = 0; i < sizeof(KSampleRates) / sizeof(KSampleRates[0]); ++i) {
        if ((aRateMask & KSampleRates[i].iFlag) == 0) continue;

        TBuf<12> rate;
        rate.AppendNum(KSampleRates[i].iHertz);
        const TInt separator = hasRate ? 1 : 0;
        if (hasRate && line.Length() + separator + rate.Length() > 35) {
            drawLine(aGc, line, aFirstRow++);
            line.Copy(_L("          "));
            hasRate = EFalse;
        }
        if (hasRate) line.Append(_L(" "));
        line.Append(rate);
        hasRate = ETrue;
    }

    if (!hasRate && line.Length() == 10) line.Append(_L("none"));
    drawLine(aGc, line, aFirstRow++);
    return aFirstRow;
}

void drawOpenStatus(CWindowGc& aGc,
                    const TDesC& aLabel,
                    TOpenProbeStatus aStatus,
                    TInt aError,
                    TInt aRow)
{
    TBuf<64> line;
    line.Copy(aLabel);
    line.Append(_L(": "));

    switch (aStatus) {
    case EOpenSkippedByCapabilities:
        line.Append(_L("SKIP (not in caps)"));
        break;
    case EOpenApiUnavailable:
        line.Append(_L("SKIP (API unavailable)"));
        if (aError != KErrNone && aError != KErrNotReady) {
            line.Append(_L(" "));
            line.AppendNum(aError);
        }
        break;
    case EOpenPending:
        line.Append(_L("pending"));
        break;
    case EOpenSucceeded:
        line.Append(_L("OK ("));
        line.AppendNum(aError);
        line.Append(_L(")"));
        break;
    case EOpenFailed:
        line.Append(_L("ERR "));
        line.AppendNum(aError);
        break;
    case EOpenNotRun:
    default:
        line.Append(_L("not run"));
        break;
    }

    drawLine(aGc, line, aRow);
}

TBool supportsFormat(const TDevSoundProbeResult& aResult,
                     TUint aRate,
                     TUint aChannels)
{
    return aResult.iCapabilitiesRead &&
        (aResult.iCapabilityRate & aRate) != 0 &&
        (aResult.iCapabilityEncoding & EMMFSoundEncoding16BitPCM) != 0 &&
        (aResult.iCapabilityChannels & aChannels) != 0;
}

class CDevSoundProbeActive final : public CActive,
                                   public MDevSoundObserver,
                                   public MMdaAudioOutputStreamCallback
{
public:
    explicit CDevSoundProbeActive(TDevSoundProbeResult& aResult)
        : CActive(CActive::EPriorityStandard),
          iResult(aResult),
          iState(EStateStart),
          iOutputTest(EOutputTestNone),
          iDevSound(0),
          iStream(0),
          iOutputStreamNewL(0),
          iStreamLibraryLoaded(EFalse),
          iTimerCreated(EFalse),
          iWaitingForCallback(EFalse),
          iCurrentStage(EStageNone)
    {
        CActiveScheduler::Add(this);
    }

    ~CDevSoundProbeActive()
    {
        Cancel();
        delete iStream;
        delete iDevSound;
        if (iTimerCreated) iTimer.Close();
        if (iStreamLibraryLoaded) iStreamLibrary.Close();
    }

    void Start()
    {
        queueRunL();
    }

private:
    void DoCancel() override
    {
        if (iTimerCreated) iTimer.Cancel();
    }

    void RunL() override
    {
        // The active request is an RTimer while an asynchronous public API
        // callback is pending. Any other activation is a queued state step.
        if (iWaitingForCallback) {
            iWaitingForCallback = EFalse;
            iResult.iTimedOut = ETrue;
            iResult.iTimeoutStage = iCurrentStage;
            failActiveOpen(KErrTimedOut);
            CActiveScheduler::Stop();
            return;
        }

        switch (iState) {
        case EStateStart:
            startDevSoundL();
            return;

        case EStateOpen8000Mono:
            delete iDevSound;
            iDevSound = 0;
            if (!loadOutputStreamApi()) {
                markOpenApiUnavailable();
                iState = EStateDone;
                CActiveScheduler::Stop();
                return;
            }
            if (!supportsFormat(iResult,
                                EMMFSampleRate8000Hz,
                                EMMFMono)) {
                iResult.iOpen8000Status = EOpenSkippedByCapabilities;
                iResult.iOpen8000Error = KErrNotSupported;
                iState = EStateOpen11025Stereo;
                queueRunL();
                return;
            }
            beginOutputStreamOpenL(EOutputTest8000Mono);
            return;

        case EStateOpen11025Stereo:
            delete iStream;
            iStream = 0;
            if (!supportsFormat(iResult,
                                EMMFSampleRate11025Hz,
                                EMMFStereo)) {
                iResult.iOpen11025Status = EOpenSkippedByCapabilities;
                iResult.iOpen11025Error = KErrNotSupported;
                iState = EStateDone;
                CActiveScheduler::Stop();
                return;
            }
            beginOutputStreamOpenL(EOutputTest11025Stereo);
            return;

        case EStateWaitInitialize:
        case EStateWaitOpen8000Mono:
        case EStateWaitOpen11025Stereo:
            // Waiting states are activated only by the callback timer.
            iResult.iTimedOut = ETrue;
            iResult.iTimeoutStage = iCurrentStage;
            failActiveOpen(KErrTimedOut);
            CActiveScheduler::Stop();
            return;

        case EStateDone:
        default:
            CActiveScheduler::Stop();
            return;
        }
    }

    TInt RunError(TInt aError) override
    {
        iResult.iRunErrorCaptured = ETrue;
        iResult.iRunError = aError;
        iResult.iRunErrorStage = iCurrentStage;
        iWaitingForCallback = EFalse;
        failActiveOpen(aError);
        if (IsActive()) Cancel();
        CActiveScheduler::Stop();
        return KErrNone;
    }

    void InitializeComplete(TInt aError) override
    {
        iResult.iInitializeCallback = ETrue;
        iResult.iInitializeError = aError;

        if (aError == KErrNone && iDevSound != 0) {
            iCurrentStage = EStageCapabilities;
            const TMMFCapabilities capabilities = iDevSound->Capabilities();
            const TMMFCapabilities configuration = iDevSound->Config();
            iResult.iCapabilityRate = capabilities.iRate;
            iResult.iCapabilityEncoding = capabilities.iEncoding;
            iResult.iCapabilityChannels = capabilities.iChannels;
            iResult.iCapabilityBufferSize = capabilities.iBufferSize;
            iResult.iConfigRate = configuration.iRate;
            iResult.iConfigEncoding = configuration.iEncoding;
            iResult.iConfigChannels = configuration.iChannels;
            iResult.iConfigBufferSize = configuration.iBufferSize;
            iResult.iCapabilitiesRead = ETrue;
        }

        cancelCallbackWait();
        iState = aError == KErrNone ? EStateOpen8000Mono : EStateDone;
        queueRunL();
    }

    void ToneFinished(TInt aError) override
    {
        (void)aError;
    }

    void BufferToBeFilled(CMMFBuffer* aBuffer) override
    {
        (void)aBuffer;
    }

    void PlayError(TInt aError) override
    {
        (void)aError;
    }

    void BufferToBeEmptied(CMMFBuffer* aBuffer) override
    {
        (void)aBuffer;
    }

    void RecordError(TInt aError) override
    {
        (void)aError;
    }

    void ConvertError(TInt aError) override
    {
        (void)aError;
    }

    void DeviceMessage(TUid aMessageType, const TDesC8& aMsg) override
    {
        (void)aMessageType;
        (void)aMsg;
    }

    void MaoscOpenComplete(TInt aError) override
    {
        if (iState == EStateWaitOpen8000Mono) {
            iResult.iOpen8000Error = aError;
            iResult.iOpen8000Status = aError == KErrNone
                ? EOpenSucceeded : EOpenFailed;
            iState = EStateOpen11025Stereo;
        } else if (iState == EStateWaitOpen11025Stereo) {
            iResult.iOpen11025Error = aError;
            iResult.iOpen11025Status = aError == KErrNone
                ? EOpenSucceeded : EOpenFailed;
            iState = EStateDone;
        } else {
            failActiveOpen(KErrCorrupt);
            iState = EStateDone;
        }

        cancelCallbackWait();
        if (iState == EStateDone) {
            CActiveScheduler::Stop();
        } else {
            queueRunL();
        }
    }

    void MaoscBufferCopied(TInt aError, const TDesC8& aBuffer) override
    {
        (void)aError;
        (void)aBuffer;
        // No WriteL call exists in this probe, so this callback is not expected.
    }

    void MaoscPlayComplete(TInt aError) override
    {
        (void)aError;
        // No stream data is written and no Stop/play-completion is requested.
    }

private:
    void queueRunL()
    {
        iStatus = KRequestPending;
        TRequestStatus* status = &iStatus;
        User::RequestComplete(status, KErrNone);
        SetActive();
    }

    void armCallbackTimeoutL(TProbeStage aWaitStage)
    {
        if (!iTimerCreated) {
            iCurrentStage = EStageTimerSetup;
            const TInt error = iTimer.CreateLocal();
            if (error != KErrNone) User::Leave(error);
            iTimerCreated = ETrue;
        }
        iCurrentStage = aWaitStage;
        iWaitingForCallback = ETrue;
        iTimer.After(iStatus, KCallbackTimeoutMicroseconds);
        SetActive();
    }

    void cancelCallbackWait()
    {
        if (!iWaitingForCallback) return;
        iWaitingForCallback = EFalse;
        if (IsActive()) Cancel();
    }

    void startDevSoundL()
    {
        iCurrentStage = EStageDevSoundNewL;
        iResult.iDevSoundNewLStarted = ETrue;
        iDevSound = CMMFDevSound::NewL();
        iResult.iDevSoundNewLReturned = ETrue;

        iCurrentStage = EStageDevSoundInitialize;
        iResult.iInitializeStarted = ETrue;
        iState = EStateWaitInitialize;
        armCallbackTimeoutL(EStageDevSoundInitialize);
        // Initialize in playback mode solely to query public capabilities.
        // No PlayInitL(), PlayData(), SetConfigL(), or tone call follows.
        iDevSound->InitializeL(*this, EMMFStatePlaying);
    }

    TBool loadOutputStreamApi()
    {
        iCurrentStage = EStageStreamLibraryLoad;
        const TInt loadError = iStreamLibrary.Load(KOutputStreamLibraryName);
        iResult.iStreamLibraryCheckComplete = ETrue;
        iResult.iStreamLibraryError = loadError;
        if (loadError != KErrNone) return EFalse;
        iStreamLibraryLoaded = ETrue;

        iCurrentStage = EStageStreamOrdinalLookup;
        const TLibraryFunction function =
            iStreamLibrary.Lookup(KOutputStreamNewLOrdinal);
        iResult.iStreamOrdinalLookupComplete = ETrue;
        if (function == 0) {
            iResult.iStreamOrdinalLookupError = KErrNotFound;
            return EFalse;
        }
        iResult.iStreamOrdinalLookupError = KErrNone;
        iOutputStreamNewL = reinterpret_cast<TOutputStreamNewLFunction>(function);
        return ETrue;
    }

    void markOpenApiUnavailable()
    {
        const TInt error = iResult.iStreamLibraryError != KErrNone
            ? iResult.iStreamLibraryError
            : iResult.iStreamOrdinalLookupError;
        iResult.iOpen8000Status = EOpenApiUnavailable;
        iResult.iOpen8000Error = error;
        iResult.iOpen11025Status = EOpenApiUnavailable;
        iResult.iOpen11025Error = error;
    }

    void beginOutputStreamOpenL(TOutputTest aTest)
    {
        iOutputTest = aTest;
        iCurrentStage = EStageOutputStreamNewL;
        iStream = iOutputStreamNewL(*this, 0);
        if (iStream == 0) User::Leave(KErrNoMemory);

        if (aTest == EOutputTest8000Mono) {
            iSettings.iSampleRate = TMdaAudioDataSettings::ESampleRate8000Hz;
            iSettings.iChannels = TMdaAudioDataSettings::EChannelsMono;
            iState = EStateWaitOpen8000Mono;
            iCurrentStage = EStageOpen8000Mono;
            iResult.iOpen8000Status = EOpenPending;
        } else {
            iSettings.iSampleRate = TMdaAudioDataSettings::ESampleRate11025Hz;
            iSettings.iChannels = TMdaAudioDataSettings::EChannelsStereo;
            iState = EStateWaitOpen11025Stereo;
            iCurrentStage = EStageOpen11025Stereo;
            iResult.iOpen11025Status = EOpenPending;
        }

        armCallbackTimeoutL(aTest == EOutputTest8000Mono
            ? EStageOpen8000Mono : EStageOpen11025Stereo);
        // MDA output streams default to PCM16. Open only; deliberately no WriteL.
        iStream->Open(&iSettings);
    }

    void failActiveOpen(TInt aError)
    {
        if (iOutputTest == EOutputTest8000Mono &&
            iResult.iOpen8000Status != EOpenSucceeded) {
            iResult.iOpen8000Status = EOpenFailed;
            iResult.iOpen8000Error = aError;
        } else if (iOutputTest == EOutputTest11025Stereo &&
                   iResult.iOpen11025Status != EOpenSucceeded) {
            iResult.iOpen11025Status = EOpenFailed;
            iResult.iOpen11025Error = aError;
        }
    }

private:
    TDevSoundProbeResult& iResult;
    TProbeState iState;
    TOutputTest iOutputTest;
    CMMFDevSound* iDevSound;
    CMdaAudioOutputStream* iStream;
    TOutputStreamNewLFunction iOutputStreamNewL;
    TBool iStreamLibraryLoaded;
    RLibrary iStreamLibrary;
    RTimer iTimer;
    TBool iTimerCreated;
    TBool iWaitingForCallback;
    TProbeStage iCurrentStage;
    TMdaAudioDataSettings iSettings;
};

TInt devSoundProbeWorker(TAny* aData)
{
    TDevSoundProbeResult& result = *static_cast<TDevSoundProbeResult*>(aData);
    CTrapCleanup* cleanup = CTrapCleanup::New();
    if (cleanup == 0) return KErrNoMemory;

    // Isolate an explicit server/API panic from the UI thread.
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
        CDevSoundProbeActive probe(result);
        probe.Start();
        CActiveScheduler::Start();
    }

    CActiveScheduler::Install(0);
    delete scheduler;
    delete cleanup;
    return KErrNone;
}

void runDevSoundProbeWorker(TDevSoundProbeResult& aResult)
{
    RThread thread;
    _LIT(KWorkerName, "DevSoundCapsProbe");
    aResult.iThreadCreateError = thread.Create(
        KWorkerName,
        devSoundProbeWorker,
        KWorkerStackSize,
        KWorkerHeapMin,
        KWorkerHeapMax,
        &aResult);
    if (aResult.iThreadCreateError != KErrNone) return;

    TRequestStatus threadStatus;
    thread.Logon(threadStatus);
    thread.Resume();
    User::WaitForRequest(threadStatus);

    aResult.iThreadExitType = thread.ExitType();
    aResult.iThreadExitReason = thread.ExitReason();
    aResult.iThreadExitCategory = thread.ExitCategory();
    thread.Close();
}

void drawProbeScreen(CWsScreenDevice& aScreen,
                     CWindowGc& aGc,
                     const TSize& aSize,
                     TBool aComplete,
                     const TDevSoundProbeResult& aResult)
{
    aGc.SetBrushStyle(CGraphicsContext::ESolidBrush);
    aGc.SetPenStyle(CGraphicsContext::ENullPen);
    aGc.SetBrushColor(TRgb(12, 20, 32));
    aGc.Clear(TRect(TPoint(0, 0), aSize));

    CFont* font = 0;
    if (aScreen.GetNearestFontInPixels(
            font, TFontSpec(_L("Series 60 Sans"), 10)) != KErrNone) {
        return;
    }

    aGc.UseFont(font);
    aGc.SetPenStyle(CGraphicsContext::ESolidPen);
    aGc.SetPenColor(TRgb(240, 244, 250));
    drawLine(aGc, _L("E65 DEVSOUND / PCM OPEN PROBE"), 0);

    if (!aComplete) {
        drawLine(aGc, _L("Querying public DevSound API..."), 2);
        drawLine(aGc, _L("No PCM data will be written."), 4);
        drawLine(aGc, _L("No playback or tone is started."), 5);
        aGc.DiscardFont();
        aScreen.ReleaseFont(font);
        return;
    }

    if (aResult.iThreadCreateError != KErrNone) {
        TBuf<64> line;
        line.Copy(_L("Worker create error: "));
        line.AppendNum(aResult.iThreadCreateError);
        drawLine(aGc, line, 2);
    } else if (aResult.iThreadExitType == EExitPanic) {
        drawLine(aGc, _L("Worker/API panic captured"), 2);
        drawLine(aGc, aResult.iThreadExitCategory, 3);
        TBuf<48> line;
        line.Copy(_L("Panic reason: "));
        line.AppendNum(aResult.iThreadExitReason);
        drawLine(aGc, line, 4);
    } else if (aResult.iRunErrorCaptured) {
        TBuf<64> line;
        line.Copy(_L("Leave at "));
        appendStageName(line, aResult.iRunErrorStage);
        drawLine(aGc, line, 2);
        line.Zero();
        line.Copy(_L("Leave code: "));
        line.AppendNum(aResult.iRunError);
        drawLine(aGc, line, 3);
    } else if (!aResult.iDevSoundNewLReturned) {
        if (aResult.iThreadExitType == EExitKill &&
            aResult.iThreadExitReason != KErrNone) {
            TBuf<64> line;
            line.Copy(_L("Worker setup error: "));
            line.AppendNum(aResult.iThreadExitReason);
            drawLine(aGc, line, 2);
        } else {
            drawLine(aGc, _L("DevSound NewL did not return"), 2);
        }
    } else {
        TInt row = 2;
        TBuf<64> line;
        if (aResult.iInitializeCallback) {
            line.Copy(_L("InitializeComplete: "));
            line.AppendNum(aResult.iInitializeError);
        } else if (aResult.iTimedOut &&
                   aResult.iTimeoutStage == EStageDevSoundInitialize) {
            line.Copy(_L("Initialize callback timeout"));
        } else {
            line.Copy(_L("Initialize callback not received"));
        }
        drawLine(aGc, line, row++);

        if (aResult.iCapabilitiesRead) {
            drawMaskLine(aGc, _L("Caps rate mask: "),
                         aResult.iCapabilityRate, row++);
            row = drawRateLines(aGc, aResult.iCapabilityRate, row);
            drawTwoMasksLine(aGc, _L("Caps enc: "),
                             aResult.iCapabilityEncoding,
                             _L(" ch: "), aResult.iCapabilityChannels,
                             row++);
            line.Copy(_L("Caps buffer bytes: "));
            line.AppendNum(aResult.iCapabilityBufferSize);
            drawLine(aGc, line, row++);
            drawMaskLine(aGc, _L("Config rate: "),
                         aResult.iConfigRate, row++);
            drawTwoMasksLine(aGc, _L("Config enc: "),
                             aResult.iConfigEncoding,
                             _L(" ch: "), aResult.iConfigChannels,
                             row++);
            line.Copy(_L("Config buffer bytes: "));
            line.AppendNum(aResult.iConfigBufferSize);
            drawLine(aGc, line, row++);
        } else {
            drawLine(aGc, _L("Capabilities/Config: not queried"), row++);
        }

        if (aResult.iStreamLibraryCheckComplete) {
            line.Copy(_L("Stream DLL load: "));
            line.AppendNum(aResult.iStreamLibraryError);
            if (aResult.iStreamOrdinalLookupComplete) {
                line.Append(_L("; NewL #3: "));
                line.AppendNum(aResult.iStreamOrdinalLookupError);
            }
        } else {
            line.Copy(_L("MDA stream API: not checked"));
        }
        drawLine(aGc, line, row++);

        drawOpenStatus(aGc, _L("Open 8000 mono PCM16"),
                       aResult.iOpen8000Status, aResult.iOpen8000Error, row++);
        drawOpenStatus(aGc, _L("Open 11025 stereo PCM16"),
                       aResult.iOpen11025Status, aResult.iOpen11025Error, row++);

        drawLine(aGc, _L("Open only: no WriteL / PlayData"), row++);
        drawLine(aGc, _L("No audible output or route tested"), row++);
        drawLine(aGc, _L("No driver/DSP/DMA; MIDI off"), row++);
    }

    drawLine(aGc, _L("Press any key to exit"), 18);
    aGc.DiscardFont();
    aScreen.ReleaseFont(font);
}

TInt drawProbeWindow(RWsSession& aSession,
                     RWindow& aWindow,
                     CWsScreenDevice& aScreen,
                     CWindowGc& aGc,
                     const TSize& aSize,
                     TBool aComplete,
                     const TDevSoundProbeResult& aResult)
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

    TDevSoundProbeResult probeResult;
    result = drawProbeWindow(
        aSession, window, aScreen, aGc, size, EFalse, probeResult);
    if (result == KErrNone) {
        runDevSoundProbeWorker(probeResult);
        result = drawProbeWindow(
            aSession, window, aScreen, aGc, size, ETrue, probeResult);
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
