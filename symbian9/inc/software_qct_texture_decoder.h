#ifndef QUANTUM_SOFTWARE_QCT_TEXTURE_DECODER_H
#define QUANTUM_SOFTWARE_QCT_TEXTURE_DECODER_H

#include "quantum_types.h"

namespace code {
namespace Rendering {

/*
 * Metadata for one decoded Java QCT RawImage. After decode(), width/height
 * describe the pixelsQ-adjusted image; sourceWidth/sourceHeight retain the
 * encoded dimensions. Flags are meaningful only after a successful decode.
 */
struct SoftwareQctTextureInfo {
    code::Math::QInt32 sourceWidth;
    code::Math::QInt32 sourceHeight;
    code::Math::QInt32 sourcePixelCount;
    code::Math::QInt32 width;
    code::Math::QInt32 height;
    code::Math::QInt32 pixelCount;
    code::Math::QInt32 paletteColorCount;
    code::Math::QInt32 alphaType;
    code::Math::QInt32 scale;
    bool hasTransparentTexels;
    bool alphaMixing;

    SoftwareQctTextureInfo();
};

/*
 * Allocation-free decoder for RawImage.qctToRawImage(). Input bytes and
 * output ARGB8888 pixels are caller-owned. The output buffer must have room
 * for sourceWidth*sourceHeight pixels even when pixelsQ will downscale it;
 * the reduced image is written in place at the start of that buffer.
 *
 * inspect() reads the header and verifies the palette and fixed-size 2x2 block
 * stream, but does not validate alphaType==2 run data or palette indices.
 * decode() validates both while producing pixels. On decode failure the pixel
 * buffer may have been partially modified and must be discarded.
 *
 * pixelsQ matches Java's Main.pixelsQ behavior for QCT images: 0 halves both
 * dimensions (including the legacy zero-size result for a one-pixel axis),
 * 1 halves the vertical dimension, and other values leave dimensions
 * unchanged. RGB averaging,
 * top-left alpha preservation, zero-alpha normalization, alphaMixing, and the
 * RawImage byte-scale narrowing are preserved.
 *
 * This class does not perform Symbian file I/O, generic PNG/JPEG decoding,
 * Asset caching, or mipmap creation.
 */
class SoftwareQctTextureDecoder {
public:
    static bool inspect(const unsigned char* data,
                        code::Math::QInt32 dataLength,
                        SoftwareQctTextureInfo& info);

    static bool decode(const unsigned char* data,
                       code::Math::QInt32 dataLength,
                       code::Math::QInt32 pixelsQ,
                       code::Math::QInt32* outputPixels,
                       code::Math::QInt32 outputPixelCapacity,
                       SoftwareQctTextureInfo& info);
};

} // namespace Rendering
} // namespace code

#endif // QUANTUM_SOFTWARE_QCT_TEXTURE_DECODER_H
