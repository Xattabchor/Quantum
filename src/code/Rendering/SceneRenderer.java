package code.Rendering;

/** Optional rasterizer. No JSR-184 types in this interface or DirectX7. */
public interface SceneRenderer {
    boolean draw(DirectX7 g3d, RenderObject object, Texture texture, boolean fast);
    void prepareEffects(DirectX7 g3d, FrameEffects effects);
    void prepareWeather(DirectX7 g3d, ScreenLines weather);
    void drawCanvasImages(DirectX7 g3d,ScreenImages images,javax.microedition.lcdui.Graphics target);
    void barrier(DirectX7 g3d);
    void finish(DirectX7 g3d);
    javax.microedition.lcdui.Image finishImage(DirectX7 g3d);
    void dispose();
}
