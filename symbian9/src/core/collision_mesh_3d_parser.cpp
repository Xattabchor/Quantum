#include "collision_mesh_3d_parser.h"

#include "math_utils2.h"

namespace code {
namespace Collision {

namespace {

using code::Math::QInt32;
using code::Math::QUint32;
using code::Math::Vector3D;

const QInt32 KMaxInt32 = static_cast<QInt32>(0x7fffffffUL);

class BigEndianReader {
public:
    BigEndianReader(const unsigned char* data, QInt32 length)
        : data_(data), length_(length), position_(0)
    {
    }

    bool readInt16(QInt32& value)
    {
        if (!canRead(2)) {
            return false;
        }
        QInt32 raw = (static_cast<QInt32>(data_[position_]) << 8) |
                     static_cast<QInt32>(data_[position_ + 1]);
        position_ += 2;
        if (raw >= 32768) {
            raw -= 65536;
        }
        value = raw;
        return true;
    }

    bool readInt32(QInt32& value)
    {
        if (!canRead(4)) {
            return false;
        }
        const QUint32 bits =
            (static_cast<QUint32>(data_[position_]) << 24) |
            (static_cast<QUint32>(data_[position_ + 1]) << 16) |
            (static_cast<QUint32>(data_[position_ + 2]) << 8) |
            static_cast<QUint32>(data_[position_ + 3]);
        position_ += 4;

        if (bits <= static_cast<QUint32>(0x7fffffffUL)) {
            value = static_cast<QInt32>(bits);
        } else {
            value = -1 - static_cast<QInt32>(~bits);
        }
        return true;
    }

    bool skip(QInt32 count)
    {
        if (count < 0 || !canRead(count)) {
            return false;
        }
        position_ += count;
        return true;
    }

    QInt32 remaining() const
    {
        return length_ - position_;
    }

private:
    bool canRead(QInt32 count) const
    {
        return count >= 0 && position_ <= length_ &&
               count <= length_ - position_;
    }

