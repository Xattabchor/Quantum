// Reference-vs-fast-perspective comparison for the Level 41 walk.
//
// The fast 16-bit perspective path is the one that runs on the E65 when a
// texture is 16-bit. Its output must match this repository's 32-bit reference
// path within the quantisation the format itself introduces (5 or 4 bits per
// channel). This harness renders the same triangles through both and reports
// the worst per-channel difference, so a change to either kernel that pushes
// them apart fails here rather than on the phone.
//
// Build and run through symbian9/host_tests/run_perspective_parity_test.sh.

#include "software_fast_rasterizer.h"
#include "software_perspective_rasterizer.h"
#include "software_texture16.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

namespace {

using code::Math::QInt32;
using code::Math::QUint16;
using code::Math::QUint32;
using code::Rendering::AffineTriangleVertex;
using code::Rendering::SoftwareFastRasterizer;
using code::Rendering::SoftwarePerspectiveRasterizer;
using code::Rendering::SoftwareRenderSurfaceView;
using code::Rendering::SoftwareTexture16View;
using code::Rendering::SoftwareTextureView;

const QInt32 kWidth = 96;
const QInt32 kHeight = 80;
const QInt32 kBackground = 0x00102030;

struct Surface {
    std::vector<QInt32> pixels;
    SoftwareRenderSurfaceView view;

    Surface()
        : pixels(static_cast<std::size_t>(kWidth) * kHeight, kBackground),
          view(pixels.data(), kWidth * kHeight, kWidth, kHeight, kWidth)
    {
    }

    void reset()
    {
        for (std::size_t index = 0; index < pixels.size(); ++index) {
            pixels[index] = kBackground;
        }
    }
};

void require(bool aCondition, const char* aMessage)
{
    if (!aCondition) {
        std::fprintf(stderr, "FAIL: %s\n", aMessage);
        std::exit(1);
    }
}

QInt32 channel(QInt32 aColour, int aShift)
{
    return (aColour >> aShift) & 0xff;
}

/* Worst absolute per-channel difference over all pixels. */
QInt32 compareFrames(const Surface& aReference, const Surface& aFast)
{
    QInt32 worst = 0;
    for (std::size_t index = 0; index < aReference.pixels.size(); ++index) {
        const QInt32 left = aReference.pixels[index];
        const QInt32 right = aFast.pixels[index];
        if (left == right) continue;
        static const int kShifts[3] = {16, 8, 0};
        for (int channelIndex = 0; channelIndex < 3; ++channelIndex) {
            const QInt32 delta =
                channel(left, kShifts[channelIndex]) -
                channel(right, kShifts[channelIndex]);
            const QInt32 magnitude = delta < 0 ? -delta : delta;
            if (magnitude > worst) worst = magnitude;
        }
    }
    return worst;
}

/*
 * One texture, two views of it: 32-bit 8888 for the reference kernel and
 * 16-bit for the fast kernel, both carrying the same colours so the only
 * difference the comparison sees is the format, not the artwork.
 */
struct Textures {
    std::vector<QInt32> wide;
    std::vector<QUint16> narrow;
    SoftwareTextureView reference;
    SoftwareTexture16View fast565;

