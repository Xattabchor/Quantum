package javax.microedition.media;

public interface Player extends Controllable {
    int UNREALIZED = 100, REALIZED = 200, PREFETCHED = 300, STARTED = 400, CLOSED = 0;
    long TIME_UNKNOWN = -1;
    void realize();
    void prefetch();
    void start();
    void stop();
    void deallocate();
    void close();
    void setLoopCount(int count);
    long setMediaTime(long now);
    long getMediaTime();
    long getDuration();
    String getContentType();
    int getState();
    void addPlayerListener(PlayerListener p);
    void removePlayerListener(PlayerListener p);
    void setTimeBase(TimeBase tb);
}
