#ifndef SOFTWARE_QTX2_H
#define SOFTWARE_QTX2_H

#include "quantum_types.h"
#include "software_texture16.h"

/*
 * QTX2: a QTX whose pixels are already in the engine's 16-bit formats.
 *
 * QTX1 stores raw RGBA8888, so every start-up re-quantised all textures and
 * the archive carried four bytes per texel for data that the renderer turns
 * into two. QTX2 picks the format once, at asset build time, and stores it
 * together with the pixels, so the device does nothing but copy.
 *
 * Layout (little-endian):
 *
 *   offset  size  field
 *   0       4     magic "QTX2"
 *   4       1     format (ESoftwareQtx2Format)
 *   5       1     flags
 *   6       2     width
 *   8       2     height
 *   10      6     reserved, zero
 *   16      -     16-bit pixels, width * height words   (if flag 0x01)
 *   ...     -     8888 pixels, width * height dwords   (if flag 0x02)
 *
 * The header is 16 bytes so both pixel blocks start on a 4-byte boundary.
 * A 14-byte header would put the 8888 block at an odd offset, and ARMv5 does
 * not merely slow down on a misaligned dword load: it faults.
 *
 * Two flags, because the two kinds of asset genuinely differ:
 *
 *   0x01  a 16-bit block is stored. World textures, sky and river use this
 *         and nothing else: start-up copies bytes and never converts.
 *   0x02  an 8888 block is stored. Weapon sprites need it because Java's
 *         ImageResize scales them bilinearly at runtime, and resizing
 *         already-quantised texels would quantise twice. A weapon image
 *         stores the 8888 block instead of the 16-bit one; its `format`
 *         field then declares which 16-bit format the resized result must
 *         be converted to, so the choice is still made at build time
 *         instead of being guessed at start-up.
 *
 * colour formats are read as 0xAARRGGBB dwords and 16-bit words in the
 * engine's existing layouts:
 *   RGB565   RRRRR GGGGGG BBBBB
 *   RGB1555  A RRRRR GGGGG BBBBB   (alpha bit is the zero-texel key)
 *   ARGB4444 AAAA RRRR GGGG BBBB
 */
namespace code {
namespace Rendering {

enum SoftwareQtx2Format {
    ESoftwareQtx2Rgb565 = 0,
    ESoftwareQtx2Rgb1555 = 1,
    ESoftwareQtx2Argb4444 = 2
};

enum {
    SoftwareQtx2HeaderSize = 16,
    /* A 16-bit pixel block follows the header. */
    SoftwareQtx2FlagHas16 = 1,
    /* An 8888 pixel block follows the header. */
    SoftwareQtx2FlagHasRgb8888 = 2
};

struct SoftwareQtx2Header {
    SoftwareQtx2Format format;
    code::Math::QUint32 flags;
    code::Math::QInt32 width;
    code::Math::QInt32 height;

    SoftwareQtx2Header();
};

/* Bytes a texture with this header occupies on disk. */
code::Math::QInt32 softwareQtx2FileSize(const SoftwareQtx2Header& header);
/* Texels in the image, independent of which blocks are stored. */
code::Math::QInt32 softwareQtx2PixelCount(const SoftwareQtx2Header& header);

/* True when the image carries a 16-bit block (the common case). */
bool softwareQtx2Has16(const SoftwareQtx2Header& header);
/* True when the image carries an 8888 block (weapon sprites). */
bool softwareQtx2HasRgb8888(const SoftwareQtx2Header& header);

bool softwareQtx2FormatIsValid(SoftwareQtx2Format format);

/*
 * Validate a QTX2 image. On success `header` describes it, `sixteen` points
 * at the stored 16-bit block and `rgb8888` at the stored 8888 block; either
 * is null when that block is absent. The pointers borrow from `data` and
 * must not be modified.
 *
 * Dimensions are checked against the supplied limits so a corrupt file
 * cannot make the caller allocate arbitrarily.
 */
bool softwareQtx2Inspect(const unsigned char* data,
                         code::Math::QInt32 length,
                         code::Math::QInt32 maximumWidth,
                         code::Math::QInt32 maximumHeight,
                         SoftwareQtx2Header& header,
                         const code::Math::QUint16** sixteen,
                         const code::Math::QInt32** rgb8888);

/*
 * Wrap the stored 16-bit block as a renderer view. `hasTransparentTexels`
 * means "contains the zero key" and `hasAlphaMixing` is only ever true for
 * 4444, where partial alpha is representable.
 */
SoftwareTexture16View softwareQtx2TextView(
    const SoftwareQtx2Header& header,
    const code::Math::QUint16* sixteen);

/*
 * Writer used by the asset tools and by the tests. Writes exactly
 * softwareQtx2FileSize(header) bytes and returns false if `capacity` is too
 * small or any source pointer is missing. `rgb8888` may be null unless the
 * has-8888 flag is set.
 */
bool softwareQtx2Write(const SoftwareQtx2Header& header,
                       const code::Math::QUint16* sixteen,
                       const code::Math::QInt32* rgb8888,
                       unsigned char* destination,
                       code::Math::QInt32 capacity,
                       code::Math::QInt32& written);

/*
 * Choose the smallest format that represents the source honestly.
 *   any texel with 0 < alpha < 255   -> ARGB4444
 *   any fully transparent texel      -> RGB1555  (1-bit key)
 *   otherwise                        -> RGB565
 */
SoftwareQtx2Format softwareQtx2FormatForRgb8888(const code::Math::QInt32* rgb8888,
                                                code::Math::QInt32 pixelCount);

/*
 * Recompute the transparency flags of an 8888 block, matching the QTX1
 * loader: alpha 0 sets hasTransparentTexels, 0 < alpha < 255 sets
 * hasAlphaMixing (and therefore also hasTransparentTexels).
 */
void softwareQtx2ScanRgb8888(const code::Math::QInt32* rgb8888,
                             code::Math::QInt32 pixelCount,
                             bool& hasTransparentTexels,
                             bool& hasAlphaMixing);

} // namespace Rendering
} // namespace code

#endif
