#ifndef QUANTUM_LEVEL41_KEY_MAPPING_H
#define QUANTUM_LEVEL41_KEY_MAPPING_H

namespace code {
namespace Rendering {

/* Symbian e32keys.h TStdScanCode values for the numeric keypad. The 1..9
 * sequence precedes EStdKeyNkp0 rather than following it. */
enum {
    ELevel41NumericOneScanCode = 0x89,
    ELevel41NumericThreeScanCode = 0x8b,
    ELevel41NumericZeroScanCode = 0x92
};

/* Accept both the character delivered by normal keypad events and the
 * standard keypad scan code used by Symbian key-down/up events. */
inline bool isLevel41JumpKey(int keyCode, int scanCode)
{
    return keyCode == static_cast<int>('0') ||
           scanCode == ELevel41NumericZeroScanCode;
}

inline bool isLevel41CameraLookUpKey(int keyCode, int scanCode)
{
    return keyCode == static_cast<int>('1') ||
           scanCode == ELevel41NumericOneScanCode;
}

inline bool isLevel41CameraLookDownKey(int keyCode, int scanCode)
{
    return keyCode == static_cast<int>('3') ||
           scanCode == ELevel41NumericThreeScanCode;
}

} // namespace Rendering
} // namespace code

#endif // QUANTUM_LEVEL41_KEY_MAPPING_H
