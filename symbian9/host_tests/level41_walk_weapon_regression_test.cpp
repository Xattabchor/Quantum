#include "collision_mesh_view.h"
#include "level41_key_mapping.h"
#include "omap1710_pdiv.h"
#include "software_mesh_renderer.h"
#include "software_rain_renderer.h"
#include "software_vertex_pipeline.h"
#include "software_walk_controller.h"
#include "software_weapon_controller.h"
#include "software_weapon_renderer.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <vector>

namespace {

code::Rendering::SoftwareWeaponTuning gJavaWeaponTuning;

void require(bool condition, const char* message)
{
    if (!condition) {
        std::fprintf(stderr, "FAIL: %s\n", message);
        std::exit(1);
    }
}

bool near(float value, float expected)
{
    return std::fabs(value - expected) < 0.0001f;
}

int javaShortReference(int value)
{
    const std::uint32_t bits = static_cast<std::uint32_t>(value) & 0xffffU;
    return bits <= 0x7fffU ? static_cast<int>(bits)
                           : static_cast<int>(bits) - 65536;
}

int javaByteReference(int value)
{
    const std::uint32_t bits = static_cast<std::uint32_t>(value) & 0xffU;
    return bits <= 0x7fU ? static_cast<int>(bits)
                         : static_cast<int>(bits) - 256;
}

int javaFloatToIntReference(float value)
{
    if (value != value) return 0;
    if (value >= 2147483648.0f) return 2147483647;
    if (value <= -2147483648.0f) return -2147483647 - 1;
    return static_cast<int>(value);
}

/* Independent, line-for-line host oracle for the animation section of
   Java Gameplay/Weapon.update() in the supplied source archive. */
struct JavaWeaponAnimationReference {
    float frame;
    int dx;
    int dy;
    int dxMax;
    int dyMax;
    int widthShift;
    int heightShift;
    bool shakePending;

    JavaWeaponAnimationReference(int maximumX, int maximumY)
        : frame(-1.0f), dx(0), dy(0), dxMax(maximumX), dyMax(maximumY),
          widthShift(2), heightShift(5), shakePending(false)
    {
    }

    void requestWalkSway(int right, int forward)
    {
#if !defined(QUANTUM_LEVEL41_DISABLE_WEAPON_SWAY)
        if (right != 0 || forward != 0) shakePending = true;
#else
        (void)right;
        (void)forward;
#endif
    }

    void startFire()
    {
        if (frame == -1.0f) frame = 0.0f;
    }

