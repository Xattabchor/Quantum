/*
 * Host test for the optimized 16-bit Symbian path:
 *  - RGB565/RGB1555 conversion round-trip bounds
 *  - QCT decode to 16-bit vs the 32-bit decoder (within quantization)
 *  - fast affine/perspective mode 0/9 vs reference 32-bit kernels
 *  - RenderModePolicy fast presets
 *  - micro-benchmark: reference 32-bit vs fast 16-bit (informational)
 *
 * Usage: fast_rasterizer_test [assets-dir]
 */
#include "render_mode_policy.h"
#include "software_affine_rasterizer.h"
#include "software_fast_rasterizer.h"
#include "software_perspective_rasterizer.h"
#include "software_qct_texture_decoder.h"
#include "software_texture16.h"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

using namespace code::Rendering;
using code::Math::QInt32;
using code::Math::QUint16;
using code::Math::QUint32;

namespace {

int gFailures = 0;

void check(bool condition, const char* message)
{
    if (!condition) {
        std::printf("FAIL: %s\n", message);
        ++gFailures;
    }
}

QInt32 channelDiff(QInt32 a, QInt32 b)
{
    const QUint32 ab = static_cast<QUint32>(a);
    const QUint32 bb = static_cast<QUint32>(b);
    QInt32 worst = 0;
    int shift;
    for (shift = 0; shift < 24; shift += 8) {
        QInt32 d = static_cast<QInt32>((ab >> shift) & 0xffU) -
                   static_cast<QInt32>((bb >> shift) & 0xffU);
        if (d < 0) d = -d;
        if (d > worst) worst = d;
    }
    return worst;
}

struct Framebuffer {
    std::vector<QInt32> pixels;
    SoftwareRenderSurfaceView view;
    Framebuffer(QInt32 width, QInt32 height)
        : pixels(static_cast<size_t>(width) * height, 0),
          view(&pixels[0], width * height, width, height, width)
    {
    }
    void fill(QInt32 color)
    {
        for (size_t i = 0; i < pixels.size(); ++i) pixels[i] = color;
    }
};

QInt32 compareFrames(const Framebuffer& a, const Framebuffer& b,
                     QInt32 background)
{
    QInt32 worst = 0;
    QInt32 covered = 0;
    for (size_t i = 0; i < a.pixels.size(); ++i) {
        if (a.pixels[i] == background && b.pixels[i] == background) continue;
        ++covered;
        const QInt32 d = channelDiff(a.pixels[i], b.pixels[i]);
        if (d > worst) worst = d;
    }
    (void)covered;
    return worst;
}

void testConversions()
{
    /* Round-trip: 565 error <= 8 per channel, 1555 opaque error <= 8. */
    QInt32 worst565 = 0;
    QInt32 worst1555 = 0;
    QInt32 r, g, b;
    for (r = 0; r < 256; r += 17) {
        for (g = 0; g < 256; g += 17) {
            for (b = 0; b < 256; b += 17) {
                const QInt32 pixel = static_cast<QInt32>(
                    0xff000000U | (static_cast<QUint32>(r) << 16) |
                    (static_cast<QUint32>(g) << 8) |
                    static_cast<QUint32>(b));
                const QInt32 back565 =
                    rgb8888From565(rgb565From8888(pixel));
                const QInt32 d565 = channelDiff(pixel, back565);
                if (d565 > worst565) worst565 = d565;
                const QInt32 back1555 =
                    rgb8888From1555(rgb1555From8888(pixel));
                const QInt32 d1555 = channelDiff(pixel, back1555);
                if (d1555 > worst1555) worst1555 = d1555;
            }
        }
    }
    check(worst565 <= 8, "RGB565 round-trip error exceeds 8");
    check(worst1555 <= 8, "RGB1555 round-trip error exceeds 8");

    /* Transparent 1555 texel decodes to zero key. */
    check(rgb8888From1555(0) == 0, "1555 transparent texel must be zero");
    check(rgb1555From8888(0) == 0, "transparent 8888 must map to 1555 zero");
    std::printf("conversions: worst565=%d worst1555=%d\n", worst565,
                worst1555);
}

std::vector<unsigned char> readFile(const std::string& path)
{
    std::vector<unsigned char> data;
    FILE* file = std::fopen(path.c_str(), "rb");
    if (file == 0) return data;
    unsigned char buffer[8192];
    size_t count;
    while ((count = std::fread(buffer, 1, sizeof(buffer), file)) > 0) {
        data.insert(data.end(), buffer, buffer + count);
    }
    std::fclose(file);
    return data;
}

std::vector<unsigned char> makeSyntheticQct()
{
    /* Minimal 8x8 QCT: headerSize 7, 4-color palette, 16 blocks. */
    std::vector<unsigned char> qct;
    qct.push_back(0);
    qct.push_back(0);
    qct.push_back(0);
    qct.push_back(0);
    qct.push_back(7); /* headerSize */
    qct.push_back(0); /* header byte 5 */
    qct.push_back(0);
    qct.push_back(8); /* width */
    qct.push_back(0);
    qct.push_back(8); /* height */
    qct.push_back(1); /* paletteShift-1 -> 4 colors */
    qct.push_back(0); /* alphaType */
    /* Palette: red, green, blue, white. */
    const unsigned char palette[12] = { 255, 0, 0, 0, 255, 0,
                                        0, 0, 255, 255, 255, 255 };
    qct.insert(qct.end(), palette, palette + 12);
    /* 16 blocks: color0=0, color3=3, selectors all index 1. */
    int i;
    for (i = 0; i < 16; ++i) {
        qct.push_back(0);
        qct.push_back(3);
        qct.push_back(0x55);
    }
    return qct;
}

void testQctDecode(const std::string& assetsDir)
{
    (void)assetsDir;
    const std::vector<unsigned char> data = makeSyntheticQct();
    const QInt32 dataLength = static_cast<QInt32>(data.size());

    SoftwareQctTextureInfo inspectInfo;
    check(SoftwareQctTextureDecoder::inspect(data.data(), dataLength,
                                             inspectInfo),
          "synthetic QCT inspect failed");
    const QInt32 capacity = inspectInfo.sourcePixelCount;

    /* 32-bit reference decode. */
    std::vector<QInt32> pixels32(capacity, 0);
    SoftwareQctTextureInfo info32;
    bool ok32 = SoftwareQctTextureDecoder::decode(
        data.data(), dataLength, 2, pixels32.data(), capacity, info32);
    const QInt32 width32 = info32.width;
    const QInt32 height32 = info32.height;
    check(ok32 && width32 == 8 && height32 == 8,
          "32-bit QCT decode failed");

    /* 16-bit decode (565 and 1555) with the same pixelsQ. */
    std::vector<QUint16> pixels565(capacity, 0);
    QInt32 width565 = 0;
    QInt32 height565 = 0;
    bool transparent565 = false;
    bool ok565 = SoftwareTexture16::decodeQctTo16(
        data.data(), dataLength, 2, false, pixels565.data(), capacity,
        width565, height565, transparent565);
    check(ok565 && width565 == width32 && height565 == height32,
          "16-bit 565 QCT decode failed or size mismatch");

    std::vector<QUint16> pixels1555(capacity, 0);
    QInt32 width1555 = 0;
    QInt32 height1555 = 0;
    bool transparent1555 = false;
    bool ok1555 = SoftwareTexture16::decodeQctTo16(
        data.data(), dataLength, 2, true, pixels1555.data(), capacity,
        width1555, height1555, transparent1555);
    check(ok1555 && width1555 == width32 && height1555 == height32,
          "16-bit 1555 QCT decode failed or size mismatch");

    if (ok32 && ok565) {
        QInt32 worst = 0;
        const QInt32 count = width32 * height32;
        QInt32 i;
        for (i = 0; i < count; ++i) {
            const QInt32 d = channelDiff(pixels32[i],
                                         rgb8888From565(pixels565[i]));
            if (d > worst) worst = d;
        }
        check(worst <= 8, "565 QCT decode differs from 32-bit beyond quantization");
        std::printf("QCT 565 vs 32-bit: worst=%d (%dx%d)\n", worst, width32,
                    height32);
    }
    if (ok32 && ok1555) {
        QInt32 worst = 0;
        const QInt32 count = width32 * height32;
        QInt32 i;
        for (i = 0; i < count; ++i) {
            const QInt32 d = channelDiff(pixels32[i],
                                         rgb8888From1555(pixels1555[i]));
            if (d > worst) worst = d;
        }
        check(worst <= 8, "1555 QCT decode differs from 32-bit beyond quantization");
        std::printf("QCT 1555 vs 32-bit: worst=%d\n", worst);
    }

    /* QTX1 asset: raw RGBA -> 16-bit conversion round-trip. */
    const std::string qtxPath = assetsDir + "/qtx/map.qtx";
    const std::vector<unsigned char> qtx = readFile(qtxPath);
    if (qtx.size() < 8 || qtx[0] != 'Q' || qtx[1] != 'T' || qtx[2] != 'X' ||
        qtx[3] != '1') {
        std::printf("skip QTX1 test: %s not found\n", qtxPath.c_str());
        return;
    }
    const QInt32 qtxWidth = static_cast<QInt32>(qtx[4]) |
                            (static_cast<QInt32>(qtx[5]) << 8);
    const QInt32 qtxHeight = static_cast<QInt32>(qtx[6]) |
                             (static_cast<QInt32>(qtx[7]) << 8);
    const QInt32 qtxCount = qtxWidth * qtxHeight;
    if (qtxWidth <= 0 || qtxHeight <= 0 ||
        qtx.size() != static_cast<size_t>(8 + qtxCount * 4)) {
        std::printf("skip QTX1 test: bad size\n");
        return;
    }
    std::vector<QInt32> qtxPixels(qtxCount, 0);
    QInt32 i;
    for (i = 0; i < qtxCount; ++i) {
        const unsigned char* rgba = &qtx[8 + i * 4];
        const QUint32 color = (static_cast<QUint32>(rgba[3]) << 24U) |
                              (static_cast<QUint32>(rgba[0]) << 16U) |
                              (static_cast<QUint32>(rgba[1]) << 8U) |
                              static_cast<QUint32>(rgba[2]);
        qtxPixels[i] = code::Math::javaIntFromUnsigned(color);
    }
    std::vector<QUint16> qtx565(qtxCount, 0);
    SoftwareTexture16::convert8888To565(qtxPixels.data(), qtx565.data(),
                                        qtxCount);
    QInt32 worst = 0;
    for (i = 0; i < qtxCount; ++i) {
        const QInt32 d =
            channelDiff(qtxPixels[i], rgb8888From565(qtx565[i]));
        if (d > worst) worst = d;
    }
    check(worst <= 8, "QTX1 565 conversion differs beyond quantization");
    std::printf("QTX1 565 conversion: worst=%d (%dx%d)\n", worst, qtxWidth,
                qtxHeight);
}

void makeGradientTexture(std::vector<QInt32>& pixels, QInt32 width,
                         QInt32 height)
{
    pixels.assign(static_cast<size_t>(width) * height, 0);
    QInt32 y, x;
    for (y = 0; y < height; ++y) {
        for (x = 0; x < width; ++x) {
            const QInt32 r = (x * 255) / (width - 1);
            const QInt32 g = (y * 255) / (height - 1);
            const QInt32 b = ((x + y) * 255) / (width + height - 2);
            pixels[static_cast<size_t>(y) * width + x] = static_cast<QInt32>(
                0xff000000U | (static_cast<QUint32>(r) << 16) |
                (static_cast<QUint32>(g) << 8) | static_cast<QUint32>(b));
        }
    }
}

void testFastKernels()
{
    const QInt32 kWidth = 176;
    const QInt32 kHeight = 144;
    const QInt32 kBackground = static_cast<QInt32>(0xff101010U);

    std::vector<QInt32> texture32;
    makeGradientTexture(texture32, 64, 64);
    const SoftwareTextureView textureView32(
        texture32.data(), static_cast<QInt32>(texture32.size()), 64, 64,
        false, false, false);

    std::vector<QUint16> texture565(64 * 64, 0);
    SoftwareTexture16::convert8888To565(texture32.data(), texture565.data(),
                                        64 * 64);
    const SoftwareTexture16View textureView565(texture565.data(), 64 * 64, 64,
                                               64, false, false);

    std::vector<QUint16> texture1555(64 * 64, 0);
    SoftwareTexture16::convert8888To1555(texture32.data(), texture1555.data(),
                                         64 * 64);
    const SoftwareTexture16View textureView1555(texture1555.data(), 64 * 64,
                                                64, 64, true, false);

    const AffineTriangleVertex v0(20, 20, 0, 0, -1000);
    const AffineTriangleVertex v1(150, 30, 64, 0, -2000);
    const AffineTriangleVertex v2(40, 120, 0, 64, -1500);

    /* Affine mode 0: fast 565 vs reference 32-bit. */
    {
        Framebuffer ref(kWidth, kHeight);
        Framebuffer fast(kWidth, kHeight);
        ref.fill(kBackground);
        fast.fill(kBackground);
        check(SoftwareAffineRasterizer::drawMode0(ref.view, textureView32, v0,
                                                  v1, v2),
              "reference affine mode0 failed");
        check(SoftwareFastRasterizer::drawAffineMode0(fast.view, textureView565,
                                                      v0, v1, v2),
              "fast affine mode0 failed");
        const QInt32 worst = compareFrames(ref, fast, kBackground);
        check(worst <= 8, "fast affine mode0 differs beyond quantization");
        std::printf("affine mode0: worst=%d\n", worst);
    }

    /* Affine mode 9 with vertex shade. */
    {
        Framebuffer ref(kWidth, kHeight);
        Framebuffer fast(kWidth, kHeight);
        ref.fill(kBackground);
        fast.fill(kBackground);
        check(SoftwareAffineRasterizer::drawMode9(ref.view, textureView32, v0,
                                                  v1, v2, 128, 200, 255),
              "reference affine mode9 failed");
        check(SoftwareFastRasterizer::drawAffineMode9(fast.view, textureView565,
                                                      v0, v1, v2, 128, 200,
                                                      255),
              "fast affine mode9 failed");
        const QInt32 worst = compareFrames(ref, fast, kBackground);
        check(worst <= 12, "fast affine mode9 differs beyond tolerance");
        std::printf("affine mode9: worst=%d\n", worst);
    }

    /* Affine mode 0 with 1555 texture. */
    {
        Framebuffer ref(kWidth, kHeight);
        Framebuffer fast(kWidth, kHeight);
        ref.fill(kBackground);
        fast.fill(kBackground);
        check(SoftwareAffineRasterizer::drawMode0(ref.view, textureView32, v0,
                                                  v1, v2),
              "reference affine mode0 (1555) failed");
        check(SoftwareFastRasterizer::drawAffineMode0(fast.view,
                                                      textureView1555, v0, v1,
                                                      v2),
              "fast affine mode0 1555 failed");
        const QInt32 worst = compareFrames(ref, fast, kBackground);
        check(worst <= 8, "fast affine mode0 1555 differs beyond quantization");
        std::printf("affine mode0 1555: worst=%d\n", worst);
    }

    /* Perspective mode 0, q=12. */
    {
        Framebuffer ref(kWidth, kHeight);
        Framebuffer fast(kWidth, kHeight);
        ref.fill(kBackground);
        fast.fill(kBackground);
        check(SoftwarePerspectiveRasterizer::drawMode0Copy(
                  ref.view, textureView32, v0, v1, v2, 12, 150),
              "reference perspective mode0 failed");
        check(SoftwareFastRasterizer::drawPerspectiveMode0(
                  fast.view, textureView565, v0, v1, v2, 12, 150),
              "fast perspective mode0 failed");
        const QInt32 worst = compareFrames(ref, fast, kBackground);
        check(worst <= 16, "fast perspective mode0 differs beyond tolerance");
        std::printf("perspective mode0 q=12: worst=%d\n", worst);
    }

    /* Perspective mode 9, q=24. */
    {
        Framebuffer ref(kWidth, kHeight);
        Framebuffer fast(kWidth, kHeight);
        ref.fill(kBackground);
        fast.fill(kBackground);
        check(SoftwarePerspectiveRasterizer::drawMode9Shade(
                  ref.view, textureView32, v0, v1, v2, 128, 200, 255, 24,
                  150),
              "reference perspective mode9 failed");
        check(SoftwareFastRasterizer::drawPerspectiveMode9(
                  fast.view, textureView565, v0, v1, v2, 128, 200, 255, 24,
                  150),
              "fast perspective mode9 failed");
        const QInt32 worst = compareFrames(ref, fast, kBackground);
        check(worst <= 16, "fast perspective mode9 differs beyond tolerance");
        std::printf("perspective mode9 q=24: worst=%d\n", worst);
    }

    /* Off-screen / degenerate triangles are successful no-ops. */
    {
        Framebuffer fast(kWidth, kHeight);
        fast.fill(kBackground);
        const AffineTriangleVertex off0(-500, -500, 0, 0, -1000);
        const AffineTriangleVertex off1(-400, -500, 64, 0, -1000);
        const AffineTriangleVertex off2(-500, -400, 0, 64, -1000);
        check(SoftwareFastRasterizer::drawAffineMode0(fast.view, textureView565,
                                                      off0, off1, off2),
              "fast off-screen triangle must succeed");
        check(SoftwareFastRasterizer::drawAffineMode0(fast.view, textureView565,
                                                      v0, v0, v0),
              "fast degenerate triangle must succeed");
        check(compareFrames(fast, fast, kBackground) == 0,
              "off-screen triangle must not draw");
    }
}

void testPresets()
{
    RenderModeSettings settings;
    check(RenderModePolicy::fastPresetOf(settings) == EFastQualityOff,
          "default preset must be Off");

    RenderModePolicy::applyFastPreset(settings, EFastQualityFast);
    check(settings.perspectiveQuality == 0 && settings.fogQuality == 1 &&
              settings.use16BitTextures && settings.useFastRasterizer &&
              !settings.mipMapping,
          "Fast preset values wrong");
    check(RenderModePolicy::fastPresetOf(settings) == EFastQualityFast,
          "Fast preset round-trip failed");

    RenderModePolicy::applyFastPreset(settings, EFastQualityUltraFast);
    check(settings.perspectiveQuality == 0 && settings.fogQuality == 0 &&
              settings.textureQuality == 0 &&
              settings.use16BitTextures && settings.useFastRasterizer,
          "UltraFast preset values wrong");
    check(RenderModePolicy::fastPresetOf(settings) == EFastQualityUltraFast,
          "UltraFast preset round-trip failed");

    RenderModePolicy::applyFastPreset(settings, EFastQualityOff);
    check(settings.perspectiveQuality == 2 && settings.fogQuality == 2 &&
              !settings.use16BitTextures && !settings.useFastRasterizer &&
              settings.mipMapping,
          "Off preset must restore full quality");
    check(RenderModePolicy::fastPresetOf(settings) == EFastQualityOff,
          "Off preset round-trip failed");
}

double benchmarkAffine(const SoftwareRenderSurfaceView& destination,
                       const SoftwareTextureView& texture32,
                       const SoftwareTexture16View& texture16, bool fast,
                       int iterations)
{
    const AffineTriangleVertex v0(5, 5, 0, 0, -1000);
    const AffineTriangleVertex v1(170, 15, 64, 0, -2000);
    const AffineTriangleVertex v2(15, 138, 0, 64, -1500);
    const auto start = std::chrono::steady_clock::now();
    int i;
    for (i = 0; i < iterations; ++i) {
        if (fast) {
            SoftwareFastRasterizer::drawAffineMode0(destination, texture16, v0,
                                                    v1, v2);
        } else {
            SoftwareAffineRasterizer::drawMode0(destination, texture32, v0, v1,
                                                v2);
        }
    }
    const auto end = std::chrono::steady_clock::now();
    return std::chrono::duration<double, std::milli>(end - start).count() /
           iterations;
}

void benchmark()
{
    const QInt32 kWidth = 176;
    const QInt32 kHeight = 144;
    std::vector<QInt32> texture32;
    makeGradientTexture(texture32, 64, 64);
    const SoftwareTextureView textureView32(
        texture32.data(), static_cast<QInt32>(texture32.size()), 64, 64,
        false, false, false);
    std::vector<QUint16> texture565(64 * 64, 0);
    SoftwareTexture16::convert8888To565(texture32.data(), texture565.data(),
                                        64 * 64);
    const SoftwareTexture16View textureView565(texture565.data(), 64 * 64, 64,
                                               64, false, false);

    Framebuffer frame(kWidth, kHeight);
    const int iterations = 2000;
    /* Warm-up. */
    benchmarkAffine(frame.view, textureView32, textureView565, false, 100);
    benchmarkAffine(frame.view, textureView32, textureView565, true, 100);
    const double refMs =
        benchmarkAffine(frame.view, textureView32, textureView565, false,
                        iterations);
    const double fastMs =
        benchmarkAffine(frame.view, textureView32, textureView565, true,
                        iterations);
    std::printf("benchmark affine mode0: reference=%.3f ms/tri fast16=%.3f "
                "ms/tri speedup=%.2fx\n",
                refMs, fastMs, refMs / fastMs);
}

} // namespace

int main(int argc, char** argv)
{
    const std::string assetsDir =
        argc > 1 ? argv[1]
                 : "symbian9/level41_device_test/assets/sym9-level1";
    testConversions();
    testQctDecode(assetsDir);
    testFastKernels();
    testPresets();
    benchmark();
    if (gFailures == 0) {
        std::printf("Fast rasterizer tests passed.\n");
        return 0;
    }
    std::printf("Fast rasterizer tests FAILED: %d failure(s).\n", gFailures);
    return 1;
}
