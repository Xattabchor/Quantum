#include <e32base.h>
#include <gdi.h>
#include <hal.h>
#include <math.h>
#include <w32std.h>

#include "math_utils.h"
#include "quantum_integer.h"

using code::Math::MathUtils;
using code::Math::QInt32;
using code::Math::javaDoubleToInt;
using code::Math::javaIntAbs;
using code::Math::javaIntAdd;
using code::Math::javaIntDiv;
using code::Math::javaIntMul;
using code::Math::javaShiftRight;

extern "C" TUint64 fastfp_ddiv(TUint64 numeratorBits,
                               TUint64 denominatorBits);
extern "C" TUint32 fastfp_ddiv_fallback_count(void);
extern "C" void fastfp_ddiv_reset_fallback_count(void);

namespace {

const TUint KWindowGroupClientHandle = 1;
const TUint KProbeWindowHandle = 2;
const TUint KLightCaseCount = 64;
const TUint KDivCaseCount = 64;
const TUint KCorrectnessRandomCases = 128;
const TUint KDivIterations = 100000;
const TUint KLightIterations = 8000;
const TUint KTimedRounds = 3;

enum TProbeStage {
    EStagePreparing,
    EStageRawCorrectness,
    EStageLightCorrectness,
    EStageWarmup,
    EStageDivisionTiming,
    EStageMicroTiming,
    EStageCalcTiming,
    EStageComplete
};

struct TLightCase {
    QInt32 nx;
    QInt32 ny;
    QInt32 nz;
    QInt32 sx;
    QInt32 sy;
    QInt32 sz;
};

struct TDivCase {
    TUint64 numeratorBits;
    TUint64 denominatorBits;
};

struct TRun {
    TUint32 ticks;
    TUint32 checksum;

    TRun() : ticks(0), checksum(0) {}
};

struct TProbeResults {
    TInt rawMismatches;
    TInt rawCases;
    TInt microMismatches;
    TInt calcMismatches;
    TInt frequency;
    TInt frequencyStatus;
    TProbeStage stage;
    TUint32 rawFallbacks;
    TUint32 divFallbacks;
    TUint32 microFallbacks;
    TUint32 calcFallbacks;
    TRun systemDiv[KTimedRounds];
    TRun fastDiv[KTimedRounds];
    TRun systemMicro[KTimedRounds];
    TRun fastMicro[KTimedRounds];
    TRun systemCalc[KTimedRounds];
    TRun fastCalc[KTimedRounds];

