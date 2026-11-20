#include "software_mesh_animation.h"

#include "quantum_integer.h"

namespace code {
namespace Rendering {

using code::Math::QInt32;
using code::Math::QInt64;
using code::Math::QUint32;
using code::Math::Vector3D;

namespace {

QInt32 javaFloatToInt(float value)
{
    if (value != value) return 0;
    if (value >= 2147483648.0f) return 2147483647;
    if (value <= -2147483648.0f) return static_cast<QInt32>(-2147483647 - 1);
    return static_cast<QInt32>(value);
}

QInt64 javaFloatToLong(float value)
{
    if (value != value) return 0;
    if (static_cast<double>(value) >= 9223372036854775808.0) {
        return static_cast<QInt64>(9223372036854775807LL);
    }
    if (static_cast<double>(value) <= -9223372036854775808.0) {
        return static_cast<QInt64>(-9223372036854775807LL - 1LL);
    }
    /* The EKA2 guest runtime provides __aeabi_d2lz, not __aeabi_f2lz. */
    volatile double widenedValue = static_cast<double>(value);
    return static_cast<QInt64>(widenedValue);
}

QInt32 javaShort(QInt32 value)
{
    const QUint32 bits = static_cast<QUint32>(value) & 0xffffU;
    if (bits <= 0x7fffU) return static_cast<QInt32>(bits);
    return static_cast<QInt32>(bits) - 65536;
}

QInt32 interpolatedCoordinate(QInt32 first,
                              QInt32 second,
                              QInt32 fraction)
{
    const QInt32 inverse = code::Math::javaIntSub(1024, fraction);
    const QInt32 firstPart = code::Math::javaShiftRight(
        code::Math::javaIntMul(first, inverse), 10);
    const QInt32 secondPart = code::Math::javaShiftRight(
        code::Math::javaIntMul(second, fraction), 10);
    return code::Math::javaIntAdd(firstPart, secondPart);
}

} // anonymous namespace

SoftwareMeshAnimationClock::SoftwareMeshAnimationClock()
    : frameInter(0), state(ESoftwareMeshAnimationLoop), clickAnim(0),
      clickTimeMillis(0), gameTimeMillis(0), animationSpeed(0.0f)
{
}

SoftwareMeshAnimatedInstance::SoftwareMeshAnimatedInstance()
    : sourceInstance(0), frameVertices(0), frames(0), frameCount(0),
      clock(), morphEnabled(true), animatedVertices(0),
      animatedVertexCapacity(0), animatedMesh(), renderInstance(), frameQ10(0)
{
}

bool SoftwareMeshAnimator::frameForState(
    const SoftwareMeshAnimationClock& clock,
    QInt32 frameCount,
    QInt32& frameQ10)
{
    if (frameCount <= 0) return false;
    const QInt32 maxFrame = code::Math::javaIntMul(frameCount, 1024);
    if (clock.state == ESoftwareMeshAnimationLoop) {
        const float value =
            static_cast<float>(clock.frameInter) *
            clock.animationSpeed / 100.0f;
        frameQ10 = javaFloatToInt(value);
        return true;
    }

    if (clock.state != ESoftwareMeshAnimationOpen &&
        clock.state != ESoftwareMeshAnimationClose) {
        return false;
    }

    const QInt64 elapsed = code::Math::javaLongSub(
        clock.gameTimeMillis, clock.clickTimeMillis);
    const float elapsedAnimation =
        static_cast<float>(elapsed) * clock.animationSpeed * 20.0f / 1000.0f;
    float frameAsFloat = static_cast<float>(clock.clickAnim);
    if (clock.state == ESoftwareMeshAnimationOpen) {
        frameAsFloat += elapsedAnimation;
    } else {
        frameAsFloat -= elapsedAnimation;
    }
    QInt64 frame = javaFloatToLong(frameAsFloat);

    if (clock.state == ESoftwareMeshAnimationOpen) {
        const QInt32 finalFrame = code::Math::javaIntSub(maxFrame, 1024);
        if (frame >= finalFrame) frame = finalFrame;
    } else if (frame < 0) {
        frame = 0;
    }
    frameQ10 = code::Math::javaIntFromLong(frame);
    return true;
}

bool SoftwareMeshAnimator::prepareInstance(
    SoftwareMeshAnimatedInstance& instance)
{
    if (instance.sourceInstance == 0 ||
        instance.sourceInstance->mesh == 0) {
        return false;
    }
    const SoftwareMeshView& sourceMesh = *instance.sourceInstance->mesh;
    if (sourceMesh.vertexCount < 0) return false;

    QInt32 frameQ10;
    if (!frameForState(instance.clock, instance.frameCount, frameQ10) ||
        !interpolate(instance.frameVertices, instance.frames,
                     instance.frameCount, sourceMesh.vertexCount, frameQ10,
                     instance.morphEnabled, instance.animatedVertices,
                     instance.animatedVertexCapacity)) {
        return false;
    }

    instance.animatedMesh = sourceMesh;
    instance.animatedMesh.vertices = instance.animatedVertices;
    instance.renderInstance = SoftwareMeshInstance(
        &instance.animatedMesh, instance.sourceInstance->modelToCamera,
        instance.sourceInstance->depthBias);
    instance.frameQ10 = frameQ10;
    return true;
}

bool SoftwareMeshAnimator::interpolate(
    const Vector3D* frameVertices,
    const SoftwareMesh3DPart* frames,
    QInt32 frameCount,
    QInt32 vertexCount,
    QInt32 frameQ10,
    bool morphEnabled,
    Vector3D* outputVertices,
    QInt32 outputCapacity)
{
    if (frameCount <= 0 || vertexCount < 0 || outputCapacity < vertexCount ||
        frames == 0 || (vertexCount > 0 &&
        (frameVertices == 0 || outputVertices == 0))) {
        return false;
    }

    QInt32 frameIndex;
    for (frameIndex = 0; frameIndex < frameCount; ++frameIndex) {
        if (frames[frameIndex].vertexOffset < 0 ||
            frames[frameIndex].vertexCount < vertexCount) {
            return false;
        }
    }

    const QInt32 maxFrame = code::Math::javaIntMul(frameCount, 1024);
    if (maxFrame <= 0) return false;
    QInt32 normalizedFrame = frameQ10 % maxFrame;
    if (normalizedFrame < 0) {
        normalizedFrame = code::Math::javaIntAdd(normalizedFrame, maxFrame);
    }

    const QInt32 wholeFrame = normalizedFrame / 1024;
    const QInt32 firstFrame = wholeFrame % frameCount;
    const QInt32 secondFrame = (wholeFrame + 1) % frameCount;
    const QInt32 fraction = normalizedFrame % 1024;
    QInt32 vertexIndex;
    for (vertexIndex = 0; vertexIndex < vertexCount; ++vertexIndex) {
        const Vector3D& first = frameVertices[
            frames[firstFrame].vertexOffset + vertexIndex];
        const Vector3D& second = frameVertices[
            frames[secondFrame].vertexOffset + vertexIndex];
        const QInt32 ax = javaShort(first.x);
        const QInt32 ay = javaShort(first.y);
        const QInt32 az = javaShort(first.z);
        if (!morphEnabled) {
            outputVertices[vertexIndex].set(ax, ay, az);
            continue;
        }
        outputVertices[vertexIndex].set(
            interpolatedCoordinate(ax, javaShort(second.x), fraction),
            interpolatedCoordinate(ay, javaShort(second.y), fraction),
            interpolatedCoordinate(az, javaShort(second.z), fraction));
    }
    return true;
}

} // namespace Rendering
} // namespace code
