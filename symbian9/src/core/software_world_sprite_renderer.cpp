#include "software_world_sprite_renderer.h"

#include "software_texture16.h"

#include "quantum_integer.h"

#include <math.h>

namespace code {
namespace Rendering {

using code::Math::QInt32;
using code::Math::QInt64;
using code::Math::QUint32;
using code::Math::Vector3D;

namespace {

QInt32 intAnd(QInt32 left, QInt32 right)
{
    return code::Math::javaIntFromUnsigned(
        static_cast<QUint32>(left) & static_cast<QUint32>(right));
}

QInt32 intOr(QInt32 left, QInt32 right)
{
    return code::Math::javaIntFromUnsigned(
        static_cast<QUint32>(left) | static_cast<QUint32>(right));
}

QInt32 bitPattern(QUint32 value)
{
    return code::Math::javaIntFromUnsigned(value);
}

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

QInt64 javaLongRemainder(QInt64 dividend, QInt64 divisor)
{
    if (divisor == 0) return 0;
    if (dividend == static_cast<QInt64>(-9223372036854775807LL - 1LL) &&
        divisor == -1) {
        return 0;
    }
    return dividend % divisor;
}

bool validTexture(const SoftwareTextureView& texture)
{
    if (texture.pixels == 0 || texture.pixelCount <= 0 ||
        texture.width <= 0 || texture.height <= 0) {
        return false;
    }
    const QInt64 texelCount =
        static_cast<QInt64>(texture.width) * texture.height;
    return texelCount > 0 && texelCount <= texture.pixelCount;
}

bool validSprite(const SoftwareWorldSprite& sprite)
{
    if (sprite.frames == 0 || sprite.frameCount <= 0 ||
        sprite.mode < ESoftwareWorldSpriteKeyed ||
        sprite.mode > ESoftwareWorldSpriteAlpha ||
        sprite.cutoff < 0 || sprite.cutoff > 1 ||
        sprite.flickerCount < 0 || sprite.colorAnimationCount < 0 ||
        (sprite.flickerCount > 0 && sprite.flicker == 0) ||
        (sprite.colorAnimationCount > 0 && sprite.colorAnimation == 0)) {
        return false;
    }
    QInt32 frameIndex;
    for (frameIndex = 0; frameIndex < sprite.frameCount; ++frameIndex) {
        if (!validTexture(sprite.frames[frameIndex])) return false;
    }
    return true;
}

QInt32 frameForTime(const SoftwareWorldSprite& sprite,
                    QInt64 animationBeginMillis,
                    QInt64 currentTimeMillis)
{
    if (sprite.frameCount <= 1 || sprite.animationSpeed == 0.0f) return 0;

    const float periodAsFloat =
        static_cast<float>(code::Math::javaIntMul(sprite.frameCount, 1000)) /
        sprite.animationSpeed;
    const QInt64 period = javaFloatToLong(periodAsFloat);
    if (period == 0) return 0;

    QInt64 elapsed = code::Math::javaLongSub(
        currentTimeMillis, animationBeginMillis);
    if (sprite.limiter) {
        const float frameValue =
            static_cast<float>(elapsed) * sprite.animationSpeed / 1000.0f;
        QInt32 index = javaFloatToInt(frameValue);
        if (index >= sprite.frameCount) index = sprite.frameCount - 1;
        return index;
    }

    elapsed = javaLongRemainder(elapsed, period);
    const float frameValue =
        static_cast<float>(elapsed) * sprite.animationSpeed / 1000.0f;
    const QInt32 rawIndex = javaFloatToInt(frameValue);
    if (sprite.frameCount == 0) return 0;
    return rawIndex % sprite.frameCount;
}

QInt32 spritePixelLength(QInt32 width, QInt32 height, bool enabled)
{
    if (!enabled) return 0;
    QInt32 squared = code::Math::javaIntAdd(
        code::Math::javaIntMul(width, width),
        code::Math::javaIntMul(height, height));
    const QInt32 diagonal = code::Math::javaIntAdd(width, height);
    squared = code::Math::javaIntAdd(
        squared,
        code::Math::javaIntDiv(
            code::Math::javaIntMul(diagonal, diagonal), 4));
    return code::Math::javaDoubleToInt(::sqrt(static_cast<double>(squared)));
}

bool validDestination(const SoftwareRenderSurfaceView& destination)
{
    if (destination.pixels == 0 || destination.width <= 0 ||
        destination.height <= 0 || destination.stride < destination.width ||
        destination.pixelCount <= 0) {
        return false;
    }
    const QInt64 required =
        static_cast<QInt64>(destination.height - 1) * destination.stride +
        destination.width;
    return required > 0 && required <= destination.pixelCount;
}

void projectSpriteVertices(SoftwareRenderVertex vertices[2],
                          const SoftwareProjectionParameters& projection)
{
    QInt32 index;
    for (index = 0; index < 2; ++index) {
        QInt32 screenX = vertices[index].x;
        QInt32 screenY = code::Math::javaIntSub(0, vertices[index].y);
        if (vertices[index].z <= 0) {
            const QInt32 depth = code::Math::javaIntSub(0, vertices[index].z);
            const QInt32 denominatorX = code::Math::javaIntAdd(
                depth, projection.distX);
            const QInt32 denominatorY = code::Math::javaIntAdd(
                depth, projection.distY);
            screenX = code::Math::javaIntDiv(
                code::Math::javaIntMul(screenX, projection.distX),
                denominatorX);
            screenY = code::Math::javaIntDiv(
                code::Math::javaIntMul(screenY, projection.distY),
                denominatorY);
        }
        vertices[index].x = code::Math::javaIntAdd(
            screenX, projection.centreX);
        vertices[index].y = code::Math::javaIntAdd(
            screenY, projection.centreY);
    }
}

QInt32 spriteFogPower(QInt32 cameraDepth,
                      const SoftwareWorldSpriteRenderOptions& options)
{
    if (options.fogDistance == 0) return 0;
    QInt32 power = code::Math::javaIntDiv(
        code::Math::javaIntMul(
            code::Math::javaIntSub(0, cameraDepth), 255),
        options.fogDistance);
    if (power > 255) power = 255;
    if (power < 0) power = 0;
    return power;
}

QInt32 fogSpriteTexel(QInt32 texel,
                      QInt32 fogPower,
                      const SoftwareWorldSpriteRenderOptions& options)
{
    QInt32 fogRedBlue = intAnd(options.fogColor, bitPattern(0x00ff00ffU));
    QInt32 fogGreen = intAnd(options.fogColor, bitPattern(0x0000ff00U));
    if (options.standardDrawMode == 6) {
        fogRedBlue = 0;
        fogGreen = 0;
    }
    const QInt32 inversePower = code::Math::javaIntSub(255, fogPower);
    const QInt32 redBlueSum = code::Math::javaIntAdd(
        code::Math::javaIntMul(
            intAnd(texel, bitPattern(0x00ff00ffU)), inversePower),
        code::Math::javaIntMul(fogRedBlue, fogPower));
    const QInt32 greenSum = code::Math::javaIntAdd(
        code::Math::javaIntMul(
            intAnd(texel, bitPattern(0x0000ff00U)), inversePower),
        code::Math::javaIntMul(fogGreen, fogPower));
    const QInt32 packed = intOr(
        intAnd(redBlueSum, bitPattern(0xff00ff00U)),
        intAnd(greenSum, bitPattern(0x00ff0000U)));
    return code::Math::javaShiftRightUnsigned(packed, 8);
}

QInt32 alphaBlend(QInt32 source, QInt32 destination, QInt32 alpha)
{
    const QInt32 inverseAlpha = code::Math::javaIntSub(255, alpha);
    const QInt32 redBlue = code::Math::javaIntAdd(
        code::Math::javaIntMul(
            intAnd(source, bitPattern(0x00ff00ffU)), alpha),
        code::Math::javaIntMul(
            intAnd(destination, bitPattern(0x00ff00ffU)), inverseAlpha));
    const QInt32 green = code::Math::javaIntAdd(
        code::Math::javaIntMul(
            intAnd(source, bitPattern(0x0000ff00U)), alpha),
        code::Math::javaIntMul(
            intAnd(destination, bitPattern(0x0000ff00U)), inverseAlpha));
    const QInt32 packed = intOr(
        intAnd(redBlue, bitPattern(0xff00ff00U)),
        intAnd(green, bitPattern(0x00ff0000U)));
    return code::Math::javaShiftRightUnsigned(packed, 8);
}

QInt32 addSpriteTexel(QInt32 texel, QInt32 destination)
{
    QInt32 color = code::Math::javaIntAdd(
        intAnd(texel, bitPattern(0x00fefefeU)),
        intAnd(destination, bitPattern(0x00fefefeU)));
    const QInt32 carry = code::Math::javaIntMul(
        intAnd(code::Math::javaShiftRight(color, 8),
               bitPattern(0x00010101U)),
        255);
    color = intOr(color, carry);
    return intAnd(color, bitPattern(0x00ffffffU));
}

QInt32 multiplySpriteTexel(QInt32 brightness,
                           QInt32 destination,
                           QInt32 color)
{
    const QInt32 original = destination;
    QInt32 redBlueMask = 0;
    QInt32 greenMask = 0;
    QInt32 productMaskRedBlue = 0;
    QInt32 productMaskGreen = 0;
    QInt32 preserveMask = 0;

    switch (color) {
    case 0:
        redBlueMask = bitPattern(0x00ff00ffU);
        greenMask = bitPattern(0x0000ff00U);
        productMaskRedBlue = bitPattern(0xfe00fe00U);
        productMaskGreen = bitPattern(0x00fe0000U);
        break;
    case 1:
        redBlueMask = bitPattern(0x00ff0000U);
        productMaskRedBlue = bitPattern(0xfe000000U);
        preserveMask = bitPattern(0x0000ffffU);
        break;
    case 2:
        greenMask = bitPattern(0x0000ff00U);
        productMaskGreen = bitPattern(0x00fe0000U);
        preserveMask = bitPattern(0x00ff00ffU);
        break;
    case 3:
        redBlueMask = bitPattern(0x000000ffU);
        productMaskRedBlue = bitPattern(0x0000fe00U);
        preserveMask = bitPattern(0x00ffff00U);
        break;
    case 4:
        redBlueMask = bitPattern(0x00ff00ffU);
        productMaskRedBlue = bitPattern(0xfe00fe00U);
        preserveMask = bitPattern(0x0000ff00U);
        break;
    case 5:
        redBlueMask = bitPattern(0x00ff0000U);
        greenMask = bitPattern(0x0000ff00U);
        productMaskRedBlue = bitPattern(0xfe000000U);
        productMaskGreen = bitPattern(0x00fe0000U);
        preserveMask = bitPattern(0x000000ffU);
        break;
    case 6:
        redBlueMask = bitPattern(0x000000ffU);
        greenMask = bitPattern(0x0000ff00U);
        productMaskRedBlue = bitPattern(0x0000fe00U);
        productMaskGreen = bitPattern(0x00fe0000U);
        preserveMask = bitPattern(0x00ff0000U);
        break;
    default:
        return destination;
    }

    QInt32 packed = 0;
    if (redBlueMask != 0) {
        packed = intOr(
            packed,
            intAnd(code::Math::javaIntMul(
                       intAnd(destination, redBlueMask), brightness),
                   productMaskRedBlue));
    }
    if (greenMask != 0) {
        packed = intOr(
            packed,
            intAnd(code::Math::javaIntMul(
                       intAnd(destination, greenMask), brightness),
                   productMaskGreen));
    }
    QInt32 scaled = code::Math::javaShiftRightUnsigned(packed, 6);
    const QInt32 clampBits = code::Math::javaIntMul(
        intAnd(code::Math::javaShiftRight(scaled, 8),
               bitPattern(0x00030303U)),
        255);
    scaled = intOr(scaled, clampBits);
    return intAnd(intOr(scaled, intAnd(original, preserveMask)),
                  bitPattern(0x00ffffffU));
}

/*
 * The reference address computation, kept in one place so the 32-bit and
 * 16-bit paths agree on which texel a span position maps to.
 */
QInt32 spriteTexelIndex(const SoftwareTextureView& image,
                        QInt32 fixedU,
                        QInt32 fixedV)
{
    const QInt32 integerV = intAnd(fixedV, -4096);
    const QInt32 rowOffset = code::Math::javaIntMul(integerV, image.width);
    const QInt32 fixedIndex = code::Math::javaIntAdd(rowOffset, fixedU);
    const QInt32 shifted = code::Math::javaShiftRight(fixedIndex, 12);
    const QInt32 positiveIndex =
        intAnd(shifted, static_cast<QInt32>(2147483647));
    return positiveIndex % image.pixelCount;
}

QInt32 sampleSpriteTexel(const SoftwareTextureView& image,
                         QInt32 fixedU,
                         QInt32 fixedV)
{
    return image.pixels[spriteTexelIndex(image, fixedU, fixedV)];
}

} // anonymous namespace

SoftwareWorldSprite::SoftwareWorldSprite()
    : frames(0), frames16(0), frameCount(0), scale(5), textureScale(2),
      offsetX(0),
      offsetY(0), mode(ESoftwareWorldSpriteKeyed), cutoff(0), baseColor(0),
      mirrorX(false), mirrorY(false), fog(false), limiter(false),
      animationSpeed(1.0f), flicker(0), flickerCount(0),
      colorAnimation(0), colorAnimationCount(0)
{
}

SoftwareWorldSpriteInstance::SoftwareWorldSpriteInstance()
    : sprite(0), position(0, 0, 0), addSz(0), roomId(-1), visible(true),
      hideWhenUnusable(false), lastActivateMillis(-1), timeToResetMillis(0),
      animationBeginMillis(0), frame100(0), currentColor(0),
      colorInitialized(false), frameIndex(0),
      screenX1(0), screenY1(0), screenX2(0), screenY2(0), cameraDepth(0),
      sortDepth(0), queued(false)
{
}

SoftwareWorldSpriteRenderOptions::SoftwareWorldSpriteRenderOptions()
    : worldToCamera(0), currentTimeMillis(0), gameTimeMillis(0),
      frameTime(1), fogDistance(1),
      fogColor(0), standardDrawMode(0), useAutoWMove(true)
{
}

SoftwareWorldSpriteRenderStats::SoftwareWorldSpriteRenderStats()
    : spritesVisited(0), spritesQueued(0), spritesDrawn(0), spritesCulled(0),
      spritesHidden(0), spritesFlickered(0)
{
}

bool SoftwareWorldSpriteRenderer::prepare(
    SoftwareWorldSpriteInstance& instance,
    const code::Math::Matrix* worldToCamera,
    const SoftwareProjectionParameters& projection,
    QInt32 viewportX1,
    QInt32 viewportY1,
    QInt32 viewportX2,
    QInt32 viewportY2,
    QInt32 farDistance,
    const SoftwareWorldSpriteRenderOptions& options,
    bool& shouldQueue,
    SoftwareWorldSpriteRenderStats& stats)
{
    shouldQueue = false;
    instance.queued = false;
    ++stats.spritesVisited;
    if (instance.sprite == 0 || !validSprite(*instance.sprite) ||
        viewportX1 > viewportX2 || viewportY1 > viewportY2 ||
        projection.distX <= 0 || projection.distY <= 0) {
        return false;
    }

    const SoftwareWorldSprite& sprite = *instance.sprite;
    if (!instance.visible ||
        (instance.lastActivateMillis >= 0 && instance.hideWhenUnusable &&
         code::Math::javaLongAdd(
             instance.lastActivateMillis, instance.timeToResetMillis) >
             options.gameTimeMillis)) {
        ++stats.spritesHidden;
        return true;
    }

    if (!instance.colorInitialized) {
        instance.currentColor = sprite.baseColor;
        instance.colorInitialized = true;
    }

    instance.frameIndex = frameForTime(
        sprite, instance.animationBeginMillis, options.currentTimeMillis);
    if (instance.frameIndex < 0 || instance.frameIndex >= sprite.frameCount) {
        return false;
    }
    const SoftwareTextureView& image = sprite.frames[instance.frameIndex];

    SoftwareRenderVertex worldPosition(
        instance.position.x, instance.position.y, instance.position.z,
        255, 255, 255, 0, 0);
    SoftwareRenderVertex cameraPosition;
    SoftwareVertexPipeline::transformVertex(
        worldPosition, worldToCamera, cameraPosition);

    const QInt32 horizontalScale = sprite.textureScale == 0 ? 2 : 1;
    const QInt32 verticalScale = sprite.textureScale < 2 ? 2 : 1;
    const QInt32 spriteWidth = code::Math::javaIntMul(
        code::Math::javaIntMul(image.width, sprite.scale), horizontalScale);
    const QInt32 rawHeight = code::Math::javaIntMul(image.height, sprite.scale);
    const QInt32 spriteHeight =
        code::Math::javaIntMul(rawHeight, verticalScale);

    QInt32 positionX = code::Math::javaIntAdd(
        cameraPosition.x,
        code::Math::javaIntMul(sprite.offsetX, horizontalScale));
    QInt32 positionY = code::Math::javaIntAdd(
        cameraPosition.y,
        code::Math::javaIntMul(sprite.offsetY, verticalScale));
    positionX = code::Math::javaIntSub(positionX, spriteWidth / 2);
    positionY = code::Math::javaIntAdd(positionY, spriteHeight);

    QInt32 sizeX = code::Math::javaIntAdd(positionX, spriteWidth);
    QInt32 sizeY = code::Math::javaIntSub(positionY, spriteHeight);
    if (cameraPosition.z > 0 && sprite.cutoff == 1) {
        sizeY = code::Math::javaIntAdd(sizeY, cameraPosition.z);
    }

    SoftwareRenderVertex projected[2] = {
        SoftwareRenderVertex(positionX, positionY, cameraPosition.z,
                             255, 255, 255, 0, 0),
        SoftwareRenderVertex(sizeX, sizeY, cameraPosition.z,
                             255, 255, 255, 0, 0)
    };
    projectSpriteVertices(projected, projection);

    instance.screenX1 = projected[0].x;
    instance.screenY1 = projected[0].y;
    instance.screenX2 = projected[1].x;
    instance.screenY2 = projected[1].y;
    instance.cameraDepth = cameraPosition.z;
    const QInt32 projectedWidth = code::Math::javaIntSub(
        instance.screenX2, instance.screenX1);
    const QInt32 projectedHeight = code::Math::javaIntSub(
        instance.screenY2, instance.screenY1);
    const QInt32 screenRadius = spritePixelLength(
        projectedWidth, projectedHeight, options.useAutoWMove);
    instance.sortDepth = code::Math::javaIntSub(
        code::Math::javaIntMul(cameraPosition.z, 4), screenRadius);

    if (code::Math::javaIntSub(0, cameraPosition.z) > farDistance) {
        ++stats.spritesCulled;
        return true;
    }

    const QInt32 sizeForCutoff =
        code::Math::javaIntMul(rawHeight, 4);
    if (sprite.cutoff == 0) {
        if (instance.sortDepth > 0 ||
            instance.screenX1 >= viewportX2 ||
            instance.screenY1 >= viewportY2 ||
            instance.screenX2 <= viewportX1 ||
            instance.screenY2 <= viewportY1) {
            ++stats.spritesCulled;
            return true;
        }
    } else if (sprite.cutoff == 1) {
        const QInt32 scaleMargin = sprite.scale;
        if (instance.sortDepth > sizeForCutoff ||
            code::Math::javaIntSub(instance.screenX1, scaleMargin) >=
                viewportX2 ||
            code::Math::javaIntSub(instance.screenY1, scaleMargin) >=
                viewportY2 ||
            code::Math::javaIntAdd(instance.screenX2, scaleMargin) <=
                viewportX1 ||
            code::Math::javaIntAdd(instance.screenY2, scaleMargin) <=
                viewportY1) {
            ++stats.spritesCulled;
            return true;
        }
    } else {
        ++stats.spritesCulled;
        return true;
    }

    const QInt32 oldFrame = instance.frame100;
    const QInt32 frameAdvance = code::Math::javaIntDiv(
        code::Math::javaIntMul(100, options.frameTime), 50);
    instance.frame100 = code::Math::javaIntAdd(oldFrame, frameAdvance);

    QInt32 patternIndex = 0;
    if (sprite.flickerCount > 0) {
        patternIndex = (oldFrame / 125) % sprite.flickerCount;
        if (patternIndex < 0) {
            patternIndex = code::Math::javaIntAdd(
                patternIndex, sprite.flickerCount);
        }
        if (!sprite.flicker[patternIndex]) {
            ++stats.spritesFlickered;
            return true;
        }
    }

    if (sprite.colorAnimationCount > 0) {
        patternIndex = (oldFrame / 125) % sprite.colorAnimationCount;
        if (patternIndex < 0) {
            patternIndex = code::Math::javaIntAdd(
                patternIndex, sprite.colorAnimationCount);
        }
        instance.currentColor = sprite.colorAnimation[patternIndex];
    }

    instance.sortDepth = code::Math::javaIntAdd(
        instance.sortDepth, instance.addSz);
    instance.queued = true;
    shouldQueue = true;
    ++stats.spritesQueued;
    return true;
}

bool SoftwareWorldSpriteRenderer::draw(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareWorldSpriteInstance& instance,
    const SoftwareWorldSpriteRenderOptions& options)
{
    if (!instance.queued || instance.sprite == 0 ||
        !validSprite(*instance.sprite) || !validDestination(destination) ||
        instance.frameIndex < 0 ||
        instance.frameIndex >= instance.sprite->frameCount) {
        return false;
    }

    const SoftwareWorldSprite& sprite = *instance.sprite;
    const SoftwareTextureView& image = sprite.frames[instance.frameIndex];
    QInt32 xStart = instance.screenX1;
    QInt32 yStart = instance.screenY1;
    QInt32 xEnd = instance.screenX2;
    QInt32 yEnd = instance.screenY2;
    if (xStart > xEnd) {
        const QInt32 temporary = xStart;
        xStart = xEnd;
        xEnd = temporary;
    }
    if (yStart > yEnd) {
        const QInt32 temporary = yStart;
        yStart = yEnd;
        yEnd = temporary;
    }
    if (xStart == xEnd || yStart == yEnd) return true;

    QInt32 u1 = 0;
    QInt32 v1 = 0;
    QInt32 u2 = image.width;
    QInt32 v2 = image.height;
    if (sprite.mirrorX) {
        u1 = image.width;
        u2 = 0;
    }
    if (sprite.mirrorY) {
        v1 = image.height;
        v2 = 0;
    }
    u1 = code::Math::javaShiftLeft(u1, 12);
    u2 = code::Math::javaShiftLeft(u2, 12);
    v1 = code::Math::javaShiftLeft(v1, 12);
    v2 = code::Math::javaShiftLeft(v2, 12);
    const QInt32 du = code::Math::javaIntDiv(
        code::Math::javaIntSub(u2, u1),
        code::Math::javaIntSub(xEnd, xStart));
    const QInt32 dv = code::Math::javaIntDiv(
        code::Math::javaIntSub(v2, v1),
        code::Math::javaIntSub(yEnd, yStart));
    QInt32 uStart = u1;
    QInt32 vStart = v1;

    if (yStart < 0) {
        vStart = code::Math::javaIntSub(
            vStart, code::Math::javaIntMul(dv, yStart));
        yStart = 0;
    }
    if (yEnd > destination.height) yEnd = destination.height;
    if (xStart < 0) {
        uStart = code::Math::javaIntSub(
            u1, code::Math::javaIntMul(du, xStart));
        xStart = 0;
    }
    if (xEnd > destination.width) xEnd = destination.width;
    if (xStart >= xEnd || yStart >= yEnd) return true;

    const QInt32 colorMode = instance.currentColor;
    const bool alphaMode = sprite.mode == ESoftwareWorldSpriteAlpha &&
                           image.hasAlphaMixing;
    const bool alphaFogMode = alphaMode && sprite.fog;
    /*
     * ARGB4444 is used only by the alpha-blend path, and only when the
     * sprite actually has a 16-bit frame of matching size. Everything else
     * keeps sampling the 32-bit image, so keyed/add/multiply are untouched.
     */
    const SoftwareTexture16View* frame16 = 0;
    if (alphaMode && !alphaFogMode && sprite.frames16 != 0 &&
        instance.frameIndex >= 0 && instance.frameIndex < sprite.frameCount) {
        const SoftwareTexture16View& candidate =
            sprite.frames16[instance.frameIndex];
        if (candidate.pixels != 0 && candidate.is4444 &&
            candidate.width == image.width &&
            candidate.height == image.height) {
            frame16 = &candidate;
        }
    }
    const QInt32 fogPower = sprite.fog
        ? spriteFogPower(instance.cameraDepth, options) : 0;

    QInt32 y;
    for (y = yStart; y < yEnd; ++y) {
        const QInt32 rowOffset = code::Math::javaIntMul(y, destination.stride);
        QInt32 texU = uStart;
        QInt32 x;
        for (x = xStart; x < xEnd; ++x) {
            /*
             * Keep the reference's wrapping index math for both paths so the
             * two agree bit-for-bit on where a texel comes from; only the
             * texel decode differs.
             */
            QInt32 texel;
            if (frame16 != 0) {
                const QInt32 index = spriteTexelIndex(image, texU, vStart);
                texel = argb8888From4444(
                    frame16->pixels[static_cast<size_t>(index)]);
            } else {
                texel = sampleSpriteTexel(image, texU, vStart);
            }
            const QInt32 pixelIndex = code::Math::javaIntAdd(rowOffset, x);
            QInt32& target = destination.pixels[pixelIndex];

            if (sprite.mode == ESoftwareWorldSpriteAdd) {
                target = addSpriteTexel(texel, target);
            } else if (sprite.mode == ESoftwareWorldSpriteMultiply &&
                       colorMode >= 0 && colorMode <= 6) {
                const QInt32 brightness = intAnd(texel, 255);
                if (brightness != 69) {
                    target = multiplySpriteTexel(
                        brightness, target, colorMode);
                }
            } else if (alphaMode) {
                const QInt32 alpha = intAnd(
                    code::Math::javaShiftRight(texel, 24), 255);
                if (alpha != 0) {
                    const QInt32 source = alphaFogMode
                        ? fogSpriteTexel(texel, fogPower, options) : texel;
                    target = alphaBlend(source, target, alpha);
                }
            } else if (texel != 0) {
                const QInt32 source = sprite.fog
                    ? fogSpriteTexel(texel, fogPower, options) : texel;
                target = intAnd(source, bitPattern(0x00ffffffU));
            }
            texU = code::Math::javaIntAdd(texU, du);
        }
        vStart = code::Math::javaIntAdd(vStart, dv);
    }
    return true;
}

} // namespace Rendering
} // namespace code
