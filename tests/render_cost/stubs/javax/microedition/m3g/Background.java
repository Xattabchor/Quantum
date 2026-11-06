package javax.microedition.m3g;
public class Background {
    public boolean colorClear = true, depthClear = true, crop;
    public int color = 0xff000000, boundsX = 0, boundsY = 0, boundsW = -1, boundsH = -1;
    public Image2D image;
    public void setColor(int argb) { color = argb; }
    public int getColor() { return color; }
    public void setColorClearEnable(boolean e) { colorClear = e; }
    public boolean isColorClearEnabled() { return colorClear; }
    public void setDepthClearEnable(boolean e) { depthClear = e; }
    public boolean isDepthClearEnabled() { return depthClear; }
    public void setImage(Image2D image) { this.image = image; }
    public Image2D getImage() { return image; }
    public void setCrop(int x, int y, int w, int h) { crop = true; boundsX = x; boundsY = y; boundsW = w; boundsH = h; }
    public int getCropX() { return boundsX; }
    public int getCropY() { return boundsY; }
    public int getCropWidth() { return boundsW; }
    public int getCropHeight() { return boundsH; }
    public void setImageMode(int modeX, int modeY) {}
}
