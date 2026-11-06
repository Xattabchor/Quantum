package javax.microedition.m3g;
public class PolygonMode {
    public static final int CULL_BACK = 160, CULL_FRONT = 161, CULL_NONE = 162;
    public static final int SHADE_FLAT = 164, SHADE_SMOOTH = 165;
    public static final int WINDING_CCW = 168, WINDING_CW = 169;
    public boolean perspective = true;
    public int cull = CULL_BACK, winding = WINDING_CCW, shading = SHADE_SMOOTH;
    public float offsetFactor, offsetUnits;
    public static int perspectiveCalls;
    public void setCulling(int mode) { cull = mode; }
    public int getCulling() { return cull; }
    public void setWinding(int mode) { winding = mode; }
    public int getWinding() { return winding; }
    public void setShading(int mode) { shading = mode; }
    public int getShading() { return shading; }
    public void setPerspectiveCorrectionEnable(boolean enable) { perspective = enable; perspectiveCalls++; }
    public boolean isPerspectiveCorrectionEnabled() { return perspective; }
    public void setTwoSidedLightingEnable(boolean enable) {}
    public boolean isTwoSidedLightingEnabled() { return false; }
    public void setLocalCameraLightingEnable(boolean enable) {}
    public boolean isLocalCameraLightingEnabled() { return false; }
}
