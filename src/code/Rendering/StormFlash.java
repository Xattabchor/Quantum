package code.Rendering;

/** Screen-space additive storm light. WeatherGenerator owns the CPU fallback. */
public final class StormFlash extends RenderObject {
    int top, horizon, size;

    public void set(int horizon, int size) {
        this.horizon=horizon;this.size=size;top=Math.max(0,horizon);
    }

    public boolean isVisible(int x1,int y1,int x2,int y2) { return top<y2; }
    // Explicit overlay only: failure restores the frame, then WeatherGenerator
    // applies its original pixel loop once. Never queue this cosmetic object.
    public void render(DirectX7 g,Texture t) { }
    public void renderFast(DirectX7 g,Texture t) { }
}
