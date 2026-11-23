#ifndef QUANTUM_LEVEL41_WEAPON_SELECTION_H
#define QUANTUM_LEVEL41_WEAPON_SELECTION_H

namespace code {
namespace Rendering {

/*
 * The sample ships four weapon bodies: `weapon.qtx` (index 0, the FN2000 art
 * the walk profile was tuned around) plus `weapon1.qtx`, `weapon2.qtx` and
 * `weapon3.qtx`. All four are stored as QTX2 with format RGB1555.
 *
 * The order follows Java's Arsenal: selection wraps, so `#` from the last
 * weapon returns to the first. Java's default arsenal also contains an
 * unarmed slot (-1). This port keeps four armed slots and no unarmed one:
 * releasing the sprite buffers to show empty hands would only be worth its
 * code path if the sample had a hand sprite and a melee animation, and it
 * does not. Wrapping straight from 3 to 0 keeps every press visibly useful.
 */
enum { KLevel41WeaponCount = 4 };

/* True when `index` addresses one of the shipped bodies. */
inline bool isValidLevel41Weapon(int index)
{
    return index >= 0 && index < KLevel41WeaponCount;
}

/* Next armed slot, wrapping from the last one back to the first. */
inline int level41NextWeapon(int current)
{
    if (!isValidLevel41Weapon(current)) return 0;
    return current + 1 == KLevel41WeaponCount ? 0 : current + 1;
}

} // namespace Rendering
} // namespace code

#endif // QUANTUM_LEVEL41_WEAPON_SELECTION_H
