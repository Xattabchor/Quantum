#ifndef QUANTUM_SOFTWARE_WEAPON_CONTROLLER_H
#define QUANTUM_SOFTWARE_WEAPON_CONTROLLER_H

#include "quantum_types.h"

namespace code {
namespace Rendering {

/*
 * Gameplay values for the native walk sample's currently equipped weapon.
 * The on-device QWG1/QWG2/QWG3 config is generated from weapons.txt group
 * [0]. QWG2 adds the Java muzzle-flash ratios; QWG3 also carries Java's
 * custom-position X_POS value. When no local
 * JAR-derived config is installed, the constructor supplies a clearly
 * test-only USP profile so the native input/state path remains exercisable.
 * Times preserve the Java Weapon units: shot/reload are 50 ms units, while
 * delayFrames are the Java post-shot 16.6 ms animation-frame units.
 */
struct SoftwareWeaponTuning {
    code::Math::QInt32 magazineCapacity;
    code::Math::QInt32 initialTotalAmmo;
    code::Math::QInt32 delayFrames;
    code::Math::QInt32 shotTime50Ms;
    code::Math::QInt32 reloadTime50Ms;
    code::Math::QInt32 damageValue;
    code::Math::QInt32 attackIntensityQ8;
    bool twoHands;
    bool patronBuy;
    bool canShoot;
    bool newFireAnimation;
    bool meleeAnimation;
    bool leftHand;
    bool hasZoom;
    bool ignoreSightOnDraw;
    bool customPosition;
    bool centreAlign;
    bool shootLight;
    float customPositionX;
    float muzzleXRatio;
    float muzzleYRatio;

    SoftwareWeaponTuning();
};

/* Mutable, allocation-free candidate state. The v1.0.33 initializer/update
 * paths corroborate selected behavioral offsets: frame pair +0/+4, reload
 * timer +0x10, offsets +0x14/+0x18, shifts +0x24/+0x28, and one-shot marker
 * +0x50. This C++ struct is not a byte-layout mirror; full field mapping and
 * source-level equivalence remain unproven. dx/dy and their bounds/shifts use
 * Java-compatible signed-short arithmetic. See SIS33-WALK-SWAY-NATIVE-TRACE.md. */
struct SoftwareWeaponState {
    code::Math::QInt64 frameQ16;
    code::Math::QInt32 rounds;
    code::Math::QInt32 reserveAmmo;
    code::Math::QInt32 reloadElapsedMs;
    code::Math::QInt32 dx;
    code::Math::QInt32 dy;
    code::Math::QInt32 dxMax;
    code::Math::QInt32 dyMax;
    code::Math::QInt32 widthShift;
    code::Math::QInt32 heightShift;
    code::Math::QInt32 shootIntensity;
    code::Math::QInt32 shootLength;
    code::Math::QInt32 shotFeedbackRemainingMs;
    code::Math::QInt32 lastShotDistance;
    code::Math::QInt32 lastHitX;
    code::Math::QInt32 lastHitY;
    code::Math::QInt32 lastHitZ;
    code::Math::QInt32 shotsFired;
    code::Math::QInt32 worldHits;
    bool shakePending;
    bool hasShot;
    bool lastShotHitWorld;

    SoftwareWeaponState();
};

/*
 * Ports the candidate's equipped-weapon firing/reload and selected observations
 * from the v1.0.33 sub_29a3d routine. Confirmed native mechanics include a
 * signed Q16.16 frame, 1..200 ms delta clamp, 17/29 divisor selection, and a
 * one-shot marker set by the Up/Down-only input gate in sub_9489. The complete
 * native object's field mapping and sway/update equivalence are not yet proven;
 * Java Weapon.update is only a secondary cross-check. Inventory, sound, enemy
 * damage, and Java scenes are outside this controller.
 */
class SoftwareWeaponController {
public:
    /* Parse the 18-byte QWG1, 26-byte QWG2, or 30-byte QWG3 config. */
    static bool loadTuning(const unsigned char* data,
                           code::Math::QInt32 length,
                           SoftwareWeaponTuning& tuning);

    static bool initialize(const SoftwareWeaponTuning& tuning,
                           code::Math::QInt32 dxMax,
                           code::Math::QInt32 dyMax,
                           SoftwareWeaponState& state);

    /* SIS v1.0.33 sub_9489 sets its one-shot marker when Up and Down differ
       (forward is nonzero); the Left/Right turn axis does not set the marker.
       Keep turn in the signature so regressions exercise that distinction.
       The optional A/B build suppresses this sway but keeps firing recoil. */
    static void requestSis33WalkSwayForInput(SoftwareWeaponState& state,
                                             code::Math::QInt32 turn,
                                             code::Math::QInt32 forward);
    static void enableShake(SoftwareWeaponState& state);

    /* Returns true only when a round was actually fired. */
    static bool fire(const SoftwareWeaponTuning& tuning,
                     SoftwareWeaponState& state);

    /* Advance the candidate using the SIS-observed 1..200 ms clamp and Q16.16
       frame arithmetic. Other tuning/state field mapping is still reconstructed. */
    static void update(const SoftwareWeaponTuning& tuning,
                       code::Math::QInt32 elapsedMs,
                       SoftwareWeaponState& state);

    /* Java GameScreen fades the muzzle light after rendering each frame. */
    static void decayShotLight(SoftwareWeaponState& state,
                               code::Math::QInt32 elapsedMs);

    static bool isFiring(const SoftwareWeaponState& state);
    static bool isReloading(const SoftwareWeaponState& state);
    static code::Math::QInt32 reloadPercentage(
        const SoftwareWeaponTuning& tuning,
        const SoftwareWeaponState& state);

    /* World-only ray-cast feedback; the sample has no native enemy objects. */
    static void recordShotResult(SoftwareWeaponState& state,
                                 bool hitWorld,
                                 code::Math::QInt32 distance,
                                 code::Math::QInt32 hitX,
                                 code::Math::QInt32 hitY,
                                 code::Math::QInt32 hitZ);
};

} // namespace Rendering
} // namespace code

#endif // QUANTUM_SOFTWARE_WEAPON_CONTROLLER_H
