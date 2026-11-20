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
const TInt KPCMPlaybackDurationMilliseconds = 500;
const TInt KPCMPlayCompleteTimeoutMicroseconds = 5000000;
const TInt KPCMStopCompleteTimeoutMicroseconds = 2000000;
const TInt KPCMAmplitude = 8192;
const TInt KSinTableSize = 64;
const TInt KOutputStreamNewLOrdinal = 3;

_LIT(KHexDigits, "0123456789ABCDEF");
_LIT(KOutputStreamLibraryName, "mediaclientaudiostream.dll");

const TInt16 KSinTable[KSinTableSize] = {
    0, 3212, 6393, 9512, 12539, 15446, 18204, 20787,
    23170, 25329, 27245, 28898, 30273, 31356, 32137, 32609,
    32767, 32609, 32137, 31356, 30273, 28898, 27245, 25329,
    23170, 20787, 18204, 15446, 12539, 9512, 6393, 3212,
    0, -3212, -6393, -9512, -12539, -15446, -18204, -20787,
    -23170, -25329, -27245, -28898, -30273, -31356, -32137, -32609,
    -32767, -32609, -32137, -31356, -30273, -28898, -27245, -25329,
    -23170, -20787, -18204, -15446, -12539, -9512, -6393, -3212
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
    EStageOpen11025Stereo,
    EStagePcmBuild,
    EStagePcmWrite,
    EStageBufferCopied,
    EStagePlayComplete
};

enum TProbeState
{
    EStateStart = 0,
    EStateWaitInitialize,
    EStateOpen8000Mono,
    EStateOpen11025Stereo,
    EStateWaitOpen,
    EStateWritePCM,
    EStateWaitBufferCopied,
    EStateStartWaitPlayComplete,
    EStateStopAfterCopyError,
    EStateWaitPlayComplete,
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

class TPCMPlaybackResult
{
public:
    TPCMPlaybackResult()
        : iWriteCalled(EFalse),
          iWriteError(KErrNotReady),
          iExpectedBytes(0),
          iBufferCopiedCallback(EFalse),
          iBufferCopiedError(KErrNotReady),
          iBufferCopiedBytes(0),
          iStopRequested(EFalse),
          iVolumeSet(EFalse),
          iMaxVolume(0),
          iRequestedVolume(0),
          iPlayCompleteCallback(EFalse),
          iPlayCompleteError(KErrNotReady),
          iTimedOut(EFalse),
          iTimeoutStage(EStageNone)
    {
    }

    TBool iWriteCalled;
    TInt iWriteError;
    TInt iExpectedBytes;
    TBool iBufferCopiedCallback;
    TInt iBufferCopiedError;
    TInt iBufferCopiedBytes;
    TBool iStopRequested;
    TBool iVolumeSet;
    TInt iMaxVolume;
    TInt iRequestedVolume;
    TBool iPlayCompleteCallback;
    TInt iPlayCompleteError;
    TBool iTimedOut;
    TProbeStage iTimeoutStage;
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
          iPlayback8000(),
          iPlayback11025(),
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
    TPCMPlaybackResult iPlayback8000;
    TPCMPlaybackResult iPlayback11025;
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
    case EStagePcmBuild:
        aText.Append(_L("PCM buffer generation"));
        break;
    case EStagePcmWrite:
        aText.Append(_L("MDA WriteL"));
        break;
    case EStageBufferCopied:
        aText.Append(_L("MaoscBufferCopied"));
        break;
    case EStagePlayComplete:
        aText.Append(_L("MaoscPlayComplete"));
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
          iCurrentStage(EStageNone),
          iPcmBuffer(0)
    {
        CActiveScheduler::Add(this);
    }

