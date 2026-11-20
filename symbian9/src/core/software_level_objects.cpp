#include "software_level_objects.h"

#include "quantum_integer.h"

#include <math.h>
#include <stdlib.h>

namespace code {
namespace Rendering {

using code::Math::QInt32;
using code::Math::QInt64;
using code::Math::QUint32;
using code::Math::QUint64;
using code::Math::Vector3D;

namespace {

const QInt32 KValueCapacity = 2048;
const QInt64 KLongMaximum = static_cast<QInt64>(9223372036854775807LL);
const QInt64 KLongMinimum = static_cast<QInt64>(-9223372036854775807LL - 1LL);

struct TextView {
    const char* data;
    QInt32 length;

    TextView() : data(0), length(0) {}
    TextView(const char* value, QInt32 valueLength)
        : data(value), length(valueLength) {}
};

struct LevelObjectBlock {
    TextView type;
    TextView body;
};

enum LevelObjectKind {
    ELevelObjectIgnored = 0,
    ELevelObjectSprite,
    ELevelObjectMesh
};

bool isSpace(char value)
{
    return value == ' ' || value == '\t' || value == '\r' ||
           value == '\n' || value == '\f';
}

TextView trimmed(TextView value)
{
    while (value.length > 0 && isSpace(value.data[0])) {
        ++value.data;
        --value.length;
    }
    while (value.length > 0 && isSpace(value.data[value.length - 1])) {
        --value.length;
    }
    return value;
}

QInt32 literalLength(const char* literal)
{
    if (literal == 0) return 0;
    QInt32 length = 0;
    while (literal[length] != '\0') ++length;
    return length;
}

bool equals(TextView value, const char* literal)
{
    if (value.data == 0 || literal == 0) return false;
    const QInt32 length = literalLength(literal);
    if (value.length != length) return false;
    QInt32 i;
    for (i = 0; i < length; ++i) {
        if (value.data[i] != literal[i]) return false;
    }
    return true;
}

bool contains(TextView value, const char* needle)
{
    if (value.data == 0 || needle == 0) return false;
    QInt32 needleLength = 0;
    while (needle[needleLength] != '\0') ++needleLength;
    if (needleLength == 0 || needleLength > value.length) return false;
    QInt32 start;
    for (start = 0; start <= value.length - needleLength; ++start) {
        QInt32 i;
        for (i = 0; i < needleLength; ++i) {
            if (value.data[start + i] != needle[i]) break;
        }
        if (i == needleLength) return true;
    }
    return false;
}

LevelObjectKind classify(TextView type)
{
    /* Script and object/NPC branches precede SpriteObject/MeshObject. */
    if (contains(type, "ON_ACTIVATE") || contains(type, "ON_SPAWN") ||
        contains(type, "ON_DEATH") || contains(type, "MESSAGE") ||
        contains(type, "ERRMSG") || contains(type, "OBJECT") ||
        contains(type, "TELEPORT") || contains(type, "NPC")) {
        return ELevelObjectIgnored;
    }
    /* Preserve the remaining LevelLoader if/else order: SPRITE before MESH;
     * later SHOP/LEVELCHANGE/IMAGE2D tests must not mask these substrings. */
    if (contains(type, "SPRITE")) return ELevelObjectSprite;
    if (contains(type, "MESH")) return ELevelObjectMesh;
    return ELevelObjectIgnored;
}

bool nextBlock(const unsigned char* data,
               QInt32 dataLength,
               QInt32& scanPosition,
               LevelObjectBlock& block)
{
    if (data == 0 || scanPosition < 0 || scanPosition >= dataLength) {
        return false;
    }
    QInt32 open = scanPosition;
    while (open < dataLength && data[open] != '[') ++open;
    if (open >= dataLength) {
        scanPosition = dataLength;
        return false;
    }

    QInt32 close = open + 1;
    while (close < dataLength && data[close] != ']') ++close;
    if (close >= dataLength) return false;

    QInt32 nextOpen = close + 1;
    while (nextOpen < dataLength && data[nextOpen] != '[') ++nextOpen;
    const QInt32 bodyEnd = nextOpen < dataLength ? nextOpen : dataLength;
    block.type = trimmed(TextView(
        reinterpret_cast<const char*>(data + open + 1), close - open - 1));
    block.body = TextView(
        reinterpret_cast<const char*>(data + close + 1), bodyEnd - close - 1);
    scanPosition = nextOpen < dataLength ? nextOpen : dataLength;
    return true;
}

bool keyEquals(TextView key, const char* wanted)
{
    return equals(trimmed(key), wanted);
}

bool findProperty(const LevelObjectBlock& block,
                  const char* key,
                  TextView& output)
{
    output = TextView();
    if (block.body.data == 0 || block.body.length < 0 || key == 0) {
        return false;
    }
    QInt32 lineStart = 0;
    bool found = false;
    while (lineStart < block.body.length) {
        QInt32 lineEnd = lineStart;
        while (lineEnd < block.body.length &&
               block.body.data[lineEnd] != '\n') {
            ++lineEnd;
        }
        QInt32 first = lineStart;
        while (first < lineEnd && isSpace(block.body.data[first])) ++first;
        if (first < lineEnd && block.body.data[first] != '#') {
            QInt32 equalsPosition = first;
            while (equalsPosition < lineEnd &&
                   block.body.data[equalsPosition] != '=') {
                ++equalsPosition;
            }
            if (equalsPosition < lineEnd) {
                const TextView lineKey = trimmed(TextView(
                    block.body.data + first, equalsPosition - first));
                if (keyEquals(lineKey, key)) {
                    output = trimmed(TextView(
                        block.body.data + equalsPosition + 1,
                        lineEnd - equalsPosition - 1));
                    found = true;
                }
            }
        }
        lineStart = lineEnd + 1;
    }
    return found;
}

bool copyText(TextView value,
              char* output,
              QInt32 outputCapacity,
              QInt32& outputLength)
{
    outputLength = 0;
    if (value.length < 0 || output == 0 ||
        outputCapacity <= value.length) {
        return false;
    }
    QInt32 i;
    for (i = 0; i < value.length; ++i) output[i] = value.data[i];
    output[value.length] = '\0';
    outputLength = value.length;
    return true;
}

bool validateCallbackText(char* output,
                          QInt32 outputCapacity,
                          QInt32& outputLength)
{
    if (output == 0 || outputCapacity <= 0 || outputLength < 0 ||
        outputLength >= outputCapacity) {
        return false;
    }
    output[outputLength] = '\0';
    return true;
}

bool getField(const LevelObjectBlock& block,
              const TextView& preset,
              const char* key,
              bool allowLevelFallback,
              const SoftwareLevelObjectResolvers& resolvers,
              const char* defaultValue,
              char* output,
              QInt32 outputCapacity,
              QInt32& outputLength)
{
    outputLength = 0;
    TextView value;
    if (findProperty(block, key, value)) {
        return copyText(value, output, outputCapacity, outputLength);
    }

    const QInt32 keyLength = literalLength(key);
    if (preset.data != 0 && preset.length > 0 &&
        resolvers.resolvePresetSetting != 0) {
        QInt32 length = 0;
        if (resolvers.resolvePresetSetting(
                resolvers.context, preset.data, preset.length,
                key, keyLength, output, outputCapacity, length)) {
            outputLength = length;
            return validateCallbackText(output, outputCapacity, outputLength);
        }
    }
    if (allowLevelFallback && resolvers.resolveLevelSetting != 0) {
        QInt32 length = 0;
        if (resolvers.resolveLevelSetting(
                resolvers.context, key, keyLength,
                output, outputCapacity, length)) {
            outputLength = length;
            return validateCallbackText(output, outputCapacity, outputLength);
        }
    }
    if (resolvers.resolveGlobalSetting != 0) {
        QInt32 length = 0;
        if (resolvers.resolveGlobalSetting(
                resolvers.context, key, keyLength,
                output, outputCapacity, length)) {
            outputLength = length;
            return validateCallbackText(output, outputCapacity, outputLength);
        }
    }
    if (defaultValue == 0) return false;

    const QInt32 length = literalLength(defaultValue);
    return copyText(TextView(defaultValue, length), output,
                    outputCapacity, outputLength);
}

bool readInteger(const char* data, QInt32 length, QInt32& value)
{
    if (data == 0 || length <= 0) return false;
    QInt32 index = 0;
    bool negative = false;
    if (data[index] == '-' || data[index] == '+') {
        negative = data[index] == '-';
        ++index;
    }
    if (index >= length) return false;

    QUint32 accumulated = 0U;
    const QUint32 positiveLimit = 0x7fffffffU;
    const QUint32 negativeLimit = 0x80000000U;
    const QUint32 limit = negative ? negativeLimit : positiveLimit;
    for (; index < length; ++index) {
        const char digit = data[index];
        if (digit < '0' || digit > '9') return false;
        const QUint32 number = static_cast<QUint32>(digit - '0');
        if (accumulated > (limit - number) / 10U) return false;
        accumulated = accumulated * 10U + number;
    }
    if (negative) {
        if (accumulated == negativeLimit) {
            value = static_cast<QInt32>(-2147483647 - 1);
        } else {
            value = -static_cast<QInt32>(accumulated);
        }
    } else {
        value = static_cast<QInt32>(accumulated);
    }
    return true;
}

bool readLong(const char* data, QInt32 length, QInt64& value)
{
    if (data == 0 || length <= 0) return false;
    QInt32 index = 0;
    bool negative = false;
    if (data[index] == '-' || data[index] == '+') {
        negative = data[index] == '-';
        ++index;
    }
    if (index >= length) return false;

    const QUint64 positiveLimit = static_cast<QUint64>(KLongMaximum);
    const QUint64 negativeLimit = positiveLimit + 1U;
    const QUint64 limit = negative ? negativeLimit : positiveLimit;
    QUint64 accumulated = 0U;
    for (; index < length; ++index) {
        const char digit = data[index];
        if (digit < '0' || digit > '9') return false;
        const QUint64 number = static_cast<QUint64>(digit - '0');
        if (accumulated > (limit - number) / 10U) return false;
        accumulated = accumulated * 10U + number;
    }
    if (negative) {
        if (accumulated == negativeLimit) {
            value = KLongMinimum;
        } else {
            value = -static_cast<QInt64>(accumulated);
        }
    } else {
        value = static_cast<QInt64>(accumulated);
    }
    return true;
}

bool readFloat(const char* data, QInt32 length, float& value)
{
    if (data == 0 || length <= 0 || length >= KValueCapacity) return false;
    char local[KValueCapacity];
    QInt32 i;
    for (i = 0; i < length; ++i) local[i] = data[i];
    local[length] = '\0';
    char* end = 0;
    const double parsed = ::strtod(local, &end);
    if (end != local + length || parsed != parsed ||
        parsed > 3.402823466e+38 || parsed < -3.402823466e+38) {
        return false;
    }
    value = static_cast<float>(parsed);
    return true;
}

bool addCount(QInt32 current, QInt32 addition, QInt32& output)
{
    if (current < 0 || addition < 0 || current > 2147483647 - addition) {
        return false;
    }
    output = current + addition;
    return true;
}

struct TokenCursor {
    TextView text;
    QInt32 position;
    char delimiter;

