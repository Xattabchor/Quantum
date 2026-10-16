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
        for(int i = 0; i < count * 3; i++) {
            float value = work[i] / scale;
            positions[i] = (short)(int)(value + (value < 0 ? -0.5f : 0.5f));
        }
        // Do not send an unstable/degenerate quantization to a short-only API.
        for(int i = 0; i < count; i++) {
            float z = positions[i * 3 + 2];
            if(z >= 0) return 0;
            float sx = cx - positions[i * 3] / z;
            float sy = cy + positions[i * 3 + 1] / z;
            if(Math.abs(sx - vertices[i].sx) > 0.75f
                    || Math.abs(sy - vertices[i].sy) > 0.75f) return 0;
        }
        return scale;
    }
}
