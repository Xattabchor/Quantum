package javax.microedition.m3g;
public class Transform {
    public static int created;
    public float[] matrix = new float[16];
    public Transform() { created++; matrix[0] = matrix[5] = matrix[10] = matrix[15] = 1f; }
    public void set(float[] m) { System.arraycopy(m, 0, matrix, 0, 16); }
    public void get(float[] m) { System.arraycopy(matrix, 0, m, 0, 16); }
    public void setIdentity() { matrix = new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}; }
    public void postRotate(float angle, float ax, float ay, float az) {
        double a = Math.toRadians(angle), c = Math.cos(a), s = Math.sin(a), t = 1 - c;
        float[] r = {
            (float)(t*ax*ax + c), (float)(t*ax*ay + s*az), (float)(t*ax*az - s*ay), 0,
            (float)(t*ax*ay - s*az), (float)(t*ay*ay + c), (float)(t*ay*az + s*ax), 0,
            (float)(t*ax*az + s*ay), (float)(t*ay*az - s*ax), (float)(t*az*az + c), 0,
            0, 0, 0, 1};
        matrix = mul(matrix, r);
    }
    public void postTranslate(float x, float y, float z) {
        matrix[3] += x; matrix[7] += y; matrix[11] += z;
    }
    public void postScale(float x, float y, float z) {
        for (int i = 0; i < 3; i++) { matrix[i] *= x; matrix[4 + i] *= y; matrix[8 + i] *= z; }
    }
    private static float[] mul(float[] a, float[] b) {
        float[] r = new float[16];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
            float v = 0; for (int k = 0; k < 4; k++) v += a[i * 4 + k] * b[k * 4 + j];
            r[i * 4 + j] = v;
        }
        return r;
    }
    public Transform(Transform t) { this(); if (t != null) System.arraycopy(t.matrix, 0, matrix, 0, 16); }
}
