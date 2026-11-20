#ifndef QUANTUM_SOFTWARE_MESH_ANIMATION_H
#define QUANTUM_SOFTWARE_MESH_ANIMATION_H

#include "software_mesh_3d_parser.h"

namespace code {
namespace Rendering {

enum SoftwareMeshAnimationState {
    ESoftwareMeshAnimationLoop = 0,
    ESoftwareMeshAnimationOpen = 1,
    ESoftwareMeshAnimationClose = 2
};

/* Per-object values consumed by MeshObject.setFrame(). */
struct SoftwareMeshAnimationClock {
    code::Math::QInt32 frameInter;
    code::Math::QInt32 state;
    code::Math::QInt32 clickAnim;
    code::Math::QInt64 clickTimeMillis;
    code::Math::QInt64 gameTimeMillis;
    float animationSpeed;

    SoftwareMeshAnimationClock();
};

/*
 * Per-placement binding for a Java MeshObject. frameVertices/frames refer to
 * the parser's concatenated .3d keyframes; sourceInstance supplies the first
 * frame's topology, placement transform and ADDSZ. animatedVertices is caller-
 * owned storage with room for sourceInstance->mesh->vertexCount coordinates.
 */
struct SoftwareMeshAnimatedInstance {
    const SoftwareMeshInstance* sourceInstance;
    const code::Math::Vector3D* frameVertices;
    const SoftwareMesh3DPart* frames;
    code::Math::QInt32 frameCount;
    SoftwareMeshAnimationClock clock;
    bool morphEnabled;
    code::Math::Vector3D* animatedVertices;
    code::Math::QInt32 animatedVertexCapacity;

    /* Prepared render view and instance; valid until this binding is reused. */
    SoftwareMeshView animatedMesh;
    SoftwareMeshInstance renderInstance;
    code::Math::QInt32 frameQ10;

    SoftwareMeshAnimatedInstance();

private:
    /* renderInstance.mesh points into this binding's animatedMesh. */
    SoftwareMeshAnimatedInstance(const SoftwareMeshAnimatedInstance&);
    SoftwareMeshAnimatedInstance& operator=(
        const SoftwareMeshAnimatedInstance&);
};

/*
 * Allocation-free Java Morphing port for the multi-section .3d model format.
 * Each section is one source keyframe; the first section supplies the topology.
 * Like Morphing.create(), source coordinates are narrowed to signed short
 * before the 10-bit linear interpolation is performed.
 */
class SoftwareMeshAnimator {
public:
    static bool frameForState(const SoftwareMeshAnimationClock& clock,
                              code::Math::QInt32 frameCount,
                              code::Math::QInt32& frameQ10);

    /* Update a placed Java MeshObject for the current frame before queueing. */
    static bool prepareInstance(SoftwareMeshAnimatedInstance& instance);

    static bool interpolate(const code::Math::Vector3D* frameVertices,
                            const SoftwareMesh3DPart* frames,
                            code::Math::QInt32 frameCount,
                            code::Math::QInt32 vertexCount,
                            code::Math::QInt32 frameQ10,
                            bool morphEnabled,
                            code::Math::Vector3D* outputVertices,
                            code::Math::QInt32 outputCapacity);
};

} // namespace Rendering
} // namespace code

#endif // QUANTUM_SOFTWARE_MESH_ANIMATION_H