    const unsigned char* data_;
    QInt32 length_;
    QInt32 position_;
};

bool checkedAdd(QInt32 left, QInt32 right, QInt32& result)
{
    if (left < 0 || right < 0 || left > KMaxInt32 - right) {
        return false;
    }
    result = left + right;
    return true;
}

QInt32 javaMaterialByte(QInt32 value)
{
    QInt32 lowByte = value % 256;
    if (lowByte < 0) {
        lowByte += 256;
    }
    if (lowByte >= 128) {
        lowByte -= 256;
    }
    return lowByte;
}

bool updateMaterialCount(QInt32 materialIndex, QInt32& materialCount)
{
    /* Java stores the parsed ID in a signed byte and indexes the texture list. */
    if (materialIndex < 0) {
        return false;
    }
    if (materialIndex >= materialCount) {
        materialCount = materialIndex + 1;
    }
    return true;
}

bool scaleCoordinate(QInt32 coordinate, float scale, QInt32& result)
{
    /* Match Java's short * float, followed by a truncating int conversion. */
    const float scaled = static_cast<float>(coordinate) * scale;
    if (scaled != scaled) {
        return false;
    }
    const double checked = static_cast<double>(scaled);
    if (checked < -2147483648.0 || checked >= 2147483648.0) {
        return false;
    }
    result = static_cast<QInt32>(scaled);
    return true;
}

bool validLocalIndex(QInt32 index, QInt32 vertexCount)
{
    return index >= 0 && index < vertexCount;
}

void setTriangle(CollisionMeshPolygonData& polygon,
                 const Vector3D* vertices,
                 QInt32 vertexOffset,
                 QInt32 va,
                 QInt32 vb,
                 QInt32 vc,
                 QInt32 materialIndex)
{
    polygon.vertexCount = 3;
    polygon.vertexIndices[0] = vertexOffset + vc;
    polygon.vertexIndices[1] = vertexOffset + vb;
    polygon.vertexIndices[2] = vertexOffset + va;
    polygon.vertexIndices[3] = 0;
    polygon.materialIndex = materialIndex;
    polygon.identity = &polygon;
    polygon.normal.set(code::Math::MathUtils2::createNormal(
        vertices[vertexOffset + vc],
        vertices[vertexOffset + vb],
        vertices[vertexOffset + va]));
}

void setQuad(CollisionMeshPolygonData& polygon,
             const Vector3D* vertices,
             QInt32 vertexOffset,
             QInt32 va,
             QInt32 vb,
             QInt32 vc,
             QInt32 vd,
             QInt32 materialIndex)
{
    const QInt32 indexD = vertexOffset + vd;
    const QInt32 indexC = vertexOffset + vc;
    const QInt32 indexB = vertexOffset + vb;
    const QInt32 indexA = vertexOffset + va;
    const Vector3D firstNormal = code::Math::MathUtils2::createNormal(
        vertices[indexD], vertices[indexC], vertices[indexB]);
    const Vector3D secondNormal = code::Math::MathUtils2::createNormal(
        vertices[indexD], vertices[indexB], vertices[indexA]);

    polygon.vertexCount = 4;
    polygon.vertexIndices[0] = indexD;
    polygon.vertexIndices[1] = indexC;
    polygon.vertexIndices[2] = indexB;
    polygon.vertexIndices[3] = indexA;
    polygon.materialIndex = materialIndex;
    polygon.identity = &polygon;
    polygon.normal.set(firstNormal.x / 2 + secondNormal.x / 2,
                       firstNormal.y / 2 + secondNormal.y / 2,
                       firstNormal.z / 2 + secondNormal.z / 2);
}

bool scanData(const unsigned char* data,
              QInt32 dataLength,
              float scaleX,
              float scaleY,
              float scaleZ,
              Vector3D* outputVertices,
              CollisionMeshPolygonData* outputPolygons,
              CollisionMesh3DRequirements& requirements)
{
    requirements = CollisionMesh3DRequirements();
    if (data == 0 || dataLength < 4) {
        return false;
    }

    BigEndianReader reader(data, dataLength);
    QInt32 meshCount = 0;
    if (!reader.readInt32(meshCount) || meshCount < 0) {
        return false;
    }
    /* Every mesh has at least three 16-bit counts. */
    if (meshCount > (dataLength - 4) / 6) {
        return false;
    }

    QInt32 vertexOffset = 0;
    QInt32 polygonOffset = 0;
    QInt32 requiredMaterialCount = 0;
    QInt32 meshIndex;
    for (meshIndex = 0; meshIndex < meshCount; ++meshIndex) {
        QInt32 meshVertexCount = 0;
        if (!reader.readInt16(meshVertexCount) || meshVertexCount < 0) {
            return false;
        }
        QInt32 nextVertexOffset = 0;
        if (!checkedAdd(vertexOffset, meshVertexCount, nextVertexOffset)) {
            return false;
        }

        QInt32 vertexIndex;
        for (vertexIndex = 0; vertexIndex < meshVertexCount; ++vertexIndex) {
            QInt32 x = 0;
            QInt32 y = 0;
            QInt32 z = 0;
            if (!reader.readInt16(x) || !reader.readInt16(y) ||
                !reader.readInt16(z)) {
                return false;
            }
            if (outputVertices != 0) {
                QInt32 scaledX = 0;
                QInt32 scaledY = 0;
                QInt32 scaledZ = 0;
                if (!scaleCoordinate(x, scaleX, scaledX) ||
                    !scaleCoordinate(y, scaleY, scaledY) ||
                    !scaleCoordinate(z, scaleZ, scaledZ)) {
                    return false;
                }
                outputVertices[vertexOffset + vertexIndex].set(
                    scaledX, scaledY, scaledZ);
            }
        }

        QInt32 triangleCount = 0;
        if (!reader.readInt16(triangleCount) || triangleCount < 0) {
            return false;
        }
        QInt32 materialIndex = 0;
        QInt32 triangleIndex;
        for (triangleIndex = 0; triangleIndex < triangleCount; ++triangleIndex) {
            QInt32 va = 0;
            QInt32 vb = 0;
            QInt32 vc = 0;
            if (!reader.readInt16(va)) {
                return false;
            }
            if (va == -32768) {
                QInt32 rawMaterialIndex = 0;
                if (!reader.readInt16(rawMaterialIndex)) {
                    return false;
                }
                materialIndex = javaMaterialByte(rawMaterialIndex);
                if (!updateMaterialCount(materialIndex,
                                         requiredMaterialCount) ||
                    !reader.readInt16(va)) {
                    return false;
                }
            } else if (!updateMaterialCount(materialIndex,
                                            requiredMaterialCount)) {
                return false;
            }
            if (!reader.readInt16(vb) || !reader.readInt16(vc) ||
                !validLocalIndex(va, meshVertexCount) ||
                !validLocalIndex(vb, meshVertexCount) ||
                !validLocalIndex(vc, meshVertexCount) ||
                !reader.skip(6)) {
                return false;
            }
            if (polygonOffset == KMaxInt32) {
                return false;
            }
            if (outputPolygons != 0) {
                setTriangle(outputPolygons[polygonOffset], outputVertices,
                            vertexOffset, va, vb, vc, materialIndex);
            }
            ++polygonOffset;
        }

        QInt32 quadCount = 0;
        if (!reader.readInt16(quadCount) || quadCount < 0) {
            return false;
        }
        materialIndex = 0;
        QInt32 quadIndex;
        for (quadIndex = 0; quadIndex < quadCount; ++quadIndex) {
            QInt32 va = 0;
            QInt32 vb = 0;
            QInt32 vc = 0;
            QInt32 vd = 0;
            if (!reader.readInt16(va)) {
                return false;
            }
            if (va == -32768) {
                QInt32 rawMaterialIndex = 0;
                if (!reader.readInt16(rawMaterialIndex)) {
                    return false;
                }
                materialIndex = javaMaterialByte(rawMaterialIndex);
                if (!updateMaterialCount(materialIndex,
                                         requiredMaterialCount) ||
                    !reader.readInt16(va)) {
                    return false;
                }
            } else if (!updateMaterialCount(materialIndex,
                                            requiredMaterialCount)) {
                return false;
            }
            if (!reader.readInt16(vb) || !reader.readInt16(vc) ||
                !reader.readInt16(vd) ||
                !validLocalIndex(va, meshVertexCount) ||
                !validLocalIndex(vb, meshVertexCount) ||
                !validLocalIndex(vc, meshVertexCount) ||
                !validLocalIndex(vd, meshVertexCount) ||
                !reader.skip(8)) {
                return false;
            }
            if (polygonOffset == KMaxInt32) {
                return false;
            }
            if (outputPolygons != 0) {
                setQuad(outputPolygons[polygonOffset], outputVertices,
                        vertexOffset, va, vb, vc, vd, materialIndex);
            }
            ++polygonOffset;
        }

        vertexOffset = nextVertexOffset;
    }

    if (reader.remaining() != 0) {
        return false;
    }

    requirements.meshCount = meshCount;
    requirements.vertexCount = vertexOffset;
    requirements.polygonCount = polygonOffset;
    requirements.materialCount = requiredMaterialCount;
    return true;
}

} // anonymous namespace

bool CollisionMesh3DParser::measure(const unsigned char* data,
                                    QInt32 dataLength,
                                    CollisionMesh3DRequirements& requirements)
{
    return scanData(data, dataLength, 1.0f, 1.0f, 1.0f,
                    0, 0, requirements);
}

bool CollisionMesh3DParser::parse(const unsigned char* data,
                                  QInt32 dataLength,
                                  float scaleX,
                                  float scaleY,
                                  float scaleZ,
                                  Vector3D* outputVertices,
                                  QInt32 vertexCapacity,
                                  CollisionMeshPolygonData* outputPolygons,
                                  QInt32 polygonCapacity,
                                  const CollisionMaterialFlags* materials,
                                  QInt32 materialCount,
                                  CollisionMeshDataView& outputView)
{
    outputView = CollisionMeshDataView();
    if (vertexCapacity < 0 || polygonCapacity < 0 || materialCount < 0) {
        return false;
    }

    CollisionMesh3DRequirements required;
    if (!measure(data, dataLength, required)) {
        return false;
    }
    if (required.vertexCount > vertexCapacity ||
        required.polygonCount > polygonCapacity ||
        required.materialCount > materialCount) {
        return false;
    }
    if ((required.vertexCount > 0 && outputVertices == 0) ||
        (required.polygonCount > 0 && outputPolygons == 0) ||
        (materialCount > 0 && materials == 0)) {
        return false;
    }

    CollisionMesh3DRequirements parsed;
    if (!scanData(data, dataLength, scaleX, scaleY, scaleZ,
                  outputVertices, outputPolygons, parsed)) {
        return false;
    }
    if (parsed.meshCount != required.meshCount ||
        parsed.vertexCount != required.vertexCount ||
        parsed.polygonCount != required.polygonCount ||
        parsed.materialCount != required.materialCount) {
        return false;
    }

    outputView = CollisionMeshDataView(
        outputVertices, parsed.vertexCount,
        outputPolygons, parsed.polygonCount,
        materials, materialCount);
    return true;
}

} // namespace Collision
} // namespace code
