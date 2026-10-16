package com.nokia.mid.ui;

import javax.microedition.lcdui.Graphics;

public final class DirectUtils {
    public static DirectGraphics getDirectGraphics(final Graphics g) {
        String mode = System.getProperty("nokia.mode", "direct");
        if (mode.equals("linkage")) throw new NoClassDefFoundError("optional API");
        if (mode.equals("oom")) throw new OutOfMemoryError("test");
        if (!g.supportsDirect) return null;
        return new DirectGraphics() {
            public void drawPixels(int[] pixels, boolean alpha, int offset,
                    int stride, int x, int y, int width, int height,
                    int manipulation, int format) {
                if (manipulation != 0 || format != TYPE_INT_8888_ARGB) {
                    throw new AssertionError("Unexpected Nokia pixel format/manipulation");
                }
                g.directCalls++;
                g.writePixels(pixels, offset, stride, x, y, width, height, alpha);
            }
        };
    }
}