    ~CDevSoundProbeActive()
    {
        Cancel();
        delete iPcmBuffer;
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
        if (iWaitingForCallback) {
            const TProbeStage timedOutStage = iCurrentStage;
            iWaitingForCallback = EFalse;
            iResult.iTimedOut = ETrue;
            iResult.iTimeoutStage = timedOutStage;
            if (timedOutStage == EStageBufferCopied ||
                timedOutStage == EStagePlayComplete) {
                TPCMPlaybackResult& playback = activePlaybackResult();
                playback.iTimedOut = ETrue;
                playback.iTimeoutStage = timedOutStage;
            }
            failCurrent(KErrTimedOut);

            // Do not turn a missing natural completion into a premature cancel.
            // Stop only after the playback-complete grace period, then allow the
            // resulting cancel/completion callback its own short window.
            if (timedOutStage == EStagePlayComplete) {
                TPCMPlaybackResult& playback = activePlaybackResult();
                if (!playback.iStopRequested && iStream != 0) {
                    playback.iStopRequested = ETrue;
                    iState = EStateWaitPlayComplete;
                    armCallbackTimeoutL(
                        EStagePlayComplete,
                        KPCMStopCompleteTimeoutMicroseconds);
                    iStream->Stop();
                    return;
                }
            }
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
            delete iPcmBuffer;
            iPcmBuffer = 0;
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

        case EStateWritePCM:
            beginPCMWriteL();
            return;

        case EStateStartWaitPlayComplete:
            iState = EStateWaitPlayComplete;
            armCallbackTimeoutL(
                EStagePlayComplete,
                KPCMPlayCompleteTimeoutMicroseconds);
            return;

        case EStateStopAfterCopyError:
            stopAfterCopyErrorL();
            return;

        case EStateWaitInitialize:
        case EStateWaitOpen:
        case EStateWaitBufferCopied:
        case EStateWaitPlayComplete:
            // These states are only active while their callback timeout is armed.
            iResult.iRunErrorCaptured = ETrue;
            iResult.iRunError = KErrCorrupt;
            iResult.iRunErrorStage = iCurrentStage;
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
        cancelCurrentTimer();
        failCurrent(aError);
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

        cancelCurrentTimer();
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
        if (iState != EStateWaitOpen) {
            recordCallbackFailure(KErrCorrupt, EStageOpen8000Mono);
            return;
        }

        cancelCurrentTimer();
        if (iOutputTest == EOutputTest8000Mono) {
            iResult.iOpen8000Error = aError;
            iResult.iOpen8000Status = aError == KErrNone
                ? EOpenSucceeded : EOpenFailed;
        } else if (iOutputTest == EOutputTest11025Stereo) {
            iResult.iOpen11025Error = aError;
            iResult.iOpen11025Status = aError == KErrNone
                ? EOpenSucceeded : EOpenFailed;
        } else {
            recordCallbackFailure(KErrCorrupt, EStageOutputStreamNewL);
            return;
        }

        if (aError == KErrNone) {
            if (iStream == 0) {
                recordCallbackFailure(KErrCorrupt, EStageOutputStreamNewL);
                return;
            }
            TPCMPlaybackResult& playback = activePlaybackResult();
            playback.iMaxVolume = iStream->MaxVolume();
            playback.iRequestedVolume = playback.iMaxVolume / 2;
            iStream->SetVolume(playback.iRequestedVolume);
            playback.iVolumeSet = ETrue;
            iState = EStateWritePCM;
            queueRunL();
        } else {
            continueAfterCurrentOutput();
        }
    }

    void MaoscBufferCopied(TInt aError, const TDesC8& aBuffer) override
    {
        if (iState != EStateWaitBufferCopied) {
            recordCallbackFailure(KErrCorrupt, EStageBufferCopied);
            return;
        }

        cancelCurrentTimer();
        TPCMPlaybackResult& playback = activePlaybackResult();
        playback.iBufferCopiedCallback = ETrue;
        playback.iBufferCopiedError = aError;
        playback.iBufferCopiedBytes = aBuffer.Length();
        if (aError == KErrNone && (iPcmBuffer == 0 ||
            aBuffer.Length() != iPcmBuffer->Length())) {
            playback.iBufferCopiedError = KErrCorrupt;
        }

        if (playback.iBufferCopiedError == KErrNone) {
            if (playback.iPlayCompleteCallback) {
                continueAfterCurrentOutput();
                return;
            }
            iState = EStateStartWaitPlayComplete;
        } else if (playback.iPlayCompleteCallback) {
            continueAfterCurrentOutput();
            return;
        } else {
            iState = EStateStopAfterCopyError;
        }
        queueRunL();
    }

    void MaoscPlayComplete(TInt aError) override
    {
        if (iState != EStateStartWaitPlayComplete &&
            iState != EStateStopAfterCopyError &&
            iState != EStateWaitPlayComplete &&
            iState != EStateWaitBufferCopied) {
            recordCallbackFailure(KErrCorrupt, EStagePlayComplete);
            return;
        }

        TPCMPlaybackResult& playback = activePlaybackResult();
        playback.iPlayCompleteCallback = ETrue;
        playback.iPlayCompleteError = aError;

        // Keep the WriteL-copy timeout armed until its callback returns the
        // client buffer, even if the playback-complete notification is earlier.
        if (iState == EStateWaitBufferCopied) return;

        cancelCurrentTimer();
        continueAfterCurrentOutput();
    }

private:
    void queueRunL()
    {
        iStatus = KRequestPending;
        TRequestStatus* status = &iStatus;
        User::RequestComplete(status, KErrNone);
        SetActive();
    }

    void ensureTimerL()
    {
        if (iTimerCreated) return;
        iCurrentStage = EStageTimerSetup;
        const TInt error = iTimer.CreateLocal();
        if (error != KErrNone) User::Leave(error);
        iTimerCreated = ETrue;
    }

    void armCallbackTimeoutL(
        TProbeStage aWaitStage,
        TInt aTimeoutMicroseconds = KCallbackTimeoutMicroseconds)
    {
        ensureTimerL();
        iCurrentStage = aWaitStage;
        iWaitingForCallback = ETrue;
        iTimer.After(iStatus, aTimeoutMicroseconds);
        SetActive();
    }

    void cancelCurrentTimer()
    {
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
        // Initialize in playback mode to query the public caps/config API.
        // PCM playback below uses only the public MDA WriteL stream callback API.
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
            iResult.iOpen8000Status = EOpenPending;
            iCurrentStage = EStageOpen8000Mono;
        } else {
            iSettings.iSampleRate = TMdaAudioDataSettings::ESampleRate11025Hz;
            iSettings.iChannels = TMdaAudioDataSettings::EChannelsStereo;
            iResult.iOpen11025Status = EOpenPending;
            iCurrentStage = EStageOpen11025Stereo;
        }

        iState = EStateWaitOpen;
        armCallbackTimeoutL(iCurrentStage);
        // MDA output streams default to signed PCM16; no static MDA import is used.
        iStream->Open(&iSettings);
    }

