package javax.microedition.m3g;
public class Material {
    public static final int AMBIENT = 1024, DIFFUSE = 2048, EMISSIVE = 4096, SPECULAR = 8192;
    public int ambient = 0x00333333, diffuse = 0xffcccccc, emissive = 0x00000000, specular = 0x00000000;
    public float shininess = 0f;
    public boolean tracking = true;
    public void setColor(int target, int color) {
        if (target == AMBIENT) ambient = color; else if (target == DIFFUSE) diffuse = color;
        else if (target == EMISSIVE) emissive = color; else if (target == SPECULAR) specular = color;
        else throw new IllegalArgumentException("material target");
    }
    public int getColor(int target) {
        if (target == AMBIENT) return ambient; if (target == DIFFUSE) return diffuse;
        if (target == EMISSIVE) return emissive; return specular;
    }
    public void setShininess(float s) { shininess = s; }
    public float getShininess() { return shininess; }
    public void setVertexColorTrackingEnable(boolean e) { tracking = e; }
    public boolean isVertexColorTrackingEnabled() { return tracking; }
}
