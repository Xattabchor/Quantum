#include "software_walk_controller.h"

#include "collision_height_computer.h"
#include "quantum_integer.h"
#include "sphere_cast.h"

namespace code {
namespace Rendering {

using code::Math::Matrix;
using code::Math::QInt32;
using code::Math::QInt64;
using code::Math::Vector3D;

namespace {

const QInt32 KWalkFrameTimeBaseMs = 50;
const QInt32 KWalkMinimumFrameTimeMs = 1;
const QInt32 KWalkMaximumFrameTimeMs = 200;

QInt32 wrapDegrees(QInt32 degrees)
{
    QInt32 wrapped = degrees % 360;
    if (wrapped < 0) wrapped += 360;
    return wrapped;
}

bool validSettings(const SoftwareWalkSettings& settings)
{
    return settings.walkSpeed >= 0 && settings.turnStepDegrees >= 0 &&
           settings.turnAccelerationDegreesPerSecondSquared >= 0 &&
           settings.turnDecelerationDegreesPerSecondSquared >= 0 &&
           settings.maximumTurnSpeedDegreesPerSecond > 0 &&
           settings.maximumTurnSpeedDegreesPerSecond <= 32767 &&
           settings.turnTapImpulseDegreesPerSecond >= 0 &&
           settings.turnTapImpulseDegreesPerSecond <= 32767 &&
           settings.bodyRadius >= 0 && settings.eyeHeight >= 0 &&
           settings.maximumStepUp >= 0 && settings.maximumStepDown >= 0 &&
           settings.friction > 0 && settings.jumpImpulse >= 0 &&
           settings.gravity <= 0;
}

QInt32 clampFrameTime(QInt32 elapsedMs)
{
    if (elapsedMs < KWalkMinimumFrameTimeMs) {
        return KWalkMinimumFrameTimeMs;
    }
    if (elapsedMs > KWalkMaximumFrameTimeMs) {
        return KWalkMaximumFrameTimeMs;
    }
    return elapsedMs;
}

QInt32 frameScaledDisplacement(QInt32 velocity, QInt32 elapsedMs)
{
    return code::Math::javaIntDiv(
        code::Math::javaIntMul(velocity, elapsedMs), KWalkFrameTimeBaseMs);
}

QInt32 javaJumpVelocityScale(QInt32 velocity)
{
    const float scaled = static_cast<float>(velocity) * 1.2F;
    if (scaled >= 2147483648.0F) return 2147483647;
    if (scaled <= -2147483648.0F) return static_cast<QInt32>(-2147483647 - 1);
    return static_cast<QInt32>(scaled);
}

#if defined(QUANTUM_LEVEL41_TURN_INERTIA)
const QInt32 KTurnRateQ16One = 65536;
const QInt32 KMillisecondsPerSecond = 1000;

QInt32 clampTurnVelocityQ16(QInt64 velocityQ16,
                            const SoftwareWalkSettings& settings)
{
    const QInt64 maximumQ16 = static_cast<QInt64>(
        settings.maximumTurnSpeedDegreesPerSecond) * KTurnRateQ16One;
    if (velocityQ16 > maximumQ16) velocityQ16 = maximumQ16;
    if (velocityQ16 < -maximumQ16) velocityQ16 = -maximumQ16;
    return static_cast<QInt32>(velocityQ16);
}

QInt64 turnRateDeltaQ16(QInt32 accelerationDegreesPerSecondSquared,
                         QInt32 elapsedMs)
{
    return static_cast<QInt64>(accelerationDegreesPerSecondSquared) *
           elapsedMs * KTurnRateQ16One / KMillisecondsPerSecond;
}

void updateTurnInertia(QInt32 turnInput,
                       QInt32 elapsedMs,
                       const SoftwareWalkSettings& settings,
                       SoftwareWalkState& state)
{
    QInt64 velocityQ16 = state.turnVelocityDegreesPerSecondQ16;
    if (turnInput != 0) {
        velocityQ16 += static_cast<QInt64>(turnInput) * turnRateDeltaQ16(
            settings.turnAccelerationDegreesPerSecondSquared, elapsedMs);
    } else {
        const QInt64 decelerationQ16 = turnRateDeltaQ16(
            settings.turnDecelerationDegreesPerSecondSquared, elapsedMs);
        if (velocityQ16 > 0) {
            velocityQ16 -= decelerationQ16;
            if (velocityQ16 < 0) velocityQ16 = 0;
        } else if (velocityQ16 < 0) {
            velocityQ16 += decelerationQ16;
            if (velocityQ16 > 0) velocityQ16 = 0;
        }
    }
    state.turnVelocityDegreesPerSecondQ16 =
        clampTurnVelocityQ16(velocityQ16, settings);

    const QInt64 yawDeltaQ16 = static_cast<QInt64>(state.turnRemainderQ16) +
        static_cast<QInt64>(state.turnVelocityDegreesPerSecondQ16) *
            elapsedMs / KMillisecondsPerSecond;
    const QInt32 wholeDegrees = static_cast<QInt32>(
        yawDeltaQ16 / KTurnRateQ16One);
    state.turnRemainderQ16 = static_cast<QInt32>(
        yawDeltaQ16 % KTurnRateQ16One);
    state.yawDegrees = wrapDegrees(
        code::Math::javaIntAdd(state.yawDegrees, wholeDegrees));
}
#endif

void addInputVelocity(const SoftwareWalkInput& input,
                      const SoftwareWalkSettings& settings,
                      const SoftwareWalkState& state,
                      QInt32& velocityX,
                      QInt32& velocityZ)
{
    Matrix heading;
    heading.setRotY(wrapDegrees(state.yawDegrees));

    /* Character.moveZ(-walkSpeed * forward), in the Java transform basis. */
    const QInt32 forwardDistance = code::Math::javaIntMul(
        -input.forward, settings.walkSpeed);
    const QInt32 forwardX = code::Math::javaShiftRight(
        code::Math::javaIntMul(heading.m02, forwardDistance), Matrix::fp);
    const QInt32 forwardZ = code::Math::javaShiftRight(
        code::Math::javaIntMul(heading.m22, forwardDistance), Matrix::fp);
    velocityX = code::Math::javaIntAdd(velocityX, forwardX);
    velocityZ = code::Math::javaIntAdd(velocityZ, forwardZ);

    /* Character.moveX(walkSpeed * right), also in the Java transform basis. */
    const QInt32 rightDistance = code::Math::javaIntMul(
        input.right, settings.walkSpeed);
    const QInt32 rightX = code::Math::javaShiftRight(
        code::Math::javaIntMul(heading.m00, rightDistance), Matrix::fp);
    const QInt32 rightZ = code::Math::javaShiftRight(
        code::Math::javaIntMul(heading.m20, rightDistance), Matrix::fp);
    velocityX = code::Math::javaIntAdd(velocityX, rightX);
    velocityZ = code::Math::javaIntAdd(velocityZ, rightZ);
}

} // anonymous namespace

SoftwareWalkState::SoftwareWalkState()
    : x(0), feetY(0), z(0), yawDegrees(0), velocityX(0), velocityY(0),
      velocityZ(0), turnVelocityDegreesPerSecondQ16(0),
      turnRemainderQ16(0), onFloor(true), underRoof(false)
{
}

SoftwareWalkState::SoftwareWalkState(QInt32 worldX,
                                     QInt32 worldFeetY,
                                     QInt32 worldZ,
                                     QInt32 yaw)
    : x(worldX), feetY(worldFeetY), z(worldZ), yawDegrees(yaw),
      velocityX(0), velocityY(0), velocityZ(0),
      turnVelocityDegreesPerSecondQ16(0), turnRemainderQ16(0),
      onFloor(true), underRoof(false)
{
}

SoftwareWalkInput::SoftwareWalkInput()
    : forward(0), right(0), turn(0), jump(0)
{
}

SoftwareWalkInput::SoftwareWalkInput(QInt32 forwardAxis, QInt32 rightAxis)
    : forward(forwardAxis), right(rightAxis), turn(0), jump(0)
{
}

SoftwareWalkInput::SoftwareWalkInput(QInt32 forwardAxis,
                                     QInt32 rightAxis,
                                     QInt32 turnAxis)
    : forward(forwardAxis), right(rightAxis), turn(turnAxis), jump(0)
{
}

SoftwareWalkInput::SoftwareWalkInput(QInt32 forwardAxis,
                                     QInt32 rightAxis,
                                     QInt32 turnAxis,
                                     QInt32 jumpPulse)
    : forward(forwardAxis), right(rightAxis), turn(turnAxis), jump(jumpPulse)
{
}

SoftwareWalkSettings::SoftwareWalkSettings()
    : walkSpeed(150), turnStepDegrees(10),
      turnAccelerationDegreesPerSecondSquared(240),
      turnDecelerationDegreesPerSecondSquared(360),
      maximumTurnSpeedDegreesPerSecond(120),
      turnTapImpulseDegreesPerSecond(60),
      bodyRadius(400), eyeHeight(1503),
      maximumStepUp(600), maximumStepDown(3000), friction(4),
      jumpImpulse(150), gravity(-20)
{
}

SoftwareWalkSettings::SoftwareWalkSettings(QInt32 speed,
                                           QInt32 turnDegrees,
                                           QInt32 radius,
                                           QInt32 height,
                                           QInt32 maximumUp,
                                           QInt32 maximumDown,
                                           QInt32 groundedFriction)
    : walkSpeed(speed), turnStepDegrees(turnDegrees),
      turnAccelerationDegreesPerSecondSquared(240),
      turnDecelerationDegreesPerSecondSquared(360),
      maximumTurnSpeedDegreesPerSecond(120),
      turnTapImpulseDegreesPerSecond(60),
      bodyRadius(radius), eyeHeight(height), maximumStepUp(maximumUp),
      maximumStepDown(maximumDown), friction(groundedFriction),
      jumpImpulse(150), gravity(-20)
{
}

bool SoftwareWalkController::update(
    const code::Collision::CollisionMeshView& collision,
    const SoftwareWalkInput& input,
    QInt32 elapsedMs,
    const SoftwareWalkSettings& settings,
    SoftwareWalkState& state)
{
    if (!validSettings(settings) || input.forward < -1 || input.forward > 1 ||
        input.right < -1 || input.right > 1 ||
        input.turn < -1 || input.turn > 1 ||
        input.jump < 0 || input.jump > 1) {
        return false;
    }

    elapsedMs = clampFrameTime(elapsedMs);
    const bool wasOnFloor = state.onFloor;
#if defined(QUANTUM_LEVEL41_TURN_INERTIA)
    updateTurnInertia(input.turn, elapsedMs, settings, state);
#else
    /* SIS v1.0.33 sub_261cd applies the signed left/right axis as
       turnStep * elapsedMs / 50, then wraps yaw at 360 degrees. The native
       initializer sets turnStep to 10. */
    if (input.turn != 0) {
        const QInt32 turnNumerator = code::Math::javaIntMul(
            code::Math::javaIntMul(settings.turnStepDegrees, elapsedMs),
            input.turn);
        const QInt32 turnDelta = code::Math::javaIntDiv(
            turnNumerator, KWalkFrameTimeBaseMs);
        state.yawDegrees = wrapDegrees(
            code::Math::javaIntAdd(state.yawDegrees, turnDelta));
    }
#endif

    /* Java Player.walk adds the configured scalar to velocity per update. */
    if (state.onFloor && (input.forward != 0 || input.right != 0)) {
        addInputVelocity(input, settings, state,
                         state.velocityX, state.velocityZ);
    }

    /* Java Character.jump(150, 1.2F) is a grounded, one-shot impulse. */
    bool jumpStarted = false;
    if (input.jump != 0 && state.onFloor) {
        state.velocityX = javaJumpVelocityScale(state.velocityX);
        state.velocityY = javaJumpVelocityScale(code::Math::javaIntAdd(
            state.velocityY, settings.jumpImpulse));
        state.velocityZ = javaJumpVelocityScale(state.velocityZ);
        jumpStarted = true;
    }

    /* Scene applies gravity before Character.update integrates displacement. */
    if (collision.isValid() || !wasOnFloor || jumpStarted) {
        const QInt32 gravityDelta = code::Math::javaIntDiv(
            code::Math::javaIntMul(settings.gravity, elapsedMs),
            KWalkFrameTimeBaseMs);
        state.velocityY = code::Math::javaIntAdd(
            state.velocityY, gravityDelta);
    } else {
        /* No-collision host fixtures represent a flat, stationary floor. */
        state.velocityY = 0;
    }

    /* Character.update scales every velocity component by frameTime / 50. */
    Vector3D movement(frameScaledDisplacement(state.velocityX, elapsedMs),
                      frameScaledDisplacement(state.velocityY, elapsedMs),
                      frameScaledDisplacement(state.velocityZ, elapsedMs));

    /* Character.update caps the full 3D displacement at radius * 0.8. */
    const QInt32 maximumDisplacement = code::Math::javaIntDiv(
        code::Math::javaIntMul(settings.bodyRadius, 4), 5);
    const QInt32 maximumDisplacementSquared = code::Math::javaIntMul(
        maximumDisplacement, maximumDisplacement);
    if (movement.lengthSquared() > maximumDisplacementSquared) {
        movement.setLength(maximumDisplacement);
    }

    QInt32 worldX = code::Math::javaIntAdd(state.x, movement.x);
    QInt32 feetY = code::Math::javaIntAdd(state.feetY, movement.y);
    QInt32 worldZ = code::Math::javaIntAdd(state.z, movement.z);
    bool underRoof = false;
    bool grounded = false;

    if (collision.isValid()) {
        Vector3D bodyCenter(
            worldX, code::Math::javaIntAdd(feetY, settings.eyeHeight), worldZ);
        const QInt32 requestedCenterY = bodyCenter.y;
        code::Collision::SphereCast::cast(
            collision, bodyCenter, settings.bodyRadius, false);
        if (state.velocityY > 0 && bodyCenter.y < requestedCenterY) {
            /* A ceiling stopped the upward sphere motion. */
            state.velocityY = 0;
        }
        worldX = bodyCenter.x;
        feetY = code::Math::javaIntSub(bodyCenter.y, settings.eyeHeight);
        worldZ = bodyCenter.z;

        code::Collision::CollisionHeightState height;
        height.reset(worldX,
                     code::Math::javaIntAdd(feetY, settings.eyeHeight),
                     worldZ);
        code::Collision::CollisionHeightComputer::computeHeight(
            collision, height);
        underRoof = height.underRoof;
        if (height.isOnFloor()) {
            const QInt32 floorDelta = code::Math::javaIntSub(
                height.height, feetY);
            const QInt32 minimumDrop = code::Math::javaIntSub(
                0, settings.maximumStepDown);
            const bool groundedStep = wasOnFloor && !jumpStarted &&
                floorDelta <= settings.maximumStepUp &&
                floorDelta >= minimumDrop;
            const bool descendingLanding = state.velocityY <= 0 &&
                feetY <= height.height;
            if (groundedStep || descendingLanding) {
                feetY = height.height;
                grounded = true;
            }
        }
    } else {
        grounded = wasOnFloor && !jumpStarted;
    }

    state.x = worldX;
    state.feetY = feetY;
    state.z = worldZ;
    state.onFloor = grounded;
    state.underRoof = underRoof;

    /* Java GameObject.update divides all grounded velocity components by friction. */
    if (state.onFloor) {
        state.velocityX = code::Math::javaIntDiv(
            state.velocityX, settings.friction);
        state.velocityY = code::Math::javaIntDiv(
            state.velocityY, settings.friction);
        state.velocityZ = code::Math::javaIntDiv(
            state.velocityZ, settings.friction);
    }
    return true;
}

bool SoftwareWalkController::updateCameraPitch(
    QInt32 verticalLookAxis,
    QInt32 elapsedMs,
    QInt32& pitchDegrees,
    QInt32& pitchRemainder)
{
    const QInt32 KMaximumCameraPitchDegrees = 60;
    const QInt32 KCameraLookRateDegreesPerSecond = 60;
    const QInt32 KMillisecondsPerSecond = 1000;

    if (verticalLookAxis < -1 || verticalLookAxis > 1) return false;
    if (pitchDegrees < -KMaximumCameraPitchDegrees) {
        pitchDegrees = -KMaximumCameraPitchDegrees;
    } else if (pitchDegrees > KMaximumCameraPitchDegrees) {
        pitchDegrees = KMaximumCameraPitchDegrees;
    }
    if (verticalLookAxis == 0) {
        pitchRemainder = 0;
        return true;
    }

    elapsedMs = clampFrameTime(elapsedMs);
    const QInt32 numerator = code::Math::javaIntAdd(
        pitchRemainder,
        code::Math::javaIntMul(
            code::Math::javaIntMul(verticalLookAxis,
                                   KCameraLookRateDegreesPerSecond),
            elapsedMs));
    const QInt32 pitchDelta =
        code::Math::javaIntDiv(numerator, KMillisecondsPerSecond);
    pitchRemainder = numerator % KMillisecondsPerSecond;
    const QInt32 requestedPitch = code::Math::javaIntAdd(
        pitchDegrees, pitchDelta);
    if (requestedPitch > KMaximumCameraPitchDegrees) {
        pitchDegrees = KMaximumCameraPitchDegrees;
        if (verticalLookAxis > 0) pitchRemainder = 0;
    } else if (requestedPitch < -KMaximumCameraPitchDegrees) {
        pitchDegrees = -KMaximumCameraPitchDegrees;
        if (verticalLookAxis < 0) pitchRemainder = 0;
    } else {
        pitchDegrees = requestedPitch;
    }
    return true;
}

bool SoftwareWalkController::turn(QInt32 direction,
                                  const SoftwareWalkSettings& settings,
                                  SoftwareWalkState& state)
{
    if (!validSettings(settings) || direction < -1 || direction > 1) {
        return false;
    }
    if (direction == 0) return true;
#if defined(QUANTUM_LEVEL41_TURN_INERTIA)
    const QInt64 impulseQ16 = static_cast<QInt64>(direction) *
        settings.turnTapImpulseDegreesPerSecond * KTurnRateQ16One;
    state.turnVelocityDegreesPerSecondQ16 = clampTurnVelocityQ16(
        static_cast<QInt64>(state.turnVelocityDegreesPerSecondQ16) + impulseQ16,
        settings);
#else
    const QInt32 turnDelta = code::Math::javaIntMul(
        direction, settings.turnStepDegrees);
    state.yawDegrees = wrapDegrees(
        code::Math::javaIntAdd(state.yawDegrees, turnDelta));
#endif
    return true;
}

void SoftwareWalkController::buildWorldToCamera(
    const SoftwareWalkState& state,
    const SoftwareWalkSettings& settings,
    Matrix& worldToCamera,
    QInt32 cameraPitchDegrees)
{
    Matrix cameraWorld;
    cameraWorld.setRotY(wrapDegrees(state.yawDegrees));
    if (cameraPitchDegrees != 0) {
        Matrix pitch;
        pitch.setRotX(cameraPitchDegrees);
        cameraWorld.mul(pitch);
    }
    cameraWorld.setPosition(
        state.x,
        code::Math::javaIntAdd(state.feetY, settings.eyeHeight),
        state.z);
    cameraWorld.invert();
    worldToCamera.set(cameraWorld);
}

} // namespace Rendering
} // namespace code
