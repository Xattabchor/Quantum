#include "software_affine_rasterizer.h"

#include "quantum_integer.h"

#include <stddef.h>

namespace code {
namespace Rendering {

using code::Math::QInt32;
using code::Math::QInt64;
using code::Math::QUint32;

namespace {

const unsigned int KScreenFractionBits = 14U;
const unsigned int KTextureFractionBits = 12U;

enum RasterPixelMode {
    ERasterPixelCopy,
    ERasterPixelFogAdd,
    ERasterPixelSurfaceAdd,
    ERasterPixelFogBlendLow,
    ERasterPixelFogBlendHigh,
    ERasterPixelAlphaBlend,
    ERasterPixelShade,
    ERasterPixelFill,
    ERasterPixelGlass,
    ERasterPixelMode9,
    ERasterPixelMode13
};

struct RasterPixelParameters {
    QInt32 first;
    QInt32 second;
    QInt32 third;

    RasterPixelParameters(QInt32 firstValue = 0,
                          QInt32 secondValue = 0,
                          QInt32 thirdValue = 0)
        : first(firstValue), second(secondValue), third(thirdValue)
    {
    }
};

bool validSurface(const SoftwareRenderSurfaceView& surface)
{
    if (surface.width < 0 || surface.height < 0 || surface.stride < 0 ||
        surface.pixelCount < 0) {
        return false;
    }
    if (surface.width == 0 || surface.height == 0) {
        return true;
    }
    if (surface.pixels == 0 || surface.pixelCount == 0 ||
        surface.stride < surface.width) {
        return false;
    }

    const QInt64 required =
        static_cast<QInt64>(surface.height - 1) * surface.stride +
        surface.width;
    return required <= surface.pixelCount;
}

bool validTexture(const SoftwareTextureView& texture)
{
    if (texture.pixels == 0 || texture.pixelCount <= 0 ||
        texture.width <= 0 || texture.height <= 0) {
        return false;
    }

    const QInt64 texelCount =
        static_cast<QInt64>(texture.width) * texture.height;
    return texelCount <= texture.pixelCount;
}

QInt32 textureWidthBit(QInt32 width)
{
    QInt32 bit;
    for (bit = 0; bit < 31; ++bit) {
        if (width == static_cast<QInt32>(
                static_cast<QUint32>(1U) << static_cast<unsigned int>(bit))) {
            return bit;
        }
    }
    return 0;
}

void swapVertices(AffineTriangleVertex& left,
                  AffineTriangleVertex& right)
{
    const AffineTriangleVertex temporary(left);
    left = right;
    right = temporary;
}

bool computeSlope(QInt32 coordinateDelta,
                  QInt32 rowDelta,
                  unsigned int fractionBits,
                  QInt32& slope)
{
    if (rowDelta == 0) {
        return false;
    }
    slope = code::Math::javaIntDiv(
        code::Math::javaShiftLeft(coordinateDelta, fractionBits), rowDelta);
    return true;
}

QInt32 advance(QInt32 initial, QInt32 slope, QInt32 rows)
{
    return code::Math::javaIntAdd(initial,
        code::Math::javaIntMul(slope, rows));
}

void swapTriangleColors(AffineTriangleColor& left,
                        AffineTriangleColor& right)
{
    const AffineTriangleColor temporary(left);
    left = right;
    right = temporary;
}

bool computeColorSlope(const AffineTriangleColor& from,
                       const AffineTriangleColor& to,
                       QInt32 rowDelta,
                       AffineTriangleColor& slope)
{
    return computeSlope(code::Math::javaIntSub(to.redFactor, from.redFactor),
                        rowDelta, KTextureFractionBits, slope.redFactor) &&
           computeSlope(code::Math::javaIntSub(to.greenFactor, from.greenFactor),
                        rowDelta, KTextureFractionBits, slope.greenFactor) &&
           computeSlope(code::Math::javaIntSub(to.blueFactor, from.blueFactor),
                        rowDelta, KTextureFractionBits, slope.blueFactor);
}

AffineTriangleColor shiftColor(const AffineTriangleColor& color)
{
    return AffineTriangleColor(
        code::Math::javaShiftLeft(color.redFactor, KTextureFractionBits),
        code::Math::javaShiftLeft(color.greenFactor, KTextureFractionBits),
        code::Math::javaShiftLeft(color.blueFactor, KTextureFractionBits));
}

AffineTriangleColor advanceColor(const AffineTriangleColor& initial,
                                 const AffineTriangleColor& slope,
                                 QInt32 rows)
{
    return AffineTriangleColor(
        advance(initial.redFactor, slope.redFactor, rows),
        advance(initial.greenFactor, slope.greenFactor, rows),
        advance(initial.blueFactor, slope.blueFactor, rows));
}

AffineTriangleColor addColors(const AffineTriangleColor& left,
                              const AffineTriangleColor& right)
{
    return AffineTriangleColor(
        code::Math::javaIntAdd(left.redFactor, right.redFactor),
        code::Math::javaIntAdd(left.greenFactor, right.greenFactor),
        code::Math::javaIntAdd(left.blueFactor, right.blueFactor));
}

QInt32 mode3VertexFogWeight(QInt32 depthRz, QInt32 fogDistance)
{
    QInt32 fog = code::Math::javaIntDiv(
        code::Math::javaIntMul(code::Math::javaIntSub(0, depthRz), 255),
        fogDistance);
    if (fog > 255) fog = 255;
    if (fog < 0) fog = 0;
    return code::Math::javaIntSub(255, fog);
}

QInt32 sampleTexture(const SoftwareTextureView& texture,
                     QInt32 widthBit,
                     QUint32 pixelMask,
                     QInt32 u,
                     QInt32 v)
{
    const QUint32 texelRow =
        static_cast<QUint32>(v) >> KTextureFractionBits;
    const QUint32 texelColumn =
        static_cast<QUint32>(u) >> KTextureFractionBits;
    const QUint32 index =
        ((texelRow << static_cast<unsigned int>(widthBit)) + texelColumn) &
        pixelMask;
    return texture.pixels[static_cast<size_t>(index)];
}

template <RasterPixelMode Mode>
inline void writePixel(const SoftwareRenderSurfaceView& destination,
                       size_t destinationIndex,
                       QInt32 texel,
                       const RasterPixelParameters& parameters,
                       QInt32& fogAccumulator,
                       QInt32 fogSlope,
                       AffineTriangleColor& colorAccumulator,
                       const AffineTriangleColor& colorSlope,
                       QInt32 logicalDestinationIndex)
{
    if (Mode == ERasterPixelFill) {
        destination.pixels[destinationIndex] = parameters.first;
        return;
    }
    if (Mode == ERasterPixelGlass) {
        const QUint32 texelBits = static_cast<QUint32>(texel);
        const QInt32 offsetX = code::Math::javaShiftRight(
            code::Math::javaIntMul(
                code::Math::javaIntSub(
                    static_cast<QInt32>(texelBits & 0x00ff0000U), 0x00800000),
                parameters.first),
            24U);
        const QInt32 offsetY = code::Math::javaShiftRight(
            code::Math::javaIntMul(
                code::Math::javaIntSub(
                    static_cast<QInt32>(texelBits & 0x0000ff00U), 0x00008000),
                parameters.first),
            16U);
        QInt32 coord = code::Math::javaIntAdd(
            code::Math::javaIntAdd(
                offsetX,
                code::Math::javaIntMul(offsetY, destination.width)),
            logicalDestinationIndex);
        /* Java clamps in a packed width*height display; map through stride later. */
        const QInt32 lastLogicalPixel =
            code::Math::javaIntSub(parameters.second, 1);
        if (coord < 0) {
            coord = 0;
        } else if (coord > lastLogicalPixel) {
            coord = lastLogicalPixel;
        }
        const QInt32 sourceY = code::Math::javaIntDiv(coord, destination.width);
        const QInt32 sourceX = code::Math::javaIntSub(
            coord, code::Math::javaIntMul(sourceY, destination.width));
        const size_t sourceIndex =
            static_cast<size_t>(sourceY) *
                static_cast<size_t>(destination.stride) +
            static_cast<size_t>(sourceX);
        destination.pixels[destinationIndex] = destination.pixels[sourceIndex];
        return;
    }
    if (Mode == ERasterPixelMode9) {
        if (texel == 0) {
            return;
        }
        colorAccumulator.redFactor = code::Math::javaIntAdd(
            colorAccumulator.redFactor, colorSlope.redFactor);
        const QUint32 texelBits = static_cast<QUint32>(texel);
        const QInt32 redBlue = code::Math::javaIntMul(
            static_cast<QInt32>(texelBits & 0x00ff00ffU),
            code::Math::javaShiftRightUnsigned(
                colorAccumulator.redFactor, KTextureFractionBits));
        const QInt32 green = code::Math::javaIntMul(
            static_cast<QInt32>(texelBits & 0x0000ff00U),
            code::Math::javaShiftRightUnsigned(
                colorAccumulator.redFactor, KTextureFractionBits));
        const QUint32 blended =
            ((static_cast<QUint32>(redBlue) & 0xff00ff00U) |
             (static_cast<QUint32>(green) & 0x00ff0000U)) >> 8U;
        destination.pixels[destinationIndex] =
            code::Math::javaIntFromUnsigned(blended);
        return;
    }
    if (Mode == ERasterPixelMode13) {
        if (texel == 0) {
            return;
        }
        colorAccumulator.redFactor = code::Math::javaIntAdd(
            colorAccumulator.redFactor, colorSlope.redFactor);
        colorAccumulator.greenFactor = code::Math::javaIntAdd(
            colorAccumulator.greenFactor, colorSlope.greenFactor);
        colorAccumulator.blueFactor = code::Math::javaIntAdd(
            colorAccumulator.blueFactor, colorSlope.blueFactor);

        const QUint32 texelBits = static_cast<QUint32>(texel);
        const QInt32 red = code::Math::javaIntMul(
            static_cast<QInt32>(texelBits & 0x00ff0000U),
            code::Math::javaShiftRightUnsigned(
                colorAccumulator.redFactor, KTextureFractionBits));
        const QInt32 green = code::Math::javaIntMul(
            static_cast<QInt32>(texelBits & 0x0000ff00U),
            code::Math::javaShiftRightUnsigned(
                colorAccumulator.greenFactor, KTextureFractionBits));
        const QInt32 blue = code::Math::javaIntMul(
            static_cast<QInt32>(texelBits & 0x000000ffU),
            code::Math::javaShiftRightUnsigned(
                colorAccumulator.blueFactor, KTextureFractionBits));
        const QUint32 blended =
            ((static_cast<QUint32>(red) & 0xff000000U) |
             (static_cast<QUint32>(green) & 0x00ff0000U) |
             static_cast<QUint32>(blue)) >> 8U;
        destination.pixels[destinationIndex] =
            code::Math::javaIntFromUnsigned(blended);
        return;
    }
    if (texel == 0) {
        return;
    }
    if (Mode == ERasterPixelAlphaBlend) {
        const QUint32 texelBits = static_cast<QUint32>(texel);
        const QUint32 destinationBits = static_cast<QUint32>(
            destination.pixels[destinationIndex]);
        const QInt32 alpha = code::Math::javaShiftRightUnsigned(texel, 24U);
        const QInt32 destinationWeight = code::Math::javaIntSub(255, alpha);
        const QInt32 redBlue = code::Math::javaIntAdd(
            code::Math::javaIntMul(
                static_cast<QInt32>(texelBits & 0x00ff00ffU), alpha),
            code::Math::javaIntMul(
                static_cast<QInt32>(destinationBits & 0x00ff00ffU),
                destinationWeight));
        const QInt32 green = code::Math::javaIntAdd(
            code::Math::javaIntMul(
                static_cast<QInt32>(texelBits & 0x0000ff00U), alpha),
            code::Math::javaIntMul(
                static_cast<QInt32>(destinationBits & 0x0000ff00U),
                destinationWeight));
        const QUint32 blended =
            ((static_cast<QUint32>(redBlue) & 0xff00ff00U) |
             (static_cast<QUint32>(green) & 0x00ff0000U)) >> 8U;
        destination.pixels[destinationIndex] =
            code::Math::javaIntFromUnsigned(blended);
        return;
    }
    if (Mode == ERasterPixelShade) {
        const QUint32 texelBits = static_cast<QUint32>(texel);
        const QInt32 redBlue = code::Math::javaIntMul(
            static_cast<QInt32>(texelBits & 0x00ff00ffU), parameters.first);
        const QInt32 green = code::Math::javaIntMul(
            static_cast<QInt32>(texelBits & 0x0000ff00U), parameters.first);
        const QUint32 shaded =
            ((static_cast<QUint32>(redBlue) & 0xff00ff00U) |
             (static_cast<QUint32>(green) & 0x00ff0000U)) >> 8U;
        destination.pixels[destinationIndex] =
            code::Math::javaIntFromUnsigned(shaded);
        return;
    }
    if (Mode == ERasterPixelFogBlendHigh) {
        fogAccumulator = code::Math::javaIntAdd(fogAccumulator, fogSlope);
        const QInt32 textureWeight = code::Math::javaShiftRightUnsigned(
            fogAccumulator, KTextureFractionBits);
        const QInt32 fogWeight =
            code::Math::javaIntSub(255, textureWeight);
        const QUint32 texelBits = static_cast<QUint32>(texel);
        const QInt32 redBlue = code::Math::javaIntAdd(
            code::Math::javaIntMul(parameters.first, fogWeight),
            code::Math::javaIntMul(
                static_cast<QInt32>(texelBits & 0x00ff00ffU),
                textureWeight));
        const QInt32 green = code::Math::javaIntAdd(
            code::Math::javaIntMul(parameters.second, fogWeight),
            code::Math::javaIntMul(
                static_cast<QInt32>(texelBits & 0x0000ff00U),
                textureWeight));
        const QUint32 blended =
            ((static_cast<QUint32>(redBlue) & 0xff00ff00U) |
             (static_cast<QUint32>(green) & 0x00ff0000U)) >> 8U;
        destination.pixels[destinationIndex] =
            code::Math::javaIntFromUnsigned(blended);
        return;
    }
    if (Mode == ERasterPixelCopy) {
        destination.pixels[destinationIndex] = texel;
        return;
    }
    if (Mode == ERasterPixelFogBlendLow) {
        const QUint32 texelBits = static_cast<QUint32>(texel);
        const QInt32 redBlue = code::Math::javaIntAdd(
            code::Math::javaIntMul(
                static_cast<QInt32>(texelBits & 0x00ff00ffU),
                parameters.first),
            parameters.second);
        const QInt32 green = code::Math::javaIntAdd(
            code::Math::javaIntMul(
                static_cast<QInt32>(texelBits & 0x0000ff00U),
                parameters.first),
            parameters.third);
        const QUint32 blended =
            ((static_cast<QUint32>(redBlue) & 0xff00ff00U) |
             (static_cast<QUint32>(green) & 0x00ff0000U)) >> 8;
        destination.pixels[destinationIndex] =
            code::Math::javaIntFromUnsigned(blended);
        return;
    }

    const QUint32 texelRgb =
        static_cast<QUint32>(texel) & 0x00fefefeU;
    QUint32 addend;
    if (Mode == ERasterPixelFogAdd) {
        addend = static_cast<QUint32>(parameters.first) & 0x00fefefeU;
    } else {
        addend = static_cast<QUint32>(destination.pixels[destinationIndex]) &
                 0x00fefefeU;
    }

    const QUint32 sum = texelRgb + addend;
    const QUint32 channelCarryMask =
        ((sum >> 8) & 0x00010101U) * 0xffU;
    destination.pixels[destinationIndex] =
        code::Math::javaIntFromUnsigned(sum | channelCarryMask);
}

template <RasterPixelMode Mode>
void drawSpan(const SoftwareRenderSurfaceView& destination,
              const SoftwareTextureView& texture,
              QInt32 widthBit,
              QUint32 textureMask,
              QInt32 screenY,
              QInt32 leftFixed,
              QInt32 rightFixed,
              QInt32 leftU,
              QInt32 leftV,
              QInt32 rightU,
              QInt32 rightV,
              QInt32 leftFog,
              QInt32 rightFog,
              const AffineTriangleColor& leftColor,
              const AffineTriangleColor& rightColor,
              const AffineTriangleColor& horizontalColorSlope,
              QInt32 du,
              QInt32 dv,
              QInt32 df,
              const RasterPixelParameters& parameters)
{
    QInt32 x1;
    QInt32 x2;
    QInt32 u;
    QInt32 v;
    QInt32 fog;
    QInt32 logicalIndex;
    AffineTriangleColor color;
    AffineTriangleColor colorSlope;

    if (leftFixed > rightFixed) {
        x1 = code::Math::javaShiftRight(rightFixed, KScreenFractionBits);
        u = rightU;
        v = rightV;
        fog = rightFog;
        color = rightColor;
        colorSlope = horizontalColorSlope;
        x2 = code::Math::javaShiftRight(leftFixed, KScreenFractionBits);
    } else {
        x1 = code::Math::javaShiftRight(leftFixed, KScreenFractionBits);
        u = leftU;
        v = leftV;
        fog = leftFog;
        color = leftColor;
        colorSlope = horizontalColorSlope;
        x2 = code::Math::javaShiftRight(rightFixed, KScreenFractionBits);
    }

    if (x1 < 0) {
        u = code::Math::javaIntSub(u, code::Math::javaIntMul(du, x1));
        v = code::Math::javaIntSub(v, code::Math::javaIntMul(dv, x1));
        fog = code::Math::javaIntSub(fog, code::Math::javaIntMul(df, x1));
        if (Mode == ERasterPixelMode13 || Mode == ERasterPixelMode9) {
            color.redFactor = code::Math::javaIntSub(
                color.redFactor,
                code::Math::javaIntMul(colorSlope.redFactor, x1));
            if (Mode == ERasterPixelMode13) {
                color.greenFactor = code::Math::javaIntSub(
                    color.greenFactor,
                    code::Math::javaIntMul(colorSlope.greenFactor, x1));
                /* paint13Mini applies the green x-clipping correction twice. */
                color.greenFactor = code::Math::javaIntSub(
                    color.greenFactor,
                    code::Math::javaIntMul(colorSlope.greenFactor, x1));
                color.blueFactor = code::Math::javaIntSub(
                    color.blueFactor,
                    code::Math::javaIntMul(colorSlope.blueFactor, x1));
            }
        }
        x1 = 0;
    }
    if (x2 > destination.width) {
        x2 = destination.width;
    }

    if (x2 <= 0 || x1 >= destination.width || x1 >= x2) {
        return;
    }

    const size_t rowOffset = static_cast<size_t>(screenY) *
                             static_cast<size_t>(destination.stride);
    size_t destinationIndex = rowOffset + static_cast<size_t>(x1);
    logicalIndex = 0;
    if (Mode == ERasterPixelGlass) {
        logicalIndex = code::Math::javaIntAdd(
            code::Math::javaIntMul(screenY, destination.width), x1);
    }
    u = code::Math::javaIntSub(u, du);
    v = code::Math::javaIntSub(v, dv);
    fog = code::Math::javaIntSub(fog, df);
    if (Mode == ERasterPixelMode13 || Mode == ERasterPixelMode9) {
        if (color.redFactor < 0) color.redFactor = 0;
        const QInt32 spanLength = code::Math::javaIntSub(x2, x1);
        if (code::Math::javaIntAdd(
                color.redFactor,
                code::Math::javaIntMul(colorSlope.redFactor, spanLength)) < 0) {
            colorSlope.redFactor = code::Math::javaIntDiv(
                code::Math::javaIntSub(0, color.redFactor), spanLength);
        }
        if (Mode == ERasterPixelMode13) {
            if (color.greenFactor < 0) color.greenFactor = 0;
            if (color.blueFactor < 0) color.blueFactor = 0;
            if (code::Math::javaIntAdd(
                    color.greenFactor,
                    code::Math::javaIntMul(colorSlope.greenFactor, spanLength)) < 0) {
                colorSlope.greenFactor = code::Math::javaIntDiv(
                    code::Math::javaIntSub(0, color.greenFactor), spanLength);
            }
            if (code::Math::javaIntAdd(
                    color.blueFactor,
                    code::Math::javaIntMul(colorSlope.blueFactor, spanLength)) < 0) {
                colorSlope.blueFactor = code::Math::javaIntDiv(
                    code::Math::javaIntSub(0, color.blueFactor), spanLength);
            }
        }
    }

    while (x2 - x1 >= 6) {
        u = code::Math::javaIntAdd(u, du);
        v = code::Math::javaIntAdd(v, dv);
        writePixel<Mode>(destination, destinationIndex,
                   sampleTexture(texture, widthBit, textureMask, u, v),
                   parameters, fog, df, color, colorSlope, logicalIndex);
        if (Mode == ERasterPixelGlass) {
            logicalIndex = code::Math::javaIntAdd(logicalIndex, 1);
        }

        u = code::Math::javaIntAdd(u, du);
        v = code::Math::javaIntAdd(v, dv);
        writePixel<Mode>(destination, destinationIndex + 1,
                   sampleTexture(texture, widthBit, textureMask, u, v),
                   parameters, fog, df, color, colorSlope, logicalIndex);
        if (Mode == ERasterPixelGlass) {
            logicalIndex = code::Math::javaIntAdd(logicalIndex, 1);
        }

        u = code::Math::javaIntAdd(u, du);
        v = code::Math::javaIntAdd(v, dv);
        writePixel<Mode>(destination, destinationIndex + 2,
                   sampleTexture(texture, widthBit, textureMask, u, v),
                   parameters, fog, df, color, colorSlope, logicalIndex);
        if (Mode == ERasterPixelGlass) {
            logicalIndex = code::Math::javaIntAdd(logicalIndex, 1);
        }

        u = code::Math::javaIntAdd(u, du);
        v = code::Math::javaIntAdd(v, dv);
        writePixel<Mode>(destination, destinationIndex + 3,
                   sampleTexture(texture, widthBit, textureMask, u, v),
                   parameters, fog, df, color, colorSlope, logicalIndex);
        if (Mode == ERasterPixelGlass) {
            logicalIndex = code::Math::javaIntAdd(logicalIndex, 1);
        }

        u = code::Math::javaIntAdd(u, du);
        v = code::Math::javaIntAdd(v, dv);
        writePixel<Mode>(destination, destinationIndex + 4,
                   sampleTexture(texture, widthBit, textureMask, u, v),
                   parameters, fog, df, color, colorSlope, logicalIndex);
        if (Mode == ERasterPixelGlass) {
            logicalIndex = code::Math::javaIntAdd(logicalIndex, 1);
        }

        u = code::Math::javaIntAdd(u, du);
        v = code::Math::javaIntAdd(v, dv);
        writePixel<Mode>(destination, destinationIndex + 5,
                   sampleTexture(texture, widthBit, textureMask, u, v),
                   parameters, fog, df, color, colorSlope, logicalIndex);
        if (Mode == ERasterPixelGlass) {
            logicalIndex = code::Math::javaIntAdd(logicalIndex, 1);
        }

        x1 += 6;
        destinationIndex += 6;
    }

    while (x1 < x2) {
        u = code::Math::javaIntAdd(u, du);
        v = code::Math::javaIntAdd(v, dv);
        writePixel<Mode>(destination, destinationIndex,
                   sampleTexture(texture, widthBit, textureMask, u, v),
                   parameters, fog, df, color, colorSlope, logicalIndex);
        if (Mode == ERasterPixelGlass) {
            logicalIndex = code::Math::javaIntAdd(logicalIndex, 1);
        }
        ++x1;
        ++destinationIndex;
    }
}

} // anonymous namespace

SoftwareRenderSurfaceView::SoftwareRenderSurfaceView()
    : pixels(0), pixelCount(0), width(0), height(0), stride(0)
{
}

SoftwareRenderSurfaceView::SoftwareRenderSurfaceView(
    QInt32* surfacePixels,
    QInt32 surfacePixelCount,
    QInt32 surfaceWidth,
    QInt32 surfaceHeight,
    QInt32 surfaceStride)
    : pixels(surfacePixels), pixelCount(surfacePixelCount), width(surfaceWidth),
      height(surfaceHeight), stride(surfaceStride)
{
}

SoftwareTextureView::SoftwareTextureView()
    : pixels(0), pixelCount(0), width(0), height(0),
      hasTransparentTexels(false)
{
}

SoftwareTextureView::SoftwareTextureView(const QInt32* texturePixels,
                                         QInt32 texturePixelCount,
                                         QInt32 textureWidth,
                                         QInt32 textureHeight,
                                         bool textureHasTransparentTexels)
    : pixels(texturePixels), pixelCount(texturePixelCount), width(textureWidth),
      height(textureHeight), hasTransparentTexels(textureHasTransparentTexels)
{
}

AffineTriangleVertex::AffineTriangleVertex()
    : screenX(0), screenY(0), textureU(0), textureV(0), depthRz(0)
{
}

AffineTriangleVertex::AffineTriangleVertex(QInt32 x,
                                           QInt32 y,
                                           QInt32 u,
                                           QInt32 v)
    : screenX(x), screenY(y), textureU(u), textureV(v), depthRz(0)
{
}

AffineTriangleVertex::AffineTriangleVertex(QInt32 x,
                                           QInt32 y,
                                           QInt32 u,
                                           QInt32 v,
                                           QInt32 rz)
    : screenX(x), screenY(y), textureU(u), textureV(v), depthRz(rz)
{
}

AffineTriangleColor::AffineTriangleColor()
    : redFactor(0), greenFactor(0), blueFactor(0)
{
}

AffineTriangleColor::AffineTriangleColor(QInt32 red,
                                         QInt32 green,
                                         QInt32 blue)
    : redFactor(red), greenFactor(green), blueFactor(blue)
{
}

namespace {

template <RasterPixelMode Mode>
bool drawAffineTriangle(const SoftwareRenderSurfaceView& destination,
                        const SoftwareTextureView& texture,
                        const AffineTriangleVertex& first,
                        const AffineTriangleVertex& second,
                        const AffineTriangleVertex& third,
                        const RasterPixelParameters& parameters,
                        const AffineTriangleColor* vertexColors = 0)
{
    if (!validSurface(destination)) {
        return false;
    }
    if (destination.width == 0 || destination.height == 0) {
        return true;
    }
    if (!validTexture(texture)) {
        return false;
    }

    AffineTriangleVertex a(first);
    AffineTriangleVertex b(second);
    AffineTriangleVertex c(third);
    AffineTriangleColor colorA;
    AffineTriangleColor colorB;
    AffineTriangleColor colorC;

    if (Mode == ERasterPixelMode13 || Mode == ERasterPixelMode9) {
        if (vertexColors == 0) {
            return false;
        }
        colorA = vertexColors[0];
        colorB = vertexColors[1];
        colorC = vertexColors[2];
    }

    if (b.screenY < a.screenY) {
        swapVertices(a, b);
        if (Mode == ERasterPixelMode13 || Mode == ERasterPixelMode9) swapTriangleColors(colorA, colorB);
    }
    if (c.screenY < a.screenY) {
        swapVertices(a, c);
        if (Mode == ERasterPixelMode13 || Mode == ERasterPixelMode9) swapTriangleColors(colorA, colorC);
    }
    if (c.screenY < b.screenY) {
        swapVertices(b, c);
        if (Mode == ERasterPixelMode13 || Mode == ERasterPixelMode9) swapTriangleColors(colorB, colorC);
    }
    if (a.screenY == c.screenY) {
        return true;
    }

    QInt32 dxStart;
    QInt32 duStart;
    QInt32 dvStart;
    QInt32 dxEnd = 0;
    QInt32 duEnd = 0;
    QInt32 dvEnd = 0;
    QInt32 dfStart = 0;
    QInt32 dfEnd = 0;
    QInt32 aFog = 0;
    QInt32 bFog = 0;
    QInt32 cFog = 0;
    QInt32 xStart;
    QInt32 uStart;
    QInt32 vStart;
    QInt32 fStart = 0;
    QInt32 xEnd;
    QInt32 uEnd;
    QInt32 vEnd;
    QInt32 fEnd = 0;
    QInt32 du;
    QInt32 dv;
    QInt32 df = 0;
    QInt32 temp;
    AffineTriangleColor colorSlopeStart;
    AffineTriangleColor colorSlopeEnd;
    AffineTriangleColor colorStart;
    AffineTriangleColor colorEnd;
    AffineTriangleColor colorSlope;

    if (Mode == ERasterPixelFogBlendHigh) {
        aFog = mode3VertexFogWeight(a.depthRz, parameters.third);
        bFog = mode3VertexFogWeight(b.depthRz, parameters.third);
        cFog = mode3VertexFogWeight(c.depthRz, parameters.third);
        temp = code::Math::javaIntSub(c.screenY, a.screenY);
        if (!computeSlope(code::Math::javaIntSub(cFog, aFog), temp,
                          KTextureFractionBits, dfStart)) {
            return true;
        }
        if (b.screenY != a.screenY) {
            temp = code::Math::javaIntSub(b.screenY, a.screenY);
            if (!computeSlope(code::Math::javaIntSub(bFog, aFog), temp,
                              KTextureFractionBits, dfEnd)) {
                return true;
            }
        }
    }

    if (Mode == ERasterPixelMode13 || Mode == ERasterPixelMode9) {
        temp = code::Math::javaIntSub(c.screenY, a.screenY);
        if (!computeColorSlope(colorA, colorC, temp, colorSlopeStart)) {
            return true;
        }
        if (b.screenY != a.screenY) {
            temp = code::Math::javaIntSub(b.screenY, a.screenY);
            if (!computeColorSlope(colorA, colorB, temp, colorSlopeEnd)) {
                return true;
            }
        }
    }

    temp = code::Math::javaIntSub(c.screenY, a.screenY);
    if (!computeSlope(code::Math::javaIntSub(c.screenX, a.screenX), temp,
                      KScreenFractionBits, dxStart) ||
        !computeSlope(code::Math::javaIntSub(c.textureU, a.textureU), temp,
                      KTextureFractionBits, duStart) ||
        !computeSlope(code::Math::javaIntSub(c.textureV, a.textureV), temp,
                      KTextureFractionBits, dvStart)) {
        return true;
    }

    if (b.screenY != a.screenY) {
        temp = code::Math::javaIntSub(b.screenY, a.screenY);
        if (!computeSlope(code::Math::javaIntSub(b.screenX, a.screenX), temp,
                          KScreenFractionBits, dxEnd) ||
            !computeSlope(code::Math::javaIntSub(b.textureU, a.textureU), temp,
                          KTextureFractionBits, duEnd) ||
            !computeSlope(code::Math::javaIntSub(b.textureV, a.textureV), temp,
                          KTextureFractionBits, dvEnd)) {
            return true;
        }
    }

    const QInt32 fixedAX =
        code::Math::javaShiftLeft(a.screenX, KScreenFractionBits);
    const QInt32 fixedAU =
        code::Math::javaShiftLeft(a.textureU, KTextureFractionBits);
    const QInt32 fixedAV =
        code::Math::javaShiftLeft(a.textureV, KTextureFractionBits);
    const QInt32 fixedAF =
        code::Math::javaShiftLeft(aFog, KTextureFractionBits);
    const QInt32 fixedBX =
        code::Math::javaShiftLeft(b.screenX, KScreenFractionBits);
    const QInt32 fixedBU =
        code::Math::javaShiftLeft(b.textureU, KTextureFractionBits);
    const QInt32 fixedBV =
        code::Math::javaShiftLeft(b.textureV, KTextureFractionBits);
    const QInt32 fixedBF =
        code::Math::javaShiftLeft(bFog, KTextureFractionBits);
    const AffineTriangleColor fixedColorA = shiftColor(colorA);
    const AffineTriangleColor fixedColorB = shiftColor(colorB);

    temp = code::Math::javaIntSub(b.screenY, a.screenY);
    xStart = advance(fixedAX, dxStart, temp);
    uStart = advance(fixedAU, duStart, temp);
    vStart = advance(fixedAV, dvStart, temp);
    fStart = advance(fixedAF, dfStart, temp);
    colorStart = advanceColor(fixedColorA, colorSlopeStart, temp);
    xEnd = fixedBX;
    uEnd = fixedBU;
    vEnd = fixedBV;
    fEnd = fixedBF;
    colorEnd = fixedColorB;

    temp = code::Math::javaShiftRight(
        code::Math::javaIntSub(xStart, xEnd), KScreenFractionBits);
    if (temp == 0) {
        return true;
    }
    du = code::Math::javaIntDiv(code::Math::javaIntSub(uStart, uEnd), temp);
    dv = code::Math::javaIntDiv(code::Math::javaIntSub(vStart, vEnd), temp);
    df = code::Math::javaIntDiv(code::Math::javaIntSub(fStart, fEnd), temp);
    colorSlope = AffineTriangleColor(
        code::Math::javaIntDiv(
            code::Math::javaIntSub(colorStart.redFactor, colorEnd.redFactor),
            temp),
        code::Math::javaIntDiv(
            code::Math::javaIntSub(colorStart.greenFactor, colorEnd.greenFactor),
            temp),
        code::Math::javaIntDiv(
            code::Math::javaIntSub(colorStart.blueFactor, colorEnd.blueFactor),
            temp));
    if (Mode == ERasterPixelMode9) {
        if (colorSlope.redFactor > 0) {
            colorSlope.redFactor = code::Math::javaIntSub(
                colorSlope.redFactor, 1);
        }
        if (colorSlope.redFactor < 0) {
            colorSlope.redFactor = code::Math::javaIntAdd(
                colorSlope.redFactor, 1);
        }
    }

    xStart = fixedAX;
    uStart = fixedAU;
    vStart = fixedAV;
    fStart = fixedAF;
    colorStart = fixedColorA;
    xEnd = fixedAX;
    uEnd = fixedAU;
    vEnd = fixedAV;
    fEnd = fixedAF;
    colorEnd = fixedColorA;

    QInt32 yStart = a.screenY;
    const QInt32 yEnd = c.screenY < destination.height
        ? c.screenY : destination.height;
    if (yEnd <= 0 || yStart >= yEnd) {
        return true;
    }

    /* Skip off-screen rows in O(1), preserving Java's wrapped edge stepping. */
    if (yStart < 0) {
        yStart = 0;
        const QInt32 rowsFromA = code::Math::javaIntSub(yStart, a.screenY);
        xStart = advance(fixedAX, dxStart, rowsFromA);
        uStart = advance(fixedAU, duStart, rowsFromA);
        vStart = advance(fixedAV, dvStart, rowsFromA);
        fStart = advance(fixedAF, dfStart, rowsFromA);
        colorStart = advanceColor(fixedColorA, colorSlopeStart, rowsFromA);

        if (yStart >= b.screenY) {
            temp = code::Math::javaIntSub(c.screenY, b.screenY);
            if (!computeSlope(code::Math::javaIntSub(c.screenX, b.screenX),
                              temp, KScreenFractionBits, dxEnd) ||
                !computeSlope(code::Math::javaIntSub(c.textureU, b.textureU),
                              temp, KTextureFractionBits, duEnd) ||
                !computeSlope(code::Math::javaIntSub(c.textureV, b.textureV),
                              temp, KTextureFractionBits, dvEnd)) {
                return true;
            }
            if (Mode == ERasterPixelFogBlendHigh &&
                !computeSlope(code::Math::javaIntSub(cFog, bFog), temp,
                              KTextureFractionBits, dfEnd)) {
                return true;
            }
            if ((Mode == ERasterPixelMode13 || Mode == ERasterPixelMode9) &&
                !computeColorSlope(colorB, colorC, temp, colorSlopeEnd)) {
                return true;
            }
            temp = code::Math::javaIntSub(yStart, b.screenY);
            xEnd = advance(fixedBX, dxEnd, temp);
            uEnd = advance(fixedBU, duEnd, temp);
            vEnd = advance(fixedBV, dvEnd, temp);
            fEnd = advance(fixedBF, dfEnd, temp);
            colorEnd = advanceColor(fixedColorB, colorSlopeEnd, temp);
        } else {
            temp = code::Math::javaIntSub(yStart, a.screenY);
            xEnd = advance(fixedAX, dxEnd, temp);
            uEnd = advance(fixedAU, duEnd, temp);
            vEnd = advance(fixedAV, dvEnd, temp);
            fEnd = advance(fixedAF, dfEnd, temp);
            colorEnd = advanceColor(fixedColorA, colorSlopeEnd, temp);
        }
    }

    const QInt32 widthBit = textureWidthBit(texture.width);
    const QUint32 textureMask = static_cast<QUint32>(texture.pixelCount - 1);

    for (; yStart < yEnd; ++yStart,
             xStart = code::Math::javaIntAdd(xStart, dxStart),
             uStart = code::Math::javaIntAdd(uStart, duStart),
             vStart = code::Math::javaIntAdd(vStart, dvStart),
             fStart = code::Math::javaIntAdd(fStart, dfStart),
             xEnd = code::Math::javaIntAdd(xEnd, dxEnd),
             uEnd = code::Math::javaIntAdd(uEnd, duEnd),
             vEnd = code::Math::javaIntAdd(vEnd, dvEnd),
             fEnd = code::Math::javaIntAdd(fEnd, dfEnd)) {
        if (yStart == b.screenY) {
            if (c.screenY == b.screenY) {
                return true;
            }

            temp = code::Math::javaIntSub(b.screenY, a.screenY);
            xStart = advance(fixedAX, dxStart, temp);
            uStart = advance(fixedAU, duStart, temp);
            vStart = advance(fixedAV, dvStart, temp);
            fStart = advance(fixedAF, dfStart, temp);
            colorStart = advanceColor(fixedColorA, colorSlopeStart, temp);
            xEnd = fixedBX;
            uEnd = fixedBU;
            vEnd = fixedBV;
            fEnd = fixedBF;
            colorEnd = fixedColorB;

            temp = code::Math::javaIntSub(c.screenY, b.screenY);
            if (!computeSlope(code::Math::javaIntSub(c.screenX, b.screenX),
                              temp, KScreenFractionBits, dxEnd) ||
                !computeSlope(code::Math::javaIntSub(c.textureU, b.textureU),
                              temp, KTextureFractionBits, duEnd) ||
                !computeSlope(code::Math::javaIntSub(c.textureV, b.textureV),
                              temp, KTextureFractionBits, dvEnd)) {
                return true;
            }
            if (Mode == ERasterPixelFogBlendHigh &&
                !computeSlope(code::Math::javaIntSub(cFog, bFog), temp,
                              KTextureFractionBits, dfEnd)) {
                return true;
            }
            if ((Mode == ERasterPixelMode13 || Mode == ERasterPixelMode9) &&
                !computeColorSlope(colorB, colorC, temp, colorSlopeEnd)) {
                return true;
            }
        }

        drawSpan<Mode>(destination, texture, widthBit, textureMask, yStart,
                       xStart, xEnd, uStart, vStart, uEnd, vEnd,
                       fStart, fEnd, colorStart, colorEnd, colorSlope,
                       du, dv, df, parameters);
        if (Mode == ERasterPixelMode13 || Mode == ERasterPixelMode9) {
            colorStart = addColors(colorStart, colorSlopeStart);
            colorEnd = addColors(colorEnd, colorSlopeEnd);
        }
    }

    return true;
}

bool drawDitherSpan(const SoftwareRenderSurfaceView& destination,
                    const SoftwareTextureView& texture,
                    QInt32 widthBit,
                    QInt32 lengthMask,
                    QUint32 textureMask,
                    QInt32 screenY,
                    QInt32 leftFixed,
                    QInt32 rightFixed,
                    QInt32 leftGradient,
                    QInt32 rightGradient,
                    QInt32 horizontalGradientSlope,
                    QInt32 ditherY)
{
    QInt32 x1;
    QInt32 x2;
    QInt32 gradient;
    if (leftFixed > rightFixed) {
        x1 = code::Math::javaShiftRight(rightFixed, KScreenFractionBits);
        x2 = code::Math::javaShiftRight(leftFixed, KScreenFractionBits);
        gradient = rightGradient;
    } else {
        x1 = code::Math::javaShiftRight(leftFixed, KScreenFractionBits);
        x2 = code::Math::javaShiftRight(rightFixed, KScreenFractionBits);
        gradient = leftGradient;
    }

    if (x1 < 0) {
        gradient = code::Math::javaIntSub(
            gradient, code::Math::javaIntMul(horizontalGradientSlope, x1));
        x1 = 0;
    }
    if (x2 > destination.width) x2 = destination.width;
    if (x2 <= 0 || x1 >= destination.width || x1 >= x2) return true;

    QInt32 logicalIndex = code::Math::javaIntAdd(
        code::Math::javaIntMul(screenY, destination.width), x1);
    const size_t rowOffset = static_cast<size_t>(screenY) *
                             static_cast<size_t>(destination.stride);
    const size_t destinationStart = rowOffset + static_cast<size_t>(x1);
    QInt32 textureRow = code::Math::javaShiftRight(
        gradient, KTextureFractionBits);
    QInt32 x;
    for (x = x1; x < x2; ++x) {
        const QInt32 ditherCoordinate = code::Math::javaIntAdd(
            logicalIndex, ditherY);
        const QInt32 maskedCoordinate = code::Math::javaIntFromUnsigned(
            static_cast<QUint32>(ditherCoordinate) &
            static_cast<QUint32>(lengthMask));
        const QInt32 textureRowOffset = code::Math::javaShiftLeft(
            textureRow, static_cast<unsigned int>(widthBit));
        const QInt32 sampleIndex = code::Math::javaIntAdd(
            maskedCoordinate, textureRowOffset);
        const QUint32 wrappedIndex = static_cast<QUint32>(sampleIndex) &
                                     textureMask;
        destination.pixels[destinationStart +
                           static_cast<size_t>(x - x1)] =
            texture.pixels[static_cast<size_t>(wrappedIndex)];
        gradient = code::Math::javaIntAdd(
            gradient, horizontalGradientSlope);
        textureRow = code::Math::javaShiftRight(
            gradient, KTextureFractionBits);
        logicalIndex = code::Math::javaIntAdd(logicalIndex, 1);
    }
    return true;
}

bool drawDitherGradient(const SoftwareRenderSurfaceView& destination,
                        const SoftwareTextureView& texture,
                        const AffineTriangleVertex& first,
                        const AffineTriangleVertex& second,
                        const AffineTriangleVertex& third,
                        QInt32 firstGradient,
                        QInt32 secondGradient,
                        QInt32 thirdGradient)
{
    if (!validSurface(destination)) return false;
    if (destination.width == 0 || destination.height == 0) return true;
    if (!validTexture(texture)) return false;

    const QInt32 widthInHeight = code::Math::javaIntDiv(
        texture.height, texture.width);
    if (widthInHeight <= 0) return false;
    const QInt32 maskBase = code::Math::javaIntDiv(
        texture.pixelCount, widthInHeight);
    if (maskBase <= 0) return false;

    QInt32 factors[3];
    factors[0] = firstGradient;
    factors[1] = secondGradient;
    factors[2] = thirdGradient;
    AffineTriangleVertex a(first);
    AffineTriangleVertex b(second);
    AffineTriangleVertex c(third);
    AffineTriangleColor colorA;
    AffineTriangleColor colorB;
    AffineTriangleColor colorC;
    colorA.redFactor = code::Math::javaIntDiv(
        code::Math::javaIntMul(factors[0], widthInHeight), 256);
    colorB.redFactor = code::Math::javaIntDiv(
        code::Math::javaIntMul(factors[1], widthInHeight), 256);
    colorC.redFactor = code::Math::javaIntDiv(
        code::Math::javaIntMul(factors[2], widthInHeight), 256);
    const QInt32 maximumRow = code::Math::javaIntSub(widthInHeight, 1);
    if (colorA.redFactor > maximumRow) colorA.redFactor = maximumRow;
    if (colorB.redFactor > maximumRow) colorB.redFactor = maximumRow;
    if (colorC.redFactor > maximumRow) colorC.redFactor = maximumRow;

    if (b.screenY < a.screenY) {
        swapVertices(a, b);
        swapTriangleColors(colorA, colorB);
    }
    if (c.screenY < a.screenY) {
        swapVertices(a, c);
        swapTriangleColors(colorA, colorC);
    }
    if (c.screenY < b.screenY) {
        swapVertices(b, c);
        swapTriangleColors(colorB, colorC);
    }
    if (a.screenY == c.screenY) return true;

    QInt32 dxStart;
    QInt32 duStart;
    QInt32 dxEnd = 0;
    QInt32 duEnd = 0;
    QInt32 temp;
    temp = code::Math::javaIntSub(c.screenY, a.screenY);
    if (!computeSlope(code::Math::javaIntSub(c.screenX, a.screenX), temp,
                      KScreenFractionBits, dxStart) ||
        !computeSlope(code::Math::javaIntSub(
                          colorC.redFactor, colorA.redFactor), temp,
                      KTextureFractionBits, duStart)) {
        return true;
    }
    if (b.screenY != a.screenY) {
        temp = code::Math::javaIntSub(b.screenY, a.screenY);
        if (!computeSlope(code::Math::javaIntSub(b.screenX, a.screenX), temp,
                          KScreenFractionBits, dxEnd) ||
            !computeSlope(code::Math::javaIntSub(
                              colorB.redFactor, colorA.redFactor), temp,
                          KTextureFractionBits, duEnd)) {
            return true;
        }
    }

    const QInt32 fixedAX = code::Math::javaShiftLeft(
        a.screenX, KScreenFractionBits);
    const QInt32 fixedAU = code::Math::javaShiftLeft(
        colorA.redFactor, KTextureFractionBits);
    const QInt32 fixedBX = code::Math::javaShiftLeft(
        b.screenX, KScreenFractionBits);
    const QInt32 fixedBU = code::Math::javaShiftLeft(
        colorB.redFactor, KTextureFractionBits);
    temp = code::Math::javaIntSub(b.screenY, a.screenY);
    QInt32 xStart = advance(fixedAX, dxStart, temp);
    QInt32 uStart = advance(fixedAU, duStart, temp);
    QInt32 xEnd = fixedBX;
    QInt32 uEnd = fixedBU;
    temp = code::Math::javaShiftRight(
        code::Math::javaIntSub(xStart, xEnd), KScreenFractionBits);
    if (temp == 0) return true;
    QInt32 horizontalGradientSlope = code::Math::javaIntDiv(
        code::Math::javaIntSub(uStart, uEnd), temp);
    if (horizontalGradientSlope > 0) {
        horizontalGradientSlope = code::Math::javaIntSub(
            horizontalGradientSlope, 1);
    }
    if (horizontalGradientSlope < 0) {
        horizontalGradientSlope = code::Math::javaIntAdd(
            horizontalGradientSlope, 1);
    }

    xStart = fixedAX;
    uStart = fixedAU;
    xEnd = fixedAX;
    uEnd = fixedAU;
    QInt32 yStart = a.screenY;
    const QInt32 yEnd = c.screenY < destination.height
        ? c.screenY : destination.height;
    if (yEnd <= 0 || yStart >= yEnd) return true;

    if (yStart < 0) {
        yStart = 0;
        const QInt32 rowsFromA = code::Math::javaIntSub(yStart, a.screenY);
        xStart = advance(fixedAX, dxStart, rowsFromA);
        uStart = advance(fixedAU, duStart, rowsFromA);
        if (yStart >= b.screenY) {
            temp = code::Math::javaIntSub(c.screenY, b.screenY);
            if (!computeSlope(code::Math::javaIntSub(c.screenX, b.screenX),
                              temp, KScreenFractionBits, dxEnd) ||
                !computeSlope(code::Math::javaIntSub(
                                  colorC.redFactor, colorB.redFactor), temp,
                              KTextureFractionBits, duEnd)) {
                return true;
            }
            const QInt32 rowsFromB = code::Math::javaIntSub(yStart, b.screenY);
            xEnd = advance(fixedBX, dxEnd, rowsFromB);
            uEnd = advance(fixedBU, duEnd, rowsFromB);
        } else {
            xEnd = advance(fixedAX, dxEnd, rowsFromA);
            uEnd = advance(fixedAU, duEnd, rowsFromA);
        }
    }

    QInt32 ditherY = code::Math::javaIntMul(a.screenY, texture.width);
    const QInt32 ditherRowMove = code::Math::javaIntSub(
        texture.width, destination.width);
    const QInt32 widthBit = textureWidthBit(
        code::Math::javaIntMul(texture.width, texture.width));
    const QInt32 lengthMask = code::Math::javaIntSub(maskBase, 1);
    const QUint32 textureMask = static_cast<QUint32>(texture.pixelCount - 1);

    for (; yStart < yEnd; ++yStart) {
        if (yStart == b.screenY) {
            if (c.screenY == b.screenY) return true;
            temp = code::Math::javaIntSub(b.screenY, a.screenY);
            xStart = advance(fixedAX, dxStart, temp);
            uStart = advance(fixedAU, duStart, temp);
            xEnd = fixedBX;
            uEnd = fixedBU;
            temp = code::Math::javaIntSub(c.screenY, b.screenY);
            if (!computeSlope(code::Math::javaIntSub(c.screenX, b.screenX),
                              temp, KScreenFractionBits, dxEnd) ||
                !computeSlope(code::Math::javaIntSub(
                                  colorC.redFactor, colorB.redFactor), temp,
                              KTextureFractionBits, duEnd)) {
                return true;
            }
        }

        ditherY = code::Math::javaIntAdd(ditherY, ditherRowMove);
        if (!drawDitherSpan(destination, texture, widthBit, lengthMask,
                            textureMask, yStart, xStart, xEnd, uStart, uEnd,
                            horizontalGradientSlope, ditherY)) {
            return false;
        }
        xStart = code::Math::javaIntAdd(xStart, dxStart);
        uStart = code::Math::javaIntAdd(uStart, duStart);
        xEnd = code::Math::javaIntAdd(xEnd, dxEnd);
        uEnd = code::Math::javaIntAdd(uEnd, duEnd);
    }
    return true;
}

} // anonymous namespace

bool SoftwareAffineRasterizer::drawMode0(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third)
{
    return drawAffineTriangle<ERasterPixelCopy>(
        destination, texture, first, second, third, RasterPixelParameters());
}

bool SoftwareAffineRasterizer::drawMode1(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 packedFogColor)
{
    return drawAffineTriangle<ERasterPixelFogAdd>(
        destination, texture, first, second, third,
        RasterPixelParameters(packedFogColor));
}

bool SoftwareAffineRasterizer::drawMode2(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third)
{
    return drawAffineTriangle<ERasterPixelSurfaceAdd>(
        destination, texture, first, second, third, RasterPixelParameters());
}

bool SoftwareAffineRasterizer::drawMode3LQ(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 fogColor,
    QInt32 fogStrength)
{
    if (fogStrength > 255) fogStrength = 255;
    if (fogStrength < 0) fogStrength = 0;

    const QInt32 textureWeight = code::Math::javaIntSub(255, fogStrength);
    const QUint32 fogBits = static_cast<QUint32>(fogColor);
    const QInt32 redBlueFog = code::Math::javaIntMul(
        static_cast<QInt32>(fogBits & 0x00ff00ffU), fogStrength);
    const QInt32 greenFog = code::Math::javaIntMul(
        static_cast<QInt32>(fogBits & 0x0000ff00U), fogStrength);

    return drawAffineTriangle<ERasterPixelFogBlendLow>(
        destination, texture, first, second, third,
        RasterPixelParameters(textureWeight, redBlueFog, greenFog));
}

bool SoftwareAffineRasterizer::drawMode3HQ(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 fogColor,
    QInt32 fogDistance)
{
    if (fogDistance == 0) {
        return false;
    }

    const QUint32 fogBits = static_cast<QUint32>(fogColor);
    return drawAffineTriangle<ERasterPixelFogBlendHigh>(
        destination, texture, first, second, third,
        RasterPixelParameters(
            static_cast<QInt32>(fogBits & 0x00ff00ffU),
            static_cast<QInt32>(fogBits & 0x0000ff00U),
            fogDistance));
}

bool SoftwareAffineRasterizer::drawMode4AlphaBlend(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third)
{
    return drawAffineTriangle<ERasterPixelAlphaBlend>(
        destination, texture, first, second, third, RasterPixelParameters());
}

bool SoftwareAffineRasterizer::drawMode5Shade(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 shadeStrength)
{
    if (shadeStrength > 255) shadeStrength = 255;
    if (shadeStrength < 0) shadeStrength = 0;
    const QInt32 brightness = code::Math::javaIntSub(255, shadeStrength);
    return drawAffineTriangle<ERasterPixelShade>(
        destination, texture, first, second, third,
        RasterPixelParameters(brightness));
}

bool SoftwareAffineRasterizer::drawMode6LQ(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 fallbackShadeStrength,
    QInt32 fogDistance)
{
    QInt32 farthestDepth = first.depthRz;
    if (second.depthRz > farthestDepth) farthestDepth = second.depthRz;
    if (third.depthRz > farthestDepth) farthestDepth = third.depthRz;
    const QInt32 shadeStrength = mode3FogStrength(
        farthestDepth, fogDistance, fallbackShadeStrength);
    return drawMode5Shade(
        destination, texture, first, second, third, shadeStrength);
}

bool SoftwareAffineRasterizer::drawMode6HQ(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 firstBrightness,
    QInt32 secondBrightness,
    QInt32 thirdBrightness)
{
    return drawMode9(destination, texture, first, second, third,
                     firstBrightness, secondBrightness, thirdBrightness);
}

bool SoftwareAffineRasterizer::drawMode8Fill(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 sampleU,
    QInt32 sampleV)
{
    if (!validTexture(texture)) {
        return false;
    }
    const QUint32 mask = static_cast<QUint32>(texture.pixelCount - 1);
    const QInt32 widthBit = textureWidthBit(texture.width);
    const QUint32 index = static_cast<QUint32>(
        code::Math::javaShiftLeft(sampleV,
                                  static_cast<unsigned int>(widthBit))) +
        static_cast<QUint32>(sampleU);
    const QInt32 color = texture.pixels[index & mask];
    const AffineTriangleVertex fillA(
        first.screenX, first.screenY, 0, 0, first.depthRz);
    const AffineTriangleVertex fillB(
        second.screenX, second.screenY, 0, 0, second.depthRz);
    const AffineTriangleVertex fillC(
        third.screenX, third.screenY, 0, 0, third.depthRz);
    return drawAffineTriangle<ERasterPixelFill>(
        destination, SoftwareTextureView(&color, 1, 1, 1),
        fillA, fillB, fillC, RasterPixelParameters(color));
}

bool SoftwareAffineRasterizer::drawMode7Glass(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third)
{
    const QInt32 dimensions = code::Math::javaIntAdd(
        destination.height, destination.width);
    const QInt32 scale = code::Math::javaIntDiv(
        code::Math::javaIntMul(64, dimensions), 560);
    const QInt32 logicalPixelCount = code::Math::javaIntMul(
        destination.width, destination.height);
    return drawAffineTriangle<ERasterPixelGlass>(
        destination, texture, first, second, third,
        RasterPixelParameters(scale, logicalPixelCount));
}

bool SoftwareAffineRasterizer::drawMode9(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 firstBrightness,
    QInt32 secondBrightness,
    QInt32 thirdBrightness)
{
    const AffineTriangleColor colors[3] = {
        AffineTriangleColor(firstBrightness, 0, 0),
        AffineTriangleColor(secondBrightness, 0, 0),
        AffineTriangleColor(thirdBrightness, 0, 0)
    };
    return drawAffineTriangle<ERasterPixelMode9>(
        destination, texture, first, second, third,
        RasterPixelParameters(), colors);
}

bool SoftwareAffineRasterizer::drawMode10DitherGradient(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 firstGradient,
    QInt32 secondGradient,
    QInt32 thirdGradient)
{
    return drawDitherGradient(destination, texture, first, second, third,
                              firstGradient, secondGradient, thirdGradient);
}

namespace {

QInt32 depthDitherGradient(QInt32 depthRz, QInt32 distance)
{
    QInt32 gradient = code::Math::javaIntAdd(
        255, code::Math::javaIntDiv(
                 code::Math::javaIntMul(depthRz, 255), distance));
    if (gradient > 255) gradient = 255;
    if (gradient < 0) gradient = 0;
    return gradient;
}

} // anonymous namespace

bool SoftwareAffineRasterizer::drawMode11DitherWater(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 waterDistance)
{
    if (waterDistance == 0) return false;
    return drawMode10DitherGradient(
        destination, texture, first, second, third,
        depthDitherGradient(first.depthRz, waterDistance),
        depthDitherGradient(second.depthRz, waterDistance),
        depthDitherGradient(third.depthRz, waterDistance));
}

bool SoftwareAffineRasterizer::drawMode12DitherFog(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 fogDistance)
{
    if (fogDistance == 0) return false;
    return drawMode10DitherGradient(
        destination, texture, first, second, third,
        depthDitherGradient(first.depthRz, fogDistance),
        depthDitherGradient(second.depthRz, fogDistance),
        depthDitherGradient(third.depthRz, fogDistance));
}

bool SoftwareAffineRasterizer::drawMode13(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    const AffineTriangleColor& firstColor,
    const AffineTriangleColor& secondColor,
    const AffineTriangleColor& thirdColor)
{
    AffineTriangleColor vertexColors[3];
    vertexColors[0] = firstColor;
    vertexColors[1] = secondColor;
    vertexColors[2] = thirdColor;
    return drawAffineTriangle<ERasterPixelMode13>(
        destination, texture, first, second, third,
        RasterPixelParameters(), vertexColors);
}

QInt32 SoftwareAffineRasterizer::mode3FogStrength(
    QInt32 farthestDepth,
    QInt32 fogDistance,
    QInt32 fallbackStrength)
{
    const QInt32 fogStep = code::Math::javaIntDiv(fogDistance, 255);
    QInt32 strength = fallbackStrength;
    if (fogStep != 0) {
        strength = code::Math::javaIntDiv(
            code::Math::javaIntSub(0, farthestDepth), fogStep);
    }
    if (strength > 255) strength = 255;
    if (strength < 0) strength = 0;
    return strength;
}

QInt32 SoftwareAffineRasterizer::mode1FogAddColor(
    QInt32 nearestDepth,
    QInt32 fogDistance,
    QInt32 fogColor)
{
    const QInt32 negativeDepth = code::Math::javaIntSub(0, nearestDepth);
    QInt32 fogAmount = code::Math::javaIntDiv(negativeDepth, 255);
    const QInt32 fogStep = code::Math::javaIntDiv(fogDistance, 255);
    if (fogStep != 0) {
        fogAmount = code::Math::javaIntDiv(negativeDepth, fogStep);
    }

    if (fogAmount > 255) fogAmount = 255;
    if (fogAmount < 0) fogAmount = 0;
    fogAmount = code::Math::javaIntSub(255, fogAmount);

    const QUint32 fogBits = static_cast<QUint32>(fogColor);
    QInt32 red = static_cast<QInt32>((fogBits >> 16) & 0xffU) - fogAmount;
    QInt32 green = static_cast<QInt32>((fogBits >> 8) & 0xffU) - fogAmount;
    QInt32 blue = static_cast<QInt32>(fogBits & 0xffU) - fogAmount;
    if (red < 0) red = 0;
    if (green < 0) green = 0;
    if (blue < 0) blue = 0;

    const QUint32 packed =
        (static_cast<QUint32>(red) << 16) |
        (static_cast<QUint32>(green) << 8) |
        static_cast<QUint32>(blue);
    return code::Math::javaIntFromUnsigned(packed);
}

} // namespace Rendering
} // namespace code
