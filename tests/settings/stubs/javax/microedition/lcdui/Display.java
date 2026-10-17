package javax.microedition.lcdui;

import javax.microedition.midlet.MIDlet;

/** No device window, but allow the actual MainCanvas/Setting constructors. */
public final class Display {
    private static final Display instance=new Display();
    public static Display getDisplay(MIDlet midlet) {return instance;}
    public void setCurrent(Displayable screen) {}
}
