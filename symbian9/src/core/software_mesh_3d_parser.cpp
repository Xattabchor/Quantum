#include "software_mesh_3d_parser.h"

#include "math_utils2.h"
#include "quantum_integer.h"

namespace code {
namespace Rendering {

using code::Math::QInt32;
using code::Math::Vector3D;

namespace {

const QInt32 KMaxInt32 = static_cast<QInt32>(0x7fffffffUL);

class BigEndianReader {
public:
    BigEndianReader(const unsigned char* data, QInt32 length)
        : data_(data), length_(length), position_(0)
    {
    }

    bool readByte(QInt32& value)
    {
        if (!canRead(1)) return false;
        value = static_cast<QInt32>(data_[position_++]);
        return true;
    }

    bool readInt16(QInt32& value)
    {
        if (!canRead(2)) return false;
        QInt32 raw = (static_cast<QInt32>(data_[position_]) << 8) |
                     static_cast<QInt32>(data_[position_ + 1]);
        position_ += 2;
        if (raw >= 32768) raw -= 65536;
        value = raw;
        return true;
    }

    bool readInt32(QInt32& value)
    {
        if (!canRead(4)) return false;
        const code::Math::QUint32 bits =
            (static_cast<code::Math::QUint32>(data_[position_]) << 24) |
            (static_cast<code::Math::QUint32>(data_[position_ + 1]) << 16) |
            (static_cast<code::Math::QUint32>(data_[position_ + 2]) << 8) |
            static_cast<code::Math::QUint32>(data_[position_ + 3]);
        position_ += 4;
        value = code::Math::javaIntFromUnsigned(bits);
        return true;
    }

    bool skip(QInt32 count)
    {
        if (count < 0 || !canRead(count)) return false;
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
        return data_ != 0 && count >= 0 && position_ <= length_ &&
               count <= length_ - position_;
    }

