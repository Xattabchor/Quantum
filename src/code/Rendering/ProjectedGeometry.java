package code.Rendering;

/** Reconstruct homogeneous positions from the engine's already projected data.
 * Keeps portal culling, model animation and the original camera convention.
 * The M3G camera divides x/y by -z; z is used as texture perspective weight,
 * not as a depth-test key (the original renderer uses painter ordering).
 */
public final class ProjectedGeometry {
    private ProjectedGeometry() { }

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