    TPCMPlaybackResult& activePlaybackResult()
    {
        return iOutputTest == EOutputTest11025Stereo
            ? iResult.iPlayback11025 : iResult.iPlayback8000;
    }

    TOpenProbeStatus& activeOpenStatus()
    {
        return iOutputTest == EOutputTest11025Stereo
            ? iResult.iOpen11025Status : iResult.iOpen8000Status;
    }

    TInt& activeOpenError()
    {
        return iOutputTest == EOutputTest11025Stereo
            ? iResult.iOpen11025Error : iResult.iOpen8000Error;
    }

    void appendPcmSample(TDes8& aData, TInt aSample)
    {
        const TUint16 bits = static_cast<TUint16>(static_cast<TInt16>(aSample));
        const TUint8 bytes[2] = {
            static_cast<TUint8>(bits & 0xff),
            static_cast<TUint8>((bits >> 8) & 0xff)
        };
        aData.Append(bytes, 2);
    }

    void buildPcmToneL()
    {
        iCurrentStage = EStagePcmBuild;
        const TInt sampleRate = iOutputTest == EOutputTest8000Mono
            ? 8000 : 11025;
        const TInt channels = iOutputTest == EOutputTest8000Mono ? 1 : 2;
        const TInt frequencyHz = iOutputTest == EOutputTest8000Mono
            ? 440 : 660;
        const TInt frames =
            (sampleRate * KPCMPlaybackDurationMilliseconds + 500) / 1000;
        const TInt byteCount = frames * channels * 2;
        iPcmBuffer = HBufC8::NewL(byteCount);
        TPtr8 pcm = iPcmBuffer->Des();
        pcm.Zero();

        const TInt fadeFrames = sampleRate / 100;
        const TUint64 phaseStep =
            (static_cast<TUint64>(frequencyHz) << 32) /
            static_cast<TUint64>(sampleRate);
        TUint32 phase = 0;
        for (TInt frame = 0; frame < frames; ++frame) {
            const TInt tableIndex = static_cast<TInt>(phase >> 26);
            TInt sample = (KSinTable[tableIndex] * KPCMAmplitude) / 32767;
            TInt gain = 32767;
            if (frame < fadeFrames) {
                gain = (frame * 32767) / fadeFrames;
            } else if (frame >= frames - fadeFrames) {
                gain = ((frames - 1 - frame) * 32767) / fadeFrames;
            }
            sample = (sample * gain) / 32767;
            appendPcmSample(pcm, sample);
            if (channels == 2) appendPcmSample(pcm, sample);
            phase = static_cast<TUint32>(phase + phaseStep);
        }

        if (pcm.Length() != byteCount) User::Leave(KErrCorrupt);
        activePlaybackResult().iExpectedBytes = byteCount;
    }

