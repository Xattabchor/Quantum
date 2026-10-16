package code.Rendering;

import java.util.Random;

/** Projection/weight contracts, NOT a GPU rasterization or screenshot test. */
public final class GeometryTest {
    private static void check(boolean ok, String message) { M3GTest.check(ok, message); }
    private static float pack(Vertex[] v, boolean perspective, short[] out, float[] work) {
        return ProjectedGeometry.pack(v, v.length, 160, 120, 150, perspective, out, work);
    }
    private static void exact(Vertex[] v, short[] out, float scale, boolean perspective) {
        for(int i = 0; i < v.length; i++) {
            int q = -out[i * 3 + 2];
            check(q > 0, "nonpositive homogeneous weight");
            check(out[i * 3] == ((long)v[i].sx - 160) * q, "X endpoint moved");
            check(out[i * 3 + 1] == ((long)120 - v[i].sy) * q, "Y endpoint moved");
            float weight = perspective ? 1.0f - v[i].rz / 150.0f : 1;
            check(Math.abs(q * scale - weight) <= weight / 128, "unstable perspective/fog weight");
        }
    }
    public static void main(String[] args) {
        // Old independent XYZ rounding accepted both triangles, but projected A
        // at x=57.9714 vs x=58.4048: a 0.43px crack along their common edge.
        Vertex a = M3GTest.vertex(58,114,-4334), b = M3GTest.vertex(257,-28,-8200);
        Vertex[] left = {a,b,M3GTest.vertex(-68,50,-8276)};
        Vertex[] right = {b,a,M3GTest.vertex(54,338,-14599)};
        short[] p = new short[12], other = new short[12]; float[] work = new float[12];
        for(boolean perspective : new boolean[]{false,true}) {
            float scale = pack(left,perspective,p,work), scale2 = pack(right,perspective,other,work);
            check(scale > 0 && scale2 > 0 && scale != scale2, "shared-edge fixture must use different scales");
            exact(left,p,scale,perspective); exact(right,other,scale2,perspective);
            for(int i = 0; i < 2; i++) {
                int j = 1-i;
                check((long)p[i*3]*other[j*3+2] == (long)other[j*3]*p[i*3+2], "shared X differs");
                check((long)p[i*3+1]*other[j*3+2] == (long)other[j*3+1]*p[i*3+2], "shared Y differs");
            }
        }
        System.out.println("PASS shared-edge regression / exact projected endpoints across independent scales");

        Random random = new Random(184);
        int accepted = 0, fallback = 0;
        for(int n = 0; n < 20000; n++) {
            Vertex[] v = new Vertex[n % 2 + 3];
            for(int i = 0; i < v.length; i++)
                v[i] = M3GTest.vertex(random.nextInt(481)-80,random.nextInt(361)-60,-random.nextInt(20001));
            boolean perspective = n % 3 != 0;
            float scale = pack(v,perspective,p,work);
            if(scale > 0) { exact(v,p,scale,perspective); accepted++; }
            else fallback++;
        }
        check(accepted > 5000 && fallback > 100, "exercise native and fallback geometries");
        Vertex[] v = {M3GTest.vertex(0,0,-100),M3GTest.vertex(320,0,-200),M3GTest.vertex(0,240,1)};
        check(pack(v,true,p,work) == 0, "behind-camera must fall back");
        v[2] = M3GTest.vertex(0,240,Integer.MIN_VALUE);
        check(pack(v,true,p,work) == 0, "extreme depth must not collapse near weights");
        v[2] = M3GTest.vertex(Integer.MAX_VALUE,Integer.MIN_VALUE,-200);
        check(pack(v,true,p,work) == 0, "offscreen integer overflow must fall back");
        v[2] = M3GTest.vertex(20160,120,-200);
        check(pack(v,false,p,work) == 0, "rounded short overflow must fall back");
        check(ProjectedGeometry.pack(left,3,160,120,0,true,p,work)==0,"invalid distance");
        System.out.println("PASS 20000 triangle/quad projection cases / stable weights / extreme fallback");
    }
}
