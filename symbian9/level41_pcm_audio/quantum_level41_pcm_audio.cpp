#include "quantum_level41_pcm_audio.h"

#include "quantum_level41_pcm_core.h"

#include <f32file.h>
#include <mdaaudiooutputstream.h>
#include <mdaaudiosampleplayer.h>
#include <mda/common/audio.h>
#include <mda/common/resource.h>

/*
 * Level 41 audio backend: effects on one PCM stream, music unchanged.
 *
 * The music half below is the shipped quantum_level41_audio.cpp logic moved
 * over without behaviour changes: same MDA player utility, same ordinal-42
 * OpenFileL import, same state machine, same deferred replay of the looping
 * track after MapcPlayComplete. Fire and reload no longer use a player each;
 * they go to a single CMdaAudioOutputStream that plays one 8 kHz mono PCM16
 * clip at a time.
 */

using quantum::level41pcm::CopyToStream;
using quantum::level41pcm::EWaveParseOk;
using quantum::level41pcm::EWaveParseUnsupportedPcm;
using quantum::level41pcm::ParsePcm16Wave;
using quantum::level41pcm::PcmWaveView;
using quantum::level41pcm::StreamBytesFor;
using quantum::level41pcm::VoiceGate;

namespace
{

enum TQuantumLevel41MusicState
{
    EMusicUnopened = 0,
    EMusicOpening,
    EMusicReady,
    EMusicPlaying,
    EMusicFailed
};

typedef void (*TOpenFileLFunction)(CMdaAudioPlayerUtility*, const TDesC&);

const TInt KOpenFileOrdinal = 42;
const TInt KAudioPollIntervalMicroseconds = 20000;
const TInt KWorkerStackSize = 0x8000;
const TInt KWorkerHeapMinimum = 0x10000;
const TInt KWorkerHeapMaximum = 0x200000;
const TInt KMaximumWaveBytes = 2 * 1024 * 1024;

_LIT(KMdaAudioLibraryName, "mediaclientaudio.dll");
_LIT(KStreamLibraryName, "mediaclientaudiostream.dll");
_LIT(KAudioThreadName, "QuantumLevel1Audio");
const TInt KStreamNewLOrdinal = 3;

} // namespace

class CQuantumLevel41PcmAudioWorker;

/* Music callback object, unchanged from the shipped player backend. */
class CQuantumLevel41AudioCallback : public MMdaAudioPlayerCallback
{
public:
    CQuantumLevel41AudioCallback(CQuantumLevel41PcmAudioWorker& aWorker);

    void MapcInitComplete(
        TInt aError,
        const TTimeIntervalMicroSeconds& aDuration) override;
    void MapcPlayComplete(TInt aError) override;

private:
    CQuantumLevel41PcmAudioWorker& iWorker;
};

