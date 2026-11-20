#ifndef QUANTUM_LEVEL41_SNDDMA_OUTPUT_H
#define QUANTUM_LEVEL41_SNDDMA_OUTPUT_H

#include <e32base.h>
#include <e32std.h>
#include <mdaaudiooutputstream.h>
#include <mda/common/audio.h>

#include "quantum_snddma_mixer.h"

class CQuantumSndDmaPump;

// EKA2 adapter for the portable Quake-style mixer. This is an application-
// level PCM stream, not direct access to OMAP DMA registers. All methods and
// MDA callbacks must run on the thread that owns the active scheduler.
class CQuantumSndDmaOutput : public CBase,
                             public MMdaAudioOutputStreamCallback
{
public:
    enum TEffect {
        EShotEffect = 0,
        EReloadEffect = 1
    };

    static CQuantumSndDmaOutput* NewL();
    ~CQuantumSndDmaOutput();

    // The supplied descriptors must outlive this object because the mixer
    // stores non-owning PCM views into their contents.
    TInt SetWave(TEffect aEffect, const TDesC8& aWaveBytes);
    TInt PlayShot();
    TInt PlayReload();

    TInt LastError() const;
    TInt UnderflowCount() const;
    TInt ActiveVoiceCount() const;
    TBool IsReady() const;
    void Stop();

    // MMdaAudioOutputStreamCallback
    void MaoscOpenComplete(TInt aError);
    void MaoscBufferCopied(TInt aError, const TDesC8& aBuffer);
    void MaoscPlayComplete(TInt aError);

private:
    friend class CQuantumSndDmaPump;

    enum {
        KOutputRate = 11025,
        KOutputChannels = 2,
        KFramesPerBlock = 256,
        KBytesPerSample = 2,
        KBytesPerFrame = KOutputChannels * KBytesPerSample,
        KBlockBytes = KFramesPerBlock * KBytesPerFrame,
        KBlockCount = 3
    };

    struct TOutputBlock {
        TInt16 iSamples[KFramesPerBlock * KOutputChannels];
        TPtrC8 iDescriptor;
        TBool iQueued;

        TOutputBlock();
    };

    CQuantumSndDmaOutput();
    void ConstructL();
    void QueuePump();
    TInt Pump();
    void FillAndSubmitFreeBlocks();
    void SetFailure(TInt aError);

    quantum::snddma::Mixer iMixer;
    TOutputBlock iBlocks[KBlockCount];
    TMdaAudioDataSettings iSettings;
    CMdaAudioOutputStream* iOutputStream;
    CQuantumSndDmaPump* iPump;
    TInt iLastError;
    TInt iUnderflowCount;
    TInt iSubmittedBlocks[KBlockCount];
    TInt iSubmittedHead;
    TInt iSubmittedCount;
    TBool iReady;
    TBool iStopping;
};

#endif // QUANTUM_LEVEL41_SNDDMA_OUTPUT_H
