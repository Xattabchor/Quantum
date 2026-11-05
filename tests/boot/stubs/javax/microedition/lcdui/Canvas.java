package javax.microedition.lcdui;

/** Minimal Canvas: a paintable, key-driven Displayable (no toolkit thread). */
public abstract class Canvas extends Displayable {
    public static final int KEY_NUM0 = 48, KEY_NUM1 = 49, KEY_NUM2 = 50, KEY_NUM3 = 51,
            KEY_NUM4 = 52, KEY_NUM5 = 53, KEY_NUM6 = 54, KEY_NUM7 = 55, KEY_NUM8 = 56,
            KEY_NUM9 = 57, KEY_STAR = 42, KEY_POUND = 35;
    public static final int UP = 1, DOWN = 6, LEFT = 2, RIGHT = 5, FIRE = 8;
    public static final int GAME_A = 9, GAME_B = 10, GAME_C = 11, GAME_D = 12;

    protected abstract void paint(Graphics g);
    protected void keyPressed(int keyCode) { }
    protected void keyReleased(int keyCode) { }
    protected void keyRepeated(int keyCode) { }
    protected void pointerPressed(int x, int y) { }
    protected void pointerReleased(int x, int y) { }
    protected void pointerDragged(int x, int y) { }
    protected void showNotify() { }
    protected void hideNotify() { }
    // Harness access: the real handlers are protected, the MIDlet's own
    // MainCanvas calls them from inside the class.
    public void paintTo(Graphics g) { paint(g); }
    public void press(int keyCode) { keyPressed(keyCode); }
    public void release(int keyCode) { keyReleased(keyCode); }

    public void repaint() { }
    public void repaint(int x, int y, int w, int h) { }
    public void serviceRepaints() { }
    public int getGameAction(int keyCode) { return 0; }
    public int getKeyCode(int gameAction) { return 0; }
    public String getKeyName(int keyCode) { return "key" + keyCode; }
    public boolean hasPointerEvents() { return true; }
    public boolean hasPointerMotionEvents() { return true; }
    public boolean hasRepeatEvents() { return false; }
    public boolean isDoubleBuffered() { return true; }
    public void setFullScreenMode(boolean on) { }
}
