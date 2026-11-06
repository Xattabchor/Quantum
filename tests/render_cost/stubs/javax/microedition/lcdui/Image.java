package javax.microedition.lcdui;

import java.io.InputStream;

/** Pixels-backed Image double: real ARGB buffer so frames can be hashed. */
public class Image {

    public static int creates, getRGBCalls, readbacks;
    public int[] pixels;
    public int width, height;

    protected Image() {}

    private static Image of(int[] rgb, int w, int h) {
        Image i = new Image();
        i.width = w; i.height = h; i.pixels = rgb;
        creates++;
        return i;
    }

    public static Image createImage(int w, int h) {
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("image size " + w + "x" + h);
        Image i = of(new int[w * h], w, h);
        i.shortPixels = new short[w * h];
        return i;
    }

    private int[] shortBacking;

    public static Image createRGBImage(int[] rgb, int w, int h, boolean processAlpha) {
        if (rgb == null || w <= 0 || h <= 0 || rgb.length != w * h) throw new IllegalArgumentException("rgb image");
        return of(rgb.clone(), w, h);
    }

    /** Mutable off-screen image backed by a raw short[] (JSR-184 bindTarget). */
    public static Image createImage(int w, int h, boolean mutableShort) {
        if (!mutableShort) return createImage(w, h);
        Image i = new Image();
        i.width = w; i.height = h; i.pixels = new int[w * h];
        i.shortPixels = new short[w * h];
        creates++;
        return i;
    }

    public short[] shortPixels;

    public static Image createImage(Image source) {
        return of(source.pixels.clone(), source.width, source.height);
    }

    public static Image createImage(byte[] imageData, int offset, int length) {
        // Only the mutable-image path matters for the renderer harness.
        int w = 1, h = 1;
        try {
            java.awt.image.BufferedImage decoded = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(imageData, offset, length));
            if (decoded != null) {
                w = decoded.getWidth(); h = decoded.getHeight();
                return of(decoded.getRGB(0, 0, w, h, null, 0, w), w, h);
            }
        } catch (Throwable ignored) {
        }
        return of(new int[w * h], w, h);
    }

    public static Image createImage(String name) {
        InputStream in = Image.class.getResourceAsStream(name);
        if (in == null) throw new IllegalArgumentException("image not found: " + name);
        byte[] data;
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            data = out.toByteArray();
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("image read: " + name);
        }
        if (data.length > 2 && (data[0] & 0xff) == 0x51 && (data[1] & 0xff) == 0x43) {
            return decodeQct(data, name); // legacy QCIF palette format
        }
        return createImage(data, 0, data.length);
    }

    private static Image decodeQct(byte[] data, String name) {
        try {
            int w = ((data[2] & 0xff) << 8) | (data[3] & 0xff);
            int h = ((data[4] & 0xff) << 8) | (data[5] & 0xff);
            int[] rgb = new int[w * h];
            int pos = 6;
            for (int i = 0; i < rgb.length; i++) {
                int b = data[pos] & 0xff;
                rgb[i] = 0xff000000 | (b << 16) | (b << 8) | b;
                pos++;
            }
            return of(rgb, w, h);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("qct image: " + name);
        }
    }

    public static Image createImage(InputStream stream) {
        if (stream == null) throw new NullPointerException("stream");
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = stream.read(buf)) != -1) out.write(buf, 0, n);
            return createImage(out.toByteArray(), 0, out.size());
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("image stream");
        }
    }

    public int getWidth() { return width; }

    public int getHeight() { return height; }

    public Graphics getGraphics() { return new Graphics(this); }

    public void getRGB(int[] rgbData, int offset, int scanlength, int x, int y, int w, int h) {
        getRGBCalls++; readbacks++;
        if (Boolean.getBoolean("readback.fail")) throw new IllegalStateException("readback");
        if (rgbData == null) throw new NullPointerException("rgb");
        if (x < 0 || y < 0 || w < 0 || h < 0 || x + w > width || y + h > height)
            throw new IllegalArgumentException("getRGB range");
        for (int j = 0; j < h; j++)
            System.arraycopy(pixels, (y + j) * width + x, rgbData, offset + j * scanlength, w);
    }
}
