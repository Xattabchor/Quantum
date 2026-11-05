package javax.microedition.lcdui;

/** Fixed-metric system font; the game's own bitmap font is code.HUD.Base.Font. */
public final class Font {
    public static final int FACE_SYSTEM = 0, FACE_MONOSPACE = 32, FACE_PROPORTIONAL = 64;
    public static final int STYLE_PLAIN = 0, STYLE_BOLD = 1, STYLE_ITALIC = 2, STYLE_UNDERLINED = 4;
    public static final int SIZE_SMALL = 8, SIZE_MEDIUM = 0, SIZE_LARGE = 16;

    private static final Font INSTANCE = new Font();
    private final int height, width;

    private Font() { this.height = 12; this.width = 6; }

    public static Font getFont(int face, int style, int size) { return INSTANCE; }
    public static Font getDefaultFont() { return INSTANCE; }

    public int getHeight() { return height; }
    public int getBaselinePosition() { return height - 3; }
    public int charWidth(char c) { return width; }
    public int charsWidth(char[] c, int off, int len) { return width * len; }
    public int stringWidth(String s) { return s == null ? 0 : width * s.length(); }
    public int getFace() { return FACE_SYSTEM; }
    public int getStyle() { return STYLE_PLAIN; }
    public int getSize() { return SIZE_SMALL; }
    public boolean isPlain() { return true; }
    public boolean isBold() { return false; }
    public boolean isItalic() { return false; }
    public boolean isUnderlined() { return false; }
}
