package javax.microedition.lcdui;

/** Only the surface used by the game (window over an Image). */
public class Sprite {
    public static final int TRANS_NONE = 0, TRANS_ROT90 = 5;
    private final Image img;
    private int frame, x, y;

    public Sprite(Image image) { this(image, image.getWidth(), image.getHeight()); }
    public Sprite(Image image, int frameWidth, int frameHeight) {
        img = image; this.frame = frameWidth;
    }
    public void defineReferencePixel(int x, int y) { }
    public void setRefPixelPosition(int x, int y) { this.x = x; this.y = y; }
    public int getRefPixelX() { return x; }
    public int getRefPixelY() { return y; }
    public int getFrame() { return 0; }
    public void setFrame(int f) { }
    public int getRawFrameCount() { return 1; }
    public void setTransform(int t) { }
    public void paint(Graphics g) { }
}
