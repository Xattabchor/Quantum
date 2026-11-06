package javax.microedition.m3g;
public class Camera extends Object3D {
    public static final int GENERIC = 48, PARALLEL = 49, PERSPECTIVE = 50;
    public int type = GENERIC;
    public float fovy, aspect, near, far, height;
    public float[] projection = new float[16];
    public void setPerspective(float fovy, float aspect, float near, float far) {
        if (fovy <= 0 || fovy >= 180 || aspect <= 0 || near <= 0 || near >= far) throw new IllegalArgumentException("perspective");
        type = PERSPECTIVE; this.fovy = fovy; this.aspect = aspect; this.near = near; this.far = far;
        double f = 1.0 / Math.tan(Math.toRadians(fovy) / 2.0);
        projection[0] = (float) (f / aspect); projection[5] = (float) f;
        projection[10] = -(far + near) / (far - near); projection[11] = -2 * far * near / (far - near);
        projection[14] = -1;
    }
    public void setParallel(float height, float aspect, float near, float far) { type = PARALLEL; this.height = height; this.aspect = aspect; this.near = near; this.far = far; }
    public void setGeneric(Transform transform) { type = GENERIC; if (transform != null) System.arraycopy(transform.matrix, 0, projection, 0, 16); }
    public int getProjection(float[] params) {
        if (params != null) {
            if (type == PARALLEL) { params[0] = height; params[1] = aspect; params[2] = near; params[3] = far; }
            else { params[0] = fovy; params[1] = aspect; params[2] = near; params[3] = far; }
        }
        return type;
    }
}
