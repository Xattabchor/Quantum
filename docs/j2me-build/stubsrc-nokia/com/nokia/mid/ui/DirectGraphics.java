package com.nokia.mid.ui;

/**
 * Compile-time stub of the Nokia UI API interface (JSR-75-style vendor
 * optional package). Only the members used by code.Rendering.NokiaBlitter
 * are declared; the stub jar never reaches the device (bootclasspath /
 * ProGuard -libraryjars only).
 *
 * Constant values follow the official Nokia UI API 1.1 javadoc and were
 * cross-checked against two independent emulator sources (J2ME-Loader,
 * KEmulator). ECJ inlines `static final int`, so these values MUST match
 * the real device API: TYPE_INT_8888_ARGB = 8888 ("Value 8888 is assigned
 * to TYPE_INT_8888_ARGB" - Nokia UI API 1.1, Constant Field Values).
 */
public interface DirectGraphics {

    public static final int FLIP_HORIZONTAL = 8192;
    public static final int FLIP_VERTICAL = 16384;
    public static final int ROTATE_90 = 90;
    public static final int ROTATE_180 = 180;
    public static final int ROTATE_270 = 270;
    public static final int TYPE_INT_888_RGB = 888;
    public static final int TYPE_INT_8888_ARGB = 8888;

    /**
     * Copies pixel data directly to the graphics context. Addressing is
     * identical to Graphics.drawRGB:
     *   P(x1, y1) = pixels[offset + (x1 - x) + (y1 - y) * scanlength]
     * transparency == false ignores the alpha channel (opaque blit),
     * matching drawRGB(processAlpha = false). manipulation is a bitwise OR
     * of FLIP_ and ROTATE_ constants (0 = no manipulation; the API has no FLIP_NONE
     * constant). formatOfPixels is one of the TYPE_ pixel format values.
     */
    public void drawPixels(int[] pixels, boolean transparency, int offset,
            int scanlength, int x, int y, int width, int height,
            int manipulation, int formatOfPixels);
}
