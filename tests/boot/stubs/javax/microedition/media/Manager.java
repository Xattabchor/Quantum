package javax.microedition.media;

import java.io.InputStream;
import javax.microedition.media.control.VolumeControl;

/**
 * Stand-in whose behaviour is controlled by system properties, so the test can
 * simulate a handset without MMAPI:
 *   -Dstub.media=absent  -> every call throws (no MMAPI installed)
 *   -Dstub.media=broken  -> createPlayer throws SecurityException
 *   -Dstub.media=strict  -> like real Symbian/Nokia players: seek and loop
 *                           count are illegal while the player is STARTED, and
 *                           start() on a STARTED player throws
 * default              -> permissive silent player
 */
public final class Manager {
    public static final String TONE_DEVICE_LOCATOR = "device://tone";
    private static int created;

    public static String mode() {
        String m = System.getProperty("stub.media");
        return m == null ? "ok" : m;
    }

    /** Real handsets reject state changes that a permissive stub would allow. */
    public static boolean strict() {
        return "strict".equals(mode());
    }

    public static int livePlayers() {
        int live = 0;
        for (int i = 0; i < ALL.size(); i++) {
            StubPlayer p = (StubPlayer) ALL.elementAt(i);
            if (p.state == Player.CLOSED) live++;
        }
        return ALL.size() - live;
    }

    static final java.util.Vector ALL = new java.util.Vector();

    private static void gate(String what) {
        String m = mode();
        if ("absent".equals(m)) throw new IllegalStateException("MMAPI not available: " + what);
        if ("broken".equals(m)) throw new SecurityException("player access denied: " + what);
    }

    public static Player createPlayer(String locator) throws java.io.IOException, MediaException {
        gate("locator");
        created++;
        return new StubPlayer();
    }

    public static Player createPlayer(InputStream stream, String type) throws java.io.IOException, MediaException {
        gate("stream:" + type);
        created++;
        StubPlayer p = new StubPlayer();
        ALL.addElement(p);
        return p;
    }

    public static String[] getSupportedContentTypes(String protocol) {
        gate("types");
        return new String[]{"audio/midi", "audio/x-tone-seq", "audio/wav"};
    }

    public static Player createPlayer(DataSource source) throws java.io.IOException, MediaException {
        throw new UnsupportedOperationException();
    }

    public static int createdPlayers() { return created; }

    public static void resetCounters() { created = 0; }

    private static final class StubPlayer implements Player {
        private int state = UNREALIZED;
        private long time;
        private VolumeControl volume = new StubVolume();

        public void realize() {
            if (state == CLOSED) throw new IllegalStateException("realize on CLOSED");
            state = REALIZED;
        }
        public void prefetch() {
            if (state == CLOSED) throw new IllegalStateException("prefetch on CLOSED");
            state = PREFETCHED;
        }
        public void start() {
            if (state == CLOSED) throw new IllegalStateException("start on CLOSED");
            if (strict() && state == STARTED) throw new IllegalStateException("start on STARTED");
            state = STARTED;
        }
        public void stop() {
            if (state != STARTED && state != PREFETCHED) return;
            state = PREFETCHED;
        }
        public void deallocate() { state = UNREALIZED; }
        public void close() { state = CLOSED; }
        public void setLoopCount(int c) {
            if (strict() && state == STARTED) throw new IllegalStateException("setLoopCount while STARTED");
        }
        public long setMediaTime(long now) {
            if (strict() && state == STARTED) throw new IllegalStateException("setMediaTime while STARTED");
            long old = time; time = now; return old;
        }
        public long getMediaTime() { return time; }
        public long getDuration() { return 30000; }
        public String getContentType() { return "audio/midi"; }
        public int getState() { return state; }
        public void addPlayerListener(PlayerListener l) { }
        public void removePlayerListener(PlayerListener l) { }
        public void setTimeBase(javax.microedition.media.TimeBase tb) { }
        public Control getControl(String type) {
            return type.endsWith("VolumeControl") ? volume : null;
        }
        public Control[] getControls() { return new Control[]{volume}; }
    }

    private static final class StubVolume implements VolumeControl {
        private int level = 100;
        public int setLevel(int l) { int old = level; level = l < 0 ? 0 : (l > 100 ? 100 : l); return old; }
        public int getLevel() { return level; }
        public void setMute(boolean m) { level = m ? 0 : 100; }
        public boolean isMuted() { return level == 0; }
        public void addControlListener(ControlListener l) { }
        public void removeControlListener(ControlListener l) { }
    }
}
