#include "software_fast_rasterizer.h"

#include "armv5te_dsp.h"
#include "omap1710_pdiv.h"
#include "quantum_integer.h"

#include <stddef.h>

#if defined(QUANTUM_LEVEL41_OMAP1710_DSP) && defined(__SYMBIAN32__)
extern "C" code::Math::QInt32 quantum_omap1710_mul_shift12(
    code::Math::QInt32 slope, code::Math::QInt32 fraction);
extern "C" code::Math::QInt32 quantum_omap1710_mul_shift12_outer(
    code::Math::QInt32 slope, code::Math::QInt32 fraction);
#endif

#if defined(QUANTUM_LEVEL41_OMAP1710_SCALAR_PDIV_ASM) && \
    defined(__SYMBIAN32__)
extern "C" code::Math::QUint32 quantum_omap1710_udiv_u32(
    code::Math::QUint32 dividend, code::Math::QUint32 divisor);
#endif

namespace code {
namespace Rendering {

using code::Math::QInt32;
using code::Math::QInt64;
using code::Math::QUint16;
using code::Math::QUint32;

namespace {

const unsigned int KScreenFractionBits = 14U;
const unsigned int KTextureFractionBits = 12U;
const QInt32 KTextureFraction = 4096;

/*
 * Java evaluates ((long) value << 12) / divisor and narrows to int. A
 * 32-bit divide would overflow (C++ signed overflow is undefined), so split
 * the quotient into whole and 12-bit fractional parts and extend the
 * remainder one bit at a time. On the E65 build the whole part goes through
 * the same normalized-divide assembly helper the reference perspective
 * kernel uses, so no hardware divide is needed on ARMv5TE.
 */
static QInt32 divideShift12(QInt32 value, QInt32 divisor)
{
    const QUint32 valueBits = static_cast<QUint32>(value);
    const QUint32 divisorBits = static_cast<QUint32>(divisor);
    const QUint32 valueMagnitude = value < 0
        ? static_cast<QUint32>(static_cast<QUint32>(0) - valueBits)
        : valueBits;
    const QUint32 divisorMagnitude = divisor < 0
        ? static_cast<QUint32>(static_cast<QUint32>(0) - divisorBits)
        : divisorBits;
    const bool negative = (value < 0) != (divisor < 0);
    QUint32 whole = 0;
#if defined(QUANTUM_LEVEL41_OMAP1710_SCALAR_PDIV_ASM) && \
    defined(__SYMBIAN32__)
    whole = quantum_omap1710_udiv_u32(valueMagnitude, divisorMagnitude);
#elif defined(QUANTUM_LEVEL41_OMAP1710_SCALAR_PDIV_ASM)
    if (!code::Math::ArmV5teDsp::omap1710UnsignedDivideReference(
            valueMagnitude, divisorMagnitude, whole)) {
        whole = valueMagnitude / divisorMagnitude;
    }
#else
    whole = valueMagnitude / divisorMagnitude;
#endif
    QUint32 remainder = valueMagnitude - whole * divisorMagnitude;
    QUint32 fractional = 0;
    if (remainder != 0U) {
        unsigned int bit;
        for (bit = 0; bit < KTextureFractionBits; ++bit) {
            fractional <<= 1U;
            remainder += remainder;
            if (remainder >= divisorMagnitude) {
                remainder -= divisorMagnitude;
                fractional |= 1U;
            }
        }
    }
    QUint32 quotientBits = (whole << KTextureFractionBits) | fractional;
    if (negative) {
        quotientBits = static_cast<QUint32>(
            static_cast<QUint32>(0) - quotientBits);
    }
    return code::Math::javaIntFromUnsigned(quotientBits);
}

/*
 * (slope * fraction) >> 12 with the reference's arithmetic shift. The E65
 * build routes it through the same SMULWB/SMLAWB helper the reference
 * kernel uses, which covers every Q12 fraction, including the one-period
 * wrap outside [-2048, 2047].
 */
static QInt32 multiplyShift12(QInt32 slope, QInt32 fraction)
{
#if defined(QUANTUM_LEVEL41_OMAP1710_DSP) && defined(__SYMBIAN32__)
    if (code::Math::ArmV5teDsp::canUseSmulwbDirectForQ12Fraction(fraction)) {
        return quantum_omap1710_mul_shift12(slope, fraction);
    }
    if (code::Math::ArmV5teDsp::canUseArmV5teDspForQ12Fraction(fraction)) {
        return quantum_omap1710_mul_shift12_outer(slope, fraction);
    }
#endif
    const QInt64 product = static_cast<QInt64>(slope) * fraction;
    return static_cast<QInt32>(product >> 12);
}

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

bool validTexture16(const SoftwareTexture16View& texture)
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

inline QUint16 sample16(const SoftwareTexture16View& texture,
                        QInt32 widthBit,
                        QUint32 textureMask,
                        QUint32 uBits,
                        QUint32 vBits)
{
    const QUint32 texelRow = vBits >> KTextureFractionBits;
    const QUint32 texelColumn = uBits >> KTextureFractionBits;
    const QUint32 index =
        ((texelRow << static_cast<unsigned int>(widthBit)) + texelColumn) &
        textureMask;
    return texture.pixels[static_cast<size_t>(index)];
}

inline QInt32 expandTexel(const SoftwareTexture16View& texture, QUint16 texel)
{
    return texture.is1555 ? rgb8888From1555(texel) : rgb8888From565(texel);
}

inline bool isTransparent(const SoftwareTexture16View& texture, QUint16 texel)
{
    return texture.is1555 && texel == 0;
}

void swapVertices(AffineTriangleVertex& left, AffineTriangleVertex& right)
{
    const AffineTriangleVertex temporary(left);
    left = right;
    right = temporary;
}

bool computeSlope(QInt32 delta, QInt32 rows, unsigned int fractionBits,
                  QInt32& slope)
{
    if (rows == 0) return false;
    if (fractionBits == KTextureFractionBits) {
        slope = divideShift12(delta, rows);
        return true;
    }
    slope = static_cast<QInt32>(
        (static_cast<QInt64>(delta) << fractionBits) / rows);
    return true;
}

QInt32 advance(QInt32 initial, QInt32 slope, QInt32 rows)
{
    return static_cast<QInt32>(
        static_cast<QInt64>(initial) + static_cast<QInt64>(slope) * rows);
}

/* ---------------- affine span ---------------- */

template <bool Is1555, bool Shaded>
inline void drawAffineSpanBody(const SoftwareTexture16View& texture,
                               QInt32 widthBit,
                               QUint32 textureMask,
                               QInt32* pixels,
                               size_t destinationIndex,
                               QInt32 x1,
                               QInt32 x2,
                               QUint32& uBits,
                               QUint32& vBits,
                               QUint32 duBits,
                               QUint32 dvBits,
                               QInt32& shade,
                               QInt32 shadeSlope)
{
    const QUint16* texels = texture.pixels;
    while (x2 - x1 >= 4) {
        uBits += duBits;
        vBits += dvBits;
        if (Shaded) shade += shadeSlope;
        {
            const QUint16 texel = texels[(((vBits >> 12) << widthBit) +
                                          (uBits >> 12)) &
                                         textureMask];
            if (!Is1555 || texel != 0) {
                QInt32 color = Is1555 ? rgb8888From1555(texel)
                                      : rgb8888From565(texel);
                if (Shaded) {
                    const QUint32 bits = static_cast<QUint32>(color);
                    const QInt32 factor = shade >> 12;
                    const QInt32 redBlue = static_cast<QInt32>(
                        (bits & 0x00ff00ffU) *
                        static_cast<QUint32>(factor));
                    const QInt32 green = static_cast<QInt32>(
                        (bits & 0x0000ff00U) *
                        static_cast<QUint32>(factor));
                    const QUint32 blended =
                        ((static_cast<QUint32>(redBlue) & 0xff00ff00U) |
                         (static_cast<QUint32>(green) & 0x00ff0000U)) >> 8U;
                    color = code::Math::javaIntFromUnsigned(blended);
                }
                pixels[destinationIndex] = color;
            }
        }

        uBits += duBits;
        vBits += dvBits;
        if (Shaded) shade += shadeSlope;
        {
            const QUint16 texel = texels[(((vBits >> 12) << widthBit) +
                                          (uBits >> 12)) &
                                         textureMask];
            if (!Is1555 || texel != 0) {
                QInt32 color = Is1555 ? rgb8888From1555(texel)
                                      : rgb8888From565(texel);
                if (Shaded) {
                    const QUint32 bits = static_cast<QUint32>(color);
                    const QInt32 factor = shade >> 12;
                    const QInt32 redBlue = static_cast<QInt32>(
                        (bits & 0x00ff00ffU) *
                        static_cast<QUint32>(factor));
                    const QInt32 green = static_cast<QInt32>(
                        (bits & 0x0000ff00U) *
                        static_cast<QUint32>(factor));
                    const QUint32 blended =
                        ((static_cast<QUint32>(redBlue) & 0xff00ff00U) |
                         (static_cast<QUint32>(green) & 0x00ff0000U)) >> 8U;
                    color = code::Math::javaIntFromUnsigned(blended);
                }
                pixels[destinationIndex + 1] = color;
            }
        }

        uBits += duBits;
        vBits += dvBits;
        if (Shaded) shade += shadeSlope;
        {
            const QUint16 texel = texels[(((vBits >> 12) << widthBit) +
                                          (uBits >> 12)) &
                                         textureMask];
            if (!Is1555 || texel != 0) {
                QInt32 color = Is1555 ? rgb8888From1555(texel)
                                      : rgb8888From565(texel);
                if (Shaded) {
                    const QUint32 bits = static_cast<QUint32>(color);
                    const QInt32 factor = shade >> 12;
                    const QInt32 redBlue = static_cast<QInt32>(
                        (bits & 0x00ff00ffU) *
                        static_cast<QUint32>(factor));
                    const QInt32 green = static_cast<QInt32>(
                        (bits & 0x0000ff00U) *
                        static_cast<QUint32>(factor));
                    const QUint32 blended =
                        ((static_cast<QUint32>(redBlue) & 0xff00ff00U) |
                         (static_cast<QUint32>(green) & 0x00ff0000U)) >> 8U;
                    color = code::Math::javaIntFromUnsigned(blended);
                }
                pixels[destinationIndex + 2] = color;
            }
        }

        uBits += duBits;
        vBits += dvBits;
        if (Shaded) shade += shadeSlope;
        {
            const QUint16 texel = texels[(((vBits >> 12) << widthBit) +
                                          (uBits >> 12)) &
                                         textureMask];
            if (!Is1555 || texel != 0) {
                QInt32 color = Is1555 ? rgb8888From1555(texel)
                                      : rgb8888From565(texel);
                if (Shaded) {
                    const QUint32 bits = static_cast<QUint32>(color);
                    const QInt32 factor = shade >> 12;
                    const QInt32 redBlue = static_cast<QInt32>(
                        (bits & 0x00ff00ffU) *
                        static_cast<QUint32>(factor));
                    const QInt32 green = static_cast<QInt32>(
                        (bits & 0x0000ff00U) *
                        static_cast<QUint32>(factor));
                    const QUint32 blended =
                        ((static_cast<QUint32>(redBlue) & 0xff00ff00U) |
                         (static_cast<QUint32>(green) & 0x00ff0000U)) >> 8U;
                    color = code::Math::javaIntFromUnsigned(blended);
                }
                pixels[destinationIndex + 3] = color;
            }
        }

        x1 += 4;
        destinationIndex += 4;
    }

    while (x1 < x2) {
        uBits += duBits;
        vBits += dvBits;
        if (Shaded) shade += shadeSlope;
        const QUint16 texel = texels[(((vBits >> 12) << widthBit) +
                                      (uBits >> 12)) &
                                     textureMask];
        if (!Is1555 || texel != 0) {
            QInt32 color = Is1555 ? rgb8888From1555(texel)
                                  : rgb8888From565(texel);
            if (Shaded) {
                const QUint32 bits = static_cast<QUint32>(color);
                const QInt32 factor = shade >> 12;
                const QInt32 redBlue = static_cast<QInt32>(
                    (bits & 0x00ff00ffU) * static_cast<QUint32>(factor));
                const QInt32 green = static_cast<QInt32>(
                    (bits & 0x0000ff00U) * static_cast<QUint32>(factor));
                const QUint32 blended =
                    ((static_cast<QUint32>(redBlue) & 0xff00ff00U) |
                     (static_cast<QUint32>(green) & 0x00ff0000U)) >> 8U;
                color = code::Math::javaIntFromUnsigned(blended);
            }
            pixels[destinationIndex] = color;
        }
        ++x1;
        ++destinationIndex;
    }
}

void drawAffineSpan(const SoftwareRenderSurfaceView& destination,
                    const SoftwareTexture16View& texture,
                    QInt32 widthBit,
                    QUint32 textureMask,
                    QInt32 screenY,
                    QInt32 leftFixed,
                    QInt32 rightFixed,
                    QInt32 leftU,
                    QInt32 leftV,
                    QInt32 rightU,
                    QInt32 rightV,
                    QInt32 leftShade,
                    QInt32 rightShade,
                    QInt32 horizontalShadeSlope,
                    QInt32 du,
                    QInt32 dv,
                    bool shaded)
{
    QInt32 x1;
    QInt32 x2;
    QInt32 u;
    QInt32 v;
    QInt32 shade;
    QInt32 shadeSlope = horizontalShadeSlope;

    if (leftFixed > rightFixed) {
        x1 = rightFixed >> 14;
        u = rightU;
        v = rightV;
        shade = rightShade;
        x2 = leftFixed >> 14;
    } else {
        x1 = leftFixed >> 14;
        u = leftU;
        v = leftV;
        shade = leftShade;
        x2 = rightFixed >> 14;
    }

    if (x1 < 0) {
        u = static_cast<QInt32>(static_cast<QInt64>(u) -
                                static_cast<QInt64>(du) * x1);
        v = static_cast<QInt32>(static_cast<QInt64>(v) -
                                static_cast<QInt64>(dv) * x1);
        if (shaded) {
            shade = static_cast<QInt32>(static_cast<QInt64>(shade) -
                                        static_cast<QInt64>(shadeSlope) * x1);
        }
        x1 = 0;
    }
    if (x2 > destination.width) x2 = destination.width;
    if (x2 <= 0 || x1 >= destination.width || x1 >= x2) return;

    const size_t rowOffset = static_cast<size_t>(screenY) *
                             static_cast<size_t>(destination.stride);
    size_t destinationIndex = rowOffset + static_cast<size_t>(x1);

    /* Reference span pre-steps UV so the first sample lands on (u, v). */
    u = static_cast<QInt32>(static_cast<QUint32>(u) -
                            static_cast<QUint32>(du));
    v = static_cast<QInt32>(static_cast<QUint32>(v) -
                            static_cast<QUint32>(dv));
    QUint32 uBits = static_cast<QUint32>(u);
    QUint32 vBits = static_cast<QUint32>(v);
    const QUint32 duBits = static_cast<QUint32>(du);
    const QUint32 dvBits = static_cast<QUint32>(dv);

    if (shaded) {
        if (shade < 0) shade = 0;
        const QInt32 spanLength = x2 - x1;
        if (static_cast<QInt64>(shade) +
                static_cast<QInt64>(shadeSlope) * spanLength < 0) {
            shadeSlope = static_cast<QInt32>(
                static_cast<QInt64>(-shade) / spanLength);
        }
    }

    QInt32* pixels = destination.pixels;
    if (texture.is1555) {
        if (shaded) {
            drawAffineSpanBody<true, true>(texture, widthBit, textureMask,
                                           pixels, destinationIndex, x1, x2,
                                           uBits, vBits, duBits, dvBits,
                                           shade, shadeSlope);
        } else {
            drawAffineSpanBody<true, false>(texture, widthBit, textureMask,
                                            pixels, destinationIndex, x1, x2,
                                            uBits, vBits, duBits, dvBits,
                                            shade, shadeSlope);
        }
    } else {
        if (shaded) {
            drawAffineSpanBody<false, true>(texture, widthBit, textureMask,
                                            pixels, destinationIndex, x1, x2,
                                            uBits, vBits, duBits, dvBits,
                                            shade, shadeSlope);
        } else {
            drawAffineSpanBody<false, false>(texture, widthBit, textureMask,
                                             pixels, destinationIndex, x1, x2,
                                             uBits, vBits, duBits, dvBits,
                                             shade, shadeSlope);
        }
    }
}

bool drawAffineTriangle(const SoftwareRenderSurfaceView& destination,
                        const SoftwareTexture16View& texture,
                        const AffineTriangleVertex& first,
                        const AffineTriangleVertex& second,
                        const AffineTriangleVertex& third,
                        bool shaded,
                        QInt32 brightnessA,
                        QInt32 brightnessB,
                        QInt32 brightnessC)
{
    if (!validSurface(destination)) return false;
    if (destination.width == 0 || destination.height == 0) return true;
    if (!validTexture16(texture)) return false;

    AffineTriangleVertex a(first);
    AffineTriangleVertex b(second);
    AffineTriangleVertex c(third);
    QInt32 shadeA = brightnessA;
    QInt32 shadeB = brightnessB;
    QInt32 shadeC = brightnessC;

    if (b.screenY < a.screenY) {
        swapVertices(a, b);
        QInt32 t = shadeA;
        shadeA = shadeB;
        shadeB = t;
    }
    if (c.screenY < a.screenY) {
        swapVertices(a, c);
        QInt32 t = shadeA;
        shadeA = shadeC;
        shadeC = t;
    }
    if (c.screenY < b.screenY) {
        swapVertices(b, c);
        QInt32 t = shadeB;
        shadeB = shadeC;
        shadeC = t;
    }
    if (a.screenY == c.screenY) return true;

    QInt32 dxStart;
    QInt32 duStart;
    QInt32 dvStart;
    QInt32 dxEnd = 0;
    QInt32 duEnd = 0;
    QInt32 dvEnd = 0;
    QInt32 shadeSlopeStart = 0;
    QInt32 shadeSlopeEnd = 0;

    if (shaded) {
        QInt32 temp = c.screenY - a.screenY;
        if (!computeSlope(shadeC - shadeA, temp, KTextureFractionBits,
                          shadeSlopeStart)) {
            return true;
        }
        if (b.screenY != a.screenY) {
            temp = b.screenY - a.screenY;
            if (!computeSlope(shadeB - shadeA, temp, KTextureFractionBits,
                              shadeSlopeEnd)) {
                return true;
            }
        }
    }

    QInt32 temp = c.screenY - a.screenY;
    if (!computeSlope(c.screenX - a.screenX, temp, KScreenFractionBits,
                      dxStart) ||
        !computeSlope(c.textureU - a.textureU, temp, KTextureFractionBits,
                      duStart) ||
        !computeSlope(c.textureV - a.textureV, temp, KTextureFractionBits,
                      dvStart)) {
        return true;
    }
    if (b.screenY != a.screenY) {
        temp = b.screenY - a.screenY;
        if (!computeSlope(b.screenX - a.screenX, temp, KScreenFractionBits,
                          dxEnd) ||
            !computeSlope(b.textureU - a.textureU, temp,
                          KTextureFractionBits, duEnd) ||
            !computeSlope(b.textureV - a.textureV, temp,
                          KTextureFractionBits, dvEnd)) {
            return true;
        }
    }

    const QInt32 fixedAX = a.screenX << 14;
    const QInt32 fixedAU = a.textureU << 12;
    const QInt32 fixedAV = a.textureV << 12;
    const QInt32 fixedAShade = shadeA << 12;
    const QInt32 fixedBX = b.screenX << 14;
    const QInt32 fixedBU = b.textureU << 12;
    const QInt32 fixedBV = b.textureV << 12;
    const QInt32 fixedBShade = shadeB << 12;

    temp = b.screenY - a.screenY;
    QInt32 xStart = advance(fixedAX, dxStart, temp);
    QInt32 uStart = advance(fixedAU, duStart, temp);
    QInt32 vStart = advance(fixedAV, dvStart, temp);
    QInt32 shadeStart = advance(fixedAShade, shadeSlopeStart, temp);
    QInt32 xEnd = fixedBX;
    QInt32 uEnd = fixedBU;
    QInt32 vEnd = fixedBV;
    QInt32 shadeEnd = fixedBShade;

    temp = (xStart - xEnd) >> 14;
    if (temp == 0) return true;
    QInt32 du = (uStart - uEnd) / temp;
    QInt32 dv = (vStart - vEnd) / temp;
    QInt32 shadeSlope = 0;
    if (shaded) {
        shadeSlope = (shadeStart - shadeEnd) / temp;
        if (shadeSlope > 0) shadeSlope -= 1;
        if (shadeSlope < 0) shadeSlope += 1;
    }

    xStart = fixedAX;
    uStart = fixedAU;
    vStart = fixedAV;
    shadeStart = fixedAShade;
    xEnd = fixedAX;
    uEnd = fixedAU;
    vEnd = fixedAV;
    shadeEnd = fixedAShade;

    QInt32 yStart = a.screenY;
    const QInt32 yEnd =
        c.screenY < destination.height ? c.screenY : destination.height;
    if (yEnd <= 0 || yStart >= yEnd) return true;

    if (yStart < 0) {
        yStart = 0;
        const QInt32 rowsFromA = yStart - a.screenY;
        xStart = advance(fixedAX, dxStart, rowsFromA);
        uStart = advance(fixedAU, duStart, rowsFromA);
        vStart = advance(fixedAV, dvStart, rowsFromA);
        shadeStart = advance(fixedAShade, shadeSlopeStart, rowsFromA);

        if (yStart >= b.screenY) {
            temp = c.screenY - b.screenY;
            if (!computeSlope(c.screenX - b.screenX, temp,
                              KScreenFractionBits, dxEnd) ||
                !computeSlope(c.textureU - b.textureU, temp,
                              KTextureFractionBits, duEnd) ||
                !computeSlope(c.textureV - b.textureV, temp,
                              KTextureFractionBits, dvEnd)) {
                return true;
            }
            if (shaded &&
                !computeSlope(shadeC - shadeB, temp, KTextureFractionBits,
                              shadeSlopeEnd)) {
                return true;
            }
            temp = yStart - b.screenY;
            xEnd = advance(fixedBX, dxEnd, temp);
            uEnd = advance(fixedBU, duEnd, temp);
            vEnd = advance(fixedBV, dvEnd, temp);
            shadeEnd = advance(fixedBShade, shadeSlopeEnd, temp);
        } else {
            temp = yStart - a.screenY;
            xEnd = advance(fixedAX, dxEnd, temp);
            uEnd = advance(fixedAU, duEnd, temp);
            vEnd = advance(fixedAV, dvEnd, temp);
            shadeEnd = advance(fixedAShade, shadeSlopeEnd, temp);
        }
    }

    const QInt32 widthBit = textureWidthBit(texture.width);
    const QUint32 textureMask = static_cast<QUint32>(texture.pixelCount - 1);

    for (; yStart < yEnd; ++yStart,
             xStart += dxStart, uStart += duStart, vStart += dvStart,
             shadeStart += shadeSlopeStart, xEnd += dxEnd, uEnd += duEnd,
             vEnd += dvEnd, shadeEnd += shadeSlopeEnd) {
        if (yStart == b.screenY) {
            if (c.screenY == b.screenY) return true;
            temp = b.screenY - a.screenY;
            xStart = advance(fixedAX, dxStart, temp);
            uStart = advance(fixedAU, duStart, temp);
            vStart = advance(fixedAV, dvStart, temp);
            shadeStart = advance(fixedAShade, shadeSlopeStart, temp);
            xEnd = fixedBX;
            uEnd = fixedBU;
            vEnd = fixedBV;
            shadeEnd = fixedBShade;

            temp = c.screenY - b.screenY;
            if (!computeSlope(c.screenX - b.screenX, temp,
                              KScreenFractionBits, dxEnd) ||
                !computeSlope(c.textureU - b.textureU, temp,
                              KTextureFractionBits, duEnd) ||
                !computeSlope(c.textureV - b.textureV, temp,
                              KTextureFractionBits, dvEnd)) {
                return true;
            }
            if (shaded &&
                !computeSlope(shadeC - shadeB, temp, KTextureFractionBits,
                              shadeSlopeEnd)) {
                return true;
            }
        }

        drawAffineSpan(destination, texture, widthBit, textureMask, yStart,
                       xStart, xEnd, uStart, vStart, uEnd, vEnd, shadeStart,
                       shadeEnd, shadeSlope, du, dv, shaded);
    }

    return true;
}

/* ---------------- perspective ---------------- */

struct PerspectiveVertex {
    QInt32 screenX;
    QInt32 screenY;
    QInt32 reciprocalDepth;
    QInt32 uOverDepth;
    QInt32 vOverDepth;
    QInt32 shade;

