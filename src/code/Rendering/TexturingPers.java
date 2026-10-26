package code.Rendering;
/**
 * M3G-only build: the CPU perspective rasterizer is removed entirely.
 * Only the projection distance constant survives - it feeds native-path
 * fog and sprite math (M3GSceneRenderer, M3GSpriteRenderer, Mode12Fog).
 * @author Roman Lahin
 */
public class TexturingPers {
   public static int move = 150;
}