class CQuantumLevel41PcmAudioWorker : public CBase,
                                      public MMdaAudioOutputStreamCallback
{
public:
    static void operator delete(void* aPointer) { User::Free(aPointer); }
    static CQuantumLevel41PcmAudioWorker* New(CQuantumLevel41PcmAudio& aOwner,
                                              TInt& aError);
    ~CQuantumLevel41PcmAudioWorker();

    void HandleCommand(TInt aCommand);
    void OnInitComplete(TInt aError);
    void OnPlayComplete(TInt aError);
    void ProcessPendingPlayCompletions();
    void Stop();

    /* MMdaAudioOutputStreamCallback: the effects voice. */
    void MaoscOpenComplete(TInt aError) override;
    void MaoscBufferCopied(TInt aError, const TDesC8& aBuffer) override;
    void MaoscPlayComplete(TInt aError) override;

private:
    friend class CQuantumLevel41PcmAudioPoller;

    explicit CQuantumLevel41PcmAudioWorker(CQuantumLevel41PcmAudio& aOwner);
    TInt Construct();

    /* Music: the shipped player path. */
    TInt EnsureMdaLoaded();
    TInt EnsurePlayer();
    TInt OpenFile();
    void PlayMusic();
    void StartMusicPlayback();
    void HandlePlayComplete(TInt aError);

    /* Effects: one PCM voice over one stream. */
    void PrepareEffects();
    void PrepareStreamL();
    void ConfigureStreamL();
    void SubmitL();
    void Fail(TInt aError);
    HBufC8* LoadPcmL(const TDesC& aPath);

    CQuantumLevel41PcmAudio& iOwner;
    CQuantumLevel41PcmAudioPoller* iPoller;

    /* Music members, as shipped. */
    CQuantumLevel41AudioCallback iMusicCallback;
    CMdaAudioPlayerUtility* iMusicPlayer;
    RLibrary iMdaLibrary;
    TOpenFileLFunction iOpenFileFunction;
    TBool iMdaLibraryLoaded;
    TInt iMusicState;
    TInt iMusicError;
    TBool iMusicCompletionPending;
    TInt iMusicCompletionError;

    /* Effects members. */
    RLibrary iStreamLibrary;
    CMdaAudioOutputStream* iStream;
    HBufC8* iShot;
    HBufC8* iReload;
    TPtrC8 iSubmitted;
    TInt iQueue[CQuantumLevel41PcmAudio::KCommandCapacity];
    TInt iHead;
    TInt iCount;
    VoiceGate iGate;
    TTime iLastSubmitTime;
    TBool iStreamLibraryLoaded;
    TBool iReady;
    TBool iConfigured;
    TBool iInFlight;
    TBool iStopping;
    TBool iPrepared;
};

class CQuantumLevel41PcmAudioPoller : public CActive
{
public:
    static void operator delete(void* aPointer) { User::Free(aPointer); }
    static CQuantumLevel41PcmAudioPoller* New(
        CQuantumLevel41PcmAudio& aOwner,
        CQuantumLevel41PcmAudioWorker& aWorker,
        TInt& aError);
    ~CQuantumLevel41PcmAudioPoller();

    void Start();
    void WakeNow();

private:
    CQuantumLevel41PcmAudioPoller(CQuantumLevel41PcmAudio& aOwner,
                                  CQuantumLevel41PcmAudioWorker& aWorker);
    TInt Construct();
    void Schedule();
    void DoCancel() override;
    void RunL() override;
    TInt RunError(TInt aError) override;

    CQuantumLevel41PcmAudio& iOwner;
    CQuantumLevel41PcmAudioWorker& iWorker;
    RTimer iTimer;
    TBool iTimerCreated;
};

CQuantumLevel41AudioCallback::CQuantumLevel41AudioCallback(
    CQuantumLevel41PcmAudioWorker& aWorker)
    : iWorker(aWorker)
{
}

void CQuantumLevel41AudioCallback::MapcInitComplete(
    TInt aError, const TTimeIntervalMicroSeconds& aDuration)
{
    (void)aDuration;
    iWorker.OnInitComplete(aError);
}

void CQuantumLevel41AudioCallback::MapcPlayComplete(TInt aError)
{
    iWorker.OnPlayComplete(aError);
}

CQuantumLevel41PcmAudioWorker::CQuantumLevel41PcmAudioWorker(
    CQuantumLevel41PcmAudio& aOwner)
    : iOwner(aOwner), iPoller(0), iMusicCallback(*this), iMusicPlayer(0),
      iOpenFileFunction(0), iMdaLibraryLoaded(EFalse),
      iMusicState(EMusicUnopened), iMusicError(KErrNone),
      iMusicCompletionPending(EFalse), iMusicCompletionError(KErrNone),
      iStream(0), iShot(0), iReload(0), iSubmitted(), iHead(0), iCount(0),
      iStreamLibraryLoaded(EFalse), iReady(EFalse), iConfigured(EFalse),
      iInFlight(EFalse), iStopping(EFalse), iPrepared(EFalse)
{
}

