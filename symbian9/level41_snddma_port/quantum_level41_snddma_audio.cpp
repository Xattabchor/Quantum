#include "quantum_level41_snddma_audio.h"

#include "quantum_snddma_output.h"

#include <f32file.h>
#include <mdaaudiosampleplayer.h>
#include <mda/common/resource.h>
#include <new>

namespace
{

const TInt KOpenFileOrdinal = 42;
const TInt KAudioPollIntervalMicroseconds = 5000;
const TInt KWorkerStackSize = 0x8000;
const TInt KWorkerHeapMinimum = 0x10000;
const TInt KWorkerHeapMaximum = 0x200000;
const TInt KMaximumWaveBytes = 2 * 1024 * 1024;

typedef void (*TOpenFileLFunction)(CMdaAudioPlayerUtility*, const TDesC&);

_LIT(KMdaAudioLibraryName, "mediaclientaudio.dll");
_LIT(KAudioThreadName, "QuantumLevel1SndDma");

} // namespace

class CQuantumLevel41SndDmaAudioWorker : public CBase,
                                         public MMdaAudioPlayerCallback
{
public:
    static CQuantumLevel41SndDmaAudioWorker* New(
        CQuantumLevel41SndDmaAudio& aOwner, TInt& aError);
    ~CQuantumLevel41SndDmaAudioWorker();

    void HandleCommand(TInt aCommand);
    void ProcessMusicCompletion();
    void UpdateEffectsStatus();
    void Stop();

    void MapcInitComplete(
        TInt aError,
        const TTimeIntervalMicroSeconds& aDuration) override;
    void MapcPlayComplete(TInt aError) override;

private:
    friend class CQuantumLevel41SndDmaAudioPoller;

    enum TOperation
    {
        EOperationNone = 0,
        EOperationMusic,
        EOperationEffects
    };

    explicit CQuantumLevel41SndDmaAudioWorker(
        CQuantumLevel41SndDmaAudio& aOwner);
    TInt Construct();
    TInt EnsureMdaLoaded();
    void HandleLeave(TInt aError);
    HBufC8* LoadWaveL(const TDesC& aPath);
    void PrepareEffects();
    void PrepareEffectsL();
    void PlayMusic();
    void StartMusicPlayback();
    void PlayEffect(TBool aShot);
    void SetMusicFailure(TInt aError);
    void SetEffectsFailure(TInt aError);
    void StopMusic();

    CQuantumLevel41SndDmaAudio& iOwner;
    CQuantumLevel41SndDmaAudioPoller* iPoller;
    CQuantumSndDmaOutput* iOutput;
    CMdaAudioPlayerUtility* iMusicPlayer;
    RLibrary iMdaLibrary;
    RFs iFs;
    HBufC8* iShotWave;
    HBufC8* iReloadWave;
    TOpenFileLFunction iOpenFileFunction;
    TInt iEffectConfigurationError;
    TInt iMusicCompletionError;
    TOperation iCurrentOperation;
    TBool iFsConnected;
    TBool iLibraryLoaded;
    TBool iEffectsConfigured;
    TBool iMusicCompletionPending;
    TBool iStopping;
};

class CQuantumLevel41SndDmaAudioPoller : public CActive
{
public:
    static CQuantumLevel41SndDmaAudioPoller* New(
        CQuantumLevel41SndDmaAudio& aOwner,
        CQuantumLevel41SndDmaAudioWorker& aWorker,
        TInt& aError);
    ~CQuantumLevel41SndDmaAudioPoller();
    void Start();

private:
    CQuantumLevel41SndDmaAudioPoller(
        CQuantumLevel41SndDmaAudio& aOwner,
        CQuantumLevel41SndDmaAudioWorker& aWorker);
    TInt Construct();
    void Schedule();
    void DoCancel() override;
    void RunL() override;
    TInt RunError(TInt aError) override;

    CQuantumLevel41SndDmaAudio& iOwner;
    CQuantumLevel41SndDmaAudioWorker& iWorker;
    RTimer iTimer;
    TBool iTimerCreated;
};

