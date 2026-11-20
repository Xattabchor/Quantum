#include <e32base.h>
#include <e32std.h>

extern "C" {

/* Zero for the launch-only SIS. The branch keeps a native TRAP/leave boundary
   in the linked image without raising a leave during normal startup. */
volatile TInt quantumNativeLeavesImportProbeEnabled = EFalse;

void quantumNativeLeavesImportProbeMaybeRun()
    {
    if (quantumNativeLeavesImportProbeEnabled)
        {
        TRAPD(error, User::Leave(KErrNotSupported));
        (void)error;
        }
    }

} // extern "C"