CQuantumLevel41PcmAudioWorker* CQuantumLevel41PcmAudioWorker::New(
    CQuantumLevel41PcmAudio& aOwner, TInt& aError)
{
    CQuantumLevel41PcmAudioWorker* self =
        new (ELeave) CQuantumLevel41PcmAudioWorker(aOwner);
    aError = self->Construct();
    if (aError != KErrNone) {
        delete self;
        return 0;
    }
    return self;
}

TInt CQuantumLevel41PcmAudioWorker::Construct()
{
    TInt error = KErrNone;
    iPoller = CQuantumLevel41PcmAudioPoller::New(iOwner, *this, error);
    if (iPoller != 0) iPoller->Start();
    return error;
}

CQuantumLevel41PcmAudioWorker::~CQuantumLevel41PcmAudioWorker()
{
    Stop();
    delete iPoller;
    iPoller = 0;
    User::Free(iShot);
    User::Free(iReload);
}

/* ---------------------------------------------------------------- music -- */

TInt CQuantumLevel41PcmAudioWorker::EnsureMdaLoaded()
{
    if (iOpenFileFunction != 0) return KErrNone;

    const TInt loadError = iMdaLibrary.Load(KMdaAudioLibraryName);
    if (loadError != KErrNone) return loadError;
    iMdaLibraryLoaded = ETrue;

    iOpenFileFunction = reinterpret_cast<TOpenFileLFunction>(
        iMdaLibrary.Lookup(KOpenFileOrdinal));
    if (iOpenFileFunction == 0) {
        iMdaLibrary.Close();
        iMdaLibraryLoaded = EFalse;
        return KErrNotFound;
    }
    return KErrNone;
}

TInt CQuantumLevel41PcmAudioWorker::EnsurePlayer()
{
    if (iMusicPlayer != 0) return KErrNone;
    iMusicPlayer = CMdaAudioPlayerUtility::NewL(iMusicCallback);
    return KErrNone;
}

TInt CQuantumLevel41PcmAudioWorker::OpenFile()
{
    const TInt libraryError = EnsureMdaLoaded();
    if (libraryError != KErrNone) return libraryError;

    const TInt playerError = EnsurePlayer();
    if (playerError != KErrNone) return playerError;

    iOpenFileFunction(iMusicPlayer, iOwner.iMusicFile);
    return KErrNone;
}

void CQuantumLevel41PcmAudioWorker::PlayMusic()
{
    if (iStopping) return;

    if (iMusicState == EMusicUnopened) {
        iMusicState = EMusicOpening;
        iOwner.SetMusicStatus(CQuantumLevel41PcmAudio::EAudioOpening,
                              KErrNone);
        const TInt openError = OpenFile();
        if (openError != KErrNone) {
            iMusicState = EMusicFailed;
            iMusicError = openError;
            iOwner.SetMusicStatus(CQuantumLevel41PcmAudio::EAudioFailed,
                                  openError);
        }
    } else if (iMusicState == EMusicReady) {
        StartMusicPlayback();
    } else if (iMusicState == EMusicFailed) {
        iOwner.SetMusicStatus(CQuantumLevel41PcmAudio::EAudioFailed,
                              iMusicError);
    }
}

void CQuantumLevel41PcmAudioWorker::StartMusicPlayback()
{
    if (iStopping) return;
    if (iMusicPlayer == 0 || iMusicState != EMusicReady) return;

    iMusicPlayer->SetRepeats(KMdaRepeatForever, TTimeIntervalMicroSeconds(0));
    iMusicPlayer->Play();
    iMusicState = EMusicPlaying;
    iOwner.SetMusicStatus(CQuantumLevel41PcmAudio::EAudioPlaying, KErrNone);
}

void CQuantumLevel41PcmAudioWorker::OnInitComplete(TInt aError)
{
    if (iStopping) return;

    if (aError != KErrNone) {
        iMusicState = EMusicFailed;
        iMusicError = aError;
        iOwner.SetMusicStatus(CQuantumLevel41PcmAudio::EAudioFailed, aError);
        return;
    }

    iMusicState = EMusicReady;
    StartMusicPlayback();
}

