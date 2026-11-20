#ifndef QUANTUM_SOFTWARE_PERSPECTIVE_RASTERIZER_H
#define QUANTUM_SOFTWARE_PERSPECTIVE_RASTERIZER_H

#include "software_affine_rasterizer.h"

namespace code {
namespace Rendering {

/*
 * Standalone perspective-correct triangle kernels corresponding to the Java
 * TexturingPers drawmode dispatch. Vertices are already projected; texture
 * U/V are integer texel coordinates (pre-scaled for this texture), and depthRz
 * is Java Vertex.rz. The horizontal correction interval is `q` (normally 12
 * or 24 pixels); `move` matches TexturingPers.move (default 150).
 *
 * Modes 8 and 10–12 intentionally are not perspective kernels: Java routes
 * those modes through constant-fill or screen-space dither paths before its
 * perspective dispatcher. Call the matching affine kernels for them. The
 * q > 999 floor paths are included for mode 3 LQ/HQ, mode 6 HQ, mode 9 HQ,
 * and mode 13; mode 6 HQ preserves its scanline-constant floor shade. Other
 * kernels accept a large q as a long perspective interval. Invalid views or
 * settings, or a zero perspective denominator that Java would divide by,
 * return false.
 */
struct SoftwarePerspectiveRasterProfile {
    code::Math::QInt32 perspectiveDivideCalls;
    code::Math::QInt32 sampledPerspectiveDivideCalls;
    code::Math::QInt64 sampledPerspectiveDivideTicks;
    code::Math::QInt32 pixelLoopCalls;
    code::Math::QInt32 pixelCount;
    code::Math::QUint32 counterReadOverheadTicks;
    code::Math::QInt32 sampledPixelLoopCalls;
    code::Math::QInt32 sampledPixelCount;
    code::Math::QInt64 sampledPixelLoopTicks;

    /* Exclusive, sampled decomposition of DMODE 9's current Other raster. */
    code::Math::QInt32 mode9TriangleCalls;
    code::Math::QInt32 sampledMode9TriangleSetupCalls;
    code::Math::QInt64 sampledMode9TriangleSetupTicks;
    code::Math::QInt32 mode9EdgeRows;
    code::Math::QInt32 sampledMode9EdgeRows;
    code::Math::QInt64 sampledMode9EdgeTicks;
    code::Math::QInt64 sampledMode9SpanRawTicks;
    code::Math::QInt32 sampledMode9SpanPerspectiveDivideCalls;
    code::Math::QInt32 sampledMode9SpanSampledPerspectiveDivideCalls;
    code::Math::QInt64 sampledMode9SpanPixelCount;
    code::Math::QInt32 sampledMode9SpanSampledPixelLoopCalls;

    SoftwarePerspectiveRasterProfile()
        : perspectiveDivideCalls(0), sampledPerspectiveDivideCalls(0),
          sampledPerspectiveDivideTicks(0), pixelLoopCalls(0), pixelCount(0),
          counterReadOverheadTicks(0), sampledPixelLoopCalls(0),
          sampledPixelCount(0), sampledPixelLoopTicks(0),
          mode9TriangleCalls(0), sampledMode9TriangleSetupCalls(0),
          sampledMode9TriangleSetupTicks(0), mode9EdgeRows(0),
          sampledMode9EdgeRows(0), sampledMode9EdgeTicks(0),
          sampledMode9SpanRawTicks(0),
          sampledMode9SpanPerspectiveDivideCalls(0),
          sampledMode9SpanSampledPerspectiveDivideCalls(0),
          sampledMode9SpanPixelCount(0),
          sampledMode9SpanSampledPixelLoopCalls(0)
    {
    }

    void reset()
    {
        perspectiveDivideCalls = 0;
        sampledPerspectiveDivideCalls = 0;
        sampledPerspectiveDivideTicks = 0;
        pixelLoopCalls = 0;
        pixelCount = 0;
        counterReadOverheadTicks = 0;
        sampledPixelLoopCalls = 0;
        sampledPixelCount = 0;
        sampledPixelLoopTicks = 0;
        mode9TriangleCalls = 0;
        sampledMode9TriangleSetupCalls = 0;
        sampledMode9TriangleSetupTicks = 0;
        mode9EdgeRows = 0;
        sampledMode9EdgeRows = 0;
        sampledMode9EdgeTicks = 0;
        sampledMode9SpanRawTicks = 0;
        sampledMode9SpanPerspectiveDivideCalls = 0;
        sampledMode9SpanSampledPerspectiveDivideCalls = 0;
        sampledMode9SpanPixelCount = 0;
        sampledMode9SpanSampledPixelLoopCalls = 0;
    }
};

class SoftwarePerspectiveRasterizer {
public:
#if defined(QUANTUM_LEVEL41_PROFILE_RASTER_DETAILS)
    /* Diagnostic-only hook; null disables all raster profiling. */
    static void setProfile(SoftwarePerspectiveRasterProfile* profile);
#endif
    static bool drawMode0Copy(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTextureView& texture,
        const AffineTriangleVertex& first,
        const AffineTriangleVertex& second,
        const AffineTriangleVertex& third,
        code::Math::QInt32 perspectiveStepPixels,
        code::Math::QInt32 move = 150);