    void beginPCMWriteL()
    {
        buildPcmToneL();
        TPCMPlaybackResult& playback = activePlaybackResult();
        playback.iWriteCalled = ETrue;
        iState = EStateWaitBufferCopied;
        armCallbackTimeoutL(EStageBufferCopied);
        iCurrentStage = EStagePcmWrite;
        iStream->WriteL(*iPcmBuffer);
        playback.iWriteError = KErrNone;
        iCurrentStage = EStageBufferCopied;
    }

    void stopAfterCopyErrorL()
    {
        TPCMPlaybackResult& playback = activePlaybackResult();
        playback.iStopRequested = ETrue;
        iState = EStateWaitPlayComplete;
        armCallbackTimeoutL(
            EStagePlayComplete,
            KPCMStopCompleteTimeoutMicroseconds);
        if (iStream != 0) iStream->Stop();
    }

    void continueAfterCurrentOutput()
    {
        delete iPcmBuffer;
        iPcmBuffer = 0;
        if (iOutputTest == EOutputTest8000Mono) {
            iState = EStateOpen11025Stereo;
            queueRunL();
        } else {
            iState = EStateDone;
            CActiveScheduler::Stop();
        }
    }

    void failCurrent(TInt aError)
    {
        if (iOutputTest != EOutputTestNone &&
            (iState == EStateWaitOpen ||
             iState == EStateOpen8000Mono ||
             iState == EStateOpen11025Stereo)) {
            activeOpenStatus() = EOpenFailed;
            activeOpenError() = aError;
        }

        TPCMPlaybackResult& playback = activePlaybackResult();
        if (iCurrentStage == EStagePcmWrite) {
            playback.iWriteCalled = ETrue;
            playback.iWriteError = aError;
        } else if (iCurrentStage == EStageBufferCopied &&
                   !playback.iBufferCopiedCallback) {
            playback.iBufferCopiedError = aError;
        } else if (iCurrentStage == EStagePlayComplete &&
                   !playback.iPlayCompleteCallback) {
            playback.iPlayCompleteError = aError;
        }
    }

