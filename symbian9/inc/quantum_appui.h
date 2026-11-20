#ifndef QUANTUM_APPUI_H
#define QUANTUM_APPUI_H

#include <aknappui.h>

class CQuantumView;

class CQuantumAppUi : public CAknAppUi
{
public:
    void ConstructL();
    ~CQuantumAppUi();

private:
    void HandleCommandL(TInt command);
    void HandleResourceChangeL(TInt type);

private:
    CQuantumView* iView;
};

#endif // QUANTUM_APPUI_H
