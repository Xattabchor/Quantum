#include "quantum_snddma_output.h"

#include <new>

class CQuantumSndDmaPump : public CActive
{
public:
    static CQuantumSndDmaPump* NewL(CQuantumSndDmaOutput& aOwner);
    ~CQuantumSndDmaPump();
    void Schedule();

private:
    explicit CQuantumSndDmaPump(CQuantumSndDmaOutput& aOwner);
    void ConstructL();
    void DoCancel() override;
    void RunL() override;
    TInt RunError(TInt aError) override;

    CQuantumSndDmaOutput& iOwner;
    RTimer iTimer;
    TBool iTimerCreated;
};

CQuantumSndDmaPump::CQuantumSndDmaPump(CQuantumSndDmaOutput& aOwner)
    : CActive(CActive::EPriorityStandard),
      iOwner(aOwner),
      iTimerCreated(EFalse)
{
    CActiveScheduler::Add(this);
}

CQuantumSndDmaPump* CQuantumSndDmaPump::NewL(
    CQuantumSndDmaOutput& aOwner)
{
    CQuantumSndDmaPump* self = new (ELeave) CQuantumSndDmaPump(aOwner);
    CleanupStack::PushL(self);
    self->ConstructL();
    CleanupStack::Pop(self);
    return self;
}

void CQuantumSndDmaPump::ConstructL()
{
    User::LeaveIfError(iTimer.CreateLocal());
    iTimerCreated = ETrue;
}

CQuantumSndDmaPump::~CQuantumSndDmaPump()
{
    Cancel();
    if (iTimerCreated) iTimer.Close();
}

void CQuantumSndDmaPump::Schedule()
{
    if (IsActive()) return;
    iTimer.After(iStatus, TTimeIntervalMicroSeconds32(0));
    SetActive();
}

void CQuantumSndDmaPump::DoCancel()
{
    iTimer.Cancel();
}

void CQuantumSndDmaPump::RunL()
{
    (void)iOwner.Pump();
}

TInt CQuantumSndDmaPump::RunError(TInt aError)
{
    iOwner.SetFailure(aError);
    return KErrNone;
}

CQuantumSndDmaOutput::TOutputBlock::TOutputBlock()
    : iDescriptor(reinterpret_cast<const TUint8*>(iSamples), KBlockBytes),
      iQueued(EFalse)
{
    Mem::FillZ(iSamples, sizeof(iSamples));
}

CQuantumSndDmaOutput::CQuantumSndDmaOutput()
    : iMixer(KOutputRate),
      iOutputStream(0),
      iPump(0),
      iLastError(KErrNone),
      iUnderflowCount(0),
      iSubmittedHead(0),
      iSubmittedCount(0),
      iReady(EFalse),
      iStopping(EFalse)
{
    Mem::FillZ(iSubmittedBlocks, sizeof(iSubmittedBlocks));
}

CQuantumSndDmaOutput* CQuantumSndDmaOutput::NewL()
{
    CQuantumSndDmaOutput* self = new (ELeave) CQuantumSndDmaOutput();
    CleanupStack::PushL(self);
    self->ConstructL();
    CleanupStack::Pop(self);
    return self;
}

void CQuantumSndDmaOutput::ConstructL()
{
    iPump = CQuantumSndDmaPump::NewL(*this);

    iSettings.iSampleRate = TMdaAudioDataSettings::ESampleRate11025Hz;
    iSettings.iChannels = TMdaAudioDataSettings::EChannelsStereo;

    iOutputStream = CMdaAudioOutputStream::NewL(*this);
    const TInt keepOpenError = iOutputStream->KeepOpenAtEnd();
    if (keepOpenError != KErrNone && keepOpenError != KErrNotSupported) {
        User::LeaveIfError(keepOpenError);
    }
    iOutputStream->SetPriority(
        EMdaPriorityNormal, EMdaPriorityPreferenceTimeAndQuality);
    iOutputStream->Open(&iSettings);
}

CQuantumSndDmaOutput::~CQuantumSndDmaOutput()
{
    Stop();
    delete iPump;
    iPump = 0;
}

TInt CQuantumSndDmaOutput::SetWave(TEffect aEffect,
                                   const TDesC8& aWaveBytes)
{
    quantum::snddma::PcmWaveView view;
    const quantum::snddma::WaveParseResult parseResult =
        quantum::snddma::ParsePcm16Wave(
            aWaveBytes.Ptr(), static_cast<std::size_t>(aWaveBytes.Length()),
            view);
    if (parseResult != quantum::snddma::EWaveParseOk) {
        return parseResult == quantum::snddma::EWaveParseBadArgument
            ? KErrArgument
            : parseResult == quantum::snddma::EWaveParseUnsupportedPcm
                ? KErrNotSupported
                : KErrCorrupt;
    }

    if (aEffect == EShotEffect) {
        return iMixer.SetShotWave(view) ? KErrNone : KErrNotSupported;
    }
    if (aEffect == EReloadEffect) {
        return iMixer.SetReloadWave(view) ? KErrNone : KErrNotSupported;
    }
    return KErrArgument;
}

