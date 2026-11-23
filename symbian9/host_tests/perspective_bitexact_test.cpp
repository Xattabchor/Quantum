// Bit-exactness harness for the Level 41 perspective rasterizer.
//
// The fast-rasterizer test compares the 16-bit kernels against the 32-bit
// ones with a tolerance, which cannot catch a one-pixel change in the
// reference path. This harness pins the reference path itself: it renders a
// fixed set of triangles through every perspective draw mode the walk uses and
// prints a hash of each resulting framebuffer. The hashes are compared against
// a stored golden file, so any change in output -- including the loop-hoisting
// refactors in drawPerspectiveEffectSpan -- fails loudly.
//
// It deliberately uses only Symbian-free headers so it builds on the host.
//
// Build and run through symbian9/host_tests/run_perspective_bitexact_test.sh.

#include "software_perspective_rasterizer.h"
#include "software_texture16.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

using code::Math::QInt32;
using code::Math::QUint16;
using code::Math::QUint32;
using code::Rendering::AffineTriangleVertex;
using code::Rendering::SoftwarePerspectiveRasterizer;
using code::Rendering::SoftwareRenderSurfaceView;
using code::Rendering::SoftwareTextureView;

const QInt32 kWidth = 96;
const QInt32 kHeight = 80;
const QInt32 kBackground = 0x00102030;

struct Framebuffer {
    std::vector<QInt32> pixels;
    SoftwareRenderSurfaceView view;

    Framebuffer()
        : pixels(static_cast<std::size_t>(kWidth) * kHeight, 0), view()
    {
        /* pixelCount is required: the rasterizer rejects an unset surface. */
        view = SoftwareRenderSurfaceView(pixels.data(), kWidth * kHeight,
                                         kWidth, kHeight, kWidth);
    }

    void fill(QInt32 aValue)
    {
        for (std::size_t index = 0; index < pixels.size(); ++index) {
            pixels[index] = aValue;
        }
    }
};

void fail(const char* aMessage)
{
    std::fprintf(stderr, "FAIL: %s\n", aMessage);
    std::exit(1);
}

void require(bool aCondition, const char* aMessage)
{
    if (!aCondition) fail(aMessage);
}

/* FNV-1a over the raw bytes, so a single changed channel flips the hash. */
std::uint64_t hashFramebuffer(const Framebuffer& aFramebuffer)
{
    std::uint64_t hash = 1469598103934665603ULL;
    const std::uint8_t* bytes = reinterpret_cast<const std::uint8_t*>(
        aFramebuffer.pixels.data());
    const std::size_t count = aFramebuffer.pixels.size() * sizeof(QInt32);
    for (std::size_t index = 0; index < count; ++index) {
        hash ^= bytes[index];
        hash *= 1099511628211ULL;
    }
    return hash;
}

/*
 * A texture with a deliberate mix of opaque, dark, and bright texels so the
 * zero-texel skip, the fog lerp and the alpha path all get exercised.
 */
SoftwareTextureView makeOpaqueTexture(std::vector<QInt32>& aStorage)
{
    const QInt32 texWidthBit = 6; /* 64x64 */
    aStorage.assign(static_cast<std::size_t>(1) << (texWidthBit * 2), 0);
    for (QInt32 y = 0; y < 64; ++y) {
        for (QInt32 x = 0; x < 64; ++x) {
            const QInt32 red = (x * 4) & 0xff;
            const QInt32 green = (y * 4) & 0xff;
            const QInt32 blue = ((x + y) * 2) & 0xff;
            aStorage[(y << texWidthBit) + x] =
                (red << 16) | (green << 8) | blue;
        }
    }
    /* Masked sampling uses power-of-two dimensions, so 64x64 it is. */
    return SoftwareTextureView(aStorage.data(),
                               static_cast<QInt32>(aStorage.size()), 64, 64,
                               /*hasTransparentTexels=*/false,
                               /*mayContainZeroTexels=*/false,
                               /*hasAlphaMixing=*/false);
}

/* Same geometry but with holes, to drive the MayContainZeroTexels template. */
SoftwareTextureView makeKeyedTexture(std::vector<QInt32>& aStorage)
{
    makeOpaqueTexture(aStorage);
    for (std::size_t index = 0; index < aStorage.size(); index += 3) {
        aStorage[index] = 0;
    }
    /* Rebuild the view over the mutated storage with the key flag set, so the
       MayContainZeroTexels template is actually exercised. */
    return SoftwareTextureView(aStorage.data(),
                               static_cast<QInt32>(aStorage.size()), 64, 64,
                               /*hasTransparentTexels=*/true,
                               /*mayContainZeroTexels=*/true,
                               /*hasAlphaMixing=*/false);
}

