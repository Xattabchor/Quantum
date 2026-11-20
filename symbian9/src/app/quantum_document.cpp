#include "quantum_document.h"
#include "quantum_appui.h"

#include <eikappui.h>
#include <eikenv.h>

CQuantumDocument::CQuantumDocument(CEikApplication& application)
    : CAknDocument(application)
{
}

CQuantumDocument* CQuantumDocument::NewL(CEikApplication& application)
{
    CQuantumDocument* self = new (ELeave) CQuantumDocument(application);
    return self;
}

CEikAppUi* CQuantumDocument::CreateAppUiL()
{
    return new (ELeave) CQuantumAppUi;
}
