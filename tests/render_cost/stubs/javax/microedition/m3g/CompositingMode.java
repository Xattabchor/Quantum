package javax.microedition.m3g;
public class CompositingMode {
    public static final int ALPHA = 64, ALPHA_ADD = 65, MODULATE = 66, MODULATE_X2 = 67, REPLACE = 68;
    public int blend = REPLACE;
    public float threshold;
    public boolean alphaWrite = true, colorWrite = true, depthWrite = true, depthTest = false;
    public float offsetFactor, offsetUnits;
    public void setBlending(int mode) { blend = mode; }
    public int getBlending() { return blend; }
    public void setAlphaThreshold(float t) { threshold = t; }
    public float getAlphaThreshold() { return threshold; }
    public void setAlphaWriteEnable(boolean e) { alphaWrite = e; }
    public boolean isAlphaWriteEnabled() { return alphaWrite; }
    public void setColorWriteEnable(boolean e) { colorWrite = e; }
    public boolean isColorWriteEnabled() { return colorWrite; }
    public void setDepthWriteEnable(boolean e) { depthWrite = e; }
    public boolean isDepthWriteEnabled() { return depthWrite; }
    public void setDepthTestEnable(boolean e) { depthTest = e; }
    public boolean isDepthTestEnabled() { return depthTest; }
    public void setDepthOffset(float factor, float units) { offsetFactor = factor; offsetUnits = units; }
    public float getDepthOffsetFactor() { return offsetFactor; }
    public float getDepthOffsetUnits() { return offsetUnits; }
}
