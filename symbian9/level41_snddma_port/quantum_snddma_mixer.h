#ifndef QUANTUM_LEVEL41_SNDDMA_MIXER_H
#define QUANTUM_LEVEL41_SNDDMA_MIXER_H

#include <cstddef>
#include <cstdint>

namespace quantum {
namespace snddma {

static const std::uint32_t KDefaultOutputRate = 22050;
static const std::size_t KMaxVoices = 8;

struct PcmWaveView {
    const std::uint8_t* samples;
    std::size_t sampleBytes;
    std::size_t frames;
    std::uint32_t sampleRate;
    std::uint16_t channels;

    PcmWaveView();
    bool IsValid() const;
};

enum WaveParseResult {
    EWaveParseOk = 0,
    EWaveParseBadArgument,
    EWaveParseBadContainer,
    EWaveParseTruncated,
    EWaveParseUnsupportedPcm
};

// Parses RIFF/WAVE PCM16 without allocating. The returned view points into
// aWaveBytes, which must remain alive for as long as the mixer uses the view.
WaveParseResult ParsePcm16Wave(const std::uint8_t* aWaveBytes,
                               std::size_t aWaveSize,
                               PcmWaveView& aView);

// Small software mixer modeled on Quake's painted-channel/mix-buffer design.
// It produces interleaved signed PCM16 stereo for CMdaAudioOutputStream. It
// deliberately has no Quake client.h, file-system, cvar, or SNDDMA globals.
class Mixer {
public:
    explicit Mixer(std::uint32_t aOutputRate = KDefaultOutputRate);

    bool SetShotWave(const PcmWaveView& aWave);
    bool SetReloadWave(const PcmWaveView& aWave);

    // Volume is Q15 [0,32767]. Pan is signed Q15 [-32768,32767], where
    // -32768 is hard left, zero is center, and 32767 is hard right.
    bool PlayShot(std::uint16_t aVolumeQ15 = 24576,
                  std::int16_t aPanQ15 = 0);
    bool PlayReload(std::uint16_t aVolumeQ15 = 24576,
                    std::int16_t aPanQ15 = 0);

    // Mixes aOutputFrames frames into interleaved L,R samples. If no voices
    // are active it writes digital silence. Returns false only for bad args.
    bool Mix(std::int16_t* aOutputStereo, std::size_t aOutputFrames);

    void StopAll();
    std::size_t ActiveVoiceCount() const;
    std::uint32_t OutputRate() const;
    std::uint32_t DroppedVoiceCount() const;

private:
    enum TAssetId {
        EAssetShot = 0,
        EAssetReload = 1,
        EAssetCount = 2
    };

    struct TVoice {
        PcmWaveView iWave;
        std::uint64_t iPositionQ16;
        std::uint32_t iStepQ16;
        std::uint16_t iLeftGainQ15;
        std::uint16_t iRightGainQ15;
        bool iActive;

        TVoice();
    };

    bool SetWave(TAssetId aAsset, const PcmWaveView& aWave);
    bool Play(TAssetId aAsset,
              std::uint16_t aVolumeQ15,
              std::int16_t aPanQ15);
    std::size_t FindVoiceSlot();
    static std::int32_t ReadSample(const PcmWaveView& aWave,
                                   std::size_t aFrame,
                                   std::uint16_t aChannel);
    static std::int32_t Interpolate(const PcmWaveView& aWave,
                                    std::uint64_t aPositionQ16,
                                    std::uint16_t aChannel);
    static std::int16_t ClipToPcm16(std::int64_t aSample);

    PcmWaveView iAssets[EAssetCount];
    TVoice iVoices[KMaxVoices];
    std::uint32_t iOutputRate;
    std::uint32_t iDroppedVoices;
};

} // namespace snddma
} // namespace quantum

#endif // QUANTUM_LEVEL41_SNDDMA_MIXER_H
