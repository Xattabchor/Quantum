#ifndef QUANTUM_RENDER_MODE_POLICY_H
#define QUANTUM_RENDER_MODE_POLICY_H

#include "quantum_types.h"

namespace code {
namespace Rendering {

enum {
    KMinPerspectiveQuality = 0,
    KMaxPerspectiveQuality = 4,
    KMinTextureQuality = 0,
    KMaxTextureQuality = 2,
    KMinFogQuality = 0,
    KMaxFogQuality = 2,
    KMinTextureDrawMode = 0,
    KMaxTextureDrawMode = 13
};

/*
 * Portable state for the rendering-quality options in Main/Setting.java.
 * Public values intentionally retain raw saved settings; the step methods
 * apply the ranges enforced by the Java options menu.
 */
struct RenderModeSettings {
    code::Math::QInt32 perspectiveQuality;
    code::Math::QInt32 textureQuality;
    code::Math::QInt32 fogQuality;
    code::Math::QInt32 standardDrawMode;
    code::Math::QInt32 perspectiveDistanceQ;
    bool mipMapping;

    RenderModeSettings();

    void setPerspectiveQuality(code::Math::QInt32 quality);
    void setTextureQuality(code::Math::QInt32 quality);
    void setFogQuality(code::Math::QInt32 quality);
    void setStandardDrawMode(code::Math::QInt32 drawMode);
    void setMipMapping(bool enabled);

    void decreasePerspectiveQuality();
    void increasePerspectiveQuality();
    void decreaseTextureQuality();
    void increaseTextureQuality();
    void decreaseFogQuality();
    void increaseFogQuality();

    code::Math::QInt32 perspectiveQualityPercent() const;
};

/* The destination selected by the Java TexturingAffine.paint() drawmode switch. */
enum AffineRasterizer {
    EAffineRasterizer0 = 0,
    EAffineRasterizer1,
    EAffineRasterizer2,
    EAffineRasterizer3,
    EAffineRasterizer4,
    EAffineRasterizer5,
    EAffineRasterizer9,
    EAffineRasterizer13,
    EGlassRasterizer,
    EFillRasterizer,
    EDitherGradientRasterizer,
    EWaterDitherRasterizer,
    EDistanceDitherRasterizer,
    EUnsupportedRasterizer
};

/*
 * Host-testable mode selection and perspective/affine dispatch policy.
 * Pixel kernels are implemented separately: affine drawmodes 0–7, 8–13,
 * and perspective drawmodes 0–7, 9, 13. Modes 8 and 10–12 intentionally
 * bypass perspective mapping like the Java implementation. Mode numbers are
 * Java Texture.drawmode codes (0..13); selection sizes are projected spans.
 */
class RenderModePolicy {
public:
    static bool usesFastAffinePath(const RenderModeSettings& settings);

    static bool usesPerspectiveCorrection(
        const RenderModeSettings& settings,
        code::Math::QInt32 vertexCount,
        code::Math::QInt32 projectedWidth,
        code::Math::QInt32 projectedHeight,
        bool texturePerspectiveCorrection);

    /* Apply Java's early affine/fill/dither bypasses for drawmodes 8 and 10–12. */
    static bool usesPerspectiveCorrectionForDrawMode(
        const RenderModeSettings& settings,
        code::Math::QInt32 drawMode,
        code::Math::QInt32 vertexCount,
        code::Math::QInt32 projectedWidth,
        code::Math::QInt32 projectedHeight,
        bool texturePerspectiveCorrection);

    static code::Math::QInt32 resolveTextureDrawMode(
        const RenderModeSettings& settings,
        bool hasTextureOverride,
        code::Math::QInt32 textureDrawMode);

    static bool isSupportedTextureDrawMode(code::Math::QInt32 drawMode);
    static bool usesUvBasedMipmapSelection(code::Math::QInt32 drawMode);

    static AffineRasterizer selectAffineRasterizer(
        code::Math::QInt32 drawMode,
        code::Math::QInt32 fogQuality,
        bool textureAlphaMixing,
        bool verticesAreGrayscale,
        bool primaryColorChannelsAreWhite);
};

} // namespace Rendering
} // namespace code

#endif // QUANTUM_RENDER_MODE_POLICY_H