    const unsigned char* data_;
    QInt32 length_;
    QInt32 position_;
};

bool checkedAdd(QInt32 left, QInt32 right, QInt32& result)
{
    if (left < 0 || right < 0 || left > KMaxInt32 - right) return false;
    result = left + right;
    return true;
}

QInt32 javaMaterialByte(QInt32 value)
{
    QInt32 lowByte = value % 256;
    if (lowByte < 0) lowByte += 256;
    if (lowByte >= 128) lowByte -= 256;
    return lowByte;
}

bool updateMaterialCount(QInt32 materialIndex, QInt32& materialCount)
{
    /* Polygon3V/Polygon4V store the texture index in Java's signed byte. */
    if (materialIndex < 0 || materialIndex == KMaxInt32) return false;
    if (materialIndex >= materialCount) materialCount = materialIndex + 1;
    return true;
}

QInt32 javaScaledCoordinate(QInt32 coordinate, float scale)
{
    /* Java evaluates short * float as float before its saturating f2i cast. */
    const float scaled = static_cast<float>(coordinate) * scale;
    return code::Math::javaDoubleToInt(static_cast<double>(scaled));
}

bool validLocalIndex(QInt32 index, QInt32 vertexCount)
{
    return index >= 0 && index < vertexCount;
}

void setCommonPolygon(SoftwareMeshPolygon& polygon,
                      QInt32 vertexCount,
                      QInt32 materialIndex,
                      const QInt32 indices[4],
                      const QInt32 textureU[4],
                      const QInt32 textureV[4])
{
    polygon.vertexCount = vertexCount;
    polygon.shading = ESoftwareMeshUnlit;
    polygon.normalX = 0;
    polygon.normalY = 0;
    polygon.normalZ = 0;
    polygon.textureIndex = materialIndex;
    polygon.depthBias = 0;

    QInt32 corner;
    QInt32 channel;
    for (corner = 0; corner < 4; ++corner) {
        for (channel = 0; channel < 3; ++channel) {
            polygon.vertexColor[corner][channel] = 255;
        }
    }
    for (corner = 0; corner < vertexCount; ++corner) {
        polygon.vertexIndices[corner] = indices[corner];
        polygon.textureU[corner] = textureU[corner];
        polygon.textureV[corner] = textureV[corner];
    }
    for (; corner < 4; ++corner) {
        polygon.vertexIndices[corner] = 0;
        polygon.textureU[corner] = 0;
        polygon.textureV[corner] = 0;
    }
}

void setTriangle(SoftwareMeshPolygon& polygon,
                 const Vector3D* vertices,
                 QInt32 vertexOffset,
                 QInt32 va,
                 QInt32 vb,
                 QInt32 vc,
                 const QInt32 rawU[3],
                 const QInt32 rawV[3],
                 QInt32 materialIndex)
{
    const QInt32 indices[4] = { vc, vb, va, 0 };
    const QInt32 textureU[4] = { rawU[2], rawU[1], rawU[0], 0 };
    const QInt32 textureV[4] = { rawV[2], rawV[1], rawV[0], 0 };
    setCommonPolygon(polygon, 3, materialIndex, indices, textureU, textureV);
    const Vector3D& a = vertices[vertexOffset + indices[0]];
    const Vector3D& b = vertices[vertexOffset + indices[1]];
    const Vector3D& c = vertices[vertexOffset + indices[2]];
    const Vector3D normal = code::Math::MathUtils2::createNormal(a, b, c);
    polygon.normalX = normal.x;
    polygon.normalY = normal.y;
    polygon.normalZ = normal.z;
}

void setQuad(SoftwareMeshPolygon& polygon,
             const Vector3D* vertices,
             QInt32 vertexOffset,
             QInt32 va,
             QInt32 vb,
             QInt32 vc,
             QInt32 vd,
             const QInt32 rawU[4],
             const QInt32 rawV[4],
             QInt32 materialIndex)
{
    const QInt32 indices[4] = { vd, vc, vb, va };
    const QInt32 textureU[4] = {
        rawU[3], rawU[2], rawU[1], rawU[0]
    };
    const QInt32 textureV[4] = {
        rawV[3], rawV[2], rawV[1], rawV[0]
    };
    setCommonPolygon(polygon, 4, materialIndex, indices, textureU, textureV);

    const Vector3D firstNormal = code::Math::MathUtils2::createNormal(
        vertices[vertexOffset + indices[0]],
        vertices[vertexOffset + indices[1]],
        vertices[vertexOffset + indices[2]]);
    const Vector3D secondNormal = code::Math::MathUtils2::createNormal(
        vertices[vertexOffset + indices[0]],
        vertices[vertexOffset + indices[2]],
        vertices[vertexOffset + indices[3]]);
    polygon.normalX = code::Math::javaIntAdd(
        code::Math::javaIntDiv(firstNormal.x, 2),
        code::Math::javaIntDiv(secondNormal.x, 2));
    polygon.normalY = code::Math::javaIntAdd(
        code::Math::javaIntDiv(firstNormal.y, 2),
        code::Math::javaIntDiv(secondNormal.y, 2));
    polygon.normalZ = code::Math::javaIntAdd(
        code::Math::javaIntDiv(firstNormal.z, 2),
        code::Math::javaIntDiv(secondNormal.z, 2));
}

bool readPolygon(BigEndianReader& reader,
                 QInt32 meshVertexCount,
                 QInt32 verticesPerPolygon,
                 QInt32& currentMaterial,
                 QInt32& materialCount,
                 QInt32 indices[4],
                 QInt32 textureU[4],
                 QInt32 textureV[4])
{
    QInt32 first = 0;
    if (!reader.readInt16(first)) return false;
    if (first == -32768) {
        QInt32 rawMaterial = 0;
        if (!reader.readInt16(rawMaterial) || !reader.readInt16(first)) {
            return false;
        }
        currentMaterial = javaMaterialByte(rawMaterial);
    }
    if (!updateMaterialCount(currentMaterial, materialCount)) return false;

    indices[0] = first;
    QInt32 i;
    for (i = 1; i < verticesPerPolygon; ++i) {
        if (!reader.readInt16(indices[i])) return false;
    }
    for (i = 0; i < verticesPerPolygon; ++i) {
        if (!reader.readByte(textureU[i]) || !reader.readByte(textureV[i])) {
            return false;
        }
    }
    for (; i < 4; ++i) {
        indices[i] = 0;
        textureU[i] = 0;
        textureV[i] = 0;
    }

    for (i = 0; i < verticesPerPolygon; ++i) {
        if (!validLocalIndex(indices[i], meshVertexCount)) return false;
    }
    return true;
}

bool scanData(const unsigned char* data,
              QInt32 dataLength,
              float scaleX,
              float scaleY,
              float scaleZ,
              Vector3D* outputVertices,
              SoftwareMeshPolygon* outputPolygons,
              SoftwareMesh3DPart* outputParts,
              SoftwareMesh3DRequirements& requirements)
{
    requirements = SoftwareMesh3DRequirements();
    if (data == 0 || dataLength < 4) return false;

    BigEndianReader reader(data, dataLength);
    QInt32 meshCount = 0;
    if (!reader.readInt32(meshCount) || meshCount < 0) return false;
    if (meshCount > (dataLength - 4) / 6) return false;

    QInt32 vertexOffset = 0;
    QInt32 polygonOffset = 0;
    QInt32 materialCount = 0;
    QInt32 meshIndex;
    for (meshIndex = 0; meshIndex < meshCount; ++meshIndex) {
        const QInt32 partVertexOffset = vertexOffset;
        const QInt32 partPolygonOffset = polygonOffset;
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
                outputVertices[vertexOffset + vertexIndex].set(
                    javaScaledCoordinate(x, scaleX),
                    javaScaledCoordinate(y, scaleY),
                    javaScaledCoordinate(z, scaleZ));
            }
        }

