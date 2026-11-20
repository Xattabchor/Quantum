#include "quantum_appui.h"
#include "quantum.hrh"
#include "quantum_view.h"

#include <aknnotewrappers.h>
#include <eikenv.h>

void CQuantumAppUi::ConstructL()
{
    iView = 0;
    BaseConstructL();

    iView = CQuantumView::NewL(ClientRect());
    CleanupStack::PushL(iView);
    AddToStackL(iView);
    CleanupStack::Pop(iView);
}

CQuantumAppUi::~CQuantumAppUi()
{
    if (iView != 0) {
        RemoveFromStack(iView);
        delete iView;
        iView = 0;
    }
}

void CQuantumAppUi::HandleCommandL(TInt command)
{
    switch (command) {
    case EQuantumCmdAbout:
        {
            CAknInformationNote* note = new (ELeave) CAknInformationNote;
            note->ExecuteLD(_L("Native mesh rendering is wired to this view. Use left/right to rotate the smoke mesh. Game assets and gameplay are not ported yet."));
        }
        break;
    case EAknSoftkeyExit:
    case EEikCmdExit:
        Exit();
        break;
    default:
        CAknAppUi::HandleCommandL(command);
        break;
    }
}

void CQuantumAppUi::HandleResourceChangeL(TInt type)
{
    CAknAppUi::HandleResourceChangeL(type);
    if (type == KEikDynamicLayoutVariantSwitch && iView != 0) {
        iView->SetRect(ClientRect());
    }
}
