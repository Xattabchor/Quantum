package javax.microedition.m3g;
public class Light extends Object3D {
    public static final int AMBIENT = 128, DIRECTIONAL = 129, OMNI = 130, SPOT = 131;
    public int mode = DIRECTIONAL, color = 0xffffffff;
    public float intensity = 1f, constant = 1f, linear = 0f, quadratic = 0f;
    public void setMode(int m) { mode = m; }
    public int getMode() { return mode; }
    public void setColor(int c) { color = c; }
    public int getColor() { return color; }
    public void setIntensity(float i) { intensity = i; }
    public float getIntensity() { return intensity; }
    public void setAttenuation(float c, float l, float q) { constant = c; linear = l; quadratic = q; }
    public float getConstantAttenuation() { return constant; }
    public float getLinearAttenuation() { return linear; }
    public float getQuadraticAttenuation() { return quadratic; }
    public void setSpotAngle(float a) {}
    public float getSpotAngle() { return 0f; }
    public void setSpotExponent(float e) {}
    public float getSpotExponent() { return 0f; }
}
