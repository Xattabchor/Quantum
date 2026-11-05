package code.HUD;

/**
 * Startup with javax.microedition.media missing from the classpath entirely, as
 * on a handset without JSR-135. Anything that touches MMAPI at class-init time
 * fails here; the MIDlet itself must still boot.
 *
 * Compiled against the stubs, executed without them (see tests/boot/run.py).
 */
public final class NoMediaBoot {

    public static void main(String[] args) throws Exception {
        Object midlet = Class.forName("code.utils.Main").getDeclaredConstructor().newInstance();
        java.lang.reflect.Method start = midlet.getClass().getDeclaredMethod("startApp");
        start.setAccessible(true);
        start.invoke(midlet);

        Object failure = midlet.getClass().getField("startupFailure").get(null);
        if (failure != null) {
            throw new AssertionError("startup failed without MMAPI: " + failure);
        }
        if (javax.microedition.lcdui.Display.getDisplay(null).getCurrent() == null) {
            throw new AssertionError("no screen after startup without MMAPI");
        }
        System.out.println("[ok] boot/nomedia-noclass startup without javax.microedition.media");
    }
}
