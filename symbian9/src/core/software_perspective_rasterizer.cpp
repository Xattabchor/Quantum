#include "software_perspective_rasterizer.h"

#include "quantum_integer.h"

#include <stddef.h>

namespace code {
namespace Rendering {

using code::Math::QInt32;
using code::Math::QInt64;
using code::Math::QUint32;

namespace {

const unsigned int KTextureFractionBits = 12U;
const QInt32 KTextureFraction = 4096;

enum PerspectivePixelMode {
    EPerspectiveFogAdd,
    EPerspectiveSurfaceAdd,
    EPerspectiveFogBlendLow,
    EPerspectiveFogBlendHigh,
    EPerspectiveAlphaBlend,
    EPerspectiveShade,
    EPerspectiveVertexShade,
    EPerspectiveVertexShadeOpaque,
    EPerspectiveVertexShadeFloor6,
    EPerspectiveGlass,
    EPerspectiveMode13
};

struct PerspectivePixelParameters {
    QInt32 first;
    QInt32 second;
    QInt32 third;

    PerspectivePixelParameters(QInt32 firstValue = 0,
                               QInt32 secondValue = 0,
                               QInt32 thirdValue = 0)
        : first(firstValue), second(secondValue), third(thirdValue)
    {
    }
};

struct PerspectiveVertex {
    QInt32 screenX;
    QInt32 screenY;
    QInt32 reciprocalDepth;
    QInt32 uOverDepth;
    QInt32 vOverDepth;
    AffineTriangleColor color;

    PerspectiveVertex()
        : screenX(0), screenY(0), reciprocalDepth(0), uOverDepth(0),
          vOverDepth(0), color()
    {
    }
};

struct FixedPerspectivePoint {
    QInt32 screenX;
    QInt32 reciprocalDepth;
    QInt32 uOverDepth;
    QInt32 vOverDepth;
    AffineTriangleColor color;

    FixedPerspectivePoint()
        : screenX(0), reciprocalDepth(0), uOverDepth(0), vOverDepth(0),
          color()
    {
    }
};

struct PerspectiveSlope {
    QInt32 screenX;
    QInt32 reciprocalDepth;
    QInt32 uOverDepth;
    QInt32 vOverDepth;
    AffineTriangleColor color;