    void recordCallbackFailure(TInt aError, TProbeStage aStage)
    {
        iResult.iRunErrorCaptured = ETrue;
        iResult.iRunError = aError;
        iResult.iRunErrorStage = aStage;
        iWaitingForCallback = EFalse;
        if (IsActive()) Cancel();
        CActiveScheduler::Stop();
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
    HBufC8* iPcmBuffer;
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

void appendOpenSummary(TDes& aText, TOpenProbeStatus aStatus, TInt aError)
{
    switch (aStatus) {
    case EOpenSkippedByCapabilities:
        aText.Append(_L("SKIP"));
        break;
    case EOpenApiUnavailable:
        aText.Append(_L("API"));
        break;
    case EOpenPending:
        aText.Append(_L("WAIT"));
        break;
    case EOpenSucceeded:
        aText.AppendNum(aError);
        break;
    case EOpenFailed:
        aText.Append(_L("!"));
        aText.AppendNum(aError);
        break;
    case EOpenNotRun:
    default:
        aText.Append(_L("--"));
        break;
    }
}

void drawPlaybackFormat(CWindowGc& aGc,
                        const TDesC& aLabel,
                        TOpenProbeStatus aOpenStatus,
                        TInt aOpenError,
                        const TPCMPlaybackResult& aPlayback,
                        TInt aFirstRow)
{
    TBuf<80> line;
    line.Copy(aLabel);
    line.Append(_L(" O:"));
    appendOpenSummary(line, aOpenStatus, aOpenError);
    line.Append(_L(" W:"));
    if (aPlayback.iWriteCalled) line.AppendNum(aPlayback.iWriteError);
    else line.Append(_L("--"));
    drawLine(aGc, line, aFirstRow);

    line.Copy(_L("Copy "));
    if (aPlayback.iBufferCopiedCallback) {
        line.AppendNum(aPlayback.iBufferCopiedError);
    } else if (aPlayback.iTimedOut &&
               aPlayback.iTimeoutStage == EStageBufferCopied) {
        line.Append(_L("TO"));
    } else {
        line.Append(_L("--"));
    }
    line.Append(_L(" "));
    line.AppendNum(aPlayback.iBufferCopiedBytes);
    line.Append(_L("/"));
    line.AppendNum(aPlayback.iExpectedBytes);
    line.Append(_L(" P:"));
    if (aPlayback.iPlayCompleteCallback) {
        line.AppendNum(aPlayback.iPlayCompleteError);
    } else if (aPlayback.iTimedOut &&
               aPlayback.iTimeoutStage == EStagePlayComplete) {
        line.Append(_L("TO"));
    } else {
        line.Append(_L("--"));
    }
    line.Append(_L(" S:"));
    line.Append(aPlayback.iStopRequested ? _L("Y") : _L("N"));
    line.Append(_L(" V:"));
    if (aPlayback.iVolumeSet) {
        line.AppendNum(aPlayback.iRequestedVolume);
        line.Append(_L("/"));
        line.AppendNum(aPlayback.iMaxVolume);
    } else {
        line.Append(_L("--"));
    }
    drawLine(aGc, line, aFirstRow + 1);
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
    drawLine(aGc, _L("E65 PCM16 PLAYBACK PROBE"), 0);

    if (!aComplete) {
        drawLine(aGc, _L("Starting public MDA PCM stream..."), 2);
        drawLine(aGc, _L("Two short, low-level sine samples."), 4);
        drawLine(aGc, _L("Listen for 440 Hz, then 660 Hz."), 5);
        drawLine(aGc, _L("Stream volume 50%; default route."), 7);
        aGc.DiscardFont();
        aScreen.ReleaseFont(font);
        return;
    }

    TInt row = 2;
    TBuf<80> line;
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

    if (aResult.iRunErrorCaptured) {
        line.Copy(_L("Error: "));
        appendStageName(line, aResult.iRunErrorStage);
        line.Append(_L(" "));
        line.AppendNum(aResult.iRunError);
        drawLine(aGc, line, row++);
    } else if (aResult.iThreadCreateError != KErrNone) {
        line.Copy(_L("Worker create error: "));
        line.AppendNum(aResult.iThreadCreateError);
        drawLine(aGc, line, row++);
    } else if (aResult.iThreadExitType == EExitPanic) {
        line.Copy(_L("Worker panic "));
        line.Append(aResult.iThreadExitCategory);
        line.Append(_L(" "));
        line.AppendNum(aResult.iThreadExitReason);
        drawLine(aGc, line, row++);
    }

    if (aResult.iCapabilitiesRead) {
        drawMaskLine(aGc, _L("Caps rate: "),
                     aResult.iCapabilityRate, row++);
        line.Copy(_L("Caps enc/ch: 0x"));
        appendHex32(line, aResult.iCapabilityEncoding);
        line.Append(_L("/0x"));
        appendHex32(line, aResult.iCapabilityChannels);
        drawLine(aGc, line, row++);
        line.Copy(_L("Cfg r/e/ch: 0x"));
        appendHex32(line, aResult.iConfigRate);
        line.Append(_L("/0x"));
        appendHex32(line, aResult.iConfigEncoding);
        line.Append(_L("/0x"));
        appendHex32(line, aResult.iConfigChannels);
        drawLine(aGc, line, row++);
    } else {
        drawLine(aGc, _L("Capabilities/Config not queried"), row++);
    }

    if (aResult.iStreamLibraryCheckComplete) {
        line.Copy(_L("MDA DLL: "));
        line.AppendNum(aResult.iStreamLibraryError);
        if (aResult.iStreamOrdinalLookupComplete) {
            line.Append(_L("; NewL #3: "));
            line.AppendNum(aResult.iStreamOrdinalLookupError);
        }
    } else {
        line.Copy(_L("MDA stream API not checked"));
    }
    drawLine(aGc, line, row++);

    drawPlaybackFormat(aGc, _L("8k mono 440Hz"),
                       aResult.iOpen8000Status, aResult.iOpen8000Error,
                       aResult.iPlayback8000, row);
    row += 2;
    drawPlaybackFormat(aGc, _L("11k stereo 660Hz"),
                       aResult.iOpen11025Status, aResult.iOpen11025Error,
                       aResult.iPlayback11025, row);
    row += 3;

    drawLine(aGc, _L("PCM16 LE signed; 500ms; peak 8192"), row++);
    drawLine(aGc, _L("MDA policy route; no route forced"), row++);
    drawLine(aGc, _L("Listen: first 440 Hz, then 660 Hz"), row++);
    drawLine(aGc, _L("Report speaker/headset separately"), row++);
    drawLine(aGc, _L("No driver/DSP/DMA; MIDI off"), row++);
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
