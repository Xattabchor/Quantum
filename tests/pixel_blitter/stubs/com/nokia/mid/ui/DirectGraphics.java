package com.nokia.mid.ui;

/** Only the Nokia API subset used by the adapter. Not a device implementation. */
public interface DirectGraphics {
    int TYPE_INT_8888_ARGB = 8888;

    void drawPixels(int[] pixels, boolean transparency, int offset, int scanlength,
            int x, int y, int width, int height, int manipulation, int format);
}
