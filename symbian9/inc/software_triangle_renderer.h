#ifndef QUANTUM_SOFTWARE_TRIANGLE_RENDERER_H
#define QUANTUM_SOFTWARE_TRIANGLE_RENDERER_H

#include "software_affine_rasterizer.h"
#include "render_mode_policy.h"

namespace code {
namespace Rendering {

/*
 * Values already prepared by the polygon/texture layer for one triangle.
 * `usePerspectiveCorrection` must be resolved once for the original Java
 * polygon with RenderModePolicy (a quad's policy uses four-vertex bounds).
 * Modes 8 and 10–12 still take their Java affine/fill/dither fallback.
 */
struct SoftwareTriangleRenderSettings {
    code::Math::QInt32 drawMode;
    code::Math::QInt32 fogQuality;
    bool usePerspectiveCorrection;
    bool forceLowQualityFog;
    bool textureAlphaMixing;
    /* DMODE 6 HQ can multiply depth fog by Java CameraVectorLight factors. */
    bool mode6UsesVertexLight;
    code::Math::QInt32 perspectiveStepPixels;
    code::Math::QInt32 perspectiveMove;

    code::Math::QInt32 fogColor;
    code::Math::QInt32 packedFogColor;
    bool hasPackedFogColor;
    /* Java DirectX7.fDist loaded from FOGD; DIST maps to a separate drDist. */
    code::Math::QInt32 fogDistance;
    code::Math::QInt32 shadeStrength;
    code::Math::QInt32 fallbackShadeStrength;
    code::Math::QInt32 waterDistance;
    code::Math::QInt32 fillSampleU;
    code::Math::QInt32 fillSampleV;

    code::Math::QInt32 brightness[3];
    code::Math::QInt32 ditherGradient[3];
    AffineTriangleColor color[3];

    SoftwareTriangleRenderSettings();
};

/*
 * Java TexturingAffine/TexturingPers drawmode dispatch over the standalone
 * pixel kernels. The caller retains ownership of the framebuffer/texture and
 * supplies lighting/fog values computed by its polygon and camera layers.
 */
class SoftwareTriangleRenderer {
public:
    static bool draw(const SoftwareRenderSurfaceView& destination,
                     const SoftwareTextureView& texture,
                     const AffineTriangleVertex& first,
                     const AffineTriangleVertex& second,
                     const AffineTriangleVertex& third,
                     const SoftwareTriangleRenderSettings& settings);
};

} // namespace Rendering
} // namespace code

#endif // QUANTUM_SOFTWARE_TRIANGLE_RENDERER_H
