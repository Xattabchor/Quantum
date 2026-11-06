package javax.microedition.m3g;
public class Texture2D {
    public static final int FILTER_BASE_LEVEL = 208, FILTER_LINEAR = 209, FILTER_NEAREST = 210;
    public static final int FUNC_ADD = 224, FUNC_BLEND = 225, FUNC_DECAL = 226, FUNC_MODULATE = 227, FUNC_REPLACE = 228;
    public static final int WRAP_CLAMP = 240, WRAP_REPEAT = 241;
    public static int created;
    public Image2D image;
    public int levelFilter = FILTER_BASE_LEVEL, imageFilter = FILTER_NEAREST;
    public int function = FUNC_MODULATE, blendColor = 0xffffffff, wrapS = WRAP_REPEAT, wrapT = WRAP_REPEAT;
    public Texture2D(Image2D image) { if (image == null) throw new NullPointerException("image"); created++; this.image = image; }
    public void setImage(Image2D image) { this.image = image; }
    public Image2D getImage() { return image; }
    public void setFiltering(int level, int image) { levelFilter = level; imageFilter = image; }
    public void setWrapping(int s, int t) { wrapS = s; wrapT = t; }
    public int getWrappingS() { return wrapS; }
    public int getWrappingT() { return wrapT; }
    public void setBlending(int function) { this.function = function; }
    public int getBlending() { return function; }
    public void setBlendColor(int color) { blendColor = color; }
    public int getBlendColor() { return blendColor; }
    public int getImageFilter() { return imageFilter; }
    public void setTranslation(float s, float t, float u) { }
    public void setTranslationMatrix(Transform transform) { }
    public int getLevelFilter() { return levelFilter; }
}
