#ifndef QUANTUM_DOCUMENT_H
#define QUANTUM_DOCUMENT_H

#include <akndoc.h>

class CQuantumDocument : public CAknDocument
{
public:
    static CQuantumDocument* NewL(CEikApplication& application);
    CEikAppUi* CreateAppUiL();

private:
    explicit CQuantumDocument(CEikApplication& application);
};

#endif // QUANTUM_DOCUMENT_H