    PerspectiveVertex()
        : screenX(0), screenY(0), reciprocalDepth(0), uOverDepth(0),
          vOverDepth(0), shade(0)
    {
    }
};

struct FixedPerspectivePoint {
    QInt32 screenX;
    QInt32 reciprocalDepth;
    QInt32 uOverDepth;
    QInt32 vOverDepth;
    QInt32 shade;

    FixedPerspectivePoint()
        : screenX(0), reciprocalDepth(0), uOverDepth(0), vOverDepth(0),
          shade(0)
    {
    }
};

struct PerspectiveSlope {
    QInt32 screenX;
    QInt32 reciprocalDepth;
    QInt32 uOverDepth;
    QInt32 vOverDepth;
    QInt32 shade;

    PerspectiveSlope()
        : screenX(0), reciprocalDepth(0), uOverDepth(0), vOverDepth(0),
          shade(0)
    {
    }
};

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
                           QInt32 shade,
                           PerspectiveVertex& result)
{
    QInt32 denominator = move;
    if (source.depthRz < 0) {
        denominator = -source.depthRz + move;
    }
    if (denominator == 0) return false;
    result.screenX = source.screenX;
    result.screenY = source.screenY;
    result.reciprocalDepth = reciprocalNumerator / denominator;
    result.uOverDepth = source.textureU * result.reciprocalDepth;
    result.vOverDepth = source.textureV * result.reciprocalDepth;
    result.shade = shade;
    return true;
}

FixedPerspectivePoint toFixed(const PerspectiveVertex& vertex)
{
    FixedPerspectivePoint result;
    result.screenX = vertex.screenX << 12;
    result.reciprocalDepth = vertex.reciprocalDepth << 12;
    result.uOverDepth = vertex.uOverDepth << 12;
    result.vOverDepth = vertex.vOverDepth << 12;
    result.shade = vertex.shade << 12;
    return result;
}

void swapPerspectiveVertices(PerspectiveVertex& left, PerspectiveVertex& right)
{
    const PerspectiveVertex temporary(left);
    left = right;
    right = temporary;
}

bool computePerspectiveSlope(QInt32 start, QInt32 end, QInt32 rows,
                             QInt32& slope)
{
    if (rows == 0) return false;
    slope = divideShift12(end - start, rows);
    return true;
}

bool computePerspectiveSlope(const PerspectiveVertex& start,
                             const PerspectiveVertex& end,
                             QInt32 rows,
                             PerspectiveSlope& slope)
{
    return computePerspectiveSlope(start.screenX, end.screenX, rows,
                                   slope.screenX) &&
           computePerspectiveSlope(start.reciprocalDepth, end.reciprocalDepth,
                                   rows, slope.reciprocalDepth) &&
           computePerspectiveSlope(start.uOverDepth, end.uOverDepth, rows,
                                   slope.uOverDepth) &&
           computePerspectiveSlope(start.vOverDepth, end.vOverDepth, rows,
                                   slope.vOverDepth) &&
           computePerspectiveSlope(start.shade, end.shade, rows, slope.shade);
}

FixedPerspectivePoint advancePoint(const FixedPerspectivePoint& initial,
                                   const PerspectiveSlope& slope,
                                   QInt32 rows)
{
    FixedPerspectivePoint result;
    result.screenX = static_cast<QInt32>(
        static_cast<QInt64>(initial.screenX) +
        static_cast<QInt64>(slope.screenX) * rows);
    result.reciprocalDepth = static_cast<QInt32>(
        static_cast<QInt64>(initial.reciprocalDepth) +
        static_cast<QInt64>(slope.reciprocalDepth) * rows);
    result.uOverDepth = static_cast<QInt32>(
        static_cast<QInt64>(initial.uOverDepth) +
        static_cast<QInt64>(slope.uOverDepth) * rows);
    result.vOverDepth = static_cast<QInt32>(
        static_cast<QInt64>(initial.vOverDepth) +
        static_cast<QInt64>(slope.vOverDepth) * rows);
    result.shade = static_cast<QInt32>(
        static_cast<QInt64>(initial.shade) +
        static_cast<QInt64>(slope.shade) * rows);
    return result;
}

void advancePointOneRow(FixedPerspectivePoint& point,
                        const PerspectiveSlope& slope)
{
    point.screenX += slope.screenX;
    point.reciprocalDepth += slope.reciprocalDepth;
    point.uOverDepth += slope.uOverDepth;
    point.vOverDepth += slope.vOverDepth;
    point.shade += slope.shade;
}

QInt32 adjustForFraction(QInt32 value, QInt32 horizontalSlope,
                         QInt32 fraction)
{
    return static_cast<QInt32>(static_cast<QInt64>(value) -
                               multiplyShift12(horizontalSlope, fraction));
}

bool perspectiveDivide(QInt32 numerator, QInt32 denominator, QInt32& result)
{
    if (denominator == 0) return false;
    if (numerator == 0) {
        result = 0;
        return true;
    }
    result = divideShift12(numerator, denominator);
    return true;
}

template <bool Is1555, bool Shaded>
inline void drawPerspectiveSpanBody(const SoftwareTexture16View& texture,
                                    QInt32 widthBit,
                                    QUint32 textureMask,
                                    QInt32* pixels,
                                    QInt32& x1,
                                    QInt32 x2,
                                    FixedPerspectivePoint& left,
                                    const FixedPerspectivePoint& right,
                                    const PerspectiveSlope& horizontalSlope,
                                    QInt32 perspectiveStepPixels,
                                    QUint32& uBits,
                                    QUint32& vBits,
                                    QInt32& shade,
                                    size_t rowOffset)
{
    const QUint16* texels = texture.pixels;
    const QInt32 shadeSlope = horizontalSlope.shade;
    QInt32 u = static_cast<QInt32>(uBits);
    QInt32 v = static_cast<QInt32>(vBits);
    QInt32 length = x2 - x1;

    while (length > 0) {
        QInt32 lineEnd;
        QInt32 du;
        QInt32 dv;
        if (length >= perspectiveStepPixels) {
            lineEnd = x1 + perspectiveStepPixels;
            left.uOverDepth += horizontalSlope.uOverDepth *
                               perspectiveStepPixels;
            left.vOverDepth += horizontalSlope.vOverDepth *
                               perspectiveStepPixels;
            left.reciprocalDepth += horizontalSlope.reciprocalDepth *
                                    perspectiveStepPixels;

            QInt32 endU;
            QInt32 endV;
            if (!perspectiveDivide(left.uOverDepth, left.reciprocalDepth,
                                   endU) ||
                !perspectiveDivide(left.vOverDepth, left.reciprocalDepth,
                                   endV)) {
                return;
            }
            const QInt32 divisor = perspectiveStepPixels + 1;
            du = (endU - u) / divisor;
            dv = (endV - v) / divisor;
            length -= perspectiveStepPixels;
        } else {
            lineEnd = x2;
            QInt32 endU;
            QInt32 endV;
            if (!perspectiveDivide(right.uOverDepth, right.reciprocalDepth,
                                   endU) ||
                !perspectiveDivide(right.vOverDepth, right.reciprocalDepth,
                                   endV)) {
                return;
            }
            du = (endU - u) / length;
            dv = (endV - v) / length;
            length = 0;
        }

        const QUint32 duBits = static_cast<QUint32>(du);
        const QUint32 dvBits = static_cast<QUint32>(dv);
        while (x1 < lineEnd) {
            uBits += duBits;
            vBits += dvBits;
            if (Shaded) shade += shadeSlope;
            const QUint16 texel =
                texels[(((vBits >> 12) << widthBit) + (uBits >> 12)) &
                       textureMask];
            if (!Is1555 || texel != 0) {
                QInt32 color = Is1555 ? rgb8888From1555(texel)
                                      : rgb8888From565(texel);
                if (Shaded) {
                    const QUint32 bits = static_cast<QUint32>(color);
                    QInt32 factor = shade >> 12;
                    if (factor < 0) factor = 0;
                    if (factor > 255) factor = 255;
                    const QInt32 redBlue = static_cast<QInt32>(
                        (bits & 0x00ff00ffU) * static_cast<QUint32>(factor));
                    const QInt32 green = static_cast<QInt32>(
                        (bits & 0x0000ff00U) * static_cast<QUint32>(factor));
                    const QUint32 blended =
                        ((static_cast<QUint32>(redBlue) & 0xff00ff00U) |
                         (static_cast<QUint32>(green) & 0x00ff0000U)) >> 8U;
                    color = code::Math::javaIntFromUnsigned(blended);
                }
                pixels[rowOffset + static_cast<size_t>(x1)] = color;
            }
            ++x1;
        }
        u = static_cast<QInt32>(uBits);
        v = static_cast<QInt32>(vBits);
    }
}

bool drawPerspectiveSpan(const SoftwareRenderSurfaceView& destination,
                         const SoftwareTexture16View& texture,
                         QInt32 widthBit,
                         QUint32 textureMask,
                         QInt32 screenY,
                         FixedPerspectivePoint left,
                         FixedPerspectivePoint right,
                         const PerspectiveSlope& horizontalSlope,
                         QInt32 perspectiveStepPixels,
                         bool shaded)
{
    const QInt32 subtexelLeft = left.screenX % 4096;
    const QInt32 subtexelRight = right.screenX % 4096;

    left.reciprocalDepth = adjustForFraction(
        left.reciprocalDepth, horizontalSlope.reciprocalDepth, subtexelLeft);
    left.uOverDepth = adjustForFraction(
        left.uOverDepth, horizontalSlope.uOverDepth, subtexelLeft);
    left.vOverDepth = adjustForFraction(
        left.vOverDepth, horizontalSlope.vOverDepth, subtexelLeft);
    left.shade = adjustForFraction(
        left.shade, horizontalSlope.shade, subtexelLeft);
    right.reciprocalDepth = adjustForFraction(
        right.reciprocalDepth, horizontalSlope.reciprocalDepth, subtexelRight);
    right.uOverDepth = adjustForFraction(
        right.uOverDepth, horizontalSlope.uOverDepth, subtexelRight);
    right.vOverDepth = adjustForFraction(
        right.vOverDepth, horizontalSlope.vOverDepth, subtexelRight);
    right.shade = adjustForFraction(
        right.shade, horizontalSlope.shade, subtexelRight);

    QInt32 x1 = left.screenX >> 12;
    QInt32 x2 = right.screenX >> 12;
    if (x1 < 0) {
        right.reciprocalDepth -= horizontalSlope.reciprocalDepth * x1;
        right.uOverDepth -= horizontalSlope.uOverDepth * x1;
        right.vOverDepth -= horizontalSlope.vOverDepth * x1;
        right.shade -= horizontalSlope.shade * x1;
        x1 = 0;
    }
    if (x2 > destination.width) {
        const QInt32 excess = x2 - destination.width;
        right.reciprocalDepth -= horizontalSlope.reciprocalDepth * excess;
        right.uOverDepth -= horizontalSlope.uOverDepth * excess;
        right.vOverDepth -= horizontalSlope.vOverDepth * excess;
        right.shade -= horizontalSlope.shade * excess;
        x2 = destination.width;
    }

    if (left.reciprocalDepth == 0 || right.reciprocalDepth == 0) return true;
    if (x2 <= 0 || x1 >= destination.width || x1 >= x2) return true;

    QInt32 u;
    QInt32 v;
    if (!perspectiveDivide(left.uOverDepth, left.reciprocalDepth, u) ||
        !perspectiveDivide(left.vOverDepth, left.reciprocalDepth, v)) {
        return false;
    }

    QUint32 uBits = static_cast<QUint32>(u);
    QUint32 vBits = static_cast<QUint32>(v);
    QInt32 shade = left.shade;
    const size_t rowOffset = static_cast<size_t>(screenY) *
                             static_cast<size_t>(destination.stride);
    QInt32* pixels = destination.pixels;

    if (texture.is1555) {
        if (shaded) {
            drawPerspectiveSpanBody<true, true>(texture, widthBit, textureMask,
                                                pixels, x1, x2, left, right,
                                                horizontalSlope,
                                                perspectiveStepPixels, uBits,
                                                vBits, shade, rowOffset);
        } else {
            drawPerspectiveSpanBody<true, false>(texture, widthBit, textureMask,
                                                 pixels, x1, x2, left, right,
                                                 horizontalSlope,
                                                 perspectiveStepPixels, uBits,
                                                 vBits, shade, rowOffset);
        }
    } else {
        if (shaded) {
            drawPerspectiveSpanBody<false, true>(texture, widthBit, textureMask,
                                                 pixels, x1, x2, left, right,
                                                 horizontalSlope,
                                                 perspectiveStepPixels, uBits,
                                                 vBits, shade, rowOffset);
        } else {
            drawPerspectiveSpanBody<false, false>(texture, widthBit, textureMask,
                                                  pixels, x1, x2, left, right,
                                                  horizontalSlope,
                                                  perspectiveStepPixels, uBits,
                                                  vBits, shade, rowOffset);
        }
    }
    return true;
}

bool drawPerspectiveTriangle(const SoftwareRenderSurfaceView& destination,
                             const SoftwareTexture16View& texture,
                             const AffineTriangleVertex& first,
                             const AffineTriangleVertex& second,
                             const AffineTriangleVertex& third,
                             bool shaded,
                             QInt32 brightnessA,
                             QInt32 brightnessB,
                             QInt32 brightnessC,
                             QInt32 perspectiveStepPixels,
                             QInt32 move)
{
    if (!validSurface(destination)) return false;
    if (destination.width == 0 || destination.height == 0) return true;
    if (!validTexture16(texture) || perspectiveStepPixels <= 0 || move <= 0) {
        return false;
    }

    const QInt32 maximumDimension = texture.width > texture.height
                                        ? texture.width
                                        : texture.height;
    const QInt32 reciprocalNumerator =
        (textureWUnit(maximumDimension) >> 12) + move;

    PerspectiveVertex a;
    PerspectiveVertex b;
    PerspectiveVertex c;
    if (!makePerspectiveVertex(first, reciprocalNumerator, move,
                               shaded ? brightnessA : 255, a) ||
        !makePerspectiveVertex(second, reciprocalNumerator, move,
                               shaded ? brightnessB : 255, b) ||
        !makePerspectiveVertex(third, reciprocalNumerator, move,
                               shaded ? brightnessC : 255, c)) {
        return false;
    }

    if (b.screenY < a.screenY) swapPerspectiveVertices(a, b);
    if (c.screenY < a.screenY) swapPerspectiveVertices(a, c);
    if (c.screenY < b.screenY) swapPerspectiveVertices(b, c);
    if (a.screenY == c.screenY) return true;

    PerspectiveSlope slopeAC;
    PerspectiveSlope slopeAB;
    QInt32 rowsAC = c.screenY - a.screenY;
    QInt32 rowsAB = b.screenY - a.screenY;
    if (!computePerspectiveSlope(a, c, rowsAC, slopeAC)) return true;
    if (b.screenY != a.screenY &&
        !computePerspectiveSlope(a, b, rowsAB, slopeAB)) {
        return true;
    }
    PerspectiveSlope slopeBC;
    if (c.screenY != b.screenY &&
        !computePerspectiveSlope(b, c, c.screenY - b.screenY, slopeBC)) {
        return true;
    }

    const FixedPerspectivePoint fixedA = toFixed(a);
    const FixedPerspectivePoint fixedB = toFixed(b);

    /* Reference derives the horizontal slope from the B split scanline. */
    const FixedPerspectivePoint longAtB =
        advancePoint(fixedA, slopeAC, rowsAB);
    const QInt32 horizontalPixels = (longAtB.screenX - fixedB.screenX) >> 12;
    if (horizontalPixels == 0) return true;
    PerspectiveSlope horizontalSlope;
    horizontalSlope.screenX = 0;
    horizontalSlope.reciprocalDepth =
        (longAtB.reciprocalDepth - fixedB.reciprocalDepth) /
        horizontalPixels;
    horizontalSlope.uOverDepth =
        (longAtB.uOverDepth - fixedB.uOverDepth) / horizontalPixels;
    horizontalSlope.vOverDepth =
        (longAtB.vOverDepth - fixedB.vOverDepth) / horizontalPixels;
    horizontalSlope.shade =
        (longAtB.shade - fixedB.shade) / horizontalPixels;

    const QInt32 yStart = a.screenY < 0 ? 0 : a.screenY;
    const QInt32 yEnd = c.screenY < destination.height
                            ? c.screenY : destination.height;
    if (yEnd <= 0 || yStart >= yEnd) return true;

    const QInt32 widthBit = textureWidthBit(texture.width);
    const QUint32 textureMask = static_cast<QUint32>(texture.pixelCount - 1);
    const QInt32 rowsFromStart = yStart - a.screenY;
    FixedPerspectivePoint longEdge = advancePoint(fixedA, slopeAC, rowsFromStart);
    FixedPerspectivePoint shortEdge;
    if (yStart < b.screenY) {
        shortEdge = advancePoint(fixedA, slopeAB, rowsFromStart);
    } else {
        shortEdge = advancePoint(fixedB, slopeAB, yStart - b.screenY);
    }

    QInt32 y;
    for (y = yStart; y < yEnd; ++y) {
        if (y == b.screenY) shortEdge = fixedB;
        FixedPerspectivePoint left = longEdge;
        FixedPerspectivePoint right = shortEdge;
        if (left.screenX > right.screenX) {
            left = shortEdge;
            right = longEdge;
        }
        if (!drawPerspectiveSpan(destination, texture, widthBit, textureMask,
                                 y, left, right, horizontalSlope,
                                 perspectiveStepPixels, shaded)) {
            return false;
        }
        advancePointOneRow(longEdge, slopeAC);
        if (y < b.screenY) {
            advancePointOneRow(shortEdge, slopeAB);
        } else {
            advancePointOneRow(shortEdge, slopeBC);
        }
    }
    return true;
}

} // namespace

bool SoftwareFastRasterizer::drawAffineMode0(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTexture16View& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third)
{
    return drawAffineTriangle(destination, texture, first, second, third,
                              false, 255, 255, 255);
}

bool SoftwareFastRasterizer::drawAffineMode9(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTexture16View& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    code::Math::QInt32 firstBrightness,
    code::Math::QInt32 secondBrightness,
    code::Math::QInt32 thirdBrightness)
{
    return drawAffineTriangle(destination, texture, first, second, third,
                              true, firstBrightness, secondBrightness,
                              thirdBrightness);
}

bool SoftwareFastRasterizer::drawPerspectiveMode0(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTexture16View& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    code::Math::QInt32 perspectiveStepPixels,
    code::Math::QInt32 move)
{
    return drawPerspectiveTriangle(destination, texture, first, second, third,
                                   false, 255, 255, 255,
                                   perspectiveStepPixels, move);
}

bool SoftwareFastRasterizer::drawPerspectiveMode9(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareTexture16View& texture,
    const AffineTriangleVertex& first,
    const AffineTriangleVertex& second,
    const AffineTriangleVertex& third,
    code::Math::QInt32 firstBrightness,
    code::Math::QInt32 secondBrightness,
    code::Math::QInt32 thirdBrightness,
    code::Math::QInt32 perspectiveStepPixels,
    code::Math::QInt32 move)
{
    return drawPerspectiveTriangle(destination, texture, first, second, third,
                                   true, firstBrightness, secondBrightness,
                                   thirdBrightness, perspectiveStepPixels,
                                   move);
}

} // namespace Rendering
} // namespace code
