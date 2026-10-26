package code.Rendering;

import code.utils.Main;

public class SpeedTest {

    /** M3G port of the CPU affine triangle painter: instead of pixel-bashing
     *  g.display, submits one textured triangle through the hardware pipe
     *  via M3GSceneRenderer.paintM3GTriangle. */
    public static void paintM3GTriangle(DirectX7 g3d, Texture texture,
            Vertex a, int au, int av,
            Vertex b, int bu, int bv,
            Vertex c, int cu, int cv) {
        if(g3d.getSceneRenderer() instanceof M3GSceneRenderer) {
            ((M3GSceneRenderer)g3d.getSceneRenderer()).paintM3GTriangle(
                g3d, texture, a, au, av, b, bu, bv, c, cu, cv);
        }
    }

    /** Old CPU affine rasterizer (retained as reference, no callers). */
    /*
    public final static int fp = 12, FP = 1<<fp;
    public final static int fpPosition = 14, FPPosition = 1<<fpPosition;

    static final void paintAffine_0(DirectX7 g3d, Texture texture,
            Vertex a, int au, int av,
            Vertex b, int bu, int bv,
            Vertex c, int cu, int cv) {
        ...
    }
    */
}