void CQuantumLevel41PcmAudioWorker::OnPlayComplete(TInt aError)
{
    if (iStopping) return;

    /* Keep the MDA completion callback minimal. Process the replay on the
       poller's next worker turn, after MDA has unwound its callback. */
    iMusicCompletionError = aError;
    iMusicCompletionPending = ETrue;
    if (iPoller != 0) iPoller->WakeNow();
}

void CQuantumLevel41PcmAudioWorker::HandlePlayComplete(TInt aError)
{
    if (iStopping) return;

    if (aError != KErrNone) {
        iMusicState = EMusicFailed;
        iMusicError = aError;
        iOwner.SetMusicStatus(CQuantumLevel41PcmAudio::EAudioFailed, aError);
        return;
    }

    iMusicState = EMusicReady;
    /* Some E65 codecs complete despite SetRepeats; replay the already opened
       file without issuing another asynchronous OpenFileL. */
    StartMusicPlayback();
}

void CQuantumLevel41PcmAudioWorker::ProcessPendingPlayCompletions()
{
    if (!iMusicCompletionPending) return;
    const TInt error = iMusicCompletionError;
    iMusicCompletionPending = EFalse;
    HandlePlayComplete(error);
}

/* -------------------------------------------------------------- effects -- */

/*
 * Reads a WAV into a stream-ready buffer: PCM16 mono, 8000 Hz. Decoding and
 * the 16 kHz -> 8 kHz averaging live in quantum_level41_pcm_core.cpp so the
 * host test exercises exactly this code.
 */
HBufC8* CQuantumLevel41PcmAudioWorker::LoadPcmL(const TDesC& aPath)
{
    RFs fileServer;
    User::LeaveIfError(fileServer.Connect());
    CleanupClosePushL(fileServer);

    RFile file;
    User::LeaveIfError(
        file.Open(fileServer, aPath, EFileRead | EFileShareReadersOnly));
    CleanupClosePushL(file);

    TInt size = 0;
    User::LeaveIfError(file.Size(size));
    if (size < 44 || size > KMaximumWaveBytes) User::Leave(KErrCorrupt);

    HBufC8* wave = HBufC8::NewLC(size);
    TPtr8 bytes = wave->Des();
    User::LeaveIfError(file.Read(bytes));
    if (bytes.Length() != size) User::Leave(KErrCorrupt);

    PcmWaveView view;
    const TInt parse = ParsePcm16Wave(bytes.Ptr(), bytes.Length(), view);
    if (parse != EWaveParseOk) {
        User::Leave(parse == EWaveParseUnsupportedPcm ? KErrNotSupported
                                                      : KErrCorrupt);
    }

    const TInt outputSize = static_cast<TInt>(StreamBytesFor(view));
    if (outputSize <= 0) User::Leave(KErrNotSupported);
    HBufC8* output = HBufC8::NewLC(outputSize);
    TPtr8 destination = output->Des();
    destination.SetLength(outputSize);
    if (!CopyToStream(view, const_cast<TUint8*>(destination.Ptr()),
                      destination.Length())) {
        User::Leave(KErrGeneral);
    }

    CleanupStack::Pop(output);
    CleanupStack::PopAndDestroy(wave);
    CleanupStack::PopAndDestroy(&file);
    CleanupStack::PopAndDestroy(&fileServer);
    return output;
}

void CQuantumLevel41PcmAudioWorker::PrepareEffects()
{
    if (iStopping || iPrepared) return;
    iPrepared = ETrue;
    iOwner.SetEffectsStatus(CQuantumLevel41PcmAudio::EAudioOpening, KErrNone);
    PrepareStreamL();
}

