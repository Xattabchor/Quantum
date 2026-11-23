/*
 * The `#` key cycles the four weapon bodies. The wrap rule lives in
 * level41_weapon_selection.h so the device and this test share one definition
 * instead of the test restating arithmetic the device performs.
 */
#include "level41_key_mapping.h"
#include "level41_weapon_selection.h"

#include <cstdio>

namespace {

int gFailures = 0;

void require(bool condition, const char* what)
{
    if (condition) return;
    std::printf("FAIL: %s\n", what);
    ++gFailures;
}

void checkWrap()
{
    using namespace code::Rendering;
    require(KLevel41WeaponCount == 4, "four weapon bodies are shipped");

    require(level41NextWeapon(0) == 1, "0 advances to 1");
    require(level41NextWeapon(1) == 2, "1 advances to 2");
    require(level41NextWeapon(2) == 3, "2 advances to 3");
    require(level41NextWeapon(3) == 0, "the last body wraps back to the first");

    /* A full cycle visits every slot exactly once and returns home. */
    int visited[KLevel41WeaponCount] = {0, 0, 0, 0};
    int slot = 0;
    for (int step = 0; step < KLevel41WeaponCount; ++step) {
        require(isValidLevel41Weapon(slot), "every visited slot is valid");
        ++visited[slot];
        slot = level41NextWeapon(slot);
    }
    require(slot == 0, "a full cycle returns to the starting slot");
    for (int i = 0; i < KLevel41WeaponCount; ++i) {
        require(visited[i] == 1, "each body is selected once per cycle");
    }

    /* An out-of-range index must not index the sprite save arrays. */
    require(!isValidLevel41Weapon(-1), "-1 is not a valid slot");
    require(!isValidLevel41Weapon(KLevel41WeaponCount),
            "one past the end is not a valid slot");
    require(level41NextWeapon(-1) == 0, "an invalid slot recovers to 0");
    require(level41NextWeapon(KLevel41WeaponCount) == 0,
            "an over-range slot recovers to 0");
}

void checkSwitchKey()
{
    using namespace code::Rendering;
    /* Both the character and the scan code must be recognised: normal keypad
       events carry '#', key-down/up events may carry only the scan code. */
    require(isLevel41WeaponSwitchKey(static_cast<int>('#'), 0),
            "'#' character selects the switch");
    require(isLevel41WeaponSwitchKey(0, ELevel41HashScanCode),
            "the hash scan code selects the switch");
    require(!isLevel41WeaponSwitchKey(static_cast<int>('*'), 0),
            "'*' does not switch weapons");
    require(!isLevel41WeaponSwitchKey(0, ELevel41NumericZeroScanCode),
            "the jump key does not switch weapons");
    /* The switch key must not collide with the keys already bound. */
    require(!isLevel41JumpKey(static_cast<int>('#'), ELevel41HashScanCode),
            "'#' is not the jump key");
    require(!isLevel41CameraLookUpKey(static_cast<int>('#'),
                                      ELevel41HashScanCode),
            "'#' is not the look-up key");
    require(!isLevel41CameraLookDownKey(static_cast<int>('#'),
                                        ELevel41HashScanCode),
            "'#' is not the look-down key");
}

} // namespace

int main()
{
    checkWrap();
    checkSwitchKey();
    if (gFailures != 0) {
        std::printf("%d weapon selection checks failed\n", gFailures);
        return 1;
    }
    std::printf("Weapon selection tests passed: # cycles four bodies and wraps.\n");
    return 0;
}
