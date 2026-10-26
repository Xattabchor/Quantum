package code.Rendering.Meshes;

import code.Math.Matrix;
import code.Rendering.*;

/** One transactional sky submission. No optional JSR-184 API in this class. */
public final class SkyMesh extends RenderObject {
    public final Mesh mesh;
    public final Matrix matrix = new Matrix();
    public int x1, y1, x2, y2;
    private SkyCoverage coverage;
    private final RenderObjectBuffer fallback = new RenderObjectBuffer();

    public SkyMesh(Mesh mesh) { this.mesh = mesh; }

    public void set(Matrix transform, int left, int top, int right, int bottom) {
        matrix.set(transform);
        x1 = left; y1 = top; x2 = right; y2 = bottom;
    }

    /** A hidden opaque base makes both native and CPU recovery complete. */
    public boolean clearsBackground(DirectX7 g) {
        try {
            if(coverage==null) coverage=new SkyCoverage(mesh);
            return coverage.complete(g,matrix,x1,y1,x2,y2);
        } catch(OutOfMemoryError ex) {coverage=null;return false;}
    }
    public int backgroundColor() {return coverage.color();}

    public boolean isVisible(int left, int top, int right, int bottom) { return true; }

    public void renderFast(DirectX7 g, Texture unused) { render(g, unused); }

    public void render(DirectX7 g, Texture unused) {
        /* CPU whole-mesh fallback removed - M3G sky renderer owns SkyMesh. */
    }
}
