package code.Rendering;

import javax.microedition.lcdui.Graphics;

/**
 * Presents the original RGB/ARGB buffers without converting or copying them.
 * Nokia-specific types must stay out of this class so it also loads on MIDP
 * devices which do not provide the Nokia UI API.
 */
public final class PixelBlitter {

    private static PixelBlitterBackend backend = createBackend();

    private PixelBlitter() {
    }

    private static PixelBlitterBackend createBackend() {
        try {
            // Keep this name when enabling an obfuscator.
            return (PixelBlitterBackend) Class.forName(
                    "code.Rendering.NokiaPixelBlitter").newInstance();
        } catch (ClassNotFoundException ex) {
        } catch (IllegalAccessException ex) {
        } catch (InstantiationException ex) {
        } catch (NoClassDefFoundError ex) {
        } catch (SecurityException ex) {
        }
        return null;
    }

    /** Same buffer layout, alpha, coordinates and Graphics state as drawRGB. */
    public static void drawRGB(Graphics g, int[] pixels, int offset, int scanlength,
            int x, int y, int width, int height, boolean processAlpha) {
        if (backend != null) {
            try {
                if (backend.drawPixels(g, pixels, offset, scanlength,
                        x, y, width, height, processAlpha)) return;
            } catch (NoClassDefFoundError ex) {
                // Some VMs resolve the optional API only on the first draw.
                backend = null;
            }
        }
        g.drawRGB(pixels, offset, scanlength, x, y, width, height, processAlpha);
    }
}
