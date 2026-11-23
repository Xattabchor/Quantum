#include "software_texture16.h"

#include "quantum_integer.h"

namespace code {
namespace Rendering {

using code::Math::QInt32;
using code::Math::QUint16;
using code::Math::QUint32;
using code::Math::QUint64;

SoftwareTexture16View::SoftwareTexture16View()
    : pixels(0), pixelCount(0), width(0), height(0),
      hasTransparentTexels(false), is1555(false)
{
}

SoftwareTexture16View::SoftwareTexture16View(
    const QUint16* texturePixels,
    QInt32 texturePixelCount,
    QInt32 textureWidth,
    QInt32 textureHeight,
    bool textureIs1555,
    bool textureHasTransparentTexels)
    : pixels(texturePixels), pixelCount(texturePixelCount),
      width(textureWidth), height(textureHeight),
      hasTransparentTexels(textureHasTransparentTexels),
      is1555(textureIs1555)
{
}

void SoftwareTexture16::convert8888To565(const QInt32* source,
                                         QUint16* destination,
                                         QInt32 pixelCount)
{
    if (source == 0 || destination == 0 || pixelCount <= 0) return;
    QInt32 i;
    for (i = 0; i < pixelCount; ++i) {
        destination[i] = rgb565From8888(source[i]);
    }
}

void SoftwareTexture16::convert8888To1555(const QInt32* source,
                                          QUint16* destination,
                                          QInt32 pixelCount)
{
    if (source == 0 || destination == 0 || pixelCount <= 0) return;
    QInt32 i;
    for (i = 0; i < pixelCount; ++i) {
        destination[i] = rgb1555From8888(source[i]);
    }
}

void SoftwareTexture16::convert565To8888(const QUint16* source,
                                         QInt32* destination,
                                         QInt32 pixelCount)
{
    if (source == 0 || destination == 0 || pixelCount <= 0) return;
    QInt32 i;
    for (i = 0; i < pixelCount; ++i) {
        destination[i] = rgb8888From565(source[i]);
    }
}

void SoftwareTexture16::convert1555To8888(const QUint16* source,
                                          QInt32* destination,
                                          QInt32 pixelCount)
{
    if (source == 0 || destination == 0 || pixelCount <= 0) return;
    QInt32 i;
    for (i = 0; i < pixelCount; ++i) {
        destination[i] = rgb8888From1555(source[i]);
    }
}

bool SoftwareTexture16::makeMip565(const QUint16* source,
                                   QInt32 width,
                                   QInt32 height,
                                   QUint16* destination)
{
    if (source == 0 || destination == 0 || width < 2 || height < 2) {
        return false;
    }
    const QInt32 destWidth = width / 2;
    const QInt32 destHeight = height / 2;
    QInt32 y;
    QInt32 x;
    for (y = 0; y < destHeight; ++y) {
        for (x = 0; x < destWidth; ++x) {
            const QInt32 sourceIndex = (y * 2) * width + (x * 2);
            const QInt32 p0 = rgb8888From565(source[sourceIndex]);
            const QInt32 p1 = rgb8888From565(source[sourceIndex + 1]);
            const QInt32 p2 =
                rgb8888From565(source[sourceIndex + width]);
            const QInt32 p3 =
                rgb8888From565(source[sourceIndex + width + 1]);
            const QUint32 b0 = static_cast<QUint32>(p0);
            const QUint32 b1 = static_cast<QUint32>(p1);
            const QUint32 b2 = static_cast<QUint32>(p2);
            const QUint32 b3 = static_cast<QUint32>(p3);
            const QUint32 red =
                (((b0 >> 16) & 0xffU) + ((b1 >> 16) & 0xffU) +
                 ((b2 >> 16) & 0xffU) + ((b3 >> 16) & 0xffU)) / 4U;
            const QUint32 green =
                (((b0 >> 8) & 0xffU) + ((b1 >> 8) & 0xffU) +
                 ((b2 >> 8) & 0xffU) + ((b3 >> 8) & 0xffU)) / 4U;
            const QUint32 blue =
                ((b0 & 0xffU) + (b1 & 0xffU) + (b2 & 0xffU) +
                 (b3 & 0xffU)) / 4U;
            const QUint32 averaged =
                0xff000000U | (red << 16U) | (green << 8U) | blue;
            destination[y * destWidth + x] =
                rgb565From8888(code::Math::javaIntFromUnsigned(averaged));
        }
    }
    return true;
}

bool SoftwareTexture16::makeMip1555(const QUint16* source,
                                    QInt32 width,
                                    QInt32 height,
                                    QUint16* destination)
{
    if (source == 0 || destination == 0 || width < 2 || height < 2) {
        return false;
    }
    const QInt32 destWidth = width / 2;
    const QInt32 destHeight = height / 2;
    QInt32 y;
    QInt32 x;
    for (y = 0; y < destHeight; ++y) {
        for (x = 0; x < destWidth; ++x) {
            const QInt32 sourceIndex = (y * 2) * width + (x * 2);
            const QUint16 t0 = source[sourceIndex];
            const QUint16 t1 = source[sourceIndex + 1];
            const QUint16 t2 = source[sourceIndex + width];
            const QUint16 t3 = source[sourceIndex + width + 1];
            const QInt32 opaqueCount =
                ((t0 & 0x8000U) != 0U ? 1 : 0) +
                ((t1 & 0x8000U) != 0U ? 1 : 0) +
                ((t2 & 0x8000U) != 0U ? 1 : 0) +
                ((t3 & 0x8000U) != 0U ? 1 : 0);
            if (opaqueCount < 2) {
                destination[y * destWidth + x] = 0;
                continue;
            }
            const QInt32 p0 = rgb8888From1555(t0);
            const QInt32 p1 = rgb8888From1555(t1);
            const QInt32 p2 = rgb8888From1555(t2);
            const QInt32 p3 = rgb8888From1555(t3);
            const QUint32 b0 = static_cast<QUint32>(p0);
            const QUint32 b1 = static_cast<QUint32>(p1);
            const QUint32 b2 = static_cast<QUint32>(p2);
            const QUint32 b3 = static_cast<QUint32>(p3);
            const QUint32 red =
                (((b0 >> 16) & 0xffU) + ((b1 >> 16) & 0xffU) +
                 ((b2 >> 16) & 0xffU) + ((b3 >> 16) & 0xffU)) / 4U;
            const QUint32 green =
                (((b0 >> 8) & 0xffU) + ((b1 >> 8) & 0xffU) +
                 ((b2 >> 8) & 0xffU) + ((b3 >> 8) & 0xffU)) / 4U;
            const QUint32 blue =
                ((b0 & 0xffU) + (b1 & 0xffU) + (b2 & 0xffU) +
                 (b3 & 0xffU)) / 4U;
            const QUint32 averaged =
                0xff000000U | (red << 16U) | (green << 8U) | blue;
            destination[y * destWidth + x] =
                rgb1555From8888(code::Math::javaIntFromUnsigned(averaged));
        }
    }
    return true;
}

namespace {

const QUint64 KMaxInt32 = static_cast<QUint64>(0x7fffffffUL);
const QInt32 KPaletteIndexCapacity = 256;

struct QctHeader16 {
    QInt32 width;
    QInt32 height;
    QInt32 pixelCount;
    QInt32 paletteColorCount;
    QInt32 alphaType;
    QInt32 paletteOffset;
    QInt32 paletteEnd;
};

QInt32 readUnsignedShort16(const unsigned char* data, QInt32 offset)
{
    return (static_cast<QInt32>(data[offset]) << 8) |
           static_cast<QInt32>(data[offset + 1]);
}

bool parseHeader16(const unsigned char* data,
                   QInt32 dataLength,
                   QctHeader16& header)
{
    if (data == 0 || dataLength < 12) return false;

    const QInt32 headerSize = readUnsignedShort16(data, 3);
    const QInt32 width = readUnsignedShort16(data, 6);
    const QInt32 height = readUnsignedShort16(data, 8);
    const QInt32 alphaType = static_cast<QInt32>(data[11]);
    const QInt32 minimumHeaderSize = alphaType >= 1 ? 8 : 7;
    if (headerSize < minimumHeaderSize || width <= 0 || height <= 0) {
        return false;
    }
    if (headerSize > dataLength - 5) return false;

    const unsigned int paletteShift =
        (static_cast<unsigned int>(data[10]) + 1U) & 31U;
    if (paletteShift == 31U) return false;
    const QUint32 paletteCountBits =
        static_cast<QUint32>(1U) << paletteShift;
    if (paletteCountBits == 0U ||
        static_cast<QUint64>(paletteCountBits) > KMaxInt32) {
        return false;
    }

    const QUint64 pixelCount = static_cast<QUint64>(width) *
                               static_cast<QUint64>(height);
    if (pixelCount == 0U || pixelCount > KMaxInt32) return false;

    const QInt32 paletteOffset = 5 + headerSize;
    const QUint64 paletteBytes =
        static_cast<QUint64>(paletteCountBits) * 3U;
    const QInt32 remainingAfterHeader = dataLength - paletteOffset;
    if (remainingAfterHeader < 0 ||
        paletteBytes > static_cast<QUint64>(remainingAfterHeader)) {
        return false;
    }
    const QInt32 paletteEnd = paletteOffset +
        static_cast<QInt32>(paletteBytes);

    const QUint64 blockCount =
        static_cast<QUint64>(width / 2) *
        static_cast<QUint64>(height / 2);
    const QUint64 blockBytes = blockCount * 3U;
    const QInt32 remainingAfterPalette = dataLength - paletteEnd;
    if (remainingAfterPalette < 0 ||
        blockBytes > static_cast<QUint64>(remainingAfterPalette)) {
        return false;
    }

    header.width = width;
    header.height = height;
    header.pixelCount = static_cast<QInt32>(pixelCount);
    header.paletteColorCount = static_cast<QInt32>(paletteCountBits);
    header.alphaType = alphaType;
    header.paletteOffset = paletteOffset;
    header.paletteEnd = paletteEnd;
    return true;
}

void downsizeBothAxes16(QUint16* pixels,
                        QInt32& width,
                        QInt32& height,
                        bool useAlpha)
{
    const QInt32 sourceWidth = width;
    const QInt32 sourceHeight = height;
    const QInt32 destinationWidth = sourceWidth / 2;
    const QInt32 destinationHeight = sourceHeight / 2;
    QInt32 y;
    QInt32 x;

    for (y = 0; y < destinationHeight; ++y) {
        for (x = 0; x < destinationWidth; ++x) {
            const QInt32 sourceIndex =
                (y * 2) * sourceWidth + (x * 2);
            const QUint16 t0 = pixels[sourceIndex];
            const QUint16 t1 = pixels[sourceIndex + 1];
            const QUint16 t2 = pixels[sourceIndex + sourceWidth];
            const QUint16 t3 = pixels[sourceIndex + sourceWidth + 1];
            if (useAlpha) {
                const QInt32 opaqueCount =
                    ((t0 & 0x8000U) != 0U ? 1 : 0) +
                    ((t1 & 0x8000U) != 0U ? 1 : 0) +
                    ((t2 & 0x8000U) != 0U ? 1 : 0) +
                    ((t3 & 0x8000U) != 0U ? 1 : 0);
                if (opaqueCount < 2) {
                    pixels[y * destinationWidth + x] = 0;
                    continue;
                }
                const QInt32 p0 = rgb8888From1555(t0);
                const QInt32 p1 = rgb8888From1555(t1);
                const QInt32 p2 = rgb8888From1555(t2);
                const QInt32 p3 = rgb8888From1555(t3);
                const QUint32 b0 = static_cast<QUint32>(p0);
                const QUint32 b1 = static_cast<QUint32>(p1);
                const QUint32 b2 = static_cast<QUint32>(p2);
                const QUint32 b3 = static_cast<QUint32>(p3);
                const QUint32 red =
                    (((b0 >> 16) & 0xffU) + ((b1 >> 16) & 0xffU) +
                     ((b2 >> 16) & 0xffU) + ((b3 >> 16) & 0xffU)) / 4U;
                const QUint32 green =
                    (((b0 >> 8) & 0xffU) + ((b1 >> 8) & 0xffU) +
                     ((b2 >> 8) & 0xffU) + ((b3 >> 8) & 0xffU)) / 4U;
                const QUint32 blue =
                    ((b0 & 0xffU) + (b1 & 0xffU) + (b2 & 0xffU) +
                     (b3 & 0xffU)) / 4U;
                const QUint32 averaged =
                    0xff000000U | (red << 16U) | (green << 8U) | blue;
                pixels[y * destinationWidth + x] = rgb1555From8888(
                    code::Math::javaIntFromUnsigned(averaged));
            } else {
                const QInt32 p0 = rgb8888From565(t0);
                const QInt32 p1 = rgb8888From565(t1);
                const QInt32 p2 = rgb8888From565(t2);
                const QInt32 p3 = rgb8888From565(t3);
                const QUint32 b0 = static_cast<QUint32>(p0);
                const QUint32 b1 = static_cast<QUint32>(p1);
                const QUint32 b2 = static_cast<QUint32>(p2);
                const QUint32 b3 = static_cast<QUint32>(p3);
                const QUint32 red =
                    (((b0 >> 16) & 0xffU) + ((b1 >> 16) & 0xffU) +
                     ((b2 >> 16) & 0xffU) + ((b3 >> 16) & 0xffU)) / 4U;
                const QUint32 green =
                    (((b0 >> 8) & 0xffU) + ((b1 >> 8) & 0xffU) +
                     ((b2 >> 8) & 0xffU) + ((b3 >> 8) & 0xffU)) / 4U;
                const QUint32 blue =
                    ((b0 & 0xffU) + (b1 & 0xffU) + (b2 & 0xffU) +
                     (b3 & 0xffU)) / 4U;
                const QUint32 averaged =
                    0xff000000U | (red << 16U) | (green << 8U) | blue;
                pixels[y * destinationWidth + x] = rgb565From8888(
                    code::Math::javaIntFromUnsigned(averaged));
            }
        }
    }

    width = destinationWidth;
    height = destinationHeight;
}

void downsizeVertical16(QUint16* pixels,
                        QInt32& width,
                        QInt32& height,
                        bool useAlpha)
{
    const QInt32 sourceWidth = width;
    const QInt32 sourceHeight = height;
    const QInt32 destinationHeight = sourceHeight / 2;
    QInt32 y;
    QInt32 x;

    for (y = 0; y < destinationHeight; ++y) {
        for (x = 0; x < sourceWidth; ++x) {
            const QInt32 sourceIndex = (y * 2) * sourceWidth + x;
            const QUint16 t0 = pixels[sourceIndex];
            const QUint16 t1 = pixels[sourceIndex + sourceWidth];
            if (useAlpha) {
                const QInt32 opaqueCount =
                    ((t0 & 0x8000U) != 0U ? 1 : 0) +
                    ((t1 & 0x8000U) != 0U ? 1 : 0);
                if (opaqueCount == 0) {
                    pixels[y * sourceWidth + x] = 0;
                    continue;
                }
                const QInt32 p0 = rgb8888From1555(t0);
                const QInt32 p1 = rgb8888From1555(t1);
                const QUint32 b0 = static_cast<QUint32>(p0);
                const QUint32 b1 = static_cast<QUint32>(p1);
                const QUint32 red =
                    (((b0 >> 16) & 0xffU) + ((b1 >> 16) & 0xffU)) / 2U;
                const QUint32 green =
                    (((b0 >> 8) & 0xffU) + ((b1 >> 8) & 0xffU)) / 2U;
                const QUint32 blue =
                    ((b0 & 0xffU) + (b1 & 0xffU)) / 2U;
                const QUint32 averaged =
                    0xff000000U | (red << 16U) | (green << 8U) | blue;
                pixels[y * sourceWidth + x] = rgb1555From8888(
                    code::Math::javaIntFromUnsigned(averaged));
            } else {
                const QInt32 p0 = rgb8888From565(t0);
                const QInt32 p1 = rgb8888From565(t1);
                const QUint32 b0 = static_cast<QUint32>(p0);
                const QUint32 b1 = static_cast<QUint32>(p1);
                const QUint32 red =
                    (((b0 >> 16) & 0xffU) + ((b1 >> 16) & 0xffU)) / 2U;
                const QUint32 green =
                    (((b0 >> 8) & 0xffU) + ((b1 >> 8) & 0xffU)) / 2U;
                const QUint32 blue =
                    ((b0 & 0xffU) + (b1 & 0xffU)) / 2U;
                const QUint32 averaged =
                    0xff000000U | (red << 16U) | (green << 8U) | blue;
                pixels[y * sourceWidth + x] = rgb565From8888(
                    code::Math::javaIntFromUnsigned(averaged));
            }
        }
    }

    height = destinationHeight;
}

} // anonymous namespace

bool SoftwareTexture16::convertLevel8888To16(
    const QInt32* source,
    QInt32 width,
    QInt32 height,
    bool useAlpha,
    QUint16* destination,
    QInt32 destinationCapacity,
    bool& outIs1555,
    bool& outHasTransparentTexels)
{
    outIs1555 = false;
    outHasTransparentTexels = false;
    if (source == 0 || destination == 0) return false;
    if (width <= 0 || height <= 0) return false;
    const QUint64 pixelCount64 = static_cast<QUint64>(width) *
                                static_cast<QUint64>(height);
    if (pixelCount64 > KMaxInt32) return false;
    const QInt32 pixelCount = static_cast<QInt32>(pixelCount64);
    if (destinationCapacity < pixelCount) return false;

    /*
     * 1555 keeps 1-bit alpha, so it is only worth its extra cost when the
     * source actually has transparent texels. Otherwise stay on RGB565.
     */
    bool hasTransparency = false;
    if (useAlpha) {
        QInt32 index;
        for (index = 0; index < pixelCount; ++index) {
            if (source[index] == 0) {
                hasTransparency = true;
                break;
            }
        }
    }

    if (hasTransparency) {
        convert8888To1555(source, destination, pixelCount);
        outIs1555 = true;
        outHasTransparentTexels = true;
    } else {
        convert8888To565(source, destination, pixelCount);
    }
    return true;
}

bool SoftwareTexture16::decodeQctTo16(const unsigned char* data,
                                      QInt32 dataLength,
                                      QInt32 pixelsQ,
                                      bool useAlpha,
                                      QUint16* outputPixels,
                                      QInt32 outputPixelCapacity,
                                      QInt32& outWidth,
                                      QInt32& outHeight,
                                      bool& outHasTransparentTexels)
{
    outWidth = 0;
    outHeight = 0;
    outHasTransparentTexels = false;
    QctHeader16 header;
    if (dataLength < 0 || !parseHeader16(data, dataLength, header) ||
        outputPixels == 0 || outputPixelCapacity < header.pixelCount) {
        return false;
    }

    QInt32 colors[KPaletteIndexCapacity];
    const QInt32 paletteEntriesToStore =
        header.paletteColorCount < KPaletteIndexCapacity
            ? header.paletteColorCount : KPaletteIndexCapacity;
    QInt32 i;
    for (i = 0; i < paletteEntriesToStore; ++i) {
        const QInt32 colorOffset = header.paletteOffset + i * 3;
        const QUint32 color = 0xff000000U |
            (static_cast<QUint32>(data[colorOffset]) << 16) |
            (static_cast<QUint32>(data[colorOffset + 1]) << 8) |
            static_cast<QUint32>(data[colorOffset + 2]);
        colors[i] = code::Math::javaIntFromUnsigned(color);
    }

    for (i = 0; i < header.pixelCount; ++i) outputPixels[i] = 0;

    QInt32 inputOffset = header.paletteEnd;
    QInt32 y;
    QInt32 x;
    for (y = 0; y < header.height / 2; ++y) {
        const QInt32 topRowOffset = (y * 2) * header.width;
        for (x = 0; x < header.width / 2; ++x) {
            const QInt32 color0Index = static_cast<QInt32>(data[inputOffset]);
            const QInt32 color3Index = static_cast<QInt32>(data[inputOffset + 1]);
            inputOffset += 2;
            if (color0Index >= header.paletteColorCount ||
                color3Index >= header.paletteColorCount) {
                return false;
            }

            const QUint32 color0 =
                static_cast<QUint32>(colors[color0Index]);
            const QUint32 color3 =
                static_cast<QUint32>(colors[color3Index]);
            QUint32 blockColors[4];
            blockColors[0] = color0;
            blockColors[3] = color3;
            const QUint32 rb0 = color0 & 0x00ff00ffU;
            const QUint32 rb3 = color3 & 0x00ff00ffU;
            const QUint32 g0 = color0 & 0x0000ff00U;
            const QUint32 g3 = color3 & 0x0000ff00U;

            if (color0Index > color3Index) {
                const QUint32 average =
                    (((rb0 + rb3) & 0x01fe01deU) |
                     ((g0 + g3) & 0x0001fe00U)) >> 1;
                blockColors[1] = average | 0xff000000U;
                blockColors[2] = 0U;
            } else {
                const QUint32 firstWeighted =
                    (((rb0 * 171U + rb3 * 85U) & 0xff00ff00U) |
                     ((g0 * 171U + g3 * 85U) & 0x00ff0000U)) >> 8;
                const QUint32 secondWeighted =
                    (((rb0 * 85U + rb3 * 171U) & 0xff00ff00U) |
                     ((g0 * 85U + g3 * 171U) & 0x00ff0000U)) >> 8;
                blockColors[1] = firstWeighted | 0xff000000U;
                blockColors[2] = secondWeighted | 0xff000000U;
            }

            const QUint32 selectors =
                static_cast<QUint32>(data[inputOffset++]);
            const QInt32 topLeftIndex = topRowOffset + (x * 2);
            QUint32 block16[4];
            QInt32 blockIndex;
            for (blockIndex = 0; blockIndex < 4; ++blockIndex) {
                const QInt32 blockPixel =
                    code::Math::javaIntFromUnsigned(blockColors[blockIndex]);
                block16[blockIndex] = useAlpha
                    ? rgb1555From8888(blockPixel)
                    : rgb565From8888(blockPixel);
            }
            outputPixels[topLeftIndex] =
                static_cast<QUint16>(block16[selectors & 3U]);
            outputPixels[topLeftIndex + 1] =
                static_cast<QUint16>(block16[(selectors >> 2) & 3U]);
            outputPixels[topLeftIndex + header.width] =
                static_cast<QUint16>(block16[(selectors >> 4) & 3U]);
            outputPixels[topLeftIndex + header.width + 1] =
                static_cast<QUint16>(block16[(selectors >> 6) & 3U]);
        }
    }

    if (header.alphaType == 2) {
        QInt32 pixelIndex = 0;
        while (pixelIndex < header.pixelCount) {
            if (useAlpha) {
                if ((outputPixels[pixelIndex] & 0x8000U) == 0U) {
                    ++pixelIndex;
                    continue;
                }
            } else {
                /* 565 has no transparency; validate stream without skipping. */
            }
            if (inputOffset > dataLength || dataLength - inputOffset < 2) {
                return false;
            }

            const QInt32 solidCount = static_cast<QInt32>(data[inputOffset++]);
            const QInt32 alphaCount = static_cast<QInt32>(data[inputOffset++]);
            const QInt32 remainingPixels = header.pixelCount - pixelIndex;
            if (solidCount > remainingPixels) {
                pixelIndex = header.pixelCount;
            } else {
                pixelIndex += solidCount;
            }
            if (alphaCount == 0) continue;

            QInt32 writtenAlphaCount = 0;
            while (pixelIndex < header.pixelCount &&
                   writtenAlphaCount < alphaCount) {
                if (useAlpha &&
                    (outputPixels[pixelIndex] & 0x8000U) == 0U) {
                    ++pixelIndex;
                    continue;
                }
                if (inputOffset >= dataLength) return false;

                const QUint32 alpha =
                    static_cast<QUint32>(data[inputOffset++]);
                if (useAlpha && alpha < 128U) {
                    outputPixels[pixelIndex] = 0;
                }
                ++writtenAlphaCount;
                ++pixelIndex;
            }
        }
    }

    QInt32 width = header.width;
    QInt32 height = header.height;
    if (pixelsQ == 0) {
        downsizeBothAxes16(outputPixels, width, height, useAlpha);
    }
    if (pixelsQ == 1) {
        downsizeVertical16(outputPixels, width, height, useAlpha);
    }

    bool hasTransparentTexels = false;
    const QInt32 pixelCount = width * height;
    if (useAlpha) {
        for (i = 0; i < pixelCount; ++i) {
            if ((outputPixels[i] & 0x8000U) == 0U) {
                outputPixels[i] = 0;
                hasTransparentTexels = true;
            }
        }
    }

    outWidth = width;
    outHeight = height;
    outHasTransparentTexels = hasTransparentTexels;
    return true;
}

} // namespace Rendering
} // namespace code
