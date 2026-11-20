#include "software_qct_texture_decoder.h"

#include "quantum_integer.h"

namespace code {
namespace Rendering {

using code::Math::QInt32;
using code::Math::QUint32;
using code::Math::QUint64;

namespace {

const QUint64 KMaxInt32 = static_cast<QUint64>(0x7fffffffUL);
const QInt32 KPaletteIndexCapacity = 256;

struct QctHeader {
    QInt32 width;
    QInt32 height;
    QInt32 pixelCount;
    QInt32 paletteColorCount;
    QInt32 alphaType;
    QInt32 paletteOffset;
    QInt32 paletteEnd;
};

QInt32 readUnsignedShort(const unsigned char* data, QInt32 offset)
{
    return (static_cast<QInt32>(data[offset]) << 8) |
           static_cast<QInt32>(data[offset + 1]);
}

bool parseHeader(const unsigned char* data,
                 QInt32 dataLength,
                 QctHeader& header)
{
    if (data == 0 || dataLength < 12) return false;

    const QInt32 headerSize = readUnsignedShort(data, 3);
    const QInt32 width = readUnsignedShort(data, 6);
    const QInt32 height = readUnsignedShort(data, 8);
    const QInt32 alphaType = static_cast<QInt32>(data[11]);
    const QInt32 minimumHeaderSize = alphaType >= 1 ? 8 : 7;
    if (headerSize < minimumHeaderSize || width <= 0 || height <= 0) {
        return false;
    }
    if (headerSize > dataLength - 5) return false;

    const unsigned int paletteShift =
        (static_cast<unsigned int>(data[10]) + 1U) & 31U;
    /* Java's 1 << 31 is negative and cannot size its palette array. */
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

void setHeaderInfo(const QctHeader& header, SoftwareQctTextureInfo& info)
{
    info.sourceWidth = header.width;
    info.sourceHeight = header.height;
    info.sourcePixelCount = header.pixelCount;
    info.width = header.width;
    info.height = header.height;
    info.pixelCount = header.pixelCount;
    info.paletteColorCount = header.paletteColorCount;
    info.alphaType = header.alphaType;
}

QInt32 javaByteValue(QInt32 value)
{
    const QInt32 lowByte = static_cast<QInt32>(
        static_cast<QUint32>(value) & 0xffU);
    return lowByte >= 128 ? lowByte - 256 : lowByte;
}

QUint32 channelAverage4(QUint32 first,
                        QUint32 second,
                        QUint32 third,
                        QUint32 fourth,
                        unsigned int shift)
{
    const QUint32 sum = ((first >> shift) & 0xffU) +
                        ((second >> shift) & 0xffU) +
                        ((third >> shift) & 0xffU) +
                        ((fourth >> shift) & 0xffU);
    return sum / 4U;
}

void downsizeBothAxes(QInt32* pixels, QInt32& width, QInt32& height)
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
            const QUint32 topLeft =
                static_cast<QUint32>(pixels[sourceIndex]);
            const QUint32 topRight =
                static_cast<QUint32>(pixels[sourceIndex + 1]);
            const QUint32 bottomLeft =
                static_cast<QUint32>(pixels[sourceIndex + sourceWidth]);
            const QUint32 bottomRight =
                static_cast<QUint32>(pixels[sourceIndex + sourceWidth + 1]);
            const QUint32 alpha = topLeft & 0xff000000U;
            const QUint32 red = channelAverage4(
                topLeft, topRight, bottomRight, bottomLeft, 16U);
            const QUint32 green = channelAverage4(
                topLeft, topRight, bottomRight, bottomLeft, 8U);
            const QUint32 blue = channelAverage4(
                topLeft, topRight, bottomRight, bottomLeft, 0U);
            const QUint32 result = alpha | (red << 16) | (green << 8) | blue;
            pixels[y * destinationWidth + x] =
                code::Math::javaIntFromUnsigned(result);
        }
    }

