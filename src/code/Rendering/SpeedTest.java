package code.Rendering;

import javax.microedition.m3g.CompositingMode;
import javax.microedition.m3g.Texture2D;

/**
 * M3G fill benchmark: draws a textured quad through the hardware pipe once per
 * fill state and measures wall-clock time for each. The states differ in what
 * the rasteriser actually does - opaque, decal, alpha cutout, cutout with fog,
 * modulate, additive - instead of stepping a mode number that never reached
 * the pipe (the old loop timed one state fourteen times).
 * <p>
 * Call {@link #runBenchmark(DirectX7, int)} from a native render pass
 * (e.g. DeveloperMenu hook) to get per-state timings in {@link #resultsMs}.
 */
public class SpeedTest {

    /** Number of quad draws per mode per benchmark run. */
    private static final int ITERATIONS_DEFAULT = 200;

    /** Test texture size (power of two, <= maxTextureDimension). */
    private static final int TEST_TEX_W = 64;
    private static final int TEST_TEX_H = 64;

    /** True if benchmark ran successfully. */
    public boolean ranOk = false;

    /** Last error message, if any. */
    public String lastError = null;

    // ------------------------------------------------------------------
    // Test data (lazily created on first run)
    // ------------------------------------------------------------------
    private Texture testTexture = null;
    private Vertex[] testVerts3 = null; // 3 vertices per triangle
    private Vertex[] testVerts4 = null; // 4 vertices per quad

    /** Create test texture + geometry once. */
    private void ensureTestData(DirectX7 g) {
        if(testTexture != null) return;

        // --- Test texture: 64x64 POT, checkerboard pattern ---
        int[] pixels = new int[TEST_TEX_W * TEST_TEX_H];
        for(int y = 0; y < TEST_TEX_H; y++) {
            for(int x = 0; x < TEST_TEX_W; x++) {
                int c = ((x >> 3) ^ (y >> 3)) & 1;
                pixels[y * TEST_TEX_W + x] = c != 0 ? 0xff00ff00 : 0xff0000ff;
            }
        }
        RawImage raw = new RawImage(pixels, TEST_TEX_W, TEST_TEX_H);
        testTexture = new Texture(raw, true, null);

        // --- Screen-space quad covering most of the viewport ---
        int w = g.width, h = g.height;
        int cx = g.centreX, cy = g.centreY;
        int q = w / 4; // quarter size

        // Triangle 1: top-left to bottom-right diagonal
        Vertex v1 = new Vertex(); v1.sx = cx - q; v1.sy = cy - q; v1.rz = -4000;
        Vertex v2 = new Vertex(); v2.sx = cx + q; v2.sy = cy - q; v2.rz = -4000;
        Vertex v3 = new Vertex(); v3.sx = cx + q; v3.sy = cy + q; v3.rz = -4000;
        testVerts3 = new Vertex[]{v1, v2, v3};

        // Quad: two triangles
        Vertex v4 = new Vertex(); v4.sx = cx - q; v4.sy = cy + q; v4.rz = -4000;
        testVerts4 = new Vertex[]{v1, v2, v3, v4};
    }

    /**
     * Fill states that actually differ on the pipe. Each entry is a distinct
     * combination of texture function, compositing mode and fog, which is what
     * a fill costs on a software rasteriser.
     */
    private static final int[][] STATES = {
        // blending,               function,                    fog
        { CompositingMode.REPLACE,        Texture2D.FUNC_MODULATE, 0 }, // opaque
        { CompositingMode.REPLACE,        Texture2D.FUNC_DECAL,    0 }, // decal
        { CompositingMode.ALPHA,          Texture2D.FUNC_MODULATE, 0 }, // cutout
        { CompositingMode.ALPHA,          Texture2D.FUNC_MODULATE, 1 }, // + fog
        { CompositingMode.MODULATE,       Texture2D.FUNC_MODULATE, 0 }, // modulate
        { CompositingMode.ALPHA_ADD,      Texture2D.FUNC_MODULATE, 0 }, // additive
    };

