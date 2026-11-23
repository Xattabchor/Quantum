#include "quantum_level41_pcm_core.h"

#include <cstring>

namespace quantum {
namespace level41pcm {

namespace {

std::uint32_t Read32(const std::uint8_t* aBytes)
{
    return static_cast<std::uint32_t>(aBytes[0]) |
           (static_cast<std::uint32_t>(aBytes[1]) << 8) |
           (static_cast<std::uint32_t>(aBytes[2]) << 16) |
           (static_cast<std::uint32_t>(aBytes[3]) << 24);
}

std::uint16_t Read16(const std::uint8_t* aBytes)
{
    return static_cast<std::uint16_t>(static_cast<std::uint16_t>(aBytes[0]) |
                                      (static_cast<std::uint16_t>(aBytes[1])
                                       << 8));
}

bool IsChunkId(const std::uint8_t* aBytes, const char* aId)
{
    return aBytes[0] == static_cast<std::uint8_t>(aId[0]) &&
           aBytes[1] == static_cast<std::uint8_t>(aId[1]) &&
           aBytes[2] == static_cast<std::uint8_t>(aId[2]) &&
           aBytes[3] == static_cast<std::uint8_t>(aId[3]);
}

} // namespace

PcmWaveView::PcmWaveView()
    : samples(0), sampleBytes(0), sampleRate(0), channels(0)
{
}

bool PcmWaveView::IsValid() const
{
    return samples != 0 && sampleBytes != 0 && channels == 1 &&
           (sampleRate == 8000 || sampleRate == 16000);
}

WaveParseResult ParsePcm16Wave(const std::uint8_t* aWaveBytes,
                               std::size_t aWaveSize,
                               PcmWaveView& aView)
{
    aView = PcmWaveView();
    if (aWaveBytes == 0 || aWaveSize < 44) return EWaveParseBadArgument;

    if (!IsChunkId(aWaveBytes, "RIFF") || !IsChunkId(aWaveBytes + 8, "WAVE")) {
        return EWaveParseBadContainer;
    }
    const std::uint64_t declared = Read32(aWaveBytes + 4);
    if (declared > aWaveSize - 8) return EWaveParseTruncated;

    std::uint32_t rate = 0;
    std::uint32_t dataSize = 0;
    std::size_t dataOffset = 0;
    bool haveFormat = false;
    std::size_t position = 12;
    while (position + 8 <= aWaveSize) {
        const std::uint32_t chunkBytes = Read32(aWaveBytes + position + 4);
        const std::size_t body = position + 8;
        if (chunkBytes > aWaveSize - body) return EWaveParseTruncated;

        if (IsChunkId(aWaveBytes + position, "fmt ")) {
            if (chunkBytes < 16) return EWaveParseBadContainer;
            const std::uint16_t format = Read16(aWaveBytes + body);
            const std::uint16_t channels = Read16(aWaveBytes + body + 2);
            const std::uint16_t bits = Read16(aWaveBytes + body + 14);
            if (format != 1 || channels != 1 || bits != 16) {
                return EWaveParseUnsupportedPcm;
            }
            rate = Read32(aWaveBytes + body + 4);
            haveFormat = true;
        } else if (IsChunkId(aWaveBytes + position, "data") &&
                   dataOffset == 0) {
            dataOffset = body;
            dataSize = chunkBytes;
        } else if (IsChunkId(aWaveBytes + position, "fact")) {
            if (chunkBytes < 4) return EWaveParseBadContainer;
        }

        const std::size_t padded = chunkBytes + (chunkBytes & 1U);
        if (padded > aWaveSize - body) return EWaveParseTruncated;
        position = body + padded;
    }

    if (!haveFormat || dataOffset == 0 || dataSize == 0) {
        return EWaveParseBadContainer;
    }
    if (rate != 8000 && rate != 16000) return EWaveParseUnsupportedPcm;
    if ((dataSize & 1U) != 0) return EWaveParseUnsupportedPcm;
    if (rate == 16000 && (dataSize & 3U) != 0) return EWaveParseUnsupportedPcm;

    aView.samples = aWaveBytes + dataOffset;
    aView.sampleBytes = dataSize;
    aView.sampleRate = rate;
    aView.channels = 1;
    return EWaveParseOk;
}

std::size_t StreamBytesFor(const PcmWaveView& aView)
{
    if (!aView.IsValid()) return 0;
    return aView.sampleRate == 16000 ? aView.sampleBytes / 2
                                     : aView.sampleBytes;
}

bool CopyToStream(const PcmWaveView& aView, std::uint8_t* aDestination,
                  std::size_t aDestinationBytes)
{
    const std::size_t needed = StreamBytesFor(aView);
    if (needed == 0 || aDestination == 0) return false;
    if (aDestinationBytes < needed) return false;

    if (aView.sampleRate == 8000) {
        std::memcpy(aDestination, aView.samples, needed);
        return true;
    }

    const std::size_t frames = needed / 2;
    for (std::size_t index = 0; index < frames; ++index) {
        const std::int16_t first = static_cast<std::int16_t>(
            Read16(aView.samples + index * 4));
        const std::int16_t second = static_cast<std::int16_t>(
            Read16(aView.samples + index * 4 + 2));
        /* Average in 32-bit so -32768 + -32768 does not wrap. */
        const std::int32_t mixed =
            (static_cast<std::int32_t>(first) + second) / 2;
        const std::uint16_t bits =
            static_cast<std::uint16_t>(static_cast<std::int16_t>(mixed));
        aDestination[index * 2] = static_cast<std::uint8_t>(bits & 0xffU);
        aDestination[index * 2 + 1] =
            static_cast<std::uint8_t>((bits >> 8) & 0xffU);
    }
    return true;
}

VoiceGate::VoiceGate() : iLastDurationUs(0), iHaveSubmitTime(false)
{
}

bool VoiceGate::MaySubmit(bool aReady, bool aInFlight, bool aQueued,
                          bool aHaveSubmitTime,
                          std::int64_t aElapsedUs) const
{
    if (!aReady || aInFlight || aQueued) return false;
    if (aHaveSubmitTime && aElapsedUs >= 0 && aElapsedUs < iLastDurationUs) {
        return false;
    }
    return true;
}

void VoiceGate::RecordSubmit(std::size_t aByteCount)
{
    iLastDurationUs = static_cast<std::int64_t>(aByteCount) * 1000000 /
                      static_cast<std::int64_t>(KStreamBytesPerSecond);
    iHaveSubmitTime = true;
}

void VoiceGate::Reset()
{
    iLastDurationUs = 0;
    iHaveSubmitTime = false;
}

} // namespace level41pcm
} // namespace quantum
