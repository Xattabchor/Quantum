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
        // Only selected-M3G recovery gets the same hidden base as native.
        // Original Software and open/transparent/cropped skies are unchanged.
        if(g.getRenderer()==DirectX7.M3G && clearsBackground(g)) g.clearDisplay(backgroundColor());
        // Whole-mesh fallback only: never mix native and CPU sky faces.
        g.transformAndProjectVertices(mesh, matrix);
        try {
            fallback.addRenderObjects(mesh.getPolygons(), mesh.getTexture(), x1, y1, x2, y2);
            fallback.sort(0, fallback.getSize() - 1);
            TMPElement[] items = fallback.getBuffer();
            for(int i = fallback.getSize() - 1; i >= 0; i--)
                g.drawSoftwareObject(items[i].obj,items[i].tex,false);
        } finally {
            fallback.resetTex();
            fallback.reset();
        }
    }
}