struct Triangle {
    AffineTriangleVertex first;
    AffineTriangleVertex second;
    AffineTriangleVertex third;
};

/*
 * Three triangles: a large one that covers most of the frame (so intra-span
 * stepping is used), a small one, and one that straddles the left edge (so the
 * clipping branch and the rewrite of x1 fire).
 */
void makeTriangles(std::vector<Triangle>& aTriangles)
{
    aTriangles.clear();
    {
        Triangle triangle;
        triangle.first = AffineTriangleVertex(4, 2, 0, 0, 4000);
        triangle.second = AffineTriangleVertex(90, 6, 63 * 256, 0, 3900);
        triangle.third = AffineTriangleVertex(40, 76, 8 * 256, 63 * 256, 4100);
        aTriangles.push_back(triangle);
    }
    {
        Triangle triangle;
        triangle.first = AffineTriangleVertex(20, 20, 8 * 256, 8 * 256, 2000);
        triangle.second = AffineTriangleVertex(50, 24, 40 * 256, 10 * 256, 1900);
        triangle.third = AffineTriangleVertex(30, 50, 20 * 256, 50 * 256, 2100);
        aTriangles.push_back(triangle);
    }
    {
        Triangle triangle;
        triangle.first = AffineTriangleVertex(-12, 10, 0, 0, 3000);
        triangle.second = AffineTriangleVertex(30, 14, 60 * 256, 0, 2900);
        triangle.third = AffineTriangleVertex(-6, 60, 4 * 256, 60 * 256, 3100);
        aTriangles.push_back(triangle);
    }
}

std::uint64_t renderMode0(const SoftwareTextureView& aTexture,
                          const std::vector<Triangle>& aTriangles)
{
    Framebuffer framebuffer;
    framebuffer.fill(kBackground);
    for (std::size_t index = 0; index < aTriangles.size(); ++index) {
        require(SoftwarePerspectiveRasterizer::drawMode0Copy(
                    framebuffer.view, aTexture, aTriangles[index].first,
                    aTriangles[index].second, aTriangles[index].third, 12, 150),
                "perspective mode0 failed");
    }
    return hashFramebuffer(framebuffer);
}

std::uint64_t renderMode9(const SoftwareTextureView& aTexture,
                          const std::vector<Triangle>& aTriangles)
{
    Framebuffer framebuffer;
    framebuffer.fill(kBackground);
    for (std::size_t index = 0; index < aTriangles.size(); ++index) {
        require(SoftwarePerspectiveRasterizer::drawMode9Shade(
                    framebuffer.view, aTexture, aTriangles[index].first,
                    aTriangles[index].second, aTriangles[index].third, 200, 150,
                    255, 12, 150),
                "perspective mode9 failed");
    }
    return hashFramebuffer(framebuffer);
}

std::uint64_t renderMode3LQ(const SoftwareTextureView& aTexture,
                            const std::vector<Triangle>& aTriangles)
{
    Framebuffer framebuffer;
    framebuffer.fill(kBackground);
    for (std::size_t index = 0; index < aTriangles.size(); ++index) {
        require(SoftwarePerspectiveRasterizer::drawMode3LQ(
                    framebuffer.view, aTexture, aTriangles[index].first,
                    aTriangles[index].second, aTriangles[index].third,
                    0x00406080, 96, 12, 150),
                "perspective mode3LQ failed");
    }
    return hashFramebuffer(framebuffer);
}

std::uint64_t renderMode3HQ(const SoftwareTextureView& aTexture,
                            const std::vector<Triangle>& aTriangles)
{
    Framebuffer framebuffer;
    framebuffer.fill(kBackground);
    for (std::size_t index = 0; index < aTriangles.size(); ++index) {
        require(SoftwarePerspectiveRasterizer::drawMode3HQ(
                    framebuffer.view, aTexture, aTriangles[index].first,
                    aTriangles[index].second, aTriangles[index].third,
                    0x00406080, 2200, 12, 150),
                "perspective mode3HQ failed");
    }
    return hashFramebuffer(framebuffer);
}

