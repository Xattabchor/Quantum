package javax.microedition.lcdui;

/** Raster operations on a mutable pixel buffer; counts calls so tests can assert. */
public class Graphics {
    public static final int HCENTER = 1, VCENTER = 2, LEFT = 4, RIGHT = 8, TOP = 16, BOTTOM = 32;
    public static final int SOLID = 0, DOTTED = 1;
    public static final int TRANS_NONE = 0, TRANS_ROT90 = 5, TRANS_ROT180 = 3, TRANS_ROT270 = 6,
            TRANS_MIRROR = 2, TRANS_MIRROR_ROT90 = 7, TRANS_MIRROR_ROT180 = 1, TRANS_MIRROR_ROT270 = 4;

    public static int fills, regions, strings, images;
    private final Image target;
    private Image clip;
    private int color = 0, tx, ty, stroke;
    private Font font;
    private int[] clipBox;

    public Graphics(Image target) { this.target = target; this.clip = target; }

    public void setColor(int rgb) { color = rgb & 0xffffff; }
    public int getColor() { return color; }
    public void setFont(Font f) { font = f; }
    public Font getFont() { return font == null ? Font.getDefaultFont() : font; }
    public void setStrokeStyle(int s) { stroke = s; }
    public void translate(int x, int y) { tx += x; ty += y; }
    public int getTranslateX() { return tx; }
    public int getTranslateY() { return ty; }
    public int getClipX() { return clipBox == null ? 0 : clipBox[0]; }
    public int getClipY() { return clipBox == null ? 0 : clipBox[1]; }
    public int getClipWidth() { return clipBox == null ? clip.w : clipBox[2]; }
    public int getClipHeight() { return clipBox == null ? clip.h : clipBox[3]; }
    public void setClip(int x, int y, int w, int h) { clipBox = new int[]{x, y, w, h}; }

    public void fillRect(int x, int y, int w, int h) {
        fills++;
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++) put(x + i, y + j, 0xff000000 | color);
    }

    public void drawLine(int x1, int y1, int x2, int y2) {
        images++;
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1), sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1, err = dx - dy;
        while (true) {
            put(x1, y1, 0xff000000 | color);
            if (x1 == x2 && y1 == y2) break;
            int e2 = err * 2;
            if (e2 > -dy) { err -= dy; x1 += sx; }
            if (e2 < dx) { err += dx; y1 += sy; }
        }
    }

    public void drawRect(int x, int y, int w, int h) {
        drawLine(x, y, x + w - 1, y); drawLine(x, y + h - 1, x + w - 1, y + h - 1);
    }

    public void drawRGB(int[] rgb, int off, int scan, int x, int y, int w, int h, boolean alpha) {
        images++;
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++) put(x + i, y + j, rgb[off + j * scan + i]);
    }

    public void drawImage(Image img, int x, int y, int anchor) {
        images++;
        int[] at = align(x, y, img.w, img.h, anchor);
        blit(img, 0, 0, img.w, img.h, at[0], at[1], 0);
    }

    public void drawRegion(Image img, int sx, int sy, int sw, int sh, int transform, int x, int y, int anchor) {
        regions++;
        int[] at = align(x, y, sw, sh, anchor);
        blit(img, sx, sy, sw, sh, at[0], at[1], transform);
    }

    public void drawChar(char c, int x, int y, int anchor) {
        strings++;
        drawString(String.valueOf(c), x, y, anchor);
    }

    public void drawString(String s, int x, int y, int anchor) {
        strings++;
        if (s == null) return;
        int wpx = (font == null ? 6 : font.charWidth('W')) * s.length();
        int hpx = font == null ? 12 : font.getHeight();
        int[] at = align(x, y, wpx, hpx, anchor);
        for (int i = 0; i < s.length(); i++)
            for (int j = 0; j < hpx; j++)
                for (int k = 0; k < (font == null ? 6 : font.charWidth(s.charAt(i))); k++)
                    put(at[0] + i * 6 + k, at[1] + j, 0xff000000 | color);
    }

    public void drawString(String s, int x, int y, int anchor, int labelColor) { drawString(s, x, y, anchor); }

    private int[] align(int x, int y, int w, int h, int anchor) {
        int ax = x, ay = y;
        if ((anchor & RIGHT) != 0) ax = x - w;
        else if ((anchor & HCENTER) != 0) ax = x - w / 2;
        if ((anchor & BOTTOM) != 0) ay = y - h;
        else if ((anchor & VCENTER) != 0) ay = y - h / 2;
        return new int[]{ax, ay};
    }

    private void blit(Image img, int sx, int sy, int sw, int sh, int dx, int dy, int transform) {
        for (int j = 0; j < sh; j++)
            for (int i = 0; i < sw; i++) {
                int px = sx + i, py = sy + j;
                if (px < 0 || py < 0 || px >= img.w || py >= img.h) continue;
                put(dx + i, dy + j, img.pixels[py * img.w + px]);
            }
    }

    private void put(int x, int y, int argb) {
        x += tx; y += ty;
        if (x < 0 || y < 0 || x >= target.w || y >= target.h) return;
        if (clipBox != null && (x < clipBox[0] || y < clipBox[1]
                || x >= clipBox[0] + clipBox[2] || y >= clipBox[1] + clipBox[3])) return;
        int alpha = (argb >>> 24);
        if (alpha == 0) return;
        int old = target.pixels[y * target.w + x];
        if (alpha == 255) { target.pixels[y * target.w + x] = argb; return; }
        int r = (((argb >> 16) & 255) * alpha + ((old >> 16) & 255) * (255 - alpha)) / 255;
        int g = (((argb >> 8) & 255) * alpha + ((old >> 8) & 255) * (255 - alpha)) / 255;
        int b = ((argb & 255) * alpha + (old & 255) * (255 - alpha)) / 255;
        target.pixels[y * target.w + x] = (Math.max(alpha, old >>> 24) << 24) | (r << 16) | (g << 8) | b;
    }
}
