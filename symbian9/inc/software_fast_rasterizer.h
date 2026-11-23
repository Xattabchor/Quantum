#ifndef QUANTUM_SOFTWARE_FAST_RASTERIZER_H
#define QUANTUM_SOFTWARE_FAST_RASTERIZER_H

#include "software_affine_rasterizer.h"
#include "software_texture16.h"

namespace code {
namespace Rendering {

/*
 * Fast 16-bit texture rasterizers for the optimized Symbian path.
 *
 * These kernels sample SoftwareTexture16View (RGB565 / RGB1555) and expand
 * texels to the 32-bit 0xAARRGGBB framebuffer on the fly. They cover the
 * two hot drawmodes:
 *  - mode 0: opaque copy (sky, unlit surfaces). 1555 transparent texels
 *    (alpha bit 0) are skipped, matching the 32-bit zero-texel key.
 *  - mode 9: per-vertex grayscale shade (lit world geometry). Brightness
 *    factors are 0..255 and interpolated in Q12 like the reference path.
 *
 * Affine kernels use Q14 screen / Q12 texture fixed-point edge walking
 * with native integer arithmetic (no Java-wrapping helpers in the pixel
 * loop) and 4x unrolled spans. Perspective kernels interpolate u/w, v/w
 * and 1/w along edges and correct every `q` pixels (Java TexturingPers
 * style); q > 999 is treated as a single correction interval.
 *
 * Invalid views return false; degenerate or fully off-screen triangles
 * are successful no-ops. No memory is allocated.
 */
class SoftwareFastRasterizer {
public:
    static bool drawAffineMode0(const SoftwareRenderSurfaceView& destination,
                                const SoftwareTexture16View& texture,
                                const AffineTriangleVertex& first,
                                const AffineTriangleVertex& second,
                                const AffineTriangleVertex& third);

    static bool drawAffineMode9(const SoftwareRenderSurfaceView& destination,
                                const SoftwareTexture16View& texture,
                                const AffineTriangleVertex& first,
                                const AffineTriangleVertex& second,
                                const AffineTriangleVertex& third,
                                code::Math::QInt32 firstBrightness,
                                code::Math::QInt32 secondBrightness,
                                code::Math::QInt32 thirdBrightness);

    /*
     * Drawmode 4: per-texel alpha blend against the existing framebuffer.
     * Requires an ARGB4444 texture: 1-bit 1555 cannot express the partial
     * alpha this mode exists for, and RGB565 has no alpha at all. The 4-bit
     * alpha expands to 0..255 and the blend then uses the reference's exact
     * 8-bit arithmetic, so results match the 32-bit kernel within 4444's
     * channel quantisation.
     */
    static bool drawAffineMode4(const SoftwareRenderSurfaceView& destination,
                                const SoftwareTexture16View& texture,
                                const AffineTriangleVertex& first,
                                const AffineTriangleVertex& second,
                                const AffineTriangleVertex& third);

    static bool drawPerspectiveMode4(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTexture16View& texture,
        const AffineTriangleVertex& first,
        const AffineTriangleVertex& second,
        const AffineTriangleVertex& third,
        code::Math::QInt32 perspectiveStepPixels,
        code::Math::QInt32 move = 150);

    static bool drawPerspectiveMode0(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTexture16View& texture,
        const AffineTriangleVertex& first,
        const AffineTriangleVertex& second,
        const AffineTriangleVertex& third,
        code::Math::QInt32 perspectiveStepPixels,
        code::Math::QInt32 move = 150);

    static bool drawPerspectiveMode9(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTexture16View& texture,
        const AffineTriangleVertex& first,
        const AffineTriangleVertex& second,
        const AffineTriangleVertex& third,
        code::Math::QInt32 firstBrightness,
        code::Math::QInt32 secondBrightness,
        code::Math::QInt32 thirdBrightness,
        code::Math::QInt32 perspectiveStepPixels,
        code::Math::QInt32 move = 150);
};

} // namespace Rendering
} // namespace code

#endif // QUANTUM_SOFTWARE_FAST_RASTERIZER_H
