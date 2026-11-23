#ifndef QUANTUM_SOFTWARE_TEXTURE16_H
#define QUANTUM_SOFTWARE_TEXTURE16_H

#include "quantum_integer.h"
#include "quantum_types.h"

namespace code {
namespace Rendering {

/*
 * 16-bit texture storage for the fast Symbian path.
 *
 * Two packed formats are supported:
 *  - RGB565 (opaque): RRRRRGGGGGGBBBBB. Halves texture bandwidth versus
 *    ARGB8888 and matches the EColor64K presenter format. No alpha.
 *  - RGB1555 (1-bit alpha): ARRRRRGGGGGBBBBB. Alpha bit 0 is transparent,
 *    1 is opaque. Per-pixel alpha from QCT alphaType==2 is thresholded to
 *    one bit (alpha >= 128 is opaque); partial alpha is lost.
 *
 * The 32-bit framebuffer format stays 0xAARRGGBB; 16-bit texels are
 * expanded to 8888 on sample in the fast rasterizers. Conversion truncates
 * (R8>>3, G8>>2) which bounds round-trip error to <= 8 per channel.
 */

struct SoftwareTexture16View {
    const code::Math::QUint16* pixels;
    code::Math::QInt32 pixelCount;
    code::Math::QInt32 width;
    code::Math::QInt32 height;
    /* 1555 only: at least one texel has alpha bit 0. Always false for 565. */
    bool hasTransparentTexels;
    /* False = RGB565 opaque, true = RGB1555 with 1-bit alpha. */
    bool is1555;

    SoftwareTexture16View();
    SoftwareTexture16View(const code::Math::QUint16* texturePixels,
                          code::Math::QInt32 texturePixelCount,
                          code::Math::QInt32 textureWidth,
                          code::Math::QInt32 textureHeight,
                          bool textureIs1555 = false,
                          bool textureHasTransparentTexels = false);
};

inline code::Math::QUint16 rgb565From8888(code::Math::QInt32 pixel)
{
    const code::Math::QUint32 bits = static_cast<code::Math::QUint32>(pixel);
    const code::Math::QUint32 red = (bits >> 19U) & 0x1fU;
    const code::Math::QUint32 green = (bits >> 10U) & 0x3fU;
    const code::Math::QUint32 blue = (bits >> 3U) & 0x1fU;
    return static_cast<code::Math::QUint16>(
        (red << 11U) | (green << 5U) | blue);
}

inline code::Math::QInt32 rgb8888From565(code::Math::QUint16 pixel)
{
    const code::Math::QUint32 red5 =
        (static_cast<code::Math::QUint32>(pixel) >> 11U) & 0x1fU;
    const code::Math::QUint32 green6 =
        (static_cast<code::Math::QUint32>(pixel) >> 5U) & 0x3fU;
    const code::Math::QUint32 blue5 =
        static_cast<code::Math::QUint32>(pixel) & 0x1fU;
    /* Bit replication: 5->8 and 6->8 without division. */
    const code::Math::QUint32 red = (red5 << 3U) | (red5 >> 2U);
    const code::Math::QUint32 green = (green6 << 2U) | (green6 >> 4U);
    const code::Math::QUint32 blue = (blue5 << 3U) | (blue5 >> 2U);
    const code::Math::QUint32 result =
        0xff000000U | (red << 16U) | (green << 8U) | blue;
    return code::Math::javaIntFromUnsigned(result);
}

inline code::Math::QUint16 rgb1555From8888(code::Math::QInt32 pixel)
{
    const code::Math::QUint32 bits = static_cast<code::Math::QUint32>(pixel);
    const code::Math::QUint32 alpha = (bits >> 24U) & 0xffU;
    if (alpha == 0U) {
        return 0;
    }
    const code::Math::QUint32 red = (bits >> 19U) & 0x1fU;
    const code::Math::QUint32 green = (bits >> 11U) & 0x1fU;
    const code::Math::QUint32 blue = (bits >> 3U) & 0x1fU;
    const code::Math::QUint32 alphaBit = alpha >= 128U ? 0x8000U : 0U;
    return static_cast<code::Math::QUint16>(
        alphaBit | (red << 10U) | (green << 5U) | blue);
}

inline code::Math::QInt32 rgb8888From1555(code::Math::QUint16 pixel)
{
    if ((pixel & 0x8000U) == 0U) {
        return 0;
    }
    const code::Math::QUint32 red5 =
        (static_cast<code::Math::QUint32>(pixel) >> 10U) & 0x1fU;
    const code::Math::QUint32 green5 =
        (static_cast<code::Math::QUint32>(pixel) >> 5U) & 0x1fU;
    const code::Math::QUint32 blue5 =
        static_cast<code::Math::QUint32>(pixel) & 0x1fU;
    const code::Math::QUint32 red = (red5 << 3U) | (red5 >> 2U);
    const code::Math::QUint32 green = (green5 << 3U) | (green5 >> 2U);
    const code::Math::QUint32 blue = (blue5 << 3U) | (blue5 >> 2U);
    const code::Math::QUint32 result =
        0xff000000U | (red << 16U) | (green << 8U) | blue;
    return code::Math::javaIntFromUnsigned(result);
}

class SoftwareTexture16 {
public:
    static void convert8888To565(const code::Math::QInt32* source,
                                 code::Math::QUint16* destination,
                                 code::Math::QInt32 pixelCount);
    static void convert8888To1555(const code::Math::QInt32* source,
                                  code::Math::QUint16* destination,
                                  code::Math::QInt32 pixelCount);
    static void convert565To8888(const code::Math::QUint16* source,
                                 code::Math::QInt32* destination,
                                 code::Math::QInt32 pixelCount);
    static void convert1555To8888(const code::Math::QUint16* source,
                                  code::Math::QInt32* destination,
                                  code::Math::QInt32 pixelCount);

