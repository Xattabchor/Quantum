package javax.microedition.lcdui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Desktop stand-in: real PNG decoding via ImageIO, no LCDUI on the classpath. */
public class Image {
    public int[] pixels;
    public int w, h;
    public static int creates;

    Image(int w, int h, int[] px) { this.w = w; this.h = h; this.pixels = px; }

    public static Image createImage(int w, int h) {
        return new Image(w, h, new int[w * h]);
    }

    public static Image createImage(Image src, int x, int y, int w, int h, int transform) {
        int[] px = new int[w * h];
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++) {
                int sx = x + i, sy = y + j;
                px[j * w + i] = (sx >= 0 && sy >= 0 && sx < src.w && sy < src.h)
                        ? src.pixels[sy * src.w + sx] : 0;
            }
        return new Image(w, h, px);
    }

    public static Image createRGBImage(int[] rgb, int w, int h, boolean alpha) {
        return new Image(w, h, rgb);
    }

    public static Image createImage(byte[] data, int off, int len) throws IOException {
        return decode(new java.io.ByteArrayInputStream(data, off, len));
    }

    public static Image createImage(String name) throws IOException {
        InputStream is = open(name);
        if (is == null) throw new IOException("image resource not found: " + name);
        return decode(is);
    }

    /** Same lookup order as StringTools.openResource on a device. */
    public static InputStream open(String name) {
        InputStream is = Image.class.getResourceAsStream(name);
        if (is != null) return is;
        if (name.startsWith("/")) is = Image.class.getResourceAsStream(name.substring(1));
        if (is != null) return is;
        String dir = System.getProperty("stub.resource.dir");
        if (dir != null) {
            try {
                java.io.File f = new java.io.File(dir, name.startsWith("/") ? name.substring(1) : name);
                if (f.isFile()) return new java.io.FileInputStream(f);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    public static Image createImage(InputStream is) throws IOException {
        if (is == null) throw new IOException("null image stream");
        return decode(is);
    }

    private static Image decode(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        try {
            java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(bos.toByteArray()));
            if (bi == null) throw new IOException("unsupported image format");
            int w = bi.getWidth(), h = bi.getHeight();
            int[] px = bi.getRGB(0, 0, w, h, null, 0, w);
            creates++;
            return new Image(w, h, px);
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(t.toString());
        }
    }

    public int getWidth() { return w; }
    public int getHeight() { return h; }
    public Graphics getGraphics() { return new Graphics(this); }

    public void getRGB(int[] rgb, int off, int scanlen, int x, int y, int w, int h) {
        for (int j = 0; j < h; j++)
            for (int i = 0; i < w; i++) {
                int sx = x + i, sy = y + j;
                rgb[off + j * scanlen + i] = (sx >= 0 && sy >= 0 && sx < this.w && sy < this.h)
                        ? pixels[sy * this.w + sx] : 0;
            }
    }
}
