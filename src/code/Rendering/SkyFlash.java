package code.Rendering;

/** A storm changes only the sky background; no geometry, texture or world glow. */
public final class SkyFlash extends RenderObject {
    public static final int COLOR=0xe5e5e5;
    public boolean isVisible(int x1,int y1,int x2,int y2) { return true; }
    public void render(DirectX7 g,Texture t) { g.clearDisplay(COLOR); }
    public void renderFast(DirectX7 g,Texture t) { render(g,t); }
}