    void update(const code::Rendering::SoftwareWeaponTuning& tuning,
                int elapsedMs)
    {
        if (elapsedMs < 1) elapsedMs = 1;
        if (elapsedMs > 200) elapsedMs = 200;

        const int anima = tuning.attackIntensityQ8 > 256 ? 17 : 29;
        if (frame >= 0.0f && tuning.canShoot) {
            frame += 1.0f * elapsedMs / 50.0f;
            if (frame > static_cast<float>(tuning.shotTime50Ms)) {
                frame = static_cast<float>(
                    javaShortReference(-tuning.delayFrames));
            }
        }
        if (frame < -1.0f) {
            frame += 1.0f * elapsedMs / 16.6f;
            if (frame > -1.0f) frame = -1.0f;
        }

        if (frame >= 0.0f && tuning.canShoot) {
            const int integerStepX =
                ((std::abs(widthShift) << 1) * elapsedMs) / anima;
            const int integerStepY =
                ((std::abs(heightShift) << 1) * elapsedMs) / anima;
            const float intensity =
                static_cast<float>(tuning.attackIntensityQ8) / 256.0f;
            dx = javaShortReference(javaFloatToIntReference(
                static_cast<float>(dx) +
                static_cast<float>(integerStepX) * intensity));
            dy = javaShortReference(javaFloatToIntReference(
                static_cast<float>(dy) +
                static_cast<float>(integerStepY) * intensity));
        }

        if (shakePending && frame >= -1.0f) {
            if (anima != 17) {
                const int deltaX = javaByteReference(
                    (widthShift * elapsedMs) / anima);
                const int deltaY = javaByteReference(
                    (heightShift * elapsedMs) / anima);
                dx = javaShortReference(dx + deltaX);
                dy = javaShortReference(dy + deltaY);
            }
            shakePending = false;
        } else {
            const int dampX = (-dx * elapsedMs) / anima / 6;
            const int dampY = (-dy * elapsedMs) / anima / 6;
            dx = javaShortReference(dx + dampX);
            dy = javaShortReference(dy + dampY);
        }

        if (dy <= 0) {
            dy = 0;
            heightShift = javaShortReference(-heightShift);
        }
        if (dy > dyMax) {
            dy = dyMax;
            heightShift = javaShortReference(-heightShift);
        }
        if (dx <= 0) {
            dx = 0;
            widthShift = javaShortReference(-widthShift);
        }
        if (dx >= dxMax) {
            dx = dxMax;
            widthShift = javaShortReference(-widthShift);
        }
    }
};

void requireWeaponAnimationMatches(
    const code::Rendering::SoftwareWeaponState& actual,
    const JavaWeaponAnimationReference& expected,
    const char* message,
    bool compareOffsets = true)
{
    const float candidateFrame = static_cast<float>(actual.frameQ16) / 65536.0f;
    const bool offsetsMatch = !compareOffsets ||
        (actual.dx == expected.dx && actual.dy == expected.dy &&
         actual.widthShift == expected.widthShift &&
         actual.heightShift == expected.heightShift);
    require(std::fabs(candidateFrame - expected.frame) <= 0.0001f &&
                offsetsMatch &&
                actual.shakePending == expected.shakePending,
            message);
}

void checkWeaponConfigParsing()
{
    const unsigned char qwg1[18] = {
        'Q', 'W', 'G', '1',
        12, 0, 8, 0, 2, 0, 24, 0, 0, 0, 6, 0, 0, 1
    };
    code::Rendering::SoftwareWeaponTuning qwg1Tuning;
    require(code::Rendering::SoftwareWeaponController::loadTuning(
                qwg1, sizeof(qwg1), qwg1Tuning),
            "QWG1 remains supported");
    require(qwg1Tuning.magazineCapacity == 12 &&
                near(qwg1Tuning.muzzleXRatio, 0.84f) &&
                near(qwg1Tuning.muzzleYRatio, 0.60f),
            "QWG1 receives the stable Level 1 muzzle defaults");

    /* Exact 26-byte QWG2 payload decoded from the preserved v1.0.43 SISX; SHA-256 a7ac756bc9daefc1858d59eecc687b1e40783b8ba911ebffebd02780a0ac0b69. */
    unsigned char qwg2[26] = {
        'Q', 'W', 'G', '2',
        30, 0, 2, 0, 4, 0, 15, 0, 85, 0, 70, 1, 0, 1,
        0x3d, 0x0a, 0x57, 0x3f, 0x9a, 0x99, 0x19, 0x3f
    };
    code::Rendering::SoftwareWeaponTuning qwg2Tuning;
    require(code::Rendering::SoftwareWeaponController::loadTuning(
                qwg2, sizeof(qwg2), qwg2Tuning),
            "QWG2 is accepted");
    require(qwg2Tuning.magazineCapacity == 30 &&
                qwg2Tuning.delayFrames == 2 &&
                qwg2Tuning.shotTime50Ms == 4 &&
                qwg2Tuning.reloadTime50Ms == 15 &&
                qwg2Tuning.damageValue == 85 && qwg2Tuning.shootLight &&
                qwg2Tuning.hasZoom && qwg2Tuning.customPosition &&
                !qwg2Tuning.meleeAnimation && !qwg2Tuning.leftHand &&
                !qwg2Tuning.centreAlign &&
                qwg2Tuning.attackIntensityQ8 == 256 &&
                near(qwg2Tuning.muzzleXRatio, 0.84f) &&
                near(qwg2Tuning.muzzleYRatio, 0.60f) &&
                near(qwg2Tuning.customPositionX, 1.0f),
            "QWG2 compatibility fields load and missing X_POS retains the documented fallback");

    /* QWG3 is generated from sym9.zip's Java weapons.txt group [0]. */
    const unsigned char qwg3[30] = {
        'Q', 'W', 'G', '3',
        30, 0, 2, 0, 4, 0, 15, 0, 85, 0, 70, 1, 0, 1,
        0x3d, 0x0a, 0x57, 0x3f, 0x9a, 0x99, 0x19, 0x3f,
        0x1f, 0x85, 0x8b, 0x3f
    };
    code::Rendering::SoftwareWeaponTuning qwg3Tuning;
    require(code::Rendering::SoftwareWeaponController::loadTuning(
                qwg3, sizeof(qwg3), qwg3Tuning),
            "QWG3 is accepted");
    require(qwg3Tuning.hasZoom && qwg3Tuning.customPosition &&
                !qwg3Tuning.meleeAnimation &&
                qwg3Tuning.attackIntensityQ8 == 256 &&
                near(qwg3Tuning.muzzleXRatio, 0.84f) &&
                near(qwg3Tuning.muzzleYRatio, 0.60f) &&
                near(qwg3Tuning.customPositionX, 1.09f),
            "QWG3 preserves Java group-[0] muzzle ratios and X_POS=1.09");
    gJavaWeaponTuning = qwg3Tuning;

    unsigned char qwg3BadPosition[30];
    for (size_t i = 0; i < sizeof(qwg3); ++i) {
        qwg3BadPosition[i] = qwg3[i];
    }
    qwg3BadPosition[26] = 0x00;
    qwg3BadPosition[27] = 0x00;
    qwg3BadPosition[28] = 0xc0;
    qwg3BadPosition[29] = 0x7f;
    require(!code::Rendering::SoftwareWeaponController::loadTuning(
                qwg3BadPosition, sizeof(qwg3BadPosition), qwg3Tuning),
            "QWG3 rejects a non-finite Java custom-position value");

    unsigned char qwg2NoShotLight[26];
    for (size_t i = 0; i < sizeof(qwg2); ++i) qwg2NoShotLight[i] = qwg2[i];
    qwg2NoShotLight[15] |= 0x04; // reserved flags bit 10 disables Java shootLight
    code::Rendering::SoftwareWeaponTuning noShotLightTuning;
    require(code::Rendering::SoftwareWeaponController::loadTuning(
                qwg2NoShotLight, sizeof(qwg2NoShotLight), noShotLightTuning) &&
                !noShotLightTuning.shootLight,
            "QWG2 bit 10 can explicitly disable the Java shot-light effect");

    qwg2[18] = 0x00;
    qwg2[19] = 0x00;
    qwg2[20] = 0xc0;
    qwg2[21] = 0x7f;
    require(!code::Rendering::SoftwareWeaponController::loadTuning(
                qwg2, sizeof(qwg2), qwg2Tuning),
            "non-finite QWG2 muzzle ratios are rejected");
}

void checkJavaFrameTimedWalkMovement()
{
    using namespace code::Rendering;
    code::Collision::CollisionMeshView noCollision;
    SoftwareWalkSettings settings;
    SoftwareWalkState state;

    require(settings.walkSpeed == 150 && settings.turnStepDegrees == 10 &&
                settings.friction == 4 && settings.jumpImpulse == 150 &&
                settings.gravity == -20,
            "walk uses the SIS v1.0.33 per-update scalar and the Java jump cross-check defaults");
    require(SoftwareWalkController::update(
                noCollision, SoftwareWalkInput(1, 0), 50, settings, state) &&
                state.z == -150 && state.velocityZ == -37,
            "the first 50 ms update adds 150 velocity units, then applies grounded friction");
    require(SoftwareWalkController::update(
                noCollision, SoftwareWalkInput(1, 0), 50, settings, state) &&
                state.z == -337 && state.velocityZ == -46,
            "held input accumulates velocity across updates without a per-key displacement step");
    require(SoftwareWalkController::update(
                noCollision, SoftwareWalkInput(1, 0), 50, settings, state) &&
                state.z == -533 && state.velocityZ == -49,
            "sustained 150-scalar walking retains integer velocity and friction behavior");
    require(SoftwareWalkController::update(
                noCollision, SoftwareWalkInput(0, 0), 50, settings, state) &&
                state.z == -582 && state.velocityZ == -12,
            "released input coasts for the current update and then damps by grounded friction");

    SoftwareWalkState shortFrame;
    require(SoftwareWalkController::update(
                noCollision, SoftwareWalkInput(1, 0), 16, settings, shortFrame) &&
                shortFrame.z == -48 && shortFrame.velocityZ == -37,
            "the 150 update scalar still scales displacement by frame time over 50 ms");
    require(SoftwareWalkController::update(
                noCollision, SoftwareWalkInput(1, 0), 16, settings, shortFrame) &&
                shortFrame.z == -107 && shortFrame.velocityZ == -46,
            "short-frame movement preserves integer-truncated Java-style math");

    SoftwareWalkState capped;
    capped.velocityZ = -200;
    require(SoftwareWalkController::update(
                noCollision, SoftwareWalkInput(0, 0), 200, settings, capped) &&
                capped.z == -320,
            "a long frame caps displacement at 80 percent of the 400-unit body radius");
    SoftwareWalkSettings longFrameSettings(150, 10, 2000, 1503, 600, 3000, 4);
    SoftwareWalkState maximumFrame;
    require(SoftwareWalkController::update(
                noCollision, SoftwareWalkInput(1, 0), 250,
                longFrameSettings, maximumFrame) && maximumFrame.z == -600,
            "frame time is clamped to Java QFPS maximum of 200 milliseconds");

    SoftwareWalkState minimumFrame;
    require(SoftwareWalkController::update(
                noCollision, SoftwareWalkInput(1, 0), 0, settings, minimumFrame) &&
                minimumFrame.z == -3,
            "frame time is clamped to Java QFPS minimum of one millisecond");

    SoftwareWalkState strafe;
    require(SoftwareWalkController::update(
                noCollision, SoftwareWalkInput(0, 1), 50, settings, strafe) &&
                strafe.x == 150 && strafe.z == 0,
            "right-axis acceleration follows Java Character.moveX in the yaw-zero basis");
    SoftwareWalkState turnedWalk;
#if defined(QUANTUM_LEVEL41_TURN_INERTIA)
    require(SoftwareWalkController::turn(1, settings, turnedWalk) &&
                turnedWalk.yawDegrees == 0 &&
                turnedWalk.turnVelocityDegreesPerSecondQ16 == 60 * 65536,
            "fallback left-turn event starts an angular impulse without snapping yaw");
    require(SoftwareWalkController::update(
                noCollision, SoftwareWalkInput(1, 0), 50,
                settings, turnedWalk) && turnedWalk.x < 0 &&
                turnedWalk.z < 0 && turnedWalk.yawDegrees == 2 &&
                turnedWalk.turnVelocityDegreesPerSecondQ16 == 42 * 65536,
            "walking follows the smoothly integrated heading while the turn impulse damps");
#else
    require(SoftwareWalkController::turn(1, settings, turnedWalk) &&
                turnedWalk.yawDegrees == 10 &&
                turnedWalk.turnVelocityDegreesPerSecondQ16 == 0,
            "event-only fallback applies one configured discrete left-turn step");
    require(SoftwareWalkController::update(
                noCollision, SoftwareWalkInput(1, 0, 1), 50,
                settings, turnedWalk) && turnedWalk.yawDegrees == 20 &&
                turnedWalk.turnVelocityDegreesPerSecondQ16 == 0,
            "held turning applies SIS v1.0.33's 10-degree step scaled by frame time / 50");
#endif
}

void checkLevel41CameraPitchControls()
{
    using namespace code::Math;
    using namespace code::Rendering;

    require(ELevel41NumericOneScanCode == 0x89 &&
                ELevel41NumericThreeScanCode == 0x8b &&
                ELevel41NumericZeroScanCode == 0x92,
            "key mappings use the Symbian numeric-keypad scan-code values");
    require(isLevel41CameraLookUpKey('1', 0),
            "character-code keypad 1 is recognized as camera-look up");
    require(isLevel41CameraLookUpKey(0, ELevel41NumericOneScanCode),
            "Symbian numeric-keypad 1 scan code is recognized as camera-look up");
    require(isLevel41CameraLookDownKey('3', 0),
            "character-code keypad 3 is recognized as camera-look down");
    require(isLevel41CameraLookDownKey(0, ELevel41NumericThreeScanCode),
            "Symbian numeric-keypad 3 scan code is recognized as camera-look down");
    require(!isLevel41CameraLookUpKey('3', ELevel41NumericThreeScanCode) &&
                !isLevel41CameraLookDownKey('1', ELevel41NumericOneScanCode),
            "camera-look directions do not map the opposite keypad keys");
    require(!isLevel41CameraLookUpKey(0, 0x93) &&
                !isLevel41CameraLookDownKey(0, 0x95),
            "full-stop and backlight scan codes do not trigger camera look");

    QInt32 pitchDegrees = 0;
    QInt32 pitchRemainder = 0;
    require(SoftwareWalkController::updateCameraPitch(
                1, 17, pitchDegrees, pitchRemainder) &&
                pitchDegrees == 1,
            "event-only keypad 1 press produces a visible one-degree tap");
    pitchDegrees = 0;
    pitchRemainder = 0;
    require(SoftwareWalkController::updateCameraPitch(
                -1, 17, pitchDegrees, pitchRemainder) &&
                pitchDegrees == -1,
            "event-only keypad 3 press produces a visible downward tap");
    pitchDegrees = 0;
    pitchRemainder = 0;
    require(SoftwareWalkController::updateCameraPitch(
                1, 50, pitchDegrees, pitchRemainder) &&
                pitchDegrees == 3 && pitchRemainder == 0,
            "held keypad 1 smoothly pitches the camera upward at 60 degrees per second");
    require(SoftwareWalkController::updateCameraPitch(
                -1, 25, pitchDegrees, pitchRemainder) &&
                pitchDegrees == 2 && pitchRemainder == -500,
            "keypad 3 reverses look direction and retains fractional frame time");
    require(SoftwareWalkController::updateCameraPitch(
                -1, 25, pitchDegrees, pitchRemainder) &&
                pitchDegrees == 0 && pitchRemainder == 0,
            "fractional camera pitch integrates consistently across frames");

    pitchDegrees = 55;
    require(SoftwareWalkController::updateCameraPitch(
                1, 200, pitchDegrees, pitchRemainder) &&
                pitchDegrees == 60 && pitchRemainder == 0,
            "camera pitch clamps upward at 60 degrees");
    require(SoftwareWalkController::updateCameraPitch(
                -1, 50, pitchDegrees, pitchRemainder) &&
                pitchDegrees == 57,
            "camera can look down immediately after reaching the upward clamp");
    pitchDegrees = -55;
    pitchRemainder = 0;
    require(SoftwareWalkController::updateCameraPitch(
                -1, 200, pitchDegrees, pitchRemainder) &&
                pitchDegrees == -60 && pitchRemainder == 0,
            "camera pitch clamps downward at minus 60 degrees");
    require(SoftwareWalkController::updateCameraPitch(
                1, 50, pitchDegrees, pitchRemainder) &&
                pitchDegrees == -57,
            "camera can look up immediately after reaching the downward clamp");
    require(!SoftwareWalkController::updateCameraPitch(
                2, 50, pitchDegrees, pitchRemainder),
            "camera-look controller rejects an invalid axis");

    SoftwareWalkSettings settings;
    SoftwareWalkState cameraState;
    Matrix worldToCamera;
    SoftwareWalkController::buildWorldToCamera(
        cameraState, settings, worldToCamera, 30);
    require(worldToCamera.m12 > 0 && worldToCamera.m21 < 0,
            "positive camera pitch rotates the view upward");
    SoftwareRenderVertex cameraPosition(
        cameraState.x,
        javaIntAdd(cameraState.feetY, settings.eyeHeight),
        cameraState.z, 0, 0, 0, 0, 0);
    SoftwareRenderVertex cameraPositionInView;
    SoftwareVertexPipeline::transformVertex(
        cameraPosition, &worldToCamera, cameraPositionInView);
    require(cameraPositionInView.x >= -2 && cameraPositionInView.x <= 2 &&
                cameraPositionInView.y >= -2 && cameraPositionInView.y <= 2 &&
                cameraPositionInView.z >= -2 && cameraPositionInView.z <= 2,
            "camera pitch keeps the camera anchored at its world position");
}

void checkLevel41JumpAndButtonZero()
{
    using namespace code::Collision;
    using namespace code::Math;
    using namespace code::Rendering;

    require(isLevel41JumpKey('0', 0),
            "character-code button 0 is recognized as the jump key");
    require(isLevel41JumpKey(0, ELevel41NumericZeroScanCode),
            "Symbian EStdKeyNkp0 scan code is recognized as the jump key");
    require(!isLevel41JumpKey('5', 0x8d) && !isLevel41JumpKey(0, 0),
            "other keypad keys do not trigger jumping");

    const Vector3D floorVertices[4] = {
        Vector3D(-2000, 0, -2000),
        Vector3D(2000, 0, -2000),
        Vector3D(2000, 0, 2000),
        Vector3D(-2000, 0, 2000)
    };
    CollisionPolygon floor;
    floor.setContiguousVertices(floorVertices, 4);
    floor.normal.set(0, -4096, 0);
    const CollisionMeshView floorCollision(&floor, 1);
    SoftwareWalkSettings settings;
    SoftwareWalkState player;
    player.velocityZ = -50;

    require(SoftwareWalkController::update(
                floorCollision, SoftwareWalkInput(0, 0, 0, 1),
                50, settings, player) && !player.onFloor &&
                player.feetY == 160 && player.velocityY == 160 &&
                player.z == -60,
            "a grounded 0 pulse launches with the Java jump impulse and scales existing walking velocity");
    require(SoftwareWalkController::update(
                floorCollision, SoftwareWalkInput(0, 0, 0, 1),
                50, settings, player) && !player.onFloor &&
                player.feetY == 300 && player.velocityY == 140 &&
                player.z == -120,
            "a repeated 0 pulse in midair does not apply a second jump impulse");

    int airborneFrames = 0;
    while (!player.onFloor && airborneFrames < 40) {
        require(SoftwareWalkController::update(
                    floorCollision, SoftwareWalkInput(), 50,
                    settings, player),
                "airborne update succeeds while falling back to the floor");
        ++airborneFrames;
    }
    require(player.onFloor && player.feetY == 0 && player.velocityY < 0,
            "gravity returns the player to the collision floor and grounded friction is applied");
    require(SoftwareWalkController::update(
                floorCollision, SoftwareWalkInput(0, 0, 0, 1),
                50, settings, player) && !player.onFloor &&
                player.feetY > 0,
            "a new 0 press launches again after landing");

    const Vector3D roofVertices[4] = {
        Vector3D(-1000, 2400, -1000),
        Vector3D(-1000, 2400, 1000),
        Vector3D(1000, 2400, 1000),
        Vector3D(1000, 2400, -1000)
    };
    CollisionPolygon floorAndRoof[2];
    floorAndRoof[0] = floor;
    floorAndRoof[1].setContiguousVertices(roofVertices, 4);
    floorAndRoof[1].normal.set(0, 4096, 0);
    const CollisionMeshView roofCollision(floorAndRoof, 2);
    SoftwareWalkState roofedPlayer;
    QInt32 maximumFeetY = 0;
    int frame;
    for (frame = 0; frame < 14; ++frame) {
        require(SoftwareWalkController::update(
                    roofCollision,
                    SoftwareWalkInput(0, 0, 0, frame == 0 ? 1 : 0),
                    50, settings, roofedPlayer),
                "jump update succeeds under a low ceiling");
        if (roofedPlayer.feetY > maximumFeetY) {
            maximumFeetY = roofedPlayer.feetY;
        }
    }
    require(maximumFeetY <= 497 && roofedPlayer.underRoof,
            "sphere collision stops the jump at the ceiling and retains roof detection");
}

#if defined(QUANTUM_LEVEL41_TURN_INERTIA)
void checkTurnInertia()
{
    using namespace code::Rendering;
    code::Collision::CollisionMeshView noCollision;
    SoftwareWalkSettings settings;
    SoftwareWalkState turning;

    require(SoftwareWalkController::update(
                noCollision, SoftwareWalkInput(0, 0, 1), 50,
                settings, turning) && turning.yawDegrees == 0 &&
                turning.turnVelocityDegreesPerSecondQ16 == 12 * 65536,
            "held turning ramps up angular velocity instead of stepping yaw");
    require(SoftwareWalkController::update(
                noCollision, SoftwareWalkInput(0, 0, 1), 50,
                settings, turning) && turning.yawDegrees == 1 &&
                turning.turnVelocityDegreesPerSecondQ16 == 24 * 65536,
            "fractional yaw accumulates smoothly while turn acceleration continues");
    require(SoftwareWalkController::update(
                noCollision, SoftwareWalkInput(0, 0, 1), 50,
                settings, turning) && turning.yawDegrees == 3 &&
                turning.turnVelocityDegreesPerSecondQ16 == 36 * 65536,
            "turn speed continues its controlled ramp while the key is held");
    require(SoftwareWalkController::update(
                noCollision, SoftwareWalkInput(), 50, settings, turning) &&
                turning.yawDegrees == 4 &&
                turning.turnVelocityDegreesPerSecondQ16 == 18 * 65536,
            "released turning coasts briefly with damped angular inertia");
    require(SoftwareWalkController::update(
                noCollision, SoftwareWalkInput(), 50, settings, turning) &&
                turning.yawDegrees == 4 &&
                turning.turnVelocityDegreesPerSecondQ16 == 0,
            "turn inertia settles to rest after the deceleration interval");

    SoftwareWalkState wrapped;
    wrapped.yawDegrees = 359;
    require(SoftwareWalkController::turn(1, settings, wrapped) &&
                SoftwareWalkController::update(
                    noCollision, SoftwareWalkInput(), 50,
                    settings, wrapped) && wrapped.yawDegrees == 1,
            "inertial turn integration wraps cleanly through 360 degrees");
}
#else
void checkSis33FrameTimedTurn()
{
    using namespace code::Rendering;
    code::Collision::CollisionMeshView noCollision;
    SoftwareWalkSettings settings;
    SoftwareWalkState turning;

    require(SoftwareWalkController::update(
                noCollision, SoftwareWalkInput(0, 0, 1), 50,
                settings, turning) && turning.yawDegrees == 10 &&
                turning.turnVelocityDegreesPerSecondQ16 == 0,
            "SIS v1.0.33 applies one 10-degree turn over a 50 ms frame");
    require(SoftwareWalkController::update(
                noCollision, SoftwareWalkInput(0, 0, -1), 16,
                settings, turning) && turning.yawDegrees == 7 &&
                turning.turnVelocityDegreesPerSecondQ16 == 0,
            "SIS v1.0.33 turn delta scales by elapsed time with integer truncation");
    require(SoftwareWalkController::update(
                noCollision, SoftwareWalkInput(0, 0, 1), 200,
                settings, turning) && turning.yawDegrees == 47,
            "long but clamped frames retain the native frame-scaled yaw rate");

    SoftwareWalkState wrapped;
    wrapped.yawDegrees = 350;
    require(SoftwareWalkController::update(
                noCollision, SoftwareWalkInput(0, 0, 1), 200,
                settings, wrapped) && wrapped.yawDegrees == 30,
            "SIS frame-scaled turning wraps through 360 degrees");
}
#endif

void checkJavaWalkCollision()
{
    using namespace code::Collision;
    using namespace code::Math;
    using namespace code::Rendering;

    const Vector3D wallVertices[4] = {
        Vector3D(-1000, 0, -600),
        Vector3D(-1000, 3000, -600),
        Vector3D(1000, 3000, -600),
        Vector3D(1000, 0, -600)
    };
    const Vector3D floorVertices[4] = {
        Vector3D(-2000, 0, -2000),
        Vector3D(2000, 0, -2000),
        Vector3D(2000, 0, 2000),
        Vector3D(-2000, 0, 2000)
    };
    CollisionPolygon polygons[2];
    polygons[0].setContiguousVertices(wallVertices, 4);
    polygons[0].normal.set(0, 0, -4096);
    polygons[1].setContiguousVertices(floorVertices, 4);
    polygons[1].normal.set(0, -4096, 0);
    const CollisionMeshView collision(polygons, 2);
    SoftwareWalkSettings settings;
    SoftwareWalkState state;

    for (int frame = 0; frame < 8; ++frame) {
        require(SoftwareWalkController::update(
                    collision, SoftwareWalkInput(1, 0), 50, settings, state),
                "frame-timed movement resolves against wall and floor collision geometry");
    }
    require(state.z >= -200 && state.onFloor,
            "held Java-style input preserves player-radius wall clearance on the floor");
}

void checkRoofDetectionForRainSuppression()
{
    using namespace code::Collision;
    using namespace code::Math;
    using namespace code::Rendering;

    const Vector3D roofVertices[4] = {
        Vector3D(-1000, 2400, -1000),
        Vector3D(-1000, 2400, 1000),
        Vector3D(1000, 2400, 1000),
        Vector3D(1000, 2400, -1000)
    };
    CollisionPolygon roof;
    roof.setContiguousVertices(roofVertices, 4);
    roof.normal.set(0, 4096, 0);
    const CollisionMeshView roofCollision(&roof, 1);
    const CollisionMeshView noCollision;
    SoftwareWalkSettings settings;
    SoftwareWalkState player;

    require(SoftwareWalkController::update(
                roofCollision, SoftwareWalkInput(), 1, settings, player),
            "roof query succeeds at the spawn point");
    require(player.underRoof,
            "an upward-facing ceiling above the player's eye height marks the camera under roof");

    SoftwareWalkState outsideRoof(2500, 0, 0, 0);
    require(SoftwareWalkController::update(
                roofCollision, SoftwareWalkInput(), 1, settings, outsideRoof) &&
                !outsideRoof.underRoof,
            "roof outside the player's XZ bounds does not suppress rain");

    require(SoftwareWalkController::update(
                noCollision, SoftwareWalkInput(), 1, settings, player) &&
                !player.underRoof,
            "leaving collision geometry clears the cached under-roof state");
}

/* SIS v1.0.33: sub_9489 sets byte +0x50 via sub_29885 when held Up/Down
   states differ. sub_29a3d receives that object as r2, reads/clears its marker
   through [r4+0x50], and separately reads [r5+0x50] from r0's distinct object.
   This checks the observed forward/back-only trigger; the candidate's offsets
   and animation math are independently cross-checked against Java, not claimed
   as a full native sway port. */
void checkSis33SwayTriggerAndCandidateMath()
{
    using namespace code::Rendering;
    SoftwareWeaponTuning tuning;
    SoftwareWeaponState state;
    require(SoftwareWeaponController::initialize(tuning, 100, 100, state),
            "weapon state initializes with Java's default 2/5 shifts");

    SoftwareWeaponState turnOnly;
    require(SoftwareWeaponController::initialize(
                tuning, 100, 100, turnOnly),
            "turn-only weapon state initializes");
    SoftwareWeaponController::requestSis33WalkSwayForInput(turnOnly, 1, 0);
    SoftwareWeaponController::update(tuning, 50, turnOnly);
    require(turnOnly.dx == 0 && turnOnly.dy == 0 &&
                !turnOnly.shakePending,
            "SIS v1.0.33 Left/Right-only turning does not set the sway marker");

#if defined(QUANTUM_LEVEL41_DISABLE_WEAPON_SWAY)
    SoftwareWeaponController::requestSis33WalkSwayForInput(state, 0, 1);
    SoftwareWeaponController::update(tuning, 50, state);
    require(state.dx == 0 && state.dy == 0 && !state.shakePending,
            "the isolated A/B build ignores movement-triggered weapon sway");
    require(SoftwareWeaponController::fire(tuning, state),
            "the isolated A/B build still fires normally");
    SoftwareWeaponController::update(tuning, 50, state);
    require(state.shotsFired == 1 && state.dx > 0 && state.dy > 0,
            "firing recoil remains active while walking sway is disabled");
    require(state.shootIntensity == tuning.shotTime50Ms * 90,
            "the independent Java shot-light effect remains active");
#else
    require(state.dx == 0 && state.dy == 0,
            "weapon offsets initialize at rest");

    SoftwareWeaponController::requestSis33WalkSwayForInput(state, 0, 1);
    SoftwareWeaponController::update(tuning, 50, state);
    require(state.dx == 3 && state.dy == 8 && !state.shakePending,
            "50 ms marker impulse matches the native /29 and signed-byte offset formula");

    SoftwareWeaponController::update(tuning, 50, state);
    require(state.dx == 3 && state.dy == 6,
            "idle sway offsets damp by frame-time / divisor / six");

    SoftwareWeaponController::requestSis33WalkSwayForInput(state, 0, 1);
    SoftwareWeaponController::update(tuning, 50, state);
    require(state.dx == 6 && state.dy == 14,
            "Up/Down input re-enables the native one-shot impulse after damping");

    SoftwareWeaponTuning fastAnimation = tuning;
    fastAnimation.attackIntensityQ8 = 512;
    SoftwareWeaponState fastState;
    require(SoftwareWeaponController::initialize(
                fastAnimation, 100, 100, fastState),
            "high-intensity sway state initializes");
    fastState.dx = 29;
    fastState.dy = 34;
    SoftwareWeaponController::requestSis33WalkSwayForInput(fastState, 0, 1);
    SoftwareWeaponController::update(fastAnimation, 50, fastState);
    require(fastState.dx == 29 && fastState.dy == 34 &&
                fastState.widthShift == 2 && fastState.heightShift == 5 &&
                !fastState.shakePending,
            "raw intensity above 256 consumes the marker without impulse or damping");

    SoftwareWeaponState boundedState;
    require(SoftwareWeaponController::initialize(
                tuning, 10, 10, boundedState),
            "bounded weapon sway state initializes");
    SoftwareWeaponController::requestSis33WalkSwayForInput(
        boundedState, 0, 1);
    SoftwareWeaponController::update(tuning, 50, boundedState);
    SoftwareWeaponController::requestSis33WalkSwayForInput(
        boundedState, 0, 1);
    SoftwareWeaponController::update(tuning, 50, boundedState);
    require(boundedState.dx == 6 && boundedState.dy == 10 &&
                boundedState.heightShift == -5,
            "native-style bounds clamp offsets and reverse shifts at an endpoint");

    SoftwareWeaponState crossedZero;
    require(SoftwareWeaponController::initialize(
                tuning, 100, 10, crossedZero),
            "native marker/damping edge state initializes");
    crossedZero.dy = 10;
    crossedZero.heightShift = -5;
    SoftwareWeaponController::requestSis33WalkSwayForInput(
        crossedZero, 0, 1);
    SoftwareWeaponController::update(tuning, 200, crossedZero);
    require(crossedZero.dx == 13 && crossedZero.dy == 0 &&
                crossedZero.heightShift == 5 && !crossedZero.shakePending,
            "native marker impulse bypasses damping, clamps at zero, and reverses the vertical shift");
#endif
}

void checkSis33Q16FrameArithmeticWithCandidateTuning()
{
    using namespace code::Rendering;
    SoftwareWeaponTuning tuning;
    SoftwareWeaponState state;
    require(SoftwareWeaponController::initialize(tuning, 100, 100, state),
            "candidate's Q16.16 weapon state initializes");
    require(state.frameQ16 == -65536,
            "candidate idle sentinel is exactly -1.0 in Q16.16");
    require(SoftwareWeaponController::fire(tuning, state) &&
                state.frameQ16 == 0,
            "candidate fire transition writes a zero Q16.16 frame");

    SoftwareWeaponController::update(tuning, 50, state);
    require(state.frameQ16 == 65536 &&
                SoftwareWeaponController::isFiring(state),
            "one 50 ms update advances the Q16.16 frame by exactly one");
    SoftwareWeaponController::update(tuning, 50, state);
    require(state.frameQ16 == 131072 &&
                SoftwareWeaponController::isFiring(state),
            "candidate Q16.16 frame remains live through its configured threshold");
    SoftwareWeaponController::update(tuning, 50, state);
    require(state.frameQ16 == -326891 &&
                !SoftwareWeaponController::isFiring(state),
            "candidate rollover applies the observed dt*10/166 recovery in the same update");
    SoftwareWeaponController::update(tuning, 50, state);
    require(state.frameQ16 == -129494,
            "native post-shot recovery retains its Q16.16 remainder");
    SoftwareWeaponController::update(tuning, 50, state);
    require(state.frameQ16 == -65536,
            "native post-shot recovery clamps exactly at the -1 idle sentinel");
}

void checkJavaWeaponAnimationCrossCheck()
{
    using namespace code::Rendering;
    SoftwareWeaponTuning tuning = gJavaWeaponTuning;
    SoftwareWeaponState actual;
    JavaWeaponAnimationReference expected(220, 240);
    require(SoftwareWeaponController::initialize(
                tuning, 220, 240, actual),
            "Java-profile weapon animation state initializes");
    actual.rounds = 30000;

    std::uint32_t random = 0x41c0ffeeU;
    for (int frameIndex = 0; frameIndex < 2500; ++frameIndex) {
        random = random * 1664525U + 1013904223U;
        const int elapsedMs = 1 + static_cast<int>(random % 200U);
        /* Keep the SIS-specific marker path out of this sequence. Its trigger
           and impulse/damping edge cases are covered in the dedicated tests
           above; this randomized loop cross-checks the shared Java frame,
           recoil, recovery, damping, and bounds behavior. */
        random = random * 1664525U + 1013904223U;
        if ((random & 7U) == 0U) {
            const bool expectedFire = tuning.canShoot &&
                actual.frameQ16 == -65536 && actual.rounds > 0;
            const bool fired = SoftwareWeaponController::fire(tuning, actual);
            require(fired == expectedFire,
                    "Java fire cooldown accepts shots only at the exact -1 frame sentinel");
            if (expectedFire) expected.startFire();
        }

        SoftwareWeaponController::update(tuning, elapsedMs, actual);
        expected.update(tuning, elapsedMs);
        requireWeaponAnimationMatches(
            actual, expected,
            "Q16.16 frame and no-marker offsets match Java Weapon.update");
    }

    SoftwareWeaponTuning melee = tuning;
    melee.meleeAnimation = true;
    melee.attackIntensityQ8 = 1280; // Java WeaponCreator sets attackIntensity=5.0F.
    SoftwareWeaponState meleeActual;
    JavaWeaponAnimationReference meleeExpected(300, 300);
    require(SoftwareWeaponController::initialize(
                melee, 300, 300, meleeActual),
            "Java melee-animation state initializes with the wider bounds");
    require(SoftwareWeaponController::fire(melee, meleeActual),
            "the Java melee-animation profile fires");
    meleeExpected.startFire();
    SoftwareWeaponController::requestSis33WalkSwayForInput(
        meleeActual, 0, 1);
    meleeExpected.requestWalkSway(0, 1);
    SoftwareWeaponController::update(melee, 50, meleeActual);
    meleeExpected.update(melee, 50);
    requireWeaponAnimationMatches(
        meleeActual, meleeExpected,
        "melee recoil follows Java's integer-then-float formula and consumes sway");
#if defined(QUANTUM_LEVEL41_DISABLE_WEAPON_SWAY)
    require(meleeActual.dx == 29 && meleeActual.dy == 74 &&
                !meleeActual.shakePending,
            "Java attackIntensity=5 recoil remains active with the A/B sway damping path");
#else
    require(meleeActual.dx == 55 && meleeActual.dy == 145 &&
                !meleeActual.shakePending,
            "Java attackIntensity=5 uses the 17 divisor, fivefold recoil, and no walk sway");
#endif

    SoftwareWeaponTuning delayed = tuning;
    delayed.delayFrames = 8;
    SoftwareWeaponState delayedActual;
    JavaWeaponAnimationReference delayedExpected(200, 220);
    require(SoftwareWeaponController::initialize(
                delayed, 200, 220, delayedActual),
            "long-delay animation state initializes");
    require(SoftwareWeaponController::fire(delayed, delayedActual),
            "long-delay animation state starts firing");
    delayedExpected.startFire();
    for (int frameIndex = 0; frameIndex < 5; ++frameIndex) {
        SoftwareWeaponController::update(delayed, 50, delayedActual);
        delayedExpected.update(delayed, 50);
        requireWeaponAnimationMatches(
            delayedActual, delayedExpected,
            "Java shotTime transition and same-update post-shot recovery match");
    }
    delayed.canShoot = false;
    for (int frameIndex = 0; frameIndex < 2; ++frameIndex) {
        SoftwareWeaponController::update(delayed, 50, delayedActual);
        delayedExpected.update(delayed, 50);
        requireWeaponAnimationMatches(
            delayedActual, delayedExpected,
            "Java post-shot recovery continues even if canShoot becomes false");
    }
    require(delayedActual.frameQ16 == -65536 &&
                !SoftwareWeaponController::isFiring(delayedActual),
            "Q16.16 post-shot recovery returns to the exact -1 idle sentinel");
}

void checkJavaShotLightState()
{
    using namespace code::Rendering;
    SoftwareWeaponState state;
    require(SoftwareWeaponController::initialize(
                gJavaWeaponTuning, 100, 100, state),
            "shot-light state initializes from the packaged weapon profile");
    require(SoftwareWeaponController::fire(gJavaWeaponTuning, state),
            "a loaded weapon fires for the shot-light test");
    require(state.shootIntensity == 360 && state.shootLength == 360,
            "Java shot light starts at shotTime multiplied by 90");
    SoftwareWeaponController::decayShotLight(state, 112);
    require(state.shootIntensity == 248 && state.shootLength == 360,
            "shot intensity decays by frame time without changing its length");
    SoftwareWeaponController::decayShotLight(state, 200);
    SoftwareWeaponController::decayShotLight(state, 200);
    require(state.shootIntensity == 0,
            "shot light clamps to zero after its Java-style fade");
}

void checkWorldSpaceShotLighting()
{
    using namespace code::Math;
    using namespace code::Rendering;

    const QInt32 texturePixel = javaIntFromUnsigned(0x00ffffffU);
    const SoftwareTextureView texture(
        &texturePixel, 1, 1, 1, false, false);
    const Vector3D vertices[3] = {
        Vector3D(1000, -400, -800),
        Vector3D(1000, -400, -1200),
        Vector3D(1000, 400, -1000)
    };
    SoftwareMeshPolygon polygon;
    polygon.vertexCount = 3;
    polygon.vertexIndices[0] = 0;
    polygon.vertexIndices[1] = 1;
    polygon.vertexIndices[2] = 2;
    polygon.normalX = 4096;
    polygon.normalY = 0;
    polygon.normalZ = 0;
    polygon.shading = ESoftwareMeshVertexGray;
    for (int corner = 0; corner < 3; ++corner) {
        polygon.vertexColor[corner][0] = 0;
        polygon.textureU[corner] = 0;
        polygon.textureV[corner] = 0;
    }
    SoftwareMeshMaterial material;
    material.mipLevels = &texture;
    material.mipLevelCount = 1;
    material.textureAlphaMixing = false;
    const SoftwareMeshView mesh(
        vertices, 3, &polygon, 1, &material, 1);

    Matrix cameraWorld;
    cameraWorld.setPosition(900, 0, 0);
    cameraWorld.invert();
    const SoftwareMeshInstance instance(&mesh, &cameraWorld);
    SoftwareRenderVertex cameraVertices[3];
    SoftwareMeshRenderItem queue[1];
    const SoftwareMeshRenderWorkspace workspace(
        cameraVertices, 3, queue, 1);
    std::vector<QInt32> pixels(64 * 64, 0);
    const SoftwareRenderSurfaceView surface(
        pixels.data(), static_cast<QInt32>(pixels.size()), 64, 64, 64);

    SoftwareMeshRenderOptions options;
    options.projection = SoftwareProjectionParameters(32, 32, 64, 64);
    options.projectionPath = ESoftwareMeshProjectionDirectX7;
    options.quality.setStandardDrawMode(9);
    options.quality.setPerspectiveQuality(2);
    options.quality.setMipMapping(true);
    options.triangleSettings.fogDistance = 1;
    options.cameraWorldX = 900;
    options.cameraWorldY = 0;
    options.cameraWorldZ = 0;
    options.shootLength = 360;

    SoftwareMeshRenderStats stats;
    options.shootIntensity = 0;
    require(SoftwareMeshRenderer::renderScene(
                surface, &instance, 1, options, workspace, stats),
            "the unlit source-space polygon renders");
    require(stats.polygonsDrawn == 1,
            "the test polygon reaches the DMODE 9 draw path");
    int unlitPixels = 0;
    for (size_t pixel = 0; pixel < pixels.size(); ++pixel) {
        if ((static_cast<QUint32>(pixels[pixel]) & 0x00ffffffU) != 0U) {
            ++unlitPixels;
        }
        pixels[pixel] = 0;
    }
    require(unlitPixels == 0,
            "the zero-base test polygon remains dark without a shot light");

    options.shootIntensity = 360;
    require(SoftwareMeshRenderer::renderScene(
                surface, &instance, 1, options, workspace, stats),
            "a source-space muzzle-light polygon renders");
    int litPixels = 0;
    for (size_t pixel = 0; pixel < pixels.size(); ++pixel) {
        if ((static_cast<QUint32>(pixels[pixel]) & 0x00ffffffU) != 0U) {
            ++litPixels;
        }
    }
    require(litPixels > 0,
            "shot light uses source world vertices relative to the camera world position");
}

void checkMuzzlePlacementAndFiringDraw()
{
    using namespace code::Math;
    using namespace code::Rendering;

    std::vector<QInt32> weaponSource(256 * 256, 0);
    std::vector<QInt32> fireSource(32 * 32, static_cast<QInt32>(0xffff8000U));
    SoftwareWeaponSpriteSources sources;
    sources.weapon = SoftwareTextureView(
        weaponSource.data(), static_cast<QInt32>(weaponSource.size()),
        256, 256, true, true);
    sources.fire = SoftwareTextureView(
        fireSource.data(), static_cast<QInt32>(fireSource.size()),
        32, 32, false, false);

    const SoftwareWeaponTuning& tuning = gJavaWeaponTuning;
    SoftwareWeaponCreateSettings create;
    create.viewportWidth = 240;
    create.viewportHeight = 256;
    create.customPosition = tuning.customPosition;
    create.muzzleXRatio = tuning.muzzleXRatio;
    create.muzzleYRatio = tuning.muzzleYRatio;

    SoftwareWeaponSpriteRequirements requirements;
    require(SoftwareWeaponRenderer::measureSprites(
                sources, create, requirements),
            "weapon sprite requirements are measured");

    std::vector<QInt32> preparedWeapon(requirements.weapon.pixelCount);
    std::vector<QInt32> preparedFire(requirements.fire.pixelCount);
    SoftwareWeaponSpriteBuffers buffers;
    buffers.weaponPixels = preparedWeapon.data();
    buffers.weaponCapacity = static_cast<QInt32>(preparedWeapon.size());
    buffers.firePixels = preparedFire.data();
    buffers.fireCapacity = static_cast<QInt32>(preparedFire.size());
    SoftwareWeaponSpriteSet sprites;
    require(SoftwareWeaponRenderer::prepareSprites(
                sources, create, requirements, buffers, sprites),
            "weapon sprites are prepared");
    require(sprites.dxFire == 215 && sprites.dyFire == 153,
            "QWG2 muzzle ratios produce the expected Level 1 offsets");

    std::vector<QInt32> framebuffer(240 * 256, 0);
    SoftwareWeaponDrawSettings draw;
    draw.viewportWidth = 240;
    draw.viewportHeight = 256;
    draw.hasCurrentWeapon = true;
    draw.firing = true;
    draw.customPosition = tuning.customPosition;
    draw.customPositionX = tuning.customPositionX;
    const SoftwareRenderSurfaceView surface(
        framebuffer.data(), static_cast<QInt32>(framebuffer.size()),
        240, 256, 240);
    require(SoftwareWeaponRenderer::draw(surface, sprites, draw),
            "firing frame composes safely with the corrected muzzle position");
    require(framebuffer[103 * 240 + 46] != 0,
            "Java X_POS=1.09 moves the firing flash to the configured muzzle anchor");
}

/*
 * Weapon sprites are stored 16-bit: RGB1555 for the body (keyed art) and
 * ARGB4444 for the muzzle flash (translucent glow). Both must draw the same
 * picture as the 32-bit reference up to quantisation error. Because the
 * 4-bit alpha of 4444 is coarser than the flash's original 8-bit alpha, the
 * per-channel difference is bounded, not zero, and the test pins that bound.
 */
void checkWeaponSixteenBitParity()
{
    using namespace code::Math;
    using namespace code::Rendering;

    std::vector<QInt32> weaponSource(64 * 64, 0);
    for (int y = 0; y < 64; ++y) {
        for (int x = 0; x < 64; ++x) {
            /* Keyed art: opaque core, transparent border, no partial alpha. */
            const bool inside = x >= 8 && x < 56 && y >= 8 && y < 56;
            weaponSource[y * 64 + x] =
                inside ? static_cast<QInt32>(0xff2a6ac8U) : 0;
        }
    }
    std::vector<QInt32> fireSource(32 * 32, 0);
    for (int y = 0; y < 32; ++y) {
        for (int x = 0; x < 32; ++x) {
            const int dx = x - 16;
            const int dy = y - 16;
            const int r2 = dx * dx + dy * dy;
            if (r2 > 240) continue;
            /* Radial alpha falloff, the case 1555 cannot represent. */
            QUint32 alpha = static_cast<QUint32>(255 - r2);
            fireSource[y * 32 + x] =
                static_cast<QInt32>((alpha << 24) | 0x00ffc040U);
        }
    }

    SoftwareWeaponSpriteSources sources;
    sources.weapon = SoftwareTextureView(
        weaponSource.data(), static_cast<QInt32>(weaponSource.size()),
        64, 64, true, true);
    sources.fire = SoftwareTextureView(
        fireSource.data(), static_cast<QInt32>(fireSource.size()),
        32, 32, true, true);

    const SoftwareWeaponTuning& tuning = gJavaWeaponTuning;
    SoftwareWeaponCreateSettings create;
    create.viewportWidth = 240;
    create.viewportHeight = 256;
    create.customPosition = tuning.customPosition;
    create.muzzleXRatio = tuning.muzzleXRatio;
    create.muzzleYRatio = tuning.muzzleYRatio;

    SoftwareWeaponSpriteRequirements requirements;
    require(SoftwareWeaponRenderer::measureSprites(
                sources, create, requirements),
            "16-bit weapon requirements are measured");

    std::vector<QInt32> preparedWeapon(requirements.weapon.pixelCount);
    std::vector<QInt32> preparedFire(requirements.fire.pixelCount);
    SoftwareWeaponSpriteBuffers buffers;
    buffers.weaponPixels = preparedWeapon.data();
    buffers.weaponCapacity = static_cast<QInt32>(preparedWeapon.size());
    buffers.firePixels = preparedFire.data();
    buffers.fireCapacity = static_cast<QInt32>(preparedFire.size());
    SoftwareWeaponSpriteSet sprites;
    require(SoftwareWeaponRenderer::prepareSprites(
                sources, create, requirements, buffers, sprites),
            "16-bit weapon sprites are prepared");

    /*
     * Build the 16-bit images on a copy, exactly as the device does, so the
     * untouched `sprites` still renders the 32-bit reference frame.
     */
    SoftwareWeaponSpriteSet sixteen = sprites;
    std::vector<QUint16> weapon16(static_cast<size_t>(
        sixteen.weapon.view.width) * sixteen.weapon.view.height);
    std::vector<QUint16> fire16(static_cast<size_t>(
        sixteen.fire.view.width) * sixteen.fire.view.height);
    require(sixteen.weapon.buildSixteen(
                false, weapon16.data(), static_cast<QInt32>(weapon16.size())),
            "keyed weapon body converts to RGB1555");
    require(sixteen.weapon.sixteen.is1555,
            "weapon body uses the 1555 key format");
    require(sixteen.fire.buildSixteen(
                true, fire16.data(), static_cast<QInt32>(fire16.size())),
            "muzzle flash converts to ARGB4444");
    require(sixteen.fire.sixteen.is4444,
            "muzzle flash uses the 4444 alpha format");
    require(sprites.weapon.sixteen.pixels == 0 &&
                sprites.fire.sixteen.pixels == 0,
            "the reference set keeps its 32-bit-only views");

    SoftwareWeaponDrawSettings draw;
    draw.viewportWidth = 240;
    draw.viewportHeight = 256;
    draw.hasCurrentWeapon = true;
    draw.firing = true;
    draw.customPosition = tuning.customPosition;
    draw.customPositionX = tuning.customPositionX;

    /*
     * Body only first: the weapon sprite is 1555, so the only error is the
     * 5/5/5 requantisation and must stay within one 5-bit step of 8.
     */
    SoftwareWeaponDrawSettings bodyDraw = draw;
    bodyDraw.firing = false;
    std::vector<QInt32> bodyReference(240 * 256, 0);
    const SoftwareRenderSurfaceView bodyReferenceSurface(
        bodyReference.data(), static_cast<QInt32>(bodyReference.size()),
        240, 256, 240);
    require(SoftwareWeaponRenderer::draw(bodyReferenceSurface, sprites, bodyDraw),
            "32-bit reference weapon body draws");
    std::vector<QInt32> bodyFast(240 * 256, 0);
    const SoftwareRenderSurfaceView bodyFastSurface(
        bodyFast.data(), static_cast<QInt32>(bodyFast.size()), 240, 256, 240);
    require(SoftwareWeaponRenderer::draw(bodyFastSurface, sixteen, bodyDraw),
            "16-bit weapon body draws");
    int bodyDiffering = 0;
    int bodyWorst = 0;
    for (size_t pixel = 0; pixel < bodyReference.size(); ++pixel) {
        const QUint32 a = static_cast<QUint32>(bodyReference[pixel]);
        const QUint32 b = static_cast<QUint32>(bodyFast[pixel]);
        if (a == b) continue;
        ++bodyDiffering;
        for (int shift = 0; shift < 24; shift += 8) {
            const int da = static_cast<int>((a >> shift) & 0xffU);
            const int db = static_cast<int>((b >> shift) & 0xffU);
            const int delta = da > db ? da - db : db - da;
            if (delta > bodyWorst) bodyWorst = delta;
        }
    }
    require(bodyDiffering > 0,
            "the 1555 weapon body is actually exercised");
    require(bodyWorst <= 8,
            "the 1555 weapon body stays within one 5-bit quantisation step");

    std::vector<QInt32> reference(240 * 256, 0);
    const SoftwareRenderSurfaceView referenceSurface(
        reference.data(), static_cast<QInt32>(reference.size()), 240, 256, 240);
    require(SoftwareWeaponRenderer::draw(referenceSurface, sprites, draw),
            "32-bit reference weapon frame draws");

    std::vector<QInt32> fast(240 * 256, 0);
    const SoftwareRenderSurfaceView fastSurface(
        fast.data(), static_cast<QInt32>(fast.size()), 240, 256, 240);
    require(SoftwareWeaponRenderer::draw(fastSurface, sixteen, draw),
            "16-bit weapon frame draws");

    int differing = 0;
    int worst = 0;
    for (size_t pixel = 0; pixel < reference.size(); ++pixel) {
        const QUint32 a = static_cast<QUint32>(reference[pixel]);
        const QUint32 b = static_cast<QUint32>(fast[pixel]);
        if (a == b) continue;
        ++differing;
        for (int shift = 0; shift < 32; shift += 8) {
            const int da = static_cast<int>((a >> shift) & 0xffU);
            const int db = static_cast<int>((b >> shift) & 0xffU);
            const int delta = da > db ? da - db : db - da;
            if (delta > worst) worst = delta;
        }
    }
    require(differing > 0,
            "the 16-bit weapon path actually differs from the 32-bit reference");
    /*
     * Weapon body: 1555 has at most 3 bits of error per channel after the
     * 5/5/5 requantisation. Flash: 4444 quantises alpha to 1/17 steps, so
     * an opaque red peak may shift a scaled channel by up to ~16 after the
     * blend. Anything larger means a real arithmetic divergence, not
     * quantisation.
     */
    require(worst <= 20,
            "16-bit weapon rendering stays within 1555/4444 quantisation error");
}

void checkOmap1710DivideReference()
{
    using code::Math::QUint32;
    using code::Math::ArmV5teDsp::omap1710UnsignedDivideReference;

    QUint32 quotient = 0;
    require(omap1710UnsignedDivideReference(0xffffffffU, 1U, quotient) &&
                quotient == 0xffffffffU,
            "normalized scalar divide handles the full unsigned quotient");
    require(omap1710UnsignedDivideReference(0x80000000U, 0x7fffffffU,
                                            quotient) && quotient == 1U,
            "normalized scalar divide handles high-bit operands");
    require(omap1710UnsignedDivideReference(7U, 8U, quotient) &&
                quotient == 0U,
            "normalized scalar divide handles dividend smaller than divisor");
    require(!omap1710UnsignedDivideReference(1U, 0U, quotient),
            "normalized scalar divide rejects a zero divisor");

    QUint32 state = 0x91e10da5U;
    for (int i = 0; i < 100000; ++i) {
        state = state * 1664525U + 1013904223U;
        const QUint32 dividend = state;
        state = state * 1664525U + 1013904223U;
        const QUint32 divisor = state == 0U ? 1U : state;
        require(omap1710UnsignedDivideReference(
                    dividend, divisor, quotient) &&
                    quotient == dividend / divisor,
                "normalized scalar divide matches unsigned division");
    }
}

void checkRainStreakRasterization()
{
    using namespace code::Math;
    using namespace code::Rendering;

    const QInt32 width = 12;
    const QInt32 height = 12;
    const QInt32 stride = 16;
    const QInt32 color = javaIntFromUnsigned(0x00123456U);
    std::vector<QInt32> pixels(static_cast<size_t>(stride * height), 0);
    const SoftwareRenderSurfaceView surface(
        pixels.data(), static_cast<QInt32>(pixels.size()),
        width, height, stride);
    require(SoftwareRainRenderer::drawStreak(surface, 5, 1, 8, color),
            "rain line draws on a padded-stride framebuffer");

    const QInt32 expectedX[9] = {5, 5, 4, 4, 4, 4, 3, 3, 3};
    int colored = 0;
    for (QInt32 y = 0; y < height; ++y) {
        for (QInt32 x = 0; x < stride; ++x) {
            if (pixels[static_cast<size_t>(y * stride + x)] == color) {
                ++colored;
                require(y >= 1 && y <= 9 && x == expectedX[y - 1],
                        "rain streak follows the inclusive add/compare recurrence");
            }
        }
    }
    require(colored == 9, "rain streak draws both endpoints exactly once");
    for (QInt32 y = 0; y < height; ++y) {
        for (QInt32 x = width; x < stride; ++x) {
            require(pixels[static_cast<size_t>(y * stride + x)] == 0,
                    "rain streak does not write destination padding");
        }
    }

    require(SoftwareRainRenderer::drawStreak(surface, -1, 10, 8, color),
            "partially clipped rain streak remains valid");
    require(pixels[10 * stride] == 0 && pixels[11 * stride] == 0,
            "off-screen portion of the rain streak is clipped");
    require(SoftwareRainRenderer::drawStreak(surface, 3, 4, 0, color) &&
                pixels[4 * stride + 3] == color,
            "zero-size rain streak preserves the single-point line case");
    require(SoftwareRainRenderer::drawStreak(surface, 3, 4, 8193, color),
            "unusual rain geometry uses the clipped 64-bit fallback");
}

} // namespace

