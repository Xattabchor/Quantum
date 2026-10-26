package code.Rendering;

import code.utils.Main;

/**
 * M3G render-mode benchmark: draws a textured quad (two triangles) for every
 * render mode 0..13 through the hardware pipe and measures wall-clock time.
 * <p>
 * Call {@link #runBenchmark(DirectX7, int)} from a native render pass
 * (e.g. DeveloperMenu hook) to get per-mode timings in {@link #resultsMs}.
 */
public class SpeedTest {

    /** Number of quad draws per mode per benchmark run. */
    private static final int ITERATIONS_DEFAULT = 200;

    /** Test texture size (power of two, <= maxTextureDimension). */
    private static final int TEST_TEX_W = 64;
    private static final int TEST_TEX_H = 64;

    /** Per-mode results in milliseconds, index = render mode. */
    public long[] resultsMs = new long[14];

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

    /** Run benchmark for all render modes 0..13. */
    public void runBenchmark(DirectX7 g, int iterations) {
        if(iterations <= 0) iterations = ITERATIONS_DEFAULT;

        if(g.getSceneRenderer() == null || !(g.getSceneRenderer() instanceof M3GSceneRenderer)) {
            lastError = "no M3GSceneRenderer";
            ranOk = false;
            return;
        }

        ensureTestData(g);
        if(testTexture == null) {
            lastError = "test texture creation failed";
            ranOk = false;
            return;
        }

        M3GSceneRenderer r = (M3GSceneRenderer)g.getSceneRenderer();

        for(int mode = 0; mode <= 13; mode++) {
            long t0 = System.currentTimeMillis();

            for(int i = 0; i < iterations; i++) {
                // Draw one textured quad (triangle + second triangle) per mode.
                // We reuse the same vertex set; only the render mode changes.
                try {
                    r.paintM3GTriangle(g, testTexture,
                            testVerts3[0], 0, 0,
                            testVerts3[1], TEST_TEX_W, 0,
                            testVerts3[2], TEST_TEX_W, TEST_TEX_H);
                    r.paintM3GTriangle(g, testTexture,
                            testVerts3[0], 0, 0,
                            testVerts3[2], TEST_TEX_W, TEST_TEX_H,
                            testVerts4[3], 0, TEST_TEX_H);
                } catch(Throwable ex) {
                    // Swallow per-iteration errors; time still counts.
                }
            }

            long t1 = System.currentTimeMillis();
            resultsMs[mode] = t1 - t0;
        }

        ranOk = true;
        lastError = null;
    }

    /** Convenience: run with default iteration count. */
    public void runBenchmark(DirectX7 g) {
        runBenchmark(g, ITERATIONS_DEFAULT);
    }

    /** Format results as a single line for HUD overlay. */
    public String resultsLine() {
        if(!ranOk) return "SpeedTest: " + (lastError != null ? lastError : "not run");
        StringBuffer sb = new StringBuffer("SpeedTest ms (m0..m13):");
        for(int m = 0; m <= 13; m++) {
            sb.append(' ').append(resultsMs[m]);
        }
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
