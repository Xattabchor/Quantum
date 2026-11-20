#include "quantum_level41_audio.h"

#include <mdaaudiosampleplayer.h>
#include <mda/common/resource.h>

namespace
{

enum TQuantumLevel41AudioTrack
{
    EAudioTrackMusic = 0,
    EAudioTrackShot,
    EAudioTrackReload
};

enum TQuantumLevel41AudioPlayerState
{
    EAudioPlayerUnopened = 0,
    EAudioPlayerOpening,
    EAudioPlayerReady,
    EAudioPlayerPlaying,
    EAudioPlayerFailed
};

const TInt KOpenFileOrdinal = 42;
const TInt KAudioPollIntervalMicroseconds = 20000;
const TInt KWorkerStackSize = 0x8000;
const TInt KWorkerHeapMinimum = 0x10000;
const TInt KWorkerHeapMaximum = 0x200000;

typedef void (*TOpenFileLFunction)(CMdaAudioPlayerUtility*, const TDesC&);

_LIT(KMdaAudioLibraryName, "mediaclientaudio.dll");
_LIT(KAudioThreadName, "QuantumLevel1Audio");

} // namespace

class CQuantumLevel41AudioCallback : public MMdaAudioPlayerCallback
{
public:
    CQuantumLevel41AudioCallback(CQuantumLevel41AudioWorker& aWorker,
                                 TQuantumLevel41AudioTrack aTrack);

    void MapcInitComplete(
        TInt aError,
        const TTimeIntervalMicroSeconds& aDuration) override;
    void MapcPlayComplete(TInt aError) override;

private:
    CQuantumLevel41AudioWorker& iWorker;
    TQuantumLevel41AudioTrack iTrack;
};

class CQuantumLevel41AudioWorker : public CBase
{
public:
    static CQuantumLevel41AudioWorker* New(
        CQuantumLevel41Audio& aOwner, TInt& aError);
    ~CQuantumLevel41AudioWorker();

    void HandleCommand(TInt aCommand);
    void OnInitComplete(TQuantumLevel41AudioTrack aTrack, TInt aError);
    void OnPlayComplete(TQuantumLevel41AudioTrack aTrack, TInt aError);
    void ProcessPendingInitCompletions();
    void ProcessPendingPlayCompletions();
    void Stop();

private:
    friend class CQuantumLevel41AudioPoller;

    explicit CQuantumLevel41AudioWorker(CQuantumLevel41Audio& aOwner);
    TInt Construct();
    TInt EnsureMdaLoaded();
    TInt EnsurePlayer(TQuantumLevel41AudioTrack aTrack);
    TInt OpenFile(TQuantumLevel41AudioTrack aTrack);
    void PlayMusic();
    void PrepareEffects();
    void PrepareEffect(TQuantumLevel41AudioTrack aTrack);
    void PlayEffect(TQuantumLevel41AudioTrack aTrack);
    void StartPlayback(TQuantumLevel41AudioTrack aTrack);
    void HandleInitComplete(TQuantumLevel41AudioTrack aTrack, TInt aError);
    void HandlePlayComplete(TQuantumLevel41AudioTrack aTrack, TInt aError);
    void UpdateEffectsStatus();
    void StopPlayers();
    CMdaAudioPlayerUtility*& Player(TQuantumLevel41AudioTrack aTrack);
    TQuantumLevel41AudioPlayerState& PlayerState(
        TQuantumLevel41AudioTrack aTrack);
    TBool& PendingPlayback(TQuantumLevel41AudioTrack aTrack);
    TBool& HasCompletedPlayback(TQuantumLevel41AudioTrack aTrack);
    TBool& PlayCompletionPending(TQuantumLevel41AudioTrack aTrack);
    TInt& PlayCompletionError(TQuantumLevel41AudioTrack aTrack);
    const TDesC& AudioPath(TQuantumLevel41AudioTrack aTrack) const;

