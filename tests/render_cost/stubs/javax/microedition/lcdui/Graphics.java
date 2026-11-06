package javax.microedition.lcdui;

/** Immediately rasterizing Graphics double over an Image pixel buffer. */
public class Graphics {

    public static final int HCENTER = 1, VCENTER = 2, LEFT = 4, RIGHT = 8, TOP = 16, BOTTOM = 32,
            BASELINE = 64, SOLID = 0, DOTTED = 1;

    public static int draws, fills;

    public final Image image;
    private int tx, ty, clipX, clipY, clipW, clipH, color = 0xff000000, stroke, alpha = 255;

    Graphics(Image image) {
        this.image = image;
        this.clipW = image.width;
        this.clipH = image.height;
    }

    private Graphics(Graphics other) {
        this.image = other.image;
        this.tx = other.tx; this.ty = other.ty;
        this.clipX = other.clipX; this.clipY = other.clipY; this.clipW = other.clipW; this.clipH = other.clipH;
        this.color = other.color;
    }

    public void translate(int x, int y) { tx += x; ty += y; }
    public int getTranslateX() { return tx; }
    public int getTranslateY() { return ty; }
    public int getColor() { return color; }
    public void setColor(int rgb) { color = 0xff000000 | rgb; }
    public void setColor(int r, int g, int b) { color = 0xff000000 | (r << 16) | (g << 8) | b; }
    public int getRedComponent() { return (color >> 16) & 0xff; }
    public int getGreenComponent() { return (color >> 8) & 0xff; }
    public int getBlueComponent() { return color & 0xff; }
    public int getGrayScale() { return ((color >> 16) & 0xff) * 77 / 256 + ((color >> 8) & 0xff) * 150 / 256 + (color & 0xff) * 29 / 256; }
    public void setGrayScale(int v) { setColor(v, v, v); }
    public void setStrokeStyle(int style) { stroke = style; }
    public int getStrokeStyle() { return stroke; }
    public Font getFont() { return Font.getDefaultFont(); }
    public void setFont(Font font) { }
    public int getClipX() { return clipX - tx; }
    public int getClipY() { return clipY - ty; }
    public int getClipWidth() { return clipW; }
    public int getClipHeight() { return clipH; }
    public void setClip(int x, int y, int w, int h) {
        clipX = x + tx; clipY = y + ty; clipW = w; clipH = h;
    }
    public void clipRect(int x, int y, int w, int h) { setClip(x, y, w, h); }

    private void pixel(int x, int y, int argb) {
        if (x < clipX || y < clipY || x >= clipX + clipW || y >= clipY + clipH) return;
        if (x < 0 || y < 0 || x >= image.width || y >= image.height) return;
        image.pixels[y * image.width + x] = argb;
    }

    public void fillRect(int x, int y, int w, int h) {
        fills++;
        for (int j = y + ty; j < y + ty + h; j++)
            for (int i = x + tx; i < x + tx + w; i++) pixel(i, j, color);
    }

    public void drawRect(int x, int y, int w, int h) {
        drawLine(x, y, x + w - 1, y); drawLine(x + w - 1, y, x + w - 1, y + h - 1);
        drawLine(x + w - 1, y + h - 1, x, y + h - 1); drawLine(x, y + h - 1, x, y);
    }

    public void drawLine(int x1, int y1, int x2, int y2) {
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1, err = dx - dy;
        int x = x1 + tx, y = y1 + ty;
        int xEnd = x2 + tx, yEnd = y2 + ty;
        while (true) {
            pixel(x, y, color);
            if (x == xEnd && y == yEnd) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x += sx; }
            if (e2 < dx) { err += dx; y += sy; }
        }
    }

    public void fillTriangle(int x1, int y1, int x2, int y2, int x3, int y3) { }

    public void drawImage(Image src, int x, int y, int anchor) {
        draws++;
        blit(src, 0, 0, src.width, src.height, x + tx, y + ty);
    }

    public void drawRegion(Image src, int sx, int sy, int w, int h, int transform, int x, int y, int anchor) {
        draws++;
        blit(src, sx, sy, w, h, x + tx, y + ty);
    }

    private void blit(Image src, int sx, int sy, int w, int h, int dx, int dy) {
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int sxi = sx + i, syi = sy + j;
                if (sxi < 0 || syi < 0 || sxi >= src.width || syi >= src.height) continue;
                int argb = src.pixels[syi * src.width + sxi];
                if ((argb >>> 24) == 0) continue;
                pixel(dx + i, dy + j, argb);
            }
        }
    }

    public void drawRGB(int[] rgbData, int offset, int scanlength, int x, int y, int w, int h, boolean processAlpha) {
        fills++;
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++) pixel(x + tx + i, y + ty + j, rgbData[offset + j * scanlength + i]);
    }

    public void drawString(String text, int x, int y, int anchor) { }
    public void drawSubstring(String text, int offset, int len, int x, int y, int anchor) { }
    public void drawChar(char c, int x, int y, int anchor) { }
    public void drawChars(char[] data, int offset, int length, int x, int y, int anchor) { }
    public void fillArc(int x, int y, int w, int h, int start, int arc) { fillRect(x, y, w, h); }
    public void drawArc(int x, int y, int w, int h, int start, int arc) { }
    public void fillRoundRect(int x, int y, int w, int h, int rw, int rh) { fillRect(x, y, w, h); }
    public void drawRoundRect(int x, int y, int w, int h, int rw, int rh) { drawRect(x, y, w, h); }
    public void copyArea(int sx, int sy, int w, int h, int dx, int dy, int anchor) { }
    public int getDisplayColor(int color) { return color; }

    public Graphics copy() { return new Graphics(this); }
}
