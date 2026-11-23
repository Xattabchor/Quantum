#ifndef QUANTUM_SOFTWARE_WEAPON_RENDERER_H
#define QUANTUM_SOFTWARE_WEAPON_RENDERER_H

#include "software_affine_rasterizer.h"
#include "software_texture16.h"

namespace code {
namespace Rendering {

/* Source images are the unscaled ARGB pixels loaded from the Java resource
 * paths. The views are non-owning, and the renderer never allocates memory. */
struct SoftwareWeaponSpriteSources {
    SoftwareTextureView weapon;
    SoftwareTextureView fire;
    SoftwareTextureView sight;
    SoftwareTextureView sightWeapon;

    SoftwareWeaponSpriteSources();
};

/* Inputs used by Weapon.createSprite()/createSights(). */
struct SoftwareWeaponCreateSettings {
    code::Math::QInt32 viewportWidth;
    code::Math::QInt32 viewportHeight;
    bool resizeWeapons;
    bool customPosition;
    float muzzleXRatio;
    float muzzleYRatio;
    float attackIntensity;

    SoftwareWeaponCreateSettings();
};

struct SoftwareWeaponImageSize {
    code::Math::QInt32 width;
    code::Math::QInt32 height;
    code::Math::QInt32 pixelCount;

    SoftwareWeaponImageSize();
};

/* Exact caller-owned output capacities required by prepareSprites(). */
struct SoftwareWeaponSpriteRequirements {
    code::Math::QInt32 viewportWidth;
    code::Math::QInt32 viewportHeight;
    SoftwareWeaponImageSize weapon;
    SoftwareWeaponImageSize fire;
    SoftwareWeaponImageSize sight;
    SoftwareWeaponImageSize sightWeapon;

    SoftwareWeaponSpriteRequirements();
};

/* Each non-empty destination needs pixelCount QInt32 elements. */
struct SoftwareWeaponSpriteBuffers {
    code::Math::QInt32* weaponPixels;
    code::Math::QInt32 weaponCapacity;
    code::Math::QInt32* firePixels;
    code::Math::QInt32 fireCapacity;
    code::Math::QInt32* sightPixels;
    code::Math::QInt32 sightCapacity;
    code::Math::QInt32* sightWeaponPixels;
    code::Math::QInt32 sightWeaponCapacity;

    SoftwareWeaponSpriteBuffers();
};

/*
 * One prepared weapon image: the 32-bit view produced by the bilinear
 * resize, plus an optional parallel 16-bit view of the same dimensions.
 *
 * The 16-bit view is built AFTER the resize, from the resized 8888 pixels,
 * so it never has to reimplement the interpolation. It is used only when
 * its format can represent the sprite honestly:
 *   RGB1555  keyed art (no partial alpha beyond the 1-bit cut)
 *   ARGB4444 per-texel translucency, e.g. the muzzle flash
 * Both are two bytes per texel. `sixteen` is ignored if its dimensions or
 * format do not match what the draw path needs, and drawing falls back to
 * the 32-bit view.
 */
struct SoftwareWeaponSpriteImage {
    SoftwareTextureView view;
    SoftwareTexture16View sixteen;

    SoftwareWeaponSpriteImage();

    /*
     * Build the parallel 16-bit view from the already-resized `view`.
     *
     * format4444 selects ARGB4444 (real translucency) and otherwise
     * RGB1555 (1-bit key). `view` must already contain the final resized
     * pixels, so this runs after SoftwareWeaponRenderer::prepareSprites and
     * never re-implements the bilinear resize.
     *
     * Returns false and leaves `sixteen` empty when the arguments do not
     * fit; drawing then stays on the 32-bit path.
     */
    bool buildSixteen(bool format4444,
                      code::Math::QUint16* storage,
                      code::Math::QInt32 capacity);
};

/* Images scaled once at weapon creation, as in Java's ImageResize calls.
 * dxFire/dyFire and maxOffsetX/maxOffsetY correspond to Weapon's cached
 * muzzle and sway offsets; the per-frame dx/dy remain caller-owned state. */
struct SoftwareWeaponSpriteSet {
    SoftwareWeaponSpriteImage weapon;
    SoftwareWeaponSpriteImage fire;
    SoftwareWeaponSpriteImage sight;
    SoftwareWeaponSpriteImage sightWeapon;
    code::Math::QInt32 viewportWidth;
    code::Math::QInt32 viewportHeight;
    code::Math::QInt32 dxFire;
    code::Math::QInt32 dyFire;
    code::Math::QInt32 maxOffsetX;
    code::Math::QInt32 maxOffsetY;
    bool resizedToWidth;
    bool customPositionAtCreation;

    SoftwareWeaponSpriteSet();
};

/* Per-frame values read by Weapon.draw()/drawSight() and Arsenal.drawWeapon().
 * The caller supplies dx/dy produced by its own animation/gameplay state;
 * this renderer does not implement firing, reload, inventory, or weapon
 * selection. screenY is GameScreen's 3D-viewport origin and also its clip top. */
struct SoftwareWeaponDrawSettings {
    code::Math::QInt32 viewportWidth;
    code::Math::QInt32 viewportHeight;
    code::Math::QInt32 screenY;
    code::Math::QInt32 dx;
    code::Math::QInt32 dy;
    bool hasCurrentWeapon;
    bool playerAlive;
    bool show2D;
    bool hudVisible;
    bool zoom;
    bool hasZoom;
    bool firing;
    bool newFireAnimation;
    bool twoHands;
    bool leftHand;
    bool meleeAnimation;
    bool centreAlign;
    bool ignoreSightOnDraw;
    bool resizeWeapons;
    bool customPosition;
    float customPositionX;

    SoftwareWeaponDrawSettings();
};

/*
 * Native, allocation-free first-person weapon image compositor.
 *
 * Image creation follows Java's 240x256 reference scaling and its 16.16
 * bilinear ImageResize path. The resulting sprite set is immutable for one
 * viewport size and is sampled directly per frame. Rendering follows Java's
 * bottom/right and mirror anchors, separate/replacement muzzle-flash modes,
 * two-hand/left-hand paths, zoom-sight composition and GameScreen visibility
 * gates. The framebuffer is opaque RGB (0x00RRGGBB); image inputs are ARGB.
 */
class SoftwareWeaponRenderer {
public:
    static bool measureSprites(
        const SoftwareWeaponSpriteSources& sources,
        const SoftwareWeaponCreateSettings& settings,
        SoftwareWeaponSpriteRequirements& requirements);

    static bool prepareSprites(
        const SoftwareWeaponSpriteSources& sources,
        const SoftwareWeaponCreateSettings& settings,
        const SoftwareWeaponSpriteRequirements& requirements,
        const SoftwareWeaponSpriteBuffers& buffers,
        SoftwareWeaponSpriteSet& sprites);

    /* Ports ImageResize.bilinearResizeRGB(..., alpha=true). */
    static bool resizeImage(const SoftwareTextureView& source,
                            code::Math::QInt32* destinationPixels,
                            code::Math::QInt32 destinationCapacity,
                            code::Math::QInt32 destinationWidth,
                            code::Math::QInt32 destinationHeight);

    /* Applies GameScreen's alive/HUD/show2D gates and Arsenal's zoom dispatch. */
    static bool draw(const SoftwareRenderSurfaceView& destination,
                     const SoftwareWeaponSpriteSet& sprites,
                     const SoftwareWeaponDrawSettings& settings);
};

} // namespace Rendering
} // namespace code

#endif // QUANTUM_SOFTWARE_WEAPON_RENDERER_H
