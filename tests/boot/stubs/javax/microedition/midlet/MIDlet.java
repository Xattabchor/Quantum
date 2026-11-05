package javax.microedition.midlet;

public abstract class MIDlet {
    public boolean isDestroyed;
    public MIDlet() { }

    protected abstract void startApp() throws MIDletStateChangeException;
    protected abstract void pauseApp();
    protected abstract void destroyApp(boolean unconditional) throws MIDletStateChangeException;

    public final void notifyDestroyed() { isDestroyed = true; }
    public final void notifyPaused() { }
    public final String getAppProperty(String key) { return System.getProperty(key); }
    public final boolean platformRequest(String url) { return false; }
    public final void resumeRequest() { }
}
