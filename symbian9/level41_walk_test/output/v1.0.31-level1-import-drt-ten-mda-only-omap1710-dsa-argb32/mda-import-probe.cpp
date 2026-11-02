#include <e32base.h>
#include <e32std.h>
#include <mdaaudiosampleplayer.h>

namespace
    {

class TMdaImportProbeObserver : public MMdaAudioPlayerCallback
    {
public:
    void MapcInitComplete(
        TInt aError,
        const TTimeIntervalMicroSeconds& aDuration)
        {
        (void)aError;
        (void)aDuration;
        }

    void MapcPlayComplete(TInt aError)
        {
        (void)aError;
        }
    };

/* The function is retained solely so the E32 converter emits the selected
   MDA imports. Its only caller is guarded by an unmodified volatile false
   flag. No instance is created, no descriptor/path is opened, and Play is
   never referenced by this probe. */
void MdaImportReferencesOnlyL(const TDesC& aPath)
    {
    TMdaImportProbeObserver observer;
    CMdaAudioPlayerUtility* player =
        CMdaAudioPlayerUtility::NewL(observer);
    if (player == 0) return;
    player->OpenFileL(aPath);
    const TTimeIntervalMicroSeconds start(0);
    player->SetPosition(start);
    player->Close();
    delete player;
    }

    } // namespace

extern "C" {

volatile TInt quantumMdaImportProbeEnabled = EFalse;

void quantumMdaImportProbeMaybeRun()
    {
    if (quantumMdaImportProbeEnabled)
        {
        _LIT(KMdaImportProbeEmptyPath, "");
        MdaImportReferencesOnlyL(KMdaImportProbeEmptyPath);
        }
    }

} // extern "C"
