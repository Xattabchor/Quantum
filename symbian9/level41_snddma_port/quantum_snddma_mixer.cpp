#include "quantum_snddma_mixer.h"

#include <cstring>
#include <limits>

namespace quantum {
namespace snddma {
namespace {

const std::uint16_t KWaveFormatPcm = 1;
const std::uint16_t KBitsPerSample = 16;
const std::uint32_t KMinSampleRate = 4000;
const std::uint32_t KMaxSampleRate = 48000;
const std::uint64_t KQ16One = 65536ULL;
const std::uint32_t KQ15One = 32767U;

std::uint16_t ReadLe16(const std::uint8_t* aBytes)
{
    return static_cast<std::uint16_t>(
        static_cast<std::uint16_t>(aBytes[0]) |
        (static_cast<std::uint16_t>(aBytes[1]) << 8));
}

std::uint32_t ReadLe32(const std::uint8_t* aBytes)
{
    return static_cast<std::uint32_t>(aBytes[0]) |
           (static_cast<std::uint32_t>(aBytes[1]) << 8) |
           (static_cast<std::uint32_t>(aBytes[2]) << 16) |
           (static_cast<std::uint32_t>(aBytes[3]) << 24);
}

bool HasId(const std::uint8_t* aBytes, const char* aId)
{
    return aBytes[0] == static_cast<std::uint8_t>(aId[0]) &&
           aBytes[1] == static_cast<std::uint8_t>(aId[1]) &&
           aBytes[2] == static_cast<std::uint8_t>(aId[2]) &&
           aBytes[3] == static_cast<std::uint8_t>(aId[3]);
}

std::uint16_t ScaleQ15(std::uint16_t aVolume, std::uint32_t aPanScale)
{
    const std::uint64_t value =
        static_cast<std::uint64_t>(aVolume) * aPanScale + KQ15One / 2U;
    return static_cast<std::uint16_t>(value / KQ15One);
}

} // namespace

PcmWaveView::PcmWaveView()
    : samples(0), sampleBytes(0), frames(0), sampleRate(0), channels(0)
{
}

bool PcmWaveView::IsValid() const
{
    if (samples == 0 || sampleBytes == 0 || frames == 0 ||
        sampleRate < KMinSampleRate || sampleRate > KMaxSampleRate ||
        (channels != 1 && channels != 2)) {
        return false;
    }
    const std::size_t bytesPerFrame =
        static_cast<std::size_t>(channels) * 2U;
    if (frames > std::numeric_limits<std::size_t>::max() / bytesPerFrame) {
        return false;
    }
    return sampleBytes >= frames * bytesPerFrame;
}

WaveParseResult ParsePcm16Wave(const std::uint8_t* aWaveBytes,
                               std::size_t aWaveSize,
                               PcmWaveView& aView)
{
    aView = PcmWaveView();
    if (aWaveBytes == 0) return EWaveParseBadArgument;
    if (aWaveSize < 12U) return EWaveParseTruncated;
    if (!HasId(aWaveBytes, "RIFF") || !HasId(aWaveBytes + 8U, "WAVE")) {
        return EWaveParseBadContainer;
    }

    const std::uint32_t riffPayloadBytes = ReadLe32(aWaveBytes + 4U);
    if (riffPayloadBytes < 4U) return EWaveParseBadContainer;
    if (static_cast<std::uint64_t>(riffPayloadBytes) + 8ULL > aWaveSize) {
        return EWaveParseTruncated;
    }
    const std::size_t riffEnd =
        static_cast<std::size_t>(riffPayloadBytes) + 8U;

    bool haveFormat = false;
    bool haveData = false;
    std::uint16_t formatTag = 0;
    std::uint16_t channels = 0;
    std::uint16_t bitsPerSample = 0;
    std::uint16_t blockAlign = 0;
    std::uint32_t sampleRate = 0;
    const std::uint8_t* sampleData = 0;
    std::size_t sampleBytes = 0;

    std::size_t cursor = 12U;
    while (cursor <= riffEnd && riffEnd - cursor >= 8U) {
        const std::uint8_t* chunkHeader = aWaveBytes + cursor;
        const std::uint32_t chunkBytes32 = ReadLe32(chunkHeader + 4U);
        const std::size_t payload = cursor + 8U;
        if (static_cast<std::uint64_t>(chunkBytes32) >
            static_cast<std::uint64_t>(riffEnd - payload)) {
            return EWaveParseTruncated;
        }
        const std::size_t chunkBytes =
            static_cast<std::size_t>(chunkBytes32);

        if (!haveFormat && HasId(chunkHeader, "fmt ")) {
            if (chunkBytes < 16U) return EWaveParseUnsupportedPcm;
            const std::uint8_t* fmt = aWaveBytes + payload;
            formatTag = ReadLe16(fmt);
            channels = ReadLe16(fmt + 2U);
            sampleRate = ReadLe32(fmt + 4U);
            blockAlign = ReadLe16(fmt + 12U);
            bitsPerSample = ReadLe16(fmt + 14U);
            haveFormat = true;
        } else if (!haveData && HasId(chunkHeader, "data")) {
            sampleData = aWaveBytes + payload;
            sampleBytes = chunkBytes;
            haveData = true;
        }

        const std::size_t paddedChunkBytes =
            chunkBytes + (chunkBytes & static_cast<std::size_t>(1U));
        if (paddedChunkBytes > riffEnd - payload) {
            return EWaveParseTruncated;
        }
        cursor = payload + paddedChunkBytes;
    }

    if (!haveFormat || !haveData || sampleData == 0 || sampleBytes == 0) {
        return EWaveParseTruncated;
    }
    if (formatTag != KWaveFormatPcm || bitsPerSample != KBitsPerSample ||
        (channels != 1 && channels != 2) ||
        sampleRate < KMinSampleRate || sampleRate > KMaxSampleRate ||
        blockAlign != static_cast<std::uint16_t>(channels * 2U)) {
        return EWaveParseUnsupportedPcm;
    }

    const std::size_t bytesPerFrame =
        static_cast<std::size_t>(channels) * 2U;
    if (sampleBytes % bytesPerFrame != 0U) {
        return EWaveParseUnsupportedPcm;
    }
    const std::size_t frames = sampleBytes / bytesPerFrame;
    if (frames == 0U) return EWaveParseUnsupportedPcm;

    aView.samples = sampleData;
    aView.sampleBytes = sampleBytes;
    aView.frames = frames;
    aView.sampleRate = sampleRate;
    aView.channels = channels;
    return EWaveParseOk;
}

Mixer::TVoice::TVoice()
    : iWave(),
      iPositionQ16(0),
      iStepQ16(0),
      iLeftGainQ15(0),
      iRightGainQ15(0),
      iActive(false)
{
}

Mixer::Mixer(std::uint32_t aOutputRate)
    : iAssets(), iVoices(),
      iOutputRate(aOutputRate >= KMinSampleRate &&
                          aOutputRate <= KMaxSampleRate
                      ? aOutputRate
                      : KDefaultOutputRate),
      iDroppedVoices(0)
{
}

bool Mixer::SetShotWave(const PcmWaveView& aWave)
{
    return SetWave(EAssetShot, aWave);
}

bool Mixer::SetReloadWave(const PcmWaveView& aWave)
{
    return SetWave(EAssetReload, aWave);
}

bool Mixer::SetWave(TAssetId aAsset, const PcmWaveView& aWave)
{
    if (aAsset < 0 || aAsset >= EAssetCount || !aWave.IsValid()) return false;
    iAssets[aAsset] = aWave;
    return true;
}

bool Mixer::PlayShot(std::uint16_t aVolumeQ15, std::int16_t aPanQ15)
{
    return Play(EAssetShot, aVolumeQ15, aPanQ15);
}

bool Mixer::PlayReload(std::uint16_t aVolumeQ15, std::int16_t aPanQ15)
{
    return Play(EAssetReload, aVolumeQ15, aPanQ15);
}

bool Mixer::Play(TAssetId aAsset,
                 std::uint16_t aVolumeQ15,
                 std::int16_t aPanQ15)
{
    if (aAsset < 0 || aAsset >= EAssetCount || !iAssets[aAsset].IsValid()) {
        return false;
    }
    if (aVolumeQ15 > KQ15One) aVolumeQ15 = KQ15One;

    const std::size_t slot = FindVoiceSlot();
    if (slot >= KMaxVoices) return false;
    if (iVoices[slot].iActive) ++iDroppedVoices;

    std::int32_t leftPanScale = static_cast<std::int32_t>(KQ15One);
    std::int32_t rightPanScale = static_cast<std::int32_t>(KQ15One);
    if (aPanQ15 < 0) {
        rightPanScale += static_cast<std::int32_t>(aPanQ15);
    } else if (aPanQ15 > 0) {
        leftPanScale -= static_cast<std::int32_t>(aPanQ15);
    }
    if (leftPanScale < 0) leftPanScale = 0;
    if (rightPanScale < 0) rightPanScale = 0;

    TVoice& voice = iVoices[slot];
    voice.iWave = iAssets[aAsset];
    voice.iPositionQ16 = 0;
    voice.iStepQ16 = static_cast<std::uint32_t>(
        (static_cast<std::uint64_t>(voice.iWave.sampleRate) * KQ16One +
         iOutputRate / 2U) /
        iOutputRate);
    voice.iLeftGainQ15 = ScaleQ15(
        aVolumeQ15, static_cast<std::uint32_t>(leftPanScale));
    voice.iRightGainQ15 = ScaleQ15(
        aVolumeQ15, static_cast<std::uint32_t>(rightPanScale));
    voice.iActive = true;
    return true;
}

std::size_t Mixer::FindVoiceSlot()
{
    std::size_t i;
    for (i = 0; i < KMaxVoices; ++i) {
        if (!iVoices[i].iActive) return i;
    }

    // Like Quake's channel picker, prefer replacing a voice close to its end.
    std::size_t victim = 0;
    std::uint64_t shortestRemaining =
        static_cast<std::uint64_t>(iVoices[0].iWave.frames) * KQ16One -
        iVoices[0].iPositionQ16;
    for (i = 1; i < KMaxVoices; ++i) {
        const std::uint64_t end =
            static_cast<std::uint64_t>(iVoices[i].iWave.frames) * KQ16One;
        const std::uint64_t remaining =
            end > iVoices[i].iPositionQ16
                ? end - iVoices[i].iPositionQ16
                : 0;
        if (remaining < shortestRemaining) {
            shortestRemaining = remaining;
            victim = i;
        }
    }
    return victim;
}

std::int32_t Mixer::ReadSample(const PcmWaveView& aWave,
                              std::size_t aFrame,
                              std::uint16_t aChannel)
{
    const std::size_t sourceChannel =
        aWave.channels == 1 ? 0U : static_cast<std::size_t>(aChannel);
    const std::size_t offset =
        (aFrame * static_cast<std::size_t>(aWave.channels) + sourceChannel) * 2U;
    const std::uint16_t raw = ReadLe16(aWave.samples + offset);
    return raw >= 0x8000U
        ? static_cast<std::int32_t>(raw) - 65536
        : static_cast<std::int32_t>(raw);
}

std::int32_t Mixer::Interpolate(const PcmWaveView& aWave,
                                std::uint64_t aPositionQ16,
                                std::uint16_t aChannel)
{
    const std::size_t frame =
        static_cast<std::size_t>(aPositionQ16 >> 16);
    const std::size_t nextFrame =
        frame + 1U < aWave.frames ? frame + 1U : frame;
    const std::uint32_t fraction =
        static_cast<std::uint32_t>(aPositionQ16 & 0xffffU);
    const std::int32_t first = ReadSample(aWave, frame, aChannel);
    const std::int32_t second = ReadSample(aWave, nextFrame, aChannel);
    const std::int64_t weighted =
        static_cast<std::int64_t>(first) * (65536U - fraction) +
        static_cast<std::int64_t>(second) * fraction;
    return static_cast<std::int32_t>(weighted / 65536LL);
}

std::int16_t Mixer::ClipToPcm16(std::int64_t aSample)
{
    if (aSample > 32767) aSample = 32767;
    if (aSample < -32768) aSample = -32768;
    return static_cast<std::int16_t>(aSample);
}

bool Mixer::Mix(std::int16_t* aOutputStereo, std::size_t aOutputFrames)
{
    if (aOutputFrames > 0U && aOutputStereo == 0) return false;
    if (aOutputFrames >
        std::numeric_limits<std::size_t>::max() / (2U * sizeof(std::int16_t))) {
        return false;
    }

    std::size_t outFrame;
    for (outFrame = 0; outFrame < aOutputFrames; ++outFrame) {
        std::int64_t left = 0;
        std::int64_t right = 0;
        std::size_t voiceIndex;

        for (voiceIndex = 0; voiceIndex < KMaxVoices; ++voiceIndex) {
            TVoice& voice = iVoices[voiceIndex];
            if (!voice.iActive) continue;

            const std::uint64_t endPosition =
                static_cast<std::uint64_t>(voice.iWave.frames) * KQ16One;
            if (voice.iPositionQ16 >= endPosition) {
                voice.iActive = false;
                continue;
            }

            const std::int32_t sampleLeft =
                Interpolate(voice.iWave, voice.iPositionQ16, 0);
            const std::int32_t sampleRight =
                voice.iWave.channels == 1
                    ? sampleLeft
                    : Interpolate(voice.iWave, voice.iPositionQ16, 1);
            left += (static_cast<std::int64_t>(sampleLeft) *
                     voice.iLeftGainQ15) / KQ15One;
            right += (static_cast<std::int64_t>(sampleRight) *
                      voice.iRightGainQ15) / KQ15One;

            voice.iPositionQ16 += voice.iStepQ16;
            if (voice.iPositionQ16 >= endPosition) voice.iActive = false;
        }

        aOutputStereo[outFrame * 2U] = ClipToPcm16(left);
        aOutputStereo[outFrame * 2U + 1U] = ClipToPcm16(right);
    }
    return true;
}

void Mixer::StopAll()
{
    std::size_t i;
    for (i = 0; i < KMaxVoices; ++i) iVoices[i].iActive = false;
}

std::size_t Mixer::ActiveVoiceCount() const
{
    std::size_t count = 0;
    std::size_t i;
    for (i = 0; i < KMaxVoices; ++i) {
        if (iVoices[i].iActive) ++count;
    }
    return count;
}

std::uint32_t Mixer::OutputRate() const
{
    return iOutputRate;
}

std::uint32_t Mixer::DroppedVoiceCount() const
{
    return iDroppedVoices;
}

} // namespace snddma
} // namespace quantum
