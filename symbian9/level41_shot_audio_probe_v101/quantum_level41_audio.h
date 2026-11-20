#ifndef QUANTUM_LEVEL41_AUDIO_H
#define QUANTUM_LEVEL41_AUDIO_H

#include <e32base.h>
#include <e32std.h>

enum TQuantumLevel41AudioState
{
    EQuantumLevel41AudioQueued = 0,
    EQuantumLevel41AudioOpening,
    EQuantumLevel41AudioPlaying,
    EQuantumLevel41AudioReady,
    EQuantumLevel41AudioFailed
};

class CQuantumLevel41AudioWorker;
class CQuantumLevel41AudioPoller;

/*
 * Optional audio service for the Level 1 walk sample. MDA operations run on a
 * dedicated active-scheduler thread so neither the GDI event wait nor DSA
 * frame timer is blocked by codec callbacks. OpenFileL ordinal 42 is resolved
 * at runtime. Music, fire, and reload each keep a separate player and open
 * their file once. Shot and reload files are pre-opened in the background so
 * their first trigger does not wait for OpenFileL; one trigger received during
 * preparation is coalesced, while additional triggers during playback are
 * dropped. Play-completion callbacks only latch their result; the worker
 * poller updates player state after MDA unwinds the callback. A freshly opened
 * player is played from its initial position; SetPosition is reserved for
 * replay after a successful completion.
 */
class CQuantumLevel41Audio : public CBase
{
public:
    static CQuantumLevel41Audio* New(const TDesC& aDrive, TInt& aError);
    ~CQuantumLevel41Audio();

    void StartLevelMusic();
    void PlayShot();
    void PlayReload();
    void StatusLine(TDes& aText);
    TBool WorkerAlive() const { return iThreadLogon == KRequestPending; }
    TInt WorkerExitReason() const { return iThread.ExitReason(); }
    TExitType WorkerExitType() const { return iThread.ExitType(); }
    TExitCategoryName WorkerExitCategory() const { return iThread.ExitCategory(); }
    enum TTrace { EOpen, EInitError, EPlayCall, EPlayReturn, ECompleteCount, ECompleteError, EHandled, ETraceCount };
    TInt Trace(TInt n) const { return __atomic_load_n(&iTrace[n], __ATOMIC_SEQ_CST); }

private:
    friend class CQuantumLevel41AudioWorker;
    friend class CQuantumLevel41AudioPoller;

    enum TCommand
    {
        ECommandStartMusic = 1,
        ECommandPlayShot,
        ECommandPlayReload,
        ECommandPrepareEffects,
        ECommandStop
    };

    enum
    {
        KCommandCapacity = 8
    };

    explicit CQuantumLevel41Audio(const TDesC& aDrive);
    TInt Construct();
    TInt Enqueue(TCommand aCommand);
    TInt TakeCommands(TInt* aCommands, TInt aCapacity);
    void SetMusicStatus(TQuantumLevel41AudioState aState, TInt aError);
    void SetEffectsStatus(TQuantumLevel41AudioState aState, TInt aError);
    static TInt ThreadEntry(TAny* aData);

    // Aligned atomic counters remain readable even if the worker panics.
    TInt iTrace[ETraceCount];
    RMutex iMutex;
    TBool iMutexCreated;
    RThread iThread;
    TBool iThreadCreated;
    TRequestStatus iThreadLogon;
    TFileName iMusicFile;
    TFileName iShotFile;
    TFileName iReloadFile;
    TInt iCommands[KCommandCapacity];
    TInt iQueueHead;
    TInt iQueueCount;
    TQuantumLevel41AudioState iMusicState;
    TQuantumLevel41AudioState iEffectsState;
    TInt iMusicError;
    TInt iEffectsError;
};

#endif // QUANTUM_LEVEL41_AUDIO_H
