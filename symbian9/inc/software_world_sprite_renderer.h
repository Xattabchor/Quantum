#ifndef QUANTUM_SOFTWARE_WORLD_SPRITE_RENDERER_H
#define QUANTUM_SOFTWARE_WORLD_SPRITE_RENDERER_H

#include "software_affine_rasterizer.h"
#include "software_vertex_pipeline.h"
#include "vector3d.h"

namespace code {
namespace Rendering {

enum SoftwareWorldSpriteMode {
    ESoftwareWorldSpriteKeyed = 0,
    ESoftwareWorldSpriteAdd = 1,
    ESoftwareWorldSpriteMultiply = 2,
    ESoftwareWorldSpriteAlpha = 3
};

/* Immutable, non-owning equivalent of the visual fields of Java Sprite. */
struct SoftwareWorldSprite {
    const SoftwareTextureView* frames;
    code::Math::QInt32 frameCount;
    code::Math::QInt32 scale;
    /* RawImage.scale of textures[0], used by Sprite.project's pixel scaling. */
    code::Math::QInt32 textureScale;
    code::Math::QInt32 offsetX;
    code::Math::QInt32 offsetY;
    code::Math::QInt32 mode;
    code::Math::QInt32 cutoff;
    code::Math::QInt32 baseColor;
    bool mirrorX;
    bool mirrorY;
    bool fog;
    bool limiter;
    float animationSpeed;
    const bool* flicker;
    code::Math::QInt32 flickerCount;
    const code::Math::QInt32* colorAnimation;
    code::Math::QInt32 colorAnimationCount;

    SoftwareWorldSprite();
};

/*
 * Per-placement state corresponding to one Java SpriteObject. Each placement
 * has its own flicker counter, current animated color and projected rectangle,
 * even if multiple placements share the immutable sprite texture definition.
 */
struct SoftwareWorldSpriteInstance {
    const SoftwareWorldSprite* sprite;
    code::Math::Vector3D position;
    code::Math::QInt32 addSz;
    /* Java RoomObject.getNewPart() override; room traversal is caller-owned. */
    code::Math::QInt32 roomId;
    bool visible;
    bool hideWhenUnusable;
    code::Math::QInt64 lastActivateMillis;
    code::Math::QInt64 timeToResetMillis;
    code::Math::QInt64 animationBeginMillis;

    /* Java SpriteObject.intframe and persistent Sprite.color state. */
    code::Math::QInt32 frame100;
    code::Math::QInt32 currentColor;
    bool colorInitialized;

    /* Renderer-owned projection/cache fields, overwritten by prepare(). */
    code::Math::QInt32 frameIndex;
    code::Math::QInt32 screenX1;
    code::Math::QInt32 screenY1;
    code::Math::QInt32 screenX2;
    code::Math::QInt32 screenY2;
    code::Math::QInt32 cameraDepth;
    code::Math::QInt32 sortDepth;
    bool queued;

    SoftwareWorldSpriteInstance();
};

/* Time, fog and the Java MathUtils.pLength switch used by Sprite.isVisible. */
struct SoftwareWorldSpriteRenderOptions {
    const code::Math::Matrix* worldToCamera;
    code::Math::QInt64 currentTimeMillis;
    code::Math::QInt64 gameTimeMillis;
    code::Math::QInt32 frameTime;
    code::Math::QInt32 fogDistance;
    code::Math::QInt32 fogColor;
    code::Math::QInt32 standardDrawMode;
    bool useAutoWMove;

    SoftwareWorldSpriteRenderOptions();
};

struct SoftwareWorldSpriteRenderStats {
    code::Math::QInt32 spritesVisited;
    code::Math::QInt32 spritesQueued;
    code::Math::QInt32 spritesDrawn;
    code::Math::QInt32 spritesCulled;
    code::Math::QInt32 spritesHidden;
    code::Math::QInt32 spritesFlickered;

    SoftwareWorldSpriteRenderStats();
};

/*
 * Java SpriteObject/Sprite projection, visibility, frame/flicker update and
 * texture-rectangle draw path. prepare() is called before the shared painter
 * sort; draw() is called from that same sorted queue as mesh polygons.
 * Functions use caller-owned state and allocate no memory.
 */
class SoftwareWorldSpriteRenderer {
public:
    static bool prepare(
        SoftwareWorldSpriteInstance& instance,
        const code::Math::Matrix* worldToCamera,
        const SoftwareProjectionParameters& projection,
        code::Math::QInt32 viewportX1,
        code::Math::QInt32 viewportY1,
        code::Math::QInt32 viewportX2,
        code::Math::QInt32 viewportY2,
        code::Math::QInt32 farDistance,
        const SoftwareWorldSpriteRenderOptions& options,
        bool& shouldQueue,
        SoftwareWorldSpriteRenderStats& stats);

    static bool draw(const SoftwareRenderSurfaceView& destination,
                     const SoftwareWorldSpriteInstance& instance,
                     const SoftwareWorldSpriteRenderOptions& options);
};

} // namespace Rendering
} // namespace code

#endif // QUANTUM_SOFTWARE_WORLD_SPRITE_RENDERER_H
