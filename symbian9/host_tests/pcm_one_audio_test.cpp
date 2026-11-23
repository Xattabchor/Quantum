// Host test for the single-PCM effects path used by Level 41.
//
// It exercises the device-independent core in
// symbian9/level41_pcm_audio/quantum_level41_pcm_core.cpp: the WAV decoder,
// the 16 kHz -> 8 kHz fold-down and the one-voice admission rule. The Symbian
// MDA plumbing in quantum_level41_pcm_audio.cpp cannot run here.
//
// Build and run through symbian9/host_tests/run_pcm_one_audio_test.sh.

#include "../level41_pcm_audio/quantum_level41_pcm_core.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

namespace {

using quantum::level41pcm::CopyToStream;
using quantum::level41pcm::EWaveParseOk;
using quantum::level41pcm::EWaveParseTruncated;
using quantum::level41pcm::EWaveParseUnsupportedPcm;
using quantum::level41pcm::ParsePcm16Wave;
using quantum::level41pcm::PcmWaveView;
using quantum::level41pcm::StreamBytesFor;
using quantum::level41pcm::VoiceGate;

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

std::vector<std::uint8_t> SamplesToBytes(
    const std::vector<std::int16_t>& aSamples)
{
    std::vector<std::uint8_t> bytes;
    bytes.reserve(aSamples.size() * 2);
    for (const std::int16_t sample : aSamples) {
        PutLe16(bytes, static_cast<std::uint16_t>(sample));
    }
    return bytes;
}

// Builds a RIFF/WAVE file. aListChunk inserts a LIST chunk between fmt and
// data, which is what the packaged reload WAV actually contains.
std::vector<std::uint8_t> MakeWave(
    std::uint32_t aRate,
    std::uint16_t aChannels,
    std::uint16_t aBits,
    const std::vector<std::uint8_t>& aData,
    bool aInsertListChunk = false,
    bool aDeclaredDataTooLarge = false)
{
    std::vector<std::uint8_t> body;
    PutId(body, "WAVE");

    PutId(body, "fmt ");
    PutLe32(body, 16);
    PutLe16(body, 1); // PCM
    PutLe16(body, aChannels);
    PutLe32(body, aRate);
    PutLe32(body, aRate * aChannels * (aBits / 8U));
    PutLe16(body, static_cast<std::uint16_t>(aChannels * (aBits / 8U)));
    PutLe16(body, aBits);

    if (aInsertListChunk) {
        PutId(body, "LIST");
        const std::vector<std::uint8_t> listBody(12, 'x');
        PutLe32(body, static_cast<std::uint32_t>(listBody.size()));
        body.insert(body.end(), listBody.begin(), listBody.end());
    }

    PutId(body, "data");
    PutLe32(body, aDeclaredDataTooLarge
                      ? static_cast<std::uint32_t>(aData.size() + 64)
                      : static_cast<std::uint32_t>(aData.size()));
    body.insert(body.end(), aData.begin(), aData.end());

    std::vector<std::uint8_t> file;
    PutId(file, "RIFF");
    PutLe32(file, static_cast<std::uint32_t>(body.size()));
    file.insert(file.end(), body.begin(), body.end());
    return file;
}

void TestEightKilohertzCopiedVerbatim()
{
    const std::vector<std::int16_t> samples = {0, 1, -1, 300, -300, 32767,
                                               -32768};
    const std::vector<std::uint8_t> data = SamplesToBytes(samples);
    const std::vector<std::uint8_t> wave = MakeWave(8000, 1, 16, data);

    PcmWaveView view;
    Require(ParsePcm16Wave(wave.data(), wave.size(), view) == EWaveParseOk,
            "8 kHz mono PCM16 must parse");
    Require(view.channels == 1, "8 kHz view must report mono");
    Require(view.sampleRate == 8000, "8 kHz view must report its rate");
    Require(StreamBytesFor(view) == data.size(),
            "8 kHz stream size must equal the data size");

    std::vector<std::uint8_t> stream(StreamBytesFor(view));
    Require(CopyToStream(view, stream.data(), stream.size()),
            "8 kHz copy must succeed");
    Require(stream == data, "8 kHz samples must be copied unchanged");
}

void TestSixteenKilohertzFoldsDownToEight()
{
    // Pairs are averaged so the clip keeps its duration at 8 kHz.
    const std::vector<std::int16_t> samples = {
        0,     100,    // -> 50
        -32768, -32768, // -> -32768, must not wrap
        32767, 32767,  // -> 32767
        -1,    0,      // -> 0, truncation toward zero
    };
    const std::vector<std::uint8_t> data = SamplesToBytes(samples);
    std::vector<std::uint8_t> wave = MakeWave(16000, 1, 16, data,
                                              /*aInsertListChunk=*/true);

    PcmWaveView view;
    Require(ParsePcm16Wave(wave.data(), wave.size(), view) == EWaveParseOk,
            "16 kHz mono PCM16 behind a LIST chunk must parse");
    Require(view.sampleRate == 16000, "16 kHz view must report its rate");
    Require(StreamBytesFor(view) == data.size() / 2,
            "16 kHz stream size must be half the data size");

    std::vector<std::uint8_t> stream(StreamBytesFor(view));
    Require(CopyToStream(view, stream.data(), stream.size()),
            "16 kHz fold-down must succeed");
    const std::vector<std::uint8_t> expected =
        SamplesToBytes({50, -32768, 32767, 0});
    Require(stream == expected,
            "16 kHz fold-down must average pairs without wrapping");
}

void TestRejectsUnsupportedFormats()
{
    const std::vector<std::uint8_t> data = SamplesToBytes({0, 1, 2, 3});

    PcmWaveView view;
    std::vector<std::uint8_t> stereo = MakeWave(8000, 2, 16, data);
    Require(ParsePcm16Wave(stereo.data(), stereo.size(), view) ==
                EWaveParseUnsupportedPcm,
            "stereo input must be rejected; the stream opens mono");

    std::vector<std::uint8_t> eightBit = MakeWave(8000, 1, 8, data);
    Require(ParsePcm16Wave(eightBit.data(), eightBit.size(), view) ==
                EWaveParseUnsupportedPcm,
            "8-bit input must be rejected; the stream is PCM16");

    std::vector<std::uint8_t> elevenKilohertz = MakeWave(11025, 1, 16, data);
    Require(ParsePcm16Wave(elevenKilohertz.data(), elevenKilohertz.size(),
                           view) == EWaveParseUnsupportedPcm,
            "11.025 kHz input must be rejected; only 8/16 kHz are openable");
}

void TestRejectsMalformedContainers()
{
    const std::vector<std::uint8_t> data = SamplesToBytes({0, 1, 2, 3});
    PcmWaveView view;

    const std::vector<std::uint8_t> truncated =
        MakeWave(8000, 1, 16, data, false, /*aDeclaredDataTooLarge=*/true);
    Require(ParsePcm16Wave(truncated.data(), truncated.size(), view) ==
                EWaveParseTruncated,
            "a data chunk larger than the file must be rejected");

    std::vector<std::uint8_t> notRiff = MakeWave(8000, 1, 16, data);
    notRiff[0] = 'X';
    Require(ParsePcm16Wave(notRiff.data(), notRiff.size(), view) !=
                EWaveParseOk,
            "a non-RIFF container must be rejected");

    // An odd data size cannot be whole PCM16 frames.
    std::vector<std::uint8_t> odd = SamplesToBytes({0, 1, 2});
    odd.push_back(0x7f);
    std::vector<std::uint8_t> oddWave = MakeWave(8000, 1, 16, odd);
    Require(ParsePcm16Wave(oddWave.data(), oddWave.size(), view) !=
                EWaveParseOk,
            "an odd data size must be rejected");

    Require(ParsePcm16Wave(nullptr, 0, view) != EWaveParseOk,
            "a null buffer must be rejected");
}

void TestVoiceGateDropsWhileBusy()
{
    VoiceGate gate;
    Require(!gate.MaySubmit(/*ready=*/false, false, false, false, 0),
            "an unopened stream must refuse effects");
    Require(gate.MaySubmit(/*ready=*/true, /*inFlight=*/false,
                           /*queued=*/false, /*haveSubmitTime=*/false, 0),
            "a ready idle stream must accept the first effect");
    Require(!gate.MaySubmit(/*ready=*/true, /*inFlight=*/true, false, false,
                            0),
            "a request while a clip is in flight must be dropped");
    Require(!gate.MaySubmit(/*ready=*/true, /*inFlight=*/false,
                            /*queued=*/true, false, 0),
            "a request with a command still queued must be dropped");

    // A packaged shot is 6400 bytes at 16000 B/s: 400 ms.
    gate.RecordSubmit(6400);
    Require(gate.LastDurationUs() == 400000,
            "6400 bytes must be estimated at 400 ms at 8 kHz mono");
    Require(gate.HaveSubmitTime(), "a submission must be remembered");

    Require(!gate.MaySubmit(true, false, false, gate.HaveSubmitTime(),
                            399999),
            "an effect must not restart before the estimated duration");
    Require(gate.MaySubmit(true, false, false, gate.HaveSubmitTime(),
                           400000),
            "an effect may restart once the estimate has elapsed");

    gate.Reset();
    Require(!gate.HaveSubmitTime(), "reset must clear the submission time");
    Require(gate.MaySubmit(true, false, false, gate.HaveSubmitTime(), 0),
            "after reset the next effect is admitted immediately");
}

/*
 * The device-tested reference treats a backwards clock as "no estimate", not
 * as an early repeat: QuantumShotMaySubmit tests elapsedUs >= 0 before
 * comparing it against the duration. Restarting on a negative delta would
 * defeat the pacing entirely if the clock ever stepped.
 */
void TestNegativeElapsedIsNotAnEarlyRepeat()
{
    VoiceGate gate;
    gate.RecordSubmit(6400);
    Require(gate.MaySubmit(true, false, false, gate.HaveSubmitTime(), -1),
            "a negative elapsed must be treated as no estimate, not a repeat");
    Require(!gate.MaySubmit(true, false, false, gate.HaveSubmitTime(), 0),
            "a zero elapsed is still an early repeat");
}

/*
 * Sequence of a single trigger in the reference build:
 *   PlayShot -> poller -> HandleCommand -> SubmitL -> WriteL
 *   MaoscBufferCopied -> iInFlight = false
 *   poller tick -> SubmitL (queued work, if any)
 * MaoscOpenComplete and MaoscPlayComplete do not submit anything. The first
 * trigger of a level therefore waits for the next poller turn rather than
 * writing from inside the open callback.
 */
void TestTriggerWaitsForPollerNotForOpenCallback()
{
    VoiceGate gate;
    bool ready = false;
    bool inFlight = false;
    int queued = 0;

    // Trigger fired before the stream finished opening: dropped, because the
    // reference does not hold it.
    if (gate.MaySubmit(ready, inFlight, queued != 0, gate.HaveSubmitTime(),
                       0)) {
        ++queued;
    }
    Require(queued == 0,
            "a trigger before the stream is ready must be dropped");

    // MaoscOpenComplete sets ready only; submission happens from the poller.
    ready = true;
    Require(gate.MaySubmit(ready, inFlight, queued != 0,
                           gate.HaveSubmitTime(), 0),
            "the poller turn after open must be able to submit");

    // Submit: now queued and in flight.
    ++queued;
    inFlight = true;
    gate.RecordSubmit(6400);

    // MaoscPlayComplete does not clear the flight flag in the reference.
    Require(!gate.MaySubmit(ready, inFlight, queued != 0,
                            gate.HaveSubmitTime(), 500000),
            "play complete alone must not free the voice");

    // MaoscBufferCopied clears it, but the duration estimate still blocks.
    inFlight = false;
    queued = 0;
    Require(!gate.MaySubmit(ready, inFlight, queued != 0,
                            gate.HaveSubmitTime(), 100000),
            "BufferCopied alone must not allow an early retrigger");
    Require(gate.MaySubmit(ready, inFlight, queued != 0,
                           gate.HaveSubmitTime(), 400000),
            "once the clip's estimated end passes the voice is free again");
}

void TestRealFilesIfProvided(int aArgumentCount, char** aArguments)
{
    for (int index = 1; index < aArgumentCount; ++index) {
        std::FILE* file = std::fopen(aArguments[index], "rb");
        if (file == 0) {
            std::fprintf(stderr, "FAIL: cannot open %s\n", aArguments[index]);
            std::exit(1);
        }
        std::vector<std::uint8_t> bytes;
        std::uint8_t chunk[4096];
        std::size_t read = 0;
        while ((read = std::fread(chunk, 1, sizeof(chunk), file)) != 0) {
            bytes.insert(bytes.end(), chunk, chunk + read);
        }
        std::fclose(file);

        PcmWaveView view;
        Require(ParsePcm16Wave(bytes.data(), bytes.size(), view) ==
                    EWaveParseOk,
                "a packaged effect WAV must parse");
        std::vector<std::uint8_t> stream(StreamBytesFor(view));
        Require(CopyToStream(view, stream.data(), stream.size()),
                "a packaged effect WAV must convert");
        std::printf("  %s: %u Hz -> %zu stream bytes\n", aArguments[index],
                    static_cast<unsigned>(view.sampleRate), stream.size());
    }
}

} // namespace

int main(int aArgumentCount, char** aArguments)
{
    TestEightKilohertzCopiedVerbatim();
    TestSixteenKilohertzFoldsDownToEight();
    TestRejectsUnsupportedFormats();
    TestRejectsMalformedContainers();
    TestVoiceGateDropsWhileBusy();
    TestNegativeElapsedIsNotAnEarlyRepeat();
    TestTriggerWaitsForPollerNotForOpenCallback();
    TestRealFilesIfProvided(aArgumentCount, aArguments);
    std::printf("pcm_one_audio_test: all checks passed\n");
    return 0;
}
