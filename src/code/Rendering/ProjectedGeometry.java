package code.Rendering;

/** Reconstruct homogeneous positions from the engine's already projected data.
 * Keeps portal culling, model animation and the original camera convention.
 * The M3G camera divides x/y by -z; z is used as texture perspective weight,
 * not as a depth-test key (the original renderer uses painter ordering).
 */
public final class ProjectedGeometry {
    private ProjectedGeometry() { }

    /** Fog-only shared binary depth lattice. Scale = 2^shift / move, rather
     * than a unique float for each polygon's screen bounds. Exact screen
     * endpoints and the original <=1/128 eye-weight error bound are retained.
     * If the common lattice is unsuitable, use the original per-polygon pack. */
    public static float packFog(Vertex[] vertices,int count,int cx,int cy,
            int distance,short[] positions,float[] work) {
        if(distance<=0) return 0;
        long max=1;
        for(int i=0;i<count;i++) {
            Vertex v=vertices[i];
            if(v.rz>0) return 0;
            long x=Math.abs((long)v.sx-cx),y=Math.abs((long)v.sy-cy);
            if(x>32767 || y>32767) return 0; // q>=1 cannot fit even the old pack.
            long depth=(long)distance-v.rz;
            max=Math.max(max,Math.max(1L,Math.max(x,y))*depth);
        }
        long limit=30000;int shift=0;
        while(max>limit) {limit<<=1;shift++;}
        long step=1L<<shift;
        float scale=(float)step/distance;
        boolean valid=true;
        for(int i=0;i<count;i++) {
            Vertex v=vertices[i];
            long depth=(long)distance-v.rz;
            long q=(depth+(step>>>1))>>>shift;
            float weight=1.0f-(float)v.rz/distance;
            long x=((long)v.sx-cx)*q,y=((long)cy-v.sy)*q;
            if(q<=0 || q>32767 || x < -32767 || x>32767 || y < -32767 || y>32767
                    || Math.abs(q*scale-weight)>weight/128.0f) {valid=false;break;}
            positions[i*3]=(short)x;positions[i*3+1]=(short)y;positions[i*3+2]=(short)-q;
        }
        if(valid) return scale;
        return pack(vertices,count,cx,cy,distance,true,positions,work);
    }

    public static float pack(Vertex[] vertices, int count, int cx, int cy,
            int distance, boolean perspective, short[] positions, float[] work) {
        if(distance <= 0) return 0;
        float max = 1;
        for(int i = 0; i < count; i++) {
            Vertex v = vertices[i];
            // Behind-camera and near-plane cases keep the original CPU behavior.
            if(v.rz > 0) return 0;
            float w = perspective ? 1.0f - (float)v.rz / distance : 1.0f;
            work[i * 3] = ((float)v.sx - cx) * w;
            work[i * 3 + 1] = ((float)cy - v.sy) * w;
            work[i * 3 + 2] = -w;
            for(int j = 0; j < 3; j++) max = Math.max(max, Math.abs(work[i * 3 + j]));
        }
        float scale = max / 30000.0f;
        // Quantize the homogeneous weight FIRST, then derive X/Y from it.
        // Independent rounding of X/Y/Z moves shared endpoints differently in
        // adjacent polygons (each has its own scale), opening visible cracks.
        // Integer screen offsets times the same integer weight project exactly
        // back to the original endpoints, regardless of the polygon's scale.
        for(int i = 0; i < count; i++) {
            float w = -work[i * 3 + 2];
            int q = (int)(w / scale + 0.5f);
            if(q <= 0 || q > 32767 || Math.abs(q * scale - w) > w / 128.0f) return 0;
            long x = ((long)vertices[i].sx - cx) * q;
            long y = ((long)cy - vertices[i].sy) * q;
            if(x < -32767 || x > 32767 || y < -32767 || y > 32767) return 0;
            positions[i * 3] = (short)x;
            positions[i * 3 + 1] = (short)y;
            positions[i * 3 + 2] = (short)-q;
        }
        return scale;
    }
}