void CQuantumLevel41PcmAudioWorker::PrepareStreamL()
{
    iShot = LoadPcmL(iOwner.iShotFile);
    iReload = LoadPcmL(iOwner.iReloadFile);

    /*
     * The output-stream library is loaded at runtime and its factory is
     * called by ordinal, matching how this firmware exposes MDA: a static
     * import of the stream DLL is not available here.
     */
    User::LeaveIfError(iStreamLibrary.Load(KStreamLibraryName));
    iStreamLibraryLoaded = ETrue;
    typedef CMdaAudioOutputStream* (*TNewStream)(
        MMdaAudioOutputStreamCallback&, CMdaServer*);
    TNewStream createStream = reinterpret_cast<TNewStream>(
        iStreamLibrary.Lookup(KStreamNewLOrdinal));
    if (createStream == 0) User::Leave(KErrNotFound);
    iStream = createStream(*this, 0);
    if (iStream == 0) User::Leave(KErrNoMemory);
    iStream->Open(0);
}

void CQuantumLevel41PcmAudioWorker::MaoscOpenComplete(TInt aError)
{
    if (iStopping) return;
    if (aError != KErrNone) {
        Fail(aError);
        return;
    }
    iReady = ETrue;
    iOwner.SetEffectsStatus(CQuantumLevel41PcmAudio::EAudioReady, KErrNone);
    /* A trigger that arrived while the stream was opening is served now. */
    TRAPD(error, SubmitL());
    if (error != KErrNone) Fail(error);
}

void CQuantumLevel41PcmAudioWorker::MaoscBufferCopied(
    TInt aError, const TDesC8& aBuffer)
{
    (void)aBuffer;
    if (iStopping) return;
    /*
     * BufferCopied only releases the descriptor; it is not a playback-ended
     * notification. The next trigger is still gated by the estimated
     * duration of the clip we just handed over.
     */
    iInFlight = EFalse;
    if (aError != KErrNone) {
        Fail(aError);
        return;
    }
    iOwner.SetEffectsStatus(CQuantumLevel41PcmAudio::EAudioReady, KErrNone);
}

void CQuantumLevel41PcmAudioWorker::MaoscPlayComplete(TInt aError)
{
    if (iStopping) return;
    iInFlight = EFalse;
    /* Underflow is expected between clips; it is not a failure. */
    if (aError != KErrNone && aError != KErrUnderflow) {
        Fail(aError);
        return;
    }
    TRAPD(error, SubmitL());
    if (error != KErrNone) Fail(error);
}

void CQuantumLevel41PcmAudioWorker::ConfigureStreamL()
{
    if (iConfigured || iStream == 0 || !iReady) return;
    /*
     * The configuration the reference build runs on the E65: 8 kHz mono
     * PCM16, 16000 bytes per second. The retired SNDDMA port opened 11.025
     * kHz stereo here, which was never verified on the device.
     */
    iStream->SetAudioPropertiesL(TMdaAudioDataSettings::ESampleRate8000Hz,
                                 TMdaAudioDataSettings::EChannelsMono);
    iStream->SetVolume(iStream->MaxVolume() / 2);
    iConfigured = ETrue;
}

void CQuantumLevel41PcmAudioWorker::SubmitL()
{
    if (iStopping || !iReady || iInFlight || iCount == 0 || iStream == 0) {
        return;
    }
    ConfigureStreamL();
    if (!iConfigured) return;

    const TInt command = iQueue[iHead];
    iHead = (iHead + 1) % CQuantumLevel41PcmAudio::KCommandCapacity;
    --iCount;

    iSubmitted.Set(command == CQuantumLevel41PcmAudio::ECommandPlayShot
                       ? *iShot
                       : *iReload);
    iInFlight = ETrue;
    iStream->WriteL(iSubmitted);

    iLastSubmitTime.UniversalTime();
    iGate.RecordSubmit(iSubmitted.Length());
    iOwner.SetEffectsStatus(CQuantumLevel41PcmAudio::EAudioPlaying, KErrNone);
}

void CQuantumLevel41PcmAudioWorker::Fail(TInt aError)
{
    iReady = EFalse;
    iCount = 0;
    iOwner.SetEffectsStatus(CQuantumLevel41PcmAudio::EAudioFailed, aError);
}

