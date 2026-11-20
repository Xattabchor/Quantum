#include "quantum_math.h"

#if defined(__SYMBIAN32__)
#include <e32math.h>
#include <e32std.h>
#else
#include <math.h>
#endif

namespace code {
namespace Math {

double quantumSqrt(double value)
{
#if defined(__SYMBIAN32__)
    TReal result = 0.0;
    if (::Math::Sqrt(result, static_cast<TReal>(value)) != KErrNone) {
        return 0.0;
    }
    return result;
#else
    return ::sqrt(value);
#endif
}

double quantumSin(double radians)
{
#if defined(__SYMBIAN32__)
    TReal result = 0.0;
    if (::Math::Sin(result, static_cast<TReal>(radians)) != KErrNone) {
        return 0.0;
    }
    return result;
#else
    return ::sin(radians);
#endif
}

double quantumCos(double radians)
{
#if defined(__SYMBIAN32__)
    TReal result = 0.0;
    if (::Math::Cos(result, static_cast<TReal>(radians)) != KErrNone) {
        return 0.0;
    }
    return result;
#else
    return ::cos(radians);
#endif
}

} // namespace Math
} // namespace code
