package code.Rendering;

/**
 * Per-frame cost profile (M3G_PROFILE=1).
 * <p>
 * Splits one M3G frame into its passes and reports, every two seconds, what
 * share of the frame each pass took. The renderer already serialises the passes
 * on one target, so a single wall clock per boundary is enough: the four
 * timestamps around a pass bound its cost from both sides.
 * <p>
 * CLDC has only {@code System.currentTimeMillis()}, which ticks in ~15 ms
 * steps on many devices. A single frame is far shorter than one tick, so
 * nothing here reads a raw per-frame delta: each pass accumulates its measured
 * milliseconds into a long and the percentage is taken over the accumulated
 * totals. The zero-delta frames contribute nothing and bias nothing; they only
 * mean a pass needs more frames before it is reported. It follows that a
 * percentage here is a share of the MEASURED time, not of the wall clock, and
 * it is only meaningful on multi-second runs.
 */
final class FrameProfile {

    static final int WORLD = 0, SPRITES = 1, SKY = 2, EFFECTS = 3,
            WEATHER = 4, FINISH = 5, SETUP = 6, PASSES = 7;

    static final String[] NAMES = {
        "world", "sprites", "sky", "effects", "wx", "finish", "setup"
    };

    private static final long WINDOW_MS = 2000L;

    private static long[] last = new long[8];
    private static long[] total = new long[PASSES];
    private static long[] frames = new long[PASSES];
    private static long windowStart;
    private static long framesInWindow;
    private static int drawnFrames;

    private FrameProfile() { }

    /** Stamp the start of a pass. Cheap: one array read plus a clock read. */
    static void mark(int pass) {
        last[pass] = System.currentTimeMillis();
    }

    /**
     * Close a pass that was opened by {@code mark(from)}.
     * {@code to} is the pass that the clock read itself belongs to; the cost of
     * reading the clock is negligible next to a pass, so it is charged to the
     * pass being closed rather than tracked separately.
     */
    static void close(int pass) {
        long now = System.currentTimeMillis();
        long d = now - last[pass];
        last[pass] = now;
        if (d <= 0 || d > 500) return; // a stall is not a pass cost
        total[pass] += d;
        frames[pass]++;
    }

    static void frameStart() {
        long now = System.currentTimeMillis();
        if (windowStart == 0) windowStart = now;
        mark(SETUP);
    }

    static void frameEnd(int objectsDrawn) {
        drawnFrames++;
        framesInWindow++;
        long now = System.currentTimeMillis();
        if (now - windowStart < WINDOW_MS) return;
        report(now);
        windowStart = now;
        framesInWindow = 0;
        for (int i = 0; i < PASSES; i++) { total[i] = 0; frames[i] = 0; }
        drawnFrames = 0;
    }

    private static void report(long now) {
        long sum = 0;
        for (int i = 0; i < PASSES; i++) sum += total[i];
        StringBuffer sb = new StringBuffer("PROFILE ");
        sb.append(framesInWindow).append("f ");
        if (sum <= 0) {
            sb.append("(no measurable time in this window)");
            System.out.println(sb.toString());
            return;
        }
        for (int i = 0; i < PASSES; i++) {
            if (total[i] <= 0) continue;
            sb.append(NAMES[i]).append('=')
              .append((int) (total[i] * 100 / sum)).append("%/")
              .append(total[i]).append("ms ");
        }
        sb.append("| measured=").append(sum).append("ms of ")
          .append(framesInWindow).append(" frames; objects=f")
          .append(drawnFrames > 0 ? drawnFrames : 0);
        System.out.println(sb.toString());
    }

    /** One line for the HUD: the three passes that dominate. */
    static String hudLine() {
        long sum = 0;
        for (int i = 0; i < PASSES; i++) sum += total[i];
        if (sum <= 0) return "profile: waiting for measurable time";
        StringBuffer sb = new StringBuffer("profile ");
        int shown = 0;
        for (int i = 0; i < PASSES && shown < 3; i++) {
            if (total[i] <= 0) continue;
            sb.append(NAMES[i]).append('=')
              .append((int) (total[i] * 100 / sum)).append("% ");
            shown++;
        }
        return sb.toString();
    }
}
