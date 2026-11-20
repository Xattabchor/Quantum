#include "screen_copy.h"

#include <stddef.h>

namespace code {
namespace Rendering {

namespace {

bool rowOffsetsFit(code::Math::QInt32 stride, code::Math::QInt32 height)
{
    if (height <= 1) {
        return true;
    }

    const size_t maxOffset = static_cast<size_t>(-1);
    const size_t rowCount = static_cast<size_t>(height - 1);
    const size_t rowStride = static_cast<size_t>(stride);
    const size_t rowOffset = rowCount * rowStride;
    return rowOffset / rowCount == rowStride &&
           rowOffset <= maxOffset - static_cast<size_t>(stride);
}

} // anonymous namespace

bool copyArgb8888ToRgb565(const code::Math::QInt32* source,
                          code::Math::QInt32 sourceStride,
                          code::Math::QUint16* destination,
                          code::Math::QInt32 destinationStride,
                          code::Math::QInt32 width,
                          code::Math::QInt32 height)
{
    if (width < 0 || height < 0) {
        return false;
    }
    if (width == 0 || height == 0) {
        return true;
    }
    if (source == 0 || destination == 0 ||
        sourceStride < width || destinationStride < width ||
        !rowOffsetsFit(sourceStride, height) ||
        !rowOffsetsFit(destinationStride, height)) {
        return false;
    }

    for (code::Math::QInt32 y = 0; y < height; ++y) {
        const size_t sourceOffset = static_cast<size_t>(y) *
                                    static_cast<size_t>(sourceStride);
        const size_t destinationOffset = static_cast<size_t>(y) *
                                         static_cast<size_t>(destinationStride);
        const code::Math::QInt32* sourceRow = source + sourceOffset;
        code::Math::QUint16* destinationRow = destination + destinationOffset;

        for (code::Math::QInt32 x = 0; x < width; ++x) {
            const code::Math::QUint32 rgb =
                static_cast<code::Math::QUint32>(sourceRow[x]);
            destinationRow[x] = static_cast<code::Math::QUint16>(
                (((rgb >> 19) & 0x1fU) << 11) |
                (((rgb >> 10) & 0x3fU) << 5) |
                ((rgb >> 3) & 0x1fU));
        }
    }

    return true;
}

} // namespace Rendering
} // namespace code