    CQuantumLevel41Audio& iOwner;
    CQuantumLevel41AudioPoller* iPoller;
    CQuantumLevel41AudioCallback iMusicCallback;
    CQuantumLevel41AudioCallback iShotCallback;
    CQuantumLevel41AudioCallback iReloadCallback;
    CMdaAudioPlayerUtility* iMusicPlayer;
    CMdaAudioPlayerUtility* iShotPlayer;
    CMdaAudioPlayerUtility* iReloadPlayer;
    RLibrary iMdaLibrary;
    TOpenFileLFunction iOpenFileFunction;
    TBool iLibraryLoaded;
    TQuantumLevel41AudioPlayerState iMusicPlayerState;
    TQuantumLevel41AudioPlayerState iShotPlayerState;
    TQuantumLevel41AudioPlayerState iReloadPlayerState;
    TBool iShotPlaybackPending;
    TBool iReloadPlaybackPending;
    TBool iShotHasCompletedPlayback;
    TBool iReloadHasCompletedPlayback;
    TInt iMusicError;
    TInt iShotError;
    TInt iReloadError;
    TBool iMusicCompletionPending;
    TBool iShotCompletionPending;
    TBool iReloadCompletionPending;
    TInt iMusicCompletionError;
    TInt iShotCompletionError;
    TInt iReloadCompletionError;
    TBool iInitPending[3];
    TInt iInitError[3];
    TBool iStopping;
};

class CQuantumLevel41AudioPoller : public CActive
{
public:
    static CQuantumLevel41AudioPoller* New(
        CQuantumLevel41Audio& aOwner,
        CQuantumLevel41AudioWorker& aWorker,
        TInt& aError);
    ~CQuantumLevel41AudioPoller();
    void Start();

private:
    CQuantumLevel41AudioPoller(CQuantumLevel41Audio& aOwner,
                               CQuantumLevel41AudioWorker& aWorker);
    TInt Construct();
    void Schedule();
    void DoCancel() override;
    void RunL() override;
    TInt RunError(TInt aError) override;

    CQuantumLevel41Audio& iOwner;
    CQuantumLevel41AudioWorker& iWorker;
    RTimer iTimer;
    TBool iTimerCreated;
};

CQuantumLevel41AudioCallback::CQuantumLevel41AudioCallback(
    CQuantumLevel41AudioWorker& aWorker,
    TQuantumLevel41AudioTrack aTrack)
    : iWorker(aWorker), iTrack(aTrack)
{
}

void CQuantumLevel41AudioCallback::MapcInitComplete(
    TInt aError,
    const TTimeIntervalMicroSeconds& aDuration)
{
    (void)aDuration;
    iWorker.OnInitComplete(iTrack, aError);
}

void CQuantumLevel41AudioCallback::MapcPlayComplete(TInt aError)
{
    iWorker.OnPlayComplete(iTrack, aError);
}

CQuantumLevel41AudioWorker::CQuantumLevel41AudioWorker(
    CQuantumLevel41Audio& aOwner)
    : iOwner(aOwner),
      iPoller(0),
      iMusicCallback(*this, EAudioTrackMusic),
      iShotCallback(*this, EAudioTrackShot),
      iReloadCallback(*this, EAudioTrackReload),
      iMusicPlayer(0),
      iShotPlayer(0),
      iReloadPlayer(0),
      iOpenFileFunction(0),
      iLibraryLoaded(EFalse),
      iMusicPlayerState(EAudioPlayerUnopened),
      iShotPlayerState(EAudioPlayerUnopened),
      iReloadPlayerState(EAudioPlayerUnopened),
      iShotPlaybackPending(EFalse),
      iReloadPlaybackPending(EFalse),
      iShotHasCompletedPlayback(EFalse),
      iReloadHasCompletedPlayback(EFalse),
      iMusicError(KErrNone),
      iShotError(KErrNone),
      iReloadError(KErrNone),
      iMusicCompletionPending(EFalse),
      iShotCompletionPending(EFalse),
      iReloadCompletionPending(EFalse),
      iMusicCompletionError(KErrNone),
      iShotCompletionError(KErrNone),
      iReloadCompletionError(KErrNone),
      iStopping(EFalse)
{
    for (TInt i = 0; i < 3; ++i) { iInitPending[i] = EFalse; iInitError[i] = KErrNotReady; }
}

