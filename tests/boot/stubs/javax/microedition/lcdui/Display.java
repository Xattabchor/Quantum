package javax.microedition.lcdui;

import javax.microedition.midlet.MIDlet;

/** Desktop Display: keeps a current Displayable and lets tests drive paint/keys. */
public final class Display {
    private static final Display INSTANCE = new Display();
    private Displayable current;
    public static int paints;

    public static Display getDisplay(MIDlet m) { return INSTANCE; }

    public void setCurrent(Displayable d) { current = d; }
    public Displayable getCurrent() { return current; }
    public boolean isColor() { return true; }
    public int numColors() { return 65536; }
    public void vibrate(int ms) { }
    public void flashBacklight(int ms) { }
    public boolean flashBacklight(int duration, int color) { return true; }
}
