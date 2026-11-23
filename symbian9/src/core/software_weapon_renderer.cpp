#include "software_weapon_renderer.h"

#include "quantum_integer.h"

#if defined(QUANTUM_LEVEL41_OMAP1710_EFFECTS_ASM) && defined(__SYMBIAN32__)
#include "omap1710_effects.h"
#endif

namespace code {
namespace Rendering {

namespace {

using code::Math::QInt32;
using code::Math::QInt64;
using code::Math::QUint16;
using code::Math::QUint64;
using code::Math::QUint32;

const QInt32 KReferenceWidth = 240;
const QInt32 KReferenceHeight = 256;
const QInt32 KImageFixedShift = 16;
const QUint32 KImageFixedMask = 0xffffU;
const QInt32 KMaximumViewportDimension = 8192;
const QInt32 KMaximumPreparedImagePixels = 512 * 1024;

enum TSpriteAnchor {
    ESpriteAnchorLeftTop = 0,
    ESpriteAnchorLeftBottom,
    ESpriteAnchorRightBottom,
    ESpriteAnchorCenterBottom,
    ESpriteAnchorCenter
};

QInt32 javaShort(QInt32 value)
{
    const QUint32 bits = static_cast<QUint32>(value) & 0xffffU;
    if (bits <= 0x7fffU) return static_cast<QInt32>(bits);
    return static_cast<QInt32>(bits) - 65536;
}

QInt32 javaFloatToInt(float value)
{
    if (value != value) return 0;
    if (value >= 2147483648.0f) return 2147483647;
    if (value <= -2147483648.0f) return static_cast<QInt32>(-2147483647 - 1);
    return static_cast<QInt32>(value);
}

bool validScale(float value)
{
    return value == value && value >= 0.0f && value <= 64.0f;
}

bool validateImage(const SoftwareTextureView& image, bool& present)
{
    present = false;
    if (image.pixels == 0 && image.width == 0 && image.height == 0 &&
        image.pixelCount == 0) {
        return true;
    }
    if (image.pixels == 0 || image.width <= 0 || image.height <= 0 ||
        image.width > KMaximumViewportDimension ||
        image.height > KMaximumViewportDimension || image.pixelCount <= 0) {
        return false;
    }
    const QInt64 required = static_cast<QInt64>(image.width) *
                            static_cast<QInt64>(image.height);
    if (required <= 0 || required > image.pixelCount ||
        required > KMaximumPreparedImagePixels) {
        return false;
    }
    present = true;
    return true;
}

bool imageSizeForScale(const SoftwareTextureView& source,
                       float scaleX,
                       float scaleY,
                       SoftwareWeaponImageSize& size)
{
    size = SoftwareWeaponImageSize();
    bool present = false;
    if (!validateImage(source, present)) return false;
    if (!present) return true;
    if (!validScale(scaleX) || !validScale(scaleY)) return false;

    const float widthValue = static_cast<float>(source.width) * scaleX;
    const float heightValue = static_cast<float>(source.height) * scaleY;
    if (widthValue > static_cast<float>(KMaximumViewportDimension) ||
        heightValue > static_cast<float>(KMaximumViewportDimension)) {
        return false;
    }
    const QInt32 width = javaFloatToInt(widthValue);
    const QInt32 height = javaFloatToInt(heightValue);
    if (width <= 0 || height <= 0) return true;

    const QInt64 pixelCount = static_cast<QInt64>(width) * height;
    if (pixelCount <= 0 || pixelCount > KMaximumPreparedImagePixels) {
        return false;
    }
    size.width = width;
    size.height = height;
    size.pixelCount = static_cast<QInt32>(pixelCount);
    return true;
}

bool exactImageSize(const SoftwareTextureView& source,
                    QInt32 width,
                    QInt32 height,
                    SoftwareWeaponImageSize& size)
{
    size = SoftwareWeaponImageSize();
    bool present = false;
    if (!validateImage(source, present)) return false;
    if (!present) return true;
    if (width <= 0 || height <= 0 ||
        width > KMaximumViewportDimension ||
        height > KMaximumViewportDimension) {
        return false;
    }
    const QInt64 pixelCount = static_cast<QInt64>(width) * height;
    if (pixelCount <= 0 || pixelCount > KMaximumPreparedImagePixels) {
        return false;
    }
    size.width = width;
    size.height = height;
    size.pixelCount = static_cast<QInt32>(pixelCount);
    return true;
}

bool sameImageSize(const SoftwareWeaponImageSize& left,
                   const SoftwareWeaponImageSize& right)
{
    return left.width == right.width && left.height == right.height &&
           left.pixelCount == right.pixelCount;
}

bool requirementsMatch(const SoftwareWeaponSpriteRequirements& left,
                       const SoftwareWeaponSpriteRequirements& right)
{
    return left.viewportWidth == right.viewportWidth &&
           left.viewportHeight == right.viewportHeight &&
           sameImageSize(left.weapon, right.weapon) &&
           sameImageSize(left.fire, right.fire) &&
           sameImageSize(left.sight, right.sight) &&
           sameImageSize(left.sightWeapon, right.sightWeapon);
}

QUint32 channel(QUint32 pixel, unsigned int shift)
{
    return (pixel >> shift) & 0xffU;
}

QUint32 interpolateChannel(QUint32 topLeft,
                           QUint32 topRight,
                           QUint32 bottomLeft,
                           QUint32 bottomRight,
                           unsigned int shift,
                           QUint32 fractionX,
                           QUint32 fractionY)
{
    const QUint32 inverseX = KImageFixedMask - fractionX;
    const QUint32 inverseY = KImageFixedMask - fractionY;
    const QUint32 top =
        ((channel(topLeft, shift) * inverseX +
          channel(topRight, shift) * fractionX) >> KImageFixedShift);
    const QUint32 bottom =
        ((channel(bottomLeft, shift) * inverseX +
          channel(bottomRight, shift) * fractionX) >> KImageFixedShift);
    return (top * inverseY + bottom * fractionY) >> KImageFixedShift;
}

bool validPreparedImage(const SoftwareTextureView& image)
{
    bool present = false;
    return validateImage(image, present);
}

QUint32 divide255Rounded(QUint32 value)
{
    /* Exact round-to-nearest division for the bounded 0..65025 alpha sum,
       without a software integer divide on ARMv5. */
    value += 128U;
    return (value + (value >> 8U)) >> 8U;
}

void blendOverRgb(QInt32* destination, QUint32 source)
{
    const QUint32 alpha = (source >> 24U) & 0xffU;
    if (alpha == 0U) return;

    const QUint32 sourceRgb = source & 0x00ffffffU;
    if (alpha == 255U) {
        *destination = code::Math::javaIntFromUnsigned(sourceRgb);
        return;
    }

    const QUint32 destinationRgb =
        static_cast<QUint32>(*destination) & 0x00ffffffU;
    const QUint32 inverseAlpha = 255U - alpha;
    const QUint32 red = divide255Rounded(
        ((sourceRgb >> 16U) & 0xffU) * alpha +
        ((destinationRgb >> 16U) & 0xffU) * inverseAlpha);
    const QUint32 green = divide255Rounded(
        ((sourceRgb >> 8U) & 0xffU) * alpha +
        ((destinationRgb >> 8U) & 0xffU) * inverseAlpha);
    const QUint32 blue = divide255Rounded(
        (sourceRgb & 0xffU) * alpha +
        (destinationRgb & 0xffU) * inverseAlpha);
    const QUint32 blended = (red << 16U) | (green << 8U) | blue;
    *destination = code::Math::javaIntFromUnsigned(blended);
}

void drawWeaponRow(QInt32* destination,
                   const QInt32* source,
                   QInt32 pixelCount,
                   QInt32 sourceStep)
{
#if defined(QUANTUM_LEVEL41_OMAP1710_EFFECTS_ASM) && defined(__SYMBIAN32__)
    quantum_omap1710_blend_argb_row(
        destination, source, pixelCount, sourceStep);
#else
    QInt32 pixel;
    for (pixel = 0; pixel < pixelCount; ++pixel) {
        blendOverRgb(destination + pixel,
                     static_cast<QUint32>(source[
                         static_cast<QInt64>(pixel) * sourceStep]));
    }
#endif
}

/*
 * 16-bit counterpart. The texel is expanded to 0xAARRGGBB first, so the
 * blend below is the reference arithmetic on an equivalent colour: 1555
 * contributes a 1-bit alpha (0 or 255), 4444 a 4-bit alpha expanded to
 * 0..255. No assembly kernel is used because the existing one is written
 * for 32-bit ARGB rows; the per-pixel work here is a shift and a table-free
 * expand.
 */
void drawWeaponRow16(QInt32* destination,
                     const QUint16* source,
                     QInt32 pixelCount,
                     QInt32 sourceStep,
                     bool is4444)
{
    QInt32 pixel;
    for (pixel = 0; pixel < pixelCount; ++pixel) {
        const QUint16 texel =
            source[static_cast<QInt64>(pixel) * sourceStep];
        const QInt32 expanded = is4444 ? argb8888From4444(texel)
                                       : rgb8888From1555(texel);
        blendOverRgb(destination + pixel,
                     static_cast<QUint32>(expanded));
    }
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

bool drawSprite(const SoftwareRenderSurfaceView& destination,
                const SoftwareWeaponSpriteImage& prepared,
                QInt32 anchorX,
                QInt32 anchorY,
                TSpriteAnchor anchor,
                bool mirrorX,
                QInt32 viewportWidth,
                QInt32 viewportHeight,
                QInt32 screenY)
{
    /* The 32-bit view defines presence and geometry; the 16-bit view, when
       usable, only changes where texels are read from. */
    const SoftwareTextureView& image = prepared.view;
    bool present = false;
    if (!validateImage(image, present)) return false;
    if (!present) return true;

    const SoftwareTexture16View* source16 = 0;
    if (prepared.sixteen.pixels != 0 &&
        (prepared.sixteen.is1555 || prepared.sixteen.is4444) &&
        prepared.sixteen.width == image.width &&
        prepared.sixteen.height == image.height &&
        prepared.sixteen.pixelCount >= image.width * image.height) {
        /* 1555 only reproduces art whose alpha is already a 0/255 cut;
           4444 covers the translucent cases. */
        if (prepared.sixteen.is4444 || image.hasTransparentTexels) {
            source16 = &prepared.sixteen;
        }
    }

    QInt32 left = anchorX;
    QInt32 top = anchorY;
    if (anchor == ESpriteAnchorRightBottom) {
        left = code::Math::javaIntSub(anchorX, image.width);
        top = code::Math::javaIntSub(anchorY, image.height);
    } else if (anchor == ESpriteAnchorLeftBottom) {
        top = code::Math::javaIntSub(anchorY, image.height);
    } else if (anchor == ESpriteAnchorCenterBottom) {
        left = code::Math::javaIntSub(anchorX, image.width / 2);
        top = code::Math::javaIntSub(anchorY, image.height);
    } else if (anchor == ESpriteAnchorCenter) {
        left = code::Math::javaIntSub(anchorX, image.width / 2);
        top = code::Math::javaIntSub(anchorY, image.height / 2);
    }

    QInt64 clipLeft = 0;
    QInt64 clipTop = screenY;
    QInt64 clipRight = viewportWidth;
    QInt64 clipBottom = static_cast<QInt64>(screenY) + viewportHeight;
    if (clipLeft < 0) clipLeft = 0;
    if (clipTop < 0) clipTop = 0;
    if (clipRight > destination.width) clipRight = destination.width;
    if (clipBottom > destination.height) clipBottom = destination.height;
    if (clipRight <= clipLeft || clipBottom <= clipTop) return true;

    QInt64 firstX = left;
    QInt64 firstY = top;
    QInt64 lastX = static_cast<QInt64>(left) + image.width;
    QInt64 lastY = static_cast<QInt64>(top) + image.height;
    if (firstX < clipLeft) firstX = clipLeft;
    if (firstY < clipTop) firstY = clipTop;
    if (lastX > clipRight) lastX = clipRight;
    if (lastY > clipBottom) lastY = clipBottom;
    if (firstX >= lastX || firstY >= lastY) return true;

    const QInt32 sourceStep = mirrorX ? -1 : 1;
    const QInt32 rowPixelCount = static_cast<QInt32>(lastX - firstX);
    const QInt32 firstLocalX = static_cast<QInt32>(firstX - left);
    const QInt32 firstSourceX = mirrorX
        ? image.width - 1 - firstLocalX : firstLocalX;
    QInt64 screenY64;
    for (screenY64 = firstY; screenY64 < lastY; ++screenY64) {
        const QInt32 localY = static_cast<QInt32>(screenY64 - top);
        const QInt32 sourceIndex = localY * image.width + firstSourceX;
        const QInt32 rowOffset = static_cast<QInt32>(screenY64) *
                                 destination.stride;
        if (source16 != 0) {
            drawWeaponRow16(destination.pixels + rowOffset +
                                static_cast<QInt32>(firstX),
                            source16->pixels + sourceIndex, rowPixelCount,
                            sourceStep, source16->is4444);
        } else {
            drawWeaponRow(destination.pixels + rowOffset +
                              static_cast<QInt32>(firstX),
                          image.pixels + sourceIndex, rowPixelCount,
                          sourceStep);
        }
    }
    return true;
}

bool drawNormalWeapon(const SoftwareRenderSurfaceView& destination,
                      const SoftwareWeaponSpriteSet& sprites,
                      const SoftwareWeaponDrawSettings& settings)
{
    QInt32 drawDx = settings.dx;
    QInt32 drawDy = settings.dy;
    if (settings.meleeAnimation) {
        drawDx = javaShort(code::Math::javaIntSub(
            sprites.maxOffsetX, settings.dx));
        drawDy = javaShort(code::Math::javaIntSub(
            sprites.maxOffsetY, settings.dy));
    }

    const QInt32 oldWidth = settings.viewportWidth;
    QInt32 width = settings.viewportWidth;
    QInt32 x = 0;
    if (settings.customPosition) {
        const QInt32 referenceWidth = code::Math::javaIntDiv(
            code::Math::javaIntMul(settings.viewportHeight, KReferenceWidth),
            KReferenceHeight);
        const QInt32 widthDelta = code::Math::javaIntSub(
            settings.viewportWidth, referenceWidth);
        const float xValue =
            static_cast<float>(widthDelta) * settings.customPositionX / 2.0f;
        x = javaFloatToInt(xValue);
        const QInt32 referenceProduct = code::Math::javaIntMul(
            settings.viewportHeight, KReferenceWidth);
        const float widthValue =
            static_cast<float>(referenceProduct) *
            settings.customPositionX / static_cast<float>(KReferenceHeight);
        width = javaFloatToInt(widthValue);
    }

    if (settings.centreAlign && sprites.weapon.view.pixels != 0) {
        x = code::Math::javaIntAdd(x, sprites.weapon.view.width / 2);
    }

    const bool drawRightHand = settings.twoHands || !settings.leftHand;
    const bool drawLeftHand = settings.twoHands || settings.leftHand;
    const QInt32 weaponRight = code::Math::javaIntAdd(
        code::Math::javaIntAdd(width, drawDx), x);
    const QInt32 weaponBottom = code::Math::javaIntAdd(
        code::Math::javaIntAdd(settings.viewportHeight, drawDy),
        settings.screenY);

    if (!settings.newFireAnimation) {
        if (drawRightHand) {
            if (settings.firing && sprites.fire.view.pixels != 0) {
                QInt32 fireX = code::Math::javaIntAdd(
                    code::Math::javaIntSub(width, sprites.dxFire), drawDx);
                fireX = code::Math::javaIntAdd(fireX, x);
                QInt32 fireY = code::Math::javaIntAdd(
                    code::Math::javaIntSub(settings.viewportHeight,
                                           sprites.dyFire), drawDy);
                fireY = code::Math::javaIntAdd(fireY, settings.screenY);
                if (!drawSprite(destination, sprites.fire, fireX, fireY,
                                ESpriteAnchorCenter, false,
                                settings.viewportWidth, settings.viewportHeight,
                                settings.screenY)) {
                    return false;
                }
            }
            if (sprites.weapon.view.pixels != 0 &&
                !drawSprite(destination, sprites.weapon, weaponRight,
                            weaponBottom, ESpriteAnchorRightBottom, false,
                            settings.viewportWidth, settings.viewportHeight,
                            settings.screenY)) {
                return false;
            }
        }

        if (drawLeftHand) {
            if (settings.firing && sprites.fire.view.pixels != 0) {
                QInt32 unmirroredFireX = code::Math::javaIntAdd(
                    code::Math::javaIntSub(width, sprites.dxFire), drawDx);
                unmirroredFireX = code::Math::javaIntAdd(unmirroredFireX, x);
                const QInt32 fireX = code::Math::javaIntSub(
                    oldWidth, unmirroredFireX);
                QInt32 fireY = code::Math::javaIntAdd(
                    code::Math::javaIntSub(settings.viewportHeight,
                                           sprites.dyFire), drawDy);
                fireY = code::Math::javaIntAdd(fireY, settings.screenY);
                if (!drawSprite(destination, sprites.fire, fireX, fireY,
                                ESpriteAnchorCenter, true,
                                settings.viewportWidth, settings.viewportHeight,
                                settings.screenY)) {
                    return false;
                }
            }
            const QInt32 weaponLeft = code::Math::javaIntSub(
                oldWidth, weaponRight);
            if (sprites.weapon.view.pixels != 0 &&
                !drawSprite(destination, sprites.weapon, weaponLeft,
                            weaponBottom, ESpriteAnchorLeftBottom, true,
                            settings.viewportWidth, settings.viewportHeight,
                            settings.screenY)) {
                return false;
            }
        }
        return true;
    }

    const SoftwareWeaponSpriteImage& replacement =
        settings.firing ? sprites.fire : sprites.weapon;
    if (drawRightHand && replacement.view.pixels != 0 &&
        !drawSprite(destination, replacement, weaponRight, weaponBottom,
                    ESpriteAnchorRightBottom, false,
                    settings.viewportWidth, settings.viewportHeight,
                    settings.screenY)) {
        return false;
    }
    if (drawLeftHand && replacement.view.pixels != 0) {
        const QInt32 weaponLeft = code::Math::javaIntSub(
            oldWidth, weaponRight);
        if (!drawSprite(destination, replacement, weaponLeft, weaponBottom,
                        ESpriteAnchorLeftBottom, true,
                        settings.viewportWidth, settings.viewportHeight,
                        settings.screenY)) {
            return false;
        }
    }
    return true;
}

bool drawSight(const SoftwareRenderSurfaceView& destination,
               const SoftwareWeaponSpriteSet& sprites,
               const SoftwareWeaponDrawSettings& settings)
{
    if (settings.ignoreSightOnDraw &&
        !drawNormalWeapon(destination, sprites, settings)) {
        return false;
    }

    if (sprites.sight.view.pixels == 0 && sprites.sightWeapon.view.pixels == 0) {
        return true;
    }

    QInt32 drawDx = settings.dx;
    QInt32 drawDy = settings.dy;
    if (settings.meleeAnimation) {
        drawDx = javaShort(code::Math::javaIntSub(
            sprites.maxOffsetX, settings.dx));
        drawDy = javaShort(code::Math::javaIntSub(
            sprites.maxOffsetY, settings.dy));
    }
    (void)drawDx; // Weapon.drawSight transforms both short fields; x is unused.

    if (sprites.sightWeapon.view.pixels != 0) {
        const QInt32 sightX = settings.viewportWidth / 2;
        const QInt32 sightY = code::Math::javaIntAdd(
            code::Math::javaIntAdd(settings.viewportHeight, settings.screenY),
            drawDy);
        if (!settings.twoHands) {
            if (!drawSprite(destination, sprites.sightWeapon, sightX, sightY,
                            ESpriteAnchorCenterBottom, false,
                            settings.viewportWidth, settings.viewportHeight,
                            settings.screenY)) {
                return false;
            }
        } else {
            if (!drawSprite(destination, sprites.sightWeapon, sightX, sightY,
                            ESpriteAnchorLeftBottom, false,
                            settings.viewportWidth, settings.viewportHeight,
                            settings.screenY) ||
                !drawSprite(destination, sprites.sightWeapon, sightX, sightY,
                            ESpriteAnchorRightBottom, true,
                            settings.viewportWidth, settings.viewportHeight,
                            settings.screenY)) {
                return false;
            }
        }
    }

    if (sprites.sight.view.pixels != 0 &&
        !drawSprite(destination, sprites.sight, 0, settings.screenY,
                    ESpriteAnchorLeftTop, false,
                    settings.viewportWidth, settings.viewportHeight,
                    settings.screenY)) {
        return false;
    }
    return true;
}

} // namespace

SoftwareWeaponSpriteImage::SoftwareWeaponSpriteImage()
    : view(), sixteen()
{
}

bool SoftwareWeaponSpriteImage::buildSixteen(bool format4444,
                                             QUint16* storage,
                                             QInt32 capacity)
{
    sixteen = SoftwareTexture16View();
    if (view.pixels == 0 || view.width <= 0 || view.height <= 0 ||
        storage == 0) {
        return false;
    }
    const QUint64 pixelCount = static_cast<QUint64>(view.width) *
                               static_cast<QUint64>(view.height);
    if (pixelCount == 0U || pixelCount > static_cast<QUint64>(capacity)) {
        return false;
    }
    const QInt32 count = static_cast<QInt32>(pixelCount);

    if (format4444) {
        SoftwareTexture16::convert8888To4444(view.pixels, storage, count);
        sixteen = SoftwareTexture16View(
            storage, count, view.width, view.height, false, false, true);
        return true;
    }

    /*
     * 1555 must keep the zero-texel key exact, so a source with no
     * transparent texels would only lose colour precision. That case keeps
     * the 32-bit view.
     */
    if (!view.hasTransparentTexels) return false;
    SoftwareTexture16::convert8888To1555(view.pixels, storage, count);
    sixteen = SoftwareTexture16View(
        storage, count, view.width, view.height, true, true);
    return true;
}

SoftwareWeaponSpriteSources::SoftwareWeaponSpriteSources()
    : weapon(), fire(), sight(), sightWeapon()
{
}

SoftwareWeaponCreateSettings::SoftwareWeaponCreateSettings()
    : viewportWidth(0), viewportHeight(0), resizeWeapons(false),
      customPosition(false), muzzleXRatio(1.0f), muzzleYRatio(1.0f),
      attackIntensity(1.0f)
{
}

SoftwareWeaponImageSize::SoftwareWeaponImageSize()
    : width(0), height(0), pixelCount(0)
{
}

SoftwareWeaponSpriteRequirements::SoftwareWeaponSpriteRequirements()
    : viewportWidth(0), viewportHeight(0), weapon(), fire(), sight(),
      sightWeapon()
{
}

SoftwareWeaponSpriteBuffers::SoftwareWeaponSpriteBuffers()
    : weaponPixels(0), weaponCapacity(0), firePixels(0), fireCapacity(0),
      sightPixels(0), sightCapacity(0), sightWeaponPixels(0),
      sightWeaponCapacity(0)
{
}

SoftwareWeaponSpriteSet::SoftwareWeaponSpriteSet()
    : weapon(), fire(), sight(), sightWeapon(), viewportWidth(0),
      viewportHeight(0), dxFire(1), dyFire(1), maxOffsetX(0), maxOffsetY(0),
      resizedToWidth(false), customPositionAtCreation(false)
{
}

SoftwareWeaponDrawSettings::SoftwareWeaponDrawSettings()
    : viewportWidth(0), viewportHeight(0), screenY(0), dx(0), dy(0),
      hasCurrentWeapon(false), playerAlive(true), show2D(true), hudVisible(true),
      zoom(false), hasZoom(false), firing(false), newFireAnimation(false),
      twoHands(false), leftHand(false), meleeAnimation(false),
      centreAlign(false), ignoreSightOnDraw(false), resizeWeapons(false),
      customPosition(false), customPositionX(1.0f)
{
}

bool SoftwareWeaponRenderer::measureSprites(
    const SoftwareWeaponSpriteSources& sources,
    const SoftwareWeaponCreateSettings& settings,
    SoftwareWeaponSpriteRequirements& requirements)
{
    requirements = SoftwareWeaponSpriteRequirements();
    if (settings.viewportWidth <= 0 || settings.viewportHeight <= 0 ||
        settings.viewportWidth > KMaximumViewportDimension ||
        settings.viewportHeight > KMaximumViewportDimension ||
        !validScale(settings.muzzleXRatio) ||
        !validScale(settings.muzzleYRatio) ||
        settings.attackIntensity != settings.attackIntensity ||
        settings.attackIntensity < 0.0f || settings.attackIntensity > 64.0f) {
        return false;
    }

    const float scaleY = static_cast<float>(settings.viewportHeight) /
                         static_cast<float>(KReferenceHeight);
    float scaleX = static_cast<float>(settings.viewportWidth) /
                   static_cast<float>(KReferenceWidth);
    if (settings.customPosition || !settings.resizeWeapons) scaleX = scaleY;

    if (!imageSizeForScale(sources.weapon, scaleX, scaleY,
                           requirements.weapon) ||
        !imageSizeForScale(sources.fire, scaleX, scaleY,
                           requirements.fire) ||
        !exactImageSize(sources.sight, settings.viewportWidth,
                        settings.viewportHeight, requirements.sight) ||
        !imageSizeForScale(sources.sightWeapon, scaleY, scaleY,
                           requirements.sightWeapon)) {
        requirements = SoftwareWeaponSpriteRequirements();
        return false;
    }
    requirements.viewportWidth = settings.viewportWidth;
    requirements.viewportHeight = settings.viewportHeight;
    return true;
}

bool SoftwareWeaponRenderer::resizeImage(
    const SoftwareTextureView& source,
    QInt32* destinationPixels,
    QInt32 destinationCapacity,
    QInt32 destinationWidth,
    QInt32 destinationHeight)
{
    bool present = false;
    if (!validateImage(source, present) || !present ||
        destinationPixels == 0 || destinationCapacity <= 0 ||
        destinationWidth <= 0 || destinationHeight <= 0 ||
        destinationWidth > KMaximumViewportDimension ||
        destinationHeight > KMaximumViewportDimension) {
        return false;
    }
    const QInt64 destinationCount64 =
        static_cast<QInt64>(destinationWidth) * destinationHeight;
    if (destinationCount64 <= 0 ||
        destinationCount64 > destinationCapacity ||
        destinationCount64 > KMaximumPreparedImagePixels) {
        return false;
    }

    const QInt32 destinationCount = static_cast<QInt32>(destinationCount64);
    if (source.width == destinationWidth &&
        source.height == destinationHeight) {
        if (source.pixels != destinationPixels) {
            QInt32 pixelIndex;
            for (pixelIndex = 0; pixelIndex < destinationCount; ++pixelIndex) {
                destinationPixels[pixelIndex] = source.pixels[pixelIndex];
            }
        }
        return true;
    }

    QInt32 x;
    for (x = 0; x < destinationWidth; ++x) {
        const QInt32 rx = code::Math::javaIntMul(
            code::Math::javaIntDiv(
                code::Math::javaShiftLeft(x, KImageFixedShift),
                destinationWidth), source.width);
        const QInt32 sourceX =
            code::Math::javaShiftRightUnsigned(rx, KImageFixedShift);
        const QInt32 sourceX2 = sourceX < source.width - 1
            ? sourceX + 1 : sourceX;
        const QUint32 fractionX = static_cast<QUint32>(rx) &
                                  KImageFixedMask;
        QInt32 y;
        for (y = 0; y < destinationHeight; ++y) {
            const QInt32 ry = code::Math::javaIntMul(
                code::Math::javaIntDiv(
                    code::Math::javaShiftLeft(y, KImageFixedShift),
                    destinationHeight), source.height);
            const QInt32 sourceY =
                code::Math::javaShiftRightUnsigned(ry, KImageFixedShift);
            const QInt32 sourceY2 = sourceY < source.height - 1
                ? sourceY + 1 : sourceY;
            const QUint32 fractionY = static_cast<QUint32>(ry) &
                                      KImageFixedMask;
            const QInt32 firstIndex = sourceX + sourceY * source.width;
            const QInt32 secondIndex = sourceX2 + sourceY * source.width;
            const QInt32 thirdIndex = sourceX + sourceY2 * source.width;
            const QInt32 fourthIndex = sourceX2 + sourceY2 * source.width;
            const QUint32 topLeft = static_cast<QUint32>(
                source.pixels[firstIndex]);
            const QUint32 topRight = static_cast<QUint32>(
                source.pixels[secondIndex]);
            const QUint32 bottomLeft = static_cast<QUint32>(
                source.pixels[thirdIndex]);
            const QUint32 bottomRight = static_cast<QUint32>(
                source.pixels[fourthIndex]);

            const QUint32 alpha = interpolateChannel(
                topLeft, topRight, bottomLeft, bottomRight,
                24U, fractionX, fractionY);
            const QUint32 red = interpolateChannel(
                topLeft, topRight, bottomLeft, bottomRight,
                16U, fractionX, fractionY);
            const QUint32 green = interpolateChannel(
                topLeft, topRight, bottomLeft, bottomRight,
                8U, fractionX, fractionY);
            const QUint32 blue = interpolateChannel(
                topLeft, topRight, bottomLeft, bottomRight,
                0U, fractionX, fractionY);
            const QUint32 result = (alpha << 24U) | (red << 16U) |
                                   (green << 8U) | blue;
            destinationPixels[x + y * destinationWidth] =
                code::Math::javaIntFromUnsigned(result);
        }
    }
    return true;
}

bool SoftwareWeaponRenderer::prepareSprites(
    const SoftwareWeaponSpriteSources& sources,
    const SoftwareWeaponCreateSettings& settings,
    const SoftwareWeaponSpriteRequirements& requirements,
    const SoftwareWeaponSpriteBuffers& buffers,
    SoftwareWeaponSpriteSet& sprites)
{
    SoftwareWeaponSpriteRequirements measured;
    if (!measureSprites(sources, settings, measured) ||
        !requirementsMatch(requirements, measured)) {
        return false;
    }

    SoftwareWeaponSpriteSet prepared;
    prepared.viewportWidth = settings.viewportWidth;
    prepared.viewportHeight = settings.viewportHeight;
    prepared.resizedToWidth = settings.resizeWeapons;
    prepared.customPositionAtCreation = settings.customPosition;

    const SoftwareWeaponImageSize* sizes[4] = {
        &requirements.weapon, &requirements.fire,
        &requirements.sight, &requirements.sightWeapon
    };
    const SoftwareTextureView* sourceImages[4] = {
        &sources.weapon, &sources.fire, &sources.sight, &sources.sightWeapon
    };
    QInt32* destinationImages[4] = {
        buffers.weaponPixels, buffers.firePixels,
        buffers.sightPixels, buffers.sightWeaponPixels
    };
    const QInt32 capacities[4] = {
        buffers.weaponCapacity, buffers.fireCapacity,
        buffers.sightCapacity, buffers.sightWeaponCapacity
    };
    SoftwareTextureView* outputImages[4] = {
        &prepared.weapon.view, &prepared.fire.view,
        &prepared.sight.view, &prepared.sightWeapon.view
    };

    QInt32 imageIndex;
    for (imageIndex = 0; imageIndex < 4; ++imageIndex) {
        if (sizes[imageIndex]->pixelCount == 0) continue;
        if (destinationImages[imageIndex] == 0 ||
            capacities[imageIndex] < sizes[imageIndex]->pixelCount ||
            !resizeImage(*sourceImages[imageIndex],
                         destinationImages[imageIndex], capacities[imageIndex],
                         sizes[imageIndex]->width,
                         sizes[imageIndex]->height)) {
            return false;
        }
        *outputImages[imageIndex] = SoftwareTextureView(
            destinationImages[imageIndex], sizes[imageIndex]->pixelCount,
            sizes[imageIndex]->width, sizes[imageIndex]->height,
            sourceImages[imageIndex]->hasTransparentTexels,
            sourceImages[imageIndex]->mayContainZeroTexels);
    }

    if (prepared.weapon.view.pixels != 0) {
        prepared.dxFire = javaShort(javaFloatToInt(
            static_cast<float>(prepared.weapon.view.width) *
            settings.muzzleXRatio));
        prepared.dyFire = javaShort(javaFloatToInt(
            static_cast<float>(prepared.weapon.view.height) *
            settings.muzzleYRatio));
        const QInt32 divisor = settings.attackIntensity > 1.0f ? 2 : 5;
        prepared.maxOffsetX = javaShort(prepared.weapon.view.width / divisor);
        prepared.maxOffsetY = javaShort(prepared.weapon.view.height / divisor);
    }

    sprites = prepared;
    return true;
}

bool SoftwareWeaponRenderer::draw(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareWeaponSpriteSet& sprites,
    const SoftwareWeaponDrawSettings& settings)
{
    if (!settings.hasCurrentWeapon || !settings.playerAlive ||
        !settings.show2D || !settings.hudVisible) {
        return true;
    }
    if (settings.viewportWidth <= 0 || settings.viewportHeight <= 0 ||
        settings.viewportWidth > KMaximumViewportDimension ||
        settings.viewportHeight > KMaximumViewportDimension ||
        settings.viewportWidth != sprites.viewportWidth ||
        settings.viewportHeight != sprites.viewportHeight ||
        settings.customPosition != sprites.customPositionAtCreation ||
        settings.resizeWeapons != sprites.resizedToWidth ||
        !validDestination(destination) ||
        !validPreparedImage(sprites.weapon.view) ||
        !validPreparedImage(sprites.fire.view) ||
        !validPreparedImage(sprites.sight.view) ||
        !validPreparedImage(sprites.sightWeapon.view)) {
        return false;
    }
    if (settings.customPositionX != settings.customPositionX ||
        settings.customPositionX < -64.0f ||
        settings.customPositionX > 64.0f) {
        return false;
    }

    if (settings.zoom && settings.hasZoom) {
        return drawSight(destination, sprites, settings);
    }
    return drawNormalWeapon(destination, sprites, settings);
}

} // namespace Rendering
} // namespace code
