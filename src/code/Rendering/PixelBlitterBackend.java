package code.Rendering;

import javax.microedition.lcdui.Graphics;

interface PixelBlitterBackend {

    /** Returns false if the current Graphics context is not supported. */
    boolean drawPixels(Graphics g, int[] pixels, int offset, int scanlength,
            int x, int y, int width, int height, boolean processAlpha);
}
