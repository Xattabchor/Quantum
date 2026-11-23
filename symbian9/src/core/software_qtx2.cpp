#include "software_qtx2.h"

namespace code {
namespace Rendering {

using code::Math::QInt32;
using code::Math::QUint16;
using code::Math::QUint32;

namespace {

/* Little-endian accessors: Symbian files are byte streams, and the device is
 * ARM little-endian, but reading byte by byte keeps the parser correct on any
 * host the tests run on. */
QInt32 readU16(const unsigned char* data)
{
    return static_cast<QInt32>(data[0]) |
           (static_cast<QInt32>(data[1]) << 8);
}

void writeU32(unsigned char* data, QUint32 value)
{
    data[0] = static_cast<unsigned char>(value & 0xffU);
    data[1] = static_cast<unsigned char>((value >> 8U) & 0xffU);
    data[2] = static_cast<unsigned char>((value >> 16U) & 0xffU);
    data[3] = static_cast<unsigned char>((value >> 24U) & 0xffU);
}

void writeU16(unsigned char* data, QUint32 value)
{
    data[0] = static_cast<unsigned char>(value & 0xffU);
    data[1] = static_cast<unsigned char>((value >> 8U) & 0xffU);
}

} // namespace

SoftwareQtx2Header::SoftwareQtx2Header()
    : format(ESoftwareQtx2Rgb565), flags(0), width(0), height(0)
{
}

bool softwareQtx2FormatIsValid(SoftwareQtx2Format format)
{
    return format == ESoftwareQtx2Rgb565 ||
           format == ESoftwareQtx2Rgb1555 ||
           format == ESoftwareQtx2Argb4444;
}

QInt32 softwareQtx2PixelCount(const SoftwareQtx2Header& header)
{
    if (header.width <= 0 || header.height <= 0) return 0;
    return header.width * header.height;
}

bool softwareQtx2Has16(const SoftwareQtx2Header& header)
{
    return (header.flags & SoftwareQtx2FlagHas16) != 0U;
}

bool softwareQtx2HasRgb8888(const SoftwareQtx2Header& header)
{
    return (header.flags & SoftwareQtx2FlagHasRgb8888) != 0U;
}

QInt32 softwareQtx2FileSize(const SoftwareQtx2Header& header)
{
    const QInt32 pixelCount = softwareQtx2PixelCount(header);
    if (pixelCount == 0) return 0;
    if (!softwareQtx2FormatIsValid(header.format)) return 0;
    /* Exactly one block is expected; refuse anything else rather than guess
       where a second block would start. */
    const bool has16 = softwareQtx2Has16(header);
    const bool has8888 = softwareQtx2HasRgb8888(header);
    if (has16 == has8888) return 0;

    QInt32 size = SoftwareQtx2HeaderSize;
    if (has16) size += pixelCount * 2;
    if (has8888) size += pixelCount * 4;
    return size;
}

bool softwareQtx2Inspect(const unsigned char* data,
                         QInt32 length,
                         QInt32 maximumWidth,
                         QInt32 maximumHeight,
                         SoftwareQtx2Header& header,
                         const QUint16** sixteen,
                         const QInt32** rgb8888)
{
    header = SoftwareQtx2Header();
    if (sixteen != 0) *sixteen = 0;
    if (rgb8888 != 0) *rgb8888 = 0;

    if (data == 0 || length < SoftwareQtx2HeaderSize ||
        data[0] != 'Q' || data[1] != 'T' || data[2] != 'X' || data[3] != '2') {
        return false;
    }

    const SoftwareQtx2Format format =
        static_cast<SoftwareQtx2Format>(data[4]);
    if (!softwareQtx2FormatIsValid(format)) return false;

    header.format = format;
    header.flags = data[5];
    header.width = readU16(data + 6);
    header.height = readU16(data + 8);

    if (header.width <= 0 || header.height <= 0 ||
        header.width > maximumWidth || header.height > maximumHeight) {
        header = SoftwareQtx2Header();
        return false;
    }
    /* Reserved bytes must be zero: cheap protection against reading a
       future extension with an older parser. */
    if (data[10] != 0 || data[11] != 0 || data[12] != 0 || data[13] != 0 ||
        data[14] != 0 || data[15] != 0) {
        header = SoftwareQtx2Header();
        return false;
    }

    const QInt32 expectedSize = softwareQtx2FileSize(header);
    if (expectedSize <= 0 || length != expectedSize) {
        header = SoftwareQtx2Header();
        return false;
    }

    const QInt32 payloadOffset = SoftwareQtx2HeaderSize;

    if (sixteen != 0 && softwareQtx2Has16(header)) {
        *sixteen = reinterpret_cast<const QUint16*>(data + payloadOffset);
    }
    if (rgb8888 != 0 && softwareQtx2HasRgb8888(header)) {
        *rgb8888 = reinterpret_cast<const QInt32*>(data + payloadOffset);
    }
    return true;
}

SoftwareTexture16View softwareQtx2TextView(const SoftwareQtx2Header& header,
                                          const QUint16* sixteen)
{
    const QInt32 pixelCount = softwareQtx2PixelCount(header);
    if (sixteen == 0 || pixelCount <= 0) return SoftwareTexture16View();

    bool is1555 = false;
    bool is4444 = false;
    bool hasTransparent = false;

    switch (header.format) {
    case ESoftwareQtx2Rgb565:
        break;
    case ESoftwareQtx2Rgb1555: {
        is1555 = true;
        QInt32 pixel;
        for (pixel = 0; pixel < pixelCount; ++pixel) {
            if ((sixteen[pixel] & 0x8000U) == 0U) {
                hasTransparent = true;
                break;
            }
        }
        break;
    }
    case ESoftwareQtx2Argb4444: {
        is4444 = true;
        QInt32 pixel;
        for (pixel = 0; pixel < pixelCount; ++pixel) {
            if ((sixteen[pixel] & 0xf000U) == 0U) {
                hasTransparent = true;
                break;
            }
        }
        break;
    }
    }

    return SoftwareTexture16View(sixteen, pixelCount,
                                 header.width, header.height,
                                 is1555, hasTransparent, is4444);
}

bool softwareQtx2Write(const SoftwareQtx2Header& header,
                       const QUint16* sixteen,
                       const QInt32* rgb8888,
                       unsigned char* destination,
                       QInt32 capacity,
                       QInt32& written)
{
    written = 0;
    if (destination == 0) return false;
    const QInt32 pixelCount = softwareQtx2PixelCount(header);
    const QInt32 size = softwareQtx2FileSize(header);
    if (pixelCount <= 0 || size <= 0 || capacity < size) return false;
    const bool has16 = softwareQtx2Has16(header);
    const bool hasRgb8888 = softwareQtx2HasRgb8888(header);
    if (has16 && sixteen == 0) return false;
    if (hasRgb8888 && rgb8888 == 0) return false;

    destination[0] = 'Q';
    destination[1] = 'T';
    destination[2] = 'X';
    destination[3] = '2';
    destination[4] = static_cast<unsigned char>(header.format);
    destination[5] = static_cast<unsigned char>(header.flags);
    writeU16(destination + 6, static_cast<QUint32>(header.width));
    writeU16(destination + 8, static_cast<QUint32>(header.height));
    writeU32(destination + 10, 0U);
    writeU16(destination + 14, 0U);

    /* Exactly one block is stored, chosen by the flag. */
    unsigned char* pixels = destination + SoftwareQtx2HeaderSize;
    QInt32 pixel;
    if (has16) {
        for (pixel = 0; pixel < pixelCount; ++pixel) {
            writeU16(pixels + pixel * 2, sixteen[pixel]);
        }
    } else {
        for (pixel = 0; pixel < pixelCount; ++pixel) {
            writeU32(pixels + pixel * 4,
                     static_cast<QUint32>(rgb8888[pixel]));
        }
    }

    written = size;
    return true;
}

SoftwareQtx2Format softwareQtx2FormatForRgb8888(const QInt32* rgb8888,
                                                QInt32 pixelCount)
{
    if (rgb8888 == 0 || pixelCount <= 0) return ESoftwareQtx2Rgb565;
    bool hasTransparent = false;
    bool hasPartial = false;
    QInt32 pixel;
    for (pixel = 0; pixel < pixelCount; ++pixel) {
        const QUint32 alpha =
            static_cast<QUint32>(rgb8888[pixel]) >> 24U;
        if (alpha == 0U) {
            hasTransparent = true;
        } else if (alpha < 255U) {
            hasPartial = true;
        }
    }
    if (hasPartial) return ESoftwareQtx2Argb4444;
    if (hasTransparent) return ESoftwareQtx2Rgb1555;
    return ESoftwareQtx2Rgb565;
}

void softwareQtx2ScanRgb8888(const QInt32* rgb8888,
                             QInt32 pixelCount,
                             bool& hasTransparentTexels,
                             bool& hasAlphaMixing)
{
    hasTransparentTexels = false;
    hasAlphaMixing = false;
    if (rgb8888 == 0 || pixelCount <= 0) return;
    QInt32 pixel;
    for (pixel = 0; pixel < pixelCount; ++pixel) {
        const QUint32 alpha =
            static_cast<QUint32>(rgb8888[pixel]) >> 24U;
        if (alpha == 0U) {
            hasTransparentTexels = true;
        } else if (alpha < 255U) {
            hasAlphaMixing = true;
            hasTransparentTexels = true;
        }
    }
}

} // namespace Rendering
} // namespace code
