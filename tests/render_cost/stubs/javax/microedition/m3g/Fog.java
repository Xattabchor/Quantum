package javax.microedition.m3g;
public class Fog {
    public static final int EXPONENTIAL = 80, LINEAR = 81;
    public int mode = EXPONENTIAL, color = 0;
    public float near = 0f, far = 1f, density = 1f;
    public void setMode(int m) { mode = m; }
    public int getMode() { return mode; }
    public void setLinear(float near, float far) { this.near = near; this.far = far; }
    public float getNearDistance() { return near; }
    public float getFarDistance() { return far; }
    public void setDensity(float d) { density = d; }
    public float getDensity() { return density; }
    public void setColor(int c) { color = c; }
    public int getColor() { return color; }
}
