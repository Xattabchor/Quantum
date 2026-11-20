#ifndef QUANTUM_APPLICATION_H
#define QUANTUM_APPLICATION_H

#include <aknapp.h>

class CQuantumApplication : public CAknApplication
{
protected:
    CApaDocument* CreateDocumentL();
    TUid AppDllUid() const;
};

#endif // QUANTUM_APPLICATION_H