        QInt32 triangleCount = 0;
        if (!reader.readInt16(triangleCount) || triangleCount < 0) {
            return false;
        }
        QInt32 currentMaterial = 0;
        QInt32 triangleIndex;
        for (triangleIndex = 0; triangleIndex < triangleCount;
             ++triangleIndex) {
            QInt32 indices[4];
            QInt32 textureU[4];
            QInt32 textureV[4];
            if (!readPolygon(reader, meshVertexCount, 3, currentMaterial,
                             materialCount, indices, textureU, textureV)) {
                return false;
            }
            QInt32 nextPolygonOffset = 0;
            if (!checkedAdd(polygonOffset, 1, nextPolygonOffset)) return false;
            if (outputPolygons != 0) {
                setTriangle(outputPolygons[polygonOffset], outputVertices,
                            vertexOffset, indices[0], indices[1], indices[2],
                            textureU, textureV, currentMaterial);
            }
            polygonOffset = nextPolygonOffset;
        }

        QInt32 quadCount = 0;
        if (!reader.readInt16(quadCount) || quadCount < 0) return false;
        currentMaterial = 0;
        QInt32 quadIndex;
        for (quadIndex = 0; quadIndex < quadCount; ++quadIndex) {
            QInt32 indices[4];
            QInt32 textureU[4];
            QInt32 textureV[4];
            if (!readPolygon(reader, meshVertexCount, 4, currentMaterial,
                             materialCount, indices, textureU, textureV)) {
                return false;
            }
            QInt32 nextPolygonOffset = 0;
            if (!checkedAdd(polygonOffset, 1, nextPolygonOffset)) return false;
            if (outputPolygons != 0) {
                setQuad(outputPolygons[polygonOffset], outputVertices,
                        vertexOffset, indices[0], indices[1],
                        indices[2], indices[3], textureU, textureV,
                        currentMaterial);
            }
            polygonOffset = nextPolygonOffset;
        }

