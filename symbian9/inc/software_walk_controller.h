#ifndef QUANTUM_SOFTWARE_WALK_CONTROLLER_H
#define QUANTUM_SOFTWARE_WALK_CONTROLLER_H

#include "collision_mesh_view.h"
#include "matrix.h"

namespace code {
namespace Rendering {

/* World-space player feet, yaw, 3D velocity, and cached collision state. */
struct SoftwareWalkState {
    code::Math::QInt32 x;
    code::Math::QInt32 feetY;
    code::Math::QInt32 z;
    code::Math::QInt32 yawDegrees;
    code::Math::QInt32 velocityX;
    code::Math::QInt32 velocityY;
    code::Math::QInt32 velocityZ;
    /* Signed degrees/second in Q16, plus the fractional yaw left after each frame. */
    code::Math::QInt32 turnVelocityDegreesPerSecondQ16;
    code::Math::QInt32 turnRemainderQ16;
    bool onFloor;
    bool underRoof;

    SoftwareWalkState();
    SoftwareWalkState(code::Math::QInt32 x,
                      code::Math::QInt32 feetY,
                      code::Math::QInt32 z,
                      code::Math::QInt32 yawDegrees);
};

/* Forward/right/turn axes are sampled once per frame and are each -1, 0, or 1.
 * jump is a one-frame press pulse (0 or 1), not a held movement axis. */
struct SoftwareWalkInput {
    code::Math::QInt32 forward;
    code::Math::QInt32 right;
    code::Math::QInt32 turn;
    code::Math::QInt32 jump;

    SoftwareWalkInput();
    SoftwareWalkInput(code::Math::QInt32 forward,
                      code::Math::QInt32 right);
    SoftwareWalkInput(code::Math::QInt32 forward,
                      code::Math::QInt32 right,
                      code::Math::QInt32 turn);
    SoftwareWalkInput(code::Math::QInt32 forward,
                      code::Math::QInt32 right,
                      code::Math::QInt32 turn,
                      code::Math::QInt32 jump);
};

/* SIS v1.0.33 native disassembly supports the 10-degree turn step and 150
 * per-update walk scalar. Java Player/Character is only a movement cross-check.
 * walkSpeed is per-frame acceleration, not a per-key step. Jump uses the
 * supplied Java reference's 150 impulse, 1.2 velocity scale, and -20 gravity;
 * the native Level 41 key binding itself is a requested feature, not an
 * assertion of v1.0.33 jump-field equivalence. Distances use integer world units. */
struct SoftwareWalkSettings {
    code::Math::QInt32 walkSpeed;
    code::Math::QInt32 turnStepDegrees;
    code::Math::QInt32 turnAccelerationDegreesPerSecondSquared;
    code::Math::QInt32 turnDecelerationDegreesPerSecondSquared;
    code::Math::QInt32 maximumTurnSpeedDegreesPerSecond;
    code::Math::QInt32 turnTapImpulseDegreesPerSecond;
    code::Math::QInt32 bodyRadius;
    code::Math::QInt32 eyeHeight;
    code::Math::QInt32 maximumStepUp;
    code::Math::QInt32 maximumStepDown;
    code::Math::QInt32 friction;
    code::Math::QInt32 jumpImpulse;
    code::Math::QInt32 gravity;

    SoftwareWalkSettings();
    SoftwareWalkSettings(code::Math::QInt32 walkSpeed,
                         code::Math::QInt32 turnStepDegrees,
                         code::Math::QInt32 bodyRadius,
                         code::Math::QInt32 eyeHeight,
                         code::Math::QInt32 maximumStepUp,
                         code::Math::QInt32 maximumStepDown,
                         code::Math::QInt32 friction);
};

/*
 * Allocation-free Level 41 walk-camera controller, cross-checked against Java
 * Player.walk, Character.update, Character.jump, Scene.gravity, and grounded
 * GameObject friction. The SIS v1.0.33 input path forms forward from held
 * Up/Down and turn from Left/Right; sub_261cd applies turnStepDegrees *
 * elapsedMs / 50 and wraps yaw. Native initialization/update corroborates a
 * 150 per-update walk scalar. Jump physics follows the supplied Java reference;
 * its native field mapping is not claimed. Collision/floor/roof queries remain
 * native Level 41 implementations.
 */

class SoftwareWalkController {
public:
    static bool update(const code::Collision::CollisionMeshView& collision,
                       const SoftwareWalkInput& input,
                       code::Math::QInt32 elapsedMs,
                       const SoftwareWalkSettings& settings,
                       SoftwareWalkState& state);

    /* Camera-only vertical look: +1 looks up, -1 looks down. The view angle
       is integrated at a fixed rate and clamped without changing walk state. */
    static bool updateCameraPitch(code::Math::QInt32 verticalLookAxis,
                                  code::Math::QInt32 elapsedMs,
                                  code::Math::QInt32& pitchDegrees,
                                  code::Math::QInt32& pitchRemainder);

    /* Key-event fallback: a discrete step normally, or a velocity impulse in
       QUANTUM_LEVEL41_TURN_INERTIA builds. */
    static bool turn(code::Math::QInt32 direction,
                     const SoftwareWalkSettings& settings,
                     SoftwareWalkState& state);

    /* Positive cameraPitchDegrees looks upward; pitch never changes walk state. */
    static void buildWorldToCamera(const SoftwareWalkState& state,
                                   const SoftwareWalkSettings& settings,
                                   code::Math::Matrix& worldToCamera,
                                   code::Math::QInt32 cameraPitchDegrees = 0);
};

} // namespace Rendering
} // namespace code

#endif // QUANTUM_SOFTWARE_WALK_CONTROLLER_H