int main()
{
    checkWeaponConfigParsing();
    checkJavaFrameTimedWalkMovement();
    checkLevel41JumpAndButtonZero();
    checkLevel41CameraPitchControls();
#if defined(QUANTUM_LEVEL41_TURN_INERTIA)
    checkTurnInertia();
#else
    checkSis33FrameTimedTurn();
#endif
    checkJavaWalkCollision();
    checkRoofDetectionForRainSuppression();
    checkSis33SwayTriggerAndCandidateMath();
    checkSis33Q16FrameArithmeticWithCandidateTuning();
    checkJavaWeaponAnimationCrossCheck();
    checkJavaShotLightState();
    checkWorldSpaceShotLighting();
    checkMuzzlePlacementAndFiringDraw();
    checkWeaponSixteenBitParity();
    checkOmap1710DivideReference();
    checkRainStreakRasterization();
#if defined(QUANTUM_LEVEL41_TURN_INERTIA)
    const char* turnMode = "turn-inertia A/B";
#else
    const char* turnMode = "SIS v1.0.33 frame-scaled turn";
#endif
#if defined(QUANTUM_LEVEL41_DISABLE_WEAPON_SWAY)
    const char* swayMode = "SIS v1.0.33 no-sway A/B path";
#else
    const char* swayMode = "SIS v1.0.33 marker/impulse/damping checks";
#endif
    std::printf(
        "Level 41 regressions passed: %s; %s; Java cross-checks; QWG3; "
        "roof-rain; shot-light; OMAP1710 ASM-reference checks.\n",
        turnMode, swayMode);
    return 0;
}
