#ifndef QUANTUM_SOFTWARE_AFFINE_RASTERIZER_H
#define QUANTUM_SOFTWARE_AFFINE_RASTERIZER_H

#include "quantum_types.h"

namespace code {
namespace Rendering {

/* Non-owning views; dimensions and capacities are measured in pixels. */
struct SoftwareRenderSurfaceView {
    code::Math::QInt32* pixels;
    code::Math::QInt32 pixelCount;
    code::Math::QInt32 width;
    code::Math::QInt32 height;
    code::Math::QInt32 stride;

    SoftwareRenderSurfaceView();
    SoftwareRenderSurfaceView(code::Math::QInt32* pixels,
                              code::Math::QInt32 pixelCount,
                              code::Math::QInt32 width,
                              code::Math::QInt32 height,
                              code::Math::QInt32 stride);
};

struct SoftwareTextureView {
    const code::Math::QInt32* pixels;
    code::Math::QInt32 pixelCount;
    code::Math::QInt32 width;
    code::Math::QInt32 height;
    bool hasTransparentTexels;
    /* True by default; false guarantees no sampled texel equals the zero key. */
    bool mayContainZeroTexels;

    SoftwareTextureView();
    SoftwareTextureView(const code::Math::QInt32* pixels,
                        code::Math::QInt32 pixelCount,
                        code::Math::QInt32 width,
                        code::Math::QInt32 height,
                        bool hasTransparentTexels = false,
                        bool mayContainZeroTexels = true);
};

/* Screen coordinates are pixels, U/V are integer texels, and depthRz is
 * Java Vertex.rz.
 */
struct AffineTriangleVertex {
    code::Math::QInt32 screenX;
    code::Math::QInt32 screenY;
    code::Math::QInt32 textureU;
    code::Math::QInt32 textureV;
    code::Math::QInt32 depthRz;

    AffineTriangleVertex();
    AffineTriangleVertex(code::Math::QInt32 screenX,
                         code::Math::QInt32 screenY,
                         code::Math::QInt32 textureU,
                         code::Math::QInt32 textureV);
    AffineTriangleVertex(code::Math::QInt32 screenX,
                         code::Math::QInt32 screenY,
                         code::Math::QInt32 textureU,
                         code::Math::QInt32 textureV,
                         code::Math::QInt32 depthRz);
};

/* Per-vertex RGB multipliers consumed by affine drawmode 13. */
struct AffineTriangleColor {
    code::Math::QInt32 redFactor;
    code::Math::QInt32 greenFactor;
    code::Math::QInt32 blueFactor;

    AffineTriangleColor();
    AffineTriangleColor(code::Math::QInt32 redFactor,
                        code::Math::QInt32 greenFactor,
                        code::Math::QInt32 blueFactor);
};

/*
 * Pixel-level implementations of the Java affine drawmodes. The texture-map
 * paths preserve Java Q14 screen/Q12 stepping and integer wrap/shift
 * semantics. U/V are pre-scaled as in `TexturingAffine.paint()`.
 *
 * The routine clips to the target view and supports a padded destination
 * stride. It allocates no memory. False means a view is invalid or HQ fog
 * distance is zero; degenerate or fully off-screen triangles are successful
 * no-ops.
 */
class SoftwareAffineRasterizer {
public:
    static bool drawMode0(const SoftwareRenderSurfaceView& destination,
                          const SoftwareTextureView& texture,
                          const AffineTriangleVertex& first,
                          const AffineTriangleVertex& second,
                          const AffineTriangleVertex& third);

    /* Mode 1 adds the already-computed packed fog color (Java's sz2). */
    static bool drawMode1(const SoftwareRenderSurfaceView& destination,
                          const SoftwareTextureView& texture,
                          const AffineTriangleVertex& first,
                          const AffineTriangleVertex& second,
                          const AffineTriangleVertex& third,
                          code::Math::QInt32 packedFogColor);

    /* Mode 2 saturating-adds texture RGB into the existing framebuffer. */
    static bool drawMode2(const SoftwareRenderSurfaceView& destination,
                          const SoftwareTextureView& texture,
                          const AffineTriangleVertex& first,
                          const AffineTriangleVertex& second,
                          const AffineTriangleVertex& third);

    /* Low-quality mode 3: one polygon-wide fog weight, matching TexturingAffine. */
    static bool drawMode3LQ(const SoftwareRenderSurfaceView& destination,
                            const SoftwareTextureView& texture,
                            const AffineTriangleVertex& first,
                            const AffineTriangleVertex& second,
                            const AffineTriangleVertex& third,
                            code::Math::QInt32 fogColor,
                            code::Math::QInt32 fogStrength);

    /* HQ mode 3 mirrors Vertex.rz fog interpolation; fogDistance is fDist. */
    static bool drawMode3HQ(const SoftwareRenderSurfaceView& destination,
                            const SoftwareTextureView& texture,
                            const AffineTriangleVertex& first,
                            const AffineTriangleVertex& second,
                            const AffineTriangleVertex& third,
                            code::Math::QInt32 fogColor,
                            code::Math::QInt32 fogDistance);

