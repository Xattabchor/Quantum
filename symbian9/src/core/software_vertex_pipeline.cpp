#include "software_vertex_pipeline.h"

#include "quantum_integer.h"

namespace code {
namespace Rendering {

using code::Math::QInt32;

namespace {

void insertVertex(SoftwareRenderVertex vertices[
                      SoftwareVertexPipeline::KClipVertexCapacity],
                  QInt32& vertexCount,
                  QInt32 index,
                  const SoftwareRenderVertex& value)
{
    QInt32 i;
    for (i = vertexCount; i > index; --i) {
        vertices[i] = vertices[i - 1];
    }
    vertices[index] = value;
    ++vertexCount;
}

void deleteVertex(SoftwareRenderVertex vertices[
                      SoftwareVertexPipeline::KClipVertexCapacity],
                  QInt32& vertexCount,
                  QInt32 index)
{
    QInt32 i;
    for (i = index; i < vertexCount - 1; ++i) {
        vertices[i] = vertices[i + 1];
    }
    --vertexCount;
}

QInt32 interpolateInteger(QInt32 first,
                          QInt32 second,
                          QInt32 numerator,
                          QInt32 denominator)
{
    const QInt32 delta = code::Math::javaIntSub(second, first);
    const QInt32 scaled = code::Math::javaIntMul(numerator, delta);
    return code::Math::javaIntAdd(
        first, code::Math::javaIntDiv(scaled, denominator));
}

SoftwareRenderVertex clipIntersection(const SoftwareRenderVertex& first,
                                      const SoftwareRenderVertex& second,
                                      QInt32 clipZ)
{
    const QInt32 numerator = code::Math::javaIntSub(clipZ, first.z);
    const QInt32 denominator = code::Math::javaIntSub(second.z, first.z);
    return SoftwareRenderVertex(
        interpolateInteger(first.x, second.x, numerator, denominator),
        interpolateInteger(first.y, second.y, numerator, denominator),
        clipZ,
        interpolateInteger(first.red, second.red, numerator, denominator),
        interpolateInteger(first.green, second.green, numerator, denominator),
        interpolateInteger(first.blue, second.blue, numerator, denominator),
        interpolateInteger(first.u, second.u, numerator, denominator),
        interpolateInteger(first.v, second.v, numerator, denominator));
}

QInt32 transformedComponent(QInt32 x,
                           QInt32 y,
                           QInt32 z,
                           QInt32 m0,
                           QInt32 m1,
                           QInt32 m2,
                           QInt32 translation,
                           unsigned int fractionalBits)
{
    QInt32 result = code::Math::javaShiftRight(
        code::Math::javaIntMul(x, m0), fractionalBits);
    result = code::Math::javaIntAdd(result, code::Math::javaShiftRight(
        code::Math::javaIntMul(y, m1), fractionalBits));
    result = code::Math::javaIntAdd(result, code::Math::javaShiftRight(
        code::Math::javaIntMul(z, m2), fractionalBits));
    return code::Math::javaIntAdd(result, translation);
}

} // anonymous namespace

SoftwareRenderVertex::SoftwareRenderVertex()
    : x(0), y(0), z(0), red(0), green(0), blue(0), u(0), v(0)
{
}

SoftwareRenderVertex::SoftwareRenderVertex(
    QInt32 vertexX, QInt32 vertexY, QInt32 vertexZ,
    QInt32 vertexRed, QInt32 vertexGreen, QInt32 vertexBlue,
    QInt32 textureU, QInt32 textureV)
    : x(vertexX), y(vertexY), z(vertexZ), red(vertexRed), green(vertexGreen),
      blue(vertexBlue), u(textureU), v(textureV)
{
}

SoftwareProjectionParameters::SoftwareProjectionParameters()
    : centreX(0), centreY(0), distX(1), distY(1)
{
}

SoftwareProjectionParameters::SoftwareProjectionParameters(
    QInt32 xCentre, QInt32 yCentre, QInt32 xDistance, QInt32 yDistance)
    : centreX(xCentre), centreY(yCentre), distX(xDistance), distY(yDistance)
{
}

void SoftwareVertexPipeline::transformVertex(
    const SoftwareRenderVertex& source,
    const code::Math::Matrix* matrix,
    SoftwareRenderVertex& destination)
{
    destination = source;
    if (matrix == 0) return;

    const unsigned int fractionalBits =
        static_cast<unsigned int>(code::Math::Matrix::fp - 2);
    const QInt32 m00 = code::Math::javaShiftRight(matrix->m00, 2);
    const QInt32 m01 = code::Math::javaShiftRight(matrix->m01, 2);
    const QInt32 m02 = code::Math::javaShiftRight(matrix->m02, 2);
    const QInt32 m10 = code::Math::javaShiftRight(matrix->m10, 2);
    const QInt32 m11 = code::Math::javaShiftRight(matrix->m11, 2);
    const QInt32 m12 = code::Math::javaShiftRight(matrix->m12, 2);
    const QInt32 m20 = code::Math::javaShiftRight(matrix->m20, 2);
    const QInt32 m21 = code::Math::javaShiftRight(matrix->m21, 2);
    const QInt32 m22 = code::Math::javaShiftRight(matrix->m22, 2);

    destination.x = transformedComponent(
        source.x, source.y, source.z, m00, m01, m02, matrix->m03,
        fractionalBits);
    destination.y = transformedComponent(
        source.x, source.y, source.z, m10, m11, m12, matrix->m13,
        fractionalBits);
    destination.z = transformedComponent(
        source.x, source.y, source.z, m20, m21, m22, matrix->m23,
        fractionalBits);
}

bool SoftwareVertexPipeline::clipTriangle(
    const SoftwareRenderVertex source[3],
    QInt32 clipZ,
    SoftwareRenderVertex destination[KClipVertexCapacity],
    QInt32& destinationCount)
{
    destinationCount = 0;
    if (source == 0 || destination == 0) return false;

    SoftwareRenderVertex vertices[KClipVertexCapacity];
    QInt32 vertexCount = 3;
    QInt32 i;
    for (i = 0; i < 3; ++i) vertices[i] = source[i];

    bool completelyHidden = true;
    i = 0;
    while (i < vertexCount) {
        const QInt32 next = (i + 1) % vertexCount;
        SoftwareRenderVertex first = vertices[i];
        SoftwareRenderVertex second = vertices[next];
        if (first.z < clipZ) completelyHidden = false;

        if (first.z > second.z) {
            const SoftwareRenderVertex temporary = first;
            first = second;
            second = temporary;
        }

        if (first.z < clipZ && second.z > clipZ) {
            if (vertexCount >= KClipVertexCapacity) return false;
            const SoftwareRenderVertex intersection =
                clipIntersection(first, second, clipZ);
            insertVertex(vertices, vertexCount, next, intersection);
            ++i; // Skip the vertex just inserted, as NearClipper.java does.
        }
        ++i;
    }

    if (completelyHidden) return false;

    for (i = vertexCount - 1; i >= 0; --i) {
        if (vertices[i].z > clipZ) deleteVertex(vertices, vertexCount, i);
    }
    if (vertexCount < 3) return false;

    for (i = 0; i < vertexCount; ++i) destination[i] = vertices[i];
    destinationCount = vertexCount;
    return true;
}

bool SoftwareVertexPipeline::project(
    SoftwareRenderVertex* vertices,
    QInt32 vertexCount,
    const SoftwareProjectionParameters& parameters)
{
    if (vertexCount < 0 || (vertexCount > 0 && vertices == 0)) return false;

    QInt32 i;
    for (i = 0; i < vertexCount; ++i) {
        QInt32 screenX = vertices[i].x;
        QInt32 screenY = code::Math::javaIntSub(0, vertices[i].y);
        if (vertices[i].z <= 0) {
            /* NearClipper.project divides by rz + dist{X,Y}. */
            const QInt32 denominatorX = code::Math::javaIntAdd(
                vertices[i].z, parameters.distX);
            const QInt32 denominatorY = code::Math::javaIntAdd(
                vertices[i].z, parameters.distY);
            screenX = code::Math::javaIntDiv(
                code::Math::javaIntMul(screenX, parameters.distX),
                denominatorX);
            screenY = code::Math::javaIntDiv(
                code::Math::javaIntMul(screenY, parameters.distY),
                denominatorY);
        }
        vertices[i].x = code::Math::javaIntAdd(screenX, parameters.centreX);
        vertices[i].y = code::Math::javaIntAdd(screenY, parameters.centreY);
    }
    return true;
}

bool SoftwareVertexPipeline::projectDirectX7(
    SoftwareRenderVertex* vertices,
    QInt32 vertexCount,
    const SoftwareProjectionParameters& parameters)
{
    if (vertexCount < 0 || (vertexCount > 0 && vertices == 0)) return false;

    QInt32 i;
    for (i = 0; i < vertexCount; ++i) {
        QInt32 screenX = vertices[i].x;
        QInt32 screenY = code::Math::javaIntSub(0, vertices[i].y);
        if (vertices[i].z < 0) {
            /* DirectX7.transformAndProjectVertices uses -rz + dist. */
            const QInt32 depth = code::Math::javaIntSub(0, vertices[i].z);
            const QInt32 denominatorX = code::Math::javaIntAdd(
                depth, parameters.distX);
            const QInt32 denominatorY = code::Math::javaIntAdd(
                depth, parameters.distY);
            screenX = code::Math::javaIntDiv(
                code::Math::javaIntMul(screenX, parameters.distX),
                denominatorX);
            screenY = code::Math::javaIntDiv(
                code::Math::javaIntMul(screenY, parameters.distY),
                denominatorY);
        }
        vertices[i].x = code::Math::javaIntAdd(screenX, parameters.centreX);
        vertices[i].y = code::Math::javaIntAdd(screenY, parameters.centreY);
    }
    return true;
}

} // namespace Rendering
} // namespace code
