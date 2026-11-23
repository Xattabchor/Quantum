#ifndef QUANTUM_LEVEL41_PCM_AUDIO_H
#define QUANTUM_LEVEL41_PCM_AUDIO_H

#include <e32base.h>
#include <e32std.h>

class CQuantumLevel41PcmAudioWorker;
class CQuantumLevel41PcmAudioPoller;

/*
 * Level 41 audio: music on the MDA file player, effects on one PCM stream.
 *
 * Effects share a single CMdaAudioOutputStream playing whole PCM16 mono 8 kHz
 * clips. There is deliberately no mixer: while a clip is in flight a new
 * effect request is discarded, not queued behind it and not summed with it.
 * That is the "PCM one" behaviour from the reference build, and it is what
 * the E65 runs reliably.
 *
 * Music is unchanged: it stays on CMdaAudioPlayerUtility with its own
 * library handle and callbacks.
 *
 * The game thread never blocks on audio: StartLevelMusic, PlayShot and
 * PlayReload push commands into a small mutex-protected queue, and a worker
 * thread with its own active scheduler owns every MDA object.
 */
class CQuantumLevel41PcmAudio : public CBase
{
public:
    static CQuantumLevel41PcmAudio* New(const TDesC& aDrive, TInt& aError);
    ~CQuantumLevel41PcmAudio();

    void StartLevelMusic();
    void PlayShot();
    void PlayReload();
    void StatusLine(TDes& aText);

    enum TCommand
    {
        ECommandStartMusic = 1,
        ECommandPrepareEffects,
        ECommandPlayShot,
        ECommandPlayReload,
        ECommandStop
    };

    enum TAudioState
    {
        EAudioQueued = 0,
        EAudioOpening,
        EAudioPlaying,
        EAudioReady,
        EAudioFailed
    };

    enum
    {
        KCommandCapacity = 8
    };

    /* Called by the worker and poller, which live on the audio thread. */
    void SetMusicStatus(TInt aState, TInt aError);
    void SetEffectsStatus(TInt aState, TInt aError);
    TInt TakeCommands(TInt* aCommands, TInt aCapacity);

private:
    friend class CQuantumLevel41PcmAudioWorker;
    friend class CQuantumLevel41PcmAudioPoller;

    explicit CQuantumLevel41PcmAudio(const TDesC& aDrive);
    TInt Construct();
    TInt Enqueue(TInt aCommand);

    static TInt ThreadEntry(TAny* aSelf);
    void ThreadMainL();

    TFileName iMusicFile;
    TFileName iShotFile;
    TFileName iReloadFile;

    RMutex iMutex;
    RThread iThread;
    TRequestStatus iThreadLogon;
    TInt iCommands[KCommandCapacity];
    TInt iQueueHead;
    TInt iQueueCount;

    TInt iMusicState;
    TInt iEffectsState;
    TInt iMusicError;
    TInt iEffectsError;

    TBool iMutexCreated;
    TBool iThreadCreated;
};

#endif // QUANTUM_LEVEL41_PCM_AUDIO_H
