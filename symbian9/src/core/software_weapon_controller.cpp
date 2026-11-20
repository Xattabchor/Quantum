#include "software_weapon_controller.h"

#include "quantum_integer.h"

#include <string.h>

namespace code {
namespace Rendering {

namespace {

using code::Math::QInt32;
using code::Math::QInt64;
using code::Math::QUint32;

const QInt32 KWeaponConfigV1Bytes = 18;
const QInt32 KWeaponConfigV2Bytes = 26;
const QInt32 KWeaponConfigV3Bytes = 30;
const QInt32 KShotFeedbackDurationMs = 500;
const QInt32 KWeaponFlagTwoHands = 1 << 0;
const QInt32 KWeaponFlagPatronBuy = 1 << 1;
const QInt32 KWeaponFlagCanShoot = 1 << 2;
const QInt32 KWeaponFlagNewFireAnimation = 1 << 3;
const QInt32 KWeaponFlagMeleeAnimation = 1 << 4;
const QInt32 KWeaponFlagLeftHand = 1 << 5;
const QInt32 KWeaponFlagHasZoom = 1 << 6;
const QInt32 KWeaponFlagIgnoreSight = 1 << 7;
const QInt32 KWeaponFlagCustomPosition = 1 << 8;
const QInt32 KWeaponFlagCentreAlign = 1 << 9;
/* Older QWG1/QWG2 files leave bit 10 clear; Java's default is shootLight=true. */
const QInt32 KWeaponFlagDisableShootLight = 1 << 10;
const QInt32 KAllWeaponFlags = (1 << 11) - 1;

QInt32 javaShort(QInt32 value)
{
    const QUint32 bits = static_cast<QUint32>(value) & 0xffffU;
    if (bits <= 0x7fffU) return static_cast<QInt32>(bits);
    return static_cast<QInt32>(bits) - 65536;
}

QInt32 javaByte(QInt32 value)
{
    const QUint32 bits = static_cast<QUint32>(value) & 0xffU;
    if (bits <= 0x7fU) return static_cast<QInt32>(bits);
    return static_cast<QInt32>(bits) - 256;
}

QInt32 readUnsigned16(const unsigned char* data, QInt32 offset)
{
    return static_cast<QInt32>(data[offset]) |
           (static_cast<QInt32>(data[offset + 1]) << 8);
}

QInt32 readSigned16(const unsigned char* data, QInt32 offset)
{
    const QInt32 value = readUnsigned16(data, offset);
    return value <= 0x7fff ? value : value - 65536;
}

QUint32 readUnsigned32(const unsigned char* data, QInt32 offset)
{
    return static_cast<QUint32>(data[offset]) |
           (static_cast<QUint32>(data[offset + 1]) << 8U) |
           (static_cast<QUint32>(data[offset + 2]) << 16U) |
           (static_cast<QUint32>(data[offset + 3]) << 24U);
}

float readFloat32(const unsigned char* data, QInt32 offset)
{
    const QUint32 bits = readUnsigned32(data, offset);
    float value = 0.0f;
    memcpy(&value, &bits, sizeof(value));
    return value;
}

bool validTuning(const SoftwareWeaponTuning& tuning)
{
    return tuning.magazineCapacity > 0 &&
           tuning.magazineCapacity <= 32767 &&
           tuning.initialTotalAmmo >= 0 &&
           tuning.initialTotalAmmo <= 32767 &&
           tuning.delayFrames >= 0 && tuning.delayFrames <= 32767 &&
           tuning.shotTime50Ms >= 0 && tuning.shotTime50Ms <= 32767 &&
           tuning.reloadTime50Ms >= 0 && tuning.reloadTime50Ms <= 32767 &&
           tuning.attackIntensityQ8 > 0 &&
           tuning.attackIntensityQ8 <= 8192 &&
           tuning.customPositionX == tuning.customPositionX &&
           tuning.customPositionX >= -64.0f &&
           tuning.customPositionX <= 64.0f &&
           tuning.muzzleXRatio == tuning.muzzleXRatio &&
           tuning.muzzleXRatio >= 0.0f &&
           tuning.muzzleXRatio <= 64.0f &&
           tuning.muzzleYRatio == tuning.muzzleYRatio &&
           tuning.muzzleYRatio >= 0.0f &&
           tuning.muzzleYRatio <= 64.0f;
}

QInt32 minInt(QInt32 left, QInt32 right)
{
    return left < right ? left : right;
}

const QInt64 KWeaponFrameQ16One = 65536;
const QInt64 KWeaponFrameQ16Idle = -KWeaponFrameQ16One;

QInt64 weaponFrameStep50Ms(QInt32 elapsedMs)
{
    /* SIS .33 sub_29a3d adds (elapsedMs << 16) / 50 to its 64-bit frame. */
    return (static_cast<QInt64>(elapsedMs) * KWeaponFrameQ16One) / 50;
}

QInt64 weaponFrameRecoveryStep(QInt32 elapsedMs)
{
    /* Native constants are 0x000a0000 / 0x000000a6: dt * 10 / 166. */
    return (static_cast<QInt64>(elapsedMs) * 655360) / 166;
}

QInt32 recoilOffset(QInt32 currentOffset, QInt32 shift, QInt32 elapsedMs,
                    QInt32 animationSpeed, QInt32 intensityQ8)
{
    /* Native sub_29a3d computes abs(shift) * 2 * dt / divisor, multiplies
       by Q8 attack intensity, truncates toward zero at bit 8, then adds and
       sign-extends the short result. */
    const QInt32 doubledShift = code::Math::javaShiftLeft(
        code::Math::javaIntAbs(shift), 1);
    const QInt32 integerStep = code::Math::javaIntDiv(
        code::Math::javaIntMul(doubledShift, elapsedMs), animationSpeed);
    const QInt64 scaledQ8 =
        static_cast<QInt64>(integerStep) * intensityQ8 / 256;
    return javaShort(code::Math::javaIntAdd(
        currentOffset, static_cast<QInt32>(scaledQ8)));
}

} // anonymous namespace

SoftwareWeaponTuning::SoftwareWeaponTuning()
    : magazineCapacity(12), initialTotalAmmo(200), delayFrames(8),
      shotTime50Ms(2), reloadTime50Ms(24), damageValue(0),
      attackIntensityQ8(256), twoHands(false), patronBuy(true), canShoot(true),
      newFireAnimation(false), meleeAnimation(false), leftHand(false),
      hasZoom(false), ignoreSightOnDraw(false), customPosition(true),
      centreAlign(false), shootLight(true), customPositionX(1.0f),
      muzzleXRatio(0.84f), muzzleYRatio(0.60f)
{
}

SoftwareWeaponState::SoftwareWeaponState()
    : frameQ16(KWeaponFrameQ16Idle), rounds(0), reserveAmmo(0),
      reloadElapsedMs(-1), dx(0), dy(0), dxMax(0), dyMax(0), widthShift(2),
      heightShift(5), shootIntensity(0), shootLength(0),
      shotFeedbackRemainingMs(0), lastShotDistance(-1),
      lastHitX(0), lastHitY(0), lastHitZ(0), shotsFired(0), worldHits(0),
      shakePending(false), hasShot(false), lastShotHitWorld(false)
{
}

bool SoftwareWeaponController::loadTuning(
    const unsigned char* data,
    QInt32 length,
    SoftwareWeaponTuning& tuning)
{
    if (data == 0 || data[0] != 'Q' || data[1] != 'W' ||
        data[2] != 'G') {
        return false;
    }
    const bool version1 = data[3] == '1' &&
                          length == KWeaponConfigV1Bytes;
    const bool version2 = data[3] == '2' &&
                          length == KWeaponConfigV2Bytes;
    const bool version3 = data[3] == '3' &&
                          length == KWeaponConfigV3Bytes;
    if (!version1 && !version2 && !version3) return false;

    SoftwareWeaponTuning parsed;
    parsed.magazineCapacity = readSigned16(data, 4);
    parsed.delayFrames = readSigned16(data, 6);
    parsed.shotTime50Ms = readSigned16(data, 8);
    parsed.reloadTime50Ms = readSigned16(data, 10);
    parsed.damageValue = readSigned16(data, 12);
    const QInt32 flags = readUnsigned16(data, 14);
    parsed.attackIntensityQ8 = readUnsigned16(data, 16);
    if ((flags & ~KAllWeaponFlags) != 0) return false;

    parsed.twoHands = (flags & KWeaponFlagTwoHands) != 0;
    parsed.patronBuy = (flags & KWeaponFlagPatronBuy) != 0;
    parsed.canShoot = (flags & KWeaponFlagCanShoot) != 0;
    parsed.newFireAnimation = (flags & KWeaponFlagNewFireAnimation) != 0;
    parsed.meleeAnimation = (flags & KWeaponFlagMeleeAnimation) != 0;
    parsed.leftHand = (flags & KWeaponFlagLeftHand) != 0;
    parsed.hasZoom = (flags & KWeaponFlagHasZoom) != 0;
    parsed.ignoreSightOnDraw = (flags & KWeaponFlagIgnoreSight) != 0;
    parsed.customPosition = (flags & KWeaponFlagCustomPosition) != 0;
    parsed.centreAlign = (flags & KWeaponFlagCentreAlign) != 0;
    parsed.shootLight = (flags & KWeaponFlagDisableShootLight) == 0;
    parsed.initialTotalAmmo = parsed.twoHands ? 400 : 200;
    if (version2 || version3) {
        parsed.muzzleXRatio = readFloat32(data, 18);
        parsed.muzzleYRatio = readFloat32(data, 22);
    }
    if (version3) parsed.customPositionX = readFloat32(data, 26);
    if (!validTuning(parsed)) return false;

    tuning = parsed;
    return true;
}

bool SoftwareWeaponController::initialize(
    const SoftwareWeaponTuning& tuning,
    QInt32 dxMax,
    QInt32 dyMax,
    SoftwareWeaponState& state)
{
    if (!validTuning(tuning) || dxMax < 0 || dyMax < 0) return false;

    SoftwareWeaponState initialized;
    initialized.dxMax = javaShort(dxMax);
    initialized.dyMax = javaShort(dyMax);
    const QInt32 totalAmmo = javaShort(tuning.initialTotalAmmo);
    initialized.rounds = javaShort(
        minInt(tuning.magazineCapacity, totalAmmo));
    initialized.reserveAmmo = javaShort(totalAmmo - initialized.rounds);
    state = initialized;
    return true;
}

void SoftwareWeaponController::requestSis33WalkSwayForInput(
    SoftwareWeaponState& state,
    QInt32 turn,
    QInt32 forward)
{
    /* SIS .33 compares only the held Up and Down state bytes before setting
       state+0x50. Left/Right is the turn axis and is not part of this gate
       (Java Player.walk is broader and also shakes on lateral movement). */
    (void)turn;
    if (forward != 0) enableShake(state);
}

void SoftwareWeaponController::enableShake(SoftwareWeaponState& state)
{
#if defined(QUANTUM_LEVEL41_DISABLE_WEAPON_SWAY)
    /* Keep firing recoil in update(); suppress only movement-triggered sway. */
    (void)state;
#else
    state.shakePending = true;
#endif
}

bool SoftwareWeaponController::fire(const SoftwareWeaponTuning& tuning,
                                    SoftwareWeaponState& state)
{
    if (!validTuning(tuning) || !tuning.canShoot) return false;

    /* Weapon.fire() tops up non-purchasable ammo before checking its frame. */
    if (!tuning.patronBuy) {
        state.reserveAmmo = javaShort(
            code::Math::javaIntAdd(state.reserveAmmo, 1));
        if (state.reserveAmmo < 0) state.reserveAmmo = 0;
        state.rounds = javaShort(code::Math::javaIntAdd(state.rounds, 1));
        if (tuning.twoHands) {
            state.reserveAmmo = javaShort(
                code::Math::javaIntAdd(state.reserveAmmo, 1));
            if (state.reserveAmmo < 0) state.reserveAmmo = 0;
            state.rounds = javaShort(
                code::Math::javaIntAdd(state.rounds, 1));
        }
    }

    if (state.frameQ16 != KWeaponFrameQ16Idle) return false;

    if (state.rounds > 0) {
        state.frameQ16 = 0;
        const QInt32 roundsPerShot = tuning.twoHands ? 2 : 1;
        state.rounds = javaShort(
            code::Math::javaIntSub(state.rounds, roundsPerShot));
        state.shotsFired = code::Math::javaIntAdd(state.shotsFired, 1);
        state.hasShot = true;
        state.lastShotHitWorld = false;
        state.lastShotDistance = -1;
        state.lastHitX = 0;
        state.lastHitY = 0;
        state.lastHitZ = 0;
        state.shotFeedbackRemainingMs = KShotFeedbackDurationMs;
        state.shootLength = tuning.shootLight
            ? code::Math::javaIntMul(tuning.shotTime50Ms, 90) : 0;
        state.shootIntensity = state.shootLength;
        return true;
    }

    /* Java Weapon.fire() automatically starts Magazine.reload() when empty. */
    if (state.reserveAmmo != 0 && state.reloadElapsedMs == -1) {
        state.reloadElapsedMs = 0;
    }
    return false;
}

void SoftwareWeaponController::update(const SoftwareWeaponTuning& tuning,
                                      QInt32 elapsedMs,
                                      SoftwareWeaponState& state)
{
    if (elapsedMs < 1) elapsedMs = 1;
    if (elapsedMs > 200) elapsedMs = 200;

    if (tuning.canShoot && state.reloadElapsedMs >= 0) {
        state.reloadElapsedMs = code::Math::javaIntAdd(
            state.reloadElapsedMs, elapsedMs);
        const QInt64 reloadDuration =
            static_cast<QInt64>(tuning.reloadTime50Ms) * 50;
        if (static_cast<QInt64>(state.reloadElapsedMs) > reloadDuration) {
            state.reloadElapsedMs = -1;
            state.rounds = javaShort(
                minInt(tuning.magazineCapacity, state.reserveAmmo));
            state.reserveAmmo = javaShort(
                code::Math::javaIntSub(state.reserveAmmo, state.rounds));
        }
    }

    /* sub_29a3d's frame pair is signed Q16.16: add (dt << 16) / 50,
       compare against a threshold, and recover below -1 by (dt * 0xA0000)
       / 166. The candidate maps those native threshold/reset fields to
       shotTime50Ms/delayFrames; that object-to-config mapping is unproven. */
    if (state.frameQ16 >= 0 && tuning.canShoot) {
        state.frameQ16 += weaponFrameStep50Ms(elapsedMs);
        const QInt64 shotLimitQ16 =
            static_cast<QInt64>(tuning.shotTime50Ms) * KWeaponFrameQ16One;
        if (state.frameQ16 > shotLimitQ16) {
            state.frameQ16 =
                -static_cast<QInt64>(tuning.delayFrames) * KWeaponFrameQ16One;
        }
    }
    if (state.frameQ16 < KWeaponFrameQ16Idle) {
        state.frameQ16 += weaponFrameRecoveryStep(elapsedMs);
        if (state.frameQ16 > KWeaponFrameQ16Idle) {
            state.frameQ16 = KWeaponFrameQ16Idle;
        }
    }

    if (state.shotFeedbackRemainingMs > 0) {
        state.shotFeedbackRemainingMs -= elapsedMs;
        if (state.shotFeedbackRemainingMs < 0) {
            state.shotFeedbackRemainingMs = 0;
        }
    }

    const QInt32 animationSpeed = tuning.attackIntensityQ8 > 256 ? 17 : 29;
    if (state.frameQ16 >= 0 && tuning.canShoot) {
        state.dx = recoilOffset(state.dx, state.widthShift, elapsedMs,
                                animationSpeed, tuning.attackIntensityQ8);
        state.dy = recoilOffset(state.dy, state.heightShift, elapsedMs,
                                animationSpeed, tuning.attackIntensityQ8);
    }

    const bool consumeWalkMarker = state.shakePending &&
        state.frameQ16 >= KWeaponFrameQ16Idle;
    if (consumeWalkMarker) {
        if (animationSpeed != 17) {
            const QInt32 deltaX = javaByte(code::Math::javaIntDiv(
                code::Math::javaIntMul(state.widthShift, elapsedMs), 29));
            const QInt32 deltaY = javaByte(code::Math::javaIntDiv(
                code::Math::javaIntMul(state.heightShift, elapsedMs), 29));
            state.dx = javaShort(code::Math::javaIntAdd(state.dx, deltaX));
            state.dy = javaShort(code::Math::javaIntAdd(state.dy, deltaY));
        }
        state.shakePending = false;
    }

    /* Native sub_29a3d has separate marker and damping paths. Once the
       eligible one-shot marker is set, its impulse (or the high-intensity
       no-impulse branch) goes directly to bounds after clearing the marker;
       ordinary damping runs only when the marker path was not taken. */
    if (!consumeWalkMarker) {
        const QInt32 dampX = code::Math::javaIntDiv(
            code::Math::javaIntDiv(
                code::Math::javaIntMul(
                    code::Math::javaIntSub(0, state.dx), elapsedMs),
                animationSpeed), 6);
        const QInt32 dampY = code::Math::javaIntDiv(
            code::Math::javaIntDiv(
                code::Math::javaIntMul(
                    code::Math::javaIntSub(0, state.dy), elapsedMs),
                animationSpeed), 6);
        state.dx = javaShort(code::Math::javaIntAdd(state.dx, dampX));
        state.dy = javaShort(code::Math::javaIntAdd(state.dy, dampY));
    }

    if (state.dy <= 0) {
        state.dy = 0;
        state.heightShift = javaShort(
            code::Math::javaIntSub(0, state.heightShift));
    }
    if (state.dy > state.dyMax) {
        state.dy = state.dyMax;
        state.heightShift = javaShort(
            code::Math::javaIntSub(0, state.heightShift));
    }
    if (state.dx <= 0) {
        state.dx = 0;
        state.widthShift = javaShort(
            code::Math::javaIntSub(0, state.widthShift));
    }
    if (state.dx >= state.dxMax) {
        state.dx = state.dxMax;
        state.widthShift = javaShort(
            code::Math::javaIntSub(0, state.widthShift));
    }
}

void SoftwareWeaponController::decayShotLight(
    SoftwareWeaponState& state,
    QInt32 elapsedMs)
{
    if (elapsedMs < 1) elapsedMs = 1;
    if (elapsedMs > 200) elapsedMs = 200;
    if (state.shootIntensity > 0) {
        state.shootIntensity = code::Math::javaIntSub(
            state.shootIntensity, elapsedMs);
        if (state.shootIntensity < 0) state.shootIntensity = 0;
    }
}

bool SoftwareWeaponController::isFiring(const SoftwareWeaponState& state)
{
    return state.frameQ16 >= 0;
}

bool SoftwareWeaponController::isReloading(const SoftwareWeaponState& state)
{
    return state.reloadElapsedMs >= 0;
}

QInt32 SoftwareWeaponController::reloadPercentage(
    const SoftwareWeaponTuning& tuning,
    const SoftwareWeaponState& state)
{
    if (!isReloading(state)) return 0;
    if (tuning.reloadTime50Ms <= 0) return 0;
    const QInt64 duration = static_cast<QInt64>(tuning.reloadTime50Ms) * 50;
    QInt64 percentage = static_cast<QInt64>(state.reloadElapsedMs) * 100 /
                        duration;
    if (percentage < 0) percentage = 0;
    if (percentage > 100) percentage = 100;
    return static_cast<QInt32>(percentage);
}

void SoftwareWeaponController::recordShotResult(
    SoftwareWeaponState& state,
    bool hitWorld,
    QInt32 distance,
    QInt32 hitX,
    QInt32 hitY,
    QInt32 hitZ)
{
    state.lastShotHitWorld = hitWorld;
    state.lastShotDistance = hitWorld ? distance : -1;
    state.lastHitX = hitWorld ? hitX : 0;
    state.lastHitY = hitWorld ? hitY : 0;
    state.lastHitZ = hitWorld ? hitZ : 0;
    if (hitWorld) state.worldHits = code::Math::javaIntAdd(state.worldHits, 1);
}

} // namespace Rendering
} // namespace code
