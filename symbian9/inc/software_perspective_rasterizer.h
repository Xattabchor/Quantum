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
 * floor-special paths selected when q > 999 are not included; invalid
 * views/settings or a zero perspective denominator return false.
 */
class SoftwarePerspectiveRasterizer {
public:
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
