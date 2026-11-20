#include "quantum_application.h"

#include <eikstart.h>

LOCAL_C CApaApplication* NewApplication()
{
    return new CQuantumApplication;
}

GLDEF_C TInt E32Main()
{
    return EikStart::RunApplication(NewApplication);
}