CQuantumLevel41SndDmaAudioWorker::CQuantumLevel41SndDmaAudioWorker(
    CQuantumLevel41SndDmaAudio& aOwner)
    : iOwner(aOwner),
      iPoller(0),
      iOutput(0),
      iMusicPlayer(0),
      iShotWave(0),
      iReloadWave(0),
      iOpenFileFunction(0),
      iEffectConfigurationError(KErrNone),
      iMusicCompletionError(KErrNone),
      iCurrentOperation(EOperationNone),
      iFsConnected(EFalse),
      iLibraryLoaded(EFalse),
      iEffectsConfigured(EFalse),
      iMusicCompletionPending(EFalse),
      iStopping(EFalse)
{
}

CQuantumLevel41SndDmaAudioWorker*
CQuantumLevel41SndDmaAudioWorker::New(
    CQuantumLevel41SndDmaAudio& aOwner,
    TInt& aError)
{
    CQuantumLevel41SndDmaAudioWorker* self =
        new CQuantumLevel41SndDmaAudioWorker(aOwner);
    if (!self) {
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

TInt CQuantumLevel41SndDmaAudioWorker::Construct()
{
    TInt error = iFs.Connect();
    if (error == KErrNone) {
        iFsConnected = ETrue;
        error = iFs.ShareAuto();
        if (error != KErrNone) {
            iFs.Close();
            iFsConnected = EFalse;
        }
    }

    /* Output-stream NewL is deferred to the active poller so a leave is
       handled by RunError without terminating the audio service thread. */
    iPoller = CQuantumLevel41SndDmaAudioPoller::New(
        iOwner, *this, error);
    if (!iPoller) return error;
    iPoller->Start();
    UpdateEffectsStatus();
    return KErrNone;
}

CQuantumLevel41SndDmaAudioWorker::~CQuantumLevel41SndDmaAudioWorker()
{
    Stop();
    delete iPoller;
    iPoller = 0;
    delete iShotWave;
    iShotWave = 0;
    delete iReloadWave;
    iReloadWave = 0;
    if (iFsConnected) {
        iFs.Close();
        iFsConnected = EFalse;
    }
}

HBufC8* CQuantumLevel41SndDmaAudioWorker::LoadWaveL(
    const TDesC& aPath)
{
    if (!iFsConnected) User::Leave(KErrNotReady);

    RFile file;
    User::LeaveIfError(file.Open(iFs, aPath,
                                 EFileRead | EFileShareReadersOnly));
    CleanupClosePushL(file);

    TInt fileSize = 0;
    User::LeaveIfError(file.Size(fileSize));
    if (fileSize <= 0 || fileSize > KMaximumWaveBytes) {
        User::Leave(KErrTooBig);
    }

    HBufC8* wave = HBufC8::NewLC(fileSize);
    TPtr8 waveBytes = wave->Des();
    User::LeaveIfError(file.Read(waveBytes));
    if (waveBytes.Length() != fileSize) User::Leave(KErrCorrupt);

    CleanupStack::PopAndDestroy(&file);
    CleanupStack::Pop(wave);
    return wave;
}

void CQuantumLevel41SndDmaAudioWorker::PrepareEffectsL()
{
    iCurrentOperation = EOperationEffects;
    if (!iOutput) iOutput = CQuantumSndDmaOutput::NewL();
    if (!iShotWave) iShotWave = LoadWaveL(iOwner.iShotFile);
    if (!iReloadWave) iReloadWave = LoadWaveL(iOwner.iReloadFile);

    User::LeaveIfError(
        iOutput->SetWave(CQuantumSndDmaOutput::EShotEffect, *iShotWave));
    User::LeaveIfError(
        iOutput->SetWave(CQuantumSndDmaOutput::EReloadEffect, *iReloadWave));
    iEffectsConfigured = ETrue;
    iEffectConfigurationError = KErrNone;
    iCurrentOperation = EOperationNone;
}

void CQuantumLevel41SndDmaAudioWorker::PrepareEffects()
{
    if (iStopping || iEffectsConfigured) return;
    PrepareEffectsL();
    UpdateEffectsStatus();
}

TInt CQuantumLevel41SndDmaAudioWorker::EnsureMdaLoaded()
{
    if (iOpenFileFunction) return KErrNone;

    TInt error = iMdaLibrary.Load(KMdaAudioLibraryName);
    if (error != KErrNone) return error;
    iLibraryLoaded = ETrue;
    iOpenFileFunction = reinterpret_cast<TOpenFileLFunction>(
        iMdaLibrary.Lookup(KOpenFileOrdinal));
    if (!iOpenFileFunction) {
        iMdaLibrary.Close();
        iLibraryLoaded = EFalse;
        return KErrNotFound;
    }
    return KErrNone;
}

void CQuantumLevel41SndDmaAudioWorker::PlayMusic()
{
    if (iStopping) return;
    if (iMusicPlayer) return;

    iCurrentOperation = EOperationMusic;
    const TInt loadError = EnsureMdaLoaded();
    if (loadError != KErrNone) {
        iCurrentOperation = EOperationNone;
        SetMusicFailure(loadError);
        return;
    }

    iMusicPlayer = CMdaAudioPlayerUtility::NewL(*this);
    if (!iMusicPlayer) {
        iCurrentOperation = EOperationNone;
        SetMusicFailure(KErrNoMemory);
        return;
    }

    iOwner.SetMusicStatus(CQuantumLevel41SndDmaAudio::EAudioOpening,
                          KErrNone);
    iOpenFileFunction(iMusicPlayer, iOwner.iMusicFile);
    iCurrentOperation = EOperationNone;
}

void CQuantumLevel41SndDmaAudioWorker::StartMusicPlayback()
{
    if (iStopping || !iMusicPlayer) return;
    iMusicPlayer->SetRepeats(KMdaRepeatForever, TTimeIntervalMicroSeconds(0));
    iMusicPlayer->Play();
    iOwner.SetMusicStatus(CQuantumLevel41SndDmaAudio::EAudioPlaying,
                          KErrNone);
}

void CQuantumLevel41SndDmaAudioWorker::PlayEffect(TBool aShot)
{
    if (iStopping) return;
    if (!iEffectsConfigured) {
        PrepareEffects();
        if (!iEffectsConfigured) return;
    }
    if (!iOutput) {
        SetEffectsFailure(KErrNotReady);
        return;
    }

    const TInt error = aShot ? iOutput->PlayShot() : iOutput->PlayReload();
    if (error != KErrNone) {
        SetEffectsFailure(error);
    } else {
        UpdateEffectsStatus();
    }
}

void CQuantumLevel41SndDmaAudioWorker::HandleCommand(TInt aCommand)
{
    if (aCommand == CQuantumLevel41SndDmaAudio::ECommandPrepareEffects) {
        PrepareEffects();
    } else if (aCommand == CQuantumLevel41SndDmaAudio::ECommandStartMusic) {
        PlayMusic();
    } else if (aCommand == CQuantumLevel41SndDmaAudio::ECommandPlayShot) {
        PlayEffect(ETrue);
    } else if (aCommand == CQuantumLevel41SndDmaAudio::ECommandPlayReload) {
        PlayEffect(EFalse);
    }
}

void CQuantumLevel41SndDmaAudioWorker::HandleLeave(TInt aError)
{
    if (iCurrentOperation == EOperationMusic) {
        SetMusicFailure(aError);
    } else if (iCurrentOperation == EOperationEffects) {
        SetEffectsFailure(aError);
    } else {
        SetMusicFailure(aError);
        SetEffectsFailure(aError);
    }
    iCurrentOperation = EOperationNone;
}

void CQuantumLevel41SndDmaAudioWorker::MapcInitComplete(
    TInt aError,
    const TTimeIntervalMicroSeconds& aDuration)
{
    (void)aDuration;
    if (iStopping) return;
    if (aError != KErrNone) {
        SetMusicFailure(aError);
        return;
    }
    StartMusicPlayback();
}

void CQuantumLevel41SndDmaAudioWorker::MapcPlayComplete(TInt aError)
{
    if (iStopping) return;
    /* Re-enter Play only after the MDA callback has returned. */
    iMusicCompletionError = aError;
    iMusicCompletionPending = ETrue;
}

void CQuantumLevel41SndDmaAudioWorker::ProcessMusicCompletion()
{
    if (iStopping || !iMusicCompletionPending) return;
    const TInt error = iMusicCompletionError;
    iMusicCompletionPending = EFalse;
    if (error != KErrNone) {
        SetMusicFailure(error);
        return;
    }
    StartMusicPlayback();
}

void CQuantumLevel41SndDmaAudioWorker::UpdateEffectsStatus()
{
    if (iEffectConfigurationError != KErrNone) {
        SetEffectsFailure(iEffectConfigurationError);
        return;
    }
    if (!iOutput) {
        iOwner.SetEffectsStatus(CQuantumLevel41SndDmaAudio::EAudioOpening,
                                KErrNone);
        return;
    }
    const TInt outputError = iOutput->LastError();
    if (outputError != KErrNone) {
        SetEffectsFailure(outputError);
    } else if (!iEffectsConfigured || !iOutput->IsReady()) {
        iOwner.SetEffectsStatus(CQuantumLevel41SndDmaAudio::EAudioOpening,
                                KErrNone);
    } else if (iOutput->ActiveVoiceCount() != 0) {
        iOwner.SetEffectsStatus(CQuantumLevel41SndDmaAudio::EAudioPlaying,
                                KErrNone);
    } else {
        iOwner.SetEffectsStatus(CQuantumLevel41SndDmaAudio::EAudioReady,
                                KErrNone);
    }
}

void CQuantumLevel41SndDmaAudioWorker::SetMusicFailure(TInt aError)
{
    if (aError == KErrNone) aError = KErrGeneral;
    iOwner.SetMusicStatus(CQuantumLevel41SndDmaAudio::EAudioFailed, aError);
}

void CQuantumLevel41SndDmaAudioWorker::SetEffectsFailure(TInt aError)
{
    if (aError == KErrNone) aError = KErrGeneral;
    iEffectConfigurationError = aError;
    iOwner.SetEffectsStatus(CQuantumLevel41SndDmaAudio::EAudioFailed, aError);
}

void CQuantumLevel41SndDmaAudioWorker::StopMusic()
{
    if (iMusicPlayer) {
        iMusicPlayer->Stop();
        delete iMusicPlayer;
        iMusicPlayer = 0;
    }
    if (iLibraryLoaded) {
        iMdaLibrary.Close();
        iLibraryLoaded = EFalse;
    }
    iOpenFileFunction = 0;
    iMusicCompletionPending = EFalse;
}

void CQuantumLevel41SndDmaAudioWorker::Stop()
{
    if (iStopping) return;
    iStopping = ETrue;
    StopMusic();
    if (iOutput) {
        iOutput->Stop();
        delete iOutput;
        iOutput = 0;
    }
    iEffectsConfigured = EFalse;
}

CQuantumLevel41SndDmaAudioPoller::CQuantumLevel41SndDmaAudioPoller(
    CQuantumLevel41SndDmaAudio& aOwner,
    CQuantumLevel41SndDmaAudioWorker& aWorker)
    : CActive(CActive::EPriorityStandard),
      iOwner(aOwner),
      iWorker(aWorker),
      iTimerCreated(EFalse)
{
    CActiveScheduler::Add(this);
}

CQuantumLevel41SndDmaAudioPoller*
CQuantumLevel41SndDmaAudioPoller::New(
    CQuantumLevel41SndDmaAudio& aOwner,
    CQuantumLevel41SndDmaAudioWorker& aWorker,
    TInt& aError)
{
    CQuantumLevel41SndDmaAudioPoller* self =
        new CQuantumLevel41SndDmaAudioPoller(aOwner, aWorker);
    if (!self) {
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

TInt CQuantumLevel41SndDmaAudioPoller::Construct()
{
    const TInt error = iTimer.CreateLocal();
    if (error == KErrNone) iTimerCreated = ETrue;
    return error;
}

CQuantumLevel41SndDmaAudioPoller::~CQuantumLevel41SndDmaAudioPoller()
{
    Cancel();
    if (iTimerCreated) iTimer.Close();
}

void CQuantumLevel41SndDmaAudioPoller::Start()
{
    Schedule();
}

void CQuantumLevel41SndDmaAudioPoller::Schedule()
{
    if (IsActive()) return;
    iTimer.After(iStatus,
                 TTimeIntervalMicroSeconds32(KAudioPollIntervalMicroseconds));
    SetActive();
}

void CQuantumLevel41SndDmaAudioPoller::DoCancel()
{
    iTimer.Cancel();
}

void CQuantumLevel41SndDmaAudioPoller::RunL()
{
    iWorker.ProcessMusicCompletion();

    TInt commands[CQuantumLevel41SndDmaAudio::KCommandCapacity];
    const TInt count = iOwner.TakeCommands(
        commands, CQuantumLevel41SndDmaAudio::KCommandCapacity);
    TInt index;
    for (index = 0; index < count; ++index) {
        if (commands[index] == CQuantumLevel41SndDmaAudio::ECommandStop) {
            iWorker.Stop();
            CActiveScheduler::Stop();
            return;
        }
        iWorker.HandleCommand(commands[index]);
    }

    iWorker.UpdateEffectsStatus();
    Schedule();
}

TInt CQuantumLevel41SndDmaAudioPoller::RunError(TInt aError)
{
    iWorker.HandleLeave(aError);
    iWorker.UpdateEffectsStatus();
    Schedule();
    return KErrNone;
}

CQuantumLevel41SndDmaAudio* CQuantumLevel41SndDmaAudio::New(
    const TDesC& aDrive,
    TInt& aError)
{
    CQuantumLevel41SndDmaAudio* self =
        new CQuantumLevel41SndDmaAudio(aDrive);
    if (!self) {
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

CQuantumLevel41SndDmaAudio::CQuantumLevel41SndDmaAudio(
    const TDesC& aDrive)
    : iMutexCreated(EFalse),
      iThreadCreated(EFalse),
      iQueueHead(0),
      iQueueCount(0),
      iMusicState(EAudioQueued),
      iEffectsState(EAudioQueued),
      iMusicError(KErrNone),
      iEffectsError(KErrNone)
{
    iMusicFile.Copy(aDrive);
    iMusicFile.Append(_L("\\data\\q41_wth.mp3"));
    iShotFile.Copy(aDrive);
    iShotFile.Append(_L("\\data\\q41_fn2000.wav"));
    iReloadFile.Copy(aDrive);
    iReloadFile.Append(_L("\\data\\q41_r.wav"));
}

TInt CQuantumLevel41SndDmaAudio::Construct()
{
    TInt error = iMutex.CreateLocal();
    if (error != KErrNone) return error;
    iMutexCreated = ETrue;

    error = iThread.Create(
        KAudioThreadName, ThreadEntry, KWorkerStackSize,
        KWorkerHeapMinimum, KWorkerHeapMaximum, this);
    if (error != KErrNone) return error;

    iThreadCreated = ETrue;
    iThread.Logon(iThreadLogon);
    iThread.Resume();
    return KErrNone;
}

CQuantumLevel41SndDmaAudio::~CQuantumLevel41SndDmaAudio()
{
    if (iThreadCreated) {
        iMutex.Wait();
        iQueueHead = 0;
        iQueueCount = 1;
        iCommands[0] = ECommandStop;
        iMutex.Signal();
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

TInt CQuantumLevel41SndDmaAudio::Enqueue(TCommand aCommand)
{
    if (!iMutexCreated || !iThreadCreated) return KErrNotReady;
    iMutex.Wait();
    if (iQueueCount >= KCommandCapacity) {
        iMutex.Signal();
        return KErrOverflow;
    }
    const TInt tail = (iQueueHead + iQueueCount) % KCommandCapacity;
    iCommands[tail] = static_cast<TInt>(aCommand);
    ++iQueueCount;
    iMutex.Signal();
    return KErrNone;
}

TInt CQuantumLevel41SndDmaAudio::TakeCommands(
    TInt* aCommands,
    TInt aCapacity)
{
    if (!aCommands || aCapacity <= 0 || !iMutexCreated) return 0;
    iMutex.Wait();
    TInt count = 0;
    while (count < aCapacity && iQueueCount > 0) {
        aCommands[count] = iCommands[iQueueHead];
        iQueueHead = (iQueueHead + 1) % KCommandCapacity;
        --iQueueCount;
        ++count;
    }
    iMutex.Signal();
    return count;
}

void CQuantumLevel41SndDmaAudio::SetMusicStatus(
    TAudioState aState,
    TInt aError)
{
    if (!iMutexCreated) return;
    iMutex.Wait();
    iMusicState = aState;
    iMusicError = aError;
    iMutex.Signal();
}

void CQuantumLevel41SndDmaAudio::SetEffectsStatus(
    TAudioState aState,
    TInt aError)
{
    if (!iMutexCreated) return;
    iMutex.Wait();
    iEffectsState = aState;
    iEffectsError = aError;
    iMutex.Signal();
}

void CQuantumLevel41SndDmaAudio::StartLevelMusic()
{
    (void)Enqueue(ECommandPrepareEffects);
    (void)Enqueue(ECommandStartMusic);
}

void CQuantumLevel41SndDmaAudio::PlayShot()
{
    (void)Enqueue(ECommandPlayShot);
}

void CQuantumLevel41SndDmaAudio::PlayReload()
{
    (void)Enqueue(ECommandPlayReload);
}

void CQuantumLevel41SndDmaAudio::StatusLine(TDes& aText)
{
    TAudioState musicState;
    TAudioState effectsState;
    TInt musicError;
    TInt effectsError;

    if (!iMutexCreated) {
        aText.Copy(_L("AUDIO SNDDMA: NO WORKER"));
        return;
    }
    iMutex.Wait();
    musicState = iMusicState;
    effectsState = iEffectsState;
    musicError = iMusicError;
    effectsError = iEffectsError;
    iMutex.Signal();

    TBuf<12> music;
    TBuf<12> effects;
    if (musicState == EAudioFailed) {
        music.Format(_L("E%d"), musicError);
    } else if (musicState == EAudioPlaying) {
        music.Copy(_L("PLAY"));
    } else if (musicState == EAudioOpening) {
        music.Copy(_L("OPEN"));
    } else if (musicState == EAudioReady) {
        music.Copy(_L("READY"));
    } else {
        music.Copy(_L("QUEUE"));
    }

    if (effectsState == EAudioFailed) {
        effects.Format(_L("E%d"), effectsError);
    } else if (effectsState == EAudioPlaying) {
        effects.Copy(_L("PLAY"));
    } else if (effectsState == EAudioOpening) {
        effects.Copy(_L("OPEN"));
    } else if (effectsState == EAudioQueued) {
        effects.Copy(_L("QUEUE"));
    } else {
        effects.Copy(_L("READY"));
    }

    TBuf<56> status;
    status.Format(_L("SNDDMA FX:%S M:%S MIDI OFF"), &effects, &music);
    aText.Copy(status);
}

TInt CQuantumLevel41SndDmaAudio::ThreadEntry(TAny* aData)
{
    CQuantumLevel41SndDmaAudio& owner =
        *static_cast<CQuantumLevel41SndDmaAudio*>(aData);
    CTrapCleanup* cleanup = CTrapCleanup::New();
    if (!cleanup) {
        owner.SetMusicStatus(EAudioFailed, KErrNoMemory);
        owner.SetEffectsStatus(EAudioFailed, KErrNoMemory);
        return KErrNoMemory;
    }

    const TInt criticalError = User::SetCritical(User::ENotCritical);
    if (criticalError != KErrNone) {
        owner.SetMusicStatus(EAudioFailed, criticalError);
        owner.SetEffectsStatus(EAudioFailed, criticalError);
        delete cleanup;
        return criticalError;
    }

    CActiveScheduler* scheduler = new CActiveScheduler;
    if (!scheduler) {
        owner.SetMusicStatus(EAudioFailed, KErrNoMemory);
        owner.SetEffectsStatus(EAudioFailed, KErrNoMemory);
        delete cleanup;
        return KErrNoMemory;
    }
    CActiveScheduler::Install(scheduler);

    TInt workerError = KErrNone;
    CQuantumLevel41SndDmaAudioWorker* worker =
        CQuantumLevel41SndDmaAudioWorker::New(owner, workerError);
    if (workerError == KErrNone && worker) {
        CActiveScheduler::Start();
    } else {
        owner.SetMusicStatus(EAudioFailed, workerError);
        owner.SetEffectsStatus(EAudioFailed, workerError);
    }

    delete worker;
    CActiveScheduler::Install(0);
    delete scheduler;
    delete cleanup;
    return workerError;
}