        if (outputParts != 0) {
            SoftwareMesh3DPart part;
            part.vertexOffset = partVertexOffset;
            part.vertexCount = meshVertexCount;
            part.polygonOffset = partPolygonOffset;
            part.polygonCount = polygonOffset - partPolygonOffset;
            outputParts[meshIndex] = part;
        }
        vertexOffset = nextVertexOffset;
    }

    if (reader.remaining() != 0) return false;
    requirements.meshCount = meshCount;
    requirements.vertexCount = vertexOffset;
    requirements.polygonCount = polygonOffset;
    requirements.materialCount = materialCount;
    return true;
}

QInt32 lightmapValue(const unsigned char* data, QInt32 offset)
{
    const QInt32 signedValue = data[offset] < 128
        ? static_cast<QInt32>(data[offset])
        : static_cast<QInt32>(data[offset]) - 256;
    return signedValue + 128;
}

bool validateLightmapPolygons(const SoftwareMeshPolygon* polygons,
                              QInt32 polygonCount)
{
    if (polygonCount < 0 || (polygonCount > 0 && polygons == 0)) return false;
    QInt32 i;
    for (i = 0; i < polygonCount; ++i) {
        if (polygons[i].vertexCount != 3 && polygons[i].vertexCount != 4) {
            return false;
        }
    }
    return true;
}

bool scanColorLightmap(const unsigned char* data,
                       QInt32 dataLength,
                       SoftwareMeshPolygon* polygons,
                       QInt32 polygonCount,
                       bool apply,
                       bool preserveRgb)
{
    if (dataLength < 0 || (dataLength > 0 && data == 0) ||
        !validateLightmapPolygons(polygons, polygonCount)) {
        return false;
    }

    BigEndianReader reader(data, dataLength);
    QInt32 polygonIndex;
    for (polygonIndex = 0; polygonIndex < polygonCount; ++polygonIndex) {
        const QInt32 cornerCount = polygons[polygonIndex].vertexCount;
        QInt32 red[4] = { 0, 0, 0, 0 };
        QInt32 green[4] = { 0, 0, 0, 0 };
        QInt32 blue[4] = { 0, 0, 0, 0 };
        QInt32 corner;
        for (corner = 0; corner < cornerCount; ++corner) {
            QInt32 byte = 0;
            if (!reader.readByte(byte)) return false;
            red[corner] = static_cast<QInt32>(byte >= 128 ? byte - 256 : byte) + 128;
        }
        QInt32 coloredByte = 0;
        if (!reader.readByte(coloredByte)) return false;
        const bool colored = coloredByte != 0;
        if (colored) {
            for (corner = 0; corner < cornerCount; ++corner) {
                QInt32 byte = 0;
                if (!reader.readByte(byte)) return false;
                green[corner] =
                    static_cast<QInt32>(byte >= 128 ? byte - 256 : byte) + 128;
                if (!reader.readByte(byte)) return false;
                blue[corner] =
                    static_cast<QInt32>(byte >= 128 ? byte - 256 : byte) + 128;
            }
        } else {
            for (corner = 0; corner < cornerCount; ++corner) {
                green[corner] = red[corner];
                blue[corner] = red[corner];
            }
        }

        if (apply) {
            SoftwareMeshPolygon& polygon = polygons[polygonIndex];
            const bool useRgb = colored && preserveRgb;
            polygon.shading = useRgb
                ? ESoftwareMeshVertexRgb : ESoftwareMeshVertexGray;
            for (corner = 0; corner < cornerCount; ++corner) {
                QInt32 value = red[corner];
                if (colored && !preserveRgb) {
                    value = (red[corner] + green[corner] + blue[corner]) / 3;
                }
                polygon.vertexColor[corner][0] = useRgb ? red[corner] : value;
                polygon.vertexColor[corner][1] = useRgb ? green[corner] : value;
                polygon.vertexColor[corner][2] = useRgb ? blue[corner] : value;
            }
        }
    }
    return reader.remaining() == 0;
}

} // anonymous namespace