/* ------------------------------------------------------------- commands -- */

void CQuantumLevel41PcmAudioWorker::HandleCommand(TInt aCommand)
{
    if (aCommand == CQuantumLevel41PcmAudio::ECommandStartMusic) {
        PlayMusic();
        return;
    }
    if (aCommand == CQuantumLevel41PcmAudio::ECommandPrepareEffects) {
        PrepareEffects();
        return;
    }

    const TBool isShot = aCommand == CQuantumLevel41PcmAudio::ECommandPlayShot;
    const TBool isReload =
        aCommand == CQuantumLevel41PcmAudio::ECommandPlayReload;
    if (!isShot && !isReload) return;
    if (iShot == 0 || iReload == 0 || iStopping) return;

    /*
     * One PCM voice. A trigger that arrives while a clip is in flight or
     * before its estimated end is dropped -- not queued behind it and not
     * mixed with it. This is the "PCM one" policy of the reference build.
     */
    TInt64 elapsed = 0;
    if (iGate.HaveSubmitTime()) {
        TTime now;
        now.UniversalTime();
        elapsed = now.MicroSecondsFrom(iLastSubmitTime).Int64();
    }
    if (!iGate.MaySubmit(iReady != EFalse, iInFlight != EFalse,
                         iGate.HaveSubmitTime(), elapsed)) {
        return;
    }
    if (iCount >= CQuantumLevel41PcmAudio::KCommandCapacity) return;
    iQueue[(iHead + iCount) % CQuantumLevel41PcmAudio::KCommandCapacity] =
        aCommand;
    ++iCount;
    SubmitL();
}

void CQuantumLevel41PcmAudioWorker::Stop()
{
    if (iStopping) return;
    iStopping = ETrue;
    iReady = EFalse;
    iCount = 0;
    if (iPoller != 0) iPoller->Cancel();
    if (iMusicPlayer != 0) {
        iMusicPlayer->Stop();
        delete iMusicPlayer;
        iMusicPlayer = 0;
    }
    if (iStream != 0) {
        iStream->Stop();
        delete iStream;
        iStream = 0;
    }
    /* Libraries close only after the objects created from them are gone. */
    if (iMdaLibraryLoaded) {
        iMdaLibrary.Close();
        iMdaLibraryLoaded = EFalse;
        iOpenFileFunction = 0;
    }
    if (iStreamLibraryLoaded) {
        iStreamLibrary.Close();
        iStreamLibraryLoaded = EFalse;
    }
}

/* --------------------------------------------------------------- poller -- */

CQuantumLevel41PcmAudioPoller::CQuantumLevel41PcmAudioPoller(
    CQuantumLevel41PcmAudio& aOwner,
    CQuantumLevel41PcmAudioWorker& aWorker)
    : CActive(EPriorityStandard), iOwner(aOwner), iWorker(aWorker),
      iTimerCreated(EFalse)
{
    CActiveScheduler::Add(this);
}

CQuantumLevel41PcmAudioPoller* CQuantumLevel41PcmAudioPoller::New(
    CQuantumLevel41PcmAudio& aOwner,
    CQuantumLevel41PcmAudioWorker& aWorker,
    TInt& aError)
{
    CQuantumLevel41PcmAudioPoller* self =
        new (ELeave) CQuantumLevel41PcmAudioPoller(aOwner, aWorker);
    aError = self->Construct();
    if (aError != KErrNone) {
        delete self;
        return 0;
    }
    return self;
}

TInt CQuantumLevel41PcmAudioPoller::Construct()
{
    const TInt error = iTimer.CreateLocal();
    if (error == KErrNone) iTimerCreated = ETrue;
    return error;
}

CQuantumLevel41PcmAudioPoller::~CQuantumLevel41PcmAudioPoller()
{
    Cancel();
    if (iTimerCreated) iTimer.Close();
}

void CQuantumLevel41PcmAudioPoller::Start()
{
    Schedule();
}

