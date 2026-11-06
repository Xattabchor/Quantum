package javax.microedition.m3g;
import javax.microedition.lcdui.Image;
public class Image2D {
    public static final int ALPHA = 96, LUMINANCE = 97, LUMINANCE_ALPHA = 98, RGB = 99, RGBA = 100;
    public static int uploads;
    public int format, width, height;
    public byte[] pixels, encoded;
    public Image2D(int format, Image image) {
        if (image == null) throw new NullPointerException("image");
        this.format = format; this.width = image.getWidth(); this.height = image.getHeight();
        this.pixels = argb(image); uploads++;
    }
    private static byte[] argb(Image image) {
        int w = image.getWidth(), h = image.getHeight(), n = w * h;
        int[] rgb = new int[n];
        image.getRGB(rgb, 0, w, 0, 0, w, h);
        byte[] out = new byte[n * 4];
        for (int i = 0; i < n; i++) {
            int v = rgb[i];
            out[i * 4] = (byte) (v >> 16); out[i * 4 + 1] = (byte) (v >> 8);
            out[i * 4 + 2] = (byte) v; out[i * 4 + 3] = (byte) (v >>> 24);
        }
        return out;
    }
    public Image2D(int format, Image[] images) {
        this.format = format; this.width = 0; this.height = 0; uploads++;
    }
    public Image2D(int format, int width, int height, byte[] pixels) {
        if (Graphics3D.target != null) throw new AssertionError("texture upload with bound target");
        if (format == RGBA && Boolean.getBoolean("texture.upload.fail")) throw new OutOfMemoryError("texture memory");
        if (format == LUMINANCE && Boolean.getBoolean("effects.upload.fail")) throw new OutOfMemoryError("effect mask memory");
        if (width <= 0 || height <= 0 || pixels == null) throw new IllegalArgumentException("image dimensions");
        this.format = format; this.width = width; this.height = height; this.encoded = pixels;
        uploads++;
        if (format == LUMINANCE) {
            byte[] rgba = new byte[width * height * 4];
            for (int i = 0; i < width * height; i++) { byte v = pixels[i]; rgba[i * 4] = v; rgba[i * 4 + 1] = v; rgba[i * 4 + 2] = v; rgba[i * 4 + 3] = (byte) 255; }
            this.pixels = rgba;
        } else this.pixels = pixels;
    }
}
