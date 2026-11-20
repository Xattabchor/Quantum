#ifndef QUANTUM_SCREEN_COPY_H
#define QUANTUM_SCREEN_COPY_H

#include "quantum_types.h"

namespace code {
namespace Rendering {

/*
 * Convert Quantum's 32-bit RGB framebuffer (the format passed to J2ME
 * Graphics.drawRGB with processAlpha=false) into a pitched RGB565 surface.
 * Strides are in pixels, not bytes. Returns false for invalid dimensions,
 * strides, or pointers. A zero-sized region succeeds without touching memory.
 */
bool copyArgb8888ToRgb565(const code::Math::QInt32* source,
                          code::Math::QInt32 sourceStride,
                          code::Math::QUint16* destination,
                          code::Math::QInt32 destinationStride,
                          code::Math::QInt32 width,
                          code::Math::QInt32 height);

/*
 * Store one 0xAARRGGBB framebuffer pixel in packed Symbian EColor16M form.
 * EColor16M is 24 bpp; its little-endian byte order is B, G, R. The alpha
 * byte is discarded. The destination must point to at least three bytes.
 */
inline void storeRgb888ToEColor16M(code::Math::QInt32 source,
                                   unsigned char* destination)
{
    const code::Math::QUint32 rgb =
        static_cast<code::Math::QUint32>(source);
    destination[0] = static_cast<unsigned char>(rgb & 0xffU);
    destination[1] = static_cast<unsigned char>((rgb >> 8U) & 0xffU);
    destination[2] = static_cast<unsigned char>((rgb >> 16U) & 0xffU);
}

/*
 * Store one 0xAARRGGBB pixel in little-endian Symbian EColor64K RGB565 form.
 * Alpha is discarded; the destination must point to at least two bytes.
 */
inline void storeRgb888ToEColor64K(code::Math::QInt32 source,
                                   unsigned char* destination)
{
    const code::Math::QUint32 rgb =
        static_cast<code::Math::QUint32>(source);
    const code::Math::QUint16 rgb565 = static_cast<code::Math::QUint16>(
        (((rgb >> 19U) & 0x1fU) << 11U) |
        (((rgb >> 10U) & 0x3fU) << 5U) |
        ((rgb >> 3U) & 0x1fU));
    destination[0] = static_cast<unsigned char>(rgb565 & 0xffU);
    destination[1] = static_cast<unsigned char>((rgb565 >> 8U) & 0xffU);
}

} // namespace Rendering
} // namespace code

#endif // QUANTUM_SCREEN_COPY_H
