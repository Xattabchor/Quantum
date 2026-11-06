package javax.microedition.lcdui;

public class Font {

    public static final int FACE_SYSTEM = 0, FACE_MONOSPACE = 32, FACE_PROPORTIONAL = 64;
    public static final int STYLE_PLAIN = 0, STYLE_BOLD = 1, STYLE_ITALIC = 2, STYLE_UNDERLINED = 4;
    public static final int SIZE_SMALL = 8, SIZE_MEDIUM = 0, SIZE_LARGE = 16;

    private static final Font DEFAULT = new Font();

    public static Font getDefaultFont() { return DEFAULT; }
    public static Font getFont(int face, int style, int size) { return DEFAULT; }
    public int getHeight() { return 12; }
    public int getBaselinePosition() { return 10; }
    public int charWidth(char c) { return 6; }
    public int charsWidth(char[] c, int off, int len) { return 6 * len; }
    public int stringWidth(String s) { return s == null ? 0 : 6 * s.length(); }
    public int substringWidth(String s, int off, int len) { return 6 * len; }
    public int getFace() { return FACE_SYSTEM; }
    public int getStyle() { return STYLE_PLAIN; }
    public int getSize() { return SIZE_MEDIUM; }
    public boolean isBold() { return false; }
    public boolean isItalic() { return false; }
    public boolean isUnderlined() { return false; }
}
