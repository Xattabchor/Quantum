#ifndef QUANTUM_TYPES_H
#define QUANTUM_TYPES_H

/*
 * Shared integer types for the Symbian OS 9 build and the host-side tests.
 * Symbian's TInt/TInt64 are fixed-width types for the supported S60v3
 * baseline; the desktop build uses the C99 fixed-width typedefs.
 */
#if defined(__SYMBIAN32__)
#include <e32std.h>
#else
#include <stdint.h>
#endif

namespace code {
namespace Math {

#if defined(__SYMBIAN32__)
typedef TInt QInt32;
typedef TUint32 QUint32;
typedef TInt64 QInt64;
typedef TUint64 QUint64;
typedef TUint16 QUint16;
#else
typedef int32_t QInt32;
typedef uint32_t QUint32;
typedef int64_t QInt64;
typedef uint64_t QUint64;
typedef uint16_t QUint16;
#endif

typedef char QInt32MustBe32Bits[(sizeof(QInt32) == 4) ? 1 : -1];
typedef char QInt64MustBe64Bits[(sizeof(QInt64) == 8) ? 1 : -1];

} // namespace Math
} // namespace code

#endif // QUANTUM_TYPES_H
