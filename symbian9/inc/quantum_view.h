#ifndef QUANTUM_VIEW_H
#define QUANTUM_VIEW_H

#include "software_mesh_renderer.h"

#include <coecntrl.h>

class CQuantumView : public CCoeControl
{
public:
    static CQuantumView* NewL(const TRect& rect);
    TKeyResponse OfferKeyEventL(const TKeyEvent& keyEvent, TEventCode type);

private:
    enum {
        KRenderWidth = 64,
        KRenderHeight = 64,
        KTextureWidth = 16,
        KTextureHeight = 16,
        KBackgroundPixel = 0x000c1220,
        KRotationStepDegrees = 10,
        KMinimumRotationDegrees = -60,
        KMaximumRotationDegrees = 60
    };

    CQuantumView();
    void ConstructL(const TRect& rect);
    void SetupScene();
    void RenderFrame();
    void Draw(const TRect& rect) const;
    void SizeChanged();

    code::Math::QInt32 iFrameBuffer[KRenderWidth * KRenderHeight];
    code::Math::QInt32 iTexturePixels[KTextureWidth * KTextureHeight];
    code::Math::Vector3D iMeshVertices[3];
    code::Rendering::SoftwareMeshPolygon iMeshPolygon;
    code::Rendering::SoftwareTextureView iTextureView;
    code::Rendering::SoftwareMeshMaterial iMaterial;
    code::Rendering::SoftwareMeshRenderOptions iRenderOptions;
    code::Rendering::SoftwareRenderVertex iCameraVertices[3];
    code::Rendering::SoftwareMeshRenderItem iRenderQueue[1];
    code::Rendering::SoftwareMeshRenderWorkspace iWorkspace;
    code::Math::Matrix iModelToCamera;
    TInt iRotationDegrees;
    bool iRenderSucceeded;
};

#endif // QUANTUM_VIEW_H