    /* Mode 4 blends texel RGB over the target using the texel's high-byte alpha. */
    static bool drawMode4AlphaBlend(const SoftwareRenderSurfaceView& destination,
                                    const SoftwareTextureView& texture,
                                    const AffineTriangleVertex& first,
                                    const AffineTriangleVertex& second,
                                    const AffineTriangleVertex& third);

    /* Mode 5 applies the supplied Java sz2 shade amount (0..255). */
    static bool drawMode5Shade(const SoftwareRenderSurfaceView& destination,
                               const SoftwareTextureView& texture,
                               const AffineTriangleVertex& first,
                               const AffineTriangleVertex& second,
                               const AffineTriangleVertex& third,
                               code::Math::QInt32 shadeStrength);

    /* Mode 6 low-quality path derives shade from max(rz), as Java does. */
    static bool drawMode6LQ(const SoftwareRenderSurfaceView& destination,
                            const SoftwareTextureView& texture,
                            const AffineTriangleVertex& first,
                            const AffineTriangleVertex& second,
                            const AffineTriangleVertex& third,
                            code::Math::QInt32 fallbackShadeStrength,
                            code::Math::QInt32 fogDistance);

    /* Mode 6 high-quality per-vertex path; factors are texture brightness. */
    static bool drawMode6HQ(const SoftwareRenderSurfaceView& destination,
                            const SoftwareTextureView& texture,
                            const AffineTriangleVertex& first,
                            const AffineTriangleVertex& second,
                            const AffineTriangleVertex& third,
                            code::Math::QInt32 firstBrightness,
                            code::Math::QInt32 secondBrightness,
                            code::Math::QInt32 thirdBrightness);

    /* Mode 8 samples one texel at (sampleU,sampleV), then fills the triangle. */
    static bool drawMode8Fill(const SoftwareRenderSurfaceView& destination,
                              const SoftwareTextureView& texture,
                              const AffineTriangleVertex& first,
                              const AffineTriangleVertex& second,
                              const AffineTriangleVertex& third,
                              code::Math::QInt32 sampleU,
                              code::Math::QInt32 sampleV);

    /* Mode 7 glass path: RGB texture values offset a framebuffer sample. */
    static bool drawMode7Glass(const SoftwareRenderSurfaceView& destination,
                               const SoftwareTextureView& texture,
                               const AffineTriangleVertex& first,
                               const AffineTriangleVertex& second,
                               const AffineTriangleVertex& third);

    /* Mode 9 interpolates one brightness factor per vertex. */
    static bool drawMode9(const SoftwareRenderSurfaceView& destination,
                          const SoftwareTextureView& texture,
                          const AffineTriangleVertex& first,
                          const AffineTriangleVertex& second,
                          const AffineTriangleVertex& third,
                          code::Math::QInt32 firstBrightness,
                          code::Math::QInt32 secondBrightness,
                          code::Math::QInt32 thirdBrightness);

    /* Mode 13 independently interpolates per-vertex red, green, and blue factors. */
    static bool drawMode13(const SoftwareRenderSurfaceView& destination,
                           const SoftwareTextureView& texture,
                           const AffineTriangleVertex& first,
                           const AffineTriangleVertex& second,
                           const AffineTriangleVertex& third,
                           const AffineTriangleColor& firstColor,
                           const AffineTriangleColor& secondColor,
                           const AffineTriangleColor& thirdColor);

    /* Mode 10's screen-space texture dither with interpolated 0..255 factors. */
    static bool drawMode10DitherGradient(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTextureView& texture,
        const AffineTriangleVertex& first,
        const AffineTriangleVertex& second,
        const AffineTriangleVertex& third,
        code::Math::QInt32 firstGradient,
        code::Math::QInt32 secondGradient,
        code::Math::QInt32 thirdGradient);

    /* Modes 11/12 are the same Java dither path with depth-derived factors. */
    static bool drawMode11DitherWater(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTextureView& texture,
        const AffineTriangleVertex& first,
        const AffineTriangleVertex& second,
        const AffineTriangleVertex& third,
        code::Math::QInt32 waterDistance);
    static bool drawMode12DitherFog(
        const SoftwareRenderSurfaceView& destination,
        const SoftwareTextureView& texture,
        const AffineTriangleVertex& first,
        const AffineTriangleVertex& second,
        const AffineTriangleVertex& third,
        code::Math::QInt32 fogDistance);

    /*
     * Port of Polygon3V/Polygon4V's szCalcfogAdd color preparation.
     * nearestDepth is the minimum rz among the triangle/quad vertices.
     */
    static code::Math::QInt32 mode1FogAddColor(
        code::Math::QInt32 nearestDepth,
        code::Math::QInt32 fogDistance,
        code::Math::QInt32 fogColor);

    /* farthestDepth is max(rz); fallback applies when fogDistance/255 is 0. */
    static code::Math::QInt32 mode3FogStrength(
        code::Math::QInt32 farthestDepth,
        code::Math::QInt32 fogDistance,
        code::Math::QInt32 fallbackStrength);
};

} // namespace Rendering
} // namespace code

#endif // QUANTUM_SOFTWARE_AFFINE_RASTERIZER_H
