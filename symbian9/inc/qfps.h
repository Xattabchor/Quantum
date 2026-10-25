#ifndef QUANTUM_QFPS_H
#define QUANTUM_QFPS_H

#include "quantum_types.h"

namespace code {
namespace utils {

/* Native counterpart of the Java FPS/QFPS timing counters and delta clock. */
class QFPS {
public:
    static code::Math::QInt32 frameTime;
    static code::Math::QInt64 previousFrame;
    static code::Math::QInt64 lastFpsTest;
    static code::Math::QInt32 frames;
    static code::Math::QInt32 currentTime;
    static code::Math::QInt32 fps;

    static void reset();
    static void miniReset();
    static void frame();
    static code::Math::QInt32 getFps();
};

} // namespace utils
} // namespace code

#endif // QUANTUM_QFPS_H