void CQuantumLevel41PcmAudioPoller::Schedule()
{
    if (IsActive() || !iTimerCreated) return;
    iTimer.After(iStatus,
                 TTimeIntervalMicroSeconds32(KAudioPollIntervalMicroseconds));
    SetActive();
}

void CQuantumLevel41PcmAudioPoller::WakeNow()
{
    if (!iTimerCreated) return;
    Cancel();
    iTimer.After(iStatus, TTimeIntervalMicroSeconds32(0));
    SetActive();
}

void CQuantumLevel41PcmAudioPoller::DoCancel()
{
    if (iTimerCreated) iTimer.Cancel();
}

void CQuantumLevel41PcmAudioPoller::RunL()
{
    iWorker.ProcessPendingPlayCompletions();

    TInt commands[CQuantumLevel41PcmAudio::KCommandCapacity];
    const TInt count = iOwner.TakeCommands(
        commands, CQuantumLevel41PcmAudio::KCommandCapacity);
    TInt index;
    for (index = 0; index < count; ++index) {
        if (commands[index] == CQuantumLevel41PcmAudio::ECommandStop) {
            iWorker.Stop();
            CActiveScheduler::Stop();
            return;
        }
        TRAPD(error, iWorker.HandleCommand(commands[index]));
        if (error != KErrNone) {
            iOwner.SetEffectsStatus(CQuantumLevel41PcmAudio::EAudioFailed,
                                    error);
        }
    }
    Schedule();
}

TInt CQuantumLevel41PcmAudioPoller::RunError(TInt aError)
{
    iOwner.SetMusicStatus(CQuantumLevel41PcmAudio::EAudioFailed, aError);
    iOwner.SetEffectsStatus(CQuantumLevel41PcmAudio::EAudioFailed, aError);
    iWorker.Stop();
    CActiveScheduler::Stop();
    return KErrNone;
}

/* ---------------------------------------------------------------- owner -- */

CQuantumLevel41PcmAudio* CQuantumLevel41PcmAudio::New(const TDesC& aDrive,
                                                      TInt& aError)
{
    CQuantumLevel41PcmAudio* self = new CQuantumLevel41PcmAudio(aDrive);
    if (self == 0) {
        aError = KErrNoMemory;
        return 0;
    }
    aError = self->Construct();
    if (aError != KErrNone) {
        delete self;
        return 0;
    }
    return self;
}

CQuantumLevel41PcmAudio::CQuantumLevel41PcmAudio(const TDesC& aDrive)
    : iMutexCreated(EFalse), iThreadCreated(EFalse), iQueueHead(0),
      iQueueCount(0), iMusicState(EAudioQueued), iEffectsState(EAudioReady),
      iMusicError(KErrNone), iEffectsError(KErrNone)
{
    /*
     * MDA OpenFileL was confirmed on the E65 with a packaged C:\data WAV, so
     * the audio files stay in the public data area the packaging scripts
     * already install, not the app-private SID directory, which the media
     * server may not be able to read on this firmware.
     */
    iMusicFile.Copy(aDrive);
    iMusicFile.Append(_L("\\data\\q41_wth.mp3"));
    iShotFile.Copy(aDrive);
    iShotFile.Append(_L("\\data\\q41_fn2000.wav"));
    iReloadFile.Copy(aDrive);
    iReloadFile.Append(_L("\\data\\q41_r.wav"));
}

TInt CQuantumLevel41PcmAudio::Construct()
{
    TInt error = iMutex.CreateLocal();
    if (error != KErrNone) return error;
    iMutexCreated = ETrue;
    error = iThread.Create(KAudioThreadName, ThreadEntry, KWorkerStackSize,
                           KWorkerHeapMinimum, KWorkerHeapMaximum, this);
    if (error != KErrNone) return error;
    iThreadCreated = ETrue;
    iThread.Logon(iThreadLogon);
    iThread.Resume();
    return KErrNone;
}

