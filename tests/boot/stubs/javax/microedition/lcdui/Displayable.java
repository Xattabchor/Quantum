package javax.microedition.lcdui;

public abstract class Displayable {
    private String title;
    private CommandListener listener;

    public void setTitle(String t) { title = t; }
    public String getTitle() { return title; }
    public void addCommand(Command c) { }
    public void removeCommand(Command c) { }
    public void setCommandListener(CommandListener l) { listener = l; }
    protected void sizeChanged(int w, int h) { }
    public boolean isShown() { return Display.getDisplay(null).getCurrent() == this; }
    public int getWidth() { return 240; }
    public int getHeight() { return 320; }
    public void setFullScreenMode(boolean on) { }
}