    Textures()
        : wide(64 * 64), narrow(64 * 64), reference(), fast565()
    {
        for (QInt32 y = 0; y < 64; ++y) {
            for (QInt32 x = 0; x < 64; ++x) {
                const QInt32 red = (x * 4) & 0xff;
                const QInt32 green = (y * 4) & 0xff;
                const QInt32 blue = ((x + y) * 2) & 0xff;
                const std::size_t index =
                    static_cast<std::size_t>(y * 64 + x);
                wide[index] = (red << 16) | (green << 8) | blue;
                /* Truncate to 565 exactly as the asset pipeline does. */
                narrow[index] = static_cast<QUint16>(
                    (((red >> 3) & 0x1f) << 11) |
                    (((green >> 2) & 0x3f) << 5) | ((blue >> 3) & 0x1f));
            }
        }
        reference = SoftwareTextureView(wide.data(),
                                        static_cast<QInt32>(wide.size()), 64,
                                        64, false, false, false);
        fast565 = SoftwareTexture16View(narrow.data(),
                                        static_cast<QInt32>(narrow.size()), 64,
                                        64, false, false, false);
    }
};

struct Triangle {
    AffineTriangleVertex first;
    AffineTriangleVertex second;
    AffineTriangleVertex third;
};

void makeTriangles(std::vector<Triangle>& aTriangles)
{
    aTriangles.clear();
    {
        Triangle triangle;
        triangle.first = AffineTriangleVertex(10, 10, 0, 0, -1000);
        triangle.second = AffineTriangleVertex(85, 14, 63, 0, -2000);
        triangle.third = AffineTriangleVertex(20, 78, 0, 63, -1500);
        aTriangles.push_back(triangle);
    }
    {
        Triangle triangle;
        triangle.first = AffineTriangleVertex(5, 5, 8, 8, -1200);
        triangle.second = AffineTriangleVertex(90, 20, 48, 4, -1800);
        triangle.third = AffineTriangleVertex(30, 70, 16, 60, -1400);
        aTriangles.push_back(triangle);
    }
}

void checkMode(const char* aName, const Textures& aTextures,
               const std::vector<Triangle>& aTriangles, QInt32 aStep,
               QInt32 aTolerance)
{
    Surface reference;
    Surface fast;
    for (std::size_t index = 0; index < aTriangles.size(); ++index) {
        reference.reset();
        fast.reset();
        require(SoftwarePerspectiveRasterizer::drawMode0Copy(
                    reference.view, aTextures.reference,
                    aTriangles[index].first, aTriangles[index].second,
                    aTriangles[index].third, aStep, 150),
                "reference perspective mode0 failed");
        require(SoftwareFastRasterizer::drawPerspectiveMode0(
                    fast.view, aTextures.fast565, aTriangles[index].first,
                    aTriangles[index].second, aTriangles[index].third, aStep,
                    150),
                "fast perspective mode0 failed");
        const QInt32 worst = compareFrames(reference, fast);
        std::printf("%-22s step=%d worst=%d\n", aName, aStep, worst);
        require(worst <= aTolerance,
                "fast and reference perspective disagree beyond quantisation");
    }
}

void checkShaded(const Textures& aTextures,
                 const std::vector<Triangle>& aTriangles, QInt32 aStep)
{
    Surface reference;
    Surface fast;
    for (std::size_t index = 0; index < aTriangles.size(); ++index) {
        reference.reset();
        fast.reset();
        require(SoftwarePerspectiveRasterizer::drawMode9Shade(
                    reference.view, aTextures.reference,
                    aTriangles[index].first, aTriangles[index].second,
                    aTriangles[index].third, 200, 150, 255, aStep, 150),
                "reference perspective mode9 failed");
        require(SoftwareFastRasterizer::drawPerspectiveMode9(
                    fast.view, aTextures.fast565, aTriangles[index].first,
                    aTriangles[index].second, aTriangles[index].third, 200, 150,
                    255, aStep, 150),
                "fast perspective mode9 failed");
        const QInt32 worst = compareFrames(reference, fast);
        std::printf("%-22s step=%d worst=%d\n", "mode9", aStep, worst);
        /* Shading multiplies the 565 truncation by the shade factor, so the
           tolerance is the 16-bit budget the host tests already use. */
        require(worst <= 20,
                "fast and reference mode9 disagree beyond quantisation");
    }
}

} // namespace

int main()
{
    Textures textures;
    std::vector<Triangle> triangles;
    makeTriangles(triangles);

    /* Both the reference divisor (24) and the shipped device setting (96). */
    checkMode("mode0 step24", textures, triangles, 24, 16);
    checkMode("mode0 step96", textures, triangles, 96, 16);
    checkMode("mode0 step9", textures, triangles, 9, 16);
    checkShaded(textures, triangles, 24);
    checkShaded(textures, triangles, 96);

    std::printf("perspective_parity_test: fast path within quantisation\n");
    return 0;
}
