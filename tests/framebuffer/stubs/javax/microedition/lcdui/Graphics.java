package javax.microedition.lcdui;

/** Test double only. Never include tests in the MIDlet source path. */
public class Graphics {
    public final int[] surface = new int[64];
    public int midpCalls;
    public int translateX = 1, translateY = 2;
    public int clipX = 2, clipY = 1, clipWidth = 4, clipHeight = 5;
    public int[] lastPixels;
    public int lastOffset, lastStride, lastX, lastY, lastWidth, lastHeight;
    public boolean lastAlpha;

    public Graphics() {
        for (int i = 0; i < surface.length; i++) surface[i] = 0xff204060;
    }

    public void drawRGB(int[] pixels, int offset, int stride, int x, int y,
            int width, int height, boolean alpha) {
        midpCalls++;
        writePixels(pixels, offset, stride, x, y, width, height, alpha);
    }

    public void writePixels(int[] pixels, int offset, int stride, int x, int y,
            int width, int height, boolean alpha) {
        lastPixels = pixels;
        lastOffset = offset;
        lastStride = stride;
        lastX = x;
        lastY = y;
        lastWidth = width;
        lastHeight = height;
        lastAlpha = alpha;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int dx = x + col + translateX, dy = y + row + translateY;
                if (dx < clipX || dx >= clipX + clipWidth || dy < clipY
                        || dy >= clipY + clipHeight || dx < 0 || dx >= 8
                        || dy < 0 || dy >= 8) continue;
                int src = pixels[offset + row * stride + col];
                int a = alpha ? src >>> 24 : 255;
                int dst = surface[dy * 8 + dx];
                int r = (((src >> 16) & 255) * a + ((dst >> 16) & 255) * (255 - a)) / 255;
                int g = (((src >> 8) & 255) * a + ((dst >> 8) & 255) * (255 - a)) / 255;
                int b = ((src & 255) * a + (dst & 255) * (255 - a)) / 255;
                surface[dy * 8 + dx] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
    }
}