CQuantumLevel41PcmAudio::~CQuantumLevel41PcmAudio()
{
    if (iThreadCreated) {
        iMutex.Wait();
        iQueueHead = 0;
        iQueueCount = 1;
        iCommands[0] = ECommandStop;
        iMutex.Signal();
        /*
         * Shutdown is ordered: the worker deletes its MDA objects before the
         * scheduler stops, and this waits for that thread to exit. Killing the
         * thread with live MDA handles is what the on-device worker-exit
         * panics looked like.
         */
        if (iThreadLogon == KRequestPending) {
            User::WaitForRequest(iThreadLogon);
        }
        iThread.Close();
        iThreadCreated = EFalse;
    }
    if (iMutexCreated) {
        iMutex.Close();
        iMutexCreated = EFalse;
    }
}

TInt CQuantumLevel41PcmAudio::Enqueue(TInt aCommand)
{
    if (!iMutexCreated) return KErrNotReady;
    iMutex.Wait();
    TInt result = KErrNone;
    if (iQueueCount >= KCommandCapacity) {
        result = KErrOverflow;
    } else {
        iCommands[(iQueueHead + iQueueCount) % KCommandCapacity] = aCommand;
        ++iQueueCount;
    }
    iMutex.Signal();
    return result;
}

TInt CQuantumLevel41PcmAudio::TakeCommands(TInt* aCommands, TInt aCapacity)
{
    if (!iMutexCreated || aCommands == 0 || aCapacity <= 0) return 0;
    iMutex.Wait();
    TInt count = iQueueCount < aCapacity ? iQueueCount : aCapacity;
    TInt index;
    for (index = 0; index < count; ++index) {
        aCommands[index] = iCommands[iQueueHead];
        iQueueHead = (iQueueHead + 1) % KCommandCapacity;
    }
    iQueueCount -= count;
    iMutex.Signal();
    return count;
}

void CQuantumLevel41PcmAudio::SetMusicStatus(TInt aState, TInt aError)
{
    iMusicState = aState;
    iMusicError = aError;
}

void CQuantumLevel41PcmAudio::SetEffectsStatus(TInt aState, TInt aError)
{
    iEffectsState = aState;
    iEffectsError = aError;
}

TInt CQuantumLevel41PcmAudio::ThreadEntry(TAny* aSelf)
{
    CQuantumLevel41PcmAudio* self =
        static_cast<CQuantumLevel41PcmAudio*>(aSelf);
    CTrapCleanup* cleanup = CTrapCleanup::New();
    if (cleanup == 0) return KErrNoMemory;
    CActiveScheduler* scheduler = new CActiveScheduler();
    if (scheduler == 0) {
        delete cleanup;
        return KErrNoMemory;
    }
    CActiveScheduler::Install(scheduler);
    TRAPD(error, self->ThreadMainL());
    delete scheduler;
    delete cleanup;
    return error;
}

void CQuantumLevel41PcmAudio::ThreadMainL()
{
    TInt error = KErrNone;
    CQuantumLevel41PcmAudioWorker* worker =
        CQuantumLevel41PcmAudioWorker::New(*this, error);
    if (worker == 0) {
        SetEffectsStatus(EAudioFailed, error);
        return;
    }
    CActiveScheduler::Start();
    delete worker;
}

void CQuantumLevel41PcmAudio::StartLevelMusic()
{
    (void)Enqueue(ECommandPrepareEffects);
    (void)Enqueue(ECommandStartMusic);
}

void CQuantumLevel41PcmAudio::PlayShot()
{
    (void)Enqueue(ECommandPlayShot);
}

void CQuantumLevel41PcmAudio::PlayReload()
{
    (void)Enqueue(ECommandPlayReload);
}

void CQuantumLevel41PcmAudio::StatusLine(TDes& aText)
{
    _LIT(KFormat, "AUD mus %d/%d pcm %d/%d");
    aText.Format(KFormat, iMusicState, iMusicError, iEffectsState,
                 iEffectsError);
}
