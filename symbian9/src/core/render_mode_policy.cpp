#include "render_mode_policy.h"

#include "quantum_integer.h"

namespace code {
namespace Rendering {

using code::Math::QInt32;
using code::Math::QUint32;

namespace {

QInt32 javaSignedByte(QInt32 value)
{
    const QUint32 byteBits = static_cast<QUint32>(value) & 0xffU;
    if (byteBits <= 0x7fU) {
        return static_cast<QInt32>(byteBits);
    }
    return static_cast<QInt32>(byteBits) - 256;
}

QInt32 clampLowerBound(QInt32 value, QInt32 minimum)
{
    return value < minimum ? minimum : value;
}

QInt32 clampUpperBound(QInt32 value, QInt32 maximum)
{
    return value > maximum ? maximum : value;
}

} // anonymous namespace

RenderModeSettings::RenderModeSettings()
    : perspectiveQuality(2), textureQuality(2), fogQuality(2),
      standardDrawMode(0), perspectiveDistanceQ(24), mipMapping(true)
{
}

void RenderModeSettings::setPerspectiveQuality(QInt32 quality)
{
    perspectiveQuality = quality;
    perspectiveDistanceQ = quality <= 2 ? 24 : 12;
}

void RenderModeSettings::setTextureQuality(QInt32 quality)
{
    textureQuality = quality;
}

void RenderModeSettings::setFogQuality(QInt32 quality)
{
    fogQuality = quality;
}

void RenderModeSettings::setStandardDrawMode(QInt32 drawMode)
{
    standardDrawMode = javaSignedByte(drawMode);
}

void RenderModeSettings::setMipMapping(bool enabled)
{
    mipMapping = enabled;
}

void RenderModeSettings::decreasePerspectiveQuality()
{
    const QInt32 next = code::Math::javaIntSub(perspectiveQuality, 1);
    setPerspectiveQuality(clampLowerBound(next, KMinPerspectiveQuality));
}

void RenderModeSettings::increasePerspectiveQuality()
{
    const QInt32 next = code::Math::javaIntAdd(perspectiveQuality, 1);
    setPerspectiveQuality(clampUpperBound(next, KMaxPerspectiveQuality));
}

void RenderModeSettings::decreaseTextureQuality()
{
    const QInt32 next = code::Math::javaIntSub(textureQuality, 1);
    textureQuality = clampLowerBound(next, KMinTextureQuality);
}

void RenderModeSettings::increaseTextureQuality()
{
    const QInt32 next = code::Math::javaIntAdd(textureQuality, 1);
    textureQuality = clampUpperBound(next, KMaxTextureQuality);
}

void RenderModeSettings::decreaseFogQuality()
{
    const QInt32 next = code::Math::javaIntSub(fogQuality, 1);
    fogQuality = clampLowerBound(next, KMinFogQuality);
}

void RenderModeSettings::increaseFogQuality()
{
    const QInt32 next = code::Math::javaIntAdd(fogQuality, 1);
    fogQuality = clampUpperBound(next, KMaxFogQuality);
}

QInt32 RenderModeSettings::perspectiveQualityPercent() const
{
    return code::Math::javaIntMul(perspectiveQuality, 25);
}

bool RenderModePolicy::usesFastAffinePath(const RenderModeSettings& settings)
{
    return settings.perspectiveQuality == 0 && !settings.mipMapping;
}

bool RenderModePolicy::usesPerspectiveCorrection(
    const RenderModeSettings& settings,
    QInt32 vertexCount,
    QInt32 projectedWidth,
    QInt32 projectedHeight,
    bool texturePerspectiveCorrection)
{
    if (vertexCount != 3 && vertexCount != 4) {
        return false;
    }
    if (settings.perspectiveQuality == KMaxPerspectiveQuality) {
        return true;
    }
    if (settings.perspectiveQuality <= KMinPerspectiveQuality ||
        !texturePerspectiveCorrection) {
        return false;
    }

    if (vertexCount == 3) {
        const QInt32 threshold = settings.perspectiveQuality < 3 ? 30 : 15;
        return projectedWidth > threshold || projectedHeight > threshold;
    }
    if (vertexCount == 4) {
        const QInt32 thresholdX = settings.perspectiveQuality < 3 ? 70 : 50;
        const QInt32 thresholdY = settings.perspectiveQuality < 3 ? 100 : 70;
        return projectedWidth > thresholdX || projectedHeight > thresholdY;
    }
    return false;
}

bool RenderModePolicy::usesPerspectiveCorrectionForDrawMode(
    const RenderModeSettings& settings,
    QInt32 drawMode,
    QInt32 vertexCount,
    QInt32 projectedWidth,
    QInt32 projectedHeight,
    bool texturePerspectiveCorrection)
{
    if (!isSupportedTextureDrawMode(drawMode) || drawMode == 8 ||
        (drawMode >= 10 && drawMode <= 12)) {
        return false;
    }
    return usesPerspectiveCorrection(
        settings, vertexCount, projectedWidth, projectedHeight,
        texturePerspectiveCorrection);
}

QInt32 RenderModePolicy::resolveTextureDrawMode(
    const RenderModeSettings& settings,
    bool hasTextureOverride,
    QInt32 textureDrawMode)
{
    return hasTextureOverride
        ? javaSignedByte(textureDrawMode) : settings.standardDrawMode;
}

bool RenderModePolicy::isSupportedTextureDrawMode(QInt32 drawMode)
{
    return drawMode >= KMinTextureDrawMode &&
           drawMode <= KMaxTextureDrawMode;
}

bool RenderModePolicy::usesUvBasedMipmapSelection(QInt32 drawMode)
{
    return drawMode < 10 || drawMode > 12;
}

AffineRasterizer RenderModePolicy::selectAffineRasterizer(
    QInt32 drawMode,
    QInt32 fogQuality,
    bool textureAlphaMixing,
    bool verticesAreGrayscale,
    bool primaryColorChannelsAreWhite)
{
    if (drawMode == 4 && !textureAlphaMixing) {
        drawMode = 0;
    }

    switch (drawMode) {
    case 0:
        return EAffineRasterizer0;
    case 1:
        return fogQuality > 0 ? EAffineRasterizer1 : EAffineRasterizer0;
    case 2:
        return EAffineRasterizer2;
    case 3:
        return fogQuality > 0 ? EAffineRasterizer3 : EAffineRasterizer0;
    case 4:
        return EAffineRasterizer4;
    case 5:
        return EAffineRasterizer5;
    case 6:
        if (fogQuality == 2) return EAffineRasterizer9;
        if (fogQuality == 1) return EAffineRasterizer5;
        return EAffineRasterizer0;
    case 7:
        return EGlassRasterizer;
    case 8:
        return EFillRasterizer;
    case 9:
        return fogQuality >= 1 && !primaryColorChannelsAreWhite
            ? EAffineRasterizer9 : EAffineRasterizer0;
    case 10:
        return EDitherGradientRasterizer;
    case 11:
        return EWaterDitherRasterizer;
    case 12:
        return EDistanceDitherRasterizer;
    case 13:
        if (fogQuality < 1) return EAffineRasterizer0;
        if (verticesAreGrayscale) {
            return primaryColorChannelsAreWhite
                ? EAffineRasterizer0 : EAffineRasterizer9;
        }
        return fogQuality == 1 ? EAffineRasterizer9 : EAffineRasterizer13;
    default:
        return EUnsupportedRasterizer;
    }
}

} // namespace Rendering
} // namespace code
