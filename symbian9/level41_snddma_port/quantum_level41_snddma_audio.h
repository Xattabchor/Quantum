#ifndef QUANTUM_LEVEL41_SNDDMA_AUDIO_H
#define QUANTUM_LEVEL41_SNDDMA_AUDIO_H

#include <e32base.h>
#include <e32std.h>

class CQuantumLevel41SndDmaAudioWorker;
class CQuantumLevel41SndDmaAudioPoller;

/*
 * Separate SNDDMA-style audio backend for the Level 41 walk candidate.
 * Fire/reload WAV PCM is mixed by the portable software mixer and streamed
 * through CMdaAudioOutputStream on a dedicated active-scheduler thread.
 * The looping MP3 remains on the existing MDA player path. This is not direct
 * OMAP DMA access; the default MDA backend and SISX remain unchanged.
 */
class CQuantumLevel41SndDmaAudio : public CBase
{
public:
    static CQuantumLevel41SndDmaAudio* New(const TDesC& aDrive, TInt& aError);
    ~CQuantumLevel41SndDmaAudio();

    void StartLevelMusic();
    void PlayShot();
    void PlayReload();
    void StatusLine(TDes& aText);

private:
    friend class CQuantumLevel41SndDmaAudioWorker;
    friend class CQuantumLevel41SndDmaAudioPoller;

    enum TCommand
    {
        ECommandStartMusic = 1,
        ECommandPlayShot,
        ECommandPlayReload,
        ECommandPrepareEffects,
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

    explicit CQuantumLevel41SndDmaAudio(const TDesC& aDrive);
    TInt Construct();
    TInt Enqueue(TCommand aCommand);
    TInt TakeCommands(TInt* aCommands, TInt aCapacity);
    void SetMusicStatus(TAudioState aState, TInt aError);
    void SetEffectsStatus(TAudioState aState, TInt aError);
    void SetUnderflowCount(TInt aCount);
    static TInt ThreadEntry(TAny* aData);

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
    TAudioState iMusicState;
    TAudioState iEffectsState;
    TInt iMusicError;
    TInt iEffectsError;
    TInt iUnderflowCount;
};

#endif // QUANTUM_LEVEL41_SNDDMA_AUDIO_H