    width = destinationWidth;
    height = destinationHeight;
}

void downsizeVertical(QInt32* pixels, QInt32& width, QInt32& height)
{
    const QInt32 sourceWidth = width;
    const QInt32 sourceHeight = height;
    const QInt32 destinationHeight = sourceHeight / 2;
    QInt32 y;
    QInt32 x;

    for (y = 0; y < destinationHeight; ++y) {
        for (x = 0; x < sourceWidth; ++x) {
            const QInt32 sourceIndex = (y * 2) * sourceWidth + x;
            const QUint32 top =
                static_cast<QUint32>(pixels[sourceIndex]);
            const QUint32 bottom =
                static_cast<QUint32>(pixels[sourceIndex + sourceWidth]);
            const QUint32 alpha = top & 0xff000000U;
            const QUint32 red =
                (((top >> 16) & 0xffU) + ((bottom >> 16) & 0xffU)) / 2U;
            const QUint32 green =
                (((top >> 8) & 0xffU) + ((bottom >> 8) & 0xffU)) / 2U;
            const QUint32 blue =
                ((top & 0xffU) + (bottom & 0xffU)) / 2U;
            const QUint32 result = alpha | (red << 16) | (green << 8) | blue;
            pixels[y * sourceWidth + x] =
                code::Math::javaIntFromUnsigned(result);
        }
    }

    height = destinationHeight;
}

} // anonymous namespace

SoftwareQctTextureInfo::SoftwareQctTextureInfo()
    : sourceWidth(0), sourceHeight(0), sourcePixelCount(0),
      width(0), height(0), pixelCount(0), paletteColorCount(0),
      alphaType(0), scale(0), hasTransparentTexels(false), alphaMixing(false)
{
}

bool SoftwareQctTextureDecoder::inspect(
    const unsigned char* data,
    QInt32 dataLength,
    SoftwareQctTextureInfo& info)
{
    info = SoftwareQctTextureInfo();
    QctHeader header;
    if (dataLength < 0 || !parseHeader(data, dataLength, header)) return false;
    setHeaderInfo(header, info);
    return true;
}

bool SoftwareQctTextureDecoder::decode(
    const unsigned char* data,
    QInt32 dataLength,
    QInt32 pixelsQ,
    QInt32* outputPixels,
    QInt32 outputPixelCapacity,
    SoftwareQctTextureInfo& info)
{
    info = SoftwareQctTextureInfo();
    QctHeader header;
    if (dataLength < 0 || !parseHeader(data, dataLength, header) ||
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
            outputPixels[topLeftIndex] = code::Math::javaIntFromUnsigned(
                blockColors[selectors & 3U]);
            outputPixels[topLeftIndex + 1] =
                code::Math::javaIntFromUnsigned(
                    blockColors[(selectors >> 2) & 3U]);
            outputPixels[topLeftIndex + header.width] =
                code::Math::javaIntFromUnsigned(
                    blockColors[(selectors >> 4) & 3U]);
            outputPixels[topLeftIndex + header.width + 1] =
                code::Math::javaIntFromUnsigned(
                    blockColors[(selectors >> 6) & 3U]);
        }
    }

    if (header.alphaType == 2) {
        QInt32 pixelIndex = 0;
        while (pixelIndex < header.pixelCount) {
            if (((static_cast<QUint32>(outputPixels[pixelIndex]) >> 24) &
                 0xffU) == 0U) {
                ++pixelIndex;
                continue;
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
                if (((static_cast<QUint32>(outputPixels[pixelIndex]) >> 24) &
                     0xffU) == 0U) {
                    ++pixelIndex;
                    continue;
                }
                if (inputOffset >= dataLength) return false;

                const QUint32 oldPixel =
                    static_cast<QUint32>(outputPixels[pixelIndex]);
                const QUint32 alpha =
                    static_cast<QUint32>(data[inputOffset++]) << 24;
                outputPixels[pixelIndex] =
                    code::Math::javaIntFromUnsigned(
                        alpha | (oldPixel & 0x00ffffffU));
                ++writtenAlphaCount;
                ++pixelIndex;
            }
        }
    }

    QInt32 width = header.width;
    QInt32 height = header.height;
    if (pixelsQ == 0) {
        downsizeBothAxes(outputPixels, width, height);
    }
    if (pixelsQ == 1) {
        downsizeVertical(outputPixels, width, height);
    }

    bool hasTransparentTexels = false;
    bool alphaMixing = false;
    const QInt32 pixelCount = width * height;
    for (i = 0; i < pixelCount; ++i) {
        const QUint32 pixel = static_cast<QUint32>(outputPixels[i]);
        const QUint32 alpha = (pixel >> 24) & 0xffU;
        if (alpha == 0U) {
            outputPixels[i] = 0;
            hasTransparentTexels = true;
        } else if (alpha < 255U) {
            alphaMixing = true;
        }
    }

    setHeaderInfo(header, info);
    info.width = width;
    info.height = height;
    info.pixelCount = pixelCount;
    info.scale = javaByteValue(pixelsQ);
    info.hasTransparentTexels = hasTransparentTexels;
    info.alphaMixing = alphaMixing;
    return true;
}

} // namespace Rendering
} // namespace code