std::uint64_t renderMode1Add(const SoftwareTextureView& aTexture,
                             const std::vector<Triangle>& aTriangles)
{
    Framebuffer framebuffer;
    framebuffer.fill(kBackground);
    for (std::size_t index = 0; index < aTriangles.size(); ++index) {
        require(SoftwarePerspectiveRasterizer::drawMode1Add(
                    framebuffer.view, aTexture, aTriangles[index].first,
                    aTriangles[index].second, aTriangles[index].third,
                    0x00203040, 12, 150),
                "perspective mode1 failed");
    }
    return hashFramebuffer(framebuffer);
}

std::uint64_t renderMode2SurfaceAdd(const SoftwareTextureView& aTexture,
                                    const std::vector<Triangle>& aTriangles)
{
    Framebuffer framebuffer;
    framebuffer.fill(kBackground);
    for (std::size_t index = 0; index < aTriangles.size(); ++index) {
        require(SoftwarePerspectiveRasterizer::drawMode2SurfaceAdd(
                    framebuffer.view, aTexture, aTriangles[index].first,
                    aTriangles[index].second, aTriangles[index].third, 12, 150),
                "perspective mode2 failed");
    }
    return hashFramebuffer(framebuffer);
}

/*
 * Segmentation matters: the effect span walks the scanline in
 * perspectiveStepPixels-sized pieces, so run one pass at the reference 24 and
 * one at the shipped 96 to cover both the special-cased divisor and the
 * generic javaIntDiv path.
 */
void emit(const char* aName, std::uint64_t aHash)
{
    std::printf("%-28s %016llx\n", aName,
                static_cast<unsigned long long>(aHash));
}

} // namespace

int main(int aArgumentCount, char** aArguments)
{
    const bool printOnly =
        aArgumentCount > 1 && std::strcmp(aArguments[1], "--print") == 0;

    std::vector<QInt32> opaqueStorage;
    std::vector<QInt32> keyedStorage;
    const SoftwareTextureView opaqueTexture = makeOpaqueTexture(opaqueStorage);
    const SoftwareTextureView keyedTexture = makeKeyedTexture(keyedStorage);

    std::vector<Triangle> triangles;
    makeTriangles(triangles);

    /*
     * Golden hashes. They were produced by the code before the loop-hoisting
     * refactor and are re-verified by this same harness afterwards.
     */
    struct Entry {
        const char* name;
        std::uint64_t expected;
    };
    static const Entry kGolden[] = {
        {"mode0 opaque", 0x1c8bb55ff4b86141ULL},
        {"mode0 keyed", 0x06e1f91ca106c4abULL},
        {"mode9 opaque", 0xe6f91739971b5768ULL},
        {"mode9 keyed", 0x97257c1a51653926ULL},
        {"mode1 opaque", 0x7152c98a9f8541e4ULL},
        {"mode2 opaque", 0xba32dfae5bb1e0cbULL},
        {"mode3LQ opaque", 0xf4dc4b4edeaf8e9dULL},
        {"mode3HQ opaque", 0x73a73cb94d68af4cULL},
    };

    const std::uint64_t measured[] = {
        renderMode0(opaqueTexture, triangles),
        renderMode0(keyedTexture, triangles),
        renderMode9(opaqueTexture, triangles),
        renderMode9(keyedTexture, triangles),
        renderMode1Add(opaqueTexture, triangles),
        renderMode2SurfaceAdd(opaqueTexture, triangles),
        renderMode3LQ(opaqueTexture, triangles),
        renderMode3HQ(opaqueTexture, triangles),
    };
    const std::size_t entryCount = sizeof(kGolden) / sizeof(kGolden[0]);
    require(sizeof(measured) / sizeof(measured[0]) == entryCount,
            "golden and measured tables disagree in size");

    if (printOnly) {
        for (std::size_t index = 0; index < entryCount; ++index) {
            emit(kGolden[index].name, measured[index]);
        }
        return 0;
    }

    bool failed = false;
    for (std::size_t index = 0; index < entryCount; ++index) {
        if (measured[index] != kGolden[index].expected) {
            std::fprintf(stderr,
                         "FAIL: %s hash %016llx, expected %016llx\n",
                         kGolden[index].name,
                         static_cast<unsigned long long>(measured[index]),
                         static_cast<unsigned long long>(
                             kGolden[index].expected));
            failed = true;
        }
    }
    if (failed) return 1;

    std::printf("perspective_bitexact_test: all %zu hashes match\n",
                entryCount);
    return 0;
}
