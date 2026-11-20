#ifndef QUANTUM_OMAP1710_EFFECTS_H
#define QUANTUM_OMAP1710_EFFECTS_H

#include "quantum_integer.h"

namespace code {
namespace Math {
namespace ArmV5teDsp {

/* ARM-state ABI for one clipped-safe, shallow, downward rain line. */
struct Omap1710RainStreakArgs {
    QInt32* destination;
    QInt32 stride;
    QInt32 width;
    QInt32 height;
    QInt32 x;
    QInt32 y;
    QInt32 dx;
    QInt32 dy;
    QInt32 color;
};

} // namespace ArmV5teDsp
} // namespace Math
} // namespace code

extern "C" void quantum_omap1710_blend_argb_row(
    code::Math::QInt32* destination,
    const code::Math::QInt32* source,
    code::Math::QInt32 pixelCount,
    code::Math::QInt32 sourceStep);

extern "C" void quantum_omap1710_rain_streak(
    code::Math::ArmV5teDsp::Omap1710RainStreakArgs* args);

#endif // QUANTUM_OMAP1710_EFFECTS_H
