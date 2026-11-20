#ifndef QUANTUM_SOFTWARE_RAIN_RENDERER_H
#define QUANTUM_SOFTWARE_RAIN_RENDERER_H

#include "software_affine_rasterizer.h"

namespace code {
namespace Rendering {

/*
 * Draws the shallow, downward rain streak used by WeatherGenerator.paint():
 * from (x,y) to (x-size/4,y+size), inclusive. This small renderer is also the
 * portable fallback/reference for the E65 ARMv5TE streak kernel.
 */
class SoftwareRainRenderer {
public:
    static bool drawStreak(const SoftwareRenderSurfaceView& destination,
                           code::Math::QInt32 x,
                           code::Math::QInt32 y,
                           code::Math::QInt32 size,
                           code::Math::QInt32 color);
};

} // namespace Rendering
} // namespace code

#endif // QUANTUM_SOFTWARE_RAIN_RENDERER_H