    TokenCursor(TextView value, char valueDelimiter)
        : text(value), position(0), delimiter(valueDelimiter)
    {
    }

    bool next(TextView& token)
    {
        if (position >= text.length) return false;
        QInt32 end = position;
        while (end < text.length && text.data[end] != delimiter) ++end;
        token = trimmed(TextView(text.data + position, end - position));
        position = end < text.length ? end + 1 : text.length;
        return true;
    }
};

bool parsePosition(TextView token, Vector3D& position)
{
    TokenCursor values(token, ',');
    TextView coordinate;
    QInt32 parsed[3];
    QInt32 index;
    for (index = 0; index < 3; ++index) {
        if (!values.next(coordinate) ||
            !readInteger(coordinate.data, coordinate.length, parsed[index])) {
            return false;
        }
    }
    position.set(parsed[0], parsed[1], parsed[2]);
    return true;
}

bool countPositions(TextView text, QInt32& count)
{
    count = 0;
    TokenCursor cursor(text, ';');
    TextView token;
    while (cursor.next(token)) {
        if (token.length == 0) continue;
        Vector3D ignored;
        if (!parsePosition(token, ignored) ||
            !addCount(count, 1, count)) {
            return false;
        }
    }
    return count > 0;
}

bool countTokens(TextView text, char delimiter, QInt32& count)
{
    count = 0;
    TokenCursor cursor(text, delimiter);
    TextView token;
    while (cursor.next(token)) {
        if (token.length == 0) return false;
        if (!addCount(count, 1, count)) return false;
    }
    return count > 0;
}

bool countIntegerTokens(TextView text, QInt32& count)
{
    count = 0;
    if (equals(text, "NULL")) return true;
    TokenCursor cursor(text, ',');
    TextView token;
    while (cursor.next(token)) {
        if (token.length == 0) return false;
        QInt32 ignored = 0;
        if (!readInteger(token.data, token.length, ignored) ||
            !addCount(count, 1, count)) {
            return false;
        }
    }
    return true;
}

bool getPreset(const LevelObjectBlock& block, TextView& preset)
{
    if (!findProperty(block, "PRESET", preset)) {
        preset = TextView();
        return true;
    }
    return true;
}

bool readPositions(const LevelObjectBlock& block,
                   TextView& positions,
                   QInt32& positionCount)
{
    if (!findProperty(block, "POS", positions)) return false;
    return countPositions(positions, positionCount);
}

bool readOptionalIntegerField(const LevelObjectBlock& block,
                              const TextView& preset,
                              const char* key,
                              bool allowLevelFallback,
                              const SoftwareLevelObjectResolvers& resolvers,
                              QInt32 defaultValue,
                              QInt32& output)
{
    char text[KValueCapacity];
    QInt32 length = 0;
    if (!getField(block, preset, key, allowLevelFallback, resolvers, 0,
                  text, KValueCapacity, length)) {
        output = defaultValue;
        return true;
    }
    return readInteger(text, length, output);
}

bool readOptionalFloatField(const LevelObjectBlock& block,
                            const TextView& preset,
                            const char* key,
                            const SoftwareLevelObjectResolvers& resolvers,
                            float defaultValue,
                            float& output)
{
    char text[KValueCapacity];
    QInt32 length = 0;
    if (!getField(block, preset, key, false, resolvers, 0,
                  text, KValueCapacity, length)) {
        output = defaultValue;
        return true;
    }
    return readFloat(text, length, output);
}

bool readBooleanField(const LevelObjectBlock& block,
                      const TextView& preset,
                      const char* key,
                      bool allowLevelFallback,
                      const SoftwareLevelObjectResolvers& resolvers,
                      bool defaultValue,
                      bool& output)
{
    QInt32 value = defaultValue ? 1 : 0;
    if (!readOptionalIntegerField(block, preset, key, allowLevelFallback,
                                  resolvers, value, value)) {
        return false;
    }
    output = value == 1;
    return true;
}

bool readCommonObjectFields(const LevelObjectBlock& block,
                            const TextView& preset,
                            const SoftwareLevelObjectResolvers& resolvers,
                            bool& visible,
                            bool& hideWhenUnusable,
                            QInt64& lastActivateMillis,
                            QInt64& timeToResetMillis,
                            QInt32& roomId)
{
    visible = true;
    hideWhenUnusable = false;
    lastActivateMillis = -1;
    timeToResetMillis = 0;
    roomId = -1;

    if (!readBooleanField(block, preset, "VISIBLE", false, resolvers,
                          true, visible)) {
        return false;
    }

    char text[KValueCapacity];
    QInt32 length = 0;
    if (getField(block, preset, "REUSETIMER", false, resolvers, 0,
                  text, KValueCapacity, length) && !equals(TextView(text, length), "NULL")) {
        if (!readLong(text, length, timeToResetMillis)) return false;
        hideWhenUnusable = true;
    }
    if (!readBooleanField(block, preset, "HIDEREUSE", false, resolvers,
                          hideWhenUnusable, hideWhenUnusable)) {
        return false;
    }
    if (!readOptionalIntegerField(block, preset, "ROOM_ID", false,
                                  resolvers, -1, roomId)) {
        return false;
    }
    return true;
}

bool readRequiredField(const LevelObjectBlock& block,
                       const TextView& preset,
                       const char* key,
                       const SoftwareLevelObjectResolvers& resolvers,
                       char* output,
                       QInt32 capacity,
                       QInt32& length)
{
    return getField(block, preset, key, false, resolvers, 0,
                    output, capacity, length);
}

bool resolveMeshAsset(const LevelObjectBlock& block,
                      const TextView& preset,
                      const SoftwareLevelObjectResolvers& resolvers,
                      SoftwareLevelMeshAsset& asset,
                      QInt32& baseVertexCount)
{
    if (resolvers.resolveMesh == 0) return false;

    char modelText[KValueCapacity];
    QInt32 modelLength = 0;
    char textureText[KValueCapacity];
    QInt32 textureLength = 0;
    char drawModeText[KValueCapacity];
    QInt32 drawModeLength = 0;
    if (!readRequiredField(block, preset, "MODEL", resolvers,
                           modelText, KValueCapacity, modelLength) ||
        modelLength <= 0 ||
        !readRequiredField(block, preset, "TEX", resolvers,
                           textureText, KValueCapacity, textureLength) ||
        textureLength <= 0 ||
        !getField(block, preset, "DRAW_MODES", false, resolvers, "",
                  drawModeText, KValueCapacity, drawModeLength)) {
        return false;
    }

    float modelScale = 1.0f;
    float scale = 1.0f;
    bool perspectiveCorrection = false;
    bool realtimeLighting = false;
    if (!readOptionalFloatField(block, preset, "MODEL_SCALE", resolvers,
                                1.0f, modelScale) ||
        !readOptionalFloatField(block, preset, "SCALE", resolvers,
                                modelScale, scale) ||
        !readBooleanField(block, preset, "PERS_CORRECT", true, resolvers,
                          false, perspectiveCorrection) ||
        !readBooleanField(block, preset, "REALTIME_LIGHTING", true,
                          resolvers, false, realtimeLighting)) {
        return false;
    }

    asset = SoftwareLevelMeshAsset();
    if (!resolvers.resolveMesh(
            resolvers.context, modelText, modelLength, scale,
            textureText, textureLength, perspectiveCorrection,
            realtimeLighting, drawModeText, drawModeLength, asset) ||
        asset.frameVertices == 0 || asset.frames == 0 || asset.frameCount <= 0 ||
        asset.frames[0].vertexOffset < 0 ||
        asset.frames[0].vertexCount < 0 ||
        asset.frames[0].polygonOffset < 0 ||
        asset.frames[0].polygonCount < 0 ||
        asset.polygonCount != asset.frames[0].polygonCount ||
        asset.materialCount < 0 ||
        (asset.polygonCount > 0 && asset.polygons == 0) ||
        (asset.materialCount > 0 && asset.materials == 0)) {
        return false;
    }

    baseVertexCount = asset.frames[0].vertexCount;
    QInt32 frameIndex;
    for (frameIndex = 0; frameIndex < asset.frameCount; ++frameIndex) {
        if (asset.frames[frameIndex].vertexOffset < 0 ||
            asset.frames[frameIndex].vertexCount < baseVertexCount) {
            return false;
        }
    }
    return true;
}

bool scanRequirements(const unsigned char* data,
                      QInt32 dataLength,
                      const SoftwareLevelObjectResolvers& resolvers,
                      SoftwareLevelObjectRequirements& requirements)
{
    requirements = SoftwareLevelObjectRequirements();
    if (dataLength < 0 || (dataLength > 0 && data == 0)) return false;
    QInt32 scanPosition = 0;
    LevelObjectBlock block;
    while (nextBlock(data, dataLength, scanPosition, block)) {
        const LevelObjectKind kind = classify(block.type);
        if (kind == ELevelObjectIgnored) continue;

        TextView preset;
        getPreset(block, preset);
        TextView positionText;
        QInt32 positionCount = 0;
        if (!readPositions(block, positionText, positionCount)) {
            return false;
        }

        if (kind == ELevelObjectMesh) {
            SoftwareLevelMeshAsset asset;
            QInt32 baseVertexCount = 0;
            if (!resolveMeshAsset(block, preset, resolvers,
                                  asset, baseVertexCount) ||
                !addCount(requirements.meshObjectCount, positionCount,
                          requirements.meshObjectCount)) {
                return false;
            }
            const QInt64 vertexRequirement =
                static_cast<QInt64>(baseVertexCount) * positionCount;
            if (vertexRequirement < 0 || vertexRequirement > 2147483647LL ||
                !addCount(requirements.animationVertexCount,
                          static_cast<QInt32>(vertexRequirement),
                          requirements.animationVertexCount)) {
                return false;
            }
            continue;
        }

        if (!addCount(requirements.spriteObjectCount, positionCount,
                      requirements.spriteObjectCount)) {
            return false;
        }
        char textureText[KValueCapacity];
        QInt32 textureLength = 0;
        QInt32 frameCount = 0;
        if (!readRequiredField(block, preset, "TEX", resolvers,
                               textureText, KValueCapacity, textureLength) ||
            !countTokens(TextView(textureText, textureLength), ',', frameCount) ||
            !addCount(requirements.spriteFrameCount, frameCount,
                      requirements.spriteFrameCount)) {
            return false;
        }

        char animationText[KValueCapacity];
        QInt32 animationLength = 0;
        QInt32 valueCount = 0;
        if (!getField(block, preset, "FLICKER", false, resolvers,
                      "NULL", animationText, KValueCapacity,
                      animationLength) ||
            !countIntegerTokens(TextView(animationText, animationLength),
                                valueCount) ||
            !addCount(requirements.flickerValueCount, valueCount,
                      requirements.flickerValueCount)) {
            return false;
        }
        if (!getField(block, preset, "COLOR_ANIM", false, resolvers,
                      "NULL", animationText, KValueCapacity,
                      animationLength) ||
            !countIntegerTokens(TextView(animationText, animationLength),
                                valueCount) ||
            !addCount(requirements.colorAnimationValueCount, valueCount,
                      requirements.colorAnimationValueCount)) {
            return false;
        }
    }
    return true;
}

bool parseOptionalIntList(TextView value,
                          bool* boolOutput,
                          QInt32* intOutput,
                          QInt32 capacity,
                          QInt32& written)
{
    written = 0;
    if (equals(value, "NULL")) return true;
    TokenCursor cursor(value, ',');
    TextView token;
    while (cursor.next(token)) {
        if (token.length == 0 || written >= capacity) return false;
        QInt32 number = 0;
        if (!readInteger(token.data, token.length, number)) return false;
        if (boolOutput != 0) boolOutput[written] = number == 1;
        if (intOutput != 0) intOutput[written] = number;
        ++written;
    }
    return true;
}

bool readSpriteFields(const LevelObjectBlock& block,
                      const TextView& preset,
                      const SoftwareLevelObjectResolvers& resolvers,
                      const SoftwareLevelObjectLoadStorage& storage,
                      QInt32 frameOffset,
                      QInt32& frameCount,
                      QInt32 flickerOffset,
                      QInt32& flickerCount,
                      QInt32 colorOffset,
                      QInt32& colorCount,
                      SoftwareWorldSprite& sprite)
{
    char textureText[KValueCapacity];
    QInt32 textureLength = 0;
    if (!readRequiredField(block, preset, "TEX", resolvers,
                           textureText, KValueCapacity, textureLength) ||
        !countTokens(TextView(textureText, textureLength), ',', frameCount) ||
        resolvers.resolveTexture == 0 || frameOffset < 0 ||
        frameOffset > storage.spriteFrameCapacity ||
        frameCount > storage.spriteFrameCapacity - frameOffset ||
        storage.spriteFrames == 0) {
        return false;
    }

    QInt32 firstTextureScale = 2;
    QInt32 loadedFrameCount = 0;
    TokenCursor textureCursor(TextView(textureText, textureLength), ',');
    TextView path;
    while (textureCursor.next(path)) {
        if (path.length == 0 ||
            frameOffset + loadedFrameCount >= storage.spriteFrameCapacity) {
            return false;
        }
        QInt32 textureScale = 2;
        SoftwareTextureView texture;
        if (!resolvers.resolveTexture(
                resolvers.context, path.data, path.length,
                texture, textureScale)) {
            return false;
        }
        const QInt64 pixelCount =
            static_cast<QInt64>(texture.width) * texture.height;
        if (texture.pixels == 0 || texture.pixelCount <= 0 ||
            texture.width <= 0 || texture.height <= 0 ||
            pixelCount <= 0 || pixelCount > texture.pixelCount) {
            return false;
        }
        storage.spriteFrames[frameOffset + loadedFrameCount] = texture;
        if (loadedFrameCount == 0) firstTextureScale = textureScale;
        ++loadedFrameCount;
    }
    if (loadedFrameCount <= 0 || loadedFrameCount != frameCount) return false;

    QInt32 scale = 5;
    float animationSpeed = 1.0f;
    QInt32 color = 0;
    if (!readOptionalIntegerField(block, preset, "SCALE", false, resolvers,
                                  5, scale) ||
        !readOptionalFloatField(block, preset, "ANIMATION_SPEED", resolvers,
                                1.0f, animationSpeed) ||
        !readOptionalIntegerField(block, preset, "COLOR", false, resolvers,
                                  0, color)) {
        return false;
    }

    char value[KValueCapacity];
    QInt32 valueLength = 0;
    const SoftwareTextureView& firstFrame =
        storage.spriteFrames[frameOffset];
    const QInt32 rawHeight = code::Math::javaIntMul(
        firstFrame.height, scale);
    if (!getField(block, preset, "OFFSET", false, resolvers, "NULL",
                  value, KValueCapacity, valueLength)) {
        return false;
    }
    QInt32 offsetY = 0;
    if (equals(TextView(value, valueLength), "MID")) {
        offsetY = code::Math::javaIntDiv(
            code::Math::javaIntSub(0, rawHeight), 2);
    } else if (equals(TextView(value, valueLength), "UP")) {
        offsetY = code::Math::javaIntSub(0, rawHeight);
    }

    if (!getField(block, preset, "MODE", false, resolvers, "NULL",
                  value, KValueCapacity, valueLength)) {
        return false;
    }
    QInt32 mode = ESoftwareWorldSpriteKeyed;
    if (equals(TextView(value, valueLength), "ADD")) {
        mode = ESoftwareWorldSpriteAdd;
    } else if (equals(TextView(value, valueLength), "MUL")) {
        mode = ESoftwareWorldSpriteMultiply;
    } else if (equals(TextView(value, valueLength), "OPAQUE")) {
        if (firstFrame.hasAlphaMixing) mode = ESoftwareWorldSpriteAlpha;
    } else if (equals(TextView(value, valueLength), "NULL") &&
               firstFrame.hasAlphaMixing) {
        mode = ESoftwareWorldSpriteAlpha;
    }

    if (!getField(block, preset, "FOG", false, resolvers, "NULL",
                  value, KValueCapacity, valueLength)) {
        return false;
    }
    const TextView fogValue(value, valueLength);
    const bool fog = equals(fogValue, "MIX") ||
        (equals(fogValue, "NULL") &&
         (resolvers.standardDrawMode == 6 ||
          resolvers.standardDrawMode == 3 ||
          resolvers.standardDrawMode == 1));

    if (!getField(block, preset, "CUTOFF", false, resolvers, "NULL",
                  value, KValueCapacity, valueLength)) {
        return false;
    }
    const QInt32 cutoff =
        equals(TextView(value, valueLength), "UP") ? 1 : 0;

    char flickerText[KValueCapacity];
    QInt32 flickerTextLength = 0;
    if (!getField(block, preset, "FLICKER", false, resolvers, "NULL",
                  flickerText, KValueCapacity, flickerTextLength) ||
        flickerOffset < 0 ||
        flickerOffset > storage.flickerValueCapacity) {
        return false;
    }
    QInt32 parsedFlickerCount = 0;
    if (!parseOptionalIntList(
            TextView(flickerText, flickerTextLength),
            storage.flickerValues == 0 ? 0 :
                storage.flickerValues + flickerOffset,
            0, storage.flickerValueCapacity - flickerOffset,
            parsedFlickerCount) ||
        (parsedFlickerCount > 0 && storage.flickerValues == 0)) {
        return false;
    }
    flickerCount = parsedFlickerCount;

    char colorText[KValueCapacity];
    QInt32 colorTextLength = 0;
    if (!getField(block, preset, "COLOR_ANIM", false, resolvers, "NULL",
                  colorText, KValueCapacity, colorTextLength) ||
        colorOffset < 0 ||
        colorOffset > storage.colorAnimationValueCapacity) {
        return false;
    }
    QInt32 parsedColorCount = 0;
    if (!parseOptionalIntList(
            TextView(colorText, colorTextLength), 0,
            storage.colorAnimationValues == 0 ? 0 :
                storage.colorAnimationValues + colorOffset,
            storage.colorAnimationValueCapacity - colorOffset,
            parsedColorCount) ||
        (parsedColorCount > 0 && storage.colorAnimationValues == 0)) {
        return false;
    }
    colorCount = parsedColorCount;

    sprite = SoftwareWorldSprite();
    sprite.frames = storage.spriteFrames + frameOffset;
    sprite.frameCount = frameCount;
    sprite.scale = scale;
    sprite.textureScale = firstTextureScale;
    sprite.offsetX = 0;
    sprite.offsetY = offsetY;
    sprite.mode = mode;
    sprite.cutoff = cutoff;
    sprite.baseColor = color;
    sprite.fog = fog;
    sprite.animationSpeed = animationSpeed;
    sprite.flicker = flickerCount > 0
        ? storage.flickerValues + flickerOffset : 0;
    sprite.flickerCount = flickerCount;
    sprite.colorAnimation = colorCount > 0
        ? storage.colorAnimationValues + colorOffset : 0;
    sprite.colorAnimationCount = colorCount;
    return true;
}

bool loadMeshRecord(const LevelObjectBlock& block,
                    const TextView& preset,
                    const SoftwareLevelObjectResolvers& resolvers,
                    const SoftwareLevelObjectLoadStorage& storage,
                    QInt32& meshIndex,
                    QInt32 positionCount,
                    const TextView& positionText,
                    QInt32& animationVertexOffset,
                    SoftwareLevelObjectRequirements& requirements)
{
    SoftwareLevelMeshAsset asset;
    QInt32 vertexCount = 0;
    if (!resolveMeshAsset(block, preset, resolvers, asset, vertexCount)) {
        return false;
    }

    QInt32 rotationY = 0;
    if (!readOptionalIntegerField(block, preset, "ROTATE_Y", false,
                                  resolvers, 0, rotationY) ||
        !readOptionalIntegerField(block, preset, "ROT", false,
                                  resolvers, rotationY, rotationY)) {
        return false;
    }
    float animationSpeed = 0.0f;
    if (!readOptionalFloatField(block, preset, "ANIMATION_SPEED", resolvers,
                                0.0f, animationSpeed)) {
        return false;
    }
    QInt32 addSz = 1500;
    QInt32 animationType = -1;
    if (!readOptionalIntegerField(block, preset, "ADDSZ", false, resolvers,
                                  1500, addSz) ||
        !readOptionalIntegerField(block, preset, "ANIMATION_TYPE", false,
                                  resolvers, -1, animationType)) {
        return false;
    }
    bool visible = true;
    bool hideWhenUnusable = false;
    QInt64 lastActivateMillis = -1;
    QInt64 timeToResetMillis = 0;
    QInt32 roomId = -1;
    if (!readCommonObjectFields(block, preset, resolvers, visible,
                                hideWhenUnusable, lastActivateMillis,
                                timeToResetMillis, roomId)) {
        return false;
    }

    if (vertexCount > 0 && storage.animationVertices == 0) return false;
    const QInt64 requiredVertexCount =
        static_cast<QInt64>(vertexCount) * positionCount;
    if (animationVertexOffset < 0 ||
        animationVertexOffset > storage.animationVertexCapacity ||
        requiredVertexCount < 0 ||
        requiredVertexCount > storage.animationVertexCapacity -
            animationVertexOffset) {
        return false;
    }

    TokenCursor positions(positionText, ';');
    TextView positionToken;
    while (positions.next(positionToken)) {
        if (positionToken.length == 0) continue;
        Vector3D position;
        if (!parsePosition(positionToken, position) ||
            meshIndex >= storage.meshObjectCapacity) {
            return false;
        }
        SoftwareWorldMeshObjectInstance& object =
            storage.meshObjects[meshIndex];
        object.mesh = SoftwareMeshView(
            asset.frameVertices + asset.frames[0].vertexOffset,
            vertexCount, asset.polygons, asset.polygonCount,
            asset.materials, asset.materialCount);
        object.objectToWorld.setRotY(rotationY);
        object.objectToWorld.setPosition(position);
        object.modelToCamera.setIdentity();
        object.addSz = addSz;
        object.roomId = roomId;
        object.visible = visible;
        object.hideWhenUnusable = hideWhenUnusable;
        object.lastActivateMillis = lastActivateMillis;
        object.timeToResetMillis = timeToResetMillis;
        object.sourceInstance = SoftwareMeshInstance(
            &object.mesh, &object.modelToCamera, addSz);

        object.animation.sourceInstance = &object.sourceInstance;
        object.animation.frameVertices = asset.frameVertices;
        object.animation.frames = asset.frames;
        object.animation.frameCount = asset.frameCount;
        object.animation.clock.frameInter = 0;
        object.animation.clock.state = animationType == 2
            ? ESoftwareMeshAnimationClose : ESoftwareMeshAnimationLoop;
        object.animation.clock.clickAnim = 0;
        object.animation.clock.clickTimeMillis = 0;
        object.animation.clock.gameTimeMillis = 0;
        object.animation.clock.animationSpeed = animationSpeed;
        object.animation.morphEnabled = true;
        object.animation.animatedVertices = vertexCount > 0
            ? storage.animationVertices + animationVertexOffset : 0;
        object.animation.animatedVertexCapacity = vertexCount;
        animationVertexOffset += vertexCount;
        if (!addCount(meshIndex, 1, meshIndex)) return false;
    }

    QInt32 nextAnimationVertexCount = 0;
    if (!addCount(requirements.animationVertexCount,
                  static_cast<QInt32>(requiredVertexCount),
                  nextAnimationVertexCount)) {
        return false;
    }
    requirements.animationVertexCount = nextAnimationVertexCount;
    return true;
}

bool loadSpriteRecord(const LevelObjectBlock& block,
                      const TextView& preset,
                      const SoftwareLevelObjectResolvers& resolvers,
                      const SoftwareLevelObjectLoadStorage& storage,
                      QInt32& spriteIndex,
                      QInt32 positionCount,
                      const TextView& positionText,
                      QInt32& frameOffset,
                      QInt32& flickerOffset,
                      QInt32& colorOffset)
{
    if (spriteIndex < 0 || positionCount < 0 ||
        spriteIndex > storage.spriteInstanceCapacity ||
        spriteIndex > storage.spriteDefinitionCapacity ||
        positionCount > storage.spriteInstanceCapacity - spriteIndex ||
        positionCount > storage.spriteDefinitionCapacity - spriteIndex ||
        storage.spriteInstances == 0 || storage.spriteDefinitions == 0) {
        return false;
    }

    QInt32 frameCount = 0;
    QInt32 flickerCount = 0;
    QInt32 colorCount = 0;
    SoftwareWorldSprite prototype;
    if (!readSpriteFields(
            block, preset, resolvers, storage, frameOffset, frameCount,
            flickerOffset, flickerCount, colorOffset, colorCount, prototype)) {
        return false;
    }

    QInt32 addSz = 2000;
    bool visible = true;
    bool hideWhenUnusable = false;
    QInt64 lastActivateMillis = -1;
    QInt64 timeToResetMillis = 0;
    QInt32 roomId = -1;
    if (!readOptionalIntegerField(block, preset, "ADDSZ", false, resolvers,
                                  2000, addSz) ||
        !readCommonObjectFields(block, preset, resolvers, visible,
                                hideWhenUnusable, lastActivateMillis,
                                timeToResetMillis, roomId)) {
        return false;
    }

    TokenCursor positions(positionText, ';');
    TextView positionToken;
    QInt32 written = 0;
    while (positions.next(positionToken)) {
        if (positionToken.length == 0) continue;
        Vector3D position;
        if (!parsePosition(positionToken, position) ||
            written >= positionCount) {
            return false;
        }
        const QInt32 index = spriteIndex + written;
        storage.spriteDefinitions[index] = prototype;
        SoftwareWorldSpriteInstance& instance = storage.spriteInstances[index];
        instance.sprite = &storage.spriteDefinitions[index];
        instance.position = position;
        instance.addSz = addSz;
        instance.roomId = roomId;
        instance.visible = visible;
        instance.hideWhenUnusable = hideWhenUnusable;
        instance.lastActivateMillis = lastActivateMillis;
        instance.timeToResetMillis = timeToResetMillis;
        instance.animationBeginMillis = 0;
        instance.frame100 = 0;
        instance.currentColor = prototype.baseColor;
        instance.colorInitialized = false;
        ++written;
    }
    if (written != positionCount) return false;

    spriteIndex += written;
    frameOffset += frameCount;
    flickerOffset += flickerCount;
    colorOffset += colorCount;
    return true;
}

} // anonymous namespace

SoftwareLevelMeshAsset::SoftwareLevelMeshAsset()
    : frameVertices(0), frames(0), frameCount(0), polygons(0),
      polygonCount(0), materials(0), materialCount(0)
{
}

SoftwareLevelObjectResolvers::SoftwareLevelObjectResolvers()
    : context(0), resolveMesh(0), resolveTexture(0),
      resolvePresetSetting(0), resolveGlobalSetting(0),
      resolveLevelSetting(0), standardDrawMode(0)
{
}

SoftwareWorldMeshObjectInstance::SoftwareWorldMeshObjectInstance()
    : mesh(), sourceInstance(), objectToWorld(), modelToCamera(), animation(),
      addSz(0), roomId(-1), visible(true), hideWhenUnusable(false),
      lastActivateMillis(-1), timeToResetMillis(0)
{
}

void SoftwareWorldMeshObjectInstance::setPosition(QInt32 x,
                                                  QInt32 y,
                                                  QInt32 z)
{
    objectToWorld.setPosition(x, y, z);
}

void SoftwareWorldMeshObjectInstance::setRotationY(QInt32 degrees)
{
    const QInt32 x = objectToWorld.m03;
    const QInt32 y = objectToWorld.m13;
    const QInt32 z = objectToWorld.m23;
    objectToWorld.setRotY(degrees);
    objectToWorld.setPosition(x, y, z);
}

bool SoftwareWorldMeshObjectInstance::toggleClickAnimation(
    QInt64 gameTimeMillis)
{
    animation.clock.gameTimeMillis = gameTimeMillis;
    QInt32 currentFrame = 0;
    if (!SoftwareMeshAnimator::frameForState(
            animation.clock, animation.frameCount, currentFrame)) {
        return false;
    }
    animation.clock.clickAnim = currentFrame;
    animation.clock.clickTimeMillis = gameTimeMillis;
    animation.frameQ10 = currentFrame;
    if (animation.clock.state == ESoftwareMeshAnimationClose) {
        animation.clock.state = ESoftwareMeshAnimationOpen;
    } else if (animation.clock.state == ESoftwareMeshAnimationOpen) {
        animation.clock.state = ESoftwareMeshAnimationClose;
    }
    return true;
}

SoftwareLevelObjectRequirements::SoftwareLevelObjectRequirements()
    : meshObjectCount(0), spriteObjectCount(0), spriteFrameCount(0),
      flickerValueCount(0), colorAnimationValueCount(0),
      animationVertexCount(0)
{
}

SoftwareLevelObjectLoadStorage::SoftwareLevelObjectLoadStorage()
    : meshObjects(0), meshObjectCapacity(0), spriteInstances(0),
      spriteInstanceCapacity(0), spriteDefinitions(0),
      spriteDefinitionCapacity(0), spriteFrames(0), spriteFrameCapacity(0),
      flickerValues(0), flickerValueCapacity(0), colorAnimationValues(0),
      colorAnimationValueCapacity(0), animationVertices(0),
      animationVertexCapacity(0)
{
}

bool SoftwareLevelObjectLoader::measure(
    const unsigned char* data,
    QInt32 dataLength,
    const SoftwareLevelObjectResolvers& resolvers,
    SoftwareLevelObjectRequirements& requirements)
{
    return scanRequirements(data, dataLength, resolvers, requirements);
}

bool SoftwareLevelObjectLoader::load(
    const unsigned char* data,
    QInt32 dataLength,
    const SoftwareLevelObjectResolvers& resolvers,
    const SoftwareLevelObjectLoadStorage& storage,
    SoftwareLevelObjectRequirements& requirements)
{
    SoftwareLevelObjectRequirements measured;
    if (!scanRequirements(data, dataLength, resolvers, measured) ||
        storage.meshObjectCapacity < measured.meshObjectCount ||
        storage.spriteInstanceCapacity < measured.spriteObjectCount ||
        storage.spriteDefinitionCapacity < measured.spriteObjectCount ||
        storage.spriteFrameCapacity < measured.spriteFrameCount ||
        storage.flickerValueCapacity < measured.flickerValueCount ||
        storage.colorAnimationValueCapacity <
            measured.colorAnimationValueCount ||
        storage.animationVertexCapacity < measured.animationVertexCount ||
        (measured.meshObjectCount > 0 && storage.meshObjects == 0) ||
        (measured.spriteObjectCount > 0 &&
         (storage.spriteInstances == 0 ||
          storage.spriteDefinitions == 0)) ||
        (measured.spriteFrameCount > 0 && storage.spriteFrames == 0) ||
        (measured.flickerValueCount > 0 && storage.flickerValues == 0) ||
        (measured.colorAnimationValueCount > 0 &&
         storage.colorAnimationValues == 0) ||
        (measured.animationVertexCount > 0 &&
         storage.animationVertices == 0)) {
        return false;
    }

    QInt32 scanPosition = 0;
    QInt32 meshIndex = 0;
    QInt32 spriteIndex = 0;
    QInt32 frameOffset = 0;
    QInt32 flickerOffset = 0;
    QInt32 colorOffset = 0;
    QInt32 animationVertexOffset = 0;
    SoftwareLevelObjectRequirements loaded;
    LevelObjectBlock block;
    while (nextBlock(data, dataLength, scanPosition, block)) {
        const LevelObjectKind kind = classify(block.type);
        if (kind == ELevelObjectIgnored) continue;
        TextView preset;
        getPreset(block, preset);
        TextView positionText;
        QInt32 positionCount = 0;
        if (!readPositions(block, positionText, positionCount)) {
            return false;
        }
        if (kind == ELevelObjectMesh) {
            if (!loadMeshRecord(
                    block, preset, resolvers, storage, meshIndex,
                    positionCount, positionText,
                    animationVertexOffset, loaded)) {
                return false;
            }
        } else if (!loadSpriteRecord(
                       block, preset, resolvers, storage, spriteIndex,
                       positionCount, positionText,
                       frameOffset, flickerOffset, colorOffset)) {
            return false;
        }
    }
    if (meshIndex != measured.meshObjectCount ||
        spriteIndex != measured.spriteObjectCount ||
        frameOffset != measured.spriteFrameCount ||
        flickerOffset != measured.flickerValueCount ||
        colorOffset != measured.colorAnimationValueCount ||
        animationVertexOffset != measured.animationVertexCount ||
        loaded.animationVertexCount != measured.animationVertexCount) {
        return false;
    }
    loaded.meshObjectCount = meshIndex;
    loaded.spriteObjectCount = spriteIndex;
    loaded.spriteFrameCount = frameOffset;
    loaded.flickerValueCount = flickerOffset;
    loaded.colorAnimationValueCount = colorOffset;
    requirements = loaded;
    return true;
}

SoftwareLevelObjectRenderWorkspace::SoftwareLevelObjectRenderWorkspace()
    : meshInstances(0), meshInstanceCapacity(0)
{
}

SoftwareLevelObjectRenderWorkspace::SoftwareLevelObjectRenderWorkspace(
    SoftwareMeshInstance* instanceData,
    QInt32 instanceCapacity)
    : meshInstances(instanceData), meshInstanceCapacity(instanceCapacity)
{
}

void SoftwareLevelObjectRenderer::advanceMeshAnimationClocks(
    SoftwareWorldMeshObjectInstance* objects,
    QInt32 objectCount,
    QInt32 frameTime)
{
    if (objects == 0 || objectCount <= 0) return;
    const QInt32 frameAdvance = code::Math::javaIntDiv(
        code::Math::javaIntMul(100, frameTime), 50);
    QInt32 index;
    for (index = 0; index < objectCount; ++index) {
        objects[index].animation.clock.frameInter = code::Math::javaIntAdd(
            objects[index].animation.clock.frameInter, frameAdvance);
    }
}

bool SoftwareLevelObjectRenderer::render(
    const SoftwareRenderSurfaceView& destination,
    const SoftwareMeshInstance* sceneMeshInstances,
    QInt32 sceneMeshInstanceCount,
    SoftwareWorldMeshObjectInstance* meshObjects,
    QInt32 meshObjectCount,
    SoftwareWorldSpriteInstance* spriteObjects,
    QInt32 spriteObjectCount,
    const code::Math::Matrix& worldToCamera,
    const SoftwareMeshRenderOptions& meshOptions,
    const SoftwareWorldSpriteRenderOptions& spriteOptions,
    const SoftwareLevelObjectRenderWorkspace& objectWorkspace,
    const SoftwareMeshRenderWorkspace& renderWorkspace,
    SoftwareMeshRenderStats& meshStats,
    SoftwareWorldSpriteRenderStats& spriteStats)
{
    if (sceneMeshInstanceCount < 0 || meshObjectCount < 0 ||
        spriteObjectCount < 0 ||
        (sceneMeshInstanceCount > 0 && sceneMeshInstances == 0) ||
        (meshObjectCount > 0 && meshObjects == 0) ||
        (spriteObjectCount > 0 && spriteObjects == 0) ||
        objectWorkspace.meshInstanceCapacity < 0 ||
        sceneMeshInstanceCount > objectWorkspace.meshInstanceCapacity ||
        (sceneMeshInstanceCount > 0 && objectWorkspace.meshInstances == 0) ||
        meshObjectCount > 2147483647 - sceneMeshInstanceCount) {
        return false;
    }

    SoftwareWorldSpriteRenderOptions objectSpriteOptions = spriteOptions;
    objectSpriteOptions.worldToCamera = &worldToCamera;
    QInt32 meshInstanceCount = sceneMeshInstanceCount;
    QInt32 sceneIndex;
    if (sceneMeshInstances != objectWorkspace.meshInstances) {
        for (sceneIndex = 0; sceneIndex < sceneMeshInstanceCount;
             ++sceneIndex) {
            objectWorkspace.meshInstances[sceneIndex] =
                sceneMeshInstances[sceneIndex];
        }
    }
    QInt32 objectIndex;
    for (objectIndex = 0; objectIndex < meshObjectCount; ++objectIndex) {
        SoftwareWorldMeshObjectInstance& object = meshObjects[objectIndex];
        if (!object.visible ||
            (object.lastActivateMillis >= 0 && object.hideWhenUnusable &&
             code::Math::javaLongAdd(
                 object.lastActivateMillis, object.timeToResetMillis) >
                 objectSpriteOptions.gameTimeMillis)) {
            continue;
        }
        if (objectWorkspace.meshInstances == 0 ||
            meshInstanceCount >= objectWorkspace.meshInstanceCapacity) {
            return false;
        }

        object.modelToCamera.mul(worldToCamera, object.objectToWorld);
        object.sourceInstance.mesh = &object.mesh;
        object.sourceInstance.modelToCamera = &object.modelToCamera;
        object.sourceInstance.depthBias = object.addSz;
        object.animation.sourceInstance = &object.sourceInstance;
        object.animation.clock.gameTimeMillis =
            objectSpriteOptions.gameTimeMillis;
        if (!SoftwareMeshAnimator::prepareInstance(object.animation)) {
            return false;
        }
        objectWorkspace.meshInstances[meshInstanceCount] =
            object.animation.renderInstance;
        ++meshInstanceCount;
    }

    return SoftwareMeshRenderer::renderSceneWithSprites(
        destination, objectWorkspace.meshInstances, meshInstanceCount,
        spriteObjects, spriteObjectCount, objectSpriteOptions,
        meshOptions, renderWorkspace, meshStats, spriteStats);
}

} // namespace Rendering
} // namespace code