    /*
     * Box-filter mip generation in 16-bit space. Destination must hold
     * (width/2)*(height/2) texels. Returns false for invalid dimensions.
     * 1555 alpha uses majority vote (opaque if at least 2 of 4 are opaque).
     */
    static bool makeMip565(const code::Math::QUint16* source,
                           code::Math::QInt32 width,
                           code::Math::QInt32 height,
                           code::Math::QUint16* destination);
    static bool makeMip1555(const code::Math::QUint16* source,
                            code::Math::QInt32 width,
                            code::Math::QInt32 height,
                            code::Math::QUint16* destination);

    /*
     * QCT decode directly to 16-bit. Mirrors SoftwareQctTextureDecoder
     * semantics (header, palette, 2x2 blocks, pixelsQ downscale, alpha
     * normalization) but writes QUint16 texels. useAlpha=false selects
     * RGB565 (alpha ignored, alphaType==2 stream still validated);
     * useAlpha=true selects RGB1555 (alpha thresholded to 1 bit).
     * Output buffer must hold sourceWidth*sourceHeight texels; the
     * pixelsQ-reduced image is written in place at the start.
     */
    /*
     * Build a single 16-bit level from an already-decoded 8888 image.
     *
     * Some 16-bit destinations are needed where no mip chain exists (the
     * fast path runs without mipmapping). useAlpha selects RGB1555 so the
     * source's zero/transparent texels survive as 1-bit alpha; a texture
     * with no transparency stays RGB565 and skips the per-texel alpha test.
     * sourcePixelCount must be at least width*height.
     * hasTransparentTexels is set to whether any output texel is 0 in 1555.
     */
    static bool convertLevel8888To16(const code::Math::QInt32* source,
                                     code::Math::QInt32 width,
                                     code::Math::QInt32 height,
                                     bool useAlpha,
                                     code::Math::QUint16* destination,
                                     code::Math::QInt32 destinationCapacity,
                                     bool& outIs1555,
                                     bool& outHasTransparentTexels);

    static bool decodeQctTo16(const unsigned char* data,
                              code::Math::QInt32 dataLength,
                              code::Math::QInt32 pixelsQ,
                              bool useAlpha,
                              code::Math::QUint16* outputPixels,
                              code::Math::QInt32 outputPixelCapacity,
                              code::Math::QInt32& outWidth,
                              code::Math::QInt32& outHeight,
                              bool& outHasTransparentTexels);
};

} // namespace Rendering
} // namespace code

#endif // QUANTUM_SOFTWARE_TEXTURE16_H
