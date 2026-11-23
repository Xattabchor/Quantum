/*
 * QTX2 conformance test.
 *
 * Two things must hold:
 *   1. the C++ reader in software_qtx2.cpp decodes the files that
 *      symbian9/tools/convert_qtx1_to_qtx2.py writes, field for field, so the
 *      asset tool and the device cannot drift apart;
 *   2. the 16-bit pixels stored in those files, expanded back to 8888, match
 *      the original QTX1 source within the format's quantisation error.
 *
 * The second check is the one that matters: it proves that moving the
 * conversion from start-up to build time did not change what is drawn.
 */

#include "software_qtx2.h"
#include "software_texture16.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <vector>

using namespace code::Math;
using namespace code::Rendering;

namespace {

int gFailures = 0;

void check(bool condition, const char* message)
{
    if (!condition) {
        std::printf("FAIL: %s\n", message);
        ++gFailures;
    }
}

std::vector<unsigned char> readFile(const std::string& path)
{
    std::ifstream file(path.c_str(), std::ios::binary);
    if (!file) return std::vector<unsigned char>();
    return std::vector<unsigned char>(
        std::istreambuf_iterator<char>(file),
        std::istreambuf_iterator<char>());
}

/* QTX1 source: magic, width, height, raw RGBA8888. */
bool parseQtx1(const std::vector<unsigned char>& data,
               QInt32& width,
               QInt32& height,
               const unsigned char*& rgba)
{
    if (data.size() < 8) return false;
    if (data[0] != 'Q' || data[1] != 'T' || data[2] != 'X' || data[3] != '1') {
        return false;
    }
    width = static_cast<QInt32>(data[4]) |
            (static_cast<QInt32>(data[5]) << 8);
    height = static_cast<QInt32>(data[6]) |
             (static_cast<QInt32>(data[7]) << 8);
    if (width <= 0 || height <= 0) return false;
    if (data.size() != 8U + static_cast<size_t>(width * height) * 4U) {
        return false;
    }
    rgba = data.data() + 8;
    return true;
}

QInt32 qtx1Pixel(const unsigned char* rgba, QInt32 index)
{
    const unsigned char* p = rgba + static_cast<size_t>(index) * 4U;
    const QUint32 alpha = p[3];
    if (alpha == 0U) return 0;
    const QUint32 colour = (alpha << 24U) |
        (static_cast<QUint32>(p[0]) << 16U) |
        (static_cast<QUint32>(p[1]) << 8U) |
        static_cast<QUint32>(p[2]);
    return javaIntFromUnsigned(colour);
}

/* Expand a stored 16-bit texel back to 0xAARRGGBB for comparison. */
QInt32 expand(const SoftwareTexture16View& view, QUint16 texel)
{
    return view.expandTexel(texel);
}

int channel(QUint32 value, int shift)
{
    return static_cast<int>((value >> shift) & 0xffU);
}

struct AssetExpectation {
    const char* name;
    SoftwareQtx2Format format;
    bool stores8888;
    /*
     * True when this asset deliberately declares a format coarser than its
     * source alpha, which is only possible while the 8888 block preserves
     * the original. The converter records the same decision in
     * FORMAT_OVERRIDES; the loss it implies is asserted below, not waved
     * through.
     */
    bool declaredLossy;
    /* Visible texels the declared format is expected to drop (0 unless the
       asset is declaredLossy). Pinned so the cost cannot drift silently. */
    QInt32 expectedVanished;
};

/*
 * The formats this project reviews and accepts. Keeping them explicit means a
 * silent change of rule in the converter shows up as a test failure rather
 * than a slightly different picture.
 */
const AssetExpectation kExpected[] = {
    {"forest.qtx", ESoftwareQtx2Rgb1555, false, false, 0},
    {"map.qtx", ESoftwareQtx2Argb4444, false, false, 0},
    {"river.qtx", ESoftwareQtx2Rgb565, false, false, 0},
    {"sky.qtx", ESoftwareQtx2Rgb565, false, false, 0},
    /* The body keeps the keyed 1555 look it always had by explicit choice;
       see FORMAT_OVERRIDES in the converter. */
    {"weapon.qtx", ESoftwareQtx2Rgb1555, true, true, 67},
    {"weapon-fire.qtx", ESoftwareQtx2Argb4444, true, false, 0},
    {"weapon-sight-weapon.qtx", ESoftwareQtx2Argb4444, true, false, 0},
};

void checkAsset(const std::string& qtx2Dir,
                const std::string& qtx1Dir,
                const AssetExpectation& expectation)
{
    const std::string qtx2Path = qtx2Dir + "/" + expectation.name;
    const std::string qtx1Path = qtx1Dir + "/" + expectation.name;

    const std::vector<unsigned char> packed = readFile(qtx2Path);
    const std::vector<unsigned char> source = readFile(qtx1Path);
    if (packed.empty() || source.empty()) {
        std::printf("skip %s: input missing\n", expectation.name);
        return;
    }

    SoftwareQtx2Header header;
    const QUint16* sixteen = 0;
    const QInt32* rgb8888 = 0;
    check(softwareQtx2Inspect(packed.data(),
                              static_cast<QInt32>(packed.size()),
                              4096, 4096, header, &sixteen, &rgb8888),
          "QTX2 file parses with the C++ reader");

    check(header.format == expectation.format,
          "QTX2 format matches the reviewed choice");
    check(softwareQtx2HasRgb8888(header) == expectation.stores8888,
          "QTX2 block kind matches the reviewed choice");
    check(sixteen != 0 || rgb8888 != 0, "QTX2 exposes a pixel block");

    QInt32 width = 0;
    QInt32 height = 0;
    const unsigned char* qtx1 = 0;
    check(parseQtx1(source, width, height, qtx1),
          "QTX1 source parses for comparison");
    check(width == header.width && height == header.height,
          "QTX2 dimensions match the QTX1 source");
    check(static_cast<QInt32>(packed.size()) ==
              softwareQtx2FileSize(header),
          "QTX2 file size matches the header");

    const QInt32 pixelCount = softwareQtx2PixelCount(header);

    /*
     * For a 16-bit-block asset the stored words are the picture. For an
     * 8888-block asset the picture is defined by the declared format applied
     * to the stored source *after* the runtime resize, so reproduce that
     * conversion here on the unresized source: it is the same code path the
     * device runs, minus the scaling.
     */
    std::vector<QUint16> converted;
    SoftwareTexture16View view;
    if (sixteen != 0) {
        view = softwareQtx2TextView(header, sixteen);
    } else {
        converted.resize(static_cast<size_t>(pixelCount));
        if (header.format == ESoftwareQtx2Argb4444) {
            SoftwareTexture16::convert8888To4444(
                rgb8888, converted.data(), pixelCount);
        } else if (header.format == ESoftwareQtx2Rgb1555) {
            SoftwareTexture16::convert8888To1555(
                rgb8888, converted.data(), pixelCount);
        } else {
            SoftwareTexture16::convert8888To565(
                rgb8888, converted.data(), pixelCount);
        }
        /* Scan the result rather than trust the declaration. */
        bool transparent = false;
        bool mixing = false;
        softwareQtx2ScanRgb8888(rgb8888, pixelCount, transparent, mixing);
        view = SoftwareTexture16View(
            converted.data(), pixelCount, header.width, header.height,
            header.format == ESoftwareQtx2Rgb1555,
            header.format != ESoftwareQtx2Rgb565 &&
                (transparent || mixing),
            header.format == ESoftwareQtx2Argb4444);
    }

    check(view.is4444 == (header.format == ESoftwareQtx2Argb4444),
          "the decoded view reports 4444 for ARGB4444 images");
    check(view.is1555 == (header.format == ESoftwareQtx2Rgb1555),
          "the decoded view reports 1555 for RGB1555 images");

    /*
     * Error budget. All three formats truncate rather than round, which is
     * what the engine's own converters do, so the bound is the truncation
     * loss of the coarsest channel:
     *   565 / 1555   5-bit truncation -> 7
     *   4444         4-bit truncation -> 15
     * Anything larger means a real arithmetic difference, not precision.
     */
    int worstColour = 0;
    int worstAlpha = 0;
    QInt32 vanished = 0;
    QInt32 appeared = 0;
    QInt32 index;
    for (index = 0; index < pixelCount; ++index) {
        const QInt32 original = qtx1Pixel(qtx1, index);
        const QInt32 decoded = expand(view, view.pixels[index]);
        const QUint32 a = static_cast<QUint32>(original);
        const QUint32 b = static_cast<QUint32>(decoded);
        /*
         * Colour underneath a fully transparent texel is invisible, and the
         * QTX1 reference zeroes it while a 16-bit format keeps whatever RGB
         * happened to be there. Comparing it would report 255 for identical
         * pictures, so only visible colour counts.
         */
        const bool bothInvisible = ((a >> 24U) == 0U) && ((b >> 24U) == 0U);
        /*
         * A texel that the declared format drops is gone, not recoloured:
         * its colour cannot be compared any more, so only the alpha loss is
         * recorded for it (the exact count is pinned separately).
         */
        const bool eitherInvisible = ((a >> 24U) == 0U) || ((b >> 24U) == 0U);
        const int firstShift = (bothInvisible || eitherInvisible) ? 24 : 0;
        for (int shift = firstShift; shift < 32; shift += 8) {
            const int delta = std::abs(channel(a, shift) - channel(b, shift));
            if (shift == 24) {
                if (delta > worstAlpha) worstAlpha = delta;
            } else if (delta > worstColour) {
                worstColour = delta;
            }
        }
        /* A keyed format must not lose or invent visible texels. Counted
           separately so the exact size of the loss is pinned, not hidden. */
        if (header.format != ESoftwareQtx2Argb4444) {
            const bool visibleA = (a >> 24U) != 0U;
            const bool visibleB = (b >> 24U) != 0U;
            if (visibleA && !visibleB) ++vanished;
            if (!visibleA && visibleB) ++appeared;
        }
    }

    /* Colour is always checked: a format that fits the picture must not
       shift it, whatever alpha policy the asset declares. */
    const int colourBudget = header.format == ESoftwareQtx2Argb4444 ? 15 : 7;
    if (worstColour > colourBudget) {
        std::printf("FAIL: %s colour worst=%d budget=%d\n",
                    expectation.name, worstColour, colourBudget);
        ++gFailures;
    }
    /* Alpha is checked too, except where the asset deliberately declares a
       coarser format; there the exact collapse count is pinned instead. */
    if (!expectation.declaredLossy &&
        worstAlpha > (header.format == ESoftwareQtx2Argb4444 ? 15 : 0)) {
        std::printf("FAIL: %s alpha worst=%d\n",
                    expectation.name, worstAlpha);
        ++gFailures;
    }
    check(appeared == 0,
          "a keyed QTX2 image never invents visible texels");
    check(vanished == expectation.expectedVanished,
          "the number of texels lost to the declared format is the reviewed one");

    if (expectation.declaredLossy) {
        /* The choice is deliberate, so its cost is pinned exactly: a change
           in either direction must show up here. */
        std::printf("  %s: %d texels fall below the 1-bit key, as reviewed\n",
                    expectation.name, static_cast<int>(vanished));
    } else {
        check(vanished == 0, "a keyed QTX2 image keeps every visible texel");
    }

    std::printf("%-26s %4dx%-4d %-8s colour=%-3d alpha=%-3d size=%zu\n",
                expectation.name,
                static_cast<int>(header.width),
                static_cast<int>(header.height),
                header.format == ESoftwareQtx2Rgb565 ? "RGB565" :
                header.format == ESoftwareQtx2Rgb1555 ? "RGB1555" : "ARGB4444",
                worstColour, worstAlpha, packed.size());
}

/* The reader must reject malformed input instead of trusting it. */
void checkRejectsBadInput()
{
    std::vector<unsigned char> good(SoftwareQtx2HeaderSize + 4 * 2, 0);
    good[0] = 'Q'; good[1] = 'T'; good[2] = 'X'; good[3] = '2';
    good[4] = ESoftwareQtx2Rgb565;
    good[5] = SoftwareQtx2FlagHas16;
    good[6] = 2; good[7] = 0;
    good[8] = 2; good[9] = 0;

    SoftwareQtx2Header header;
    const QUint16* sixteen = 0;
    const QInt32* rgb = 0;
    check(softwareQtx2Inspect(good.data(), static_cast<QInt32>(good.size()),
                              256, 256, header, &sixteen, &rgb),
          "a minimal well-formed QTX2 image parses");

    std::vector<unsigned char> bad = good;
    bad[0] = 'X';
    check(!softwareQtx2Inspect(bad.data(), static_cast<QInt32>(bad.size()),
                              256, 256, header, &sixteen, &rgb),
          "a wrong magic is rejected");

    bad = good;
    bad[4] = 9; /* not a format */
    check(!softwareQtx2Inspect(bad.data(), static_cast<QInt32>(bad.size()),
                              256, 256, header, &sixteen, &rgb),
          "an unknown format id is rejected");

    bad = good;
    bad[5] = 0; /* no pixel block at all */
    check(!softwareQtx2Inspect(bad.data(), static_cast<QInt32>(bad.size()),
                              256, 256, header, &sixteen, &rgb),
          "an image with no pixel block is rejected");

    bad = good;
    bad[5] = SoftwareQtx2FlagHas16 | SoftwareQtx2FlagHasRgb8888;
    check(!softwareQtx2Inspect(bad.data(), static_cast<QInt32>(bad.size()),
                              256, 256, header, &sixteen, &rgb),
          "an image claiming both block kinds is rejected");

    check(!softwareQtx2Inspect(good.data(),
                               static_cast<QInt32>(good.size()) - 1,
                               256, 256, header, &sixteen, &rgb),
          "a truncated file is rejected");

    check(!softwareQtx2Inspect(good.data(), static_cast<QInt32>(good.size()),
                               1, 1, header, &sixteen, &rgb),
          "dimensions above the caller's limit are rejected");

    bad = good;
    bad[10] = 1; /* reserved byte must be zero */
    check(!softwareQtx2Inspect(bad.data(), static_cast<QInt32>(bad.size()),
                               256, 256, header, &sixteen, &rgb),
          "a non-zero reserved byte is rejected");
}

/* Writer and reader must agree; the asset tool relies on the same layout. */
void checkWriterRoundTrip()
{
    const QInt32 width = 4;
    const QInt32 height = 3;
    const QInt32 pixelCount = width * height;

    for (int format = 0; format < 3; ++format) {
        for (int block = 0; block < 2; ++block) {
            SoftwareQtx2Header header;
            header.format = static_cast<SoftwareQtx2Format>(format);
            header.flags = block == 0 ? SoftwareQtx2FlagHas16
                                      : SoftwareQtx2FlagHasRgb8888;
            header.width = width;
            header.height = height;

            std::vector<QUint16> sixteen(pixelCount);
            std::vector<QInt32> rgb(pixelCount);
            QInt32 index;
            for (index = 0; index < pixelCount; ++index) {
                sixteen[index] = static_cast<QUint16>(
                    (0xfU << 12U) | ((index * 3) & 0xf00) | (index & 0x0f));
                rgb[index] = javaIntFromUnsigned(
                    (0xff000000U) | (static_cast<QUint32>(index) * 0x010203U));
            }

            std::vector<unsigned char> buffer(
                static_cast<size_t>(softwareQtx2FileSize(header)));
            QInt32 written = 0;
            check(softwareQtx2Write(header,
                                    block == 0 ? sixteen.data() : 0,
                                    block == 1 ? rgb.data() : 0,
                                    buffer.data(),
                                    static_cast<QInt32>(buffer.size()),
                                    written),
                  "the writer produces a file");
            check(written == static_cast<QInt32>(buffer.size()),
                  "the writer reports the full size");

            SoftwareQtx2Header parsed;
            const QUint16* readSixteen = 0;
            const QInt32* readRgb = 0;
            check(softwareQtx2Inspect(buffer.data(),
                                      static_cast<QInt32>(buffer.size()),
                                      256, 256, parsed,
                                      &readSixteen, &readRgb),
                  "the written file parses back");
            check(parsed.format == header.format,
                  "the written format survives a round trip");
            if (block == 0) {
                check(readSixteen != 0 && readRgb == 0,
                      "a 16-bit-only file exposes only 16-bit pixels");
                bool same = true;
                for (index = 0; index < pixelCount; ++index) {
                    if (readSixteen[index] != sixteen[index]) same = false;
                }
                check(same, "16-bit pixels survive a round trip");
            } else {
                check(readRgb != 0 && readSixteen == 0,
                      "an 8888-only file exposes only 8888 pixels");
                bool same = true;
                for (index = 0; index < pixelCount; ++index) {
                    if (readRgb[index] != rgb[index]) same = false;
                }
                check(same, "8888 pixels survive a round trip");
            }
        }
    }
}

/* Format selection must follow the alpha content, in the documented order. */
void checkFormatSelection()
{
    QInt32 opaque[4] = {
        javaIntFromUnsigned(0xff102030U), javaIntFromUnsigned(0xffffffffU),
        javaIntFromUnsigned(0xff00ff00U), javaIntFromUnsigned(0xff123456U)
    };
    check(softwareQtx2FormatForRgb8888(opaque, 4) == ESoftwareQtx2Rgb565,
          "an opaque image is offered RGB565");

    QInt32 keyed[4] = {
        javaIntFromUnsigned(0xff102030U), 0,
        javaIntFromUnsigned(0xff00ff00U), javaIntFromUnsigned(0xff123456U)
    };
    check(softwareQtx2FormatForRgb8888(keyed, 4) == ESoftwareQtx2Rgb1555,
          "a keyed image is offered RGB1555");

    QInt32 soft[4] = {
        javaIntFromUnsigned(0xff102030U), javaIntFromUnsigned(0x80000000U),
        javaIntFromUnsigned(0xff00ff00U), javaIntFromUnsigned(0xff123456U)
    };
    check(softwareQtx2FormatForRgb8888(soft, 4) == ESoftwareQtx2Argb4444,
          "partial alpha forces ARGB4444 even next to opaque texels");

    bool transparent = true;
    bool mixing = false;
    softwareQtx2ScanRgb8888(keyed, 4, transparent, mixing);
    check(transparent && !mixing,
          "a keyed scan reports transparent texels but no alpha mixing");
    softwareQtx2ScanRgb8888(soft, 4, transparent, mixing);
    check(transparent && mixing,
          "a soft scan reports both transparent texels and alpha mixing");
}

} // namespace

int main(int argc, char** argv)
{
    if (argc < 3) {
        std::printf("usage: %s <qtx2-dir> <qtx1-dir>\n", argv[0]);
        return 2;
    }
    const std::string qtx2Dir = argv[1];
    const std::string qtx1Dir = argv[2];

    checkWriterRoundTrip();
    checkFormatSelection();
    checkRejectsBadInput();

    for (size_t index = 0; index < sizeof(kExpected) / sizeof(kExpected[0]);
         ++index) {
        checkAsset(qtx2Dir, qtx1Dir, kExpected[index]);
    }

    if (gFailures != 0) {
        std::printf("%d QTX2 conformance failure(s)\n", gFailures);
        return 1;
    }
    std::printf("QTX2 conformance tests passed.\n");
    return 0;
}
