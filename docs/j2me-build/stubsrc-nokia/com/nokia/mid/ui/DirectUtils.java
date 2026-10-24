package com.nokia.mid.ui;

import javax.microedition.lcdui.Graphics;

/**
 * Compile-time stub of the Nokia UI API entry point (see DirectGraphics.java
 * for provenance of the signatures). On a real Nokia device the platform
 * implementation is used; this class exists only so that ECJ can compile
 * code.Rendering.NokiaBlitter without the proprietary Nokia SDK jar.
 */
public class DirectUtils {

    /** Returns the DirectGraphics peer of the given Graphics context. */
    public static DirectGraphics getDirectGraphics(Graphics g) {
        throw new RuntimeException("compile-time stub");
    }
}
