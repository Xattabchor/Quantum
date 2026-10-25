#include "software_triangle_renderer.h"
#include "quantum_integer.h"
#include "software_perspective_rasterizer.h"

namespace code {
namespace Rendering {

using code::Math::QInt32;

namespace {

QInt32 minimumDepth(const AffineTriangleVertex& first,
                    const AffineTriangleVertex& second,
                    const AffineTriangleVertex& third)
{
    QInt32 result = first.depthRz;
    if (second.depthRz < result) result = second.depthRz;
    if (third.depthRz < result) result = third.depthRz;
    return result;
}

QInt32 maximumDepth(const AffineTriangleVertex& first,
                    const AffineTriangleVertex& second,
                    const AffineTriangleVertex& third)
{
    QInt32 result = first.depthRz;
    if (second.depthRz > result) result = second.depthRz;
    if (third.depthRz > result) result = third.depthRz;
    return result;
}

bool colorIsWhite(const AffineTriangleColor& color)
{
    return color.redFactor == 255 && color.greenFactor == 255 &&
           color.blueFactor == 255;
}

bool allColorChannelsWhite(const SoftwareTriangleRenderSettings& settings)
{
    return colorIsWhite(settings.color[0]) &&
           colorIsWhite(settings.color[1]) &&
           colorIsWhite(settings.color[2]);
}

bool colorsAreGrayscale(const SoftwareTriangleRenderSettings& settings)
{
    return settings.color[0].redFactor == settings.color[0].greenFactor &&
           settings.color[0].redFactor == settings.color[0].blueFactor &&
           settings.color[1].redFactor == settings.color[1].greenFactor &&
           settings.color[1].redFactor == settings.color[1].blueFactor &&
           settings.color[2].redFactor == settings.color[2].greenFactor &&
           settings.color[2].redFactor == settings.color[2].blueFactor;
}

bool brightnessIsWhite(const SoftwareTriangleRenderSettings& settings)
{
    return settings.brightness[0] == 255 &&
           settings.brightness[1] == 255 &&
           settings.brightness[2] == 255;
}

QInt32 averageColor(const AffineTriangleColor& color)
{
    return (color.redFactor + color.greenFactor + color.blueFactor) / 3;
}

void resolveBrightness(const SoftwareTriangleRenderSettings& settings,
                       QInt32 drawMode,
                       QInt32 brightness[3])
{
    int i;
    if (drawMode == 13) {
        for (i = 0; i < 3; ++i) {
            brightness[i] = averageColor(settings.color[i]);
        }
    } else {
        for (i = 0; i < 3; ++i) brightness[i] = settings.brightness[i];
    }
}

QInt32 affineMode6Brightness(QInt32 depthRz, QInt32 fogDistance)
{
    QInt32 brightness = code::Math::javaIntAdd(
        255, code::Math::javaIntDiv(
            code::Math::javaIntMul(depthRz, 255), fogDistance));
    if (brightness > 255) brightness = 255;
    if (brightness < 0) brightness = 0;
    return brightness;
}

QInt32 mode6VectorBrightness(QInt32 depthRz,
                              QInt32 fogDistance,
                              QInt32 vectorLight)
{
    return code::Math::javaIntDiv(
        code::Math::javaIntMul(
            affineMode6Brightness(depthRz, fogDistance), vectorLight), 255);
}

QInt32 packedFogColor(const SoftwareTriangleRenderSettings& settings,
                      const AffineTriangleVertex& first,
                      const AffineTriangleVertex& second,
                      const AffineTriangleVertex& third)
{
    if (settings.hasPackedFogColor) return settings.packedFogColor;
    return SoftwareAffineRasterizer::mode1FogAddColor(
        minimumDepth(first, second, third), settings.fogDistance,
        settings.fogColor);
}

QInt32 lqFogStrength(const SoftwareTriangleRenderSettings& settings,
                     const AffineTriangleVertex& first,
                     const AffineTriangleVertex& second,
                     const AffineTriangleVertex& third,
                     bool perspective)
{
    /*
     * Java affine mode 3 uses max(rz); perspective LQ uses min(rz). The
     * shared helper retains the source's integer fDist / 255 zero/fallback
     * condition, rather than treating it as a floating-point quality test.
     */
    const QInt32 depth = perspective
        ? minimumDepth(first, second, third)
        : maximumDepth(first, second, third);
    return SoftwareAffineRasterizer::mode3FogStrength(
        depth, settings.fogDistance, settings.fallbackShadeStrength);
}

bool drawAffine(const SoftwareRenderSurfaceView& destination,
                const SoftwareTextureView& texture,
                const AffineTriangleVertex& first,
                const AffineTriangleVertex& second,
                const AffineTriangleVertex& third,
                QInt32 drawMode,
                const SoftwareTriangleRenderSettings& settings)
{
    const bool colorGrayscale = colorsAreGrayscale(settings);
    const bool whitePrimary = drawMode == 13
        ? allColorChannelsWhite(settings) : brightnessIsWhite(settings);
    const AffineRasterizer rasterizer = RenderModePolicy::selectAffineRasterizer(
        drawMode, settings.fogQuality, settings.textureAlphaMixing,
        colorGrayscale, whitePrimary);
    QInt32 brightness[3];
    resolveBrightness(settings, drawMode, brightness);

    switch (rasterizer) {
    case EAffineRasterizer0:
        return SoftwareAffineRasterizer::drawMode0(
            destination, texture, first, second, third);
    case EAffineRasterizer1:
        return SoftwareAffineRasterizer::drawMode1(
            destination, texture, first, second, third,
            packedFogColor(settings, first, second, third));
    case EAffineRasterizer2:
        return SoftwareAffineRasterizer::drawMode2(
            destination, texture, first, second, third);
    case EAffineRasterizer3:
        if (settings.fogQuality == 2 && !settings.forceLowQualityFog) {
            return SoftwareAffineRasterizer::drawMode3HQ(
                destination, texture, first, second, third,
                settings.fogColor, settings.fogDistance);
        }
        return SoftwareAffineRasterizer::drawMode3LQ(
            destination, texture, first, second, third, settings.fogColor,
            lqFogStrength(settings, first, second, third, false));
    case EAffineRasterizer4:
        return SoftwareAffineRasterizer::drawMode4AlphaBlend(
            destination, texture, first, second, third);
    case EAffineRasterizer5:
        if (drawMode == 6) {
            return SoftwareAffineRasterizer::drawMode6LQ(
                destination, texture, first, second, third,
                settings.fallbackShadeStrength, settings.fogDistance);
        }
        return SoftwareAffineRasterizer::drawMode5Shade(
            destination, texture, first, second, third,
            settings.shadeStrength);
    case EAffineRasterizer9:
        if (drawMode == 6) {
            if (settings.mode6UsesVertexLight) {
                return SoftwareAffineRasterizer::drawMode6HQ(
                    destination, texture, first, second, third,
                    mode6VectorBrightness(first.depthRz, settings.fogDistance,
                                          brightness[0]),
                    mode6VectorBrightness(second.depthRz, settings.fogDistance,
                                          brightness[1]),
                    mode6VectorBrightness(third.depthRz, settings.fogDistance,
                                          brightness[2]));
            }
            return SoftwareAffineRasterizer::drawMode6HQ(
                destination, texture, first, second, third,
                affineMode6Brightness(first.depthRz, settings.fogDistance),
                affineMode6Brightness(second.depthRz, settings.fogDistance),
                affineMode6Brightness(third.depthRz, settings.fogDistance));
        }
        return SoftwareAffineRasterizer::drawMode9(
            destination, texture, first, second, third,
            brightness[0], brightness[1], brightness[2]);
    case EAffineRasterizer13:
        return SoftwareAffineRasterizer::drawMode13(
            destination, texture, first, second, third,
            settings.color[0], settings.color[1], settings.color[2]);
    case EGlassRasterizer:
        return SoftwareAffineRasterizer::drawMode7Glass(
            destination, texture, first, second, third);
    case EFillRasterizer:
        return SoftwareAffineRasterizer::drawMode8Fill(
            destination, texture, first, second, third,
            settings.fillSampleU, settings.fillSampleV);
    case EDitherGradientRasterizer:
        return SoftwareAffineRasterizer::drawMode10DitherGradient(
            destination, texture, first, second, third,
            settings.ditherGradient[0], settings.ditherGradient[1],
            settings.ditherGradient[2]);
    case EWaterDitherRasterizer:
        return SoftwareAffineRasterizer::drawMode11DitherWater(
            destination, texture, first, second, third,
            settings.waterDistance);
    case EDistanceDitherRasterizer:
        return SoftwareAffineRasterizer::drawMode12DitherFog(
            destination, texture, first, second, third,
            settings.fogDistance);
    default:
        return false;
    }
}

bool drawPerspective(const SoftwareRenderSurfaceView& destination,
                     const SoftwareTextureView& texture,
                     const AffineTriangleVertex& first,
                     const AffineTriangleVertex& second,
                     const AffineTriangleVertex& third,
                     QInt32 drawMode,
                     const SoftwareTriangleRenderSettings& settings)
{
    QInt32 brightness[3];
    resolveBrightness(settings, drawMode, brightness);
    const bool whiteFactors = drawMode == 13
        ? allColorChannelsWhite(settings) : brightnessIsWhite(settings);
    switch (drawMode) {
    case 0:
        return SoftwarePerspectiveRasterizer::drawMode0Copy(
            destination, texture, first, second, third,
            settings.perspectiveStepPixels, settings.perspectiveMove);
    case 1:
        if (settings.fogQuality <= 0) {
            return SoftwarePerspectiveRasterizer::drawMode0Copy(
                destination, texture, first, second, third,
                settings.perspectiveStepPixels, settings.perspectiveMove);
        }
        return SoftwarePerspectiveRasterizer::drawMode1Add(
            destination, texture, first, second, third,
            packedFogColor(settings, first, second, third),
            settings.perspectiveStepPixels, settings.perspectiveMove);
    case 2:
        return SoftwarePerspectiveRasterizer::drawMode2SurfaceAdd(
            destination, texture, first, second, third,
            settings.perspectiveStepPixels, settings.perspectiveMove);
    case 3:
        if (settings.fogQuality <= 0) {
            return SoftwarePerspectiveRasterizer::drawMode0Copy(
                destination, texture, first, second, third,
                settings.perspectiveStepPixels, settings.perspectiveMove);
        }
        if (settings.fogQuality == 2 && !settings.forceLowQualityFog) {
            return SoftwarePerspectiveRasterizer::drawMode3HQ(
                destination, texture, first, second, third, settings.fogColor,
                settings.fogDistance, settings.perspectiveStepPixels,
                settings.perspectiveMove);
        }
        return SoftwarePerspectiveRasterizer::drawMode3LQ(
            destination, texture, first, second, third, settings.fogColor,
            lqFogStrength(settings, first, second, third, true),
            settings.perspectiveStepPixels, settings.perspectiveMove);
    case 4:
        return SoftwarePerspectiveRasterizer::drawMode4AlphaBlend(
            destination, texture, first, second, third,
            settings.perspectiveStepPixels, settings.perspectiveMove);
    case 5:
        if (settings.fogQuality <= 0) {
            return SoftwarePerspectiveRasterizer::drawMode0Copy(
                destination, texture, first, second, third,
                settings.perspectiveStepPixels, settings.perspectiveMove);
        }
        return SoftwarePerspectiveRasterizer::drawMode5(
            destination, texture, first, second, third,
            settings.shadeStrength, settings.fogDistance,
            settings.perspectiveStepPixels, settings.perspectiveMove);
    case 6:
        if (settings.fogQuality == 2 && !settings.forceLowQualityFog) {
            if (settings.mode6UsesVertexLight) {
                return SoftwarePerspectiveRasterizer::drawMode6HQ(
                    destination, texture, first, second, third,
                    settings.fogDistance, brightness[0], brightness[1],
                    brightness[2], settings.perspectiveStepPixels,
                    settings.perspectiveMove);
            }
            return SoftwarePerspectiveRasterizer::drawMode6HQ(
                destination, texture, first, second, third, settings.fogDistance,
                settings.perspectiveStepPixels, settings.perspectiveMove);
        }
        if (settings.fogQuality <= 0) {
            return SoftwarePerspectiveRasterizer::drawMode0Copy(
                destination, texture, first, second, third,
                settings.perspectiveStepPixels, settings.perspectiveMove);
        }
        return SoftwarePerspectiveRasterizer::drawMode6LQ(
            destination, texture, first, second, third,
            settings.fallbackShadeStrength, settings.fogDistance,
            settings.perspectiveStepPixels, settings.perspectiveMove);
    case 7:
        return SoftwarePerspectiveRasterizer::drawMode7Glass(
            destination, texture, first, second, third,
            settings.perspectiveStepPixels, settings.perspectiveMove);
    case 9:
        if (settings.fogQuality < 1 || whiteFactors) {
            return SoftwarePerspectiveRasterizer::drawMode0Copy(
                destination, texture, first, second, third,
                settings.perspectiveStepPixels, settings.perspectiveMove);
        }
        return SoftwarePerspectiveRasterizer::drawMode9Shade(
            destination, texture, first, second, third,
            brightness[0], brightness[1], brightness[2],
            settings.perspectiveStepPixels, settings.perspectiveMove);
    case 13:
        if (settings.fogQuality < 1 || whiteFactors) {
            return SoftwarePerspectiveRasterizer::drawMode0Copy(
                destination, texture, first, second, third,
                settings.perspectiveStepPixels, settings.perspectiveMove);
        }
        if (settings.fogQuality == 1 || colorsAreGrayscale(settings)) {
            return SoftwarePerspectiveRasterizer::drawMode9Shade(
                destination, texture, first, second, third,
                brightness[0], brightness[1], brightness[2],
                settings.perspectiveStepPixels, settings.perspectiveMove);
        }
        return SoftwarePerspectiveRasterizer::drawMode13(
            destination, texture, first, second, third,
            settings.color[0], settings.color[1], settings.color[2],
            settings.perspectiveStepPixels, settings.perspectiveMove);
    default:
        return false;
    }
}

} // anonymous namespace

SoftwareTriangleRenderSettings::SoftwareTriangleRenderSettings()
    : drawMode(0), fogQuality(0), usePerspectiveCorrection(false),
      forceLowQualityFog(false), textureAlphaMixing(true),
      mode6UsesVertexLight(false), perspectiveStepPixels(24),
      perspectiveMove(150), fogColor(0),
      packedFogColor(0), hasPackedFogColor(false), fogDistance(1),
      shadeStrength(0), fallbackShadeStrength(0), waterDistance(10000),
      fillSampleU(0), fillSampleV(0)
{
    int i;
    for (i = 0; i < 3; ++i) {
        brightness[i] = 255;
        ditherGradient[i] = 255;
        color[i] = AffineTriangleColor(255, 255, 255);
    }
}

bool SoftwareTriangleRenderer::draw(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    const SoftwareTriangleRenderSettings& settings)
{
    QInt32 drawMode = settings.drawMode;
    if (!RenderModePolicy::isSupportedTextureDrawMode(drawMode)) return false;
    if (drawMode == 4 && !settings.textureAlphaMixing) drawMode = 0;
    if (drawMode == 8 || (drawMode >= 10 && drawMode <= 12)) {
        return drawAffine(destination, texture, first, second, third,
                          drawMode, settings);
    }
    if (settings.usePerspectiveCorrection) {
        return drawPerspective(destination, texture, first, second, third,
                               drawMode, settings);
    }
    return drawAffine(destination, texture, first, second, third,
                      drawMode, settings);
}

} // namespace Rendering
} // namespace code