    private static final String[] STATE_NAMES = {
        "opaque", "decal", "cutout", "cutout+fog", "modulate", "add"
    };

    /** Per fill state, in milliseconds. Index matches STATES. */
    public long[] resultsMs = new long[STATES.length];

    /** Per fill state, completed draws. A state that throws stops counting. */
    public int[] resultsDraws = new int[STATES.length];

    /** Per fill state, the first error seen, or null. */
    public String[] resultsError = new String[STATES.length];

    /**
     * Run the fill benchmark. Each state is timed over `iterations` quads.
     * An exception ends that state's run and is recorded: a benchmark that
     * swallows every failure reports confident numbers about nothing.
     */
    public void runBenchmark(DirectX7 g, int iterations) {
        if(iterations <= 0) iterations = ITERATIONS_DEFAULT;
        ranOk = false;
        lastError = null;

        if(g.getSceneRenderer() == null || !(g.getSceneRenderer() instanceof M3GSceneRenderer)) {
            lastError = "no M3GSceneRenderer";
            return;
        }
        ensureTestData(g);
        if(testTexture == null) {
            lastError = "test texture creation failed";
            return;
        }

        M3GSceneRenderer r = (M3GSceneRenderer)g.getSceneRenderer();
        int okStates = 0;

        for(int s = 0; s < STATES.length; s++) {
            resultsMs[s] = 0;
            resultsDraws[s] = 0;
            resultsError[s] = null;
            int drawn = 0;
            long t0 = System.currentTimeMillis();
            try {
                for(int i = 0; i < iterations; i++) {
                    // Both triangles of the quad, so the cost is a whole quad.
                    r.paintM3GTriangle(g, testTexture,
                            testVerts3[0], 0, 0,
                            testVerts3[1], TEST_TEX_W, 0,
                            testVerts3[2], TEST_TEX_W, TEST_TEX_H,
                            STATES[s][0], STATES[s][1], STATES[s][2] != 0);
                    r.paintM3GTriangle(g, testTexture,
                            testVerts3[0], 0, 0,
                            testVerts3[2], TEST_TEX_W, TEST_TEX_H,
                            testVerts4[3], 0, TEST_TEX_H,
                            STATES[s][0], STATES[s][1], STATES[s][2] != 0);
                    drawn += 2;
                }
            } catch(Throwable ex) {
                resultsError[s] = ex.toString();
            }
            resultsMs[s] = System.currentTimeMillis() - t0;
            resultsDraws[s] = drawn;
            if(resultsError[s] == null && drawn == iterations * 2) okStates++;
        }

        ranOk = okStates > 0;
        if(!ranOk) lastError = "every fill state failed";
        else if(okStates < STATES.length) lastError = (STATES.length - okStates) + " state(s) failed";
    }

    /** Convenience: run with default iteration count. */
    public void runBenchmark(DirectX7 g) {
        runBenchmark(g, ITERATIONS_DEFAULT);
    }

    /** Format results as a single line for HUD overlay. */
    public String resultsLine() {
        if(lastError != null && !ranOk) return "SpeedTest: " + lastError;
        StringBuffer sb = new StringBuffer("fill ms:");
        for(int s = 0; s < STATES.length; s++) {
            sb.append(' ').append(STATE_NAMES[s]).append('=').append(resultsMs[s]);
            if(resultsError[s] != null) sb.append("(failed)");
        }
        if(lastError != null) sb.append(" [").append(lastError).append(']');
        return sb.toString();
    }

    /** Old CPU affine rasterizer (retained as reference, no callers). */
    /*
    public final static int fp = 12, FP = 1<<fp;
    public final static int fpPosition = 14, FPPosition = 1<<fpPosition;
    static final void paintAffine_0(DirectX7 g3d, Texture texture,
            Vertex a, int au, int av,
            Vertex b, int bu, int bv,
            Vertex c, int cu, int cv) { ... }
    */
}
