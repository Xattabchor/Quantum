#include "software_rain_renderer.h"

#include "quantum_integer.h"

#if defined(QUANTUM_LEVEL41_OMAP1710_EFFECTS_ASM) && defined(__SYMBIAN32__)
#include "omap1710_effects.h"
#include <stddef.h>
#endif

namespace code {
namespace Rendering {

namespace {

using code::Math::QInt32;
using code::Math::QInt64;
#if defined(QUANTUM_LEVEL41_OMAP1710_EFFECTS_ASM) && defined(__SYMBIAN32__)
using code::Math::ArmV5teDsp::Omap1710RainStreakArgs;
#endif

const QInt32 KMaximumAsmRainStreakSize = 8192;

bool validDestination(const SoftwareRenderSurfaceView& destination)
{
    if (destination.pixels == 0 || destination.width <= 0 ||
        destination.height <= 0 || destination.stride < destination.width ||
        destination.pixelCount <= 0) {
        return false;
    }
    const QInt64 required =
        static_cast<QInt64>(destination.height - 1) * destination.stride +
        destination.width;
    return required > 0 && required <= destination.pixelCount;
}

void drawStreakReference(const SoftwareRenderSurfaceView& destination,
                         QInt32 x,
                         QInt32 y,
                         QInt32 size,
                         QInt32 color)
{
    const QInt64 dx = size / 4;
    const QInt64 dy = size;
    if (dy == 0) {
        if (x >= 0 && x < destination.width &&
            y >= 0 && y < destination.height) {
            const size_t index = static_cast<size_t>(y) *
                                     static_cast<size_t>(destination.stride) +
                                 static_cast<size_t>(x);
            destination.pixels[index] = color;
        }
        return;
    }
    QInt64 firstStep = 0;
    QInt64 lastStep = dy;
    if (y < 0) firstStep = -static_cast<QInt64>(y);
    const QInt64 lastVisibleStep =
        static_cast<QInt64>(destination.height - 1) - y;
    if (lastStep > lastVisibleStep) lastStep = lastVisibleStep;
    if (firstStep > lastStep) return;

    QInt64 step;
    for (step = firstStep; step <= lastStep; ++step) {
        /* Equivalent to the same dy/2-seeded add/compare recurrence used by
           the assembly kernel, evaluated directly for unusual geometry. */
        const QInt64 xOffset = (dy / 2 + dx * step) / dy;
        const QInt64 currentX = static_cast<QInt64>(x) - xOffset;
        const QInt64 currentY = static_cast<QInt64>(y) + step;
        if (currentX >= 0 && currentX < destination.width) {
            const size_t index =
                static_cast<size_t>(currentY) *
                    static_cast<size_t>(destination.stride) +
                static_cast<size_t>(currentX);
            destination.pixels[index] = color;
        }
    }
}

} // namespace

#if defined(QUANTUM_LEVEL41_OMAP1710_EFFECTS_ASM) && defined(__SYMBIAN32__)
static_assert(sizeof(void*) == 4,
              "OMAP1710 rain ABI requires 32-bit pointers");
static_assert(offsetof(Omap1710RainStreakArgs, stride) == 4 &&
                  offsetof(Omap1710RainStreakArgs, x) == 16 &&
                  offsetof(Omap1710RainStreakArgs, dx) == 24 &&
                  offsetof(Omap1710RainStreakArgs, color) == 32 &&
                  sizeof(Omap1710RainStreakArgs) == 36,
              "Unexpected OMAP1710 rain-streak ABI layout");
#endif

bool SoftwareRainRenderer::drawStreak(
    const SoftwareRenderSurfaceView& destination,
    QInt32 x,
    QInt32 y,
    QInt32 size,
    QInt32 color)
{
    if (!validDestination(destination) || size < 0) return false;

#if defined(QUANTUM_LEVEL41_OMAP1710_EFFECTS_ASM) && defined(__SYMBIAN32__)
    /* Avoid ARM wraparound for unusual coordinates and keep pathological
       sizes on the clipped 64-bit multiply/divide reference path. */
    const QInt64 endX = static_cast<QInt64>(x) - size / 4;
    const QInt64 endY = static_cast<QInt64>(y) + size;
    if (size <= KMaximumAsmRainStreakSize &&
        endX >= -2147483647LL - 1 && endX <= 2147483647LL &&
        endY >= -2147483647LL - 1 && endY <= 2147483647LL) {
        Omap1710RainStreakArgs args;
        args.destination = destination.pixels;
        args.stride = destination.stride;
        args.width = destination.width;
        args.height = destination.height;
        args.x = x;
        args.y = y;
        args.dx = size / 4;
        args.dy = size;
        args.color = color;
        quantum_omap1710_rain_streak(&args);
        return true;
    }
#endif

    drawStreakReference(destination, x, y, size, color);
    return true;
}

} // namespace Rendering
} // namespace code
