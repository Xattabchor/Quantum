package javax.microedition.media;

import java.io.InputStream;
import javax.microedition.media.control.VolumeControl;

/**
 * Stand-in whose behaviour is controlled by system properties, so the test can
 * simulate a handset without MMAPI:
 *   -Dstub.media=absent  -> every call throws (no MMAPI installed)
 *   -Dstub.media=broken  -> createPlayer throws SecurityException
 * default              -> silent player that accepts every format
 */
public final class Manager {
    public static final String TONE_DEVICE_LOCATOR = "device://tone";
    private static int created;

    public static String mode() {
        String m = System.getProperty("stub.media");
        return m == null ? "ok" : m;
    }

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
        return new StubPlayer();
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

        public void realize() { state = REALIZED; }
        public void prefetch() { state = PREFETCHED; }
        public void start() { state = STARTED; }
        public void stop() { state = PREFETCHED; }
        public void deallocate() { state = UNREALIZED; }
        public void close() { state = CLOSED; }
        public void setLoopCount(int c) { }
        public long setMediaTime(long now) { long old = time; time = now; return old; }
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
