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
            WEATHER = 4, FINISH = 5, SETUP = 6, LIT = 7, PASSES = 8;

    static final String[] NAMES = {
        "world", "sprites", "sky", "effects", "wx", "finish", "setup", "lit"
    };

    private static final long WINDOW_MS = 2000L;

    private static long[] last = new long[PASSES];
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
        // The cutoff separates "this pass is slow" from "the phone stalled" -
        // a debugger pause, an incoming call. 500 ms was chosen when a frame
        // was ~40 ms and any pass over that was obviously a hiccup; at the
        // frame rates this profile exists to diagnose a pass really does take
        // most of a second, and throwing those frames away is exactly why the
        // report can come back empty. 5 s is far past any real pass.
        if (d <= 0 || d > 5000) return; // a stall is not a pass cost
        total[pass] += d;
        frames[pass]++;
    }

    /**
     * Credit time measured inside the world pass to the lit sub-pass.
     * <p>
     * The lit bucket flush runs between {@code mark(WORLD)} and
     * {@code close(WORLD)}, so simply adding it to its own bucket would count
     * the same milliseconds twice and make every share wrong. Pushing
     * {@code last[WORLD]} forward by the same amount removes them from the
     * world pass: world is then charged from its mark to the start of the lit
     * flush, plus from the end of the flush to its close, which is what
     * "everything in the world pass except the lit geometry" means.
     */
    static void addLit(long d) {
        if (d <= 0) return;
        last[WORLD] += d;
        total[LIT] += d;
        frames[LIT]++;
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

    /** One letter per pass, for a HUD that clips at 30 glyphs: the spelled-out
     *  names never survived the trim, so the caller could not read the shares
     *  at all. Deliberately distinct from the millisecond readings. */
    private static final char[] LETTERS = { 'w','s','k','e','x','f','u','q' };

    /** Compact HUD tail: "|" plus the three dominant passes. No spaces - every
     *  glyph past 30 is invisible on a 240 px screen at 8 px per glyph. */
    static String hudLineCompact() {
        long sum = 0;
        for (int i = 0; i < PASSES; i++) sum += total[i];
        if (sum <= 0) return "|?";
        StringBuffer sb = new StringBuffer("|");
        boolean[] used = new boolean[PASSES];
        for (int shown = 0; shown < 3; shown++) {
            int best = -1;
            for (int i = 0; i < PASSES; i++)
                if (!used[i] && total[i] > 0
                        && (best < 0 || total[i] > total[best])) best = i;
            if (best < 0) break;
            used[best] = true;
            sb.append(LETTERS[best])
              .append((int) (total[best] * 100 / sum));
        }
        return sb.toString();
    }

    /** One line for the HUD: the three passes that dominate. */
    static String hudLine() {
        long sum = 0;
        for (int i = 0; i < PASSES; i++) sum += total[i];
        if (sum <= 0) return "profile: waiting for measurable time";
        // Three biggest, not the first three in pass order: the pass that
        // matters is whichever is expensive, and that is not a fixed index -
        // the lit sub-pass sits last in the array and is routinely the answer.
        StringBuffer sb = new StringBuffer("profile ");
        boolean[] used = new boolean[PASSES];
        for (int shown = 0; shown < 3; shown++) {
            int best = -1;
            for (int i = 0; i < PASSES; i++)
                if (!used[i] && total[i] > 0
                        && (best < 0 || total[i] > total[best])) best = i;
            if (best < 0) break;
            used[best] = true;
            sb.append(NAMES[best]).append('=')
              .append((int) (total[best] * 100 / sum)).append("% ");
        }
        return sb.toString();
    }
}
