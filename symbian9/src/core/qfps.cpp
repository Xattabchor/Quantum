#include "qfps.h"

#include "quantum_integer.h"

#if defined(__SYMBIAN32__)
#include <e32std.h>
#else
#include <sys/time.h>
#endif

namespace {

code::Math::QInt64 currentTimeMillis()
{
#if defined(__SYMBIAN32__)
    TTime now;
    now.UniversalTime();
    return static_cast<code::Math::QInt64>(now.Int64() / 1000);
#else
    struct timeval now;
    gettimeofday(&now, 0);
    return static_cast<code::Math::QInt64>(now.tv_sec) * 1000 +
           static_cast<code::Math::QInt64>(now.tv_usec / 1000);
#endif
}

} // anonymous namespace

namespace code {
namespace utils {

code::Math::QInt32 QFPS::frameTime = 0;
code::Math::QInt64 QFPS::previousFrame = 0;
code::Math::QInt64 QFPS::lastFpsTest = 0;
code::Math::QInt32 QFPS::frames = 0;
code::Math::QInt32 QFPS::currentTime = 0;
code::Math::QInt32 QFPS::fps = 0;

void QFPS::reset()
{
    lastFpsTest = currentTimeMillis();
    currentTime = frames = fps = 0;
    lastFpsTest = previousFrame = 0;
    frameTime = 15;
}

void QFPS::miniReset()
{
    lastFpsTest = previousFrame = 0;
    frames = 30;
}

void QFPS::frame()
{
    if (previousFrame != 0) {
        frameTime = code::Math::javaIntFromLong(
            code::Math::javaLongSub(currentTimeMillis(), previousFrame));
    }

    previousFrame = currentTimeMillis();
    frames = code::Math::javaIntAdd(frames, 1);
    currentTime = code::Math::javaIntAdd(currentTime, frameTime);

    if (code::Math::javaLongSub(previousFrame, lastFpsTest) >= 1000) {
        fps = frames;
        frames = 0;
        lastFpsTest = previousFrame;
    }

    /* QFPS extends FPS and clamps the delta after FPS.frame() has run. */
    if (frameTime < 1) {
        frameTime = 1;
    } else if (frameTime > 200) {
        frameTime = 200;
    }
}

code::Math::QInt32 QFPS::getFps()
{
    return fps;
}

} // namespace utils
} // namespace code
