#ifndef QUANTUM_OMAP1710_RASTER_H
#define QUANTUM_OMAP1710_RASTER_H

#include "quantum_integer.h"

namespace code {
namespace Math {
namespace ArmV5teDsp {

/*
 * State shared by the ARMv5TE DMODE 9 perspective-span kernel and its host
 * reference. All values are bit-patterns where the renderer uses Java int
 * wraparound. The device assembly keeps the hot fields in registers while
 * writing the destination span sequentially; no cache maintenance or
 * device-specific memory assumptions are made.
 */
struct Omap1710Mode9SpanArgs {
    QInt32* destination;
    const QInt32* texture;
    QInt32 pixelCount;
    QInt32 textureWidthBit;
    QUint32 textureMask;
    QUint32 uBits;
    QUint32 vBits;
    QUint32 duBits;
    QUint32 dvBits;
    QUint32 shadeBits;
    QUint32 shadeSlopeBits;
};

/*
 * Host model of the E65 ARM-state span kernel. A transparent zero texel
 * leaves both the destination and shade accumulator unchanged, matching the
 * Java DMODE 9 path. Multiplication and coordinate increments wrap modulo
 * 2^32, exactly as in the renderer's unsigned bit-pattern operations.
 */
inline void omap1710Mode9SpanReference(Omap1710Mode9SpanArgs& args,
                                      bool mayContainZeroTexels)
{
    QInt32 i;
    for (i = 0; i < args.pixelCount; ++i) {
        args.uBits += args.duBits;
        args.vBits += args.dvBits;

        const QUint32 row = args.vBits >> 12U;
        const QUint32 column = args.uBits >> 12U;
        const QUint32 index =
            ((row << static_cast<unsigned int>(args.textureWidthBit)) +
             column) & args.textureMask;
        const QUint32 texel = static_cast<QUint32>(args.texture[index]);
        if (mayContainZeroTexels && texel == 0U) {
            continue;
        }

        args.shadeBits += args.shadeSlopeBits;
        const QUint32 brightness = args.shadeBits >> 12U;
        const QUint32 redBlue = (texel & 0x00ff00ffU) * brightness;
        const QUint32 green = (texel & 0x0000ff00U) * brightness;
        const QUint32 output =
            ((redBlue & 0xff00ff00U) |
             (green & 0x00ff0000U)) >> 8U;
        args.destination[i] = javaIntFromUnsigned(output);
    }
}

} // namespace ArmV5teDsp
} // namespace Math
} // namespace code

#endif // QUANTUM_OMAP1710_RASTER_H
