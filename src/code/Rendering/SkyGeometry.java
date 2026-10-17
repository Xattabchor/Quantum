package code.Rendering;

/** Small, allocation-free near/viewport clipper; it never rasterizes pixels.
 * Input vertices are in view space, BEFORE Vertex.project destroys X/Y.
 * Output is homogeneous normalized X/Y, positive W, and interpolated UV.
 */
public final class SkyGeometry {
    private final float[] first = new float[60], second = new float[60];
    public float[] polygon = first;
    public int count;

    public void clip(DirectX7 g, Vertex a, int au, int av, Vertex b, int bu, int bv,
            Vertex c, int cu, int cv, int x1, int y1, int x2, int y2) {
        polygon = first;
        set(0,a,au,av); set(1,b,bu,bv); set(2,c,cu,cv);
        count = 3;
        plane(0,0,-1,0); // rz <= 0, including exact on-plane vertices
        for(int i = 0; i < count; i++) {
            int p = i * 5;
            float z = polygon[p+2], w = g.distY - z;
            // The engine has separate X/Y focal offsets. Do not assume that
            // integer FOV rounding makes distX exactly equal to distY.
            polygon[p] *= (2.0f * g.distX / g.width) * w / (g.distX - z);
            polygon[p+1] *= 2.0f * g.distY / g.height;
            polygon[p+2] = w;
        }
        plane(1,0,2.0f*(g.centreX-x1)/g.width,0);
        plane(-1,0,2.0f*(x2-g.centreX)/g.width,0);
        plane(0,1,2.0f*(y2-g.centreY)/g.height,0);
        plane(0,-1,2.0f*(g.centreY-y1)/g.height,0);
    }

    private void set(int i, Vertex v, int u, int t) {
        int p = i * 5;
        polygon[p] = v.sx; polygon[p+1] = v.sy; polygon[p+2] = v.rz;
        polygon[p+3] = u; polygon[p+4] = t;
    }

    private void plane(float x, float y, float z, float constant) {
        if(count == 0) return;
        float[] source = polygon, target = source == first ? second : first;
        int out = 0;
        for(int i = 0; i < count; i++) {
            int a = ((i + count - 1) % count) * 5, b = i * 5;
            float da = source[a]*x + source[a+1]*y + source[a+2]*z + constant;
            float db = source[b]*x + source[b+1]*y + source[b+2]*z + constant;
            if((da < 0 && db > 0) || (da > 0 && db < 0)) {
                // Always interpolate from the outside endpoint. Opposite edge
                // directions in adjacent triangles then generate identical XYZ.
                int low = da < db ? a : b, high = da < db ? b : a;
                float dl = Math.min(da,db), dh = Math.max(da,db);
                float t = -dl / (dh-dl);
                for(int k = 0; k < 5; k++)
                    target[out*5+k] = source[low+k] + (source[high+k]-source[low+k])*t;
                out++;
            }
            if(db >= 0) {
                System.arraycopy(source,b,target,out*5,5);
                out++;
            }
        }
        count = out;
        polygon = target;
    }
}
