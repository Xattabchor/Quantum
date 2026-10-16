package code.Rendering;

import com.nokia.mid.ui.DirectGraphics;
import com.nokia.mid.ui.DirectUtils;
import javax.microedition.lcdui.Graphics;

/** Optional Nokia UI implementation; loaded by name from PixelBlitter. */
public final class NokiaPixelBlitter implements PixelBlitterBackend {

    public NokiaPixelBlitter() {
    }

    public boolean drawPixels(Graphics g, int[] pixels, int offset, int scanlength,
            int x, int y, int width, int height, boolean processAlpha) {
        // Obtain the wrapper for this context, not one cached from an old paint
        // or off-screen Image. Translation and clipping belong to Graphics.
        DirectGraphics direct = DirectUtils.getDirectGraphics(g);
        if (direct == null) return false;

        // With transparency=false the high byte is ignored, just like drawRGB.
        // Do not force alpha to 255: effects and flushAlpha share this buffer.
        direct.drawPixels(pixels, processAlpha, offset, scanlength,
                x, y, width, height, 0, DirectGraphics.TYPE_INT_8888_ARGB);
        return true;
    }
}