    static bool drawMode1Add(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTextureView& texture,
        const AffineTriangleVertex& first,
        const AffineTriangleVertex& second,
        const AffineTriangleVertex& third,
        code::Math::QInt32 packedFogColor,
        code::Math::QInt32 perspectiveStepPixels,
        code::Math::QInt32 move = 150);

    static bool drawMode2SurfaceAdd(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTextureView& texture,
        const AffineTriangleVertex& first,
        const AffineTriangleVertex& second,
        const AffineTriangleVertex& third,
        code::Math::QInt32 perspectiveStepPixels,
        code::Math::QInt32 move = 150);

    static bool drawMode3LQ(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTextureView& texture,
        const AffineTriangleVertex& first,
        const AffineTriangleVertex& second,
        const AffineTriangleVertex& third,
        code::Math::QInt32 fogColor,
        code::Math::QInt32 fogStrength,
        code::Math::QInt32 perspectiveStepPixels,
        code::Math::QInt32 move = 150);

    static bool drawMode3HQ(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTextureView& texture,
        const AffineTriangleVertex& first,
        const AffineTriangleVertex& second,
        const AffineTriangleVertex& third,
        code::Math::QInt32 fogColor,
        code::Math::QInt32 fogDistance,
        code::Math::QInt32 perspectiveStepPixels,
        code::Math::QInt32 move = 150);

    static bool drawMode4AlphaBlend(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTextureView& texture,
        const AffineTriangleVertex& first,
        const AffineTriangleVertex& second,
        const AffineTriangleVertex& third,
        code::Math::QInt32 perspectiveStepPixels,
        code::Math::QInt32 move = 150);

    /* Mode 5 includes Java's all-dark depth guard; shadeStrength is sz2. */
    static bool drawMode5(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTextureView& texture,
        const AffineTriangleVertex& first,
        const AffineTriangleVertex& second,
        const AffineTriangleVertex& third,
        code::Math::QInt32 shadeStrength,
        code::Math::QInt32 fogDistance,
        code::Math::QInt32 perspectiveStepPixels,
        code::Math::QInt32 move = 150);

    /* Lower-level constant-shade path without the Java depth dispatch guard. */
    static bool drawMode5Shade(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTextureView& texture,
        const AffineTriangleVertex& first,
        const AffineTriangleVertex& second,
        const AffineTriangleVertex& third,
        code::Math::QInt32 shadeStrength,
        code::Math::QInt32 perspectiveStepPixels,
        code::Math::QInt32 move = 150);

    static bool drawMode6LQ(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTextureView& texture,
        const AffineTriangleVertex& first,
        const AffineTriangleVertex& second,
        const AffineTriangleVertex& third,
        code::Math::QInt32 fallbackShadeStrength,
        code::Math::QInt32 fogDistance,
        code::Math::QInt32 perspectiveStepPixels,
        code::Math::QInt32 move = 150);

    static bool drawMode6HQ(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTextureView& texture,
        const AffineTriangleVertex& first,
        const AffineTriangleVertex& second,
        const AffineTriangleVertex& third,
        code::Math::QInt32 fogDistance,
        code::Math::QInt32 perspectiveStepPixels,
        code::Math::QInt32 move = 150);

    static bool drawMode6HQ(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTextureView& texture,
        const AffineTriangleVertex& first,
        const AffineTriangleVertex& second,
        const AffineTriangleVertex& third,
        code::Math::QInt32 fogDistance,
        code::Math::QInt32 firstVectorLight,
        code::Math::QInt32 secondVectorLight,
        code::Math::QInt32 thirdVectorLight,
        code::Math::QInt32 perspectiveStepPixels,
        code::Math::QInt32 move = 150);

    static bool drawMode7Glass(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTextureView& texture,
        const AffineTriangleVertex& first,
        const AffineTriangleVertex& second,
        const AffineTriangleVertex& third,
        code::Math::QInt32 perspectiveStepPixels,
        code::Math::QInt32 move = 150);

    static bool drawMode9Shade(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTextureView& texture,
        const AffineTriangleVertex& first,
        const AffineTriangleVertex& second,
        const AffineTriangleVertex& third,
        code::Math::QInt32 firstBrightness,
        code::Math::QInt32 secondBrightness,
        code::Math::QInt32 thirdBrightness,
        code::Math::QInt32 perspectiveStepPixels,
        code::Math::QInt32 move = 150);

    static bool drawMode13(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTextureView& texture,
        const AffineTriangleVertex& first,
        const AffineTriangleVertex& second,
        const AffineTriangleVertex& third,
        const AffineTriangleColor& firstColor,
        const AffineTriangleColor& secondColor,
        const AffineTriangleColor& thirdColor,
        code::Math::QInt32 perspectiveStepPixels,
        code::Math::QInt32 move = 150);
};

} // namespace Rendering
} // namespace code

#endif // QUANTUM_SOFTWARE_PERSPECTIVE_RASTERIZER_H