    TProbeResults()
        : rawMismatches(0), rawCases(0), microMismatches(0),
          calcMismatches(0), frequency(0), frequencyStatus(KErrNotReady),
          stage(EStagePreparing), rawFallbacks(0), divFallbacks(0),
          microFallbacks(0), calcFallbacks(0)
    {
    }
};

TLightCase gLightCases[KLightCaseCount];
TDivCase gDivCases[KDivCaseCount];

static TUint64 doubleBits(double value)
{
    union {
        double real;
        TUint64 bits;
    } bits;
    bits.real = value;
    return bits.bits;
}

static double bitsDouble(TUint64 value)
{
    union {
        double real;
        TUint64 bits;
    } bits;
    bits.bits = value;
    return bits.real;
}

static inline __attribute__((always_inline)) TUint64
systemDdivBits(TUint64 numeratorBits, TUint64 denominatorBits)
{
    const double numerator = bitsDouble(numeratorBits);
    const double denominator = bitsDouble(denominatorBits);
    return doubleBits(numerator / denominator);
}

static inline __attribute__((always_inline)) double
fastfpDdiv(double numerator, double denominator)
{
    return bitsDouble(fastfp_ddiv(doubleBits(numerator),
                                  doubleBits(denominator)));
}

static TUint32 nextRandom(TUint32& state)
{
    state = state * 1664525u + 1013904223u;
    return state;
}

static QInt32 randomSigned(TUint32& state, QInt32 radius)
{
    const TUint32 width = static_cast<TUint32>(radius * 2 + 1);
    return static_cast<QInt32>(nextRandom(state) % width) - radius;
}

static void reduceMicroLightVector(QInt32& x, QInt32& y, QInt32& z)
{
    while (x > 30000 || y > 30000 || z > 30000 ||
           x < -30000 || y < -30000 || z < -30000) {
        x = javaShiftRight(x, 1);
        y = javaShiftRight(y, 1);
        z = javaShiftRight(z, 1);
    }
}

static void reduceLightVector(QInt32& x, QInt32& y, QInt32& z)
{
    while (javaIntAbs(x) > 30000 || javaIntAbs(y) > 30000 ||
           javaIntAbs(z) > 30000) {
        x = javaIntDiv(x, 2);
        y = javaIntDiv(y, 2);
        z = javaIntDiv(z, 2);
    }
}

static double lightVectorLength(QInt32 x, QInt32 y, QInt32 z)
{
    QInt32 squared = javaIntMul(x, x);
    squared = javaIntAdd(squared, javaIntMul(y, y));
    squared = javaIntAdd(squared, javaIntMul(z, z));
    return ::sqrt(static_cast<double>(squared));
}

static __attribute__((noinline)) QInt32
fastMicroCalcLight(QInt32 nx, QInt32 ny, QInt32 nz,
                   QInt32 sx, QInt32 sy, QInt32 sz)
{
    reduceMicroLightVector(sx, sy, sz);
    double lightLength = lightVectorLength(sx, sy, sz);
    if (lightLength < 1.0) lightLength = 1.0;

    QInt32 dot = javaIntMul(nx, sx);
    dot = javaIntAdd(dot, javaIntMul(ny, sy));
    dot = javaIntAdd(dot, javaIntMul(nz, sz));
    const double denominator = lightLength * 4096.0;
    const double value = fastfpDdiv(static_cast<double>(dot), denominator) *
                         255.0;
    const QInt32 result = javaDoubleToInt(value);
    return result > 255 ? 255 : result;
}

static __attribute__((noinline)) QInt32
fastCalcLight(QInt32 nx, QInt32 ny, QInt32 nz,
              QInt32 sx, QInt32 sy, QInt32 sz)
{
    reduceLightVector(sx, sy, sz);
    reduceLightVector(nx, ny, nz);

    double normalLength = lightVectorLength(nx, ny, nz);
    double lightLength = lightVectorLength(sx, sy, sz);
    if (normalLength < 1.0) normalLength = 1.0;
    if (lightLength < 1.0) lightLength = 1.0;

    QInt32 dot = javaIntMul(nx, sx);
    dot = javaIntAdd(dot, javaIntMul(ny, sy));
    dot = javaIntAdd(dot, javaIntMul(nz, sz));
    const double denominator = normalLength * lightLength;
    const double cosine = fastfpDdiv(static_cast<double>(dot), denominator);
    QInt32 result = javaDoubleToInt(cosine * 256.0);
    if (result < 0) result = 0;
    if (result > 255) result = 255;
    return result;
}

static void prepareInputs()
{
    TUint32 state = 0x671096c5u;
    for (TUint i = 0; i < KLightCaseCount; ++i) {
        TLightCase& item = gLightCases[i];
        item.nx = 1024 + static_cast<QInt32>(nextRandom(state) % 4096u);
        item.ny = randomSigned(state, 3072);
        item.nz = 512 + static_cast<QInt32>(nextRandom(state) % 5120u);
        item.sx = 8000 + static_cast<QInt32>(nextRandom(state) % 16000u);
        item.sy = randomSigned(state, 12000);
        item.sz = 4000 + static_cast<QInt32>(nextRandom(state) % 18000u);
        if (i & 1u) {
            item.sx = -item.sx;
            item.sz = -item.sz;
        }
        if ((i & 7u) == 0) {
            item.sx = item.sx < 0 ? -32001 : 32001;
        }

        QInt32 sx = item.sx;
        QInt32 sy = item.sy;
        QInt32 sz = item.sz;
        reduceMicroLightVector(sx, sy, sz);
        QInt32 dot = javaIntMul(item.nx, sx);
        dot = javaIntAdd(dot, javaIntMul(item.ny, sy));
        dot = javaIntAdd(dot, javaIntMul(item.nz, sz));
        if (dot == 0) dot = (i & 1u) ? -1 : 1;

        const double denominator = lightVectorLength(sx, sy, sz) * 4096.0;
        gDivCases[i].numeratorBits = doubleBits(static_cast<double>(dot));
        gDivCases[i].denominatorBits = doubleBits(denominator);
    }
}

static TUint64 normalBits(TUint32& state, TBool negative)
{
    const TUint32 exponent = 256u + nextRandom(state) % 1500u;
    const TUint64 fraction =
        ((static_cast<TUint64>(nextRandom(state)) << 20) |
         (static_cast<TUint64>(nextRandom(state)) & 0xFFFFFu)) &
        0x000FFFFFFFFFFFFFULL;
    const TUint64 sign = negative ? 0x8000000000000000ULL : 0;
    return sign | (static_cast<TUint64>(exponent) << 52) | fraction;
}

static void checkRawDdiv(TProbeResults& results)
{
    TUint32 state = 0x31415926u;
    fastfp_ddiv_reset_fallback_count();

    for (TUint i = 0; i < KDivCaseCount; ++i) {
        const TUint64 expected = systemDdivBits(
            gDivCases[i].numeratorBits, gDivCases[i].denominatorBits);
        const TUint64 actual = fastfp_ddiv(
            gDivCases[i].numeratorBits, gDivCases[i].denominatorBits);
        ++results.rawCases;
        if (expected != actual) ++results.rawMismatches;
    }

    for (TUint i = 0; i < KCorrectnessRandomCases; ++i) {
        const TUint64 numerator = normalBits(state, (i & 1u) != 0);
        const TUint64 denominator = normalBits(state, (i & 2u) != 0);
        const TUint64 expected = systemDdivBits(numerator, denominator);
        const TUint64 actual = fastfp_ddiv(numerator, denominator);
        ++results.rawCases;
        if (expected != actual) ++results.rawMismatches;
    }

    const TUint64 one = 0x3FF0000000000000ULL;
    const TUint64 negativeOne = 0xBFF0000000000000ULL;
    const TUint64 zero = 0;
    const TUint64 minNormal = 0x0010000000000000ULL;
    const TUint64 minSubnormal = 1;
    const TUint64 maxFinite = 0x7FEFFFFFFFFFFFFFULL;
    const TUint64 infinity = 0x7FF0000000000000ULL;
    const TUint64 nan = 0x7FF8000000000001ULL;
    const TUint64 edgeCases[][2] = {
        {zero, one}, {one, zero}, {zero, zero},
        {negativeOne, one}, {one, negativeOne},
        {infinity, one}, {one, infinity}, {infinity, infinity},
        {nan, one}, {one, nan},
        {minNormal, one}, {minSubnormal, one},
        {one, minSubnormal}, {maxFinite, minNormal}
    };
    for (TUint i = 0; i < sizeof(edgeCases) / sizeof(edgeCases[0]); ++i) {
        const TUint64 expected = systemDdivBits(edgeCases[i][0], edgeCases[i][1]);
        const TUint64 actual = fastfp_ddiv(edgeCases[i][0], edgeCases[i][1]);
        ++results.rawCases;
        if (expected != actual) ++results.rawMismatches;
    }
    results.rawFallbacks = fastfp_ddiv_fallback_count();
}

static void checkLighting(TProbeResults& results)
{
    fastfp_ddiv_reset_fallback_count();
    for (TUint i = 0; i < KLightCaseCount; ++i) {
        const TLightCase& item = gLightCases[i];
        const QInt32 expected = MathUtils::microCalcLight(
            item.nx, item.ny, item.nz, item.sx, item.sy, item.sz);
        const QInt32 actual = fastMicroCalcLight(
            item.nx, item.ny, item.nz, item.sx, item.sy, item.sz);
        if (expected != actual) ++results.microMismatches;
    }
    results.microFallbacks = fastfp_ddiv_fallback_count();

    fastfp_ddiv_reset_fallback_count();
    for (TUint i = 0; i < KLightCaseCount; ++i) {
        const TLightCase& item = gLightCases[i];
        const QInt32 expected = MathUtils::calcLight(
            item.nx, item.ny, item.nz, item.sx, item.sy, item.sz);
        const QInt32 actual = fastCalcLight(
            item.nx, item.ny, item.nz, item.sx, item.sy, item.sz);
        if (expected != actual) ++results.calcMismatches;
    }
    results.calcFallbacks = fastfp_ddiv_fallback_count();
    fastfp_ddiv_reset_fallback_count();
}

static TRun measureSystemDiv(TUint32 iterations)
{
    TRun run;
    TUint32 checksum = 0;
    TUint32 index = 0;
    const TUint32 start = User::FastCounter();
    for (TUint32 i = 0; i < iterations; ++i) {
        const TDivCase& item = gDivCases[index];
        const TUint64 result = systemDdivBits(item.numeratorBits,
                                               item.denominatorBits);
        checksum += static_cast<TUint32>(result) ^
                    static_cast<TUint32>(result >> 32);
        index = (index + 1u) & (KDivCaseCount - 1u);
    }
    run.ticks = User::FastCounter() - start;
    run.checksum = checksum;
    return run;
}

static TRun measureFastDiv(TUint32 iterations)
{
    TRun run;
    TUint32 checksum = 0;
    TUint32 index = 0;
    const TUint32 start = User::FastCounter();
    for (TUint32 i = 0; i < iterations; ++i) {
        const TDivCase& item = gDivCases[index];
        const TUint64 result = fastfp_ddiv(item.numeratorBits,
                                            item.denominatorBits);
        checksum += static_cast<TUint32>(result) ^
                    static_cast<TUint32>(result >> 32);
        index = (index + 1u) & (KDivCaseCount - 1u);
    }
    run.ticks = User::FastCounter() - start;
    run.checksum = checksum;
    return run;
}

static TRun measureSystemMicro(TUint32 iterations)
{
    TRun run;
    TUint32 checksum = 0;
    TUint32 index = 0;
    const TUint32 start = User::FastCounter();
    for (TUint32 i = 0; i < iterations; ++i) {
        const TLightCase& item = gLightCases[index];
        checksum += static_cast<TUint32>(MathUtils::microCalcLight(
            item.nx, item.ny, item.nz, item.sx, item.sy, item.sz));
        index = (index + 1u) & (KLightCaseCount - 1u);
    }
    run.ticks = User::FastCounter() - start;
    run.checksum = checksum;
    return run;
}

static TRun measureFastMicro(TUint32 iterations)
{
    TRun run;
    TUint32 checksum = 0;
    TUint32 index = 0;
    const TUint32 start = User::FastCounter();
    for (TUint32 i = 0; i < iterations; ++i) {
        const TLightCase& item = gLightCases[index];
        checksum += static_cast<TUint32>(fastMicroCalcLight(
            item.nx, item.ny, item.nz, item.sx, item.sy, item.sz));
        index = (index + 1u) & (KLightCaseCount - 1u);
    }
    run.ticks = User::FastCounter() - start;
    run.checksum = checksum;
    return run;
}

static TRun measureSystemCalc(TUint32 iterations)
{
    TRun run;
    TUint32 checksum = 0;
    TUint32 index = 0;
    const TUint32 start = User::FastCounter();
    for (TUint32 i = 0; i < iterations; ++i) {
        const TLightCase& item = gLightCases[index];
        checksum += static_cast<TUint32>(MathUtils::calcLight(
            item.nx, item.ny, item.nz, item.sx, item.sy, item.sz));
        index = (index + 1u) & (KLightCaseCount - 1u);
    }
    run.ticks = User::FastCounter() - start;
    run.checksum = checksum;
    return run;
}

static TRun measureFastCalc(TUint32 iterations)
{
    TRun run;
    TUint32 checksum = 0;
    TUint32 index = 0;
    const TUint32 start = User::FastCounter();
    for (TUint32 i = 0; i < iterations; ++i) {
        const TLightCase& item = gLightCases[index];
        checksum += static_cast<TUint32>(fastCalcLight(
            item.nx, item.ny, item.nz, item.sx, item.sy, item.sz));
        index = (index + 1u) & (KLightCaseCount - 1u);
    }
    run.ticks = User::FastCounter() - start;
    run.checksum = checksum;
    return run;
}

static TUint32 medianTicks(const TRun runs[KTimedRounds])
{
    TUint32 a = runs[0].ticks;
    TUint32 b = runs[1].ticks;
    TUint32 c = runs[2].ticks;
    if (a > b) { const TUint32 t = a; a = b; b = t; }
    if (b > c) { const TUint32 t = b; b = c; c = t; }
    if (a > b) { const TUint32 t = a; a = b; b = t; }
    return b;
}

static TBool checksumsMatch(const TRun systemRuns[KTimedRounds],
                            const TRun fastRuns[KTimedRounds])
{
    for (TUint i = 0; i < KTimedRounds; ++i) {
        if (systemRuns[i].checksum != fastRuns[i].checksum) return EFalse;
    }
    return ETrue;
}

static void warmUpBenchmarks()
{
    (void)measureSystemDiv(1024);
    (void)measureFastDiv(1024);
    (void)measureSystemMicro(128);
    (void)measureFastMicro(128);
    (void)measureSystemCalc(128);
    (void)measureFastCalc(128);
}

static void runDivisionBenchmark(TProbeResults& results)
{
    fastfp_ddiv_reset_fallback_count();
    for (TUint i = 0; i < KTimedRounds; ++i) {
        if (i & 1u) {
            results.fastDiv[i] = measureFastDiv(KDivIterations);
            results.systemDiv[i] = measureSystemDiv(KDivIterations);
        } else {
            results.systemDiv[i] = measureSystemDiv(KDivIterations);
            results.fastDiv[i] = measureFastDiv(KDivIterations);
        }
    }
    results.divFallbacks = fastfp_ddiv_fallback_count();
}

static void runMicroBenchmark(TProbeResults& results)
{
    fastfp_ddiv_reset_fallback_count();
    for (TUint i = 0; i < KTimedRounds; ++i) {
        if (i & 1u) {
            results.fastMicro[i] = measureFastMicro(KLightIterations);
            results.systemMicro[i] = measureSystemMicro(KLightIterations);
        } else {
            results.systemMicro[i] = measureSystemMicro(KLightIterations);
            results.fastMicro[i] = measureFastMicro(KLightIterations);
        }
    }
    results.microFallbacks = fastfp_ddiv_fallback_count();
}

static void runCalcBenchmark(TProbeResults& results)
{
    fastfp_ddiv_reset_fallback_count();
    for (TUint i = 0; i < KTimedRounds; ++i) {
        if (i & 1u) {
            results.fastCalc[i] = measureFastCalc(KLightIterations);
            results.systemCalc[i] = measureSystemCalc(KLightIterations);
        } else {
            results.systemCalc[i] = measureSystemCalc(KLightIterations);
            results.fastCalc[i] = measureFastCalc(KLightIterations);
        }
    }
    results.calcFallbacks = fastfp_ddiv_fallback_count();
}

static void readCounterFrequency(TProbeResults& results)
{
    results.frequencyStatus = HAL::Get(HALData::EFastCounterFrequency,
                                        results.frequency);
    if (results.frequencyStatus != KErrNone || results.frequency <= 0) {
        results.frequency = 0;
    }
}

static TUint32 ratioTenths(TUint32 systemTicks, TUint32 fastTicks)
{
    if (fastTicks == 0) return 0;
    const TUint64 scaled = static_cast<TUint64>(systemTicks) * 10u +
                           fastTicks / 2u;
    return static_cast<TUint32>(scaled / fastTicks);
}

static TInt displayTime(TUint32 ticks, TInt frequency)
{
    if (frequency <= 0) {
        return ticks > 0x7FFFFFFFu ? 0x7FFFFFFF : static_cast<TInt>(ticks);
    }
    const TUint64 milliseconds =
        (static_cast<TUint64>(ticks) * 1000u +
         static_cast<TUint32>(frequency) / 2u) /
        static_cast<TUint32>(frequency);
    return milliseconds > 0x7FFFFFFFu
        ? 0x7FFFFFFF : static_cast<TInt>(milliseconds);
}

static void drawBenchLine(CWindowGc& gc, const TProbeResults& results,
                          const TDesC& label, TUint32 systemTicks,
                          TUint32 fastTicks, TInt y)
{
    TBuf<96> line;
    const TUint32 ratio = ratioTenths(systemTicks, fastTicks);
    if (results.frequency > 0) {
        line.Format(_L("%S %d/%d ms x%d.%d"), &label,
                    displayTime(systemTicks, results.frequency),
                    displayTime(fastTicks, results.frequency),
                    static_cast<TInt>(ratio / 10u),
                    static_cast<TInt>(ratio % 10u));
    } else {
        line.Format(_L("%S %d/%d ticks x%d.%d"), &label,
                    displayTime(systemTicks, 0), displayTime(fastTicks, 0),
                    static_cast<TInt>(ratio / 10u),
                    static_cast<TInt>(ratio % 10u));
    }
    gc.DrawText(line, TPoint(8, y));
}

static void drawResults(CWsScreenDevice& screen, CWindowGc& gc,
                        const TSize& size, const TProbeResults& results)
{
    gc.SetBrushStyle(CGraphicsContext::ESolidBrush);
    gc.SetPenStyle(CGraphicsContext::ENullPen);
    gc.SetBrushColor(TRgb(0, 56, 92));
    gc.DrawRect(TRect(TPoint(0, 0), size));

    gc.SetPenStyle(CGraphicsContext::ESolidPen);
    gc.SetPenColor(TRgb(255, 255, 255));
    CFont* font = 0;
    if (screen.GetNearestFontInPixels(
            font, TFontSpec(_L("Series 60 Sans"), 12)) != KErrNone) {
        return;
    }
    gc.UseFont(font);

    TBuf<96> line;
    if (results.stage != EStageComplete) {
        gc.DrawText(_L("FASTFP A/B - RUNNING"), TPoint(8, 22));
        switch (results.stage) {
        case EStagePreparing:
            gc.DrawText(_L("Preparing deterministic inputs"),
                        TPoint(8, 56));
            break;
        case EStageRawCorrectness:
            gc.DrawText(_L("Checking raw ddiv result bits"),
                        TPoint(8, 56));
            break;
        case EStageLightCorrectness:
            gc.DrawText(_L("Checking lighting outputs"),
                        TPoint(8, 56));
            break;
        case EStageWarmup:
            gc.DrawText(_L("Warming up both code paths"),
                        TPoint(8, 56));
            break;
        case EStageDivisionTiming:
            gc.DrawText(_L("Timing raw division: 3 rounds"),
                        TPoint(8, 56));
            break;
        case EStageMicroTiming:
            gc.DrawText(_L("Timing microCalcLight: 3 rounds"),
                        TPoint(8, 56));
            break;
        case EStageCalcTiming:
            gc.DrawText(_L("Timing calcLight: 3 rounds"),
                        TPoint(8, 56));
            break;
        case EStageComplete:
            break;
        }
        line.Format(_L("Phase %d of 7"),
                    static_cast<TInt>(results.stage) + 1);
        gc.DrawText(line, TPoint(8, 88));
        gc.DrawText(_L("Please wait; results appear after each phase."),
                    TPoint(8, 120));
        gc.DrawText(_L("If it stops here, report this phase."),
                    TPoint(8, 144));
        gc.DiscardFont();
        screen.ReleaseFont(font);
        return;
    }

    gc.DrawText(_L("FASTFP ddiv A/B - target probe"), TPoint(8, 22));
    line.Format(_L("Mismatch raw %d/%d; micro %d; calc %d"),
                results.rawMismatches, results.rawCases,
                results.microMismatches, results.calcMismatches);
    gc.DrawText(line, TPoint(8, 46));

    drawBenchLine(gc, results, _L("ddiv"),
                  medianTicks(results.systemDiv),
                  medianTicks(results.fastDiv), 70);
    drawBenchLine(gc, results, _L("micro light"),
                  medianTicks(results.systemMicro),
                  medianTicks(results.fastMicro), 94);
    drawBenchLine(gc, results, _L("calc light"),
                  medianTicks(results.systemCalc),
                  medianTicks(results.fastCalc), 118);

    line.Format(_L("Fallback raw/d/m/c %d/%d/%d/%d"),
                static_cast<TInt>(results.rawFallbacks),
                static_cast<TInt>(results.divFallbacks),
                static_cast<TInt>(results.microFallbacks),
                static_cast<TInt>(results.calcFallbacks));
    gc.DrawText(line, TPoint(8, 142));

    line.Format(_L("Checksums d/m/c %d/%d/%d"),
                checksumsMatch(results.systemDiv, results.fastDiv),
                checksumsMatch(results.systemMicro, results.fastMicro),
                checksumsMatch(results.systemCalc, results.fastCalc));
    gc.DrawText(line, TPoint(8, 166));

    if (results.frequency > 0) {
        line.Format(_L("FastCounter: %d Hz; 3 alternating rounds"),
                    results.frequency);
    } else {
        line.Format(_L("Counter Hz unavailable; 3 alternating rounds"));
    }
    gc.DrawText(line, TPoint(8, 190));
    line.Format(_L("Iterations: ddiv %d, light %d per round"),
                static_cast<TInt>(KDivIterations),
                static_cast<TInt>(KLightIterations));
    gc.DrawText(line, TPoint(8, 214));
    gc.DrawText(_L("Press any key to exit"),
                TPoint(8, size.iHeight - 10));

    gc.DiscardFont();
    screen.ReleaseFont(font);
}

static TInt waitForKey(RWsSession& session, TUint groupClientHandle)
{
    TRequestStatus eventStatus;
    session.EventReady(&eventStatus);
    for (;;) {
        User::WaitForRequest(eventStatus);
        const TInt result = eventStatus.Int();
        if (result != KErrNone) return result;
        TWsEvent event;
        session.GetEvent(event);
        if (event.Handle() == groupClientHandle && event.Type() == EEventKey) {
            return KErrNone;
        }
        session.EventReady(&eventStatus);
    }
}

static TInt redrawResults(RWsSession& session, CWsScreenDevice& screen,
                          RWindow& window, const TSize& size,
                          const TProbeResults& results)
{
    TRequestStatus redrawStatus;
    session.RedrawReady(&redrawStatus);
    window.Invalidate();
    session.Flush();
    User::WaitForRequest(redrawStatus);
    TInt result = redrawStatus.Int();
    if (result == KErrNone) {
        TWsRedrawEvent redraw;
        session.GetRedraw(redraw);
        if (redraw.Handle() == KProbeWindowHandle) {
            window.BeginRedraw(redraw.Rect());
            CWindowGc gc(&screen);
            result = gc.Construct();
            if (result == KErrNone) {
                gc.Activate(window);
                drawResults(screen, gc, size, results);
                gc.Deactivate();
                window.EndRedraw();
            }
        }
    }
    session.RedrawReadyCancel();
    session.Flush();
    return result;
}

static TInt showStage(RWsSession& session, CWsScreenDevice& screen,
                      RWindow& window, const TSize& size,
                      TProbeResults& results, TProbeStage stage)
{
    results.stage = stage;
    return redrawResults(session, screen, window, size, results);
}

} // namespace