CQuantumLevel41AudioWorker* CQuantumLevel41AudioWorker::New(
    CQuantumLevel41Audio& aOwner,
    TInt& aError)
{
    CQuantumLevel41AudioWorker* self =
        new CQuantumLevel41AudioWorker(aOwner);
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

TInt CQuantumLevel41AudioWorker::Construct()
{
    TInt error = KErrNone;
    iPoller = CQuantumLevel41AudioPoller::New(iOwner, *this, error);
    if (iPoller == 0) return error;
    iPoller->Start();
    return KErrNone;
}

CQuantumLevel41AudioWorker::~CQuantumLevel41AudioWorker()
{
    StopPlayers();
    delete iPoller;
}

CMdaAudioPlayerUtility*& CQuantumLevel41AudioWorker::Player(
    TQuantumLevel41AudioTrack aTrack)
{
    if (aTrack == EAudioTrackMusic) return iMusicPlayer;
    if (aTrack == EAudioTrackShot) return iShotPlayer;
    return iReloadPlayer;
}

TQuantumLevel41AudioPlayerState& CQuantumLevel41AudioWorker::PlayerState(
    TQuantumLevel41AudioTrack aTrack)
{
    if (aTrack == EAudioTrackMusic) return iMusicPlayerState;
    if (aTrack == EAudioTrackShot) return iShotPlayerState;
    return iReloadPlayerState;
}

TBool& CQuantumLevel41AudioWorker::PendingPlayback(
    TQuantumLevel41AudioTrack aTrack)
{
    return aTrack == EAudioTrackShot
        ? iShotPlaybackPending : iReloadPlaybackPending;
}

TBool& CQuantumLevel41AudioWorker::HasCompletedPlayback(
    TQuantumLevel41AudioTrack aTrack)
{
    return aTrack == EAudioTrackShot
        ? iShotHasCompletedPlayback : iReloadHasCompletedPlayback;
}

TBool& CQuantumLevel41AudioWorker::PlayCompletionPending(
    TQuantumLevel41AudioTrack aTrack)
{
    if (aTrack == EAudioTrackMusic) return iMusicCompletionPending;
    if (aTrack == EAudioTrackShot) return iShotCompletionPending;
    return iReloadCompletionPending;
}

TInt& CQuantumLevel41AudioWorker::PlayCompletionError(
    TQuantumLevel41AudioTrack aTrack)
{
    if (aTrack == EAudioTrackMusic) return iMusicCompletionError;
    if (aTrack == EAudioTrackShot) return iShotCompletionError;
    return iReloadCompletionError;
}

const TDesC& CQuantumLevel41AudioWorker::AudioPath(
    TQuantumLevel41AudioTrack aTrack) const
{
    if (aTrack == EAudioTrackMusic) return iOwner.iMusicFile;
    if (aTrack == EAudioTrackShot) return iOwner.iShotFile;
    return iOwner.iReloadFile;
}

TInt CQuantumLevel41AudioWorker::EnsureMdaLoaded()
{
    if (iOpenFileFunction != 0) return KErrNone;

    const TInt loadError = iMdaLibrary.Load(KMdaAudioLibraryName);
    if (loadError != KErrNone) return loadError;
    iLibraryLoaded = ETrue;

    iOpenFileFunction = reinterpret_cast<TOpenFileLFunction>(
        iMdaLibrary.Lookup(KOpenFileOrdinal));
    if (iOpenFileFunction == 0) {
        iMdaLibrary.Close();
        iLibraryLoaded = EFalse;
        return KErrNotFound;
    }
    return KErrNone;
}

TInt CQuantumLevel41AudioWorker::EnsurePlayer(
    TQuantumLevel41AudioTrack aTrack)
{
    CMdaAudioPlayerUtility*& player = Player(aTrack);
    if (player != 0) return KErrNone;

    CQuantumLevel41AudioCallback& callback =
        aTrack == EAudioTrackMusic ? iMusicCallback :
        aTrack == EAudioTrackShot ? iShotCallback : iReloadCallback;
    player = CMdaAudioPlayerUtility::NewL(callback);
    return KErrNone;
}

TInt CQuantumLevel41AudioWorker::OpenFile(
    TQuantumLevel41AudioTrack aTrack)
{
    const TInt libraryError = EnsureMdaLoaded();
    if (libraryError != KErrNone) return libraryError;

    const TInt playerError = EnsurePlayer(aTrack);
    if (playerError != KErrNone) return playerError;

    if (aTrack == EAudioTrackShot) __atomic_store_n(&iOwner.iTrace[CQuantumLevel41Audio::EOpen], 1, __ATOMIC_SEQ_CST);
    iOpenFileFunction(Player(aTrack), AudioPath(aTrack));
    return KErrNone;
}

void CQuantumLevel41AudioWorker::PlayMusic()
{
    if (iStopping) return;

    TQuantumLevel41AudioPlayerState& state =
        PlayerState(EAudioTrackMusic);
    if (state == EAudioPlayerUnopened) {
        state = EAudioPlayerOpening;
        iOwner.SetMusicStatus(EQuantumLevel41AudioOpening, KErrNone);
        const TInt openError = OpenFile(EAudioTrackMusic);
        if (openError != KErrNone) {
            state = EAudioPlayerFailed;
            iMusicError = openError;
            iOwner.SetMusicStatus(EQuantumLevel41AudioFailed, openError);
        }
    } else if (state == EAudioPlayerReady) {
        StartPlayback(EAudioTrackMusic);
    } else if (state == EAudioPlayerFailed) {
        iOwner.SetMusicStatus(EQuantumLevel41AudioFailed, iMusicError);
    }
}

void CQuantumLevel41AudioWorker::PrepareEffect(
    TQuantumLevel41AudioTrack aTrack)
{
    if (iStopping || aTrack == EAudioTrackMusic) return;

    TQuantumLevel41AudioPlayerState& state = PlayerState(aTrack);
    if (state != EAudioPlayerUnopened) return;

    state = EAudioPlayerOpening;
    UpdateEffectsStatus();
    const TInt openError = OpenFile(aTrack);
    if (openError != KErrNone) {
        state = EAudioPlayerFailed;
        PendingPlayback(aTrack) = EFalse;
        HasCompletedPlayback(aTrack) = EFalse;
        if (aTrack == EAudioTrackShot) iShotError = openError;
        else iReloadError = openError;
        UpdateEffectsStatus();
    }
}

void CQuantumLevel41AudioWorker::PrepareEffects()
{
    if (iStopping) return;
    PrepareEffect(EAudioTrackShot);
    PrepareEffect(EAudioTrackReload);
}

void CQuantumLevel41AudioWorker::PlayEffect(
    TQuantumLevel41AudioTrack aTrack)
{
    if (iStopping || aTrack == EAudioTrackMusic) return;

    TQuantumLevel41AudioPlayerState& state = PlayerState(aTrack);
    if (state == EAudioPlayerUnopened) {
        /* Open asynchronously and remember only the first trigger. */
        PendingPlayback(aTrack) = ETrue;
        PrepareEffect(aTrack);
    } else if (state == EAudioPlayerOpening) {
        /* A pre-open is in progress; coalesce one shot until it is ready. */
        PendingPlayback(aTrack) = ETrue;
    } else if (state == EAudioPlayerPlaying) {
        /* Never restart a player from inside an outstanding Play request. */
        return;
    } else if (state == EAudioPlayerReady) {
        StartPlayback(aTrack);
    } else {
        UpdateEffectsStatus();
    }
}

void CQuantumLevel41AudioWorker::HandleCommand(TInt aCommand)
{
    if (aCommand == CQuantumLevel41Audio::ECommandStartMusic) {
        PlayMusic();
    } else if (aCommand == CQuantumLevel41Audio::ECommandPlayShot) {
        PlayEffect(EAudioTrackShot);
    } else if (aCommand == CQuantumLevel41Audio::ECommandPlayReload) {
        PlayEffect(EAudioTrackReload);
    } else if (aCommand == CQuantumLevel41Audio::ECommandPrepareEffects) {
        PrepareEffects();
    }
}

void CQuantumLevel41AudioWorker::StartPlayback(
    TQuantumLevel41AudioTrack aTrack)
{
    if (iStopping) return;
    CMdaAudioPlayerUtility* player = Player(aTrack);
    TQuantumLevel41AudioPlayerState& state = PlayerState(aTrack);
    if (player == 0 || state != EAudioPlayerReady) return;

    if (aTrack == EAudioTrackMusic) {
        player->SetRepeats(KMdaRepeatForever, TTimeIntervalMicroSeconds(0));
        player->Play();
        state = EAudioPlayerPlaying;
        iOwner.SetMusicStatus(EQuantumLevel41AudioPlaying, KErrNone);
    } else {
        PendingPlayback(aTrack) = EFalse;
        /* OpenFileL already positions a fresh player at zero. Only completed
           players need the additional SetPosition call before replay. */
        if (HasCompletedPlayback(aTrack)) {
            player->SetPosition(TTimeIntervalMicroSeconds(0));
        }
        if (aTrack == EAudioTrackShot) __atomic_add_fetch(&iOwner.iTrace[CQuantumLevel41Audio::EPlayCall], 1, __ATOMIC_SEQ_CST);
        player->Play();
        if (aTrack == EAudioTrackShot) __atomic_add_fetch(&iOwner.iTrace[CQuantumLevel41Audio::EPlayReturn], 1, __ATOMIC_SEQ_CST);
        state = EAudioPlayerPlaying;
        UpdateEffectsStatus();
    }
}

void CQuantumLevel41AudioWorker::UpdateEffectsStatus()
{
    if (iShotPlayerState == EAudioPlayerFailed) {
        iOwner.SetEffectsStatus(EQuantumLevel41AudioFailed, iShotError);
    } else if (iReloadPlayerState == EAudioPlayerFailed) {
        iOwner.SetEffectsStatus(EQuantumLevel41AudioFailed, iReloadError);
    } else if (iShotPlayerState == EAudioPlayerOpening ||
               iReloadPlayerState == EAudioPlayerOpening) {
        iOwner.SetEffectsStatus(EQuantumLevel41AudioOpening, KErrNone);
    } else if (iShotPlayerState == EAudioPlayerPlaying ||
               iReloadPlayerState == EAudioPlayerPlaying) {
        iOwner.SetEffectsStatus(EQuantumLevel41AudioPlaying, KErrNone);
    } else {
        iOwner.SetEffectsStatus(EQuantumLevel41AudioReady, KErrNone);
    }
}

void CQuantumLevel41AudioWorker::OnInitComplete(
    TQuantumLevel41AudioTrack aTrack, TInt aError)
{
    if (iStopping) return;
    if (aTrack == EAudioTrackShot) __atomic_store_n(&iOwner.iTrace[CQuantumLevel41Audio::EInitError], aError, __ATOMIC_SEQ_CST);
    iInitError[aTrack] = aError;
    iInitPending[aTrack] = ETrue;
}

void CQuantumLevel41AudioWorker::ProcessPendingInitCompletions()
{
    for (TInt track = EAudioTrackMusic; track <= EAudioTrackReload; ++track) {
        if (iInitPending[track]) {
            TInt error = iInitError[track];
            iInitPending[track] = EFalse;
            HandleInitComplete(static_cast<TQuantumLevel41AudioTrack>(track), error);
        }
    }
}

void CQuantumLevel41AudioWorker::HandleInitComplete(
    TQuantumLevel41AudioTrack aTrack, TInt aError)
{
    if (iStopping) return;

    TQuantumLevel41AudioPlayerState& state = PlayerState(aTrack);
    if (aError != KErrNone) {
        state = EAudioPlayerFailed;
        if (aTrack == EAudioTrackMusic) {
            iMusicError = aError;
            iOwner.SetMusicStatus(EQuantumLevel41AudioFailed, aError);
        } else {
            if (aTrack == EAudioTrackShot) iShotError = aError;
            else iReloadError = aError;
            PendingPlayback(aTrack) = EFalse;
            UpdateEffectsStatus();
        }
        return;
    }

    state = EAudioPlayerReady;
    if (aTrack == EAudioTrackMusic) {
        StartPlayback(EAudioTrackMusic);
    } else if (PendingPlayback(aTrack)) {
        StartPlayback(aTrack);
    } else {
        UpdateEffectsStatus();
    }
}

void CQuantumLevel41AudioWorker::OnPlayComplete(
    TQuantumLevel41AudioTrack aTrack,
    TInt aError)
{
    if (iStopping) return;

    if (aTrack == EAudioTrackShot) {
        __atomic_store_n(&iOwner.iTrace[CQuantumLevel41Audio::ECompleteError], aError, __ATOMIC_SEQ_CST);
        __atomic_add_fetch(&iOwner.iTrace[CQuantumLevel41Audio::ECompleteCount], 1, __ATOMIC_SEQ_CST);
    }
    /* Keep the MDA completion callback minimal. Process player/status changes
       on the poller's next worker turn, after MDA has unwound its callback. */
    PlayCompletionError(aTrack) = aError;
    PlayCompletionPending(aTrack) = ETrue;
}

void CQuantumLevel41AudioWorker::ProcessPendingPlayCompletions()
{
    if (iMusicCompletionPending) {
        const TInt error = iMusicCompletionError;
        iMusicCompletionPending = EFalse;
        HandlePlayComplete(EAudioTrackMusic, error);
    }
    if (iShotCompletionPending) {
        __atomic_add_fetch(&iOwner.iTrace[CQuantumLevel41Audio::EHandled], 1, __ATOMIC_SEQ_CST);
        const TInt error = iShotCompletionError;
        iShotCompletionPending = EFalse;
        HandlePlayComplete(EAudioTrackShot, error);
    }
    if (iReloadCompletionPending) {
        const TInt error = iReloadCompletionError;
        iReloadCompletionPending = EFalse;
        HandlePlayComplete(EAudioTrackReload, error);
    }
}

void CQuantumLevel41AudioWorker::HandlePlayComplete(
    TQuantumLevel41AudioTrack aTrack,
    TInt aError)
{
    if (iStopping) return;

    TQuantumLevel41AudioPlayerState& state = PlayerState(aTrack);
    if (aError != KErrNone) {
        state = EAudioPlayerFailed;
        if (aTrack == EAudioTrackMusic) {
            iMusicError = aError;
            iOwner.SetMusicStatus(EQuantumLevel41AudioFailed, aError);
        } else {
            if (aTrack == EAudioTrackShot) iShotError = aError;
            else iReloadError = aError;
            PendingPlayback(aTrack) = EFalse;
            UpdateEffectsStatus();
        }
        return;
    }

    state = EAudioPlayerReady;
    if (aTrack == EAudioTrackMusic) {
        /* Some E65 codecs complete despite SetRepeats; replay the already
           opened file without issuing another asynchronous OpenFileL. */
        StartPlayback(EAudioTrackMusic);
    } else {
        PendingPlayback(aTrack) = EFalse;
        HasCompletedPlayback(aTrack) = ETrue;
        UpdateEffectsStatus();
    }
}

void CQuantumLevel41AudioWorker::StopPlayers()
{
    iStopping = ETrue;
    if (iMusicPlayer != 0) {
        iMusicPlayer->Stop();
        delete iMusicPlayer;
        iMusicPlayer = 0;
    }
    if (iShotPlayer != 0) {
        iShotPlayer->Stop();
        delete iShotPlayer;
        iShotPlayer = 0;
    }
    if (iReloadPlayer != 0) {
        iReloadPlayer->Stop();
        delete iReloadPlayer;
        iReloadPlayer = 0;
    }
    iMusicPlayerState = EAudioPlayerUnopened;
    iShotPlayerState = EAudioPlayerUnopened;
    iReloadPlayerState = EAudioPlayerUnopened;
    iShotPlaybackPending = EFalse;
    iReloadPlaybackPending = EFalse;
    iShotHasCompletedPlayback = EFalse;
    iReloadHasCompletedPlayback = EFalse;
    iMusicCompletionPending = EFalse;
    iShotCompletionPending = EFalse;
    iReloadCompletionPending = EFalse;
    if (iLibraryLoaded) {
        iMdaLibrary.Close();
        iLibraryLoaded = EFalse;
    }
    iOpenFileFunction = 0;
}

void CQuantumLevel41AudioWorker::Stop()
{
    StopPlayers();
}

CQuantumLevel41AudioPoller::CQuantumLevel41AudioPoller(
    CQuantumLevel41Audio& aOwner,
    CQuantumLevel41AudioWorker& aWorker)
    : CActive(CActive::EPriorityStandard),
      iOwner(aOwner),
      iWorker(aWorker),
      iTimerCreated(EFalse)
{
    CActiveScheduler::Add(this);
}

CQuantumLevel41AudioPoller* CQuantumLevel41AudioPoller::New(
    CQuantumLevel41Audio& aOwner,
    CQuantumLevel41AudioWorker& aWorker,
    TInt& aError)
{
    CQuantumLevel41AudioPoller* self =
        new CQuantumLevel41AudioPoller(aOwner, aWorker);
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

TInt CQuantumLevel41AudioPoller::Construct()
{
    const TInt error = iTimer.CreateLocal();
    if (error == KErrNone) iTimerCreated = ETrue;
    return error;
}

CQuantumLevel41AudioPoller::~CQuantumLevel41AudioPoller()
{
    Cancel();
    if (iTimerCreated) iTimer.Close();
}

void CQuantumLevel41AudioPoller::Start()
{
    Schedule();
}

void CQuantumLevel41AudioPoller::Schedule()
{
    if (IsActive()) return;
    iTimer.After(iStatus,
                 TTimeIntervalMicroSeconds32(KAudioPollIntervalMicroseconds));
    SetActive();
}

void CQuantumLevel41AudioPoller::DoCancel()
{
    iTimer.Cancel();
}

void CQuantumLevel41AudioPoller::RunL()
{
    iWorker.ProcessPendingInitCompletions();
    iWorker.ProcessPendingPlayCompletions();

    TInt commands[CQuantumLevel41Audio::KCommandCapacity];
    const TInt count = iOwner.TakeCommands(
        commands, CQuantumLevel41Audio::KCommandCapacity);
    TInt index;
    for (index = 0; index < count; ++index) {
        if (commands[index] == CQuantumLevel41Audio::ECommandStop) {
            iWorker.Stop();
            CActiveScheduler::Stop();
            return;
        }
        iWorker.HandleCommand(commands[index]);
    }
    Schedule();
}

TInt CQuantumLevel41AudioPoller::RunError(TInt aError)
{
    iOwner.SetMusicStatus(EQuantumLevel41AudioFailed, aError);
    iOwner.SetEffectsStatus(EQuantumLevel41AudioFailed, aError);
    iWorker.Stop();
    CActiveScheduler::Stop();
    return KErrNone;
}

CQuantumLevel41Audio* CQuantumLevel41Audio::New(
    const TDesC& aDrive,
    TInt& aError)
{
    CQuantumLevel41Audio* self = new CQuantumLevel41Audio(aDrive);
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

CQuantumLevel41Audio::CQuantumLevel41Audio(const TDesC& aDrive)
    : iMutexCreated(EFalse),
      iThreadCreated(EFalse),
      iQueueHead(0),
      iQueueCount(0),
      iMusicState(EQuantumLevel41AudioQueued),
      iEffectsState(EQuantumLevel41AudioReady),
      iMusicError(KErrNone),
      iEffectsError(KErrNone)
{
    for (TInt i = 0; i < ETraceCount; ++i) iTrace[i] = 0;
    iTrace[EInitError] = 9999;
    iTrace[ECompleteError] = 9999;
    /* MDA OpenFileL was confirmed on the E65 with a packaged C:\\data WAV.
       Keep these audio files in the same public data area instead of the
       app-private SID directory, which the media server may not be able to
       read on this firmware. Unique names avoid colliding with user files. */
    iMusicFile.Copy(aDrive);
    iMusicFile.Append(_L("\\data\\q41_wth.mp3"));
    iShotFile.Copy(aDrive);
    iShotFile.Append(_L("\\data\\q41_probe_shot.wav"));
    iReloadFile.Copy(aDrive);
    iReloadFile.Append(_L("\\data\\q41_probe_reload.wav"));
}

TInt CQuantumLevel41Audio::Construct()
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

CQuantumLevel41Audio::~CQuantumLevel41Audio()
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

TInt CQuantumLevel41Audio::Enqueue(TCommand aCommand)
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

TInt CQuantumLevel41Audio::TakeCommands(TInt* aCommands, TInt aCapacity)
{
    if (aCommands == 0 || aCapacity <= 0 || !iMutexCreated) return 0;
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

void CQuantumLevel41Audio::SetMusicStatus(
    TQuantumLevel41AudioState aState,
    TInt aError)
{
    if (!iMutexCreated) return;
    iMutex.Wait();
    iMusicState = aState;
    iMusicError = aError;
    iMutex.Signal();
}

void CQuantumLevel41Audio::SetEffectsStatus(
    TQuantumLevel41AudioState aState,
    TInt aError)
{
    if (!iMutexCreated) return;
    iMutex.Wait();
    iEffectsState = aState;
    iEffectsError = aError;
    iMutex.Signal();
}

void CQuantumLevel41Audio::StartLevelMusic()
{
    /* Move effect-file open latency out of the first-fire input path. */
    (void)Enqueue(ECommandPrepareEffects);
    (void)Enqueue(ECommandStartMusic);
}

void CQuantumLevel41Audio::PlayShot()
{
    (void)Enqueue(ECommandPlayShot);
}

void CQuantumLevel41Audio::PlayReload()
{
    (void)Enqueue(ECommandPlayReload);
}

void CQuantumLevel41Audio::StatusLine(TDes& aText)
{
    TQuantumLevel41AudioState musicState;
    TQuantumLevel41AudioState effectsState;
    TInt musicError;
    TInt effectsError;

    if (!iMutexCreated) {
        aText.Copy(_L("AUDIO: NO WORKER"));
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
    if (musicState == EQuantumLevel41AudioFailed) {
        music.Format(_L("E%d"), musicError);
    } else if (musicState == EQuantumLevel41AudioPlaying) {
        music.Copy(_L("PLAY"));
    } else if (musicState == EQuantumLevel41AudioOpening) {
        music.Copy(_L("OPEN"));
    } else if (musicState == EQuantumLevel41AudioReady) {
        music.Copy(_L("READY"));
    } else {
        music.Copy(_L("QUEUE"));
    }

    if (effectsState == EQuantumLevel41AudioFailed) {
        effects.Format(_L("E%d"), effectsError);
    } else if (effectsState == EQuantumLevel41AudioPlaying) {
        effects.Copy(_L("PLAY"));
    } else if (effectsState == EQuantumLevel41AudioOpening) {
        effects.Copy(_L("OPEN"));
    } else if (effectsState == EQuantumLevel41AudioQueued) {
        effects.Copy(_L("QUEUE"));
    } else {
        effects.Copy(_L("READY"));
    }

    TBuf<48> status;
    status.Format(_L("AUDIO M:%S FX:%S MIDI OFF"), &music, &effects);
    aText.Copy(status);
}

TInt CQuantumLevel41Audio::ThreadEntry(TAny* aData)
{
    CQuantumLevel41Audio& owner =
        *static_cast<CQuantumLevel41Audio*>(aData);
    CTrapCleanup* cleanup = CTrapCleanup::New();
    if (cleanup == 0) {
        owner.SetMusicStatus(EQuantumLevel41AudioFailed, KErrNoMemory);
        return KErrNoMemory;
    }

    const TInt criticalError = User::SetCritical(User::ENotCritical);
    if (criticalError != KErrNone) {
        owner.SetMusicStatus(EQuantumLevel41AudioFailed, criticalError);
        delete cleanup;
        return criticalError;
    }

    CActiveScheduler* scheduler = new CActiveScheduler;
    if (scheduler == 0) {
        owner.SetMusicStatus(EQuantumLevel41AudioFailed, KErrNoMemory);
        delete cleanup;
        return KErrNoMemory;
    }
    CActiveScheduler::Install(scheduler);

    TInt workerError = KErrNone;
    CQuantumLevel41AudioWorker* worker =
        CQuantumLevel41AudioWorker::New(owner, workerError);
    if (workerError == KErrNone && worker != 0) {
        CActiveScheduler::Start();
    } else {
        owner.SetMusicStatus(EQuantumLevel41AudioFailed, workerError);
    }

    delete worker;
    CActiveScheduler::Install(0);
    delete scheduler;
    delete cleanup;
    return workerError;
}