TInt CQuantumSndDmaOutput::PlayShot()
{
    if (iStopping) return KErrDied;
    if (iLastError != KErrNone) return iLastError;
    return iMixer.PlayShot() ? KErrNone : KErrNotReady;
}

TInt CQuantumSndDmaOutput::PlayReload()
{
    if (iStopping) return KErrDied;
    if (iLastError != KErrNone) return iLastError;
    return iMixer.PlayReload() ? KErrNone : KErrNotReady;
}

TInt CQuantumSndDmaOutput::LastError() const
{
    return iLastError;
}

TInt CQuantumSndDmaOutput::UnderflowCount() const
{
    return iUnderflowCount;
}

TInt CQuantumSndDmaOutput::ActiveVoiceCount() const
{
    return static_cast<TInt>(iMixer.ActiveVoiceCount());
}

TBool CQuantumSndDmaOutput::IsReady() const
{
    return iReady;
}

void CQuantumSndDmaOutput::Stop()
{
    if (iStopping) return;
    iStopping = ETrue;
    iReady = EFalse;
    iMixer.StopAll();

    if (iPump != 0) iPump->Cancel();
    if (iOutputStream != 0) {
        iOutputStream->Stop();
        delete iOutputStream;
        iOutputStream = 0;
    }

    TInt index;
    for (index = 0; index < KBlockCount; ++index) {
        iBlocks[index].iQueued = EFalse;
    }
    iSubmittedHead = 0;
    iSubmittedCount = 0;
}

void CQuantumSndDmaOutput::MaoscOpenComplete(TInt aError)
{
    if (iStopping) return;
    if (aError != KErrNone) {
        SetFailure(aError);
        return;
    }

    iReady = ETrue;
    iOutputStream->SetVolume(iOutputStream->MaxVolume() / 2);
    QueuePump();
}

void CQuantumSndDmaOutput::MaoscBufferCopied(
    TInt aError,
    const TDesC8& aBuffer)
{
    (void)aBuffer;
    if (iStopping || iLastError != KErrNone) return;
    if (aError != KErrNone) {
        SetFailure(aError);
        return;
    }
    if (iSubmittedCount <= 0) {
        SetFailure(KErrCorrupt);
        return;
    }

    const TInt block = iSubmittedBlocks[iSubmittedHead];
    iSubmittedHead = (iSubmittedHead + 1) % KBlockCount;
    --iSubmittedCount;
    if (block < 0 || block >= KBlockCount || !iBlocks[block].iQueued) {
        SetFailure(KErrCorrupt);
        return;
    }
    iBlocks[block].iQueued = EFalse;
    QueuePump();
}

void CQuantumSndDmaOutput::MaoscPlayComplete(TInt aError)
{
    if (iStopping && (aError == KErrAbort || aError == KErrNone)) return;
    if (aError == KErrUnderflow) ++iUnderflowCount;
    if (aError == KErrUnderflow || aError == KErrNone) {
        QueuePump();
        return;
    }
    SetFailure(aError);
}

void CQuantumSndDmaOutput::QueuePump()
{
    if (iPump == 0 || iStopping || !iReady || iLastError != KErrNone) {
        return;
    }
    iPump->Schedule();
}

TInt CQuantumSndDmaOutput::Pump()
{
    if (iStopping || !iReady || iLastError != KErrNone) return KErrNone;
    FillAndSubmitFreeBlocks();
    return KErrNone;
}

void CQuantumSndDmaOutput::FillAndSubmitFreeBlocks()
{
    TInt index;
    for (index = 0; index < KBlockCount; ++index) {
        TOutputBlock& block = iBlocks[index];
        if (block.iQueued) continue;

        if (!iMixer.Mix(block.iSamples, KFramesPerBlock)) {
            SetFailure(KErrArgument);
            return;
        }

        // Retain each block until the FIFO completion callback returns it.
        // Symbian's output stream consumes submitted PCM in WriteL order.
        if (iSubmittedCount >= KBlockCount) {
            SetFailure(KErrOverflow);
            return;
        }
        const TInt tail = (iSubmittedHead + iSubmittedCount) % KBlockCount;
        iSubmittedBlocks[tail] = index;
        ++iSubmittedCount;
        block.iQueued = ETrue;
        iOutputStream->WriteL(block.iDescriptor);
    }
}

void CQuantumSndDmaOutput::SetFailure(TInt aError)
{
    if (aError == KErrNone) aError = KErrGeneral;
    if (iLastError == KErrNone) iLastError = aError;
    iReady = EFalse;
    iMixer.StopAll();
    if (iPump != 0) iPump->Cancel();
}