    PerspectiveSlope()
        : screenX(0), reciprocalDepth(0), uOverDepth(0), vOverDepth(0),
          color()
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

QInt32 textureWUnit(QInt32 maximumDimension)
{
    if (maximumDimension <= 256) return 1073741824;
    if (maximumDimension <= 512) return 536870912;
    if (maximumDimension <= 1024) return 268435456;
    if (maximumDimension <= 2048) return 134217728;
    if (maximumDimension <= 4096) return 67108864;
    if (maximumDimension <= 8192) return 33554432;
    return 16777216;
}

bool makePerspectiveVertex(const AffineTriangleVertex& source,
                           QInt32 reciprocalNumerator,
                           QInt32 move,
                           PerspectiveVertex& result,
                           const AffineTriangleColor& color =
                               AffineTriangleColor())
{
    QInt32 denominator = move;
    if (source.depthRz < 0) {
        denominator = code::Math::javaIntAdd(
            code::Math::javaIntSub(0, source.depthRz), move);
    }
    if (denominator == 0) {
        return false;
    }

    result.screenX = source.screenX;
    result.screenY = source.screenY;
    result.reciprocalDepth = code::Math::javaIntDiv(
        reciprocalNumerator, denominator);
    result.uOverDepth = code::Math::javaIntMul(
        source.textureU, result.reciprocalDepth);
    result.vOverDepth = code::Math::javaIntMul(
        source.textureV, result.reciprocalDepth);
    result.color = color;
    return true;
}

FixedPerspectivePoint toFixed(const PerspectiveVertex& vertex)
{
    FixedPerspectivePoint result;
    result.screenX = code::Math::javaShiftLeft(
        vertex.screenX, KTextureFractionBits);
    result.reciprocalDepth = code::Math::javaShiftLeft(
        vertex.reciprocalDepth, KTextureFractionBits);
    result.uOverDepth = code::Math::javaShiftLeft(
        vertex.uOverDepth, KTextureFractionBits);
    result.vOverDepth = code::Math::javaShiftLeft(
        vertex.vOverDepth, KTextureFractionBits);
    result.color.redFactor = code::Math::javaShiftLeft(
        vertex.color.redFactor, KTextureFractionBits);
    result.color.greenFactor = code::Math::javaShiftLeft(
        vertex.color.greenFactor, KTextureFractionBits);
    result.color.blueFactor = code::Math::javaShiftLeft(
        vertex.color.blueFactor, KTextureFractionBits);
    return result;
}

void swapVertices(PerspectiveVertex& left, PerspectiveVertex& right)
{
    const PerspectiveVertex temporary(left);
    left = right;
    right = temporary;
}

bool computeSlope(QInt32 start,
                  QInt32 end,
                  QInt32 rows,
                  QInt32& slope)
{
    if (rows == 0) {
        return false;
    }
    slope = code::Math::javaIntDiv(
        code::Math::javaShiftLeft(
            code::Math::javaIntSub(end, start), KTextureFractionBits),
        rows);
    return true;
}

bool computeSlope(const PerspectiveVertex& start,
                  const PerspectiveVertex& end,
                  QInt32 rows,
                  PerspectiveSlope& slope)
{
    return computeSlope(start.screenX, end.screenX, rows, slope.screenX) &&
           computeSlope(start.reciprocalDepth, end.reciprocalDepth, rows,
                        slope.reciprocalDepth) &&
           computeSlope(start.uOverDepth, end.uOverDepth, rows,
                        slope.uOverDepth) &&
           computeSlope(start.vOverDepth, end.vOverDepth, rows,
                        slope.vOverDepth) &&
           computeSlope(start.color.redFactor, end.color.redFactor, rows,
                        slope.color.redFactor) &&
           computeSlope(start.color.greenFactor, end.color.greenFactor, rows,
                        slope.color.greenFactor) &&
           computeSlope(start.color.blueFactor, end.color.blueFactor, rows,
                        slope.color.blueFactor);
}

/* Copy/textured-only triangles do not interpolate vertex colors. */
bool computeTextureSlope(const PerspectiveVertex& start,
                         const PerspectiveVertex& end,
                         QInt32 rows,
                         PerspectiveSlope& slope)
{
    return computeSlope(start.screenX, end.screenX, rows, slope.screenX) &&
           computeSlope(start.reciprocalDepth, end.reciprocalDepth, rows,
                        slope.reciprocalDepth) &&
           computeSlope(start.uOverDepth, end.uOverDepth, rows,
                        slope.uOverDepth) &&
           computeSlope(start.vOverDepth, end.vOverDepth, rows,
                        slope.vOverDepth);
}

QInt32 advance(QInt32 initial, QInt32 slope, QInt32 rows)
{
    return code::Math::javaIntAdd(
        initial, code::Math::javaIntMul(slope, rows));
}

QInt32 javaRemainder(QInt32 numerator, QInt32 denominator)
{
    return code::Math::javaIntSub(
        numerator,
        code::Math::javaIntMul(
            code::Math::javaIntDiv(numerator, denominator), denominator));
}

FixedPerspectivePoint advance(const FixedPerspectivePoint& initial,
                              const PerspectiveSlope& slope,
                              QInt32 rows)
{
    FixedPerspectivePoint result;
    result.screenX = advance(initial.screenX, slope.screenX, rows);
    result.reciprocalDepth = advance(
        initial.reciprocalDepth, slope.reciprocalDepth, rows);
    result.uOverDepth = advance(
        initial.uOverDepth, slope.uOverDepth, rows);
    result.vOverDepth = advance(
        initial.vOverDepth, slope.vOverDepth, rows);
    result.color.redFactor = advance(
        initial.color.redFactor, slope.color.redFactor, rows);
    result.color.greenFactor = advance(
        initial.color.greenFactor, slope.color.greenFactor, rows);
    result.color.blueFactor = advance(
        initial.color.blueFactor, slope.color.blueFactor, rows);
    return result;
}

FixedPerspectivePoint advanceTexture(const FixedPerspectivePoint& initial,
                                     const PerspectiveSlope& slope,
                                     QInt32 rows)
{
    FixedPerspectivePoint result;
    result.screenX = advance(initial.screenX, slope.screenX, rows);
    result.reciprocalDepth = advance(
        initial.reciprocalDepth, slope.reciprocalDepth, rows);
    result.uOverDepth = advance(
        initial.uOverDepth, slope.uOverDepth, rows);
    result.vOverDepth = advance(
        initial.vOverDepth, slope.vOverDepth, rows);
    return result;
}

/* One row of modular additions equals the original slope * row offset. */
void advanceOneRow(FixedPerspectivePoint& point,
                   const PerspectiveSlope& slope)
{
    point.screenX = code::Math::javaIntAdd(point.screenX, slope.screenX);
    point.reciprocalDepth = code::Math::javaIntAdd(
        point.reciprocalDepth, slope.reciprocalDepth);
    point.uOverDepth = code::Math::javaIntAdd(
        point.uOverDepth, slope.uOverDepth);
    point.vOverDepth = code::Math::javaIntAdd(
        point.vOverDepth, slope.vOverDepth);
    point.color.redFactor = code::Math::javaIntAdd(
        point.color.redFactor, slope.color.redFactor);
    point.color.greenFactor = code::Math::javaIntAdd(
        point.color.greenFactor, slope.color.greenFactor);
    point.color.blueFactor = code::Math::javaIntAdd(
        point.color.blueFactor, slope.color.blueFactor);
}

void advanceOneTextureRow(FixedPerspectivePoint& point,
                          const PerspectiveSlope& slope)
{
    point.screenX = code::Math::javaIntAdd(point.screenX, slope.screenX);
    point.reciprocalDepth = code::Math::javaIntAdd(
        point.reciprocalDepth, slope.reciprocalDepth);
    point.uOverDepth = code::Math::javaIntAdd(
        point.uOverDepth, slope.uOverDepth);
    point.vOverDepth = code::Math::javaIntAdd(
        point.vOverDepth, slope.vOverDepth);
}

QInt64 arithmeticShiftRight12(QInt64 value)
{
    const QInt64 divisor = 4096;
    if (value >= 0) {
        return value / divisor;
    }
    const QInt64 magnitude = -value;
    return -((magnitude + divisor - 1) / divisor);
}

QInt32 adjustForFraction(QInt32 value, QInt32 horizontalSlope, QInt32 fraction)
{
    const QInt64 product = code::Math::javaLongMulInt(
        horizontalSlope, fraction);
    const QInt64 adjusted = static_cast<QInt64>(value) -
                            arithmeticShiftRight12(product);
    return code::Math::javaIntFromLong(adjusted);
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

bool perspectiveDivide(QInt32 numerator,
                       QInt32 denominator,
                       QInt32& result)
{
    if (denominator == 0) {
        return false;
    }
    if (numerator == 0) {
        result = 0;
        return true;
    }

    /*
     * Java evaluates ((long) numerator << 12) / denominator, then narrows.
     * Split the quotient into its integer and 12-bit fractional parts so the
     * ARMv5 path needs only a 32-bit divide. Extend a nonzero remainder one
     * bit at a time. Since remainder < denominator magnitude <= 2^31, doubling
     * the remainder fits in 32 unsigned bits. Exact integer quotients need no
     * fractional-bit loop.
     */
    const QUint32 numeratorBits = static_cast<QUint32>(numerator);
    const QUint32 denominatorBits = static_cast<QUint32>(denominator);
    const QUint32 numeratorMagnitude = numerator < 0
        ? static_cast<QUint32>(static_cast<QUint32>(0) - numeratorBits)
        : numeratorBits;
    const QUint32 denominatorMagnitude = denominator < 0
        ? static_cast<QUint32>(static_cast<QUint32>(0) - denominatorBits)
        : denominatorBits;
    const bool negative = (numerator < 0) != (denominator < 0);
    const QUint32 whole = numeratorMagnitude / denominatorMagnitude;
    QUint32 remainder = numeratorMagnitude - whole * denominatorMagnitude;
    QUint32 fractional = 0;
    if (remainder != 0U) {
        unsigned int bit;
        for (bit = 0; bit < KTextureFractionBits; ++bit) {
            fractional <<= 1U;
            remainder += remainder;
            if (remainder >= denominatorMagnitude) {
                remainder -= denominatorMagnitude;
                fractional |= 1U;
            }
        }
    }

    QUint32 quotientBits =
        (whole << KTextureFractionBits) | fractional;
    if (negative) {
        quotientBits = static_cast<QUint32>(static_cast<QUint32>(0) -
                                             quotientBits);
    }
    result = code::Math::javaIntFromUnsigned(quotientBits);
    return true;
}

code::Math::QInt32 sampleTexture(const SoftwareTextureView& texture,
                                 QInt32 widthBit,
                                 QUint32 pixelMask,
                                 QUint32 uBits,
                                 QUint32 vBits)
{
    const QUint32 texelRow = vBits >> KTextureFractionBits;
    const QUint32 texelColumn = uBits >> KTextureFractionBits;
    const QUint32 index =
        ((texelRow << static_cast<unsigned int>(widthBit)) + texelColumn) &
        pixelMask;
    return texture.pixels[static_cast<size_t>(index)];
}

template <PerspectivePixelMode Mode>
void writePerspectivePixel(
    const SoftwareRenderSurfaceView& destination,
    size_t destinationIndex,
    QInt32 texel,
    const PerspectivePixelParameters& parameters,
    AffineTriangleColor& colorAccumulator,
    const AffineTriangleColor& colorSlope,
    QInt32 logicalDestinationIndex)
{
    if (Mode == EPerspectiveGlass) {
        const QUint32 texelBits = static_cast<QUint32>(texel);
        const QInt32 offsetX = code::Math::javaShiftRight(
            code::Math::javaIntMul(
                code::Math::javaIntSub(
                    static_cast<QInt32>(texelBits & 0x00ff0000U), 0x00800000),
                parameters.first), 24U);
        const QInt32 offsetY = code::Math::javaShiftRight(
            code::Math::javaIntMul(
                code::Math::javaIntSub(
                    static_cast<QInt32>(texelBits & 0x0000ff00U), 0x00008000),
                parameters.first), 16U);
        QInt32 coord = code::Math::javaIntAdd(
            code::Math::javaIntAdd(
                offsetX,
                code::Math::javaIntMul(offsetY, destination.width)),
            logicalDestinationIndex);
        const QInt32 lastLogicalPixel = code::Math::javaIntSub(
            parameters.second, 1);
        if (coord < 0) coord = 0;
        else if (coord > lastLogicalPixel) coord = lastLogicalPixel;
        const QInt32 sourceY = code::Math::javaIntDiv(coord, destination.width);
        const QInt32 sourceX = code::Math::javaIntSub(
            coord, code::Math::javaIntMul(sourceY, destination.width));
        const size_t sourceIndex = static_cast<size_t>(sourceY) *
                                   static_cast<size_t>(destination.stride) +
                                   static_cast<size_t>(sourceX);
        destination.pixels[destinationIndex] = destination.pixels[sourceIndex];
        return;
    }

    if (Mode != EPerspectiveVertexShadeOpaque && texel == 0) return;
    const QUint32 texelBits = static_cast<QUint32>(texel);

    if (Mode == EPerspectiveMode13) {
        colorAccumulator.redFactor = code::Math::javaIntAdd(
            colorAccumulator.redFactor, colorSlope.redFactor);
        colorAccumulator.greenFactor = code::Math::javaIntAdd(
            colorAccumulator.greenFactor, colorSlope.greenFactor);
        colorAccumulator.blueFactor = code::Math::javaIntAdd(
            colorAccumulator.blueFactor, colorSlope.blueFactor);
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
        const QUint32 output =
            ((static_cast<QUint32>(red) & 0xff000000U) |
             (static_cast<QUint32>(green) & 0x00ff0000U) |
             static_cast<QUint32>(blue)) >> 8U;
        destination.pixels[destinationIndex] =
            code::Math::javaIntFromUnsigned(output);
        return;
    }

    if (Mode == EPerspectiveVertexShade ||
        Mode == EPerspectiveVertexShadeOpaque ||
        Mode == EPerspectiveVertexShadeFloor6) {
        QInt32 brightness;
        if (Mode != EPerspectiveVertexShadeFloor6) {
            const QUint32 shadeBits =
                static_cast<QUint32>(colorAccumulator.redFactor) +
                static_cast<QUint32>(colorSlope.redFactor);
            colorAccumulator.redFactor =
                code::Math::javaIntFromUnsigned(shadeBits);
            brightness = static_cast<QInt32>(
                shadeBits >> KTextureFractionBits);
        } else {
            /* Java's DMODE 6 floor kernel samples one shade per scanline. */
            brightness = code::Math::javaShiftRight(
                colorAccumulator.redFactor, KTextureFractionBits);
            if (brightness < 0) brightness = 0;
        }
        /* Unsigned products reproduce Java int wraparound without conversions. */
        const QUint32 brightnessBits = static_cast<QUint32>(brightness);
        const QUint32 redBlue =
            (texelBits & 0x00ff00ffU) * brightnessBits;
        const QUint32 green =
            (texelBits & 0x0000ff00U) * brightnessBits;
        const QUint32 output =
            ((redBlue & 0xff00ff00U) |
             (green & 0x00ff0000U)) >> 8U;
        /* RGB888 output is at most 0x00ffffff, so this signed cast is safe. */
        destination.pixels[destinationIndex] = static_cast<QInt32>(output);
        return;
    }

    if (Mode == EPerspectiveShade) {
        const QInt32 redBlue = code::Math::javaIntMul(
            static_cast<QInt32>(texelBits & 0x00ff00ffU), parameters.first);
        const QInt32 green = code::Math::javaIntMul(
            static_cast<QInt32>(texelBits & 0x0000ff00U), parameters.first);
        const QUint32 output =
            ((static_cast<QUint32>(redBlue) & 0xff00ff00U) |
             (static_cast<QUint32>(green) & 0x00ff0000U)) >> 8U;
        destination.pixels[destinationIndex] =
            code::Math::javaIntFromUnsigned(output);
        return;
    }

    if (Mode == EPerspectiveAlphaBlend) {
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
        const QUint32 output =
            ((static_cast<QUint32>(redBlue) & 0xff00ff00U) |
             (static_cast<QUint32>(green) & 0x00ff0000U)) >> 8U;
        destination.pixels[destinationIndex] =
            code::Math::javaIntFromUnsigned(output);
        return;
    }

    if (Mode == EPerspectiveFogBlendHigh) {
        colorAccumulator.redFactor = code::Math::javaIntAdd(
            colorAccumulator.redFactor, colorSlope.redFactor);
        const QInt32 textureWeight = code::Math::javaShiftRightUnsigned(
            colorAccumulator.redFactor, KTextureFractionBits);
        const QInt32 fogWeight = code::Math::javaIntSub(255, textureWeight);
        const QInt32 redBlue = code::Math::javaIntAdd(
            code::Math::javaIntMul(parameters.first, fogWeight),
            code::Math::javaIntMul(
                static_cast<QInt32>(texelBits & 0x00ff00ffU), textureWeight));
        const QInt32 green = code::Math::javaIntAdd(
            code::Math::javaIntMul(parameters.second, fogWeight),
            code::Math::javaIntMul(
                static_cast<QInt32>(texelBits & 0x0000ff00U), textureWeight));
        const QUint32 output =
            ((static_cast<QUint32>(redBlue) & 0xff00ff00U) |
             (static_cast<QUint32>(green) & 0x00ff0000U)) >> 8U;
        destination.pixels[destinationIndex] =
            code::Math::javaIntFromUnsigned(output);
        return;
    }

    if (Mode == EPerspectiveFogBlendLow) {
        const QInt32 redBlue = code::Math::javaIntAdd(
            code::Math::javaIntMul(
                static_cast<QInt32>(texelBits & 0x00ff00ffU), parameters.first),
            parameters.second);
        const QInt32 green = code::Math::javaIntAdd(
            code::Math::javaIntMul(
                static_cast<QInt32>(texelBits & 0x0000ff00U), parameters.first),
            parameters.third);
        const QUint32 output =
            ((static_cast<QUint32>(redBlue) & 0xff00ff00U) |
             (static_cast<QUint32>(green) & 0x00ff0000U)) >> 8U;
        destination.pixels[destinationIndex] =
            code::Math::javaIntFromUnsigned(output);
        return;
    }

    QUint32 addend;
    if (Mode == EPerspectiveFogAdd) {
        addend = static_cast<QUint32>(parameters.first) & 0x00fefefeU;
    } else {
        addend = static_cast<QUint32>(destination.pixels[destinationIndex]) &
                 0x00fefefeU;
    }
    const QUint32 sum = (texelBits & 0x00fefefeU) + addend;
    const QUint32 carry = ((sum >> 8U) & 0x00010101U) * 0xffU;
    destination.pixels[destinationIndex] =
        code::Math::javaIntFromUnsigned(sum | carry);
}

template<bool MayContainZeroTexels>
bool drawPerspectiveSpan(const SoftwareRenderSurfaceView& destination,
                         const SoftwareTextureView& texture,
                         QInt32 widthBit,
                         QUint32 textureMask,
                         QInt32 screenY,
                         FixedPerspectivePoint left,
                         FixedPerspectivePoint right,
                         const PerspectiveSlope& horizontalSlope,
                         QInt32 perspectiveStepPixels)
{
    QInt32 x1Fixed = left.screenX;
    QInt32 x2Fixed = right.screenX;
    const QInt32 subtexelLeft =
        javaRemainder(x1Fixed, KTextureFraction);
    const QInt32 subtexelRight =
        javaRemainder(x2Fixed, KTextureFraction);

    left.reciprocalDepth = adjustForFraction(
        left.reciprocalDepth, horizontalSlope.reciprocalDepth, subtexelLeft);
    left.uOverDepth = adjustForFraction(
        left.uOverDepth, horizontalSlope.uOverDepth, subtexelLeft);
    left.vOverDepth = adjustForFraction(
        left.vOverDepth, horizontalSlope.vOverDepth, subtexelLeft);
    right.reciprocalDepth = adjustForFraction(
        right.reciprocalDepth, horizontalSlope.reciprocalDepth, subtexelRight);
    right.uOverDepth = adjustForFraction(
        right.uOverDepth, horizontalSlope.uOverDepth, subtexelRight);
    right.vOverDepth = adjustForFraction(
        right.vOverDepth, horizontalSlope.vOverDepth, subtexelRight);

    QInt32 x1 = code::Math::javaShiftRight(
        x1Fixed, KTextureFractionBits);
    QInt32 x2 = code::Math::javaShiftRight(
        x2Fixed, KTextureFractionBits);
    if (x1 < 0) {
        left.reciprocalDepth = code::Math::javaIntSub(
            left.reciprocalDepth,
            code::Math::javaIntMul(horizontalSlope.reciprocalDepth, x1));
        left.uOverDepth = code::Math::javaIntSub(
            left.uOverDepth,
            code::Math::javaIntMul(horizontalSlope.uOverDepth, x1));
        left.vOverDepth = code::Math::javaIntSub(
            left.vOverDepth,
            code::Math::javaIntMul(horizontalSlope.vOverDepth, x1));
        x1 = 0;
    }
    if (x2 > destination.width) {
        const QInt32 excess = code::Math::javaIntSub(x2, destination.width);
        right.reciprocalDepth = code::Math::javaIntSub(
            right.reciprocalDepth,
            code::Math::javaIntMul(horizontalSlope.reciprocalDepth, excess));
        right.uOverDepth = code::Math::javaIntSub(
            right.uOverDepth,
            code::Math::javaIntMul(horizontalSlope.uOverDepth, excess));
        right.vOverDepth = code::Math::javaIntSub(
            right.vOverDepth,
            code::Math::javaIntMul(horizontalSlope.vOverDepth, excess));
        x2 = destination.width;
    }

    /* Java's perspective scanline returns without drawing for zero W. */
    if (left.reciprocalDepth == 0 || right.reciprocalDepth == 0) {
        return true;
    }
    if (x2 <= 0 || x1 >= destination.width || x1 >= x2) {
        return true;
    }

    QInt32 u;
    QInt32 v;
    if (!perspectiveDivide(left.uOverDepth, left.reciprocalDepth, u) ||
        !perspectiveDivide(left.vOverDepth, left.reciprocalDepth, v)) {
        return false;
    }
    QUint32 uBits = static_cast<QUint32>(u);
    QUint32 vBits = static_cast<QUint32>(v);

    QInt32 length = code::Math::javaIntSub(x2, x1);
    const size_t rowOffset = static_cast<size_t>(screenY) *
                             static_cast<size_t>(destination.stride);
    while (length > 0) {
        QInt32 lineEnd;
        QInt32 du;
        QInt32 dv;
        if (length >= perspectiveStepPixels) {
            lineEnd = code::Math::javaIntAdd(x1, perspectiveStepPixels);
            const QInt32 stepU = code::Math::javaIntMul(
                horizontalSlope.uOverDepth, perspectiveStepPixels);
            const QInt32 stepV = code::Math::javaIntMul(
                horizontalSlope.vOverDepth, perspectiveStepPixels);
            const QInt32 stepW = code::Math::javaIntMul(
                horizontalSlope.reciprocalDepth, perspectiveStepPixels);
            left.uOverDepth = code::Math::javaIntAdd(left.uOverDepth, stepU);
            left.vOverDepth = code::Math::javaIntAdd(left.vOverDepth, stepV);
            left.reciprocalDepth = code::Math::javaIntAdd(
                left.reciprocalDepth, stepW);

            QInt32 endU;
            QInt32 endV;
            if (!perspectiveDivide(
                    left.uOverDepth, left.reciprocalDepth, endU) ||
                !perspectiveDivide(
                    left.vOverDepth, left.reciprocalDepth, endV)) {
                return false;
            }
            const QInt32 divisor =
                code::Math::javaIntAdd(perspectiveStepPixels, 1);
            const QInt32 deltaU = code::Math::javaIntSub(endU, u);
            const QInt32 deltaV = code::Math::javaIntSub(endV, v);
            if (divisor == 25) {
                du = deltaU / 25;
                dv = deltaV / 25;
            } else {
                du = code::Math::javaIntDiv(deltaU, divisor);
                dv = code::Math::javaIntDiv(deltaV, divisor);
            }
            length = code::Math::javaIntSub(length, perspectiveStepPixels);
        } else {
            lineEnd = x2;
            QInt32 endU;
            QInt32 endV;
            if (!perspectiveDivide(
                    right.uOverDepth, right.reciprocalDepth, endU) ||
                !perspectiveDivide(
                    right.vOverDepth, right.reciprocalDepth, endV)) {
                return false;
            }
            du = code::Math::javaIntDiv(
                code::Math::javaIntSub(endU, u), length);
            dv = code::Math::javaIntDiv(
                code::Math::javaIntSub(endV, v), length);
            length = 0;
        }

        const QUint32 duBits = static_cast<QUint32>(du);
        const QUint32 dvBits = static_cast<QUint32>(dv);
        while (x1 < lineEnd) {
            uBits += duBits;
            vBits += dvBits;
            const QInt32 texel = sampleTexture(
                texture, widthBit, textureMask, uBits, vBits);
            if (MayContainZeroTexels) {
                if (texel != 0) {
                    destination.pixels[
                        rowOffset + static_cast<size_t>(x1)] = texel;
                }
            } else {
                destination.pixels[
                    rowOffset + static_cast<size_t>(x1)] = texel;
            }
            ++x1;
        }
        u = code::Math::javaIntFromUnsigned(uBits);
        v = code::Math::javaIntFromUnsigned(vBits);
    }
    return true;
}

template <PerspectivePixelMode Mode>
bool drawPerspectiveEffectSpan(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    QInt32 widthBit,
    QUint32 textureMask,
    QInt32 screenY,
    FixedPerspectivePoint left,
    FixedPerspectivePoint right,
    const PerspectiveSlope& horizontalSlope,
    QInt32 perspectiveStepPixels,
    bool floorSpecial,
    const PerspectivePixelParameters& parameters)
{
    AffineTriangleColor colorSlope = horizontalSlope.color;
    if (floorSpecial &&
        (Mode == EPerspectiveVertexShade ||
         Mode == EPerspectiveVertexShadeOpaque ||
         Mode == EPerspectiveVertexShadeFloor6)) {
        /* TexturingFloors nudges mode 6/9's shade derivative toward zero. */
        if (colorSlope.redFactor > 0) {
            colorSlope.redFactor = code::Math::javaIntSub(
                colorSlope.redFactor, 1);
        } else if (colorSlope.redFactor < 0) {
            colorSlope.redFactor = code::Math::javaIntAdd(
                colorSlope.redFactor, 1);
        }
    }

    const QInt32 subtexelLeft = javaRemainder(
        left.screenX, KTextureFraction);
    const QInt32 subtexelRight = javaRemainder(
        right.screenX, KTextureFraction);
    left.reciprocalDepth = adjustForFraction(
        left.reciprocalDepth, horizontalSlope.reciprocalDepth, subtexelLeft);
    left.uOverDepth = adjustForFraction(
        left.uOverDepth, horizontalSlope.uOverDepth, subtexelLeft);
    left.vOverDepth = adjustForFraction(
        left.vOverDepth, horizontalSlope.vOverDepth, subtexelLeft);
    right.reciprocalDepth = adjustForFraction(
        right.reciprocalDepth, horizontalSlope.reciprocalDepth, subtexelRight);
    right.uOverDepth = adjustForFraction(
        right.uOverDepth, horizontalSlope.uOverDepth, subtexelRight);
    right.vOverDepth = adjustForFraction(
        right.vOverDepth, horizontalSlope.vOverDepth, subtexelRight);

    QInt32 x1 = code::Math::javaShiftRight(
        left.screenX, KTextureFractionBits);
    QInt32 x2 = code::Math::javaShiftRight(
        right.screenX, KTextureFractionBits);
    if (x1 < 0) {
        left.reciprocalDepth = code::Math::javaIntSub(
            left.reciprocalDepth,
            code::Math::javaIntMul(horizontalSlope.reciprocalDepth, x1));
        left.uOverDepth = code::Math::javaIntSub(
            left.uOverDepth,
            code::Math::javaIntMul(horizontalSlope.uOverDepth, x1));
        left.vOverDepth = code::Math::javaIntSub(
            left.vOverDepth,
            code::Math::javaIntMul(horizontalSlope.vOverDepth, x1));
        left.color.redFactor = code::Math::javaIntSub(
            left.color.redFactor,
            code::Math::javaIntMul(colorSlope.redFactor, x1));
        left.color.greenFactor = code::Math::javaIntSub(
            left.color.greenFactor,
            code::Math::javaIntMul(colorSlope.greenFactor, x1));
        left.color.blueFactor = code::Math::javaIntSub(
            left.color.blueFactor,
            code::Math::javaIntMul(colorSlope.blueFactor, x1));
        x1 = 0;
    }
    if (x2 > destination.width) {
        const QInt32 excess = code::Math::javaIntSub(x2, destination.width);
        right.reciprocalDepth = code::Math::javaIntSub(
            right.reciprocalDepth,
            code::Math::javaIntMul(horizontalSlope.reciprocalDepth, excess));
        right.uOverDepth = code::Math::javaIntSub(
            right.uOverDepth,
            code::Math::javaIntMul(horizontalSlope.uOverDepth, excess));
        right.vOverDepth = code::Math::javaIntSub(
            right.vOverDepth,
            code::Math::javaIntMul(horizontalSlope.vOverDepth, excess));
        x2 = destination.width;
    }
    if (x2 <= 0 || x1 >= destination.width || x1 >= x2) return true;
    /* TexturingPers's effect kernels also treat zero W as an empty span. */
    if (left.reciprocalDepth == 0) return true;

    QInt32 u;
    QInt32 v;
    if (!perspectiveDivide(left.uOverDepth, left.reciprocalDepth, u) ||
        !perspectiveDivide(left.vOverDepth, left.reciprocalDepth, v)) {
        return false;
    }

    QUint32 uBits = static_cast<QUint32>(u);
    QUint32 vBits = static_cast<QUint32>(v);
    QInt32 remaining = code::Math::javaIntSub(x2, x1);
    const size_t rowOffset = static_cast<size_t>(screenY) *
                             static_cast<size_t>(destination.stride);
    AffineTriangleColor color = left.color;
    if (floorSpecial &&
        (Mode == EPerspectiveVertexShade ||
         Mode == EPerspectiveVertexShadeOpaque)) {
        /* Mode 9 floor fog clamps a negative start/end shade to zero. */
        if (color.redFactor < 0) color.redFactor = 0;
        if (code::Math::javaIntAdd(
                color.redFactor,
                code::Math::javaIntMul(colorSlope.redFactor, remaining)) < 0) {
            colorSlope.redFactor = code::Math::javaIntDiv(
                code::Math::javaIntSub(0, color.redFactor), remaining);
        }
    } else if (floorSpecial && Mode == EPerspectiveMode13) {
        /* The per-channel floor kernel clamps negative shade factors. */
        if (color.redFactor < 0) color.redFactor = 0;
        if (color.greenFactor < 0) color.greenFactor = 0;
        if (color.blueFactor < 0) color.blueFactor = 0;
        if (code::Math::javaIntAdd(
                color.redFactor,
                code::Math::javaIntMul(colorSlope.redFactor, remaining)) < 0) {
            colorSlope.redFactor = code::Math::javaIntDiv(
                code::Math::javaIntSub(0, color.redFactor), remaining);
        }
        if (code::Math::javaIntAdd(
                color.greenFactor,
                code::Math::javaIntMul(colorSlope.greenFactor, remaining)) < 0) {
            colorSlope.greenFactor = code::Math::javaIntDiv(
                code::Math::javaIntSub(0, color.greenFactor), remaining);
        }
        if (code::Math::javaIntAdd(
                color.blueFactor,
                code::Math::javaIntMul(colorSlope.blueFactor, remaining)) < 0) {
            colorSlope.blueFactor = code::Math::javaIntDiv(
                code::Math::javaIntSub(0, color.blueFactor), remaining);
        }
    }
    while (remaining > 0) {
        QInt32 pixelsInSegment;
        FixedPerspectivePoint segmentEnd;
        if (!floorSpecial && remaining >= perspectiveStepPixels) {
            pixelsInSegment = perspectiveStepPixels;
            segmentEnd = left;
            segmentEnd.reciprocalDepth = code::Math::javaIntAdd(
                segmentEnd.reciprocalDepth,
                code::Math::javaIntMul(horizontalSlope.reciprocalDepth,
                                       pixelsInSegment));
            segmentEnd.uOverDepth = code::Math::javaIntAdd(
                segmentEnd.uOverDepth,
                code::Math::javaIntMul(horizontalSlope.uOverDepth,
                                       pixelsInSegment));
            segmentEnd.vOverDepth = code::Math::javaIntAdd(
                segmentEnd.vOverDepth,
                code::Math::javaIntMul(horizontalSlope.vOverDepth,
                                       pixelsInSegment));
        } else {
            pixelsInSegment = remaining;
            segmentEnd = right;
        }
        if (segmentEnd.reciprocalDepth == 0) return false;

        QInt32 endU;
        QInt32 endV;
        if (!perspectiveDivide(segmentEnd.uOverDepth,
                               segmentEnd.reciprocalDepth, endU) ||
            !perspectiveDivide(segmentEnd.vOverDepth,
                               segmentEnd.reciprocalDepth, endV)) {
            return false;
        }
        const QInt32 deltaU = code::Math::javaIntSub(endU, u);
        const QInt32 deltaV = code::Math::javaIntSub(endV, v);
        QInt32 du;
        QInt32 dv;
        if (pixelsInSegment == 24) {
            du = deltaU / 24;
            dv = deltaV / 24;
        } else {
            du = code::Math::javaIntDiv(deltaU, pixelsInSegment);
            dv = code::Math::javaIntDiv(deltaV, pixelsInSegment);
        }
        const QUint32 duBits = static_cast<QUint32>(du);
        const QUint32 dvBits = static_cast<QUint32>(dv);
        QInt32 i;
        for (i = 0; i < pixelsInSegment; ++i) {
            uBits += duBits;
            vBits += dvBits;
            const QInt32 texel = sampleTexture(
                texture, widthBit, textureMask, uBits, vBits);
            const size_t destinationIndex = rowOffset +
                                           static_cast<size_t>(x1);
            const QInt32 logicalIndex = code::Math::javaIntAdd(
                code::Math::javaIntMul(screenY, destination.width), x1);
            writePerspectivePixel<Mode>(
                destination, destinationIndex, texel, parameters,
                color, colorSlope, logicalIndex);
            ++x1;
        }
        u = code::Math::javaIntFromUnsigned(uBits);
        v = code::Math::javaIntFromUnsigned(vBits);
        remaining = code::Math::javaIntSub(remaining, pixelsInSegment);
        if (remaining > 0 && pixelsInSegment == perspectiveStepPixels) {
            left = segmentEnd;
        }
    }
    return true;
}

template <PerspectivePixelMode Mode>
bool drawPerspectiveEffectTriangle(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    const PerspectivePixelParameters& parameters,
    QInt32 perspectiveStepPixels,
    QInt32 move,
    const AffineTriangleColor* vertexColors = 0)
{
    if (!validSurface(destination)) return false;
    if (destination.width == 0 || destination.height == 0) return true;
    if (!validTexture(texture) || perspectiveStepPixels <= 0 || move <= 0) {
        return false;
    }
    const bool floorSpecial = perspectiveStepPixels > 999 &&
        (Mode == EPerspectiveFogBlendLow ||
         Mode == EPerspectiveFogBlendHigh ||
         Mode == EPerspectiveVertexShade ||
         Mode == EPerspectiveVertexShadeOpaque ||
         Mode == EPerspectiveVertexShadeFloor6 ||
         Mode == EPerspectiveMode13);
    if ((Mode == EPerspectiveVertexShade ||
         Mode == EPerspectiveVertexShadeOpaque ||
         Mode == EPerspectiveVertexShadeFloor6 ||
         Mode == EPerspectiveFogBlendHigh ||
         Mode == EPerspectiveMode13) && vertexColors == 0) {
        return false;
    }

    const QInt32 maximumDimension = texture.width > texture.height
        ? texture.width : texture.height;
    const QInt32 reciprocalNumerator = code::Math::javaIntAdd(
        code::Math::javaShiftRight(
            textureWUnit(maximumDimension), KTextureFractionBits), move);
    PerspectiveVertex a;
    PerspectiveVertex b;
    PerspectiveVertex c;
    const AffineTriangleColor noColor;
    const AffineTriangleColor& colorA = vertexColors != 0
        ? vertexColors[0] : noColor;
    const AffineTriangleColor& colorB = vertexColors != 0
        ? vertexColors[1] : noColor;
    const AffineTriangleColor& colorC = vertexColors != 0
        ? vertexColors[2] : noColor;
    if (!makePerspectiveVertex(first, reciprocalNumerator, move, a, colorA) ||
        !makePerspectiveVertex(second, reciprocalNumerator, move, b, colorB) ||
        !makePerspectiveVertex(third, reciprocalNumerator, move, c, colorC)) {
        return false;
    }

    if (b.screenY < a.screenY) swapVertices(a, b);
    if (c.screenY < a.screenY) swapVertices(a, c);
    if (c.screenY < b.screenY) swapVertices(b, c);
    if (a.screenY == c.screenY) return true;

    const FixedPerspectivePoint fixedA = toFixed(a);
    const FixedPerspectivePoint fixedB = toFixed(b);
    const QInt32 rowsAC = code::Math::javaIntSub(c.screenY, a.screenY);
    const QInt32 rowsAB = code::Math::javaIntSub(b.screenY, a.screenY);
    PerspectiveSlope slopeAC;
    PerspectiveSlope slopeAB;
    if (!computeSlope(a, c, rowsAC, slopeAC)) return false;
    if (b.screenY != a.screenY &&
        !computeSlope(a, b, rowsAB, slopeAB)) return false;

    const FixedPerspectivePoint longAtB = advance(fixedA, slopeAC, rowsAB);
    const QInt32 horizontalPixels = code::Math::javaShiftRight(
        code::Math::javaIntSub(longAtB.screenX, fixedB.screenX),
        KTextureFractionBits);
    if (horizontalPixels == 0) return true;
    PerspectiveSlope horizontalSlope;
    horizontalSlope.screenX = 0;
    horizontalSlope.reciprocalDepth = code::Math::javaIntDiv(
        code::Math::javaIntSub(
            longAtB.reciprocalDepth, fixedB.reciprocalDepth),
        horizontalPixels);
    horizontalSlope.uOverDepth = code::Math::javaIntDiv(
        code::Math::javaIntSub(longAtB.uOverDepth, fixedB.uOverDepth),
        horizontalPixels);
    horizontalSlope.vOverDepth = code::Math::javaIntDiv(
        code::Math::javaIntSub(longAtB.vOverDepth, fixedB.vOverDepth),
        horizontalPixels);
    horizontalSlope.color.redFactor = code::Math::javaIntDiv(
        code::Math::javaIntSub(
            longAtB.color.redFactor, fixedB.color.redFactor),
        horizontalPixels);
    horizontalSlope.color.greenFactor = code::Math::javaIntDiv(
        code::Math::javaIntSub(
            longAtB.color.greenFactor, fixedB.color.greenFactor),
        horizontalPixels);
    horizontalSlope.color.blueFactor = code::Math::javaIntDiv(
        code::Math::javaIntSub(
            longAtB.color.blueFactor, fixedB.color.blueFactor),
        horizontalPixels);

    PerspectiveSlope slopeBC;
    if (c.screenY != b.screenY &&
        !computeSlope(b, c,
                      code::Math::javaIntSub(c.screenY, b.screenY), slopeBC)) {
        return false;
    }
    const QInt32 yStart = a.screenY < 0 ? 0 : a.screenY;
    const QInt32 yEnd = c.screenY < destination.height
        ? c.screenY : destination.height;
    if (yEnd <= 0 || yStart >= yEnd) return true;

    const QInt32 widthBit = textureWidthBit(texture.width);
    const QUint32 textureMask = static_cast<QUint32>(texture.pixelCount - 1);
    const QInt32 rowsFromStart = code::Math::javaIntSub(yStart, a.screenY);
    FixedPerspectivePoint longEdge = advance(fixedA, slopeAC, rowsFromStart);
    FixedPerspectivePoint shortEdge;
    if (yStart < b.screenY) {
        shortEdge = advance(fixedA, slopeAB, rowsFromStart);
    } else {
        shortEdge = advance(
            fixedB, slopeBC, code::Math::javaIntSub(yStart, b.screenY));
    }

    QInt32 y;
    for (y = yStart; y < yEnd; ++y) {
        /* Java starts the lower edge from B exactly at the split scanline. */
        if (y == b.screenY) shortEdge = fixedB;
        FixedPerspectivePoint left = longEdge;
        FixedPerspectivePoint right = shortEdge;
        if (left.screenX > right.screenX) {
            left = shortEdge;
            right = longEdge;
        }
        if (!drawPerspectiveEffectSpan<Mode>(
                destination, texture, widthBit, textureMask, y,
                left, right, horizontalSlope, perspectiveStepPixels,
                floorSpecial, parameters)) {
            return false;
        }
        advanceOneRow(longEdge, slopeAC);
        if (y < b.screenY) {
            advanceOneRow(shortEdge, slopeAB);
        } else {
            advanceOneRow(shortEdge, slopeBC);
        }
    }
    return true;
}

QInt32 clampByte(QInt32 value)
{
    if (value > 255) return 255;
    if (value < 0) return 0;
    return value;
}

QInt32 perspectiveDepthBrightness(QInt32 depthRz, QInt32 fogDistance)
{
    if (fogDistance <= 1) return 255;
    return clampByte(code::Math::javaIntAdd(
        255, code::Math::javaIntDiv(
                 code::Math::javaIntMul(depthRz, 255), fogDistance)));
}

QInt32 mode6PerspectiveShade(const AffineTriangleVertex& first,
                             const AffineTriangleVertex& second,
                             const AffineTriangleVertex& third,
                             QInt32 fallbackShadeStrength,
                             QInt32 fogDistance)
{
    QInt32 nearestDepth = first.depthRz;
    if (second.depthRz < nearestDepth) nearestDepth = second.depthRz;
    if (third.depthRz < nearestDepth) nearestDepth = third.depthRz;
    const QInt32 fogStep = code::Math::javaIntDiv(fogDistance, 255);
    QInt32 shade = fallbackShadeStrength;
    if (fogStep != 0) {
        shade = code::Math::javaIntDiv(
            code::Math::javaIntSub(0, nearestDepth), fogStep);
    }
    return clampByte(shade);
}

bool fillBlackTriangle(const SoftwareRenderSurfaceView& destination,
                       const AffineTriangleVertex& first,
                       const AffineTriangleVertex& second,
                       const AffineTriangleVertex& third)
{
    QInt32 black = 0;
    const SoftwareTextureView oneBlackPixel(&black, 1, 1, 1);
    return SoftwareAffineRasterizer::drawMode8Fill(
        destination, oneBlackPixel, first, second, third, 0, 0);
}

bool allDark(QInt32 firstBrightness,
             QInt32 secondBrightness,
             QInt32 thirdBrightness)
{
    return firstBrightness < 1 && secondBrightness < 1 &&
           thirdBrightness < 1;
}

} // anonymous namespace

namespace {

template<bool MayContainZeroTexels>
bool drawPerspectiveCopyTriangle(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 perspectiveStepPixels,
    QInt32 move)
{
    if (!validSurface(destination)) {
        return false;
    }
    if (destination.width == 0 || destination.height == 0) {
        return true;
    }
    if (!validTexture(texture) || perspectiveStepPixels <= 0 ||
        move <= 0) {
        return false;
    }

    const QInt32 maximumDimension = texture.width > texture.height
        ? texture.width : texture.height;
    const QInt32 reciprocalNumerator = code::Math::javaIntAdd(
        code::Math::javaShiftRight(
            textureWUnit(maximumDimension), KTextureFractionBits),
        move);

    PerspectiveVertex a;
    PerspectiveVertex b;
    PerspectiveVertex c;
    if (!makePerspectiveVertex(first, reciprocalNumerator, move, a) ||
        !makePerspectiveVertex(second, reciprocalNumerator, move, b) ||
        !makePerspectiveVertex(third, reciprocalNumerator, move, c)) {
        return false;
    }

    if (b.screenY < a.screenY) swapVertices(a, b);
    if (c.screenY < a.screenY) swapVertices(a, c);
    if (c.screenY < b.screenY) swapVertices(b, c);
    if (a.screenY == c.screenY) {
        return true;
    }

    const FixedPerspectivePoint fixedA = toFixed(a);
    const FixedPerspectivePoint fixedB = toFixed(b);
    const QInt32 rowsAC = code::Math::javaIntSub(c.screenY, a.screenY);
    const QInt32 rowsAB = code::Math::javaIntSub(b.screenY, a.screenY);
    PerspectiveSlope slopeAC;
    PerspectiveSlope slopeAB;
    if (!computeTextureSlope(a, c, rowsAC, slopeAC)) {
        return false;
    }
    if (b.screenY != a.screenY &&
        !computeTextureSlope(a, b, rowsAB, slopeAB)) {
        return false;
    }

    const FixedPerspectivePoint longAtB =
        advanceTexture(fixedA, slopeAC, rowsAB);
    const QInt32 horizontalPixels = code::Math::javaShiftRight(
        code::Math::javaIntSub(longAtB.screenX, fixedB.screenX),
        KTextureFractionBits);
    if (horizontalPixels == 0) {
        return true;
    }
    PerspectiveSlope horizontalSlope;
    horizontalSlope.screenX = 0;
    horizontalSlope.reciprocalDepth = code::Math::javaIntDiv(
        code::Math::javaIntSub(
            longAtB.reciprocalDepth, fixedB.reciprocalDepth),
        horizontalPixels);
    horizontalSlope.uOverDepth = code::Math::javaIntDiv(
        code::Math::javaIntSub(longAtB.uOverDepth, fixedB.uOverDepth),
        horizontalPixels);
    horizontalSlope.vOverDepth = code::Math::javaIntDiv(
        code::Math::javaIntSub(longAtB.vOverDepth, fixedB.vOverDepth),
        horizontalPixels);

    PerspectiveSlope slopeBC;
    if (c.screenY != b.screenY &&
        !computeTextureSlope(
            b, c, code::Math::javaIntSub(c.screenY, b.screenY), slopeBC)) {
        return false;
    }

    const QInt32 yStart = a.screenY < 0 ? 0 : a.screenY;
    const QInt32 yEnd = c.screenY < destination.height
        ? c.screenY : destination.height;
    if (yEnd <= 0 || yStart >= yEnd) {
        return true;
    }

    const QInt32 widthBit = textureWidthBit(texture.width);
    const QUint32 textureMask = static_cast<QUint32>(texture.pixelCount - 1);
    const QInt32 rowsFromStart = code::Math::javaIntSub(yStart, a.screenY);
    FixedPerspectivePoint longEdge =
        advanceTexture(fixedA, slopeAC, rowsFromStart);
    FixedPerspectivePoint shortEdge;
    if (yStart < b.screenY) {
        shortEdge = advanceTexture(fixedA, slopeAB, rowsFromStart);
    } else {
        shortEdge = advanceTexture(
            fixedB, slopeBC, code::Math::javaIntSub(yStart, b.screenY));
    }

    QInt32 y;
    for (y = yStart; y < yEnd; ++y) {
        /* Java starts the lower edge from B exactly at the split scanline. */
        if (y == b.screenY) shortEdge = fixedB;
        FixedPerspectivePoint left = longEdge;
        FixedPerspectivePoint right = shortEdge;
        if (left.screenX > right.screenX) {
            left = shortEdge;
            right = longEdge;
        }

        if (!drawPerspectiveSpan<MayContainZeroTexels>(
                destination, texture, widthBit, textureMask, y,
                left, right, horizontalSlope, perspectiveStepPixels)) {
            return false;
        }
        advanceOneTextureRow(longEdge, slopeAC);
        if (y < b.screenY) {
            advanceOneTextureRow(shortEdge, slopeAB);
        } else {
            advanceOneTextureRow(shortEdge, slopeBC);
        }
    }
    return true;
}

} // anonymous namespace

bool SoftwarePerspectiveRasterizer::drawMode0Copy(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 perspectiveStepPixels,
    QInt32 move)
{
    if (texture.mayContainZeroTexels) {
        return drawPerspectiveCopyTriangle<true>(
            destination, texture, first, second, third,
            perspectiveStepPixels, move);
    }
    return drawPerspectiveCopyTriangle<false>(
        destination, texture, first, second, third,
        perspectiveStepPixels, move);
}

bool SoftwarePerspectiveRasterizer::drawMode1Add(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 packedFogColor,
    QInt32 perspectiveStepPixels,
    QInt32 move)
{
    return drawPerspectiveEffectTriangle<EPerspectiveFogAdd>(
        destination, texture, first, second, third,
        PerspectivePixelParameters(packedFogColor),
        perspectiveStepPixels, move);
}

bool SoftwarePerspectiveRasterizer::drawMode2SurfaceAdd(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 perspectiveStepPixels,
    QInt32 move)
{
    return drawPerspectiveEffectTriangle<EPerspectiveSurfaceAdd>(
        destination, texture, first, second, third,
        PerspectivePixelParameters(), perspectiveStepPixels, move);
}

bool SoftwarePerspectiveRasterizer::drawMode3LQ(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 fogColor,
    QInt32 fogStrength,
    QInt32 perspectiveStepPixels,
    QInt32 move)
{
    fogStrength = clampByte(fogStrength);
    const QInt32 textureWeight = code::Math::javaIntSub(255, fogStrength);
    const QUint32 fogBits = static_cast<QUint32>(fogColor);
    const QInt32 redBlueFog = code::Math::javaIntMul(
        static_cast<QInt32>(fogBits & 0x00ff00ffU), fogStrength);
    const QInt32 greenFog = code::Math::javaIntMul(
        static_cast<QInt32>(fogBits & 0x0000ff00U), fogStrength);
    return drawPerspectiveEffectTriangle<EPerspectiveFogBlendLow>(
        destination, texture, first, second, third,
        PerspectivePixelParameters(textureWeight, redBlueFog, greenFog),
        perspectiveStepPixels, move);
}

bool SoftwarePerspectiveRasterizer::drawMode3HQ(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 fogColor,
    QInt32 fogDistance,
    QInt32 perspectiveStepPixels,
    QInt32 move)
{
    const AffineTriangleColor colors[3] = {
        AffineTriangleColor(
            perspectiveDepthBrightness(first.depthRz, fogDistance), 0, 0),
        AffineTriangleColor(
            perspectiveDepthBrightness(second.depthRz, fogDistance), 0, 0),
        AffineTriangleColor(
            perspectiveDepthBrightness(third.depthRz, fogDistance), 0, 0)
    };
    const QUint32 fogBits = static_cast<QUint32>(fogColor);
    return drawPerspectiveEffectTriangle<EPerspectiveFogBlendHigh>(
        destination, texture, first, second, third,
        PerspectivePixelParameters(
            static_cast<QInt32>(fogBits & 0x00ff00ffU),
            static_cast<QInt32>(fogBits & 0x0000ff00U)),
        perspectiveStepPixels, move, colors);
}

bool SoftwarePerspectiveRasterizer::drawMode4AlphaBlend(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 perspectiveStepPixels,
    QInt32 move)
{
    return drawPerspectiveEffectTriangle<EPerspectiveAlphaBlend>(
        destination, texture, first, second, third,
        PerspectivePixelParameters(), perspectiveStepPixels, move);
}

bool SoftwarePerspectiveRasterizer::drawMode5(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 shadeStrength,
    QInt32 fogDistance,
    QInt32 perspectiveStepPixels,
    QInt32 move)
{
    if (allDark(
            perspectiveDepthBrightness(first.depthRz, fogDistance),
            perspectiveDepthBrightness(second.depthRz, fogDistance),
            perspectiveDepthBrightness(third.depthRz, fogDistance))) {
        return fillBlackTriangle(destination, first, second, third);
    }
    return drawMode5Shade(
        destination, texture, first, second, third, shadeStrength,
        perspectiveStepPixels, move);
}

bool SoftwarePerspectiveRasterizer::drawMode5Shade(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 shadeStrength,
    QInt32 perspectiveStepPixels,
    QInt32 move)
{
    shadeStrength = clampByte(shadeStrength);
    const QInt32 brightness = code::Math::javaIntSub(255, shadeStrength);
    return drawPerspectiveEffectTriangle<EPerspectiveShade>(
        destination, texture, first, second, third,
        PerspectivePixelParameters(brightness), perspectiveStepPixels, move);
}

bool SoftwarePerspectiveRasterizer::drawMode6LQ(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 fallbackShadeStrength,
    QInt32 fogDistance,
    QInt32 perspectiveStepPixels,
    QInt32 move)
{
    if (allDark(
            perspectiveDepthBrightness(first.depthRz, fogDistance),
            perspectiveDepthBrightness(second.depthRz, fogDistance),
            perspectiveDepthBrightness(third.depthRz, fogDistance))) {
        return fillBlackTriangle(destination, first, second, third);
    }
    return drawMode5Shade(
        destination, texture, first, second, third,
        mode6PerspectiveShade(first, second, third,
                              fallbackShadeStrength, fogDistance),
        perspectiveStepPixels, move);
}

bool SoftwarePerspectiveRasterizer::drawMode6HQ(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 fogDistance,
    QInt32 perspectiveStepPixels,
    QInt32 move)
{
    return drawMode6HQ(
        destination, texture, first, second, third, fogDistance,
        255, 255, 255, perspectiveStepPixels, move);
}

bool SoftwarePerspectiveRasterizer::drawMode6HQ(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 fogDistance,
    QInt32 firstVectorLight,
    QInt32 secondVectorLight,
    QInt32 thirdVectorLight,
    QInt32 perspectiveStepPixels,
    QInt32 move)
{
    const QInt32 firstBrightness = code::Math::javaIntDiv(
        code::Math::javaIntMul(
            perspectiveDepthBrightness(first.depthRz, fogDistance),
            firstVectorLight), 255);
    const QInt32 secondBrightness = code::Math::javaIntDiv(
        code::Math::javaIntMul(
            perspectiveDepthBrightness(second.depthRz, fogDistance),
            secondVectorLight), 255);
    const QInt32 thirdBrightness = code::Math::javaIntDiv(
        code::Math::javaIntMul(
            perspectiveDepthBrightness(third.depthRz, fogDistance),
            thirdVectorLight), 255);
    const AffineTriangleColor colors[3] = {
        AffineTriangleColor(firstBrightness, 0, 0),
        AffineTriangleColor(secondBrightness, 0, 0),
        AffineTriangleColor(thirdBrightness, 0, 0)
    };
    if (allDark(firstBrightness, secondBrightness, thirdBrightness) &&
        !texture.hasTransparentTexels) {
        /* Filling black is safe only when Java cannot skip transparent texels. */
        return fillBlackTriangle(destination, first, second, third);
    }
    if (perspectiveStepPixels > 999) {
        return drawPerspectiveEffectTriangle<EPerspectiveVertexShadeFloor6>(
            destination, texture, first, second, third,
            PerspectivePixelParameters(), perspectiveStepPixels, move, colors);
    }
    return drawMode9Shade(
        destination, texture, first, second, third,
        firstBrightness, secondBrightness, thirdBrightness,
        perspectiveStepPixels, move);
}

bool SoftwarePerspectiveRasterizer::drawMode7Glass(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 perspectiveStepPixels,
    QInt32 move)
{
    const QInt32 dimensions = code::Math::javaIntAdd(
        destination.width, destination.height);
    const QInt32 scale = code::Math::javaIntDiv(
        code::Math::javaIntMul(64, dimensions), 560);
    const QInt32 logicalPixelCount = code::Math::javaIntMul(
        destination.width, destination.height);
    return drawPerspectiveEffectTriangle<EPerspectiveGlass>(
        destination, texture, first, second, third,
        PerspectivePixelParameters(scale, logicalPixelCount),
        perspectiveStepPixels, move);
}

bool SoftwarePerspectiveRasterizer::drawMode9Shade(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    QInt32 firstBrightness,
    QInt32 secondBrightness,
    QInt32 thirdBrightness,
    QInt32 perspectiveStepPixels,
    QInt32 move)
{
    if (allDark(firstBrightness, secondBrightness, thirdBrightness) &&
        !texture.hasTransparentTexels) {
        return fillBlackTriangle(destination, first, second, third);
    }
    const AffineTriangleColor colors[3] = {
        AffineTriangleColor(firstBrightness, 0, 0),
        AffineTriangleColor(secondBrightness, 0, 0),
        AffineTriangleColor(thirdBrightness, 0, 0)
    };
    if (!texture.mayContainZeroTexels) {
        return drawPerspectiveEffectTriangle<
            EPerspectiveVertexShadeOpaque>(
                destination, texture, first, second, third,
                PerspectivePixelParameters(), perspectiveStepPixels, move,
                colors);
    }
    return drawPerspectiveEffectTriangle<EPerspectiveVertexShade>(
        destination, texture, first, second, third,
        PerspectivePixelParameters(), perspectiveStepPixels, move, colors);
}

bool SoftwarePerspectiveRasterizer::drawMode13(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTextureView& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    const AffineTriangleColor& firstColor,
    const AffineTriangleColor& secondColor,
    const AffineTriangleColor& thirdColor,
    QInt32 perspectiveStepPixels,
    QInt32 move)
{
    const AffineTriangleColor colors[3] = {
        firstColor, secondColor, thirdColor
    };
    return drawPerspectiveEffectTriangle<EPerspectiveMode13>(
        destination, texture, first, second, third,
        PerspectivePixelParameters(), perspectiveStepPixels, move, colors);
}

} // namespace Rendering
} // namespace code
