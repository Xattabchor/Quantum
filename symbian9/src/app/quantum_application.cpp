#include "quantum_application.h"
#include "quantum_document.h"

const TUid KUidQuantumApplication = { 0xE000A001 };

CApaDocument* CQuantumApplication::CreateDocumentL()
{
    return CQuantumDocument::NewL(*this);
}

TUid CQuantumApplication::AppDllUid() const
{
    return KUidQuantumApplication;
}