SoftwareMesh3DRequirements::SoftwareMesh3DRequirements()
    : meshCount(0), vertexCount(0), polygonCount(0), materialCount(0)
{
}

SoftwareMesh3DPart::SoftwareMesh3DPart()
    : vertexOffset(0), vertexCount(0), polygonOffset(0), polygonCount(0)
{
}

bool SoftwareMesh3DParser::measure(
    const unsigned char* data,
    QInt32 dataLength,
    SoftwareMesh3DRequirements& requirements)
{
    SoftwareMesh3DRequirements measured;
    if (!scanData(data, dataLength, 1.0f, 1.0f, 1.0f,
                  0, 0, 0, measured)) {
        return false;
    }
    requirements = measured;
    return true;
}

bool SoftwareMesh3DParser::parse(
    const unsigned char* data,
    QInt32 dataLength,
    float scaleX,
    float scaleY,
    float scaleZ,
    Vector3D* outputVertices,
    QInt32 vertexCapacity,
    SoftwareMeshPolygon* outputPolygons,
    QInt32 polygonCapacity,
    SoftwareMesh3DPart* outputParts,
    QInt32 partCapacity,
    SoftwareMesh3DRequirements& requirements)
{
    if (vertexCapacity < 0 || polygonCapacity < 0 || partCapacity < 0) {
        return false;
    }
    SoftwareMesh3DRequirements measured;
    if (!scanData(data, dataLength, scaleX, scaleY, scaleZ,
                  0, 0, 0, measured)) {
        return false;
    }
    if (vertexCapacity < measured.vertexCount ||
        polygonCapacity < measured.polygonCount ||
        partCapacity < measured.meshCount ||
        (measured.vertexCount > 0 && outputVertices == 0) ||
        (measured.polygonCount > 0 && outputPolygons == 0) ||
        (measured.meshCount > 0 && outputParts == 0)) {
        return false;
    }

    SoftwareMesh3DRequirements parsed;
    if (!scanData(data, dataLength, scaleX, scaleY, scaleZ,
                  outputVertices, outputPolygons, outputParts, parsed)) {
        return false;
    }
    requirements = parsed;
    return true;
}

bool SoftwareMesh3DParser::applyGrayscaleLightmap(
    const unsigned char* data,
    QInt32 dataLength,
    SoftwareMeshPolygon* polygons,
    QInt32 polygonCount)
{
    if (dataLength < 0 || (dataLength > 0 && data == 0) ||
        !validateLightmapPolygons(polygons, polygonCount)) {
        return false;
    }

    code::Math::QInt64 requiredBytes = 0;
    QInt32 polygonIndex;
    for (polygonIndex = 0; polygonIndex < polygonCount; ++polygonIndex) {
        requiredBytes += polygons[polygonIndex].vertexCount;
    }
    if (requiredBytes != dataLength) return false;

    QInt32 offset = 0;
    for (polygonIndex = 0; polygonIndex < polygonCount; ++polygonIndex) {
        SoftwareMeshPolygon& polygon = polygons[polygonIndex];
        polygon.shading = ESoftwareMeshVertexGray;
        QInt32 corner;
        for (corner = 0; corner < polygon.vertexCount; ++corner) {
            const QInt32 value = lightmapValue(data, offset++);
            polygon.vertexColor[corner][0] = value;
            polygon.vertexColor[corner][1] = value;
            polygon.vertexColor[corner][2] = value;
        }
    }
    return offset == dataLength;
}

bool SoftwareMesh3DParser::applyColorLightmap(
    const unsigned char* data,
    QInt32 dataLength,
    SoftwareMeshPolygon* polygons,
    QInt32 polygonCount,
    bool preserveRgb)
{
    if (!scanColorLightmap(data, dataLength, polygons, polygonCount,
                           false, preserveRgb)) {
        return false;
    }
    return scanColorLightmap(data, dataLength, polygons, polygonCount,
                             true, preserveRgb);
}

} // namespace Rendering
} // namespace code