int main()
{
    TProbeResults results;
    RWsSession session;
    TInt result = session.Connect();
    if (result != KErrNone) return result;

    CWsScreenDevice screen(session);
    result = screen.Construct();
    if (result != KErrNone) {
        session.Close();
        return result;
    }

    const TSize size = screen.SizeInPixels();
    if (size.iWidth <= 0 || size.iHeight <= 0) {
        session.Close();
        return KErrArgument;
    }

    RWindowGroup group(session);
    result = group.Construct(KWindowGroupClientHandle, ETrue);
    if (result != KErrNone) {
        session.Close();
        return result;
    }

    RWindow window(session);
    result = window.Construct(group, KProbeWindowHandle);
    if (result != KErrNone) {
        group.Close();
        session.Close();
        return result;
    }

    group.SetOrdinalPosition(0);
    window.SetExtent(TPoint(0, 0), size);
    window.SetVisible(ETrue);
    window.Activate();

    result = showStage(session, screen, window, size, results,
                       EStagePreparing);
    if (result == KErrNone) prepareInputs();

    if (result == KErrNone) {
        result = showStage(session, screen, window, size, results,
                           EStageRawCorrectness);
        if (result == KErrNone) checkRawDdiv(results);
    }
    if (result == KErrNone) {
        result = showStage(session, screen, window, size, results,
                           EStageLightCorrectness);
        if (result == KErrNone) checkLighting(results);
    }
    if (result == KErrNone) {
        result = showStage(session, screen, window, size, results,
                           EStageWarmup);
        if (result == KErrNone) warmUpBenchmarks();
    }
    if (result == KErrNone) {
        result = showStage(session, screen, window, size, results,
                           EStageDivisionTiming);
        if (result == KErrNone) runDivisionBenchmark(results);
    }
    if (result == KErrNone) {
        result = showStage(session, screen, window, size, results,
                           EStageMicroTiming);
        if (result == KErrNone) runMicroBenchmark(results);
    }
    if (result == KErrNone) {
        result = showStage(session, screen, window, size, results,
                           EStageCalcTiming);
        if (result == KErrNone) runCalcBenchmark(results);
    }
    if (result == KErrNone) {
        readCounterFrequency(results);
        result = showStage(session, screen, window, size, results,
                           EStageComplete);
    }
    if (result == KErrNone) {
        result = waitForKey(session, KWindowGroupClientHandle);
    }

    window.Close();
    group.Close();
    session.Flush();
    session.Close();
    return result;
}
