#include "../level41_snddma_port/quantum_snddma_mixer.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <vector>

namespace {

void Require(bool aCondition, const char* aMessage)
{
    if (!aCondition) {
        std::fprintf(stderr, "FAIL: %s\n", aMessage);
        std::exit(1);
    }
}

void PutLe16(std::vector<std::uint8_t>& aBytes, std::uint16_t aValue)
{
    aBytes.push_back(static_cast<std::uint8_t>(aValue & 0xffU));
    aBytes.push_back(static_cast<std::uint8_t>((aValue >> 8) & 0xffU));
}

void PutLe32(std::vector<std::uint8_t>& aBytes, std::uint32_t aValue)
{
    aBytes.push_back(static_cast<std::uint8_t>(aValue & 0xffU));
    aBytes.push_back(static_cast<std::uint8_t>((aValue >> 8) & 0xffU));
    aBytes.push_back(static_cast<std::uint8_t>((aValue >> 16) & 0xffU));
    aBytes.push_back(static_cast<std::uint8_t>((aValue >> 24) & 0xffU));
}

void PutId(std::vector<std::uint8_t>& aBytes, const char* aId)
{
    aBytes.push_back(static_cast<std::uint8_t>(aId[0]));
    aBytes.push_back(static_cast<std::uint8_t>(aId[1]));
    aBytes.push_back(static_cast<std::uint8_t>(aId[2]));
    aBytes.push_back(static_cast<std::uint8_t>(aId[3]));
}

std::vector<std::uint8_t> MakeWave(
    std::uint32_t aRate,
    std::uint16_t aChannels,
    const std::vector<std::int16_t>& aSamples,
    std::uint16_t aFormat = 1,
    bool aAddOddJunkChunk = false)
{
    const std::uint16_t bytesPerFrame =
        static_cast<std::uint16_t>(aChannels * 2U);
    std::vector<std::uint8_t> body;

    if (aAddOddJunkChunk) {
        PutId(body, "JUNK");
        PutLe32(body, 3);
        body.push_back(0x11);
        body.push_back(0x22);
        body.push_back(0x33);
        body.push_back(0);
    }

    PutId(body, "fmt ");
    PutLe32(body, 16);
    PutLe16(body, aFormat);
    PutLe16(body, aChannels);
    PutLe32(body, aRate);
    PutLe32(body, aRate * bytesPerFrame);
    PutLe16(body, bytesPerFrame);
    PutLe16(body, 16);

    PutId(body, "data");
    PutLe32(body, static_cast<std::uint32_t>(aSamples.size() * 2U));
    for (std::size_t i = 0; i < aSamples.size(); ++i) {
        PutLe16(body, static_cast<std::uint16_t>(aSamples[i]));
    }

    std::vector<std::uint8_t> result;
    PutId(result, "RIFF");
    PutLe32(result, static_cast<std::uint32_t>(body.size() + 4U));
    PutId(result, "WAVE");
    result.insert(result.end(), body.begin(), body.end());
    return result;
}

quantum::snddma::PcmWaveView Parse(
    const std::vector<std::uint8_t>& aBytes)
{
    quantum::snddma::PcmWaveView view;
    const quantum::snddma::WaveParseResult result =
        quantum::snddma::ParsePcm16Wave(
            aBytes.data(), aBytes.size(), view);
    Require(result == quantum::snddma::EWaveParseOk,
            "valid test WAV parses as PCM16");
    return view;
}

void TestRiffParser()
{
    const std::vector<std::int16_t> samples = {0, 1000, -1000, 32767};
    std::vector<std::uint8_t> wave = MakeWave(11025, 1, samples, 1, true);
    const quantum::snddma::PcmWaveView view = Parse(wave);
    Require(view.sampleRate == 11025, "sample rate is decoded");
    Require(view.channels == 1, "mono channel count is decoded");
    Require(view.frames == samples.size(), "frame count is decoded");

    quantum::snddma::PcmWaveView badView;
    Require(quantum::snddma::ParsePcm16Wave(
                wave.data(), 11, badView) ==
                quantum::snddma::EWaveParseTruncated,
            "truncated RIFF header is rejected");
    Require(quantum::snddma::ParsePcm16Wave(
                wave.data(), wave.size() - 1U, badView) ==
                quantum::snddma::EWaveParseTruncated,
            "truncated RIFF body is rejected");

    std::vector<std::uint8_t> floatWave = MakeWave(11025, 1, samples, 3);
    Require(quantum::snddma::ParsePcm16Wave(
                floatWave.data(), floatWave.size(), badView) ==
                quantum::snddma::EWaveParseUnsupportedPcm,
            "non-PCM WAV is rejected");
}

void TestMonoMixAndPan()
{
    const std::vector<std::int16_t> samples = {0, 1000, -1000, 32767};
    const std::vector<std::uint8_t> waveBytes = MakeWave(11025, 1, samples);
    const quantum::snddma::PcmWaveView wave = Parse(waveBytes);

    quantum::snddma::Mixer mixer(11025);
    Require(mixer.SetShotWave(wave), "shot asset is accepted");
    Require(mixer.PlayShot(32767, 0), "centered shot starts");
    Require(mixer.ActiveVoiceCount() == 1, "one voice is active");

    std::int16_t output[8] = {};
    Require(mixer.Mix(output, 4), "four output frames mix");
    for (std::size_t i = 0; i < samples.size(); ++i) {
        Require(output[i * 2] == samples[i],
                "mono sample is copied to left channel at unity gain");
        Require(output[i * 2 + 1] == samples[i],
                "mono sample is copied to right channel at unity gain");
    }
    Require(mixer.ActiveVoiceCount() == 0, "finished voice is retired");

    Require(mixer.PlayShot(32767, static_cast<std::int16_t>(-32768)),
            "hard-left shot starts");
    Require(mixer.Mix(output, 1), "hard-left frame mixes");
    Require(output[0] == 0 && output[1] == 0,
            "first source sample is silent");
    Require(mixer.Mix(output, 1), "hard-left nonzero frame mixes");
    Require(output[0] == 1000 && output[1] == 0,
            "hard-left pan suppresses the right channel");
}

void TestResampleOverlapAndClipping()
{
    const std::vector<std::int16_t> samples = {0, 1000, 2000};
    const std::vector<std::uint8_t> waveBytes = MakeWave(8000, 1, samples);
    const quantum::snddma::PcmWaveView wave = Parse(waveBytes);

    quantum::snddma::Mixer resampler(16000);
    Require(resampler.SetShotWave(wave), "resample asset is accepted");
    Require(resampler.PlayShot(32767, 0), "resampled voice starts");
    std::int16_t output[12] = {};
    Require(resampler.Mix(output, 6), "resampled frames mix");
    const std::int16_t expected[] = {0, 500, 1000, 1500, 2000, 2000};
    for (std::size_t i = 0; i < 6; ++i) {
        Require(output[i * 2] == expected[i] &&
                    output[i * 2 + 1] == expected[i],
                "linear 2x resampling has expected PCM samples");
    }

    const std::vector<std::int16_t> loudSamples(3, 25000);
    const std::vector<std::uint8_t> loudWaveBytes =
        MakeWave(11025, 1, loudSamples);
    const quantum::snddma::PcmWaveView loudWave = Parse(loudWaveBytes);
    quantum::snddma::Mixer mixer(11025);
    Require(mixer.SetShotWave(loudWave), "loud shot asset is accepted");
    Require(mixer.SetReloadWave(loudWave), "loud reload asset is accepted");
    Require(mixer.PlayShot(32767, 0), "first overlapping voice starts");
    Require(mixer.PlayReload(32767, 0), "second overlapping voice starts");
    Require(mixer.Mix(output, 1), "overlapped frame mixes");
    Require(output[0] == 32767 && output[1] == 32767,
            "mixed samples saturate rather than wrap");
}

void TestVoiceLimit()
{
    const std::vector<std::int16_t> samples(400, 500);
    const std::vector<std::uint8_t> waveBytes = MakeWave(11025, 1, samples);
    const quantum::snddma::PcmWaveView wave = Parse(waveBytes);
    quantum::snddma::Mixer mixer(11025);
    Require(mixer.SetShotWave(wave), "voice-limit asset is accepted");

    for (std::size_t i = 0; i < quantum::snddma::KMaxVoices; ++i) {
        Require(mixer.PlayShot(), "voice starts until fixed voice pool fills");
    }
    Require(mixer.ActiveVoiceCount() == quantum::snddma::KMaxVoices,
            "voice pool has fixed upper bound");
    Require(mixer.PlayShot(), "new voice replaces the shortest-lived channel");
    Require(mixer.ActiveVoiceCount() == quantum::snddma::KMaxVoices,
            "voice replacement does not exceed the pool");
    Require(mixer.DroppedVoiceCount() == 1,
            "replacement increments the dropped/replaced counter");
}

} // namespace

int main()
{
    TestRiffParser();
    TestMonoMixAndPan();
    TestResampleOverlapAndClipping();
    TestVoiceLimit();
    std::puts("SNDDMA-reference PCM parser/mixer tests passed.");
    return 0;
}
