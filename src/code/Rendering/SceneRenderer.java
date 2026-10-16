package code.Rendering;

/** Optional rasterizer. No JSR-184 types in this interface or DirectX7. */
public interface SceneRenderer {
    boolean draw(DirectX7 g3d, RenderObject object, Texture texture, boolean fast);
    void finish(DirectX7 g3d);
    void dispose();
}
