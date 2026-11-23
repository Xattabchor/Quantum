#ifndef QUANTUM_LEVEL41_PCM_CORE_H
#define QUANTUM_LEVEL41_PCM_CORE_H

#include <cstddef>
#include <cstdint>

/*
 * Device-independent half of the single-PCM effects path.
 *
 * This header is intentionally free of Symbian headers so the same decoding
 * and voice-admission rules that run on the phone can be exercised by the
 * host tests in symbian9/host_tests. The Symbian wrapper in
 * quantum_level41_pcm_audio.cpp only does the MDA plumbing around it.
 */
namespace quantum {
namespace level41pcm {

static const std::uint32_t KStreamRate = 8000;
static const std::uint32_t KStreamBytesPerSecond = 16000;

enum WaveParseResult {
    EWaveParseOk = 0,
    EWaveParseBadArgument,
    EWaveParseBadContainer,
    EWaveParseTruncated,
    EWaveParseUnsupportedPcm
};

struct PcmWaveView {
    const std::uint8_t* samples;
    std::size_t sampleBytes;
    std::uint32_t sampleRate;
    std::uint16_t channels;

    PcmWaveView();
    bool IsValid() const;
};

// Parses a RIFF/WAVE PCM16 file without allocating. Only mono 8000 Hz or
// 16000 Hz input is accepted, because that is what the stream is opened with
// on the device.
WaveParseResult ParsePcm16Wave(const std::uint8_t* aWaveBytes,
                               std::size_t aWaveSize,
                               PcmWaveView& aView);

// Bytes the 8 kHz mono stream needs for aView: 16 kHz input is halved.
std::size_t StreamBytesFor(const PcmWaveView& aView);

// Converts aView into aDestination, which must hold StreamBytesFor(aView)
// bytes. 8 kHz data is copied unchanged; 16 kHz frames are averaged pairwise
// so the clip keeps its duration.
bool CopyToStream(const PcmWaveView& aView, std::uint8_t* aDestination,
                  std::size_t aDestinationBytes);

// Admission rule for the single voice. There is no queue and no mixing: an
// effect requested while the stream is busy is dropped, not deferred.
class VoiceGate {
public:
    VoiceGate();

    // aElapsedUs is the time since the last accepted submission.
    bool MaySubmit(bool aReady, bool aInFlight, bool aHaveSubmitTime,
                   std::int64_t aElapsedUs) const;
    void RecordSubmit(std::size_t aByteCount);

    std::int64_t LastDurationUs() const { return iLastDurationUs; }
    bool HaveSubmitTime() const { return iHaveSubmitTime; }
    void Reset();

private:
    std::int64_t iLastDurationUs;
    bool iHaveSubmitTime;
};

} // namespace level41pcm
} // namespace quantum

#endif // QUANTUM_LEVEL41_PCM_CORE_H
