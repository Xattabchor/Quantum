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

/*
 * Three packed formats, all two bytes per texel:
 *  - RGB565   opaque world geometry; 6-bit green, 5-bit red/blue
 *  - RGB1555  1-bit alpha for keyed sprites and textures with cut-outs
 *  - ARGB4444 4-bit alpha, used only where real translucency is needed
 *             (alpha-blended sprites); its channels are coarser than 565,
 *             so it is never chosen as a general texture format
 *
 * The fast rasterizer covers RGB565 and RGB1555 through Is1555 template
 * specialisation. ARGB4444 is not routed through those kernels: it is
 * consumed by the sprite alpha-blend path, which expands each texel with
 * argb8888From4444() and keeps its existing 32-bit arithmetic.
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
    /* True for ARGB4444; never set together with is1555. */
    bool is4444;

    SoftwareTexture16View();
    SoftwareTexture16View(const code::Math::QUint16* texturePixels,
                          code::Math::QInt32 texturePixelCount,
                          code::Math::QInt32 textureWidth,
                          code::Math::QInt32 textureHeight,
                          bool textureIs1555 = false,
                          bool textureHasTransparentTexels = false,
                          bool textureIs4444 = false);

    /* Expands this view's texel regardless of format. */
    code::Math::QInt32 expandTexel(code::Math::QUint16 texel) const;
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
    /*
     * 1555 is only valid as a transparent-key format when its zero texel and
     * its clear alpha bit mean the same thing. rgb8888From1555() reports
     * exactly those two cases as 0, so any source texel whose alpha sits
     * below the 1-bit threshold must collapse to 0 as well. Otherwise it
     * would decode to pitch black while still passing the `texel != 0` key
     * test, drawing opaque black holes where partial alpha used to blend.
     */
    const code::Math::QUint32 bits = static_cast<code::Math::QUint32>(pixel);
    const code::Math::QUint32 alpha = (bits >> 24U) & 0xffU;
    if (alpha < 128U) {
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

/*
 * ARGB4444: AAAA RRRR GGGG BBBB. Used only where per-texel alpha is needed,
 * which in this renderer means alpha-blended sprites. Four bits per channel
 * is coarser than RGB565 (max +-8.5 vs +-4.1/+-2.0 per channel), so this
 * format is never a general texture format; it is a targeted replacement
 * for alpha blending that 1-bit RGB1555 cannot express.
 *
 * Four-bit alpha expands to a full 0..255 range by bit replication, which
 * keeps 0 == fully transparent and 255 (from 15) == fully opaque, so the
 * sprite alpha path sees the same domain it always has.
 */
inline code::Math::QUint16 argb4444From8888(code::Math::QInt32 pixel)
{
    const code::Math::QUint32 bits = static_cast<code::Math::QUint32>(pixel);
    /* Each field takes the source channel's high nibble into its slot:
       alpha -> bits 12-15, red -> 8-11, green -> 4-7, blue -> 0-3. */
    const code::Math::QUint32 alpha = ((bits >> 24U) & 0xf0U) << 8U;
    const code::Math::QUint32 red = ((bits >> 16U) & 0xf0U) << 4U;
    const code::Math::QUint32 green = (bits >> 8U) & 0xf0U;
    const code::Math::QUint32 blue = (bits >> 4U) & 0x0fU;
    return static_cast<code::Math::QUint16>(
        alpha | red | green | blue);
}

/* Returns 0xAARRGGBB with alpha expanded back to 0..255. */
inline code::Math::QInt32 argb8888From4444(code::Math::QUint16 pixel)
{
    const code::Math::QUint32 nibbleAlpha =
        (static_cast<code::Math::QUint32>(pixel) >> 12U) & 0x0fU;
    const code::Math::QUint32 alpha = (nibbleAlpha << 4U) | nibbleAlpha;
    const code::Math::QUint32 red =
        ((static_cast<code::Math::QUint32>(pixel) >> 8U) & 0x0fU);
    const code::Math::QUint32 green =
        ((static_cast<code::Math::QUint32>(pixel) >> 4U) & 0x0fU);
    const code::Math::QUint32 blue =
        static_cast<code::Math::QUint32>(pixel) & 0x0fU;
    const code::Math::QUint32 result =
        (alpha << 24U) |
        (((red << 4U) | red) << 16U) |
        (((green << 4U) | green) << 8U) |
        ((blue << 4U) | blue);
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
    static void convert8888To4444(const code::Math::QInt32* source,
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
